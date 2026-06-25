package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.ArchetypeSummary;

/**
 * {@code GET /api/archetypes} 薄端点(ADR-008 决策 4 选择 UI 数据源):
 * 包 registry 的选择目录为 {@code {archetypes:[...]}},六条齐(含克苏鲁)、active 标志对。
 */
class ArchetypeControllerTest {

	private final ArchetypeController controller = new ArchetypeController(new ArchetypeRegistry());

	@Test
	void listWrapsRegistrySelectionUnderArchetypesKey() {
		Map<String, List<ArchetypeSummary>> body = controller.list();
		List<ArchetypeSummary> list = body.get("archetypes");
		assertThat(list).hasSize(6);
		assertThat(list.stream().map(ArchetypeSummary::archetype))
				.containsExactly("rules_creepy", "apocalypse", "cthulhu", "life_sim", "cultivation", "cyberpunk");
		assertThat(list.stream().filter(ArchetypeSummary::active).map(ArchetypeSummary::archetype))
				.containsExactly("rules_creepy", "apocalypse", "cthulhu");
	}
}
