package com.aiuniverse.server.engine;

import com.aiuniverse.server.llm.LlmException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 容错 JSON 解析 —— 忠实移植 bakeoff {@code scenarios.py} 的 {@code _parse_json}。
 *
 * <p>模型偶尔会把结构化输出裹在 ```json 围栏里、或在 JSON 前后带点解释文字。此工具:
 * (1) 剥掉首尾可能的 ```json / ``` 围栏;(2) 直接 parse;(3) 失败则取首个 '{' 到末个 '}'
 * 的子串再 parse(对应 Python 的 {@code re.search(r"\{.*\}", s, re.S)} 贪婪匹配)。
 * 仍失败 → {@link LlmException}(干净降级,交给上层修复/no-op)。
 */
public final class LooseJson {

	private LooseJson() {
	}

	public static JsonNode parse(String raw, ObjectMapper mapper) {
		String s = (raw == null ? "" : raw).strip();
		// 剥首尾围栏(对应 Python re.sub 的两个分支:开头 ```json、结尾 ```)。
		s = s.replaceFirst("(?s)\\A```(?:json)?\\s*", "");
		s = s.replaceFirst("(?s)\\s*```\\z", "");
		try {
			return mapper.readTree(s);
		} catch (JacksonException first) {
			int i = s.indexOf('{');
			int j = s.lastIndexOf('}');
			if (i >= 0 && j > i) {
				try {
					return mapper.readTree(s.substring(i, j + 1));
				} catch (JacksonException second) {
					throw new LlmException("结构化尾巴 JSON 解析失败", second);
				}
			}
			throw new LlmException("结构化尾巴 JSON 解析失败", first);
		}
	}
}
