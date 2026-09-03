package com.aiuniverse.server.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 回合并发准入容量(ADR-022 裁定 1)。可 env 覆盖(Spring relaxed binding:
 * {@code AIUNIVERSE_TURN_MAX_CONCURRENT}),与 ADR-016 四阈值、ADR-015 落盘目录同一口径。
 *
 * <p><b>env 覆盖不是为了调参方便</b>:它是刀 3 冒烟<b>唯一能确定性触发拒绝的手段</b>
 * ——压到 1、开两局、在 A 局回合跑着时对 B 局发请求。同 ADR-016 冒烟压低 {@code INIT=2/TURNS=3} 的做法。
 *
 * <p><b>N = 8 的推导</b>(ADR-022 裁定 1,承重的是下面两条):
 * <ul>
 *   <li><b>上界(内存)</b>:8 × ~1MB 线程栈 ≈ 8MB <b>堆外</b>({@code Dockerfile} 无 {@code -Xmx}
 *       无 {@code MaxRAMPercentage},栈不占堆),对 512MB 可忽略。</li>
 *   <li><b>成本关系</b>:N=8 满负荷 ≈ 32 回合/分 ≈ ¥0.11/分 → ADR-016 日闸 ¥6 约 55 分钟打满,
 *       即 <b>N=8 时先撞到的仍是 ADR-016 的真闸</b>。并发闸只负责 turn 路径上的「不死」,
 *       成本仍归 ADR-016,两道闸不互相替代。</li>
 * </ul>
 * 「一小撮朋友同时试 ≈ 3–5」那条是取值方向、<b>不是论据</b>(线上读数是累计量,答不了并发问题)。
 *
 * <p>⚠️ <b>「只负责不死」限定到 turn 路径,不是全站承诺</b>:{@code POST /api/game/init} 仍阻塞占
 * Tomcat 容器线程(默认上限 200),本刀完全没管它(ADR-015 已知代价 2,另刀)。
 */
@ConfigurationProperties("aiuniverse.turn")
public record TurnProperties(
		/* 同时在途的回合数上限(准入名额总数)。 */
		@DefaultValue("8") int maxConcurrent) {
}
