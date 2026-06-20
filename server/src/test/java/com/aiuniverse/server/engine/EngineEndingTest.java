package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 结局结算三子分支(规格 §5 第 9–10 步 + §4.4)——golden 三路径均连推满 10 回合未触底,
 * 结局路完全没被 golden 覆盖,故在此逐分支钉死:
 * <ul>
 *   <li>(a) AI 给 ending(id 存在、reached:true)→ ended + 标该 ending;</li>
 *   <li>(b) 触底且 AI 未给 ending → §5 强制兜底坏结局 id —— 已由 {@link EngineBoundaryTest}
 *       的 {@code bottomOutSan/HpForces*} 覆盖,此处不重复;</li>
 *   <li>(c) 触底但无 condition 匹配 → 约定 fallback = endings[] 首条;</li>
 *   <li>(附) AI 给的 ending.id 不在 endings[] → 引擎不接受(不 end / 不标,§4.4)。</li>
 * </ul>
 */
class EngineEndingTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 带 hp/san 两类结局条件 + 一个 turn>=10 好结局的世界。 */
	private ObjectNode world(int hp, int san) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.2");
		w.putObject("character").putObject("attributes").put("hp", hp).put("san", san);
		w.putArray("rules"); // 空规则集即可
		var endings = w.putArray("endings");
		endings.addObject().put("id", "survive_dawn").put("title", "撑到天亮")
				.put("condition", "成功存活至6:00").put("reached", false);
		endings.addObject().put("id", "lost_mind").put("title", "精神崩溃")
				.put("condition", "san值降至0").put("reached", false);
		endings.addObject().put("id", "death_by_entity").put("title", "横死")
				.put("condition", "hp降至0").put("reached", false);
		return w;
	}

	/** 无 hp/san 条件关键字的世界(逼出 (c) fallback 路径)。 */
	private ObjectNode worldNoMatchableCondition(int hp, int san) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.2");
		w.putObject("character").putObject("attributes").put("hp", hp).put("san", san);
		w.putArray("rules");
		var endings = w.putArray("endings");
		endings.addObject().put("id", "the_long_dark").put("title", "长夜")
				.put("condition", "迷失在走廊尽头").put("reached", false);
		endings.addObject().put("id", "dawn").put("title", "黎明")
				.put("condition", "走出大门").put("reached", false);
		return w;
	}

	private ObjectNode turn(double hp, double san) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "一些叙事。");
		t.putObject("stateUpdate").put("hp", hp).put("san", san);
		var a = t.putArray("availableActions");
		a.addObject().put("id", "A").put("text", "继续");
		a.addObject().put("id", "B").put("text", "撤退");
		return t;
	}

	// ── (a) AI 给合法结局,数值未触底 ────────────────────────────────
	@Test
	void aiValidEndingEndsAndMarksWithoutBottomOut() {
		Engine eng = new Engine(world(60, 60), mapper);
		ObjectNode t = turn(60, 60); // 数值健康
		t.putObject("ending").put("id", "survive_dawn").put("reached", true);
		eng.apply(t, "A");
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(reachedIds(eng)).containsExactly("survive_dawn");
	}

	@Test
	void aiEndingWithReachedFalseDoesNotEnd() {
		Engine eng = new Engine(world(60, 60), mapper);
		ObjectNode t = turn(60, 60);
		t.putObject("ending").put("id", "survive_dawn").put("reached", false);
		eng.apply(t, "A");
		assertThat(eng.status()).isEqualTo("ongoing");
		assertThat(reachedIds(eng)).isEmpty();
	}

	// ── (c) 触底但无 condition 匹配 → 约定 fallback(首条)──────────────
	@Test
	void bottomOutWithoutMatchableConditionUsesFirstEndingAsFallback() {
		Engine eng = new Engine(worldNoMatchableCondition(20, 80), mapper);
		eng.apply(turn(0, 80), "A"); // hp 触底,但没有 condition 提及 hp
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(reachedIds(eng)).containsExactly("the_long_dark"); // endings[] 首条
	}

	// ── (附) AI 给的 ending.id 不在 endings[] → 不接受 ─────────────────
	@Test
	void aiUnknownEndingIdIsNotAccepted() {
		Engine eng = new Engine(world(60, 60), mapper);
		ObjectNode t = turn(60, 60); // 数值健康,不触发兜底
		t.putObject("ending").put("id", "nonexistent_ending").put("reached", true);
		eng.apply(t, "A");
		// §4.4:幽灵 id 不被接受 —— 不 end、不标任何 ending
		assertThat(eng.status()).isEqualTo("ongoing");
		assertThat(reachedIds(eng)).isEmpty();
	}

	@Test
	void aiUnknownEndingIdAtBottomOutFallsBackToEngineEnding() {
		Engine eng = new Engine(world(20, 80), mapper);
		ObjectNode t = turn(0, 80); // hp 触底 + AI 给幽灵 id
		t.putObject("ending").put("id", "ghost").put("reached", true);
		eng.apply(t, "A");
		// 幽灵 id 不接受 → 触底兜底接管,选 condition 提及 hp 的坏结局
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(reachedIds(eng)).containsExactly("death_by_entity");
	}

	private java.util.List<String> reachedIds(Engine eng) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (JsonNode e : eng.world().path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				out.add(e.path("id").asString());
			}
		}
		return out;
	}
}
