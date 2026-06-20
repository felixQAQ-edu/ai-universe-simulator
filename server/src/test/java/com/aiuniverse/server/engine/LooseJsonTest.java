package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.llm.LlmException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 容错 JSON 解析单测(移植 {@code _parse_json}):剥围栏、取首个 {…}、失败干净降级。 */
class LooseJsonTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void parsesPlainJson() {
		JsonNode n = LooseJson.parse("{\"a\": 1}", mapper);
		assertThat(n.get("a").asInt()).isEqualTo(1);
	}

	@Test
	void stripsJsonFences() {
		String raw = "```json\n{\"a\": 1}\n```";
		assertThat(LooseJson.parse(raw, mapper).get("a").asInt()).isEqualTo(1);
	}

	@Test
	void stripsBareFences() {
		String raw = "```\n{\"a\": 2}\n```";
		assertThat(LooseJson.parse(raw, mapper).get("a").asInt()).isEqualTo(2);
	}

	@Test
	void extractsFirstObjectFromSurroundingProse() {
		String raw = "好的,这是结果:{\"a\": 3} 希望有帮助。";
		assertThat(LooseJson.parse(raw, mapper).get("a").asInt()).isEqualTo(3);
	}

	@Test
	void unparseableRaisesLlmException() {
		assertThatThrownBy(() -> LooseJson.parse("彻底不是 JSON", mapper))
				.isInstanceOf(LlmException.class);
	}
}
