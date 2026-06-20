package com.aiuniverse.server.engine;

import java.util.ArrayList;
import java.util.List;
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
 * <p><b>数值=绝对值</b>:{@code stateUpdate.hp/.san} 是新绝对值(缺省=当前)。三道闸门(规格 §5):
 * schema 硬范围 0–100(在 {@link GameSchemas#validateTurn} 拦,apply 前)、单回合跳变 &gt;
 * {@link #JUMP_THRESHOLD} 记 issues「需复核」<b>不拒绝</b>(F-003 允许有据恢复)、{@code clamp(0,100)} 兜底。
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

	private int turn = 0;
	private String status = "ongoing";
	private double hp;
	private double san;
	private String timeline = "";
	private final List<ObjectNode> log = new ArrayList<>();
	private String logSummary = "";
	private final TreeSet<Integer> triggered = new TreeSet<>();
	private final List<String> issues = new ArrayList<>();

	public Engine(ObjectNode world, ObjectMapper mapper) {
		this.mapper = mapper;
		this.world = world;
		JsonNode attrs = world.path("character").path("attributes");
		this.hp = attrs.has("hp") ? attrs.get("hp").asDouble() : 100;
		this.san = attrs.has("san") ? attrs.get("san").asDouble() : 100;
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
		// 2. 读绝对新值(缺省=当前)
		JsonNode upd = parsed.path("stateUpdate");
		double newHp = upd.has("hp") ? upd.get("hp").asDouble() : hp;
		double newSan = upd.has("san") ? upd.get("san").asDouble() : san;
		// 3. 一致性核对:跳变过大记 issues(不拒绝,允许有据恢复 F-003)
		if (Math.abs(newHp - hp) > JUMP_THRESHOLD) {
			issues.add("T" + turn + " hp 跳变过大 " + fmt(hp) + "->" + fmt(newHp) + "(需复核)");
		}
		if (Math.abs(newSan - san) > JUMP_THRESHOLD) {
			issues.add("T" + turn + " san 跳变过大 " + fmt(san) + "->" + fmt(newSan) + "(需复核)");
		}
		// 4. clamp 兜底落账
		hp = clamp(newHp);
		san = clamp(newSan);
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
		// 10. 兜底:数值触底强制 ended;§5 补丁——AI 未给(或未被接受)结局则引擎兜一个坏结局 id
		if (hp <= 0 || san <= 0) {
			status = "ended";
			if (!aiAccepted && !anyEndingReached()) {
				forceBottomOutEnding();
			}
		}
		return leak;
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
		putNumber(attrs, "hp", hp);
		putNumber(attrs, "san", san);
		character.set("attributes", attrs); // 复刻 Python:attributes 整体替换为 {hp,san}

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
	 * §5 补丁(🆕,bake-off 无):数值触底但 AI 未给结局 → 引擎兜一个坏结局 id。
	 * 优先在 {@code endings[]} 找 condition 提及触底数值(san/hp)的那条(如 {@code lost_mind});
	 * 找不到则用约定 fallback = {@code endings[]} 首条(确定性,前端总有结局可显)。
	 */
	private void forceBottomOutEnding() {
		String pick = null;
		if (san <= 0) {
			pick = findEndingByConditionMentioning("san");
		}
		if (pick == null && hp <= 0) {
			pick = findEndingByConditionMentioning("hp");
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

	public double hp() {
		return hp;
	}

	public double san() {
		return san;
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
