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
	/**
	 * 结局极性 gate 的「濒死」阈值(ADR-010 F-014):致命轴 {@code ≤} 此值时,引擎拒绝 AI 提议的
	 * {@code outcome==success} 结局、确定性改挑失败结局。与触底 0 刻意分开——濒死(≤10)就 gate 成功结局,
	 * 不必等触底(0)。
	 */
	public static final double ENDING_GATE_THRESHOLD = 10;

	private final ObjectMapper mapper;
	private final ObjectNode world;
	/**
	 * 累积型数值轴的 key 集合(ADR-009 决策 1,F-012 正解):这些轴 {@code ≤0} <b>不触底致死</b>
	 * (0=安全起点)。其余轴一律视作 depletion(≤0 触底,= 现状)。<b>默认空集 = 全 depletion</b>
	 * (golden 用 2 参构造走此路 → 触底行为字节级不变)。引擎只读这一个二分,不懂任何具体轴语义。
	 */
	private final Set<String> accumulationKeys;
	/**
	 * 数值轴 key→玩家可见中文名(如 {@code hp→气血}/{@code mana→灵力}),仅 §5 兜底结局匹配用
	 * (F-014:world-gen 的 {@code endings[].condition} 是中文,英文 key 几乎永不命中 → 优先按中文名匹配,
	 * 中文名缺失才回落英文 key)。<b>默认空 map</b>(golden / 既有单测走 2/3 参构造 → 回落 key、行为字节级不变)。
	 * 引擎仍对轴语义无知——中文名只当「在中文 condition 里搜哪个词」的检索串,不解读其含义。
	 */
	private final Map<String, String> axisDisplayNames;
	/**
	 * 非致命 depletion 轴的 key 集合(ADR-010 决策 2,F-015 关闭):这些轴虽是 depletion(≤0 是惩罚),
	 * 但 {@code ≤0} <b>不触底致死、也不触发结局极性 gate</b>(如修仙灵力——枯竭=力竭非必死)。其余 depletion 轴
	 * 一律视作致命(= 现状)。<b>默认空集 = 全 depletion 致命</b>(golden 走 2 参构造此路 → 触底行为字节级不变)。
	 * 角色由播种层据 per-archetype 元数据(轴 {@code lethal=false})算出传入;引擎只据集合判致命,不懂轴语义。
	 */
	private final Set<String> nonLethalKeys;

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
		this(world, mapper, Set.of(), Map.of(), Set.of());
	}

	/** 带累积轴角色、无中文名(§5 兜底回落英文 key)。既有 3 参调用方/单测走此路,行为不变。 */
	public Engine(ObjectNode world, ObjectMapper mapper, Set<String> accumulationKeys) {
		this(world, mapper, accumulationKeys, Map.of(), Set.of());
	}

	/** 带累积轴角色 + 中文名(§5 兜底按中文 condition 匹配)、全 depletion 致命。既有 4 参调用方/单测走此路,行为不变。 */
	public Engine(ObjectNode world, ObjectMapper mapper, Set<String> accumulationKeys,
			Map<String, String> axisDisplayNames) {
		this(world, mapper, accumulationKeys, axisDisplayNames, Set.of());
	}

	/**
	 * 全参构造(ADR-009 F-012 + F-014 §5 + ADR-010 致命轴 gate)。三份轴语义集均由播种层
	 * ({@code GameInitService}→{@code GameSessionManager})据 per-archetype 元数据传入,引擎自身对
	 * 「哪个轴累积/致命/叫什么」无判断力(守 ADR-008:语义来自元数据,引擎只据集合 gate、按名搜 condition):
	 * <ul>
	 *   <li>{@code accumulationKeys} —— 累积型轴 key(克苏鲁 {@code knowledge}、修仙 境界),{@code ≤0} 不触底;</li>
	 *   <li>{@code axisDisplayNames} —— 轴 key→中文名(§5 兜底结局按中文 condition 匹配用);</li>
	 *   <li>{@code nonLethalKeys} —— 非致命 depletion 轴 key(修仙灵力),{@code ≤0} 不死、不触发结局极性 gate
	 *       (ADR-010,关闭 F-015);默认空 = 全 depletion 致命(现状)。</li>
	 * </ul>
	 */
	public Engine(ObjectNode world, ObjectMapper mapper, Set<String> accumulationKeys,
			Map<String, String> axisDisplayNames, Set<String> nonLethalKeys) {
		this.mapper = mapper;
		this.world = world;
		this.accumulationKeys = accumulationKeys == null ? Set.of() : Set.copyOf(accumulationKeys);
		this.axisDisplayNames = axisDisplayNames == null ? Map.of() : Map.copyOf(axisDisplayNames);
		this.nonLethalKeys = nonLethalKeys == null ? Set.of() : Set.copyOf(nonLethalKeys);
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
	 * 从持久化文档回载引擎(ADR-015 C3,<b>纯增量恢复入口</b>:现有构造器与 {@link #apply} 逐字不动)。
	 * 输入 = {@link #toPersistedState()} 的产出(或 {@code GameSessionManager} 在其上补了
	 * {@code currentActions}/{@code phaseHint} 的完整存档文档——本方法只读引擎部分,<b>容忍并忽略</b>多余顶层字段)。
	 *
	 * <p><b>轴语义集不落盘</b>(ADR-015):三份集合由调用方据 {@code world.archetypes} 经
	 * {@code ArchetypeRegistry}(单体 / {@code fusedAxes})原路重派生后传入,与播种同一真理源;
	 * 引擎照旧只据集合 gate、对轴语义无知。
	 *
	 * <p><b>非法输入 = 拒载,不半载</b>:缺 {@code schemaVersion}/{@code world}/{@code state} 或字段形态不对,
	 * 先全量校验后才构造,任何一处不合格即抛 {@link IllegalArgumentException}、不产出半残实例。
	 * {@code schemaVersion} 只认现行接受集 {@code {"0.2","0.3","0.4"}}(引用现值,不新造版本序列)。
	 *
	 * @throws IllegalArgumentException 持久化文档缺字段 / schemaVersion 不识 / 字段形态非法
	 */
	public static Engine restore(JsonNode persisted, ObjectMapper mapper, Set<String> accumulationKeys,
			Map<String, String> axisDisplayNames, Set<String> nonLethalKeys) {
		// ── 先全量校验(拒载不半载)──
		if (persisted == null || !persisted.isObject()) {
			throw new IllegalArgumentException("持久化文档必须是 JSON 对象");
		}
		String sv = persisted.path("schemaVersion").asString(null);
		if (!"0.2".equals(sv) && !"0.3".equals(sv) && !"0.4".equals(sv)) {
			throw new IllegalArgumentException("schemaVersion 不识(须为 \"0.2\"/\"0.3\"/\"0.4\"):" + sv);
		}
		JsonNode world = persisted.get("world");
		if (world == null || !world.isObject()) {
			throw new IllegalArgumentException("持久化文档缺 world(或非对象)");
		}
		JsonNode state = persisted.get("state");
		if (state == null || !state.isObject()) {
			throw new IllegalArgumentException("持久化文档缺 state(或非对象)");
		}
		if (!state.path("turn").isNumber()) {
			throw new IllegalArgumentException("state.turn 缺失或非数值");
		}
		if (!state.path("status").isString()) {
			throw new IllegalArgumentException("state.status 缺失或非字符串");
		}
		if (!state.path("timeline").isString()) {
			throw new IllegalArgumentException("state.timeline 缺失或非字符串");
		}
		if (!state.path("logSummary").isString()) {
			throw new IllegalArgumentException("state.logSummary 缺失或非字符串");
		}
		JsonNode logArr = state.get("log");
		if (logArr == null || !logArr.isArray()) {
			throw new IllegalArgumentException("state.log 缺失或非数组");
		}
		for (JsonNode e : logArr) {
			if (!e.isObject()) {
				throw new IllegalArgumentException("state.log 条目必须是对象");
			}
		}
		JsonNode triggeredArr = persisted.get("triggered");
		if (triggeredArr == null || !triggeredArr.isArray()) {
			throw new IllegalArgumentException("持久化文档缺 triggered(或非数组)");
		}
		JsonNode issuesArr = persisted.get("issues");
		if (issuesArr == null || !issuesArr.isArray()) {
			throw new IllegalArgumentException("持久化文档缺 issues(或非数组)");
		}
		// ── 校验通过才构造:attributes 由现有构造器从 world.character.attributes 读回(导出时已写入当前值)──
		Engine eng = new Engine((ObjectNode) world.deepCopy(), mapper, accumulationKeys,
				axisDisplayNames, nonLethalKeys);
		eng.turn = state.get("turn").asInt();
		eng.status = state.get("status").asString();
		eng.timeline = state.get("timeline").asString();
		eng.logSummary = state.get("logSummary").asString();
		for (JsonNode e : logArr) {
			eng.log.add((ObjectNode) e.deepCopy());
		}
		for (JsonNode id : triggeredArr) {
			eng.triggered.add(id.asInt());
		}
		for (JsonNode msg : issuesArr) {
			eng.issues.add(msg.asString(""));
		}
		return eng;
	}

	/**
	 * 导出持久化文档(ADR-015 C3,<b>视图 1 全量</b>:含 {@code isTrue}/{@code hiddenLogic},绝不出网,
	 * 落盘目录须在 web 根之外——安全条款在落盘层 Slice 2 断言)。与另两视图<b>用途不同不得混用</b>:
	 * {@link #contextJson()} 是喂模型视图(2),{@link #toClientState()} 是出网消毒投影(3)。
	 *
	 * <p>格式 = {@code {schemaVersion, world, state{turn,status,timeline,log,logSummary}, triggered, issues}}
	 * ({@code schemaVersion} 引用 world 现值,不新造版本序列)。两处与内部 world 节点的差异:
	 * (a) {@code character.attributes} 写入<b>引擎落账后的当前值</b>(内部 world 里还是初值,不写回则
	 * {@link #restore} 读到旧数值);(b) 剥掉 world 内残留的 {@code state} 键(顶层已有,避免双份陈旧 state)。
	 * {@code currentActions}/{@code phaseHint} 属 session 层,由 {@code GameSessionManager}(Slice 2)
	 * 在本文档之上补齐。
	 */
	public ObjectNode toPersistedState() {
		ObjectNode doc = mapper.createObjectNode();
		doc.put("schemaVersion", world.path("schemaVersion").asString(null));
		ObjectNode w = world.deepCopy();
		w.remove("state");
		ObjectNode character = w.has("character") && w.get("character").isObject()
				? (ObjectNode) w.get("character")
				: w.putObject("character");
		ObjectNode attrs = mapper.createObjectNode();
		for (Map.Entry<String, Double> e : attributes.entrySet()) {
			putNumber(attrs, e.getKey(), e.getValue());
		}
		character.set("attributes", attrs);
		doc.set("world", w);

		ObjectNode state = doc.putObject("state");
		state.put("turn", turn);
		state.put("status", status);
		state.put("timeline", timeline);
		ArrayNode logArr = state.putArray("log");
		for (ObjectNode e : log) {
			logArr.add(e.deepCopy());
		}
		state.put("logSummary", logSummary);

		ArrayNode triggeredArr = doc.putArray("triggered");
		for (Integer id : triggered) {
			triggeredArr.add(id);
		}
		ArrayNode issuesArr = doc.putArray("issues");
		for (String msg : issues) {
			issuesArr.add(msg);
		}
		return doc;
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
		//    ADR-010 结局极性 gate(F-014):若致命轴濒零(≤ ENDING_GATE_THRESHOLD)且 AI 提议的是
		//    outcome==success 结局 → <b>拒绝该成功结局</b>,确定性改挑一个失败结局落账(濒死不得圆满)。
		//    引擎只读 outcome 标签 + 看致命轴值,不懂结局语义(守 ADR-008)。outcome 缺省 neutral → 不 gate
		//    (向后兼容:老世界无 outcome → 永不 gate,golden 行为零回归)。
		JsonNode ending = parsed.get("ending");
		boolean aiReached = ending != null && ending.isObject()
				&& ending.path("reached").asBoolean(false);
		boolean aiAccepted = false;
		if (aiReached) {
			String id = ending.path("id").asString(null);
			if (endingExists(id)) {
				List<String> nearZeroLethal = nearZeroLethalAxes();
				if (!nearZeroLethal.isEmpty() && "success".equals(endingOutcome(id))) {
					// gate 介入:拒绝濒死时的成功结局,据极性确定性挑失败结局。
					String failureId = pickFailureEnding(nearZeroLethal);
					issues.add("T" + turn + " 致命轴濒零拒绝成功结局 " + id + " → 改判 " + failureId + "(ADR-010 gate)");
					status = "ended";
					markEndingReached(failureId);
					aiAccepted = true;
				} else {
					status = "ended";
					markEndingReached(id);
					aiAccepted = true;
				}
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
	 * 任一 <b>致命</b>数值轴 ≤ 0(ADR-009 F-012:accumulation 轴不触底;ADR-010 F-015:非致命 depletion 轴
	 * 如灵力枯竭=力竭非必死,也不触底)。{hp,san}/{hp,hunger} 全致命 → 同现状(hp≤0||san≤0 等)。引擎仍对
	 * key 语义无知——只据 {@link #accumulationKeys} / {@link #nonLethalKeys} 集合判致命。
	 */
	private boolean anyAttributeBottomedOut() {
		for (Map.Entry<String, Double> e : attributes.entrySet()) {
			if (isLethal(e.getKey()) && e.getValue() <= 0) {
				return true;
			}
		}
		return false;
	}

	/** 该轴是否 depletion 型(≤0 触底语义)。不在累积集合里的一律视作 depletion(默认现状)。 */
	private boolean isDepletion(String key) {
		return !accumulationKeys.contains(key);
	}

	/**
	 * 该轴是否<b>致命</b>(ADR-010:depletion 且非「非致命资源池」→ ≤0 死亡 + 触发结局极性 gate)。
	 * accumulation 轴非致命;非致命 depletion 轴(灵力,在 {@link #nonLethalKeys})非致命。默认空非致命集 =
	 * 全 depletion 致命(= 现状,golden 走此路字节级不变)。
	 */
	private boolean isLethal(String key) {
		return isDepletion(key) && !nonLethalKeys.contains(key);
	}

	/** 当前濒零(≤ {@link #ENDING_GATE_THRESHOLD})的致命轴 key 列表(保序;结局极性 gate 据它判濒死)。 */
	private List<String> nearZeroLethalAxes() {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Double> e : attributes.entrySet()) {
			if (isLethal(e.getKey()) && e.getValue() <= ENDING_GATE_THRESHOLD) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/** 当前触底(≤0)的致命轴 key 列表(保序;§10 兜底据它挑坏结局)。 */
	private List<String> bottomedLethalAxes() {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Double> e : attributes.entrySet()) {
			if (isLethal(e.getKey()) && e.getValue() <= 0) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/** ending 的极性 outcome(ADR-010,AI 标;缺省 {@code "neutral"})。引擎只读不解读。 */
	private String endingOutcome(String endingId) {
		if (endingId == null) {
			return "neutral";
		}
		for (JsonNode e : world.path("endings")) {
			if (endingId.equals(e.path("id").asString(null))) {
				return e.path("outcome").asString("neutral");
			}
		}
		return "neutral";
	}

	/**
	 * §5 补丁(🆕,bake-off 无):致命轴触底但 AI 未给结局 → 引擎兜一个坏结局 id。
	 * 复用 {@link #pickFailureEnding}(据极性 + 中文名确定性挑失败结局);对 key 语义无知,通吃任意轴。
	 */
	private void forceBottomOutEnding() {
		String pick = pickFailureEnding(bottomedLethalAxes());
		if (pick != null) {
			markEndingReached(pick);
		}
	}

	/**
	 * 据极性确定性挑一个失败结局 id(ADR-010,§4.4 gate 与 §5 兜底共用)。逐级退化:
	 * <ol>
	 *   <li>condition 提及某致命轴(中文名优先)<b>且 {@code outcome==failure}</b> 的结局;</li>
	 *   <li>首个 {@code outcome==failure} 结局;</li>
	 *   <li>condition 提及某致命轴的结局(任意极性,= F-014 §5 修仙批行为);</li>
	 *   <li>{@code endings[]} 首条(约定 fallback,确定性,前端总有结局可显)。</li>
	 * </ol>
	 * <b>无 outcome 字段时</b>(老世界 / golden / 既有单测):步骤 1/2 恒空 → 退化为步骤 3/4 =
	 * 修仙批 §5 中文名匹配行为,<b>golden parity 字节级零回归</b>。
	 *
	 * @param lethalAxisKeys 当前濒零/触底的致命轴 key(按它们的 condition 找匹配失败结局)
	 */
	private String pickFailureEnding(List<String> lethalAxisKeys) {
		// 1. failure 极性 + condition 提及致命轴(中文名优先)
		for (String key : lethalAxisKeys) {
			String id = findEndingByConditionMentioning(key, "failure");
			if (id != null) {
				return id;
			}
		}
		// 2. 首个 failure 极性结局
		String firstFailure = firstEndingIdWithOutcome("failure");
		if (firstFailure != null) {
			return firstFailure;
		}
		// 3. condition 提及致命轴(任意极性,= 修仙批 §5 行为)
		for (String key : lethalAxisKeys) {
			String id = findEndingByConditionMentioning(key, null);
			if (id != null) {
				return id;
			}
		}
		// 4. 约定 fallback:首条
		return firstEndingId();
	}

	/** 首个指定极性(outcome)的结局 id;无则 null。 */
	private String firstEndingIdWithOutcome(String outcome) {
		for (JsonNode e : world.path("endings")) {
			if (outcome.equals(e.path("outcome").asString(null))) {
				return e.path("id").asString(null);
			}
		}
		return null;
	}

	/**
	 * 找一条 condition 提及某轴的结局 id(F-014 §5 确定性修复 + ADR-010 极性过滤)。world-gen 的 condition 是
	 * <b>中文</b>,故<b>优先按轴中文名</b>(如 {@code 气血}/{@code 灵力})匹配——原先只用英文 key({@code hp}/
	 * {@code mana})匹配中文 condition <b>几乎永不命中</b>、一路回落 {@code endings[0]}(常是好结局),这是确定性
	 * 逻辑错误。中文名缺失(2/3 参构造、录制夹具)才回落英文 key 匹配,守向后兼容 + golden parity 零回归。
	 *
	 * @param outcomeFilter 若非 null,只匹配该极性的结局(ADR-010 gate 用 {@code "failure"});null=不限极性(§5 旧行为)
	 */
	private String findEndingByConditionMentioning(String axisKey, String outcomeFilter) {
		String displayName = axisDisplayNames.get(axisKey);
		String keyNeedle = axisKey.toLowerCase();
		for (JsonNode e : world.path("endings")) {
			if (outcomeFilter != null && !outcomeFilter.equals(e.path("outcome").asString(null))) {
				continue;
			}
			String cond = e.path("condition").asString("");
			if (displayName != null && !displayName.isBlank() && cond.contains(displayName)) {
				return e.path("id").asString(null);
			}
			if (cond.toLowerCase().contains(keyNeedle)) {
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
