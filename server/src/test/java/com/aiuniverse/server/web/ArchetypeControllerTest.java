package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.ArchetypeSummary;
import com.aiuniverse.server.archetype.FusionSummary;
import com.aiuniverse.server.persistence.NarrativeHistoryReader;
import com.aiuniverse.server.persistence.UnavailableHistoryReader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /api/archetypes} 薄端点(ADR-008 决策 4 选择 UI 数据源 + ADR-019 融合组合只读投影):
 * 包 registry 的两张表为 {@code {archetypes:[...], fusions:[...]}},七条世界齐(ADR-036 起全部已激活、无占位)、
 * active 标志对;融合组合与 registry 同源、host 在前。
 */
class ArchetypeControllerTest {

	private final ArchetypeController controller = new ArchetypeController(new ArchetypeRegistry(),
			new UnavailableHistoryReader());

	@Test
	void listWrapsRegistrySelectionUnderArchetypesKey() {
		List<ArchetypeSummary> list = controller.list().archetypes();
		assertThat(list).hasSize(8); // ADR-038 Slice A:《七日开张》time_travel 激活,7 → 8
		assertThat(list.stream().map(ArchetypeSummary::archetype))
				.containsExactly("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim", "animal_life",
						"cyberpunk", "time_travel");
		// ADR-036 Slice B:《派单》激活 cyberpunk 后七条全部可选,目录里没有占位;ADR-038 Slice A 起八条。
		assertThat(list.stream().filter(ArchetypeSummary::active).map(ArchetypeSummary::archetype))
				.containsExactly("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim", "animal_life",
						"cyberpunk", "time_travel");
	}

	/**
	 * ADR-041 决策 1:每张卡的 JSON 都带 {@code experienceMode},取值只有小写 {@code "fushi"} / {@code "shenjie"}
	 * (审定 5)。融合组合不带这个键(立字七:一律进渗界,前端据 fusions 判)。
	 */
	@Test
	void everyCardInJsonCarriesExperienceMode() throws Exception {
		MvcResult r = MockMvcBuilders.standaloneSetup(controller).build().perform(get("/api/archetypes")).andReturn();
		JsonNode body = new ObjectMapper()
				.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
		JsonNode cards = body.get("archetypes");
		assertThat(cards.size()).isEqualTo(8);
		java.util.Map<String, String> zones = new java.util.TreeMap<>();
		for (JsonNode c : cards) {
			assertThat(c.has("experienceMode")).as("卡 %s 带 experienceMode", c.get("archetype")).isTrue();
			zones.put(c.get("archetype").asString(), c.get("experienceMode").asString());
		}
		assertThat(zones.values()).allMatch(v -> v.equals("fushi") || v.equals("shenjie"));
		assertThat(zones).containsEntry("life_sim", "fushi").containsEntry("rules_creepy", "shenjie");
		for (JsonNode f : body.get("fusions")) {
			assertThat(f.has("experienceMode")).as("融合组合不带分区字段").isFalse();
		}
	}

	/** ADR-019:合法组合与世界目录**同一次请求**下发(分两个端点 = 两个可能不同步的响应)。 */
	@Test
	void listAlsoCarriesRegisteredFusionCombos() {
		ArchetypeCatalog body = controller.list();
		assertThat(body.fusions()).isEqualTo(new ArchetypeRegistry().listFusionCombos());
		assertThat(body.fusions().stream().map(FusionSummary::key))
				.containsExactly("cultivation×cthulhu", "cultivation×rules_creepy", "rules_creepy×apocalypse");
	}

	/** 能力位直接来自历史读取器自己的 {@code available()},不在 controller 里另判 profile。 */
	@Test
	void historyCapabilityFollowsReader() {
		assertThat(controller.list().capabilities().history()).isFalse();

		NarrativeHistoryReader available = new NarrativeHistoryReader() {
			@Override
			public Result read(String saveId, Integer afterTurn) {
				return new Unavailable();
			}

			@Override
			public boolean available() {
				return true;
			}
		};
		assertThat(new ArchetypeController(new ArchetypeRegistry(), available).list().capabilities().history())
				.isTrue();
	}
}
