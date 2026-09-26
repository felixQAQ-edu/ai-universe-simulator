package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 页区间的边界(纯函数,无 Docker)。 */
class HistoryPagesTest {

	@Test
	void fromTurnRejectsIntMaxInsteadOfOverflowingNegative() {
		assertThatThrownBy(() -> HistoryPages.fromTurn(Integer.MAX_VALUE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> HistoryPages.fromTurn(-1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void lastLegalCursorStaysNonNegative() {
		var page = HistoryPages.assemble("s", HistoryPages.NATIVE, "ongoing", 3, Integer.MAX_VALUE - 1, List.of(),
				null);
		assertThat(page.fromTurn()).isEqualTo(Integer.MAX_VALUE);
		assertThat(page.toTurn()).isEqualTo(Integer.MAX_VALUE);
		assertThat(page.entries()).isEmpty();
		assertThat(page.nextAfterTurn()).isNull();
	}
}
