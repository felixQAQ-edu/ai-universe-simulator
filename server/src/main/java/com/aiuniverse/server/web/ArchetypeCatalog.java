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
 * @param fusions    已登记融合组合(key 排序确定;host 在前)
 */
public record ArchetypeCatalog(List<ArchetypeSummary> archetypes, List<FusionSummary> fusions) {
}
