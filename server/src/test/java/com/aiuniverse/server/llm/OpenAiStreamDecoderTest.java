package com.aiuniverse.server.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * 纯解析单测:用一段【录制的】DeepSeek SSE 样本驱动解码器,断言 token 序列。
 * 不打真实 API —— 确定性、零成本(本次任务的 TDD 核心)。
 */
class OpenAiStreamDecoderTest {

	private final OpenAiStreamDecoder decoder = new OpenAiStreamDecoder(new ObjectMapper());

	private List<String> decodeAll(Reader reader) {
		List<String> tokens = new ArrayList<>();
		decoder.decode(reader, tokens::add);
		return tokens;
	}

	@Test
	void emitsContentDeltasInOrderFromRecordedSample() throws Exception {
		try (InputStream in = getClass().getResourceAsStream("/deepseek-sse-sample.txt")) {
			assertThat(in).as("录制样本应存在于 test resources").isNotNull();
			List<String> tokens = decodeAll(new InputStreamReader(in, StandardCharsets.UTF_8));
			// role-only(content="")跳过、空 delta 跳过、usage(choices=[])跳过、[DONE] 终止。
			assertThat(tokens).containsExactly("雨", "夜", "便利店");
		}
	}

	@Test
	void skipsCommentsBlankLinesAndEmptyContent() {
		String sse = """
				: keep-alive

				data: {"choices":[{"delta":{"role":"assistant","content":""}}]}

				data: {"choices":[{"delta":{"content":"A"}}]}

				data: {"choices":[{"delta":{"content":"B"}}]}

				data: [DONE]
				""";
		assertThat(decodeAll(new StringReader(sse))).containsExactly("A", "B");
	}

	@Test
	void stopsAtDoneSentinelAndIgnoresTrailing() {
		String sse = """
				data: {"choices":[{"delta":{"content":"X"}}]}

				data: [DONE]

				data: {"choices":[{"delta":{"content":"SHOULD_NOT_APPEAR"}}]}
				""";
		assertThat(decodeAll(new StringReader(sse))).containsExactly("X");
	}

	@Test
	void malformedDataLineRaisesLlmException() {
		String sse = "data: {not valid json}\n\n";
		assertThatThrownBy(() -> decodeAll(new StringReader(sse)))
				.isInstanceOf(LlmException.class);
	}
}
