package com.aiuniverse.server.eventloop;

/**
 * 单回合状态机的相位(规格 §3)。忙态守卫只在 {@link #AWAITING_ACTION} 接受玩家动作;
 * {@link #GENERATING}/{@link #SETTLING} 期间到来的动作一律拒(防一回合被双花)。
 */
public enum TurnPhase {
	/** 已下发 availableActions,等玩家选 id。唯一接受动作的相位。 */
	AWAITING_ACTION,
	/** 组 prompt → LLM 调用 → 叙事逐字流式、结构化尾巴缓冲。 */
	GENERATING,
	/** 回灌叙事 → 校验/修复 → Engine.apply 落账。 */
	SETTLING,
	/** 结局命中 / 兜底,整局收束。 */
	ENDED
}
