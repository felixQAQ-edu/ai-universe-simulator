package com.aiuniverse.server.archetype;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 体验分类:单体世界在大厅里属于哪一区(ADR-041 决策 1;世界目录立字三 / 五 / 七)。
 *
 * <p><b>只在大厅成立</b>(世界目录立字一):只经 {@code GET /api/archetypes} 下发给选择屏分区,
 * 不进 world-gen / event-loop 注入,不进引擎,不进局内。
 *
 * <p>融合世界没有这个字段 —— 按立字七一律进第三区(渗界),前端据 {@code fusions} 只读投影即可。
 *
 * <p>每个新世界登记时都必须回答属于哪一侧(立字五):{@link ArchetypeMeta} 的两个构造器都要求本参数、
 * 不给缺省值,漏答编译不过。
 */
public enum ExperienceMode {
	/** 浮世(轻松、寻常)。 */
	FUSHI("fushi"),
	/** 深界(恐怖、惊悚)。现有融合组合的参与世界都在这里;卡片可拖。 */
	SHENJIE("shenjie");

	private final String wire;

	ExperienceMode(String wire) {
		this.wire = wire;
	}

	/** JSON 取值:小写拼音 {@code "fushi"} / {@code "shenjie"}(ADR-041 审定 5)。 */
	@JsonValue
	public String wire() {
		return wire;
	}
}
