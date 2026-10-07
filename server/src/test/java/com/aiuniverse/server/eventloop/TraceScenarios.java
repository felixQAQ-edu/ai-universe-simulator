package com.aiuniverse.server.eventloop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.persistence.SessionStore;
import com.aiuniverse.server.persistence.TurnLedger;
import com.aiuniverse.server.persistence.TurnTrace;
import com.aiuniverse.server.quota.QuotaGate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-031 刀 2 · 测试面 2 的轨迹来源:用刀 1 的<b>真实采集</b>(真实 {@link TurnStateMachine} +
 * {@link EventLoopService},模型由脚本代替)在测试里跑出每一类回合,收下 {@link TurnTrace}。
 *
 * <p>轨迹不是手写的:手写轨迹只证明「回放器能读我想象中的轨迹」,证明不了它能重放线上那条采集路径产出的东西。
 */
final class TraceScenarios {

	private final ObjectMapper mapper;
	private final TurnPromptBuilder prompts;
	/** 落盘替身(默认不落盘);对拍工具可换成记录 encode 字节的实现。 */
	SessionStore store = SessionStore.NOOP;
	/** 每回合的 sink(默认丢弃);对拍工具可换成记录事件的实现。 */
	java.util.function.Supplier<TurnEventSink> sinks = TurnTraceTest.Sink::new;

	TraceScenarios(ObjectMapper mapper, ArchetypeRegistry registry) {
		this.mapper = mapper;
		this.prompts = new TurnPromptBuilder(registry);
	}

	/** 一段跑完的脚本:按回合顺序的轨迹 + 跑完后的会话(供需要时核对)。 */
	record Run(List<TurnTrace> traces, GameSession session, List<String> prompts) {
		TurnTrace last() {
			return traces.get(traces.size() - 1);
		}
	}

	/** 全部场景,键 = 场景名(测试面 2 的分类)。每个场景里每一条轨迹都应能回放一致。 */
	Map<String, Run> all() {
		Map<String, Run> out = new LinkedHashMap<>();
		out.put("normal", normal());
		out.put("normal_with_leak", normalWithLeak());
		out.put("repaired", repaired());
		out.put("degraded_stream_interrupted", degraded(new LlmException("断")));
		out.put("degraded_no_structured_tail", degraded("它停了一下,然后什么也没交出来。"));
		out.put("degraded_repair_failed", degraded(
				"它停了一下。" + SentinelSplitter.SENTINEL + "{\"stateUpdate\":{\"hp\":999}}", "{仍然不是 JSON"));
		out.put("ending", ending());
		out.put("box_scene_through_leave", boxSceneThroughLeave());
		out.put("box_scene_leave_degraded", boxSceneLeaveDegraded());
		out.put("lifetime_exit_action", lifetimeExitAction());
		out.put("lifetime_clamp", lifetimeClamp());
		return out;
	}

	// ── 场景 ─────────────────────────────────────────────────────────────

	static final String RC_OK = wire("它停了一下,又往前走。", "\"hp\":85,\"san\":70", "null", "A", "B");

	Run normal() {
		return run(session("rules_creepy", 0, false), List.of("A"), RC_OK);
	}

	/** 叙事里出现引擎字段名:泄露遥测命中(只进日志,不进状态)。 */
	Run normalWithLeak() {
		return run(session("rules_creepy", 0, false), List.of("A"),
				wire("镜子里有人念出了 hiddenLogic 这个词。", "\"hp\":40,\"san\":75", "null", "A", "B"));
	}

	/** 主调用校验失败 → 修复成功(settled,轨迹里有两条 usage 与错误清单)。 */
	Run repaired() {
		return run(session("rules_creepy", 0, false), List.of("A"),
				"它停了一下。" + SentinelSplitter.SENTINEL + "{\"stateUpdate\":{\"hp\":999}}",
				"{\"stateUpdate\":{\"hp\":60,\"san\":70,\"timeline\":\"tl\"},"
						+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"tA\"},{\"id\":\"B\",\"text\":\"tB\"}],"
						+ "\"ending\":null}");
	}

	Run degraded(Object... responses) {
		return run(session("rules_creepy", 0, false), List.of("A"), responses);
	}

	/** 结局回合:status → ended。 */
	Run ending() {
		return run(session("rules_creepy", 0, false), List.of("A"),
				wire("灯灭了。", "\"hp\":50,\"san\":40", "{\"id\":\"e\",\"reached\":true}"));
	}

	/**
	 * 纸箱局面:T11 起一路到被留下的余波、再到 LEAVE_HOME(离开回合)、再到屋外第一回合 —— 每一回合都出轨迹。
	 * 路径同 {@code BoxSceneWiringTest.leftThroughB3}:A, C, C, C(T14 阶段 4,g=0)、A(CHASE_CAR → R3a)、
	 * A、A(余波)、C(LEAVE_HOME)、A(屋外)。
	 */
	Run boxSceneThroughLeave() {
		List<String> actions = List.of("A", "C", "C", "C", "A", "A", "A", "C", "A");
		Object[] responses = new Object[actions.size()];
		for (int i = 0; i < responses.length; i++) {
			responses[i] = boxOk();
		}
		return run(session("animal_life", 10, true), actions, responses);
	}

	/** 离开回合本身降级(主调用流中断):离开叙事由编排确定地重算(ADR-031 §三 档 1),轨迹里不存。 */
	Run boxSceneLeaveDegraded() {
		List<String> actions = List.of("A", "C", "C", "C", "A", "A", "A", "C");
		Object[] responses = new Object[actions.size()];
		for (int i = 0; i < responses.length - 1; i++) {
			responses[i] = boxOk();
		}
		responses[responses.length - 1] = new LlmException("断");
		return run(session("animal_life", 10, true), actions, responses);
	}

	/** 一生制:进入有出口的阶段,服务端追加「就到这里」(选项由回合前状态确定地重算)。 */
	Run lifetimeExitAction() {
		return run(session("life_sim", 8, false), List.of("A"),
				wire("那年夏天很长。", "\"vigor\":60,\"longing\":40,\"crossroads\":30,\"ties\":35", "null", "A", "B"));
	}

	/** 一生制末段钳制触发(模型给气力 5,服务端抬到 15 并记一条 issue;回放经落账入口重做钳制)。 */
	Run lifetimeClamp() {
		int finalFrom = LifeStageTables.of("life_sim").finalStageFromTurn();
		return run(session("life_sim", finalFrom, false), List.of("A"),
				wire("手有点抖。", "\"vigor\":5,\"longing\":40,\"crossroads\":30,\"ties\":35", "null", "A", "B"));
	}

	// ── 执行 ─────────────────────────────────────────────────────────────

	Run run(GameSession s, List<String> actions, Object... responses) {
		TurnTraceTest.ScriptedLlm llm = new TurnTraceTest.ScriptedLlm();
		for (Object r : responses) {
			llm.then(r);
		}
		TurnTraceTest.RecordingTraceSink traces = new TurnTraceTest.RecordingTraceSink();
		TurnStateMachine machine = new TurnStateMachine(new EventLoopService(llm, prompts, mapper),
				store, QuotaGate.NOOP, TurnLedger.NOOP, traces);
		for (String a : actions) {
			int before = s.engine().turn();
			machine.submitAction(s, a, sinks.get());
			if (s.engine().turn() != before + 1) {
				throw new IllegalStateException("场景前提不成立:动作 " + a + " 没有落地");
			}
		}
		if (traces.traces.size() != actions.size()) {
			throw new IllegalStateException("场景前提不成立:每回合应恰有一条轨迹");
		}
		return new Run(new ArrayList<>(traces.traces), s, List.copyOf(llm.prompts));
	}

	// ── 夹具 ─────────────────────────────────────────────────────────────

	GameSession session(String archetype, int advance, boolean box) {
		Engine engine = new Engine(world(archetype), mapper);
		for (int i = 0; i < advance; i++) {
			engine.applyNoOp("日子", "A");
		}
		ArrayNode acts = mapper.createArrayNode();
		acts.addObject().put("id", "A").put("text", "趴着");
		acts.addObject().put("id", "B").put("text", "闻闻");
		GameSession s = new GameSession("save-replay-" + archetype, engine, acts);
		if (box) {
			s.setBoxScene(BoxSceneState.fresh());
		}
		return s;
	}

	private ObjectNode world(String archetype) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4").put("mode", "single");
		w.putArray("archetypes").add(archetype);
		w.putObject("world").put("title", "t").put("background", "b").put("dangerLevel", "low").put("tone", "克制");
		w.putArray("rules").addObject().put("id", 1).put("content", "午夜不可照镜").put("isTrue", true)
				.put("hiddenLogic", "照镜触发镜中怪").put("discovered", false);
		w.putArray("endings").addObject().put("id", "e").put("title", "结").put("condition", "c")
				.put("outcome", "neutral").put("reached", false);
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		switch (archetype) {
			case "rules_creepy" -> attrs.put("hp", 90).put("san", 80);
			case "life_sim" -> attrs.put("vigor", 70).put("longing", 50).put("crossroads", 40).put("ties", 30);
			case "animal_life" -> attrs.put("body", 80).put("warmth", 60).put("ground", 50).put("close", 50);
			default -> throw new IllegalArgumentException(archetype);
		}
		return w;
	}

	private static String boxOk() {
		return wire("它把鼻子贴近地面。", "\"body\":78,\"warmth\":58,\"ground\":49,\"close\":51", "null", "A", "B", "C");
	}

	static String wire(String narrative, String stateUpdate, String ending, String... ids) {
		StringBuilder a = new StringBuilder();
		for (String id : ids) {
			if (a.length() > 0) {
				a.append(',');
			}
			a.append("{\"id\":\"").append(id).append("\",\"text\":\"模型写的").append(id)
					.append("\",\"hint\":\"h").append(id).append("\"}");
		}
		return narrative + SentinelSplitter.SENTINEL + "{\"stateUpdate\":{" + stateUpdate
				+ ",\"timeline\":\"tl\"},\"availableActions\":[" + a + "],\"ending\":" + ending + "}";
	}
}
