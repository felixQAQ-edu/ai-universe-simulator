package com.aiuniverse.server.eventloop;

import com.aiuniverse.server.engine.LooseJson;
import com.aiuniverse.server.llm.LlmException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 叙事回灌(规格 §4.4 的关键一步)—— 把流式时移出 JSON 的叙事 {@code N} 灌回结构化尾巴解析出的节点,
 * 使 {@code TURN_SCHEMA} / {@code Engine.apply} 的 {@code narrative} 依赖<b>一行不改</b>地复用。
 *
 * <pre>
 *   tail string → LooseJson.parse → ObjectNode → put("narrative", N)
 * </pre>
 *
 * <p><b>不变量(规格 §9):永远校验回灌后的节点,绝不校验裸 tail</b>——裸尾巴无 {@code narrative} 字段,
 * 校验它必误挂在 narrative minLength 上。本类只负责「解析 + 回灌」,校验/落账交给上层。
 */
public final class TurnReinfuser {

	private TurnReinfuser() {
	}

	/**
	 * 解析结构化尾巴并回灌叙事。镜像 Python {@code parsed["narrative"] = N}。
	 *
	 * @param tail      哨兵后的结构化尾巴(可能裹围栏 / 带前后噪声,交 {@link LooseJson} 容错)
	 * @param narrative 已流给玩家的 canonical 叙事(回灌进 {@code parsed["narrative"]})
	 * @return 回灌后的可变 {@link ObjectNode}(供 validateTurn / Engine.apply)
	 * @throws LlmException 尾巴解析失败或非 JSON 对象(交上层修复 / no-op)
	 */
	public static ObjectNode reinfuse(String tail, String narrative, ObjectMapper mapper) {
		JsonNode parsed = LooseJson.parse(tail, mapper); // 解析失败 → LooseJson 抛 LlmException
		if (!parsed.isObject()) {
			throw new LlmException("结构化尾巴解析结果非 JSON 对象");
		}
		ObjectNode obj = (ObjectNode) parsed;
		obj.put("narrative", narrative); // 关键:回灌 canonical 叙事
		return obj;
	}
}
