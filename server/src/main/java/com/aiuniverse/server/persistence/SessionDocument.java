package com.aiuniverse.server.persistence;

import java.util.ArrayList;
import java.util.List;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.TurnPhase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 会话快照文档的<b>唯一</b>编解码(ADR-015 C3 的文档形态;ADR-025 刀 1 抽出,供文件与 DB 两个
 * {@link SessionStore} 实现共用)。两个实现存的是<b>同一份文档</b>:文件实现写进 {@code <saveId>.json},
 * DB 实现写进 {@code game_session.snapshot}(JSONB)。
 *
 * <p>抽出来的理由写死:ADR-025 已知代价 1 点名「DB 实现可以在文件实现绿的掩护下写错一个字段」——
 * 两个实现各写一份「差不多」的解析,正是那个掩护的来源。故编码、形状判据、还原三件事只在这里各有一份。
 *
 * <p>⚠️ 开场叙事({@link GameSession#openingNarrative()})<b>不进本文档</b>(ADR-025 已决 2 /
 * ADR-007「openingNarrative 不进持久化 state」):它只进 DB 实现的 {@code game_event} turn 0。
 */
public final class SessionDocument {

	private SessionDocument() {
	}

	/** 快照文档 = {@code Engine.toPersistedState()}(视图 1 全量)+ session 层 {@code currentActions}/{@code phaseHint}。 */
	public static ObjectNode encode(GameSession session, ObjectMapper mapper) {
		ObjectNode doc = session.engine().toPersistedState();
		ArrayNode actions = session.currentActions();
		doc.set("currentActions", actions == null ? mapper.createArrayNode() : actions.deepCopy());
		doc.put("phaseHint", session.phase().get().name()); // 仅取证用;回载按 status 重置,不读它
		return doc;
	}

	/**
	 * 形状判据:存档文档必带 {@code world} 对象节点。
	 *
	 * <p>⚠️ <b>本判定只为分流日志级别,不替代 {@code Engine.restore} 的合法性闸门</b>
	 * ({@code Engine.restore} 对 {@code world} 缺失/非对象照旧抛「持久化文档缺 world」,拒载不半载)。
	 * <b>两处判据必须一致,改一处必须看另一处</b>——同一个判断落在两个地方,是漂移的种子。
	 */
	public static boolean isSaveDocument(JsonNode doc) {
		return doc != null && doc.path("world").isObject();
	}

	/**
	 * 还原为会话:轴语义集不落盘,由 {@code world.archetypes} 经 registry 原路重派生(与播种同一真理源);
	 * phase 按 status 重置(AtomicReference 运行时态不落盘,ADR-015 勘察 2)。任何不合法一律抛(拒载不半载)。
	 */
	public static GameSession decode(String saveId, JsonNode doc, ObjectMapper mapper, ArchetypeRegistry archetypes) {
		List<String> ids = new ArrayList<>();
		doc.path("world").path("archetypes").forEach(a -> ids.add(a.asString("")));
		List<AttributeAxis> axes = archetypes.resolveAxes(ids);
		Engine engine = Engine.restore(doc, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		JsonNode actions = doc.get("currentActions");
		ArrayNode initial = actions != null && actions.isArray()
				? (ArrayNode) actions.deepCopy()
				: mapper.createArrayNode();
		GameSession session = new GameSession(saveId, engine, initial);
		session.phase().set("ended".equals(engine.status()) ? TurnPhase.ENDED : TurnPhase.AWAITING_ACTION);
		return session;
	}
}
