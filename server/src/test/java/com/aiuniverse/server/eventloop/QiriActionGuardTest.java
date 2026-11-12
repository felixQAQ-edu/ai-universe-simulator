package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.LlmException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-038「订正(2026-11-12 一)」·《七日开张》按选项前缀的服务端兜底 + 每回合数值钳制({@link QiriActionGuard})。
 * 逐格表照 ADR 手写(不复用被测代码);落账路径走真实的 {@code EventLoopService.landSettled}。
 */
class QiriActionGuardTest {

	private static final String ODD = QiriActionGuard.ODD_IDEA;
	private static final String SMOOTH = QiriActionGuard.SMOOTH_OVER;

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final EventLoopService service = new EventLoopService((req, sink) -> {
		throw new LlmException("本测试不调模型");
	}, new TurnPromptBuilder(registry), mapper);

	// ① 兜底与钳制逐格(纯函数 settle)─────────────────────────────────

	/** 一格:第 n 回合、前缀、轴、落账前、模型给的 → 期望写入值。 */
	private record Cell(String name, int n, String prefix, String axis, double before, double proposed, double expected) {
	}

	/** 早 = 第 4 回合;午 = 第 5 回合;晚 = 第 6 回合;第 22 回合 = 夜集。落账前一律摊本 40 / 熟客 30 / 底细 50。 */
	private static List<Cell> cells() {
		List<Cell> c = new java.util.ArrayList<>();
		int[] busy = { 5, 6, 22 }; // 午、晚、第 22 回合
		String[] busyName = { "午", "晚", "第22回合" };
		for (int i = 0; i < busy.length; i++) {
			int n = busy[i];
			String t = busyName[i] + " ";
			// 怪点子:摊本 Δ ≥ +8,底细 Δ ≤ −6
			c.add(new Cell(t + "怪点子 摊本 给少(+2)→ 兜底 +8", n, ODD, "capital", 40, 42, 48));
			c.add(new Cell(t + "怪点子 摊本 没动(+0)→ 兜底 +8", n, ODD, "capital", 40, 40, 48));
			c.add(new Cell(t + "怪点子 摊本 给多(+12)→ 照收", n, ODD, "capital", 40, 52, 52));
			c.add(new Cell(t + "怪点子 摊本 给太多(+24)→ 钳到 +12", n, ODD, "capital", 40, 64, 52));
			c.add(new Cell(t + "怪点子 底细 给少(不扣)→ 兜底 −6", n, ODD, "cover", 50, 50, 44));
			c.add(new Cell(t + "怪点子 底细 给多(−10)→ 照收", n, ODD, "cover", 50, 40, 40));
			c.add(new Cell(t + "怪点子 底细 给太多(−20)→ 钳到 −12", n, ODD, "cover", 50, 30, 38));
			c.add(new Cell(t + "怪点子 熟客 +20 → 钳到 +8", n, ODD, "regulars", 30, 50, 38));
			// 打圆场:底细 Δ ≥ +6,摊本 Δ ≤ −3
			c.add(new Cell(t + "打圆场 底细 给少(+2)→ 兜底 +6", n, SMOOTH, "cover", 50, 52, 56));
			c.add(new Cell(t + "打圆场 底细 给多(+15)→ 钳到 +6", n, SMOOTH, "cover", 50, 65, 56));
			c.add(new Cell(t + "打圆场 摊本 给了营业(+3)→ 兜底 −3", n, SMOOTH, "capital", 40, 43, 37));
			c.add(new Cell(t + "打圆场 摊本 给多(−6)→ 照收", n, SMOOTH, "capital", 40, 34, 34));
			// 无前缀:只钳制
			c.add(new Cell(t + "无前缀 摊本 +3 → 照收", n, null, "capital", 40, 43, 43));
			c.add(new Cell(t + "无前缀 摊本 +24 → 钳到 +12", n, null, "capital", 40, 64, 52));
			c.add(new Cell(t + "无前缀 摊本 −30 → 钳到 −15", n, null, "capital", 40, 10, 25));
			c.add(new Cell(t + "无前缀 底细 −1 → 照收", n, null, "cover", 50, 49, 49));
			c.add(new Cell(t + "无前缀 底细 +20 → 钳到 +6", n, null, "cover", 50, 70, 56));
			c.add(new Cell(t + "无前缀 熟客 −20 → 钳到 −8", n, null, "regulars", 30, 10, 22));
		}
		int m = 4; // 早回合
		c.add(new Cell("早 怪点子 摊本 给了进货(−6)→ 兜底 Δ0(只省下进货那 6)", m, ODD, "capital", 40, 34, 40));
		c.add(new Cell("早 怪点子 摊本 给多(+10)→ 照收", m, ODD, "capital", 40, 50, 50));
		c.add(new Cell("早 怪点子 摊本 给太多(+20)→ 钳到 +12", m, ODD, "capital", 40, 60, 52));
		c.add(new Cell("早 怪点子 底细 给少(−1)→ 兜底 −6", m, ODD, "cover", 50, 49, 44));
		c.add(new Cell("早 怪点子 底细 给多(−12)→ 照收", m, ODD, "cover", 50, 38, 38));
		c.add(new Cell("早 打圆场 底细 给少(+0)→ 兜底 +6", m, SMOOTH, "cover", 50, 50, 56));
		c.add(new Cell("早 打圆场 摊本 给少(−1)→ 兜底 −3", m, SMOOTH, "capital", 40, 39, 37));
		c.add(new Cell("早 打圆场 摊本 给多(−9)→ 照收", m, SMOOTH, "capital", 40, 31, 31));
		c.add(new Cell("早 无前缀 摊本 进货 −6 → 照收", m, null, "capital", 40, 34, 34));
		c.add(new Cell("早 无前缀 底细 −20 → 钳到 −12", m, null, "cover", 50, 30, 38));
		// 0–100 最后再 clamp
		c.add(new Cell("打圆场 底细 97 → 兜底 103 → clamp 100", 5, SMOOTH, "cover", 97, 97, 100));
		c.add(new Cell("怪点子 底细 3 → 兜底 −3 → clamp 0", 5, ODD, "cover", 3, 3, 0));
		c.add(new Cell("无前缀 摊本 5 给 −8 → clamp 0", 5, null, "capital", 5, -8, 0));
		return c;
	}

	@Test
	void guardAndClampCellByCell() {
		List<Cell> cells = cells();
		for (Cell c : cells) {
			double[] r = QiriActionGuard.settle(c.n(), c.prefix(), c.axis(), c.before(), c.proposed());
			assertThat(r[1]).as(c.name()).isEqualTo(c.expected());
		}
		assertThat(cells).hasSize(3 * 18 + 10 + 3);
	}

	@Test
	void morningIsEveryThirdTurnBeforeTheNightMarket() {
		for (int n = 1; n <= 30; n++) {
			boolean expected = n < 22 && (n == 1 || n == 4 || n == 7 || n == 10 || n == 13 || n == 16 || n == 19);
			assertThat(QiriActionGuard.morning(n)).as("T" + n).isEqualTo(expected);
		}
	}

	/** 兜底的界都在钳制区间之内(ADR 订正一「兜底与钳制核算」):打圆场合起来就是底细 +6。 */
	@Test
	void guardBoundsLieInsideTheClampRanges() {
		double[] cover = QiriActionGuard.DELTA_RANGE.get("cover");
		double[] capital = QiriActionGuard.DELTA_RANGE.get("capital");
		assertThat(-6.0).isBetween(cover[0], cover[1]);
		assertThat(6.0).isEqualTo(cover[1]);
		assertThat(8.0).isBetween(capital[0], capital[1]);
		assertThat(0.0).isBetween(capital[0], capital[1]);
		assertThat(-3.0).isBetween(capital[0], capital[1]);
		assertThat(QiriActionGuard.settle(5, SMOOTH, "cover", 50, 99)[1]).isEqualTo(56);
		assertThat(QiriActionGuard.settle(5, SMOOTH, "cover", 50, 0)[1]).isEqualTo(56);
	}

	// ② 前缀识别:字面精确匹配,必须在开头 ────────────────────────────────

	@Test
	void prefixIsALiteralMatchAtTheStart() {
		assertThat(QiriActionGuard.prefixOf("【怪点子】削取号竹牌")).isEqualTo(ODD);
		assertThat(QiriActionGuard.prefixOf("【打圆场】请街坊喝茶")).isEqualTo(SMOOTH);
		assertThat(QiriActionGuard.prefixOf("【怪点子】 削取号竹牌")).as("前缀之后多空格:前缀仍在开头,算").isEqualTo(ODD);
		assertThat(QiriActionGuard.prefixOf("削取号竹牌【怪点子】")).as("前缀写在句中").isNull();
		assertThat(QiriActionGuard.prefixOf("先吆喝一声,再【打圆场】")).as("前缀写在句中").isNull();
		assertThat(QiriActionGuard.prefixOf("[怪点子]削取号竹牌")).as("半角方括号").isNull();
		assertThat(QiriActionGuard.prefixOf("［怪点子］削取号竹牌")).as("全角方括号(不是【】)").isNull();
		assertThat(QiriActionGuard.prefixOf("「打圆场」请街坊喝茶")).as("直角引号").isNull();
		assertThat(QiriActionGuard.prefixOf(" 【怪点子】削取号竹牌")).as("前面多空格").isNull();
		assertThat(QiriActionGuard.prefixOf("　【打圆场】请街坊喝茶")).as("前面多全角空格").isNull();
		assertThat(QiriActionGuard.prefixOf("【 怪点子 】削取号竹牌")).as("括号里多空格").isNull();
		assertThat(QiriActionGuard.prefixOf("怪点子:削取号竹牌")).as("没有括号").isNull();
		assertThat(QiriActionGuard.prefixOf(null)).isNull();
	}

	@Test
	void unrecognisedPrefixOnlyGetsTheClamp() {
		Engine e = engine(4, Map.of("capital", 40, "cover", 50));
		ObjectNode parsed = parsed(Map.of("capital", 40, "cover", 50));
		List<String> issues = QiriActionGuard.apply(e, parsed, "A", actions("[怪点子]削取号竹牌", "【怪点子】画三栏账"));
		assertThat(issues).as("选的 A 前缀不对:不兜底;B 带了正确前缀:不缺怪点子选项").isEmpty();
		assertThat(parsed.path("stateUpdate").path("cover").asDouble()).isEqualTo(50);
	}

	// ③ 落账路径:冒烟原样回归 + issue ─────────────────────────────────

	/** `6a3b425c` 第 22 回合:落账前摊本 2、模型给 64 并提议夜集第一摊 → 钳到 14 → 明日照常开张。 */
	@Test
	void smokeRegression6a3bTurn22CapitalJumpIsClampedTo14AndTheEndingIsOpenAsUsual() {
		Engine e = engine(21, Map.of("capital", 2, "regulars", 52, "cover", 60));
		land(e, Map.of("capital", 64, "regulars", 55, "cover", 58), "night_market_top", "打听夜集的摊位", "歇下");
		assertThat(e.attributes().get("capital")).isEqualTo(14.0);
		assertThat(e.status()).isEqualTo("ended");
		assertThat(reachedOf(e)).isEqualTo("open_as_usual");
		assertThat(e.issues()).containsSubsequence("T22 钳制 capital 2→64 改 14", "T22 缺怪点子选项",
				"T22 结局核对改判 night_market_top → open_as_usual");
		assertThat(e.issues()).noneMatch(s -> s.contains("跳变过大"));
	}

	@Test
	void oddIdeaGuardWritesAndRecordsOneIssuePerAxis() {
		Engine e = engine(8, Map.of("capital", 40, "regulars", 20, "cover", 79));
		land(e, Map.of("capital", 41, "regulars", 22, "cover", 79), null, "【怪点子】削取号竹牌", "歇下");
		assertThat(e.attributes()).containsEntry("capital", 48.0).containsEntry("regulars", 22.0).containsEntry("cover", 73.0);
		assertThat(e.issues()).containsExactly("T9 怪点子兜底 capital 41→48", "T9 怪点子兜底 cover 79→73");
	}

	@Test
	void smoothOverGuardWritesBothAxes() {
		Engine e = engine(10, Map.of("capital", 40, "cover", 30));
		land(e, Map.of("capital", 43, "cover", 31), null, "【打圆场】请街坊喝茶,说是老家的规矩", "【怪点子】画三栏账");
		assertThat(e.attributes()).containsEntry("capital", 37.0).containsEntry("cover", 36.0);
		assertThat(e.issues()).containsExactly("T11 打圆场兜底 capital 43→37", "T11 打圆场兜底 cover 31→36");
	}

	@Test
	void missingAxisCountsAsNoChangeAndIsStillGuarded() {
		Engine e = engine(5, Map.of("capital", 40, "cover", 50));
		land(e, Map.of(), null, "【怪点子】第二件半价", "歇下");
		assertThat(e.attributes()).containsEntry("capital", 48.0).containsEntry("cover", 44.0);
	}

	@Test
	void nothingRecordedWhenNothingChangesAndAnOddIdeaIsOffered() {
		Engine e = engine(5, Map.of("capital", 40, "regulars", 30, "cover", 50));
		land(e, Map.of("capital", 43, "regulars", 35, "cover", 50), null, "吆喝一声", "【怪点子】画三栏账");
		assertThat(e.issues()).isEmpty();
	}

	@Test
	void missingOddIdeaOptionIsOnlyRecorded() {
		Engine e = engine(5, Map.of("capital", 40, "cover", 50));
		land(e, Map.of("capital", 43, "cover", 50), null, "吆喝一声", "【打圆场】请街坊喝茶");
		assertThat(e.issues()).containsExactly("T6 缺怪点子选项");
		assertThat(e.status()).isEqualTo("ongoing");
		assertThat(e.attributes()).containsEntry("capital", 43.0);
	}

	@Test
	void otherWorldsAreUntouchedEvenWithThePrefix() {
		for (String id : List.of("rules_creepy", "cthulhu", "cultivation", "apocalypse", "cyberpunk", "life_sim")) {
			Engine e = QiriCoverDropTest.engine(mapper, registry, id, 10, Map.of());
			ObjectNode parsed = parsed(Map.of());
			registry.meta(id).attributes().forEach(a -> ((ObjectNode) parsed.get("stateUpdate")).put(a.key(), 95.0));
			service.landSettled(new GameSession("s", e, actions("【怪点子】削取号竹牌", "【打圆场】请街坊喝茶")), parsed, "A",
					null, leak -> {
					});
			assertThat(e.issues()).as(id).noneMatch(s -> s.contains("兜底") || s.contains("钳制") || s.contains("怪点子"));
		}
	}

	// ── 夹具 ──────────────────────────────────────────────────────────

	private void land(Engine e, Map<String, Integer> upd, String proposed, String textA, String textB) {
		ObjectNode parsed = parsed(upd);
		if (proposed != null) {
			parsed.putObject("ending").put("reached", true).put("id", proposed);
		}
		service.landSettled(new GameSession("save-qiri", e, actions(textA, textB)), parsed, "A", null, leak -> {
		});
	}

	private Engine engine(int turn, Map<String, Integer> attrs) {
		Map<String, Integer> a = new LinkedHashMap<>(Map.of("capital", 50, "regulars", 50, "cover", 50));
		a.putAll(attrs);
		ObjectNode p = QiriCoverDropTest.engine(mapper, registry, "time_travel", turn, a).toPersistedState();
		ArrayNode endings = ((ObjectNode) p.path("world")).putArray("endings");
		for (String[] m : new String[][] { { "night_market_top", "夜集第一摊", "success" },
				{ "odd_idea_king", "城中怪点子王", "success" }, { "open_as_usual", "明日照常开张", "neutral" },
				{ "taoist_summoned", "请来了道士", "failure" } }) {
			endings.addObject().put("id", m[0]).put("title", m[1]).put("outcome", m[2]).put("condition", "底细")
					.put("reached", false);
		}
		return QiriCoverDropTest.restore(mapper, registry, p);
	}

	private ObjectNode parsed(Map<String, Integer> upd) {
		ObjectNode parsed = mapper.createObjectNode();
		parsed.put("narrative", "街上起了风。");
		ObjectNode su = parsed.putObject("stateUpdate");
		upd.forEach((k, v) -> su.put(k, v.doubleValue()));
		su.put("timeline", "t");
		parsed.putArray("triggeredRuleIds");
		parsed.putArray("discoveredRuleIds");
		parsed.set("availableActions", actions("【怪点子】画三栏账", "歇下"));
		parsed.putNull("ending");
		return parsed;
	}

	private ArrayNode actions(String textA, String textB) {
		ArrayNode a = mapper.createArrayNode();
		a.addObject().put("id", "A").put("text", textA).put("hint", "h");
		a.addObject().put("id", "B").put("text", textB).put("hint", "h");
		return a;
	}

	private static String reachedOf(Engine engine) {
		for (JsonNode e : engine.world().path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				return e.path("id").asString();
			}
		}
		return null;
	}
}
