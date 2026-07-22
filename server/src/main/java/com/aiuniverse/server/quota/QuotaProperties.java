package com.aiuniverse.server.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 成本闸门四阈值(ADR-016;Felix 拍板,基于线上真实 usage 数据 + 52% 缓存命中率)。
 * 全部可 env 覆盖(Spring relaxed binding,如 {@code AIUNIVERSE_QUOTA_DAILY_BUDGET_CNY}),
 * 与 ADR-015 {@code AIUNIVERSE_SESSION_STORE_DIR} 同一口径——冒烟压低阈值 = 临时 env,零代码改动。
 *
 * <p>阈值哲学(ADR-016 立字):假想敌是脚本刷不是真人玩,宽到真人永远撞不到。
 */
@ConfigurationProperties("aiuniverse.quota")
public record QuotaProperties(
		/* 全局日限额 CNY(真闸)。 */
		@DefaultValue("6") double dailyBudgetCny,
		/* 全局月顶 CNY(真闸,¥200 总预算 − 托管固定成本留余量)。 */
		@DefaultValue("175") double monthlyBudgetCny,
		/* 单 IP/设备日 init 局数(软闸)。 */
		@DefaultValue("10") int initPerKeyDaily,
		/* 单 IP/设备日回合数(软闸)。 */
		@DefaultValue("300") int turnsPerKeyDaily) {
}
