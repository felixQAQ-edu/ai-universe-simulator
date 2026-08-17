package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.ArchetypeSummary;
import com.aiuniverse.server.archetype.FusionSummary;

/**
 * {@code GET /api/archetypes} 薄端点(ADR-008 决策 4 选择 UI 数据源 + ADR-019 融合组合只读投影):
 * 包 registry 的两张表为 {@code {archetypes:[...], fusions:[...]}},六条世界齐(五已激活含《寻常》+ 一占位)、
 * active 标志对;融合组合与 registry 同源、host 在前。
 */
class ArchetypeControllerTest {

	private final ArchetypeController controller = new ArchetypeController(new ArchetypeRegistry());

	@Test
	void listWrapsRegistrySelectionUnderArchetypesKey() {
		List<ArchetypeSummary> list = controller.list().archetypes();
		assertThat(list).hasSize(7);
		assertThat(list.stream().map(ArchetypeSummary::archetype))
				.containsExactly("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim", "animal_life",
						"cyberpunk");
		assertThat(list.stream().filter(ArchetypeSummary::active).map(ArchetypeSummary::archetype))
				.containsExactly("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim", "animal_life");
	}

	/** ADR-019:合法组合与世界目录**同一次请求**下发(分两个端点 = 两个可能不同步的响应)。 */
	@Test
	void listAlsoCarriesRegisteredFusionCombos() {
		ArchetypeCatalog body = controller.list();
		assertThat(body.fusions()).isEqualTo(new ArchetypeRegistry().listFusionCombos());
		assertThat(body.fusions().stream().map(FusionSummary::key))
				.containsExactly("cultivation×rules_creepy", "rules_creepy×apocalypse");
	}
}
