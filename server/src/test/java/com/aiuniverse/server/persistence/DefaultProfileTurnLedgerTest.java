package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** ADR-026 刀 1:默认 profile 装配 {@link TurnLedger#NOOP}(两个方法体为空 → 回合路径行为逐字节不变)。 */
@SpringBootTest
class DefaultProfileTurnLedgerTest {

	@Autowired
	TurnLedger ledger;

	@Test
	void defaultProfileWiresNoopLedger() {
		assertThat(ledger).isSameAs(TurnLedger.NOOP);
	}
}
