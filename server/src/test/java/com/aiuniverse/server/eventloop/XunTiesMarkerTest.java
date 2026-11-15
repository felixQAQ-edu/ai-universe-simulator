package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.LlmException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-040 决策 1 / 2 ·《寻常》牵挂升档标记:走真实落账路径 {@code EventLoopService.landSettled},
 * 落账后 ties 的档序号(取整后 {@code bandIndex})比落账前高 → 记「T{n} 牵挂升档 a→b」;解析见 {@link XunTiesMarker#turns}。
 * 验证 B(提示词逐字节不变)与验证 C(判定)。
 */
class XunTiesMarkerTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);
	private final EventLoopService service = new EventLoopService((req, sink) -> {
		throw new LlmException("本测试不调模型");
	}, builder, mapper);

	// ── 验证 C:判定 ───────────────────────────────────────────────────

	@Test
	void oneBandUpRecordsOneLine() {
		Engine e = landTies(13, 28, 33);
		assertThat(e.issues()).containsExactly("T14 牵挂升档 28→33");
	}

	@Test
	void twoBandsInOneTurnRecordOnlyOneLine() {
		Engine e = landTies(4, 20, 70);
		assertThat(markers(e)).containsExactly("T5 牵挂升档 20→70");
	}

	@Test
	void flatOrDownRecordsNothing() {
		for (double[] c : new double[][] { { 33, 40 }, { 40, 33 }, { 65, 20 }, { 30, 30 }, { 61, 100 } }) {
			Engine e = landTies(10, c[0], c[1]);
			assertThat(markers(e)).as(c[0] + "→" + c[1]).isEmpty();
		}
	}

	@Test
	void roundingFollowsTheServerReading() {
		// Felix 2026-11-15 裁定:Math.round 后判档。30.4 → 30(轻身,不记);30.6 → 31(有所系,记)。
		assertThat(markers(landTies(10, 29, 30.4))).isEmpty();
		assertThat(markers(landTies(10, 29, 30.6))).containsExactly("T11 牵挂升档 29→30.6");
		// 落账前的值同样取整:60.6 已是「深系」,到 70 不算升档。
		assertThat(markers(landTies(10, 60.6, 70))).isEmpty();
		assertThat(markers(landTies(10, 60.4, 70))).containsExactly("T11 牵挂升档 60.4→70");
	}

	@Test
	void bandTableComesFromTheAxisNotFromLiterals() {
		// 另造一份档表不同的 ties 轴(0 / 40 / 70):判档必须跟着它走,而不是 31 / 61 两个字面量。
		AttributeAxis other = AttributeAxis.accumulating("ties", "牵挂", "测").withBands(
				new AttributeAxis.Band(0, "一", "h"), new AttributeAxis.Band(40, "二", "h"),
				new AttributeAxis.Band(70, "三", "h"));
		Engine e = engine("life_sim", 10, Map.of("ties", 30.0));
		e.apply(parsed(Map.of("ties", 35.0)), "A"); // 30 → 35:按《寻常》档表算升档,按这份不算
		XunTiesMarker.mark(e, other, 30.0);
		assertThat(markers(e)).isEmpty();
		e.apply(parsed(Map.of("ties", 45.0)), "A"); // 35 → 45:按这份升了一档
		XunTiesMarker.mark(e, other, 35.0);
		assertThat(markers(e)).containsExactly("T12 牵挂升档 35→45");
	}

	@Test
	void eachUpTurnGetsItsOwnLineAcrossTheGame() {
		Engine e = landTies(2, 10, 35); // T3:轻身 → 有所系
		landOn(e, 50); // T4:同档
		landOn(e, 62); // T5:有所系 → 深系
		landOn(e, 80); // T6:同档
		assertThat(markers(e)).containsExactly("T3 牵挂升档 10→35", "T5 牵挂升档 50→62");
		assertThat(XunTiesMarker.turns(e.issues())).containsExactly(3, 5);
	}

	@Test
	void degradedTurnRecordsNothing() {
		Engine e = engine("life_sim", 10, Map.of("ties", 28.0));
		GameSession s = new GameSession("save-xun", e, actions());
		service.landDegraded(s, "日子照常。", "A", null, () -> {
		});
		assertThat(e.turn()).isEqualTo(11);
		assertThat(markers(e)).isEmpty();
	}

	@Test
	void otherWorldsAndFusionRecordNothing() {
		for (String id : List.of("rules_creepy", "cthulhu", "cultivation", "apocalypse", "cyberpunk", "animal_life",
				"time_travel")) {
			Engine e = engine(id, 10, Map.of());
			ObjectNode parsed = parsed(Map.of());
			// 每根轴都从 50 涨到 95:凡是有档表的轴都跨了档,但不是《寻常》,一律不记。
			registry.meta(id).attributes().forEach(a -> ((ObjectNode) parsed.get("stateUpdate")).put(a.key(), 95.0));
			service.landSettled(new GameSession("s", e, actions()), parsed, "A", null, leak -> {
			});
			assertThat(markers(e)).as(id).isEmpty();
		}
		// 融合局里即便有一根叫 ties 的轴、host 是 life_sim,也不判(只查单体)。
		Engine fused = engine("life_sim", 10, Map.of("ties", 20.0));
		((ArrayNode) fused.world().get("archetypes")).add("cultivation");
		service.landSettled(new GameSession("s", fused, actions()), parsed(Map.of("ties", 70.0)), "A", null, leak -> {
		});
		assertThat(markers(fused)).isEmpty();
	}

	@Test
	void prefixDoesNotConfuseTurnOneWithTurnTwelve() {
		assertThat(XunTiesMarker.turns(List.of("T12 牵挂升档 28→33"))).containsExactly(12);
		assertThat(XunTiesMarker.turns(List.of("T1 牵挂升档 28→33", "T12 牵挂升档 50→62"))).containsExactly(1, 12);
		// 只认本前缀;别的世界的标记、引擎异常、残缺的行一律跳过;重复的回合去重、结果升序。
		assertThat(XunTiesMarker.turns(List.of("T5 画像向上跨档 29→30", "T6 底细跌破 20 25→15",
				"T7 ties 跳变过大 10->70(需复核)", "T8 牵挂升档", "T牵挂升档 1→40", "Tx 牵挂升档 1→40",
				"T9 牵挂升档 30→31", "T3 牵挂升档 20→40", "T9 牵挂升档 30→31"))).containsExactly(3, 9);
	}

	@Test
	void lineAvoidsTheSubstringsTheStatsToolCounts() {
		// ADR-033 DataStats 按 contains 计数这两个词(ISSUE_GATE / ISSUE_CLAMP);新行撞上就会被算进去。
		Engine e = landTies(13, 28, 33);
		assertThat(markers(e)).singleElement().satisfies(line -> {
			assertThat(line).doesNotContain("ADR-010 gate").doesNotContain("收束下限钳制");
		});
	}

	@Test
	void markerSurvivesSaveAndRestore() {
		Engine e = landTies(13, 28, 33);
		Engine restored = QiriCoverDropTest.restore(mapper, registry, e.toPersistedState());
		assertThat(XunTiesMarker.turns(restored.issues())).containsExactly(14);
	}

	// ── 验证 B:回合提示词逐字节不变 ───────────────────────────────────

	@Test
	void theMarkerLineDoesNotChangeTheTurnPrompt() {
		Engine without = engine("life_sim", 14, Map.of("ties", 33.0));
		Engine with = engine("life_sim", 14, Map.of("ties", 33.0));
		with.recordIssue(XunTiesMarker.xunTiesUpIssuePrefix(14) + " 28→33");
		assertThat(with.issues()).isNotEqualTo(without.issues());
		assertThat(service.renderTurnPrompt(new GameSession("s", with, actions()), "A"))
				.isEqualTo(service.renderTurnPrompt(new GameSession("s", without, actions()), "A"));
		assertThat(builder.buildTurnPrompt(with, "A", "测试行动"))
				.isEqualTo(builder.buildTurnPrompt(without, "A", "测试行动"));
	}

	// ── 夹具 ──────────────────────────────────────────────────────────

	private static List<String> markers(Engine e) {
		return e.issues().stream().filter(s -> s.contains("牵挂升档")).toList();
	}

	private Engine landTies(int turn, double before, double after) {
		Engine e = engine("life_sim", turn, Map.of("ties", before));
		landOn(e, after);
		return e;
	}

	private void landOn(Engine e, double ties) {
		service.landSettled(new GameSession("save-xun", e, actions()), parsed(Map.of("ties", ties)), "A", null,
				leak -> {
				});
	}

	private Engine engine(String id, int turn, Map<String, Double> attrs) {
		Engine e = QiriCoverDropTest.engine(mapper, registry, id, turn, Map.of());
		if (attrs.isEmpty()) {
			return e;
		}
		ObjectNode doc = e.toPersistedState();
		ObjectNode a = (ObjectNode) doc.path("world").path("character").path("attributes");
		attrs.forEach(a::put);
		return QiriCoverDropTest.restore(mapper, registry, doc);
	}

	private ObjectNode parsed(Map<String, Double> upd) {
		ObjectNode parsed = mapper.createObjectNode();
		parsed.put("narrative", "那年冬天来得早。");
		ObjectNode su = parsed.putObject("stateUpdate");
		upd.forEach(su::put);
		su.put("timeline", "t");
		parsed.putArray("triggeredRuleIds");
		parsed.putArray("discoveredRuleIds");
		parsed.set("availableActions", actions());
		parsed.putNull("ending");
		return parsed;
	}

	private ArrayNode actions() {
		ArrayNode a = mapper.createArrayNode();
		a.addObject().put("id", "A").put("text", "去看看他").put("hint", "");
		a.addObject().put("id", "B").put("text", "改天再说").put("hint", "");
		return a;
	}
}
