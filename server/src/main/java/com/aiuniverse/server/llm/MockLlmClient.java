package com.aiuniverse.server.llm;

/**
 * 离线 mock 实现:不连任何 provider,把 prompt 包一层后逐字吐回,用来证明流式通路成立。
 * 真实 DeepSeek 走 {@link OpenAiCompatLlmClient};本类保留作默认 / 回退({@code active=mock})。
 *
 * <p>由 {@link LlmClientConfig} 按 {@code aiuniverse.llm.active} 决定是否选用(故不再挂 {@code @Component},
 * 避免与真实实现产生「两个 LlmClient bean」歧义);每个字符间插一个很小的停顿,让 SSE 的逐字流式
 * 在 {@code curl -N} 下肉眼可见。
 */
public class MockLlmClient implements LlmClient {

	private final LlmProperties properties;

	public MockLlmClient(LlmProperties properties) {
		this.properties = properties;
	}

	@Override
	public void streamChat(ChatRequest request, TokenStream sink) {
		String active = properties == null ? "mock" : properties.active();
		String reply = "[mock:" + active + "] 你说:" + request.prompt();
		for (int i = 0; i < reply.length(); i++) {
			sink.onToken(String.valueOf(reply.charAt(i)));
			sleepTiny();
		}
	}

	private void sleepTiny() {
		try {
			Thread.sleep(40);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("mock 流式被中断", e);
		}
	}
}
