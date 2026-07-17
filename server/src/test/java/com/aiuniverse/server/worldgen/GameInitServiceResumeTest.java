package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.moderation.ModerationGateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-015 Slice 2 · 续局查询 {@code GameInitService.resume}:复用 InitResponse 形态、
 * <b>出网只走视图 3 消毒投影(独立硬闸:绝不漏视图 1)</b>、轴元数据与播种同一真理源重派生、
 * 不存在 → null(controller 404)。
 */
class GameInitServiceResumeTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	private GameInitService service(GameSessionManager sessions) {
		ModerationGateway noop = text -> text;
		WorldGenService worldGen = new WorldGenService((req, sink) -> sink.onToken("{}"),
				new WorldGenPromptBuilder(registry), mapper);
		return new GameInitService(worldGen, sessions, noop, registry, mapper);
	}

	private GameSessionManager seeded(String saveId, List<String> archetypeIds) {
		GameSessionManager sessions = new GameSessionManager(mapper);
		List<AttributeAxis> axes = registry.resolveAxes(archetypeIds);
		sessions.create(saveId, worldFor(archetypeIds), actions(),
				ArchetypeRegistry.accumulationKeys(axes), ArchetypeRegistry.axisDisplayNames(axes),
				ArchetypeRegistry.nonLethalKeys(axes));
		return sessions;
	}

	@Test
	void resumeReturnsSanitizedWorldActionsAndAxisMeta() {
		GameSessionManager sessions = seeded("save-1", List.of("rules_creepy"));
		InitResponse resp = service(sessions).resume("save-1");

		assertThat(resp).isNotNull();
		assertThat(resp.saveId()).isEqualTo("save-1");
		// 消毒硬闸:响应任何角落不得含视图 1 作者字段。
		String all = mapper.writeValueAsString(resp.world()) + mapper.writeValueAsString(resp.availableActions());
		assertThat(all).doesNotContain("isTrue").doesNotContain("hiddenLogic");
		// 玩家可见内容仍在。
		assertThat(resp.world().path("rules").get(0).path("content").asString()).isNotBlank();
		// openingNarrative transient 不落盘 → 续局恒空串。
		assertThat(resp.openingNarrative()).isEmpty();
		// 决策圈 = session.currentActions(守卫 1 同源)。
		assertThat(resp.availableActions()).hasSize(2);
		assertThat(resp.availableActions().get(0).path("id").asString()).isEqualTo("A");
		// 轴元数据与播种同一真理源(rules_creepy:体力/理智)。
		List<String> names = names(resp.attributes());
		assertThat(names).containsExactly("体力", "理智");
	}

	@Test
	void resumeFusionSessionRederivesFusedAxisMetaWithSkin() {
		GameSessionManager sessions = seeded("save-f", List.of("cultivation", "rules_creepy"));
		InitResponse resp = service(sessions).resume("save-f");
		assertThat(resp).isNotNull();
		// 融合轴集重派生:host 修仙三轴 + 换皮道心(ADR-012)。
		assertThat(names(resp.attributes())).containsExactly("气血", "灵力", "境界", "道心");
	}

	@Test
	void resumeUnknownSaveIdReturnsNull() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		assertThat(service(sessions).resume("nope")).isNull();
	}

	// ── helpers ────────────────────────────────────────────────────────

	private List<String> names(JsonNode attributes) {
		return java.util.stream.StreamSupport.stream(attributes.spliterator(), false)
				.map(a -> a.path("displayName").asString("")).toList();
	}

	private ArrayNode actions() {
		ArrayNode actions = mapper.createArrayNode();
		actions.addObject().put("id", "A").put("text", "谨慎观察四周").put("hint", "");
		actions.addObject().put("id", "B").put("text", "原地等待").put("hint", "");
		return actions;
	}

	private ObjectNode worldFor(List<String> archetypeIds) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4");
		w.put("mode", archetypeIds.size() > 1 ? "hybrid" : "single");
		ArrayNode arch = w.putArray("archetypes");
		archetypeIds.forEach(arch::add);
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		if (archetypeIds.size() > 1) {
			attrs.put("hp", 80).put("mana", 60).put("realm", 10).put("san", 90);
		} else {
			attrs.put("hp", 100).put("san", 100);
		}
		ArrayNode rules = w.putArray("rules");
		rules.addObject().put("id", 1).put("content", "熄灯后不要回头。")
				.put("isTrue", true).put("hiddenLogic", "回头即被标记。").put("discovered", false);
		ArrayNode endings = w.putArray("endings");
		endings.addObject().put("id", "dead").put("title", "死亡").put("condition", "体力归零")
				.put("outcome", "failure").put("reached", false);
		return w;
	}
}
