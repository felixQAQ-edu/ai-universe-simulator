package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-010 结局极性 gate(根治 F-014:濒死人物得成功结局)。
 *
 * <p>引擎在 {@code apply} 步骤 9 加一道 gate:<b>致命轴濒零(≤ {@link Engine#ENDING_GATE_THRESHOLD})且 AI 提议
 * {@code outcome==success} 结局 → 拒绝,据极性确定性挑失败结局</b>。引擎只读 outcome 标签 + 看致命轴值,不懂结局
 * 语义(守 ADR-008)。gate 仅对 {@code outcome=="success"} 生效——neutral(含无 outcome 老世界)/failure 一律放过
 * (向后兼容,golden 零回归,见 {@link EngineGoldenTest})。顺带验 F-015:非致命 depletion 轴(灵力)濒零不 gate。
 */
class EngineEndingPolarityGateTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 修仙世界:hp(气血,致命 depletion)+ mana(灵力,非致命 depletion)+ realm(境界,accumulation)。 */
	private ObjectNode cultivationWorld(int hp, int mana, int realm) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4");
		w.putObject("character").putObject("attributes").put("hp", hp).put("mana", mana).put("realm", realm);
		w.putArray("rules");
		var endings = w.putArray("endings");
		endings.addObject().put("id", "ascend").put("title", "筑基功成")
				.put("condition", "境界圆满、白日飞升").put("outcome", "success").put("reached", false);
		endings.addObject().put("id", "meridians_shattered").put("title", "经脉俱断")
				.put("condition", "气血枯竭、身死道消").put("outcome", "failure").put("reached", false);
		return w;
	}

	/** 修仙引擎:realm 累积、mana 非致命、轴中文名(§5 中文 condition 匹配)。 */
	private Engine cultivationEngine(ObjectNode w) {
		return new Engine(w, mapper, Set.of("realm"), Map.of("hp", "气血", "mana", "灵力", "realm", "境界"),
				Set.of("mana"));
	}

	private ObjectNode turn(Map<String, Integer> stateUpdate, String endingId) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "灵气在经脉中游走。");
		ObjectNode upd = t.putObject("stateUpdate");
		stateUpdate.forEach(upd::put);
		var a = t.putArray("availableActions");
		a.addObject().put("id", "A").put("text", "继续打坐");
		a.addObject().put("id", "B").put("text", "外出历练");
		if (endingId != null) {
			t.putObject("ending").put("id", endingId).put("reached", true);
		}
		return t;
	}

	// ── 核心:濒死 + AI 提议成功结局 → 引擎拒绝、改判失败结局(F-014 根治)────────────────
	@Test
	void nearDeathSuccessEndingIsRejectedAndReplacedWithFailure() {
		// 气血 8(濒死,≤10),AI 却提议成功结局 ascend(success)→ gate 拒绝、改判 meridians_shattered(failure)。
		Engine eng = cultivationEngine(cultivationWorld(8, 0, 60));
		eng.apply(turn(Map.of("hp", 8, "mana", 0, "realm", 60), "ascend"), "A");
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(reachedId(eng)).as("濒死的成功结局被拒、改判失败结局").isEqualTo("meridians_shattered");
		assertThat(eng.issues()).anyMatch(s -> s.contains("拒绝成功结局"));
	}

	// ── 濒死 + AI 提议失败结局 → 正常接受(濒死给失败合理)────────────────────────────
	@Test
	void nearDeathFailureEndingIsAccepted() {
		Engine eng = cultivationEngine(cultivationWorld(8, 0, 60));
		eng.apply(turn(Map.of("hp", 8, "mana", 0, "realm", 60), "meridians_shattered"), "A");
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(reachedId(eng)).as("濒死给失败结局 → 直接接受").isEqualTo("meridians_shattered");
		assertThat(eng.issues()).as("未触发 gate 改判").noneMatch(s -> s.contains("拒绝成功结局"));
	}

	// ── 非濒死 + AI 提议成功结局 → gate 不介入,正常接受(§4.4 现状)────────────────────
	@Test
	void healthySuccessEndingIsAcceptedGateDoesNotIntervene() {
		// 气血 80 健康 + 境界圆满 → AI 给成功结局 ascend,gate 不介入。
		Engine eng = cultivationEngine(cultivationWorld(80, 50, 100));
		eng.apply(turn(Map.of("hp", 80, "mana", 50, "realm", 100), "ascend"), "A");
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(reachedId(eng)).as("健康达成目标 → 成功结局正常接受").isEqualTo("ascend");
	}

	// ── F-015 验证:非致命 depletion 轴(灵力)濒零/枯竭 → 不 gate、不死 ──────────────────
	@Test
	void manaNearZeroDoesNotTriggerGate_F015() {
		// 灵力=0(力竭)但气血 80 健康 → 灵力非致命:既不触发 gate(成功结局照常接受)、也不触底致死。
		Engine eng = cultivationEngine(cultivationWorld(80, 0, 100));
		eng.apply(turn(Map.of("hp", 80, "mana", 0, "realm", 100), "ascend"), "A");
		assertThat(eng.status()).as("灵力非致命 → 仍能成功结局").isEqualTo("ended");
		assertThat(reachedId(eng)).isEqualTo("ascend");
	}

	@Test
	void manaBottomedOutAloneDoesNotKill_F015() {
		// 灵力=0、气血健康、AI 未给结局 → 灵力非致命轴,不触底致死,游戏继续。
		Engine eng = cultivationEngine(cultivationWorld(80, 0, 60));
		eng.apply(turn(Map.of("hp", 80, "mana", 0, "realm", 60), null), "A");
		assertThat(eng.status()).as("灵力枯竭=力竭非必死(F-015 关闭)").isEqualTo("ongoing");
		assertThat(reachedId(eng)).isNull();
	}

	// ── gate 仅对 outcome==success 生效:neutral 结局濒死也放过(向后兼容)───────────────
	@Test
	void neutralEndingAtNearDeathIsNotGated() {
		ObjectNode w = cultivationWorld(8, 0, 60);
		((ObjectNode) w.path("endings").get(0)).put("outcome", "neutral"); // ascend 改 neutral
		Engine eng = cultivationEngine(w);
		eng.apply(turn(Map.of("hp", 8, "mana", 0, "realm", 60), "ascend"), "A");
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(reachedId(eng)).as("neutral 结局不被 gate(只拦 success)").isEqualTo("ascend");
	}

	// ── 退路:濒死 + success 被拒,但无 failure 结局可挑 → 退中文名匹配/首条(合理 fallback)──
	@Test
	void noFailureEndingFallsBackDeterministically() {
		// 两个结局都标 success(畸形世界):gate 拒 ascend 后无 failure 可挑 →
		// pickFailureEnding 退到 condition 中文名匹配致命轴 → 命中 meridians(condition 含「气血」)。
		ObjectNode w = cultivationWorld(8, 0, 60);
		((ObjectNode) w.path("endings").get(1)).put("outcome", "success"); // meridians 也成 success
		Engine eng = cultivationEngine(w);
		eng.apply(turn(Map.of("hp", 8, "mana", 0, "realm", 60), "ascend"), "A");
		assertThat(eng.status()).isEqualTo("ended");
		// 无 failure 极性可挑 → 退「condition 提及致命轴(气血)」→ meridians_shattered。
		assertThat(reachedId(eng)).isEqualTo("meridians_shattered");
	}

	// ── §10 兜底据极性挑失败结局:致命轴触底 + AI 给 null → 优先 failure 极性 ────────────────
	@Test
	void bottomOutFallbackPrefersFailureOutcome() {
		// 气血触底(0)、AI 给 null → forceBottomOutEnding 复用 pickFailureEnding:
		// 这里 ascend(success,condition 也不含气血)/ meridians(failure)→ 挑 failure 的 meridians。
		Engine eng = cultivationEngine(cultivationWorld(20, 50, 60));
		eng.apply(turn(Map.of("hp", 0, "mana", 50, "realm", 60), null), "A");
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(reachedId(eng)).as("§10 兜底据极性挑 failure 结局").isEqualTo("meridians_shattered");
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
