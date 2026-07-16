package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ④ EventLoopService 全矩阵(mock {@link TokenStream},零真实 API)。覆盖 SSE 时序、消毒、
 * 一次修复、保守 no-op、叙事非法降级、兜底结局接线(规格 §3/§4/§5/§6)。
 */
class EventLoopServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder prompts = new TurnPromptBuilder(new ArchetypeRegistry());

	// ── 脚本化 LLM:每次 streamChat 弹出下一段 token 序列喂 sink,并记录请求(断言次数/json_object/含错误)──
	private static class ScriptedLlm implements LlmClient {
		final Deque<List<String>> responses = new ArrayDeque<>();
		final List<ChatRequest> requests = new ArrayList<>();

		void script(String... fullResponses) {
			for (String r : fullResponses) {
				responses.add(List.of(r)); // 整段一 token(切分器单测已覆盖跨 chunk)
			}
		}

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			requests.add(request);
			List<String> toks = responses.poll();
			if (toks == null) {
				throw new LlmException("脚本耗尽");
			}
			toks.forEach(sink::onToken);
		}
	}

	private static final class RecordingSink implements TurnEventSink {
		final List<String> order = new ArrayList<>();
		final StringBuilder narrative = new StringBuilder();
		ObjectNode delta;
		ObjectNode ending;
		String errorCode;

		@Override public void narrative(String text) { order.add("narrative"); narrative.append(text); }
		@Override public void delta(ObjectNode d) { order.add("delta"); delta = d; }
		@Override public void ending(ObjectNode e) { order.add("ending"); ending = e; }
		@Override public void error(String code, String msg) { order.add("error"); errorCode = code; }
	}

	/** 世界根:hp/san=100,规则 1 带 hiddenLogic/isTrue(消毒目标),结局含 san 触底条目 lost_mind。 */
	private GameSession session() {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.2").put("mode", "single");
		world.putArray("archetypes").add("rules_creepy");
		ObjectNode w = world.putObject("world");
		w.put("title", "雨夜便利店").put("background", "...").put("dangerLevel", "high").put("tone", "瘆人");
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		ArrayNode rules = world.putArray("rules");
		ObjectNode r1 = rules.addObject();
		r1.put("id", 1).put("content", "午夜不可照镜").put("isTrue", true)
				.put("hiddenLogic", "照镜触发镜中怪").put("discovered", false);
		ArrayNode endings = world.putArray("endings");
		endings.addObject().put("id", "survive_dawn").put("title", "活到天亮")
				.put("condition", "撑到 06:00").put("reached", false);
		endings.addObject().put("id", "lost_mind").put("title", "失心")
				.put("condition", "san<=0 时丧失心智").put("description", "你再也分不清镜里镜外").put("reached", false);
		ArrayNode actions = world.putArray("availableActions");
		actions.addObject().put("id", "A").put("text", "查看告示");
		actions.addObject().put("id", "B").put("text", "离开");
		Engine eng = new Engine(world, mapper);
		return new GameSession("save-x", eng, actions.deepCopy());
	}

	private String wire(String narrative, String tailJson) {
		return narrative + SentinelSplitter.SENTINEL + tailJson;
	}

	private String validTail(int hp, int san, String ending) {
		return "{\"stateUpdate\":{\"hp\":" + hp + ",\"san\":" + san + ",\"timeline\":\"进店\"},"
				+ "\"triggeredRuleIds\":[1],\"discoveredRuleIds\":[1],"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"看告示\"},{\"id\":\"B\",\"text\":\"离开\"}],"
				+ "\"ending\":" + ending + "}";
	}

	// ── 0. usage 观测(成本闸门读数):有 usage 记 INFO,无(mock)静默 ──
	@Test
	void logsUsageInfoWhenProviderReportsItAndStaysSilentOtherwise() {
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EventLoopService.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
				new ch.qos.logback.core.read.ListAppender<>();
		logs.start();
		logger.addAppender(logs);

		// 有 usage:主调用后一条 INFO(与 per-turn INFO 同层)。
		ScriptedLlm withUsage = new ScriptedLlm() {
			@Override
			public void streamChat(ChatRequest request, TokenStream sink) {
				super.streamChat(request, sink);
				sink.onUsage(new com.aiuniverse.server.llm.LlmUsage(500, 120, 620));
			}
		};
		withUsage.script(wire("灯闪了一下。", validTail(90, 85, "null")));
		new EventLoopService(withUsage, prompts, mapper).execute(session(), "A", new RecordingSink());
		assertThat(logs.list).anySatisfy(e -> {
			assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
			assertThat(e.getFormattedMessage()).contains("usage 主调用 prompt=500 completion=120 total=620");
		});

		// 无 usage(mock 形态):静默,不出 usage 行、不告警。
		logs.list.clear();
		ScriptedLlm noUsage = new ScriptedLlm();
		noUsage.script(wire("灯又闪了一下。", validTail(90, 85, "null")));
		new EventLoopService(noUsage, prompts, mapper).execute(session(), "A", new RecordingSink());
		assertThat(logs.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("usage"));
	}

	// ── 1. happy path:SSE 时序 narrative → delta;消毒;数值落账 ──
	@Test
	void happyPathOrdersNarrativeThenSanitizedDelta() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(wire("你走进便利店,荧光灯闪烁。", validTail(90, 85, "null")));
		GameSession s = session();
		RecordingSink sink = new RecordingSink();

		TurnResult r = new EventLoopService(llm, prompts, mapper).execute(s, "A", sink);

		assertThat(r.ended()).isFalse();
		assertThat(llm.requests).hasSize(1); // 无修复
		assertThat(sink.order).containsExactly("narrative", "delta");
		assertThat(sink.narrative.toString()).isEqualTo("你走进便利店,荧光灯闪烁。");
		assertThat(sink.delta.get("turn").asInt()).isEqualTo(1);
		assertThat(sink.delta.get("status").asString()).isEqualTo("ongoing");
		assertThat(sink.delta.get("hp").asInt()).isEqualTo(90);
		assertThat(sink.delta.get("san").asInt()).isEqualTo(85);
		// 消毒:discoveredRules 只带 id+content,整个 delta 串无隐藏字段。
		assertThat(sink.delta.get("discoveredRules").get(0).get("content").asString()).isEqualTo("午夜不可照镜");
		String deltaStr = mapper.writeValueAsString(sink.delta);
		assertThat(deltaStr).doesNotContain("hiddenLogic").doesNotContain("isTrue");
		assertThat(s.engine().hp()).isEqualTo(90);
	}

	// ── 2. 一次修复:tail 校验失败 → 修复(json_object,含校验错误)→ 同一 N 回灌 → apply ──
	@Test
	void invalidTailTriggersExactlyOneRepairKeepingSameNarrative() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(
				wire("镜子里有东西在动。", validTail(150, 85, "null")), // hp 越界 → 校验失败
				validTail(70, 80, "null"));                           // 修复发:纯尾巴 JSON
		GameSession s = session();
		RecordingSink sink = new RecordingSink();

		TurnResult r = new EventLoopService(llm, prompts, mapper).execute(s, "A", sink);

		assertThat(r.ended()).isFalse();
		assertThat(llm.requests).as("恰一次修复").hasSize(2);
		assertThat(llm.requests.get(1).jsonObject()).as("修复发开回 json_object").isTrue();
		assertThat(llm.requests.get(1).prompt()).as("修复 prompt 含校验错误").contains("超出范围");
		// 叙事不重发、不被改:只在主调用流出一次。
		assertThat(sink.order).containsExactly("narrative", "delta");
		assertThat(sink.narrative.toString()).isEqualTo("镜子里有东西在动。");
		// 落账用修复后的值。
		assertThat(s.engine().hp()).isEqualTo(70);
		assertThat(s.engine().san()).isEqualTo(80);
	}

	// ── 3. 保守 no-op:修复仍败 → turn++、hp/san 不动、仍发 delta(复用动作)──
	@Test
	void repairAlsoFailsDegradesToConservativeNoOp() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(
				wire("墙在渗水。", validTail(150, 85, "null")), // 主调用非法
				validTail(150, 85, "null"));                   // 修复也非法(hp 越界)
		GameSession s = session();
		RecordingSink sink = new RecordingSink();

		TurnResult r = new EventLoopService(llm, prompts, mapper).execute(s, "A", sink);

		assertThat(r.ended()).isFalse();
		assertThat(llm.requests).hasSize(2);
		assertThat(s.engine().turn()).as("turn++").isEqualTo(1);
		assertThat(s.engine().hp()).as("hp 不脏写").isEqualTo(100);
		assertThat(s.engine().san()).as("san 不脏写").isEqualTo(100);
		assertThat(sink.order).containsExactly("narrative", "delta"); // 已流叙事 + 降级 delta
		assertThat(sink.delta.get("availableActions")).hasSize(2);    // 复用上一组
	}

	// ── 4. 叙事非法(回灌后空)→ 不修复,直接 no-op(§6.6)──
	@Test
	void blankNarrativeShortCircuitsToNoOpWithoutRepair() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(wire("", validTail(40, 30, "null"))); // 叙事空
		GameSession s = session();
		RecordingSink sink = new RecordingSink();

		new EventLoopService(llm, prompts, mapper).execute(s, "A", sink);

		assertThat(llm.requests).as("不触发修复").hasSize(1);
		assertThat(sink.order).doesNotContain("narrative");
		assertThat(sink.order).contains("delta");
		assertThat(s.engine().turn()).isEqualTo(1);
		assertThat(s.engine().hp()).isEqualTo(100); // 不脏写
	}

	// ── 5. 兜底结局接线:san≤0 且 AI 无 ending → 引擎兜 lost_mind,service 发 ending 事件 ──
	@Test
	void bottomedOutSanWiresFallbackEndingEvent() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(wire("镜中的你笑了,而你没有。", validTail(60, 0, "null"))); // san=0,AI 未给结局
		GameSession s = session();
		RecordingSink sink = new RecordingSink();

		TurnResult r = new EventLoopService(llm, prompts, mapper).execute(s, "A", sink);

		assertThat(r.ended()).isTrue();
		assertThat(sink.order).containsExactly("narrative", "delta", "ending");
		assertThat(sink.ending.get("id").asString()).isEqualTo("lost_mind"); // condition 提及 san
		assertThat(sink.ending.get("title").asString()).isEqualTo("失心");
		assertThat(s.engine().status()).isEqualTo("ended");
	}

	// ── 6. 主调用流中断 → 保守 no-op(已流出的部分叙事当氛围)──
	@Test
	void streamFailureMidGenerationDegrades() {
		LlmClient failing = (req, sink) -> {
			sink.onToken("灯突然灭了——"); // 已流出一段
			throw new LlmException("流中断");
		};
		GameSession s = session();
		RecordingSink sink = new RecordingSink();

		TurnResult r = new EventLoopService(failing, prompts, mapper).execute(s, "A", sink);

		assertThat(r.ended()).isFalse();
		assertThat(sink.narrative.toString()).isEqualTo("灯突然灭了——");
		assertThat(s.engine().turn()).isEqualTo(1);
		assertThat(s.engine().hp()).isEqualTo(100); // 不脏写
		assertThat(sink.order).contains("delta");
	}

	// ── 7. mid-stream flush 回归:流在哨兵半截(<<<DEL)断开 → 半截哨兵不外吐、保守 no-op ──
	@Test
	void streamBreakAtHalfSentinelWithholdsPartialAndDegrades() {
		LlmClient failing = (req, sink) -> {
			sink.onToken("镜子开始起雾");
			sink.onToken("<<<DEL"); // 哨兵半截,流在此断开(hold-back 缓冲里)
			throw new LlmException("流中断");
		};
		GameSession s = session();
		RecordingSink sink = new RecordingSink();

		TurnResult r = new EventLoopService(failing, prompts, mapper).execute(s, "A", sink);

		assertThat(r.ended()).isFalse();
		// 半截哨兵 "<<<DEL" 绝不出现在下发前端的叙事里。
		assertThat(sink.narrative.toString()).isEqualTo("镜子开始起雾");
		assertThat(sink.narrative.toString()).doesNotContain("<<<DEL");
		assertThat(s.engine().turn()).isEqualTo(1); // no-op 推进
		assertThat(s.engine().hp()).isEqualTo(100); // 不脏写
		assertThat(sink.order).contains("delta");
	}

	// ── 8. 消毒不变量(专测):含真假规则 + hiddenLogic 的 state 跑一回合,断言出网 delta 干净 ──
	@Test
	void deltaPayloadIsSanitizedAndDiscoveredRulesOnlyCarryContent() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(wire("你看清了墙上的第一条规则。", validTail(88, 77, "null"))); // discoveredRuleIds=[1]
		GameSession s = session();
		RecordingSink sink = new RecordingSink();

		new EventLoopService(llm, prompts, mapper).execute(s, "A", sink);

		// 整个 delta payload(序列化)不含任何隐藏字段名。
		String deltaStr = mapper.writeValueAsString(sink.delta);
		assertThat(deltaStr)
				.doesNotContain("hiddenLogic")
				.doesNotContain("isTrue")
				.doesNotContain("isCorrect")
				.doesNotContain("groundTruth");
		// discovered 规则只带 id + content,别无他物(尤其无 hiddenLogic/isTrue)。
		ObjectNode rule = (ObjectNode) sink.delta.get("discoveredRules").get(0);
		assertThat(rule.size()).as("规则仅 2 个字段").isEqualTo(2);
		assertThat(rule.has("id")).isTrue();
		assertThat(rule.has("content")).isTrue();
		assertThat(rule.get("content").asString()).isEqualTo("午夜不可照镜");
	}
}
