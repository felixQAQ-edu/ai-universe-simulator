package com.aiuniverse.server.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.ArchetypeSummary;

/**
 * 选择屏目录端点(ADR-008 决策 4 选择 UI 的后端数据源)。薄适配(ADR-005),
 * 只读 {@link ArchetypeRegistry},无业务逻辑、无 IO、无 LLM。
 *
 * <p><b>{@code GET /api/archetypes}</b> → {@code { "archetypes": [ {archetype,displayName,tagline,vibeTag,active} ] }}:
 * 前端世界选择第一屏据此渲染氛围卡片(已激活可选、已知未开放灰显「敬请期待」),不硬编码模式清单。
 */
@RestController
public class ArchetypeController {

	private final ArchetypeRegistry registry;

	public ArchetypeController(ArchetypeRegistry registry) {
		this.registry = registry;
	}

	@GetMapping("/api/archetypes")
	public Map<String, List<ArchetypeSummary>> list() {
		return Map.of("archetypes", registry.listForSelection());
	}
}
