package com.aiuniverse.server.quota;

import com.aiuniverse.server.llm.LlmProperties;
import com.aiuniverse.server.llm.LlmUsage;

/**
 * 一次调用的估算费用(CNY):price 三段(CNY / 1M tokens)× usage 块。计价公式的<b>唯一</b>一份。
 *
 * <p>从 {@link QuotaService} 抽出(ADR-033):成本闸门记账与 /data 只读统计工具共用同一份公式,
 * 统计工具不另写、不写死单价。缺失字段(-1 口径)保守回退:cacheMiss 缺失时用 prompt − hit 兜(方言差异),
 * 全缺记 0 —— 记账是增值服务,绝不因缺字段抛错。
 */
public final class TokenPricing {

	private static final double PER_MILLION = 1_000_000.0;

	private TokenPricing() {
	}

	/** {@code price == null}(无 price 配置的 provider)→ 0。 */
	public static double costCny(LlmUsage usage, LlmProperties.Price price) {
		if (price == null) {
			return 0;
		}
		long hit = Math.max(usage.cacheHitTokens(), 0);
		long miss = usage.cacheMissTokens() >= 0 ? usage.cacheMissTokens()
				: Math.max(Math.max(usage.promptTokens(), 0) - hit, 0);
		long out = Math.max(usage.completionTokens(), 0);
		return (hit * price.inputCacheHit() + miss * price.inputCacheMiss() + out * price.output()) / PER_MILLION;
	}
}
