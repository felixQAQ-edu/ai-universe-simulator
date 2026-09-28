package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;
import com.aiuniverse.server.persistence.SessionStore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-027 刀 1 · 已落地必写盘。不变式:<b>回合已在内存落地 ⇒ 本次 persist 一定会被尝试</b>。
 *
 * <p>判据是一个事实(engine.turn() 在 CAS 之后变没变),不是异常来源 —— 故这里的 executor
 * 替身「先推进引擎、再抛」即可代表 delta / ending SSE 写失败,也代表落地之后任何一处 bug。
 *
 * <p>变异验证(读红用例名,不读计数):
 * <ul>
 *   <li>摘掉已落地分支的 {@code store.persist} → {@code landedThenThrowPersistsAndStaysSilent}、
 *       {@code landedAndEndedThenThrowGoesToEndedAndStaysEnded}、{@code endToEndDeltaWriteFailureKeepsDiskInStepWithMemory} 红;</li>
 *   <li>已落地分支相位写死 AWAITING → 只有 {@code landedAndEndedThenThrowGoesToEndedAndStaysEnded} 红;</li>
 *   <li>判据改成「一律当已落地」→ {@code notLandedThenThrowKeepsTodaysBehavior} 红,另有既有的
 *       {@code SessionPersistenceWiringTest.unexpectedExecutorFailureDoesNotPersist} 同红 —— 它早就守着
 *       「未落地不写盘」这同一件事,不是搭便车。</li>
 * </ul>
 */
class LandedTurnPersistTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final Logger fsmLogger = (Logger) LoggerFactory.getLogger(TurnStateMachine.class);
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void attach() {
		logs = new ListAppender<>();
		logs.start();
		fsmLogger.addAppender(logs);
	}

	@AfterEach
	void detach() {
		fsmLogger.detachAppender(logs);
	}

	/** 记录 persist 次数与写盘瞬间引擎回合号(= 「盘上」回合号)。 */
	private static final class RecordingStore implements SessionStore {
		final List<Integer> persistedTurns = new ArrayList<>();

		@Override
		public void persist(GameSession session) {
			persistedTurns.add(session.engine().turn());
		}

		@Override
		public List<GameSession> loadAll() {
			return List.of();
		}
	}

	private static final class RecordingSink implements TurnEventSink {
		final List<String> errors = new ArrayList<>();

		@Override public void narrative(String text) { }
		@Override public void delta(ObjectNode d) { }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String code, String message) { errors.add(code); }
	}

	private GameSession session() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		ArrayNode actions = world.putArray("availableActions");
		actions.addObject().put("id", "A").put("text", "查看告示");
		actions.addObject().put("id", "B").put("text", "离开");
		return new GameSession("save-27", new Engine(world, mapper), actions);
	}

	private List<ILoggingEvent> warns() {
		return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
	}

	// ── 已落地后抛 ──────────────────────────────────────────────────────

	@Test
	void landedThenThrowPersistsAndStaysSilent() {
		GameSession s = session();
		RecordingStore store = new RecordingStore();
		RecordingSink sink = new RecordingSink();
		TurnStateMachine fsm = new TurnStateMachine((ss, a, k) -> {
			ss.engine().applyNoOp("灯闪了一下。", a); // 内存落地 N → N+1
			throw new IllegalStateException("SSE 发送失败(客户端可能已断开)"); // 送达失败
		}, store);

		fsm.submitAction(s, "A", sink);

		assertThat(store.persistedTurns).as("已落地 ⇒ 补写盘一次,且写的是落地后的回合").containsExactly(1);
		assertThat(s.phase()).hasValue(TurnPhase.AWAITING_ACTION);
		assertThat(sink.errors).as("回合没有失败 —— 不发 internal_error").isEmpty();
		assertThat(warns()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
				.contains("save=save-27").contains("turn 0->1").contains("补写盘"));
	}

	// ── 已落地且已收束后抛(结局回合 ending 写失败)────────────────────────

	@Test
	void landedAndEndedThenThrowGoesToEndedAndStaysEnded() {
		GameSession s = session();
		RecordingStore store = new RecordingStore();
		RecordingSink sink = new RecordingSink();
		AtomicInteger calls = new AtomicInteger();
		TurnStateMachine fsm = new TurnStateMachine((ss, a, k) -> {
			calls.incrementAndGet();
			ObjectNode parsed = mapper.createObjectNode();
			parsed.put("narrative", "灯灭了。");
			parsed.putObject("stateUpdate").put("hp", 0); // 致命轴触底 → 引擎强制 ended
			ss.engine().apply(parsed, a);
			throw new IllegalStateException("ending 写失败");
		}, store);

		fsm.submitAction(s, "A", sink);

		assertThat(s.engine().status()).isEqualTo("ended");
		assertThat(s.phase()).as("相位按引擎事实定,不得被放回 AWAITING").hasValue(TurnPhase.ENDED);
		assertThat(store.persistedTurns).containsExactly(1);
		assertThat(sink.errors).isEmpty();

		// 续一步:再点(executor 不再被调用),回合不推进、不再写盘。
		RecordingSink again = new RecordingSink();
		fsm.submitAction(s, "A", again);
		assertThat(calls).as("已收束的世界不会再跑一回合").hasValue(1);
		assertThat(s.engine().turn()).isEqualTo(1);
		assertThat(store.persistedTurns).containsExactly(1);
		assertThat(again.errors).containsExactly("busy");
		assertThat(s.phase()).hasValue(TurnPhase.ENDED);
	}

	// ── 未落地就抛(既有行为的守护)──────────────────────────────────────

	@Test
	void notLandedThenThrowKeepsTodaysBehavior() {
		GameSession s = session();
		RecordingStore store = new RecordingStore();
		RecordingSink sink = new RecordingSink();
		TurnStateMachine fsm = new TurnStateMachine((ss, a, k) -> {
			throw new IllegalStateException("boom"); // 叙事中途断流 / 意外故障:引擎未动
		}, store);

		fsm.submitAction(s, "A", sink);

		assertThat(store.persistedTurns).as("未落地不写盘(盘上仍是上一个完整回合)").isEmpty();
		assertThat(s.phase()).hasValue(TurnPhase.AWAITING_ACTION);
		assertThat(sink.errors).containsExactly("internal_error");
		assertThat(warns()).as("未落地分支维持现状,不补日志").isEmpty();
	}

	// ── 端到端:真 EventLoopService + 脚本化 LLM + delta 时抛的 sink ─────────

	/**
	 * ⚠️ ADR-027 测试面写的是「真 EventLoopService + {@code MockLlmClient}」。实际未用 MockLlmClient:
	 * 它把<b>整份 prompt</b> 包一层逐字吐回、每字硬编码 {@code Thread.sleep(40)}(数千字 → 数分钟),
	 * 且吐回内容无哨兵 → 会走修复再降级。改用与 {@code EventLoopServiceTest} 同形的脚本化 LLM ——
	 * 被测的是「真 EventLoopService 在 delta 写失败时,内存与盘上的回合号一致」,LLM 替身不在被测面上。
	 */
	@Test
	void endToEndDeltaWriteFailureKeepsDiskInStepWithMemory() {
		LlmClient llm = new LlmClient() {
			final Deque<String> responses = new ArrayDeque<>(List.of(
					"你走进便利店,荧光灯闪烁。" + SentinelSplitter.SENTINEL
							+ "{\"stateUpdate\":{\"hp\":90,\"san\":85,\"timeline\":\"进店\"},"
							+ "\"triggeredRuleIds\":[],\"discoveredRuleIds\":[],"
							+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"看告示\"},{\"id\":\"B\",\"text\":\"离开\"}],"
							+ "\"ending\":null}"));

			@Override
			public void streamChat(ChatRequest request, TokenStream sink) {
				String r = responses.poll();
				if (r == null) {
					throw new LlmException("脚本耗尽");
				}
				sink.onToken(r);
			}
		};
		// 形同 SseTurnEventSink:emitter.send 抛 IOException → 转 IllegalStateException。
		TurnEventSink deltaFails = new TurnEventSink() {
			@Override public void narrative(String text) { }
			@Override public void delta(ObjectNode d) { throw new IllegalStateException("SSE 发送失败"); }
			@Override public void ending(ObjectNode e) { }
			@Override public void error(String code, String message) { }
		};
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.2").put("mode", "single");
		world.putArray("archetypes").add("rules_creepy");
		world.putObject("world").put("title", "雨夜便利店").put("background", "...")
				.put("dangerLevel", "high").put("tone", "瘆人");
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		ArrayNode actions = world.putArray("availableActions");
		actions.addObject().put("id", "A").put("text", "查看告示");
		GameSession s = new GameSession("save-e2e", new Engine(world, mapper), actions.deepCopy());
		RecordingStore store = new RecordingStore();
		EventLoopService service = new EventLoopService(llm, new TurnPromptBuilder(new ArchetypeRegistry()), mapper);

		new TurnStateMachine(service, store).submitAction(s, "A", deltaFails);

		assertThat(s.engine().turn()).isEqualTo(1);
		assertThat(store.persistedTurns).as("盘上回合号 == 内存回合号").containsExactly(s.engine().turn());
		assertThat(s.phase()).hasValue(TurnPhase.AWAITING_ACTION);
	}
}
