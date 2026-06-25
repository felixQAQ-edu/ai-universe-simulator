package com.aiuniverse.server.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 规则怪谈 event-loop 状态机内核(数值权威 + 结算)—— 忠实移植 bakeoff {@code scenarios.py}
 * 的 {@code Engine}(连推 10 回合三路径自洽已验证),并补两处生产新设计(规格 §5 / §1)。
 *
 * <p><b>真理之源</b>:state(turn/status/hp/san/timeline/log/logSummary/triggered)由引擎维护
 * (CONTEXT §三.1)。AI 只<b>提议</b>本回合产出,{@link #apply} 校验落账(CONTEXT §三.8 数值权威)。
 * 回灌走 {@code ObjectNode} 层(不卡类型化 DTO):{@code world} 是可变 {@link ObjectNode},
 * {@code rules[].discovered} / {@code endings[].reached} 原地标注。
 *
 * <p><b>数值=绝对值</b>:{@code stateUpdate} 给各数值轴的新绝对值(缺省=当前)。三道闸门(规格 §5):
 * schema 硬范围 0–100(在 {@link GameSchemas#validateTurn} 拦,apply 前)、单回合跳变 &gt;
 * {@link #JUMP_THRESHOLD} 记 issues「需复核」<b>不拒绝</b>(F-003 允许有据恢复)、{@code clamp(0,100)} 兜底。
 *
 * <p><b>对数值 key 语义无知(ADR-008 决策 1)</b>:数值轴存于有序 {@link #attributes} map(声明顺序),
 * 结算遍历该 map——通吃规则怪谈 {@code {hp,san}} 如通吃末日 {@code {hp,hunger}}:加模式只换 attributes 的
 * key 集合,引擎结算序列/clamp/跳变/兜底逻辑<b>一行不动</b>。引擎不认识任何 key 的语义(包括「饥饿会衰减」——
 * 衰减由 AI 在 {@code stateUpdate} 落新绝对值,引擎只通用落账)。本泛化由 golden parity 守 {@code {hp,san}} 零回归。
 *
 * <p>本类不涉流式/SSE/动作合法性/忙态守卫(下一批 {@code EventLoopService});它是纯数据面内核,
 * 输入已解析并回灌叙事的 {@code parsed} + 玩家动作 id。
 */
public class Engine {

	/** 近 N 回合 log 原文保留,更旧的折进 logSummary(成本控制)。 */
	public static final int LOG_KEEP = 4;
	/** 单回合 hp/san 跳变超过此值才标「需复核」(F-003)。 */
	public static final double JUMP_THRESHOLD = 40;

	private final ObjectMapper mapper;
	private final ObjectNode world;
	/**
	 * 累积型数值轴的 key 集合(ADR-009 决策 1,F-012 正解):这些轴 {@code ≤0} <b>不触底致死</b>
	 * (0=安全起点)。其余轴一律视作 depletion(≤0 触底,= 现状)。<b>默认空集 = 全 depletion</b>
	 * (golden 用 2 参构造走此路 → 触底行为字节级不变)。引擎只读这一个二分,不懂任何具体轴语义。
	 */
	private final Set<String> accumulationKeys;

	private int turn = 0;
	private String status = "ongoing";
	/** 数值轴(key→绝对值),按 world 声明顺序保序(LinkedHashMap);引擎对 key 语义无知。 */
	private final LinkedHashMap<String, Double> attributes = new LinkedHashMap<>();
	private String timeline = "";
	private final List<ObjectNode> log = new ArrayList<>();
	private String logSummary = "";
	private final TreeSet<Integer> triggered = new TreeSet<>();
	private final List<String> issues = new ArrayList<>();

	/** 全 depletion 默认构造(= 现状;golden parity 走此路,触底行为字节级不变)。 */
	public Engine(ObjectNode world, ObjectMapper mapper) {
		this(world, mapper, Set.of());
	}

	/**
	 * 带累积轴角色的构造(ADR-009 F-012 正解)。{@code accumulationKeys} 列出本局的累积型轴 key
	 * (如克苏鲁 {@code knowledge}、修仙 境界),这些轴 {@code ≤0} 不触底;其余轴 = depletion(现状)。
	 * 角色由播种层({@code GameInitService}→{@code GameSessionManager})据 per-archetype 元数据传入,
	 * 引擎自身对「哪个轴是累积」无判断力(守 ADR-008:语义来自元数据,引擎只据集合 gate 触底)。
	 */
	public Engine(ObjectNode world, ObjectMapper mapper, Set<String> accumulationKeys) {
		this.mapper = mapper;
		this.world = world;
		this.accumulationKeys = accumulationKeys == null ? Set.of() : Set.copyOf(accumulationKeys);
		// 载入声明的数值轴(保序;只取数值键)。引擎不关心 key 是什么、有什么语义。
		JsonNode attrs = world.path("character").path("attributes");
		if (attrs.isObject()) {
			attrs.properties().forEach(e -> {
				if (e.getValue().isNumber()) {
					attributes.put(e.getKey(), e.getValue().asDouble());
				}
			});
		}
	}

	/**
	 * 把模型本回合产出({@code parsed},已回灌 {@code narrative})落进真理之源,并做一致性/泄露核对。
	 * 严格复刻 Python {@code apply()} 的 1–10 步序列(规格 §5),返回泄露遥测证据(空 = 干净)。
	 *
	 * @param parsed         已校验(且已回灌 narrative)的单回合产出
	 * @param playerActionId 本回合玩家所选动作 id
	 * @return 泄露遥测命中(规格 §1c:记录用,非实时拦截)
	 */
	public List<String> apply(JsonNode parsed, String playerActionId) {
		// 1. 回合自增
		turn += 1;
		// 2-4. 遍历声明的数值轴:读绝对新值(缺省=当前)→ 跳变核对(不拒绝,F-003)→ clamp 落账。
		//      对 key 语义无知:hp/san 与 hp/hunger 走同一通用结算(ADR-008 决策 1)。
		JsonNode upd = parsed.path("stateUpdate");
		for (Map.Entry<String, Double> e : attributes.entrySet()) {
			String key = e.getKey();
			double old = e.getValue();
			double nv = upd.has(key) ? upd.get(key).asDouble() : old;
			if (Math.abs(nv - old) > JUMP_THRESHOLD) {
				issues.add("T" + turn + " " + key + " 跳变过大 " + fmt(old) + "->" + fmt(nv) + "(需复核)");
			}
			e.setValue(clamp(nv));
		}
		// 5. timeline(缺省保留)
		if (upd.has("timeline")) {
			timeline = upd.get("timeline").asString("");
		}
		// 6. triggered |= ;discoveredRuleIds → 标 rule.discovered
		for (JsonNode id : parsed.path("triggeredRuleIds")) {
			triggered.add(id.asInt());
		}
		for (JsonNode rid : parsed.path("discoveredRuleIds")) {
			markRuleDiscovered(rid.asInt());
		}
		// 7. 泄露核对(遥测,§1c)
		List<String> leak = LeakDetector.detect(parsed.path("narrative").asString(""), world);
		// 8. 追加 log,旧的折进 logSummary(抽取式,零 LLM,规格 §7)
		ObjectNode entry = mapper.createObjectNode();
		entry.put("turn", turn);
		entry.put("narrative", parsed.path("narrative").asString(""));
		entry.put("playerAction", playerActionId);
		log.add(entry);
		if (log.size() > LOG_KEEP) {
			compressLog();
		}
		// 9. 结局判定:AI 提议命中 → status=ended + 标 endings[id].reached。
		//    规格 §4.4:ending.id 须存在于 world endings[]——不存在的 id <b>不接受</b>
		//    (不 end / 不标,避免前端拿到无对应条目的"幽灵结局";交由步骤 10 或后续回合)。
		JsonNode ending = parsed.get("ending");
		boolean aiReached = ending != null && ending.isObject()
				&& ending.path("reached").asBoolean(false);
		boolean aiAccepted = false;
		if (aiReached) {
			String id = ending.path("id").asString(null);
			if (endingExists(id)) {
				status = "ended";
				markEndingReached(id);
				aiAccepted = true;
			}
		}
		// 10. 兜底:任一数值轴触底(≤0)强制 ended;§5 补丁——AI 未给(或未被接受)结局则引擎兜一个坏结局 id。
		//     对 key 语义无知:任意轴归零即触底({hp,san} 的 hp≤0||san≤0 是其特例),通吃 {hp,hunger}。
		if (anyAttributeBottomedOut()) {
			status = "ended";
			if (!aiAccepted && !anyEndingReached()) {
				forceBottomOutEnding();
			}
		}
		return leak;
	}

	/**
	 * 保守 no-op 推进(规格 §6.5/§6.6)：修复仍败 / 回灌叙事非法时的优雅降级。
	 * <b>turn++、记一条 log(已流出的叙事当氛围文字)、绝不脏写</b> hp/san/timeline/triggered/discovered/ending。
	 * 由 {@code EventLoopService} 在响亮告警后调用;数值/结局逻辑一概不动。
	 *
	 * @param narrative      已展示给玩家的叙事(可空;空表示叙事本身也非法)
	 * @param playerActionId 本回合玩家所选动作 id
	 */
	public void applyNoOp(String narrative, String playerActionId) {
		turn += 1;
		ObjectNode entry = mapper.createObjectNode();
		entry.put("turn", turn);
		entry.put("narrative", narrative == null ? "" : narrative);
		entry.put("playerAction", playerActionId);
		log.add(entry);
		if (log.size() > LOG_KEEP) {
			compressLog();
		}
	}

	/** 回传模型的真理之源(视图 2:含 hiddenLogic,模型需据此裁决真假规则)。 */
	public String contextJson() {
		return mapper.writeValueAsString(snapshot());
	}

	/**
	 * 客户端消毒投影(视图 3,规格 §1):递归剥掉 {@link LeakDetector#LEAK_TOKENS}
	 * ({@code isTrue}/{@code hiddenLogic}/{@code isCorrect}/{@code groundTruth})。
	 * <b>任何出网路径都必须过它。</b>
	 */
	public ObjectNode toClientState() {
		ObjectNode snap = snapshot();
		stripHidden(snap);
		return snap;
	}

	// ── 内部:状态快照(world + 当前 state + 当前数值)─────────────────
	private ObjectNode snapshot() {
		ObjectNode payload = world.deepCopy();
		ObjectNode character = payload.has("character") && payload.get("character").isObject()
				? (ObjectNode) payload.get("character")
				: payload.putObject("character");
		ObjectNode attrs = mapper.createObjectNode();
		for (Map.Entry<String, Double> e : attributes.entrySet()) {
			putNumber(attrs, e.getKey(), e.getValue());
		}
		character.set("attributes", attrs); // attributes 整体替换为引擎落账后的各轴绝对值(保序;对 key 无知)

		ObjectNode state = mapper.createObjectNode();
		state.put("turn", turn);
		state.put("status", status);
		state.put("timeline", timeline);
		state.put("logSummary", logSummary);
		ArrayNode logArr = state.putArray("log");
		for (ObjectNode e : log.subList(Math.max(0, log.size() - LOG_KEEP), log.size())) {
			logArr.add(e.deepCopy());
		}
		payload.set("state", state);
		return payload;
	}

	private static void stripHidden(JsonNode node) {
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			for (String tok : LeakDetector.LEAK_TOKENS) {
				obj.remove(tok);
			}
			for (JsonNode child : obj) {
				stripHidden(child);
			}
		} else if (node.isArray()) {
			for (JsonNode child : node) {
				stripHidden(child);
			}
		}
	}

	private void compressLog() {
		// 把超出 LOG_KEEP 的旧回合折成 [T{turn}选{action}] 串入 logSummary(每回合最多折 1 条)。
		List<ObjectNode> old = log.subList(0, log.size() - LOG_KEEP);
		StringBuilder folded = new StringBuilder();
		for (ObjectNode e : old) {
			if (folded.length() > 0) {
				folded.append(' ');
			}
			folded.append("[T").append(e.get("turn").asInt())
					.append("选").append(e.get("playerAction").asString("")).append(']');
		}
		logSummary = (logSummary + " " + folded).strip();
		// 保留近 LOG_KEEP 条
		List<ObjectNode> keep = new ArrayList<>(log.subList(log.size() - LOG_KEEP, log.size()));
		log.clear();
		log.addAll(keep);
	}

	private void markRuleDiscovered(int ruleId) {
		for (JsonNode r : world.path("rules")) {
			if (r.isObject() && r.path("id").asInt() == ruleId) {
				((ObjectNode) r).put("discovered", true);
			}
		}
	}

	private void markEndingReached(String endingId) {
		if (endingId == null) {
			return;
		}
		for (JsonNode e : world.path("endings")) {
			if (e.isObject() && endingId.equals(e.path("id").asString(null))) {
				((ObjectNode) e).put("reached", true);
			}
		}
	}

	private boolean endingExists(String endingId) {
		if (endingId == null) {
			return false;
		}
		for (JsonNode e : world.path("endings")) {
			if (endingId.equals(e.path("id").asString(null))) {
				return true;
			}
		}
		return false;
	}

	private boolean anyEndingReached() {
		for (JsonNode e : world.path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 任一 <b>depletion 型</b>数值轴 ≤ 0(ADR-009 F-012 正解:accumulation 轴 0=安全起点,不触底)。
	 * {hp,san}/{hp,hunger} 全 depletion → 同现状(hp≤0||san≤0 等);克苏鲁 knowledge / 修仙 境界 是
	 * accumulation,即便 ≤0 也不致死。引擎仍对 key 语义无知——只据 {@link #accumulationKeys} 集合 gate。
	 */
	private boolean anyAttributeBottomedOut() {
		for (Map.Entry<String, Double> e : attributes.entrySet()) {
			if (isDepletion(e.getKey()) && e.getValue() <= 0) {
				return true;
			}
		}
		return false;
	}

	/** 该轴是否 depletion 型(≤0 触底致死)。不在累积集合里的一律视作 depletion(默认现状)。 */
	private boolean isDepletion(String key) {
		return !accumulationKeys.contains(key);
	}

	/**
	 * §5 补丁(🆕,bake-off 无):数值触底但 AI 未给结局 → 引擎兜一个坏结局 id。
	 * 遍历声明数值轴(保序),对触底(≤0)的轴优先找 condition 提及该轴 key 的那条结局(如 san→{@code lost_mind});
	 * 找不到则用约定 fallback = {@code endings[]} 首条(确定性,前端总有结局可显)。对 key 语义无知,通吃任意轴。
	 */
	private void forceBottomOutEnding() {
		String pick = null;
		for (Map.Entry<String, Double> e : attributes.entrySet()) {
			// 只看触底的 depletion 轴(accumulation 轴 ≤0 不致死,自然也不该用它挑坏结局)。
			if (isDepletion(e.getKey()) && e.getValue() <= 0) {
				pick = findEndingByConditionMentioning(e.getKey());
				if (pick != null) {
					break;
				}
			}
		}
		if (pick == null) {
			pick = firstEndingId();
		}
		if (pick != null) {
			markEndingReached(pick);
		}
	}

	private String findEndingByConditionMentioning(String stat) {
		String needle = stat.toLowerCase();
		for (JsonNode e : world.path("endings")) {
			if (e.path("condition").asString("").toLowerCase().contains(needle)) {
				return e.path("id").asString(null);
			}
		}
		return null;
	}

	private String firstEndingId() {
		for (JsonNode e : world.path("endings")) {
			String id = e.path("id").asString(null);
			if (id != null) {
				return id;
			}
		}
		return null;
	}

	private double clamp(double x) {
		return Math.max(0, Math.min(100, x));
	}

	/** 整数值不带小数尾巴(供 issues 文案与 player 数值面板更干净)。 */
	private static String fmt(double x) {
		return x == Math.rint(x) ? Long.toString((long) x) : Double.toString(x);
	}

	private static void putNumber(ObjectNode node, String key, double x) {
		if (x == Math.rint(x)) {
			node.put(key, (long) x);
		} else {
			node.put(key, x);
		}
	}

	// ── 只读访问器(供上层 / 测试)────────────────────────────────────
	public int turn() {
		return turn;
	}

	public String status() {
		return status;
	}

	/** 某数值轴当前绝对值(不存在返回 0);key-agnostic 出网/上层读取走它。 */
	public double attribute(String key) {
		return attributes.getOrDefault(key, 0.0);
	}

	/** 所有数值轴(保序拷贝,key→绝对值);buildDelta / 前端面板按它遍历(对 key 无知)。 */
	public Map<String, Double> attributes() {
		return new LinkedHashMap<>(attributes);
	}

	/** 便捷访问器(规则怪谈轴;= {@code attribute("hp")}/{@code attribute("san")}),供既有测试/上层。 */
	public double hp() {
		return attribute("hp");
	}

	public double san() {
		return attribute("san");
	}

	public String timeline() {
		return timeline;
	}

	public String logSummary() {
		return logSummary;
	}

	public List<Integer> triggered() {
		return new ArrayList<>(triggered);
	}

	public List<String> issues() {
		return List.copyOf(issues);
	}

	public List<ObjectNode> log() {
		return List.copyOf(log);
	}

	/** 真理之源世界(可变;rules[].discovered / endings[].reached 在此标注)。仅供上层/测试读。 */
	public ObjectNode world() {
		return world;
	}
}
