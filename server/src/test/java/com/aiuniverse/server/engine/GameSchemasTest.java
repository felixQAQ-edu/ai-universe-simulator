package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 手写校验器单测(移植 {@code schema.py} 的 WORLD_SCHEMA / TURN_SCHEMA + §10 放宽)。
 * 用 bake-off 录制的真实产出做「合法」基线,再逐项破坏断「非法」。
 */
class GameSchemasTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private ObjectNode validWorld() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.2");
		w.put("mode", "single");
		w.putArray("archetypes").add("rules_creepy");
		ObjectNode world = w.putObject("world");
		world.put("title", "雨夜便利店");
		world.put("background", "凌晨的城郊便利店");
		world.put("dangerLevel", "high");
		world.put("tone", "压抑");
		w.putObject("character").putObject("attributes").put("hp", 80).put("san", 70);
		ObjectNode r = w.putArray("rules").addObject();
		r.put("id", 1);
		r.put("content", "不要回应敲窗声");
		r.put("isTrue", true);
		r.put("hiddenLogic", "触发 san-20");
		r.put("discovered", false);
		ObjectNode e = w.putArray("endings").addObject();
		e.put("id", "survive_dawn");
		e.put("title", "撑到天亮");
		e.put("condition", "存活至6:00");
		e.put("reached", false);
		return w;
	}

	private ObjectNode validTurn() {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "窗外传来三声敲击。");
		t.putObject("stateUpdate").put("hp", 80).put("san", 70).put("timeline", "夜班第一小时");
		t.putArray("triggeredRuleIds").add(1);
		t.putArray("discoveredRuleIds").add(1);
		var a = t.putArray("availableActions");
		a.addObject().put("id", "A").put("text", "继续盘点");
		a.addObject().put("id", "B").put("text", "查看监控");
		t.putNull("ending");
		return t;
	}

	@Test
	void validWorldPasses() {
		assertThat(GameSchemas.validateWorld(validWorld())).isEmpty();
	}

	@Test
	void worldWrongSchemaVersionFails() {
		ObjectNode w = validWorld();
		w.put("schemaVersion", "0.1");
		assertThat(GameSchemas.validateWorld(w)).anyMatch(s -> s.contains("schemaVersion"));
	}

	@Test
	void worldRuleIdMustBeInteger() {
		ObjectNode w = validWorld();
		((ObjectNode) w.path("rules").get(0)).put("id", "1"); // 字符串非法
		assertThat(GameSchemas.validateWorld(w)).anyMatch(s -> s.contains("rules/0/id"));
	}

	@Test
	void worldEndingIdMustBeString() {
		ObjectNode w = validWorld();
		((ObjectNode) w.path("endings").get(0)).put("id", 5); // 整数非法
		assertThat(GameSchemas.validateWorld(w)).anyMatch(s -> s.contains("endings/0/id"));
	}

	@Test
	void worldAttributesOutOfRangeFails() {
		ObjectNode w = validWorld();
		((ObjectNode) w.path("character").path("attributes")).put("hp", 150);
		assertThat(GameSchemas.validateWorld(w)).anyMatch(s -> s.contains("character/attributes/hp"));
	}

	@Test
	void validTurnPasses() {
		assertThat(GameSchemas.validateTurn(validTurn())).isEmpty();
	}

	@Test
	void turnMissingNarrativeFails() {
		ObjectNode t = validTurn();
		t.remove("narrative");
		assertThat(GameSchemas.validateTurn(t)).anyMatch(s -> s.contains("narrative"));
	}

	@Test
	void turnHpOutOfRangeFails() {
		ObjectNode t = validTurn();
		((ObjectNode) t.path("stateUpdate")).put("hp", -5);
		assertThat(GameSchemas.validateTurn(t)).anyMatch(s -> s.contains("stateUpdate/hp"));
	}

	@Test
	void turnTooFewActionsFailsWhenOngoing() {
		ObjectNode t = validTurn();
		var a = mapper.createArrayNode();
		a.addObject().put("id", "A").put("text", "唯一动作");
		t.set("availableActions", a); // 只 1 个,非结局回合 → 非法
		assertThat(GameSchemas.validateTurn(t)).anyMatch(s -> s.contains("availableActions"));
	}

	@Test
	void turnTooManyActionsFails() {
		ObjectNode t = validTurn();
		var a = mapper.createArrayNode();
		for (int i = 0; i < 5; i++) {
			a.addObject().put("id", "X" + i).put("text", "动作" + i);
		}
		t.set("availableActions", a);
		assertThat(GameSchemas.validateTurn(t)).anyMatch(s -> s.contains("availableActions"));
	}

	@Test
	void endingTurnAllowsEmptyActions() {
		// §10 放宽:ending.reached=true 时 availableActions 可空。
		ObjectNode t = validTurn();
		t.set("availableActions", mapper.createArrayNode());
		t.putObject("ending").put("id", "survive_dawn").put("reached", true);
		assertThat(GameSchemas.validateTurn(t)).isEmpty();
	}

	@Test
	void nonEndingTurnStillRequiresTwoActions() {
		ObjectNode t = validTurn();
		t.set("availableActions", mapper.createArrayNode());
		// ending=null → 仍要求 minItems 2
		assertThat(GameSchemas.validateTurn(t)).anyMatch(s -> s.contains("availableActions"));
	}

	@Test
	void realRecordedWorldAndTurnPass() {
		// 用 golden 夹具里的真实录制产出兜底:手写校验器对真实形态不误杀。
		try (var in = getClass().getResourceAsStream("/golden/event-loop-golden.json")) {
			JsonNode fx = mapper.readTree(in);
			assertThat(GameSchemas.validateWorld(fx.get("world"))).isEmpty();
			JsonNode firstTurn = fx.path("paths").path("B1").path("turns").get(0).get("parsed");
			assertThat(GameSchemas.validateTurn(firstTurn)).isEmpty();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
