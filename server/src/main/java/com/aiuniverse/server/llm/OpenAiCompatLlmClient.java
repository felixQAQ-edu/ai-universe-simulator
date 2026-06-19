package com.aiuniverse.server.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 真实运行模型的 OpenAI 兼容实现(ADR-001):读 {@link LlmProperties} 配置表选定的 provider,
 * 调其 {@code /chat/completions}(stream=true),把返回的 SSE 流逐 token 吐给 {@link TokenStream}。
 *
 * <p>行为移植自 Phase 0 已验证的 bakeoff {@code client.py}(请求构造 + 流式消费 + 思考开关 extra_body),
 * 但传输换成 JDK 内置 {@link HttpClient} —— 零新增依赖,JSON 用随 starter-web 而来的 Jackson。
 *
 * <p><b>接缝纪律(ADR-005):</b>本类只认 {@link TokenStream},完全不知道下游是 SSE 还是 Flux;
 * web 层一行不动即可消费它,印证接缝设计成立。
 *
 * <p><b>降级纪律:</b>缺 key / 网络失败 / 非 200 / 流中断统统收口成 {@link LlmException}(干净中文、
 * 不含 key),由 web 层 {@code completeWithError} 收尾,绝不把原始异常/敏感串泄给前端。
 */
public class OpenAiCompatLlmClient implements LlmClient {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
	private static final double TEMPERATURE = 0.7;
	private static final int ERROR_SNIPPET_LIMIT = 500;

	private final LlmProperties.Provider provider;
	private final ThinkingAdapter thinkingAdapter;
	private final ObjectMapper mapper;
	private final OpenAiStreamDecoder decoder;
	private final HttpClient httpClient;
	/** 注入式 env 查询(默认 {@code System::getenv}),便于单测注入假 key、绝不在测试里读真实环境。 */
	private final UnaryOperator<String> env;

	public OpenAiCompatLlmClient(LlmProperties.Provider provider, ThinkingAdapter thinkingAdapter,
			ObjectMapper mapper, UnaryOperator<String> env) {
		this.provider = provider;
		this.thinkingAdapter = thinkingAdapter;
		this.mapper = mapper;
		this.env = env;
		this.decoder = new OpenAiStreamDecoder(mapper);
		this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
	}

	@Override
	public void streamChat(ChatRequest request, TokenStream sink) {
		String key = env.apply(provider.apiKeyEnv());
		if (key == null || key.isBlank()) {
			throw new LlmException("环境变量 " + provider.apiKeyEnv() + " 未设置,无法调用真实模型(可改 active=mock 回退)");
		}

		HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(endpoint())
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.header("Accept", "text/event-stream")
				.header("Authorization", "Bearer " + key)
				.POST(HttpRequest.BodyPublishers.ofString(buildBody(request), StandardCharsets.UTF_8))
				.build();

		HttpResponse<InputStream> response;
		try {
			response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException e) {
			throw new LlmException("调用模型服务网络失败", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmException("调用模型服务被中断", e);
		}

		if (response.statusCode() != 200) {
			throw httpError(response.statusCode(), readSnippet(response.body()));
		}

		try (Reader reader = new InputStreamReader(response.body(), StandardCharsets.UTF_8)) {
			decoder.decode(reader, sink);
		} catch (IOException e) {
			throw new LlmException("读取模型流式响应中断", e);
		}
	}

	private URI endpoint() {
		String base = provider.baseUrl().replaceAll("/+$", "");
		return URI.create(base + "/chat/completions");
	}

	/**
	 * 构造 OpenAI 兼容请求体。骨架阶段把 prompt 作单条 user 消息(不碰 event-loop 契约/json_mode,
	 * 那是下个任务)。思考开关经 {@link ThinkingAdapter} 单点注入到顶层(等价 Python SDK 的 extra_body)。
	 * 包私有以便不打网络做断言。
	 */
	String buildBody(ChatRequest request) {
		ObjectNode root = mapper.createObjectNode();
		root.put("model", provider.model());
		root.put("stream", true);
		root.put("temperature", TEMPERATURE);
		root.putObject("stream_options").put("include_usage", true);

		ArrayNode messages = root.putArray("messages");
		ObjectNode userMsg = messages.addObject();
		userMsg.put("role", "user");
		userMsg.put("content", request.prompt());

		// extra_body:OpenAI SDK 把它平铺进请求体顶层,这里等价地把每个键 set 到 root。
		Map<String, Object> extra = thinkingAdapter.extraBody(provider);
		extra.forEach((k, v) -> root.set(k, mapper.valueToTree(v)));

		try {
			return mapper.writeValueAsString(root);
		} catch (JacksonException e) {
			throw new LlmException("序列化模型请求体失败", e);
		}
	}

	/** 非 200 → 干净异常。带上状态码与截断的错误体(响应体不含 key),便于排障但不泄敏感串。 */
	static LlmException httpError(int status, String bodySnippet) {
		String tail = (bodySnippet == null || bodySnippet.isBlank()) ? "" : ": " + bodySnippet;
		return new LlmException("模型服务返回 HTTP " + status + tail);
	}

	private String readSnippet(InputStream body) {
		try (InputStream in = body) {
			byte[] bytes = in.readNBytes(ERROR_SNIPPET_LIMIT);
			return new String(bytes, StandardCharsets.UTF_8).strip();
		} catch (IOException e) {
			return "";
		}
	}
}
