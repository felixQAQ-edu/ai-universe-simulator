package com.aiuniverse.server.llm;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider 配置表(ADR-001 §5.1 的 Java 版,对应 bakeoff 的 {@code providers.py})。
 *
 * <p>约定:统一走 OpenAI 兼容接口,换 provider 只改这张表(base-url / model / key 变量名 /
 * 价格 / 思考开关 / max-context)。各家非标参数(思考模式)由 {@link ThinkingAdapter} 单点翻译,
 * 不污染主流程。
 *
 * <p>安全红线:这里只存 {@code apiKeyEnv}(环境变量名),真实 key 永远只进后端运行环境变量,
 * 绝不写进 yaml / 代码 / 提交(CONTEXT §三.2、ADR-002)。
 *
 * <p>骨架阶段 {@code active=mock},不读任何 key、不发任何真实请求。
 */
@ConfigurationProperties("aiuniverse.llm")
public record LlmProperties(
		String active,
		Map<String, Provider> providers) {

	/** 单个 provider 一条记录,对应 bakeoff {@code providers.py} 的 Provider dataclass。 */
	public record Provider(
			String label,
			String baseUrl,
			String model,
			String apiKeyEnv,
			boolean thinking,
			long maxContext,
			Price price) {
	}

	/** CNY / 1M tokens。input 区分缓存命中/未命中(DeepSeek 缓存专项)。 */
	public record Price(
			double inputCacheMiss,
			double inputCacheHit,
			double output) {
	}
}
