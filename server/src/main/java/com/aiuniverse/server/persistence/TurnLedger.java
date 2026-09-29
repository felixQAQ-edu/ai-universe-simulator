package com.aiuniverse.server.persistence;

import com.aiuniverse.server.eventloop.GameSession;

/**
 * 回合受理记录接缝(ADR-026 决策 1 / 4 / 7)。消费方只有 {@code TurnStateMachine} 临界区里两处调用;
 * 回合「落地」那一半不在本接口 —— 它并入 {@link SessionStore#persist} 那一次事务(决策 2),
 * 由 DB 版 store 自己关掉受理行。
 *
 * <p><b>两个方法都是 best-effort,绝不抛</b>(已决 C = 照跑):写库失败只丢记录,不影响回合。
 * 任何人把它们改成「失败即拒绝」都会撞上 ADR-022 立字 5(准入路径上不许有必须永不失败的写)。
 *
 * <p>默认 profile 装配 {@link #NOOP}(两个方法体为空 → 行为逐字节不变);{@code pg} profile 的真实现在刀 2。
 */
public interface TurnLedger {

	/**
	 * 受理:CAS 成功之后、调模型之前,一段短事务插入一行 PROCESSING
	 * ({@code base_turn = engine.turn()},{@code target_turn = base_turn + 1})。
	 * <b>模型流式调用不在任何事务里</b> —— 本方法返回时这段事务已经提交。
	 */
	void accept(GameSession session, String actionId);

	/**
	 * 失败:进程内看见异常、且回合<b>未</b>落地(ADR-027 决策 5:只在 {@code TurnStateMachine} 的
	 * 「未落地」分支调用;已落地分支会补一次 persist,由 persist 事务里的落地更新关掉受理行)。
	 */
	void failed(GameSession session);

	/** 无记录(默认 profile;测试与旧构造签名用)。 */
	TurnLedger NOOP = new TurnLedger() {
		@Override
		public void accept(GameSession session, String actionId) {
		}

		@Override
		public void failed(GameSession session) {
		}
	};
}
