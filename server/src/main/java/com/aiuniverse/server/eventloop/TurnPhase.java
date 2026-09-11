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
	ENDED;

	/**
	 * 这一局此刻是否<b>正在推进一个回合</b>({@link #GENERATING} 或 {@link #SETTLING})。
	 *
	 * <p><b>为什么这个事实住在这里,而不是在调用点就地枚举</b>(ADR-023 立字 5):
	 * 调用方(游标比对,{@code GameController.turn})要回答的是「该判 {@code turn_stale}
	 * 还是让它去撞忙态守卫」,而那取决于「在途是哪些相位」——一个<b>会随本枚举增长而失效</b>的事实。
	 * 写在调用点上,加第五个值时它<b>静默失效、没有任何东西会红</b>;收在这里,
	 * <b>加值的人必须在一处做决定</b>。与「不写聚合数、指登记处」同形。
	 *
	 * <p>⚠️ 它<b>不构成 ADR-018 §4.1 的「两处判定」</b>:判定仍然只有调用点一处,
	 * 本方法只是把<b>「在途是哪些」这一事实</b>收归定义处(纯查询、零副作用)。
	 *
	 * <p>⚠️ 也<b>不是新提法</b>:本枚举的类 javadoc 逐字写着「{@code GENERATING}/{@code SETTLING}
	 * 期间到来的动作一律拒」。ADR-023 只是终于按它写的去用它——
	 * 而在此之前,那句话的第二半(SETTLING)被一次勘察漏读,差点让整条判据落空。
	 */
	public boolean inFlight() {
		return this == GENERATING || this == SETTLING;
	}
}
