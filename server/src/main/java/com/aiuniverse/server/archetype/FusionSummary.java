package com.aiuniverse.server.archetype;

/**
 * 一对已登记融合组合的轻量摘要(ADR-019 只读投影)——{@code GET /api/archetypes} 与世界目录同源下发,
 * 供选择屏判定「这两张卡能不能揉」(拖拽入口:被拖者=foreign,承接者=host)。
 *
 * <p><b>性质:纯只读投影。</b> 它不是新语义,只是把 {@link ArchetypeRegistry} 里**已有的** {@code FUSION_COMBOS}
 * 事实暴露给消费方——与 severity 契约(ADR-018)同族:<b>语义产出方是掌握语义的那一层,消费方无知</b>。
 * 前端据此判定合法性,而 per-combo 展示文案(融合卡标题/tagline)仍留在前端(展示层,后端不该有)。
 * 不进 state schema、不进 wire 校验、引擎绝不读;{@code schemaVersion} 保 "0.4"。
 *
 * @param host    承接者 archetype id(有序双值里在前,ADR-012/013)
 * @param foreign 被揉入者 archetype id
 * @param key     组合键 {@code host×foreign}(方向敏感;与前端封面/卡文案同键)
 */
public record FusionSummary(String host, String foreign, String key) {
}
