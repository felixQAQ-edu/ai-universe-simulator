package com.aiuniverse.server.engine;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * 泄露检测 —— 忠实移植 bakeoff {@code schema.py} 的 {@code detect_leak}(对齐 CONTEXT §三.9)。
 *
 * <p><b>语义口径(规格 §1c):流式路径里这是【事后遥测】,不是实时拦截器。</b>叙事已逐字流给玩家,
 * 流完才扫;且只抓两类【逐字】泄露:(1) 文本里出现引擎专用字段名({@link #LEAK_TOKENS});
 * (2) 整段照抄了某条规则 {@code hiddenLogic}(≥8 字符子串)。<b>抓不到改写/复述式泄露</b> ——
 * 实时防护靠结构层消毒({@link Engine#toClientState()})+ 提示词硬禁,非靠它。命中只用于记日志/标记存档复核。
 */
public final class LeakDetector {

	/** 引擎/作者视角专用字段名:任何出现在玩家可见文本里都算泄露证据。 */
	public static final List<String> LEAK_TOKENS =
			List.of("isTrue", "hiddenLogic", "isCorrect", "groundTruth");

	private LeakDetector() {
	}

	/**
	 * 扫一段玩家可见文本,返回命中的泄露证据(空 = 干净)。
	 *
	 * @param playerVisibleText 玩家可见叙事
	 * @param world             真理之源世界(用于比对 hiddenLogic 原文);可为 null
	 */
	public static List<String> detect(String playerVisibleText, JsonNode world) {
		List<String> hits = new ArrayList<>();
		String text = playerVisibleText == null ? "" : playerVisibleText;
		for (String tok : LEAK_TOKENS) {
			if (text.contains(tok)) {
				hits.add("出现引擎字段名 '" + tok + "'");
			}
		}
		if (world != null) {
			for (JsonNode rule : world.path("rules")) {
				String hl = rule.path("hiddenLogic").asString("").strip();
				if (hl.length() >= 8 && text.contains(hl)) {
					hits.add("照抄 rule#" + rule.path("id").asInt() + " 的 hiddenLogic");
				}
			}
		}
		return hits;
	}
}
