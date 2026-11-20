package com.aiuniverse.server.archetype;

import java.util.Objects;

/**
 * 选择屏一张卡片的轻量摘要(ADR-008 决策 4 选择 UI 数据源)——
 * {@code GET /api/archetypes} 下发给前端世界选择第一屏。<b>纯玩家可见展示</b>,无引擎语义。
 *
 * @param archetype   archetype id(∈ CONTEXT §三.4 枚举,snake_case;前端选中后回传 init)
 * @param displayName 玩家可见中文名(如「末日生存」「修仙」)
 * @param tagline     一句话钩子(玩家可见中文);未激活占位为 {@code null}
 * @param vibeTag     氛围/危险短标签(如「荒凉 · 绝境」);未激活占位为 {@code null}
 * @param experienceMode 大厅分区(ADR-041 决策 1),JSON 值 {@code "fushi"} / {@code "shenjie"};已激活与未开放占位都必须有
 * @param active      是否可选(true=已激活可玩;false=已知未开放,前端灰显「敬请期待」、不可点)
 */
public record ArchetypeSummary(
		String archetype,
		String displayName,
		String tagline,
		String vibeTag,
		ExperienceMode experienceMode,
		boolean active) {

	public ArchetypeSummary {
		Objects.requireNonNull(experienceMode, () -> "ArchetypeSummary " + archetype + " 缺 experienceMode(ADR-041 决策 1)");
	}
}
