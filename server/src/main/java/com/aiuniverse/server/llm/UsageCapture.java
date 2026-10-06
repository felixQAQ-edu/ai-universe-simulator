package com.aiuniverse.server.llm;

/**
 * 捕获 usage 的 {@link TokenStream} 装饰器:token 原样透传给 delegate,{@code onUsage} 存起来供
 * 调用方在流结束后读取。无 usage 块(mock / provider 未回)时 {@link #usage()} 返回 null,
 * 调用方据此静默跳过日志(不告警)。
 */
public final class UsageCapture implements TokenStream {

	private final TokenStream delegate;
	private LlmUsage usage;
	private String model;
	private long reasoningChars;

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

	@Override
	public void onResponseMeta(String model, long reasoningChars) {
		this.model = model;
		this.reasoningChars = reasoningChars;
	}

	/** 响应里的 model 字段;未出现(或 mock)为 null。 */
	public String model() {
		return model;
	}

	/** reasoning_content 累计字符数;未出现为 0。 */
	public long reasoningChars() {
		return reasoningChars;
	}

	/**
	 * usage 日志行(回合与 world-gen 两处共用):{@link LlmUsage#display()} + {@code model=}
	 * + {@code reasoningChars=}。仅在 {@link #usage()} 非 null 时调用。
	 */
	public String logLine() {
		return usage.display() + " model=" + (model == null ? "-" : model) + " reasoningChars=" + reasoningChars;
	}

	/** 流结束后读取;无 usage 块返回 null。 */
	public LlmUsage usage() {
		return usage;
	}
}
