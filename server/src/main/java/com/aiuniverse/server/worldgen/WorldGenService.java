package com.aiuniverse.server.worldgen;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aiuniverse.server.engine.GameSchemas;
import com.aiuniverse.server.engine.LooseJson;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * INITIALIZING 胖调用(设计稿 §2/§4、ADR-007)——一发 world-gen 产出完整世界根对象,
 * {@code validateWorld} 校验,失败一次修复,仍败 → 整局 {@link WorldGenException ERROR}。
 *
 * <p><b>线上口径(异于回合)</b>:全程 {@code response_format: json_object}、纯 JSON、无哨兵。
 * 故无叙事回灌问题(narrative/openingNarrative 本就是 JSON 字段),解析直接 {@link LooseJson} 即可。
 *
 * <p><b>复用零改</b>:{@link GameSchemas#validateWorld}(第一批移植 + 8 golden accept-parity)、
 * {@link LooseJson}(容错解析)原样复用,本类只编排「胖调用 + 解析校验 + 一次修复 + ERROR」。
 *
 * <p><b>与回合降级的对照</b>:回合修复用尽 → 保守 no-op(守一局 ongoing);world-gen 修复用尽 →
 * ERROR(无前态可守,干净重来)。两套口径刻意不同(设计稿 §4)。
 */
@Service
public class WorldGenService {

	private static final Logger log = LoggerFactory.getLogger(WorldGenService.class);

	private final LlmClient llm;
	private final WorldGenPromptBuilder prompts;
	private final ObjectMapper mapper;

	public WorldGenService(LlmClient llm, WorldGenPromptBuilder prompts, ObjectMapper mapper) {
		this.llm = llm;
		this.prompts = prompts;
		this.mapper = mapper;
	}

	/**
	 * 跑一发 world-gen 胖调用,返回<b>已过 {@code validateWorld} 校验</b>的完整世界根对象
	 * (含 isTrue/hiddenLogic、availableActions、openingNarrative 等模型产出原貌;消毒/提取在播种层)。
	 *
	 * @param archetype Phase 1 固定 {@code rules_creepy}(透传以备多模式)
	 * @return 校验通过的世界根 {@link ObjectNode}
	 * @throws WorldGenException 调用失败,或一次修复后仍未过校验(整局 ERROR)
	 */
	public ObjectNode generate(String archetype) {
		String prompt = prompts.buildWorldPrompt(archetype);
		String raw = call(prompt); // 主调用(开 json_object)

		List<String> errors = new ArrayList<>();
		ObjectNode parsed = tryParse(raw, errors);
		if (parsed != null) {
			return parsed;
		}

		// 一次修复(设计稿 §4.3):带校验错误回喂「只回修正后的完整 world JSON」,同样开 json_object。
		log.warn("[world-gen] archetype={} 首次产出未过校验({} 条),触发一次修复:{}", archetype, errors.size(), errors);
		String repairPrompt = prompts.buildRepairPrompt(archetype, raw, errors);
		String raw2 = call(repairPrompt);

		List<String> errors2 = new ArrayList<>();
		ObjectNode parsed2 = tryParse(raw2, errors2);
		if (parsed2 != null) {
			return parsed2;
		}

		// 修复仍败 → 整局 ERROR(设计稿 §4.4:无前态可守,不进半残 PLAYING)。
		log.error("[world-gen] 修复后仍未过校验({} 条),整局 ERROR:{}", errors2.size(), errors2);
		throw new WorldGenException("世界生成失败,请重新生成");
	}

	/** 胖调用:累积流式 token 成整串(world-gen 不逐字流给玩家,叙事随 init 一次性下发)。开 json_object。 */
	private String call(String prompt) {
		StringBuilder buf = new StringBuilder();
		try {
			llm.streamChat(new ChatRequest(prompt, true), buf::append);
		} catch (LlmException e) {
			// 调用本身失败(网络/非 200/缺 key/流中断)→ 无可修复内容 → 直接 ERROR(干净重来)。
			throw new WorldGenException("世界生成调用失败,请重新生成", e);
		}
		return buf.toString();
	}

	/** {@link LooseJson} 解析 + {@code validateWorld};通过返回节点,否则把错误写入 {@code out} 返 null。 */
	private ObjectNode tryParse(String raw, List<String> out) {
		JsonNode parsed;
		try {
			parsed = LooseJson.parse(raw, mapper);
		} catch (LlmException e) {
			out.add("产出非合法 JSON:" + e.getMessage());
			return null;
		}
		if (!parsed.isObject()) {
			out.add("产出顶层非 JSON 对象");
			return null;
		}
		List<String> errors = GameSchemas.validateWorld(parsed);
		if (!errors.isEmpty()) {
			out.addAll(errors);
			return null;
		}
		return (ObjectNode) parsed;
	}
}
