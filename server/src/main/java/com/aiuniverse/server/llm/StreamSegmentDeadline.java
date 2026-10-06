package com.aiuniverse.server.llm;

import java.time.Clock;

/**
 * 流式段总时长上界(ADR-024 语义,ADR-030 决策 4 抽成公共构件)。
 *
 * <p><b>语义</b>:守卫创建时读一次起点;之后<b>每个 token 到达时</b>算已流时长,{@code > deadlineMs} 即抛
 * {@link LlmException}。起点在调用方把守卫交给 {@code streamChat} <b>之前</b>,故一段的计时包含建连与等响应头。
 * 闭合方向写死:恰好等于上界放过。
 *
 * <p>⚠️ <b>彻底静默不在保护范围内</b>(ADR-024 §挂账 / ADR-030 已决 0):检查只在 token 到达时发生,
 * 上游一个 token 都不再来时守卫不触发。<b>不许为此加看门线程</b>——worker 自己看表、自己掐自己,
 * 正是本构件不引入任何「必须永不失败的写操作」的原因(ADR-024 立字 6)。
 *
 * <p><b>每段一个守卫</b>:主调用与修复调用各调一次 {@link #guard},各有整份预算(ADR-024 立字 4)。
 * <b>插入位置</b>:{@code new UsageCapture(guard(...))} —— 守卫在 {@link UsageCapture} 里层,
 * {@code onUsage} 在上面被截住,守卫没有可忘的转发(ADR-024 立字 3)。
 *
 * <p>读时钟次数可数:创建 1 次 + 每 token 1 次(既有 ADR-024 用例的精确时钟脚本依赖这一点)。
 */
public final class StreamSegmentDeadline {

	private StreamSegmentDeadline() {
	}

	/**
	 * @param delegate   下游
	 * @param clock      注入时钟(测试给假时钟)
	 * @param deadlineMs 本段上界(毫秒)
	 * @param onExceed   超线时异常消息的尾句,由调用方说明后果(回合侧「自掐降级」/ world-gen 侧「本次开局作废」)
	 */
	public static TokenStream guard(TokenStream delegate, Clock clock, long deadlineMs, String onExceed) {
		long segmentStartedAtMs = clock.millis();
		return token -> {
			long elapsedMs = clock.millis() - segmentStartedAtMs;
			if (elapsedMs > deadlineMs) {
				throw new LlmException("流式段超过 " + deadlineMs + "ms 上界(已流 " + elapsedMs + "ms)," + onExceed);
			}
			delegate.onToken(token);
		};
	}
}
