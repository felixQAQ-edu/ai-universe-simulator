package com.aiuniverse.server.web;

import java.io.IOException;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aiuniverse.server.eventloop.TurnEventSink;

import tools.jackson.databind.node.ObjectNode;

/**
 * 薄 web 适配(ADR-005 承重接缝):把 {@link TurnEventSink} 的命名事件桥到 {@link SseEmitter}。
 * 唯一碰 SSE 的 event-loop 出网点;核心({@link com.aiuniverse.server.eventloop.EventLoopService})
 * 对传输无感知。事件名对齐规格 §4.2:{@code narrative}/{@code delta}/{@code ending}/{@code error}。
 *
 * <p>消毒由服务层保证(传入的 {@code delta}/{@code ending} 已过 {@code toClientState});本类只转发。
 */
public final class SseTurnEventSink implements TurnEventSink {

	private final SseEmitter emitter;

	public SseTurnEventSink(SseEmitter emitter) {
		this.emitter = emitter;
	}

	@Override
	public void narrative(String text) {
		send("narrative", java.util.Map.of("text", text));
	}

	@Override
	public void delta(ObjectNode sanitizedDelta) {
		send("delta", sanitizedDelta);
	}

	@Override
	public void ending(ObjectNode sanitizedEnding) {
		send("ending", sanitizedEnding);
	}

	@Override
	public void error(String code, String message) {
		send("error", java.util.Map.of("code", code, "message", message));
	}

	private void send(String event, Object data) {
		try {
			emitter.send(SseEmitter.event().name(event).data(data));
		} catch (IOException e) {
			// 客户端断开等:转非受检冒泡,由外层 completeWithError 收口。
			throw new IllegalStateException("SSE 推送失败", e);
		}
	}
}
