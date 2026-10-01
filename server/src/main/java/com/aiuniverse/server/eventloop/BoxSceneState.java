package com.aiuniverse.server.eventloop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxScene.Pick;
import com.aiuniverse.server.eventloop.BoxScene.Situation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 局面的<b>会话层</b>状态(ADR-028 决策 1 · 方案 B):与 {@code currentActions} 同层落盘,<b>不进引擎</b>
 * (引擎 / golden / {@code schemaVersion} 零动)。只在视图 1(落盘);视图 2 只见由它渲染出的人话,
 * 视图 3 一个字段都不下发。
 *
 * <p><b>阶段不存,派生</b>(决策 1):由 {@code engine.turn()} 算,故不可能与引擎回合号漂移。
 * 存的只有:g、结果、结算回合号、处境、当回合槽位映射、意图历史。
 *
 * <p><b>旧局</b>(刀 2a 口径,校勘定):判断<b>只看文档里有没有本类的键</b>,不看回合号。
 * 没有键的《动物人生》存档一律算旧局 —— 它的世界是用旧背景与旧结局池生成的;整局跳过局面层,
 * 下次存盘时写进 {@code {"legacy": true}},从此可判定。
 *
 * <p><b>空的处境不是任何一种处境</b>:{@code situation == null} 只表示「局面结算之前」;
 * 文档里的空串或未知值一律拒载,不替它猜默认值。
 *
 * <p>并发:只在回合临界区内被改(忙态 CAS 串行),无锁。
 */
public final class BoxSceneState {

	private static final Logger log = LoggerFactory.getLogger(BoxSceneState.class);

	/** 落盘文档里的局面键。 */
	public static final String DOC_KEY = "boxScene";

	private final boolean legacy;
	int g;
	Path result;
	Integer settledTurn;
	Situation situation;
	/** 当前下发的槽位 → 内部意图(结算按它,不在下一回合反查)。空 = 当前选项不由引擎接管。 */
	Map<String, String> slots = new LinkedHashMap<>();
	List<Pick> history = new ArrayList<>();

	private BoxSceneState(boolean legacy) {
		this.legacy = legacy;
	}

	/** 新开的局:创建时就写入局面键(局面尚未开始)。 */
	public static BoxSceneState fresh() {
		return new BoxSceneState(false);
	}

	/** 旧局标记:整局跳过局面层。 */
	public static BoxSceneState legacyMarker() {
		return new BoxSceneState(true);
	}

	public boolean isLegacy() {
		return legacy;
	}

	/** 该世界是否接局面层(单体且登记在 {@link BoxSceneTables})。 */
	public static boolean appliesTo(List<String> archetypes) {
		return archetypes.size() == 1 && BoxSceneTables.box(archetypes.get(0)) != null;
	}

	/**
	 * 回载:<b>只看键在不在</b>。不接局面层的世界 → {@code null}(文档里即便有键也忽略);
	 * 接局面层而无键 → 旧局(记 WARN);有键 → 严格解析(不合法一律抛 = 拒载不半载)。
	 */
	public static BoxSceneState restoreFor(List<String> archetypes, JsonNode node, String saveId) {
		if (!appliesTo(archetypes)) {
			return null;
		}
		if (node == null || node.isMissingNode() || node.isNull()) {
			log.warn("[box-scene] save={} 旧局:存档没有局面键,整局跳过局面层(沿用发布前的行为),下次存盘写入旧局标记",
					saveId);
			return legacyMarker();
		}
		return fromJson(node);
	}

	static BoxSceneState fromJson(JsonNode n) {
		if (!n.isObject()) {
			throw new IllegalArgumentException("局面键应为对象");
		}
		if (n.path("legacy").asBoolean(false)) {
			return legacyMarker();
		}
		BoxSceneState st = fresh();
		JsonNode g = n.get("g");
		if (g == null || !g.isInt() || g.asInt() < 0) {
			throw new IllegalArgumentException("局面键 g 缺失或非法");
		}
		st.g = g.asInt();
		st.result = enumOrNull(n.get("result"), Path.class, "result");
		JsonNode settled = n.get("settledTurn");
		if (settled != null && !settled.isNull()) {
			if (!settled.isInt()) {
				throw new IllegalArgumentException("局面键 settledTurn 非法");
			}
			st.settledTurn = settled.asInt();
		}
		st.situation = enumOrNull(n.get("situation"), Situation.class, "situation");
		if ((st.result == null) != (st.settledTurn == null)) {
			throw new IllegalArgumentException("局面键 result 与 settledTurn 须同时给出");
		}
		if (st.situation != null && st.result == null) {
			throw new IllegalArgumentException("局面键:未结算却有处境");
		}
		if (st.result != null && st.situation == null) {
			throw new IllegalArgumentException("局面键:已结算却没有处境");
		}
		JsonNode slots = n.path("slots");
		if (!slots.isMissingNode() && !slots.isNull()) {
			if (!slots.isObject()) {
				throw new IllegalArgumentException("局面键 slots 非法");
			}
			for (Map.Entry<String, JsonNode> e : slots.properties()) {
				if (!BoxScene.SLOTS.contains(e.getKey()) || !e.getValue().isString() || e.getValue().asString().isBlank()) {
					throw new IllegalArgumentException("局面键 slots 条目非法:" + e.getKey());
				}
				st.slots.put(e.getKey(), e.getValue().asString());
			}
		}
		for (JsonNode p : n.path("history")) {
			if (!p.path("turn").isInt() || !p.path("intent").isString()) {
				throw new IllegalArgumentException("局面键 history 条目非法");
			}
			st.history.add(new Pick(p.get("turn").asInt(), p.get("intent").asString()));
		}
		return st;
	}

	private static <E extends Enum<E>> E enumOrNull(JsonNode v, Class<E> type, String field) {
		if (v == null || v.isNull()) {
			return null;
		}
		if (!v.isString()) {
			throw new IllegalArgumentException("局面键 " + field + " 非法");
		}
		try {
			return Enum.valueOf(type, v.asString()); // 空串 / 未知值 → 抛,不猜默认值
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("局面键 " + field + " 非法:" + v.asString());
		}
	}

	public ObjectNode toJson(ObjectMapper mapper) {
		ObjectNode n = mapper.createObjectNode();
		if (legacy) {
			n.put("legacy", true);
			return n;
		}
		n.put("legacy", false);
		n.put("g", g);
		if (result == null) {
			n.putNull("result");
		} else {
			n.put("result", result.name());
		}
		if (settledTurn == null) {
			n.putNull("settledTurn");
		} else {
			n.put("settledTurn", settledTurn);
		}
		if (situation == null) {
			n.putNull("situation");
		} else {
			n.put("situation", situation.name());
		}
		ObjectNode s = n.putObject("slots");
		slots.forEach(s::put);
		var h = n.putArray("history");
		for (Pick p : history) {
			h.addObject().put("turn", p.turn()).put("intent", p.intent());
		}
		return n;
	}
}
