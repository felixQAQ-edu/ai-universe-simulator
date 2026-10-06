package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnStateMachine;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.llm.TokenStream;
import com.aiuniverse.server.persistence.SessionStore;
import com.aiuniverse.server.quota.QuotaGate;
import com.aiuniverse.server.web.GameController;
import com.aiuniverse.server.web.TurnAdmission;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-030 实施刀 1 · world-gen 流式段时限(决策 4)+ {@code durMs} 日志(已决 1)。
 *
 * <p>从 {@code GameController.init} 端到端走:时限过线 → 502 {@code world_gen_failed} 且<b>不建 session</b>
 * (以 {@link SessionStore#persist} 调用数 = 0 为凭据:{@code sessions.create} 必落盘)。
 *
 * <p><b>时钟脚本耗尽即抛</b>:读时钟次数可数 —— generate 起点 1 + 每段守卫起点 1 + 每 token 1 + 终点 1。
 * 每次 {@code streamChat} 吐<b>恰好一个</b> token。阈值取默认 180 000 ms。
 */
class WorldGenSegmentDeadlineTest {

	private static final long DEADLINE = WorldGenProperties.DEFAULT_SEGMENT_DEADLINE_MS;

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final Logger wgLogger = (Logger) LoggerFactory.getLogger(WorldGenService.class);
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	{
		logs.start();
		wgLogger.addAppender(logs);
	}

	@AfterEach
	void detach() {
		wgLogger.detachAppender(logs);
	}

	private static final class ScriptedClock extends Clock {
		private final Deque<Instant> ticks = new ArrayDeque<>();

		ScriptedClock(long... epochMillis) {
			for (long ms : epochMillis) {
				ticks.add(Instant.ofEpochMilli(ms));
			}
		}

		@Override
		public Instant instant() {
			Instant next = ticks.poll();
			if (next == null) {
				throw new IllegalStateException("时钟脚本耗尽:实现读时钟的次数超出预期");
			}
			return next;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	/** 每次 streamChat 吐恰好一个 token,随后回一个 usage 块(第 n 发的 promptTokens = n,便于认出是哪一段被记账)。 */
	private static final class ScriptedLlm implements LlmClient {
		private final Deque<String> responses = new ArrayDeque<>();
		private int calls;

		ScriptedLlm(String... bodies) {
			for (String b : bodies) {
				responses.add(b);
			}
		}

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			String body = responses.poll();
			if (body == null) {
				throw new LlmException("脚本耗尽");
			}
			calls++;
			sink.onToken(body);
			sink.onUsage(new LlmUsage(calls, 1, calls + 1));
		}
	}

	/** 额度放行,记下每次 record 收到的 usage。 */
	private static final class RecordingQuota implements QuotaGate {
		final List<LlmUsage> recorded = new ArrayList<>();

		@Override
		public Decision checkInit(ClientKey key) {
			return Decision.ALLOW;
		}

		@Override
		public Decision checkTurn(ClientKey key) {
			return Decision.ALLOW;
		}

		@Override
		public void record(LlmUsage usage) {
			recorded.add(usage);
		}
	}

	private static final class CountingStore implements SessionStore {
		int persists;

		@Override
		public void persist(GameSession session) {
			persists++;
		}

		@Override
		public List<GameSession> loadAll() {
			return List.of();
		}
	}

	private final RecordingQuota quota = new RecordingQuota();
	private final CountingStore store = new CountingStore();

	private ResponseEntity<?> init(LlmClient llm, Clock clock) {
		WorldGenService worldGen = new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper, quota,
				clock, DEADLINE);
		GameSessionManager sessions = new GameSessionManager(mapper, store);
		GameInitService initService = new GameInitService(worldGen, sessions, registry, mapper);
		GameController controller = new GameController(sessions, new TurnStateMachine((s, a, sink) -> null),
				initService, quota, new TurnAdmission(1, Runnable::run));
		return controller.init(new GameController.InitRequest("rules_creepy", null), new MockHttpServletRequest());
	}

	private static String validWorld() {
		return """
				{"schemaVersion":"0.2","mode":"single","archetypes":["rules_creepy"],
				 "world":{"title":"雨夜便利店","background":"凌晨零点,你接替夜班。","dangerLevel":"high","tone":"压抑"},
				 "character":{"attributes":{"hp":80,"san":70},"traits":["警觉"],"inventory":["手电筒"]},
				 "rules":[{"id":1,"content":"不要直视监控","isTrue":true,"hiddenLogic":"直视则 san-10","discovered":false},
				          {"id":2,"content":"红雨衣顾客别收现金","isTrue":false,"hiddenLogic":"假规则,收了无事","discovered":false}],
				 "endings":[{"id":"survive_dawn","title":"撑到天亮","description":"你活到六点。","condition":"撑到 06:00","reached":false},
				            {"id":"lost_mind","title":"失心","description":"你疯了。","condition":"san<=0","reached":false}],
				 "availableActions":[{"id":"A","text":"查看告示","hint":""},{"id":"B","text":"原地不动","hint":""}],
				 "openingNarrative":"荧光灯忽明忽暗,墙上的告示泛黄。"}
				""";
	}

	/** 合法 JSON 但过不了 validateWorld → 触发一次修复(本文件用它造出「第二段」)。 */
	private static final String INVALID_WORLD = "{\"oops\":1}";

	private static String errorCode(ResponseEntity<?> resp) {
		@SuppressWarnings("unchecked")
		Map<String, Map<String, String>> body = (Map<String, Map<String, String>>) resp.getBody();
		return body.get("error").get("code");
	}

	private List<String> infoMessages() {
		return logs.list.stream().filter(e -> e.getLevel() == Level.INFO).map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	// ── 1. 主调用某个 token 到达时已过线 → 502 world_gen_failed,不建 session ──
	@Test
	void mainCallTokenPastDeadlineYields502AndNoSession() {
		// 4 格:generate 起点 0 / 主调用守卫起点 0 / token 到达 180_001(> 上界)/ 失败终点 180_500。
		ResponseEntity<?> resp = init(new ScriptedLlm(validWorld()),
				new ScriptedClock(0L, 0L, DEADLINE + 1, DEADLINE + 500));

		assertThat(resp.getStatusCode().value()).isEqualTo(502);
		assertThat(errorCode(resp)).isEqualTo("world_gen_failed");
		assertThat(store.persists).as("过线必须不建 session(sessions.create 必落盘,故落盘次数 = 0)").isZero();
	}

	// ── 2. 恰好等于上界:放过(闭合方向 `>` 才掐)──
	@Test
	void exactlyAtDeadlineIsNotKilled() {
		ResponseEntity<?> resp = init(new ScriptedLlm(validWorld()),
				new ScriptedClock(0L, 0L, DEADLINE, DEADLINE + 500));

		assertThat(resp.getStatusCode().value()).as("恰好等于上界必须放过").isEqualTo(200);
		assertThat(store.persists).isEqualTo(1);
	}

	// ── 3. 主调用成功、修复调用超时 → 502、无 session;只有主调用记账 ──
	@Test
	void repairCallTimeoutYields502NoSessionAndOnlyMainCallBilled() {
		// 6 格:起点 0 / 主守卫 0 / 主 token 1_000 / 修复守卫 1_000 / 修复 token 1_000+上界+1 / 终点。
		ResponseEntity<?> resp = init(new ScriptedLlm(INVALID_WORLD, validWorld()),
				new ScriptedClock(0L, 0L, 1_000L, 1_000L, 1_000L + DEADLINE + 1, 1_000L + DEADLINE + 500));

		assertThat(resp.getStatusCode().value()).isEqualTo(502);
		assertThat(errorCode(resp)).isEqualTo("world_gen_failed");
		assertThat(store.persists).isZero();
		assertThat(quota.recorded)
				.as("主调用记账一次(promptTokens=1 认出是第一发);修复段被掐在 usage 之前,不记账(ADR-030 已知代价 5)")
				.containsExactly(new LlmUsage(1, 1, 2));
	}

	// ── 4. 修复段有自己独立的预算 ──
	@Test
	void repairSegmentGetsItsOwnBudget() {
		// 主调用用掉 179_000(界内),修复段从 179_000 起又走 179_000 —— 本段界内。
		// ⚠️ 若两段共用一个预算,修复 token 算出 358_000 > 上界,开局会被掐成 502。
		long t = DEADLINE - 1_000;
		ResponseEntity<?> resp = init(new ScriptedLlm(INVALID_WORLD, validWorld()),
				new ScriptedClock(0L, 0L, t, t, 2 * t, 2 * t + 500));

		assertThat(resp.getStatusCode().value()).as("修复段必须有自己的整份预算").isEqualTo(200);
		assertThat(store.persists).isEqualTo(1);
	}

	// ── 5. durMs 日志 · 成功终点(走了修复:repaired=true)──
	@Test
	void successPathLogsDurMsAndRepairedFlag() {
		ResponseEntity<?> resp = init(new ScriptedLlm(INVALID_WORLD, validWorld()),
				new ScriptedClock(1_000L, 1_000L, 2_000L, 2_000L, 3_000L, 4_321L));

		assertThat(resp.getStatusCode().value()).isEqualTo(200);
		assertThat(infoMessages())
				.as("成功终点一行 INFO,durMs = 终点 − 起点 = 3_321,repaired=true")
				.contains("[world-gen] archetypes=[rules_creepy] 成功 durMs=3321 repaired=true");
	}

	// ── 6. durMs 日志 · 失败终点(主调用本身失败,未走修复)──
	@Test
	void failurePathLogsDurMs() {
		LlmClient failing = (request, sink) -> {
			throw new LlmException("上游 402");
		};
		// 3 格:起点 500 / 主守卫 500 / 失败终点 1_734(无 token)。
		ResponseEntity<?> resp = init(failing, new ScriptedClock(500L, 500L, 1_734L));

		assertThat(resp.getStatusCode().value()).isEqualTo(502);
		assertThat(infoMessages())
				.as("失败终点一行 INFO,durMs = 1_234,repaired=false")
				.anySatisfy(m -> assertThat(m)
						.startsWith("[world-gen] archetypes=[rules_creepy] 失败 durMs=1234 repaired=false"));
	}
}
