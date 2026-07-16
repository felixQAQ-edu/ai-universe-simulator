package com.aiuniverse.server.llm;

/**
 * 捕获 usage 的 {@link TokenStream} 装饰器:token 原样透传给 delegate,{@code onUsage} 存起来供
 * 调用方在流结束后读取。无 usage 块(mock / provider 未回)时 {@link #usage()} 返回 null,
 * 调用方据此静默跳过日志(不告警)。
 */
public final class UsageCapture implements TokenStream {

	private final TokenStream delegate;
	private LlmUsage usage;

	public UsageCapture(TokenStream delegate) {
		this.delegate = delegate;
	}

	@Override
	public void onToken(String token) {
		delegate.onToken(token);
	}

	@Override
	public void onUsage(LlmUsage usage) {
		this.usage = usage;
	}

	/** 流结束后读取;无 usage 块返回 null。 */
	public LlmUsage usage() {
		return usage;
	}
}
