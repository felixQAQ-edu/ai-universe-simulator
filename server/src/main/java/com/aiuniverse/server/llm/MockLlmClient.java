package com.aiuniverse.server.llm;

import org.springframework.stereotype.Component;

/**
 * 骨架阶段的 mock 实现:不连任何 provider,把 prompt 包一层后逐字吐回,用来证明流式通路成立。
 * 接真实 DeepSeek 是下一个任务(届时新增一个 OpenAI 兼容实现,本类可保留作 {@code --mock} 用途)。
 *
 * <p>默认激活(配置 {@code aiuniverse.llm.active=mock});每个字符间插一个很小的停顿,让 SSE 的
 * 逐字流式在 {@code curl -N} 下肉眼可见。
 */
@Component
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
