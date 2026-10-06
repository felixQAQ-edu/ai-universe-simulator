package com.aiuniverse.server.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 开局(init)并发准入容量(ADR-030 决策 2 / 已决 4)。可 env 覆盖:
 * {@code AIUNIVERSE_INIT_MAXCONCURRENT}(规范写法);{@code AIUNIVERSE_INIT_MAX_CONCURRENT} 经 Spring 的
 * legacy 下划线映射同样生效(2026-10-06 绑定实验实测,见 {@code InitPropertiesEnvBindingTest})。
 *
 * <p><b>默认 4,无实测依据</b>(并发开局从未观察到):正常情况一名玩家同一时刻至多 1 个在途 init,
 * 4 覆盖「一小撮朋友同时开局」;回合 8 + init 4 = 上游同时最多 12 条流(ADR-030 已知代价 4)。
 * 与回合准入<b>分开</b>,不合并(决策 3:占用时长差两个数量级,合并会把慢开局传染给正在玩的人)。
 */
@ConfigurationProperties("aiuniverse.init")
public record InitProperties(
		/* 同时在途的开局数上限(准入名额总数)。 */
		@DefaultValue("4") int maxConcurrent) {
}
