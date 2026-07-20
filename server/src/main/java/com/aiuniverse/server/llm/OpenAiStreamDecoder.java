package com.aiuniverse.server.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容流式响应解码器 —— 纯解析,不碰网络。把 {@code chat.completions}(stream=true)的
 * SSE 文本逐行解出 {@code choices[0].delta.content},逐个吐给 {@link TokenStream}。
 *
 * <p>移植自 bakeoff {@code client.py} 的 chunk 消费循环(只取 {@code delta.content},空串/无 content
 * 跳过;{@code choices=[]} 的流末 usage 块解出 token 用量走 {@link TokenStream#onUsage} 纯观测回调)。把它独立成纯函数,正是为了能用一段【录制样本】做
 * 确定性单测,无需打真实 API。
 */
public class OpenAiStreamDecoder {

	private static final String DATA_PREFIX = "data:";
	private static final String DONE = "[DONE]";

	private final ObjectMapper mapper;

	public OpenAiStreamDecoder(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * 逐行读 SSE 流,把每个 content delta 推给 {@code sink}。读到 {@code data: [DONE]} 或流自然
	 * 结束即返回。读流 IO 失败 → {@link LlmException}(流中断的干净降级);单行 JSON 解析失败同理。
	 */
	public void decode(Reader reader, TokenStream sink) {
		BufferedReader br = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
		try {
			String line;
			while ((line = br.readLine()) != null) {
				String trimmed = line.strip();
				// 空行(事件分隔)与以 ':' 开头的注释(如 keep-alive)直接跳过。
				if (trimmed.isEmpty() || trimmed.startsWith(":")) {
					continue;
				}
				if (!trimmed.startsWith(DATA_PREFIX)) {
					continue;
				}
				String payload = trimmed.substring(DATA_PREFIX.length()).strip();
				if (DONE.equals(payload)) {
					return;
				}
				String content = extractContent(payload, sink);
				if (content != null && !content.isEmpty()) {
					sink.onToken(content);
				}
			}
		} catch (IOException e) {
			throw new LlmException("读取模型流式响应中断", e);
		}
	}

	/**
	 * 解出一个 data chunk 的 {@code choices[0].delta.content};无内容(usage/空 delta)返回 null。
	 * chunk 若带 {@code usage} 对象(stream_options.include_usage 的流末块),顺带回调
	 * {@code sink.onUsage}(纯观测;缺字段容错记 -1,无 usage 的 chunk 不回调)。
	 */
	private String extractContent(String json, TokenStream sink) {
		JsonNode node;
		try {
			node = mapper.readTree(json);
		} catch (JacksonException e) {
			throw new LlmException("解析模型流式响应失败", e);
		}
		JsonNode usage = node.path("usage");
		if (usage.isObject()) {
			sink.onUsage(new LlmUsage(
					usage.path("prompt_tokens").asLong(-1),
					usage.path("completion_tokens").asLong(-1),
					usage.path("total_tokens").asLong(-1),
					usage.path("prompt_cache_hit_tokens").asLong(-1),
					usage.path("prompt_cache_miss_tokens").asLong(-1)));
		}
		JsonNode choices = node.path("choices");
		if (!choices.isArray() || choices.isEmpty()) {
			return null; // usage-only 块
		}
		JsonNode content = choices.get(0).path("delta").path("content");
		return content.isTextual() ? content.asText() : null;
	}
}
