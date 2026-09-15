package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;
import com.aiuniverse.server.quota.QuotaGate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 层 1 第 4 条 · 回合总耗时锚点(一对:池线程开跑 → 回合结束)。
 *
 * <p><b>消费方写死</b>:这一对锚点的读者是<b>层 1 第 2 条「超时那一刀」</b>
 * (ADR-022 已知代价 3:名额被长期占用<b>无时间上界</b>,要定一个超时值今天只能拍脑袋),
 * <b>不是统计脚本</b>——后者维持冻结。本文件只钉<b>耗时这一个维度</b>,不顺手断言别的字段。
 *
 * <p>三条守护各自独立变红(变异验证读的是<b>红用例名</b>,不是失败计数):
 * <ol>
 *   <li>摘掉 {@code settle()} 那处 → {@link #settlePathLogsTurnDurationFromInjectedClock};</li>
 *   <li>摘掉 {@code degrade()} 那处 → {@link #degradePathAlsoLogsTurnDuration}
 *       ——⚠️ <b>少了这一条,「两条路径都要打」就是一句空话</b>:降级回合恰恰是最可能耗时异常的
 *       那一类(流中断 / 修复仍败),漏掉它等于专门漏掉要找的样本;</li>
 *   <li>把注入的 {@code Clock} 换成内联读时钟 → 上面两条<b>同时</b>变红。</li>
 * </ol>
 *
 * <p>⚠️ <b>第 3 条能成立,全靠假时钟造出一个可预期的固定时长</b>:真实耗时是变量,
 * 断言就只能写成「&gt; 0」,而<b>那在任何实现下都绿</b>——与 {@code 0 == 0} 同形
 * (ADR-023 那次夹具十二处全传 {@code TurnRequest(0, …)} 的同一个坑)。
 */
class TurnDurationAnchorTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder prompts = new TurnPromptBuilder(new ArchetypeRegistry());

	/**
	 * 按脚本逐次回时刻的假时钟。<b>脚本耗尽即抛</b>——那同时钉住「一个回合恰好读两次时钟」:
	 * 多读一次会当场炸,而不是悄悄给出一个看着合理的数。
	 */
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

	private static class ScriptedLlm implements LlmClient {
		final Deque<String> responses = new ArrayDeque<>();

		void script(String... full) {
			for (String r : full) {
				responses.add(r);
			}
		}

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			String body = responses.poll();
			if (body == null) {
				throw new LlmException("脚本耗尽");
			}
			sink.onToken(body);
		}
	}

	private static final class SilentSink implements TurnEventSink {
		@Override public void narrative(String text) { }
		@Override public void delta(ObjectNode d) { }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String code, String msg) { }
	}

	private GameSession session() {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.2").put("mode", "single");
		world.putArray("archetypes").add("rules_creepy");
		ObjectNode w = world.putObject("world");
		w.put("title", "雨夜便利店").put("background", "...").put("dangerLevel", "high").put("tone", "瘆人");
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		ObjectNode r1 = world.putArray("rules").addObject();
		r1.put("id", 1).put("content", "午夜不可照镜").put("isTrue", true)
				.put("hiddenLogic", "照镜触发镜中怪").put("discovered", false);
		world.putArray("endings").addObject().put("id", "survive_dawn").put("title", "活到天亮")
				.put("condition", "撑到 06:00").put("reached", false);
		ArrayNode actions = world.putArray("availableActions");
		actions.addObject().put("id", "A").put("text", "查看告示");
		actions.addObject().put("id", "B").put("text", "离开");
		return new GameSession("save-dur", new Engine(world, mapper), actions.deepCopy());
	}

	private static String validWire() {
		return "灯闪了一下。" + SentinelSplitter.SENTINEL
				+ "{\"stateUpdate\":{\"hp\":90,\"san\":85,\"timeline\":\"进店\"},"
				+ "\"triggeredRuleIds\":[],\"discoveredRuleIds\":[],"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"看告示\"},{\"id\":\"B\",\"text\":\"离开\"}],"
				+ "\"ending\":null}";
	}

	private ListAppender<ILoggingEvent> attachAppender() {
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EventLoopService.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		return logs;
	}

	private EventLoopService serviceWithClock(LlmClient llm, Clock clock) {
		return new EventLoopService(llm, prompts, mapper, QuotaGate.NOOP, new ArchetypeRegistry(), clock);
	}

	private static List<String> messagesAt(ListAppender<ILoggingEvent> logs, Level level) {
		return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	// ── 1. 正常结算路径:per-turn INFO 带回合总耗时 ──
	@Test
	void settlePathLogsTurnDurationFromInjectedClock() {
		ListAppender<ILoggingEvent> logs = attachAppender();
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(validWire());

		// 起点 1_000_000 → 终点 1_001_234 ⇒ 期望 durMs=1234(可预期的固定时长,见类注释)。
		serviceWithClock(llm, new ScriptedClock(1_000_000L, 1_001_234L))
				.execute(session(), "A", new SilentSink());

		assertThat(messagesAt(logs, Level.INFO))
				.as("正常结算那条 per-turn INFO 必须带回合总耗时,且是注入时钟算出的值")
				.anySatisfy(m -> assertThat(m).contains("落账").contains("durMs=1234"));
	}

	// ── 2. 降级路径:no-op 降级 WARN 同样带回合总耗时(约束 (b) 的守护本体)──
	@Test
	void degradePathAlsoLogsTurnDuration() {
		ListAppender<ILoggingEvent> logs = attachAppender();
		LlmClient failing = (request, sink) -> {
			sink.onToken("灯闪了一下,然后");
			throw new LlmException("连接中断");
		};

		serviceWithClock(failing, new ScriptedClock(2_000_000L, 2_004_321L))
				.execute(session(), "A", new SilentSink());

		assertThat(messagesAt(logs, Level.WARN))
				.as("降级回合最可能耗时异常,它那条 WARN 必须同样带回合总耗时——否则分母漏掉的正是要找的样本")
				.anySatisfy(m -> assertThat(m).contains("no-op 降级落地").contains("durMs=4321"));
	}
}
