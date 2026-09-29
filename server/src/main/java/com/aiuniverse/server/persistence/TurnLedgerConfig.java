package com.aiuniverse.server.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link TurnLedger} 装配(ADR-026 刀 1)。本刀只有 {@link TurnLedger#NOOP} 一个实现,<b>两个 profile 都装它</b>
 * —— pg 真实现在刀 2 进来时,本 bean 加 {@code @Profile("!pg")},默认 profile 装配不变。
 */
@Configuration
public class TurnLedgerConfig {

	@Bean
	TurnLedger turnLedger() {
		return TurnLedger.NOOP;
	}
}
