package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 单测边界(规格 §5/§7,bake-off 未覆盖 / 新设计)——
 * 兜底结局 id、跳变 &gt;40 标记不拒绝、clamp 越界、log 压缩 LOG_KEEP=4 折叠。
 */
class EngineBoundaryTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 含 hp/san 两类结局条件的最小世界(condition 用中文,贴近真实产出)。 */
	private ObjectNode world(int hp, int san) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.2");
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		attrs.put("hp", hp);
		attrs.put("san", san);
		var rules = w.putArray("rules");
		ObjectNode r1 = rules.addObject();
		r1.put("id", 1);
		r1.put("content", "0 点后不要回应敲窗声");
		r1.put("isTrue", true);
		r1.put("hiddenLogic", "回应触发 san-20");
		r1.put("discovered", false);
		var endings = w.putArray("endings");
		ObjectNode e1 = endings.addObject();
		e1.put("id", "survive_dawn");
		e1.put("title", "撑到天亮");
		e1.put("condition", "成功存活至6:00");
		e1.put("reached", false);
		ObjectNode e2 = endings.addObject();
		e2.put("id", "lost_mind");
		e2.put("title", "精神崩溃");
		e2.put("condition", "san值降至0");
		e2.put("reached", false);
		ObjectNode e3 = endings.addObject();
		e3.put("id", "death_by_entity");
		e3.put("title", "横死");
		e3.put("condition", "hp降至0");
		e3.put("reached", false);
		return w;
	}

	private ObjectNode turn(double hp, double san) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "一些氛围叙事。");
		ObjectNode upd = t.putObject("stateUpdate");
		upd.put("hp", hp);
		upd.put("san", san);
		var actions = t.putArray("availableActions");
		actions.addObject().put("id", "A").put("text", "继续");
		actions.addObject().put("id", "B").put("text", "撤退");
		return t;
	}

	@Test
	void bottomOutSanForcesBadEndingIdWhenAiGivesNone() {
		Engine eng = new Engine(world(80, 20), mapper);
		eng.apply(turn(80, 0), "A"); // san 触底,AI 未给 ending
		assertThat(eng.status()).isEqualTo("ended");
		// §5 补丁:引擎兜一个 condition 提及 san 的坏结局(lost_mind),非 survive/hp。
		assertThat(reachedId(eng)).isEqualTo("lost_mind");
	}

	@Test
	void bottomOutHpForcesHpEndingId() {
		Engine eng = new Engine(world(30, 80), mapper);
		eng.apply(turn(0, 80), "A");
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(reachedId(eng)).isEqualTo("death_by_entity");
	}

	@Test
	void aiProvidedEndingWinsOverForcedFallback() {
		Engine eng = new Engine(world(80, 20), mapper);
		ObjectNode t = turn(80, 0);
		t.putObject("ending").put("id", "survive_dawn").put("reached", true);
		eng.apply(t, "A");
		// AI 已给结局 → 引擎不再兜底覆盖
		assertThat(reachedId(eng)).isEqualTo("survive_dawn");
	}

	@Test
	void hugeJumpIsFlaggedButNotRejected() {
		Engine eng = new Engine(world(100, 100), mapper); // 默认 hp/san=100
		eng.apply(turn(40, 100), "A"); // hp 100->40,跳变 60 > 40
		assertThat(eng.hp()).isEqualTo(40.0); // 落账,不拒绝
		assertThat(eng.issues()).anyMatch(s -> s.contains("hp 跳变过大"));
		assertThat(eng.status()).isEqualTo("ongoing");
	}

	@Test
	void smallJumpNotFlagged() {
		Engine eng = new Engine(world(100, 100), mapper);
		eng.apply(turn(70, 100), "A"); // 跳变 30 <= 40
		assertThat(eng.issues()).isEmpty();
	}

	@Test
	void outOfRangeValuesAreClamped() {
		Engine eng = new Engine(world(100, 100), mapper);
		eng.apply(turn(150, -20), "A"); // schema 本应先拦;引擎仍 clamp 兜底
		assertThat(eng.hp()).isEqualTo(100.0);
		assertThat(eng.san()).isEqualTo(0.0);
		assertThat(eng.status()).isEqualTo("ended"); // san<=0 触底
	}

	@Test
	void logCompressionKeepsLastFourFoldsOlderIntoSummary() {
		Engine eng = new Engine(world(100, 100), mapper);
		for (int i = 1; i <= 6; i++) {
			eng.apply(turn(100, 100), "A");
		}
		assertThat(eng.log()).hasSize(Engine.LOG_KEEP); // 只留近 4 条
		assertThat(eng.log().get(0).get("turn").asInt()).isEqualTo(3); // T3..T6
		assertThat(eng.logSummary()).isEqualTo("[T1选A] [T2选A]"); // 旧的 T1/T2 折进摘要
	}

	private String reachedId(Engine eng) {
		for (JsonNode e : eng.world().path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				return e.path("id").asString();
			}
		}
		return null;
	}
}
