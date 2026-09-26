package com.aiuniverse.server.persistence;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 非 pg profile 的历史读取:恒为「本环境无历史」(ADR-025 已决 4 选 (a))。
 * <b>不</b>返回空列表、<b>不</b>拿内存里那 ≤4 条冒充历史 —— 空列表会被读成「这一局什么都没发生」。
 */
@Component
@Profile("!pg")
public class UnavailableHistoryReader implements NarrativeHistoryReader {

	@Override
	public Result read(String saveId, Integer afterTurn) {
		return new Unavailable();
	}
}
