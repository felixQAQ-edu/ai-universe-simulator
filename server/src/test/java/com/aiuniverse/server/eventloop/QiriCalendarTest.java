package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.eventloop.QiriCalendar.Stage;

/** ADR-039 决策 3 ·《七日开张》回合 → 第几天 / 时段的唯一换算,逐回合钉住。 */
class QiriCalendarTest {

	@Test
	void everyTurnMapsToDayPartAndStage() {
		for (int n = 1; n <= 21; n++) {
			int day = (n - 1) / 3 + 1; // 与实现写法不同的另一种算法,互相核对
			int part = n - (day - 1) * 3 - 1;
			assertThat(QiriCalendar.of(n)).as("T" + n).isEqualTo(new QiriCalendar(n, day, part, Stage.DAY));
		}
		assertThat(QiriCalendar.of(1)).isEqualTo(new QiriCalendar(1, 1, 0, Stage.DAY));
		assertThat(QiriCalendar.of(3)).isEqualTo(new QiriCalendar(3, 1, 2, Stage.DAY));
		assertThat(QiriCalendar.of(4)).isEqualTo(new QiriCalendar(4, 2, 0, Stage.DAY));
		assertThat(QiriCalendar.of(21)).isEqualTo(new QiriCalendar(21, 7, 2, Stage.DAY));
		assertThat(QiriCalendar.of(22)).isEqualTo(new QiriCalendar(22, 0, -1, Stage.NIGHT_MARKET));
		assertThat(QiriCalendar.of(23)).isEqualTo(new QiriCalendar(23, 0, -1, Stage.AFTER));
		assertThat(QiriCalendar.of(30)).isEqualTo(new QiriCalendar(30, 0, -1, Stage.AFTER));
	}

	@Test
	void turnBelowOneIsTreatedAsOne() {
		assertThat(QiriCalendar.of(0)).isEqualTo(QiriCalendar.of(1));
		assertThat(QiriCalendar.of(-5)).isEqualTo(QiriCalendar.of(1));
	}

	@Test
	void finalTurnConstantIsSharedWithPromptBuilder() {
		assertThat(TurnPromptBuilder.QIRI_FINAL_TURN).isEqualTo(QiriCalendar.NIGHT_MARKET_TURN).isEqualTo(22);
	}
}
