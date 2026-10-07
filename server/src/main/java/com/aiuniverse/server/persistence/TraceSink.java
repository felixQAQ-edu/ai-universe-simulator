package com.aiuniverse.server.persistence;

/**
 * 回合执行轨迹的写出接缝(ADR-031 刀 1;形态照 {@link SessionStore} / {@link TurnLedger})。
 * 唯一消费方是 {@code TurnStateMachine}:回合<b>已落地</b>并写盘之后调用一次(两处 persist 之后);
 * 未落地回合不调用,降级回合照调(已决 W-6)。
 *
 * <p><b>实现可以抛,调用方负责吞</b>:轨迹是旁路记录,写失败绝不拖累回合(调用方 catch {@code Throwable} + WARN,
 * 回合、相位、存档照常)。
 *
 * <p>默认装配 {@link #NOOP}(刀 1 没有落点);文件落点 {@code <saveId>.trace.jsonl} 在刀 3。
 */
@FunctionalInterface
public interface TraceSink {

	void record(TurnTrace trace);

	/** 不记录(刀 1 默认装配;测试与旧构造签名用)。 */
	TraceSink NOOP = trace -> {
	};
}
