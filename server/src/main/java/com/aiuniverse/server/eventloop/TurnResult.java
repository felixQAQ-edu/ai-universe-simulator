package com.aiuniverse.server.eventloop;

/**
 * 单回合结算结果。{@code ended} 来自 {@code Engine.status()=="ended"}(含结局命中与数值触底兜底),
 * 决定状态机回 {@link TurnPhase#AWAITING_ACTION}(ongoing)还是进 {@link TurnPhase#ENDED}。
 */
public record TurnResult(boolean ended) {
}
