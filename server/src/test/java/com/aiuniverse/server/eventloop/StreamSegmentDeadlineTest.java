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
 * ADR-024 · 流式段总时长上界(worker 线程自己看表,自己掐自己)。
 *
 * <p>⚠️ <b>全部用假时钟,一秒都不睡</b> —— <b>真睡 20 秒的测试本身就是坏测试</b>
 * (它把一条确定性断言换成一次挂钟等待,既慢又不稳)。两条边界用例是<b>纯时钟脚本,完全确定性</b>。
 * {@code MockLlmClient} 一个字不动:它的 {@code Thread.sleep(40)} 是硬编码、没有注入点,
 * <b>造不出「逐 token 可控」的慢流</b>,而假时钟连这个需求都不需要。
 *
 * <p><b>时钟脚本的格数</b>按 {@code TurnDurationAnchorTest.ScriptedClock} 那条 <b>3 + N</b> 不变量算
 * (起点锚点 1 + 每段守卫起点 1 + 每 token 1 + 终点锚点 1),<b>精确不给富余</b>:
 * 单段 1 token ⇒ 4 格;主调用 + 修复发各 1 token ⇒ 6 格。
 *
 * <p>三条变异<b>分别</b>变红(变异验证读的是<b>红用例名</b>,不是失败计数):
 * <ol>
 *   <li>阈值改 {@code Long.MAX_VALUE}(守卫形同不存在)→ {@link #streamOverDeadlineSelfKillsIntoDegrade} <b>单独</b>红;</li>
 *   <li>闭合方向 {@code >} 改 {@code >=} → {@link #exactlyAtDeadlineIsNotKilled} <b>单独</b>红
 *       (19.999 秒那条仍绿,因为它本来就在界内 —— <b>少了这一对,「恰好等于放过」就是一句没人守的话</b>);</li>
 *   <li>段范围由「每段独立」改成「两段共用一个预算」 → {@link #repairSegmentGetsItsOwnBudget} <b>单独</b>红。</li>
 * </ol>
 *
 * <p>⚠️ <b>第 1 条为什么不是「摘掉守卫接线」</b>:那个更直觉的变异<b>同时改变了每回合读时钟的次数</b>,
 * 会把 {@code TurnDurationAnchorTest} 的精确脚本一并弄红 —— <b>那时候红的是格数,不是语义</b>,
 * 隔离性当场丢掉。改阈值则读时钟次数逐次不变,红的只剩语义那一条。
 */
class StreamSegmentDeadlineTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder prompts = new TurnPromptBuilder(new ArchetypeRegistry());

	/** 同 {@code TurnDurationAnchorTest}:按脚本逐次回时刻,<b>脚本耗尽即抛</b>(多读一次当场炸)。 */
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

	/** 每次 {@code streamChat} 吐<b>恰好一个</b> token(脚本格数据此可数)。 */
	private static final class ScriptedLlm implements LlmClient {
		private final Deque<String> responses = new ArrayDeque<>();

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
		return new GameSession("save-deadline", new Engine(world, mapper), actions.deepCopy());
	}

	private static String validTail() {
		return "{\"stateUpdate\":{\"hp\":90,\"san\":85,\"timeline\":\"进店\"},"
				+ "\"triggeredRuleIds\":[],\"discoveredRuleIds\":[],"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"看告示\"},{\"id\":\"B\",\"text\":\"离开\"}],"
				+ "\"ending\":null}";
	}

	private static String validWire() {
		return "灯闪了一下。" + SentinelSplitter.SENTINEL + validTail();
	}

	/** 尾巴结构非法 → 校验不过 → 触发一次修复发(本文件用它把「第二段」造出来)。 */
	private static String wireWithBrokenTail() {
		return "灯闪了一下。" + SentinelSplitter.SENTINEL + "{\"oops\":1}";
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

	// ── 1. 超线 → 自掐 → 落既有的第四条降级路径 ──
	@Test
	void streamOverDeadlineSelfKillsIntoDegrade() {
		ListAppender<ILoggingEvent> logs = attachAppender();
		// 4 格:起点 0 / 守卫起点 0 / token 到达 20_001(已流 20_001ms > 上界)/ 降级终点 20_500。
		serviceWithClock(new ScriptedLlm(validWire()), new ScriptedClock(0L, 0L, 20_001L, 20_500L))
				.execute(session(), "A", new SilentSink());

		List<String> warns = messagesAt(logs, Level.WARN);
		assertThat(warns)
				.as("超线必须自掐,且服务端侧读得出它不是「连接中断」——已知代价 3 那条「分得开」的凭据")
				.anySatisfy(m -> assertThat(m).contains("主调用流中断").contains("自掐降级"));
		assertThat(warns)
				.as("自掐落的是既有第四条降级路径:no-op 回合,不是错误屏")
				.anySatisfy(m -> assertThat(m).contains("no-op 降级落地"));
	}

	// ── 2. 恰好等于上界:放过(闭合方向写死 `>` 才掐)──
	@Test
	void exactlyAtDeadlineIsNotKilled() {
		ListAppender<ILoggingEvent> logs = attachAppender();
		// token 到达时已流恰好 20_000ms —— 等于上界,按 `>` 放过。
		serviceWithClock(new ScriptedLlm(validWire()), new ScriptedClock(0L, 0L, 20_000L, 20_500L))
				.execute(session(), "A", new SilentSink());

		assertThat(messagesAt(logs, Level.INFO))
				.as("恰好等于上界必须放过,回合正常落账")
				.anySatisfy(m -> assertThat(m).contains("落账"));
		assertThat(messagesAt(logs, Level.WARN))
				.as("恰好等于上界不得被掐")
				.noneSatisfy(m -> assertThat(m).contains("自掐降级"));
	}

	// ── 3. 界内(19.999 秒):放过 ──
	@Test
	void justUnderDeadlineIsNotKilled() {
		ListAppender<ILoggingEvent> logs = attachAppender();
		serviceWithClock(new ScriptedLlm(validWire()), new ScriptedClock(0L, 0L, 19_999L, 20_500L))
				.execute(session(), "A", new SilentSink());

		assertThat(messagesAt(logs, Level.INFO))
				.as("界内必须放过")
				.anySatisfy(m -> assertThat(m).contains("落账"));
		assertThat(messagesAt(logs, Level.WARN))
				.as("界内不得被掐")
				.noneSatisfy(m -> assertThat(m).contains("自掐降级"));
	}

	// ── 4. 每段独立(立字 4):修复发有自己的整份预算 ──
	@Test
	void repairSegmentGetsItsOwnBudget() {
		ListAppender<ILoggingEvent> logs = attachAppender();
		// 6 格:起点 0 / 主调用守卫起点 0 / 主 token 19_000(段内 19_000ms,界内)
		//     / 修复发守卫起点 19_000 / 修复 token 38_000(**本段**只走了 19_000ms,界内)/ 终点 38_500。
		// ⚠️ 这组脚本就是「每段独立 vs 两段共用一个预算」的判别式:若共用,修复发那个 token
		// 算出的已流时长是 38_000ms > 上界,回合会被掐成 no-op —— 而那正是立字 4 要避免的**误伤**。
		serviceWithClock(new ScriptedLlm(wireWithBrokenTail(), validTail()),
				new ScriptedClock(0L, 0L, 19_000L, 19_000L, 38_000L, 38_500L))
				.execute(session(), "A", new SilentSink());

		assertThat(messagesAt(logs, Level.INFO))
				.as("修复发必须有自己的整份预算:主调用用掉接近上界,修复发照样跑得完并落账")
				.anySatisfy(m -> assertThat(m).contains("触发一次结构化修复"));
		assertThat(messagesAt(logs, Level.INFO))
				.as("修复成功后回合正常落账,而不是被掐成 no-op")
				.anySatisfy(m -> assertThat(m).contains("落账"));
		assertThat(messagesAt(logs, Level.WARN))
				.as("两段各自独立计时,修复发不得因为主调用慢而被没收预算(立字 4:误伤代价不对称)")
				.noneSatisfy(m -> assertThat(m).contains("自掐降级"));
	}
}
