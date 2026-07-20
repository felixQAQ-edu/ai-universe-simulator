package com.aiuniverse.server.llm;

/**
 * 一次 LLM 调用的 token 用量(纯观测,ADR-002 成本闸门的程序内读数来源;本身不做任何成本计算/限流)。
 *
 * <p>字段对应 OpenAI 兼容流式响应最后一个 chunk 的 {@code usage} 块;<b>缺失的字段记 -1</b>
 * (DeepSeek 方言偶有出入,如录制样本缺 {@code total_tokens} —— 只透传存在的字段,绝不猜、不自行推算)。
 *
 * <p>{@code cacheHitTokens}/{@code cacheMissTokens} 对应 DeepSeek 方言的
 * {@code prompt_cache_hit_tokens}/{@code prompt_cache_miss_tokens}(前缀缓存命中/未命中的输入
 * token 数,两档单价差 50 倍)——④ 成本闸门阈值定值所需的真实命中率读数;非 DeepSeek 方言无此
 * 两字段,同样容错记 -1。
 */
public record LlmUsage(long promptTokens, long completionTokens, long totalTokens,
		long cacheHitTokens, long cacheMissTokens) {

	/** 便捷构造:无缓存方言字段(完整 OpenAI 口径 / 既有调用点)= 缓存两字段缺失记 -1。 */
	public LlmUsage(long promptTokens, long completionTokens, long totalTokens) {
		this(promptTokens, completionTokens, totalTokens, -1, -1);
	}

	/** 供日志用的单行展示,缺失字段显示 {@code -}。 */
	public String display() {
		return "prompt=" + fmt(promptTokens) + " completion=" + fmt(completionTokens)
				+ " total=" + fmt(totalTokens)
				+ " cacheHit=" + fmt(cacheHitTokens) + " cacheMiss=" + fmt(cacheMissTokens);
	}

	private static String fmt(long v) {
		return v < 0 ? "-" : String.valueOf(v);
	}
}
