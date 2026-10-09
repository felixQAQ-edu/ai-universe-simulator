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
 *
 * <p><b>响应元信息(纯观测,2026-10-06)</b>:顺带记下响应里的 {@code model} 字段(取最后一个非空值)与
 * {@code choices[0].delta.reasoning_content} 的累计字符数(按 code point 计,<b>只记数、不记内容、不转发</b>),
 * 在流正常结束({@code [DONE]} 或自然 EOF)时经 {@link TokenStream#onResponseMeta} 回调一次。
 * 用途:确认线上实际应答的模型(F-036)与思考开关是否真的关上(reasoningChars 应为 0)。
 * 流中途 IO 失败则不回调(与 usage 同一口径:没有完整响应就没有读数)。
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
		Meta meta = new Meta();
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
					sink.onResponseMeta(meta.model, meta.reasoningChars);
					return;
				}
				String content = extractContent(payload, sink, meta);
				if (content != null && !content.isEmpty()) {
					sink.onToken(content);
				}
			}
			sink.onResponseMeta(meta.model, meta.reasoningChars);
		} catch (IOException e) {
			throw new LlmException("读取模型流式响应中断", e);
		}
	}

	/** 单次 decode 的响应元信息累加器(不跨调用共享)。 */
	private static final class Meta {
		String model;
		long reasoningChars;
	}

	/**
	 * 解出一个 data chunk 的 {@code choices[0].delta.content};无内容(usage/空 delta)返回 null。
	 * chunk 若带 {@code usage} 对象(stream_options.include_usage 的流末块),顺带回调
	 * {@code sink.onUsage}(纯观测;缺字段容错记 -1,无 usage 的 chunk 不回调)。
	 */
	private String extractContent(String json, TokenStream sink, Meta meta) {
		JsonNode node;
		try {
			node = mapper.readTree(json);
		} catch (JacksonException e) {
			throw new LlmException("解析模型流式响应失败", e);
		}
		JsonNode model = node.path("model");
		if (model.isString() && !model.asString().isBlank()) {
			meta.model = model.asString();
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
		JsonNode delta = choices.get(0).path("delta");
		JsonNode reasoning = delta.path("reasoning_content");
		if (reasoning.isString()) {
			String r = reasoning.asString();
			meta.reasoningChars += r.codePointCount(0, r.length());
		}
		JsonNode content = delta.path("content");
		return content.isString() ? content.asString() : null;
	}
}
