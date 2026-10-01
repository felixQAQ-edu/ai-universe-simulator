package com.aiuniverse.server.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * world-gen 产出的<b>结局顺序后处理</b>(ADR-028 刀 2b)。
 *
 * <p>为什么要有它:引擎在致命轴触底时按 {@code pickFailureEnding} 的顺序兜底挑结局 —— 先找 condition 提到
 * 致命轴中文名的 failure 结局,再退到第一条 failure。《动物人生》的结局池里有两条 condition 都会提到
 * 【身子】的 failure:「太近了」(只许屋外)与「撞上」(身子中途归零)。模型若把「太近了」排在前面,
 * 新屋里身子归零也会被兜底成「太近了」—— 而那一条明写不得用来惩罚新屋。
 *
 * <p>做法:只对<b>新生成的</b>单体《动物人生》世界,把标题为「撞上」的那条确定性地挪到所有
 * {@code outcome=failure} 结局之前(其余结局相对顺序不变)。<b>引擎一行不动</b>(它只读顺序);
 * 其他世界原样返回、一个字节都不变。找不到「撞上」→ 记 WARN、不改顺序(不替模型补一条)。
 */
final class EndingOrder {

	private static final Logger log = LoggerFactory.getLogger(EndingOrder.class);

	/** archetype → 致命轴触底时必须先被兜底命中的结局标题。 */
	private static final Map<String, String> BOTTOM_OUT_FIRST = Map.of("animal_life", "撞上");

	private EndingOrder() {
	}

	static ObjectNode apply(List<String> archetypes, ObjectNode world) {
		if (archetypes.size() != 1) {
			return world;
		}
		String title = BOTTOM_OUT_FIRST.get(archetypes.get(0));
		if (title == null || !(world.get("endings") instanceof ArrayNode endings)) {
			return world;
		}
		int target = -1;
		int firstFailure = -1;
		for (int i = 0; i < endings.size(); i++) {
			JsonNode e = endings.get(i);
			if (target < 0 && title.equals(e.path("title").asString(""))) {
				target = i;
			}
			if (firstFailure < 0 && "failure".equals(e.path("outcome").asString(""))) {
				firstFailure = i;
			}
		}
		if (target < 0) {
			log.warn("[world-gen] archetype={} 结局池里没有「{}」,结局顺序不做调整", archetypes.get(0), title);
			return world;
		}
		if (firstFailure < 0 || target <= firstFailure) {
			return world;
		}
		List<JsonNode> order = new ArrayList<>();
		endings.forEach(order::add);
		JsonNode moved = order.remove(target);
		order.add(firstFailure, moved);
		endings.removeAll();
		order.forEach(endings::add);
		return world;
	}
}
