package com.aiuniverse.server.llm;

/**
 * 统一的运行模型(run-time)调用入口。所有 provider 走 OpenAI 兼容协议,换 provider 只改
 * {@link LlmProperties} 配置表 —— 这是 ADR-001 §5 抽象哲学的 Java 落地,与 bakeoff 的
 * {@code client.py} 同形。
 *
 * <p>{@link #streamChat} 阻塞执行,把生成的 token 逐个推给 {@link TokenStream};正常返回 =
 * 生成完成,抛异常 = 生成失败。web 传输细节(SSE/Flux)不在本接口的关心范围内。
 *
 * <p>骨架阶段只有 {@link MockLlmClient} 一个实现;接真实 DeepSeek 是下一个任务,届时新增
 * 一个 OpenAI 兼容实现,经 {@link LlmProperties} 选 provider、经 {@link ThinkingAdapter}
 * 注入各家非标参数。
 */
public interface LlmClient {

	void streamChat(ChatRequest request, TokenStream sink);
}
