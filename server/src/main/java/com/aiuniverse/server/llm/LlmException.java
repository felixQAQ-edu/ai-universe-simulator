package com.aiuniverse.server.llm;

/**
 * 运行模型调用域内的统一失败类型:网络失败 / 非 200 / 流中断 / 缺 key / 解析失败都收口成它。
 *
 * <p>纪律:message 只放给运维/日志看的干净中文,<b>绝不</b>拼进 API key、Authorization 头等敏感串。
 * web 层 {@code StreamController} 捕获后走 {@code completeWithError} 收尾,异常不会落进 SSE data
 * 体泄给前端(ADR-005 的薄接缝 + 本任务「干净降级」约束)。
 */
public class LlmException extends RuntimeException {

	public LlmException(String message) {
		super(message);
	}

	public LlmException(String message, Throwable cause) {
		super(message, cause);
	}
}
