package com.aiuniverse.server.eventloop;

/**
 * 《七日开张》「回合序号 → 第几天 / 时段」的<b>唯一一份换算</b>(ADR-039 决策 3)。七天 × 早 / 午 / 晚 21 回合,
 * 第 22 回合百味夜集,第 23 回合起是兜底(ADR-038 决策 2)。
 *
 * <p>三个读者各拼各的字、都不自己做回合算术:提示词时段行({@link TurnPromptBuilder#qiriTurnClock})、
 * 早回合判定({@link QiriActionGuard#morning})、下发给前端的时段标签({@link ClientProjection})。
 *
 * @param turn  回合序号 n(= {@code state.turn} + 1;小于 1 按 1)
 * @param day   第几天,1–7;{@link Stage#DAY} 以外为 0
 * @param part  时段序号,0 = 早、1 = 午、2 = 晚;{@link Stage#DAY} 以外为 −1
 * @param stage 哪一段
 */
record QiriCalendar(int turn, int day, int part, Stage stage) {

	enum Stage {
		/** 第 1–21 回合:七天里的某一个时段。 */
		DAY,
		/** 第 22 回合:百味夜集。 */
		NIGHT_MARKET,
		/** 第 23 回合起:第 22 回合没给出结局时的兜底。 */
		AFTER
	}

	/** 百味夜集回合 = 第 22 回合。 */
	static final int NIGHT_MARKET_TURN = 7 * 3 + 1;

	static QiriCalendar of(int n) {
		int turn = Math.max(n, 1);
		if (turn < NIGHT_MARKET_TURN) {
			return new QiriCalendar(turn, (turn + 2) / 3, (turn - 1) % 3, Stage.DAY);
		}
		return new QiriCalendar(turn, 0, -1, turn == NIGHT_MARKET_TURN ? Stage.NIGHT_MARKET : Stage.AFTER);
	}
}
