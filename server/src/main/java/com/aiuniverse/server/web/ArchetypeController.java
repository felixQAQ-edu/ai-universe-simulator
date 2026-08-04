package com.aiuniverse.server.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

/**
 * 选择屏目录端点(ADR-008 决策 4 选择 UI 的后端数据源)。薄适配(ADR-005),
 * 只读 {@link ArchetypeRegistry},无业务逻辑、无 IO、无 LLM。
 *
 * <p><b>{@code GET /api/archetypes}</b> → {@code { "archetypes": [ {archetype,displayName,tagline,vibeTag,active} ],
 * "fusions": [ {host,foreign,key} ] }}:前端世界选择第一屏据此渲染氛围卡片(已激活可选、已知未开放灰显
 * 「敬请期待」),并据 {@code fusions} 判定融合拖拽的合法组合(ADR-019)——两者都不硬编码在前端。
 */
@RestController
public class ArchetypeController {

	private final ArchetypeRegistry registry;

	public ArchetypeController(ArchetypeRegistry registry) {
		this.registry = registry;
	}

	@GetMapping("/api/archetypes")
	public ArchetypeCatalog list() {
		return new ArchetypeCatalog(registry.listForSelection(), registry.listFusionCombos());
	}
}
