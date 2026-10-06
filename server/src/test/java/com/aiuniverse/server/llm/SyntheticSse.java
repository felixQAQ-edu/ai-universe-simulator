package com.aiuniverse.server.llm;

import java.io.StringReader;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 测试用:合成一段 OpenAI 兼容 SSE 流,并提供一个用<b>真解码器</b>回放它的 {@link LlmClient}。
 *
 * <p>用途:usage 日志的 {@code model=} / {@code reasoningChars=} 必须从<b>解码器</b>一路走到日志,
 * 而不是测试自己调 {@code onResponseMeta} —— 后者绕过了要守的那段接线。
 * 录制样本 {@code deepseek-sse-sample.txt} 保持原样(它记录的是当时的真实响应),新形态一律合成。
 */
public final class SyntheticSse {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SyntheticSse() {
	}

	/**
	 * @param model     每个 chunk 的 {@code model} 字段;null = 不带该字段
	 * @param reasoning 思考段(各段一个 chunk,经 {@code reasoning_content});可为空
	 * @param content   正文(整段一个 chunk)
	 */
	public static String stream(String model, String[] reasoning, String content) {
		StringBuilder sb = new StringBuilder();
		for (String r : reasoning) {
			ObjectNode c = chunk(model);
			c.putArray("choices").addObject().putObject("delta").put("reasoning_content", r);
			sb.append("data: ").append(c).append("\n\n");
		}
		ObjectNode c = chunk(model);
		c.putArray("choices").addObject().putObject("delta").put("content", content);
		sb.append("data: ").append(c).append("\n\n");
		ObjectNode u = chunk(model);
		u.putArray("choices");
		u.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 20)
				.put("prompt_cache_hit_tokens", 60).put("prompt_cache_miss_tokens", 40);
		sb.append("data: ").append(u).append("\n\n");
		sb.append("data: [DONE]\n\n");
		return sb.toString();
	}

	private static ObjectNode chunk(String model) {
		ObjectNode c = MAPPER.createObjectNode();
		c.put("object", "chat.completion.chunk");
		if (model != null) {
			c.put("model", model);
		}
		return c;
	}

	/** 每次 streamChat 都用真解码器回放同一段合成流(world-gen 只调一次、回合主调用只调一次)。 */
	public static LlmClient replaying(String sse) {
		OpenAiStreamDecoder decoder = new OpenAiStreamDecoder(MAPPER);
		return (request, sink) -> decoder.decode(new StringReader(sse), sink);
	}
}
