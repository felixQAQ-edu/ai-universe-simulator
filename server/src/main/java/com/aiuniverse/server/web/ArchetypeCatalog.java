package com.aiuniverse.server.web;

import java.util.List;

import com.aiuniverse.server.archetype.ArchetypeSummary;
import com.aiuniverse.server.archetype.FusionSummary;

/**
 * {@code GET /api/archetypes} 的响应体(ADR-008 决策 4 世界目录 + ADR-019 融合组合只读投影)。
 *
 * <p>两张表**同一次请求下发**是刻意的:选择屏要同时知道「有哪些世界」与「哪两个能揉」,
 * 分两个端点就是两次往返 + 两个可能不同步的响应(ADR-019 排除的方案 2)。
 *
 * @param archetypes 世界目录(已激活在前、已知未开放占位在后)
 * @param fusions      已登记融合组合(key 排序确定;host 在前)
 * @param capabilities 本环境的只读能力标志(2026-10-09):前端据此决定要不要去探测某个接口,
 *                     而不是先撞一次 501 再判断
 */
public record ArchetypeCatalog(List<ArchetypeSummary> archetypes, List<FusionSummary> fusions,
		Capabilities capabilities) {

	/**
	 * @param history 有没有叙事历史存储({@code pg} profile 为 true,其余 false)。
	 *                只影响前端探不探测 {@code /history};{@code /history} 本身在无存储时仍回 501
	 *                (ADR-025 已决 4 不变)。
	 */
	public record Capabilities(boolean history) {
	}
}
