package com.aiuniverse.server.persistence;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

/**
 * {@link RequiresDocker} 的判定(ADR-025 刀 1 测试口径):
 * <ul>
 *   <li>Docker 可用 → 执行;</li>
 *   <li>无 Docker 且 {@code CI=true} → <b>仍然执行</b>(容器启动当场失败 = 构建红),<b>绝不跳过</b>;</li>
 *   <li>无 Docker 且非 CI(本地)→ 跳过,原因以 {@link #SKIP_MARKER} 开头,供 {@link DbTestSkipSummary} 计数。</li>
 * </ul>
 */
public class DockerGate implements ExecutionCondition {

	/** 跳过原因前缀;汇总监听器只数带这个前缀的跳过(不把别的 @Disabled 算进来)。 */
	public static final String SKIP_MARKER = "[db-tests]";

	private static final Logger log = LoggerFactory.getLogger(DockerGate.class);

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		if (dockerAvailable()) {
			return ConditionEvaluationResult.enabled("Docker 可用");
		}
		if (isCi()) {
			return ConditionEvaluationResult.enabled(
					"CI=true 且无 Docker:不跳过,让容器启动失败把构建打红(DB 测试在 CI 上不许静默缺席)");
		}
		String reason = SKIP_MARKER + " 本机无 Docker(Testcontainers 探测不到 Docker 环境),跳过 DB 测试;"
				+ "CI 上同样情况会失败而不是跳过";
		if (context.getTestMethod().isEmpty()) {
			log.warn("{} —— {}", reason, context.getDisplayName());
		}
		return ConditionEvaluationResult.disabled(reason);
	}

	static boolean isCi() {
		return "true".equalsIgnoreCase(System.getenv("CI"));
	}

	private static Boolean cached;

	static synchronized boolean dockerAvailable() {
		if (cached == null) {
			boolean ok;
			try {
				ok = DockerClientFactory.instance().isDockerAvailable();
			} catch (Throwable t) {
				ok = false;
			}
			cached = ok;
		}
		return cached;
	}
}
