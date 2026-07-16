package com.aiuniverse.server.llm;

/**
 * 最小流式 sink:LLM 核心逻辑把生成的 token 逐个吐给它,完全不知道下游是 SSE 还是别的传输。
 *
 * <p>这是 ADR-005 的承重接缝:核心逻辑只依赖本接口,web 层用薄适配把它桥到 {@code SseEmitter}。
 * 日后若换 WebFlux,只需另写一个把 token 推进 {@code Flux} 的适配,{@link LlmClient} 一行不改。
 *
 * <p>刻意保持函数式 + 单方法(可用 lambda),不搭抽象框架(YAGNI)。完成与异常由
 * {@link LlmClient#streamChat} 的正常返回 / 抛出来表达,不在本接口上堆回调。
 */
@FunctionalInterface
public interface TokenStream {
	void onToken(String token);

	/**
	 * 流末 usage 块回调(纯观测):默认 no-op,lambda 调用方零改动;需要读数的调用方
	 * (如 {@link UsageCapture})覆写。无 usage 块的流(如 mock)一次都不会被调用。
	 */
	default void onUsage(LlmUsage usage) {
	}
}
