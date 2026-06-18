package com.aiuniverse.server.web;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.moderation.ModerationGateway;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 薄 web 适配层(ADR-005 的承重接缝):唯一碰 {@link SseEmitter} 的地方。
 * 把核心吐出的 token 桥到 SSE,核心({@link LlmClient})对传输无感知。
 *
 * <p>骨架阶段只有一个 dev 冒烟端点,刻意不假装成 event-loop 接口。日后换 WebFlux 时,
 * 只需把本类改写成往 {@code Flux} 推 token,{@code llm/}、{@code moderation/} 一行不动。
 */
@RestController
@RequestMapping("/api/dev")
public class StreamController {

	private final LlmClient llm;
	private final ModerationGateway moderation;
	/** 骨架用线程池:SSE 是阻塞长连接,不能占 Tomcat 容器线程。真上线时换托管/有界线程池。 */
	private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

	public StreamController(LlmClient llm, ModerationGateway moderation) {
		this.llm = llm;
		this.moderation = moderation;
	}

	@PostMapping("/echo-stream")
	public SseEmitter echoStream(@Valid @RequestBody EchoRequest request) {
		// ADR-004 审核接缝:先审 prompt 入参(骨架走 no-op 放行)。
		String prompt = moderation.review(request.prompt());
		SseEmitter emitter = new SseEmitter(60_000L);

		streamExecutor.execute(() -> {
			try {
				// lambda 即 TokenStream:把每个 token 桥到 SSE。
				llm.streamChat(new ChatRequest(prompt), token -> sendQuietly(emitter, token));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			}
		});
		return emitter;
	}

	private static void sendQuietly(SseEmitter emitter, String token) {
		try {
			emitter.send(SseEmitter.event().data(token));
		} catch (IOException e) {
			// 客户端断开等:转成非受检异常冒泡,由外层 completeWithError 收口。
			throw new IllegalStateException("SSE 推送失败", e);
		}
	}

	@PreDestroy
	void shutdown() {
		streamExecutor.shutdown();
	}

	/** dev 冒烟请求体。 */
	public record EchoRequest(@NotBlank String prompt) {
	}
}
