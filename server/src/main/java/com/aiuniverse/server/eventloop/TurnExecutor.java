package com.aiuniverse.server.eventloop;

/**
 * 单回合「脏活」执行器:GENERATING + SETTLING 委托(规格 §3)。{@link TurnStateMachine} 在守卫
 * (合法性 + 忙态)通过后调用它;真正的 LLM 流式 / 回灌 / 校验修复 / 落账 / 发事件都在实现里
 * (见 {@link EventLoopService})。抽成接口便于状态机单测用 stub 替身(零 LLM、零流式)。
 */
public interface TurnExecutor {

	/**
	 * 执行一回合。已知 {@code actionId} 合法(状态机已校验)、相位已进 GENERATING。
	 * 实现负责:组 prompt → 驱动流(叙事经 {@code sink.narrative} 逐字下发)→ 回灌校验/修复 →
	 * {@code Engine.apply} → 发 {@code delta}(及可选 {@code ending})事件。
	 *
	 * @return 本回合结算结果(供状态机决定回 AWAITING_ACTION 还是进 ENDED)
	 */
	TurnResult execute(GameSession session, String actionId, TurnEventSink sink);
}
