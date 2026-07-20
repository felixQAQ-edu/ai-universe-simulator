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

	// ── usage 观测(成本闸门读数):流末 usage 块解析 + 缺失容错 ──────────────

	private UsageCapture decodeCapturing(Reader reader) {
		UsageCapture cap = new UsageCapture(t -> {
		});
		decoder.decode(reader, cap);
		return cap;
	}

	@Test
	void parsesUsageBlockFromRecordedSample() throws Exception {
		// 录制的 DeepSeek 样本:usage 有 prompt/completion + prompt_cache_hit/miss(方言)、
		// 缺 total_tokens → total 容错记 -1;缓存两字段取真值(④ 成本闸门的命中率读数)。
		try (InputStream in = getClass().getResourceAsStream("/deepseek-sse-sample.txt")) {
			UsageCapture cap = decodeCapturing(new InputStreamReader(in, StandardCharsets.UTF_8));
			assertThat(cap.usage()).isEqualTo(new LlmUsage(12, 3, -1, 8, 4));
			assertThat(cap.usage().display())
					.isEqualTo("prompt=12 completion=3 total=- cacheHit=8 cacheMiss=4");
		}
	}

	@Test
	void parsesFullOpenAiStyleUsageIncludingTotal() {
		// 完整 OpenAI 口径:有 total、无 prompt_cache_*(非 DeepSeek 方言)→ 缓存两字段容错记 -1。
		String sse = """
				data: {"choices":[{"delta":{"content":"A"}}]}

				data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}

				data: [DONE]
				""";
		UsageCapture cap = decodeCapturing(new StringReader(sse));
		assertThat(cap.usage()).isEqualTo(new LlmUsage(100, 20, 120, -1, -1));
		// 三参便捷构造 = 缓存字段缺失(-1),与解析结果同值(既有调用点语义不变)。
		assertThat(cap.usage()).isEqualTo(new LlmUsage(100, 20, 120));
		assertThat(cap.usage().display())
				.isEqualTo("prompt=100 completion=20 total=120 cacheHit=- cacheMiss=-");
	}

	@Test
	void missingUsageBlockLeavesCaptureNullAndTokensUnaffected() {
		String sse = """
				data: {"choices":[{"delta":{"content":"A"}}]}

				data: [DONE]
				""";
		List<String> tokens = new ArrayList<>();
		UsageCapture cap = new UsageCapture(tokens::add);
		decoder.decode(new StringReader(sse), cap);
		assertThat(cap.usage()).isNull(); // 无 usage 块 → 静默,不告警
		assertThat(tokens).containsExactly("A");
	}

	@Test
	void malformedDataLineRaisesLlmException() {
		String sse = "data: {not valid json}\n\n";
		assertThatThrownBy(() -> decodeAll(new StringReader(sse)))
				.isInstanceOf(LlmException.class);
	}
}
