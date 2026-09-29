package com.aiuniverse.server.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@link TurnLedger} 装配(ADR-026)。默认 profile 装 {@link TurnLedger#NOOP}(行为逐字节不变);
 * {@code pg} profile 由 {@link JdbcTurnLedger} 接管(刀 2)。
 */
@Configuration
public class TurnLedgerConfig {

	@Bean
	@Profile("!pg")
	TurnLedger turnLedger() {
		return TurnLedger.NOOP;
	}
}
