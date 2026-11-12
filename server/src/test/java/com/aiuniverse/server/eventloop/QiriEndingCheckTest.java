package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.LlmException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-038 Slice D ·《七日开张》结局核对 {@link QiriEndingCheck}。钉:
 * <ol>
 * <li>落点表(决策 4)逐格:<b>直接测 {@code check()}</b> —— 走 {@code landSettled} 时引擎极性 gate(ADR-010)会把
 *     「底细 ≤ 10 的 success」也改判成请来了道士,盖住核对里的降级规则;</li>
 * <li>第 22 回合注入的判定表({@link TurnPromptBuilder#QIRI_FINAL_TABLE})解析后与核对逐格对拍(读法甲:写法不同、结果相同);</li>
 * <li>第 22 回合之前模型提议的结局一律不收(底细 ≤ 0 除外);</li>
 * <li>落账路径:改判记 issue、数值取落账后的值、判定 id 不在池里不改写、其他世界不碰。</li>
 * </ol>
 */
class QiriEndingCheckTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final EventLoopService service = new EventLoopService((req, sink) -> {
		throw new LlmException("本测试不调模型");
	}, new TurnPromptBuilder(registry), mapper);

	static final List<String> ENDINGS = List.of(QiriEndingCheck.NIGHT_MARKET_TOP, QiriEndingCheck.ODD_IDEA_KING,
			QiriEndingCheck.OPEN_AS_USUAL, QiriEndingCheck.TAOIST_SUMMONED);

	// ① 落点表逐格(决策 4,直接测 check) ───────────────────────────────

	/** ADR-038 决策 4「落点表(底细 × 摊本)」,照表手写,不复用被测代码。第 22 回合及以后成立。 */
	private static String adrLandingTable(int capital, int regulars, double cover) {
		if (cover <= 0) {
			return QiriEndingCheck.TAOIST_SUMMONED;
		}
		if (capital < 60) {
			return QiriEndingCheck.OPEN_AS_USUAL; // 1–10 / 11–39 / ≥ 40 三行的「摊本 < 60」列
		}
		if (cover <= 10) {
			return QiriEndingCheck.TAOIST_SUMMONED; // 1–10 × 摊本 ≥ 60:判出怪点子王后降级
		}
		if (cover < 40) { // ADR-038 订正一 决策 6:原「≤ 39」,小数 39.5 落在空隙里
			return QiriEndingCheck.ODD_IDEA_KING;
		}
		return regulars >= 50 ? QiriEndingCheck.NIGHT_MARKET_TOP : QiriEndingCheck.OPEN_AS_USUAL;
	}

	@Test
	void landingTableCellByCell() {
		double[] covers = { 0, 1, 5, 10, 11, 25, 39, 39.5, 40, 41, 80, 100 };
		int[] capitals = { 0, 35, 59, 60, 61, 100 };
		int[] regulars = { 0, 49, 50, 100 };
		int cells = 0;
		for (int n : new int[] { 22, 23, 30 }) {
			for (double cover : covers) {
				for (int capital : capitals) {
					for (int reg : regulars) {
						String expected = adrLandingTable(capital, reg, cover);
						for (String proposed : withNull(ENDINGS)) {
							ObjectNode parsed = parsed(Map.of("capital", capital, "regulars", reg, "cover", cover), proposed);
							QiriEndingCheck.Result r = QiriEndingCheck.check(engine(n - 1, Map.of()), parsed);
							String cell = "T" + n + " 底细 " + cover + " 摊本 " + capital + " 熟客 " + reg + " 提议 " + proposed;
							assertThat(r.skipped()).as(cell).isFalse();
							assertThat(r.verdict()).as(cell).isEqualTo(expected);
							assertThat(parsed.path("ending").path("id").asString()).as(cell).isEqualTo(expected);
							assertThat(parsed.path("ending").path("reached").asBoolean()).as(cell).isTrue();
							cells++;
						}
					}
				}
			}
		}
		assertThat(cells).isEqualTo(3 * 12 * 6 * 4 * 5);
	}

	/** 落点表里有名字的那几格,单独点名(读红用例名时一眼看到是哪一格)。 */
	@Test
	void namedCells() {
		assertThat(QiriEndingCheck.verdict(22, 70, 60, 0)).as("底细 0 × 摊本 ≥ 60").isEqualTo("taoist_summoned");
		assertThat(QiriEndingCheck.verdict(22, 40, 60, 0)).as("底细 0 × 摊本 < 60").isEqualTo("taoist_summoned");
		assertThat(QiriEndingCheck.verdict(22, 40, 60, 5)).as("底细 1–10 × 摊本 < 60:neutral 不降级").isEqualTo("open_as_usual");
		assertThat(QiriEndingCheck.verdict(22, 70, 60, 5)).as("底细 1–10 × 摊本 ≥ 60:降级").isEqualTo("taoist_summoned");
		assertThat(QiriEndingCheck.verdict(22, 70, 60, 10)).as("底细 10 × 摊本 ≥ 60:降级线含 10").isEqualTo("taoist_summoned");
		assertThat(QiriEndingCheck.verdict(22, 40, 60, 25)).as("底细 11–39 × 摊本 < 60").isEqualTo("open_as_usual");
		assertThat(QiriEndingCheck.verdict(22, 70, 60, 11)).as("底细 11 × 摊本 ≥ 60").isEqualTo("odd_idea_king");
		assertThat(QiriEndingCheck.verdict(22, 70, 60, 39)).as("底细 39 × 摊本 ≥ 60").isEqualTo("odd_idea_king");
		assertThat(QiriEndingCheck.verdict(22, 70, 60, 39.5)).as("底细 39.5 × 摊本 ≥ 60:39 与 40 之间不留空隙(订正一 决策 6)")
				.isEqualTo("odd_idea_king");
		assertThat(QiriEndingCheck.verdict(22, 70, 40, 39.5)).as("底细 39.5 × 摊本 ≥ 60 × 熟客 < 50").isEqualTo("odd_idea_king");
		assertThat(QiriEndingCheck.verdict(22, 59, 60, 50)).as("底细 ≥ 40 × 摊本 < 60").isEqualTo("open_as_usual");
		assertThat(QiriEndingCheck.verdict(22, 60, 50, 40)).as("底细 ≥ 40 × 摊本 ≥ 60 × 熟客 ≥ 50").isEqualTo("night_market_top");
		assertThat(QiriEndingCheck.verdict(22, 60, 49, 40)).as("底细 ≥ 40 × 摊本 ≥ 60 × 熟客 < 50").isEqualTo("open_as_usual");
	}

	// ② 注入表与核对逐格对拍 ─────────────────────────────────────────────

	private static final Map<String, String> TITLE_TO_ID = Map.of("夜集第一摊", QiriEndingCheck.NIGHT_MARKET_TOP,
			"城中怪点子王", QiriEndingCheck.ODD_IDEA_KING, "明日照常开张", QiriEndingCheck.OPEN_AS_USUAL,
			"请来了道士", QiriEndingCheck.TAOIST_SUMMONED);

	private record Row(String id, Predicate<int[]> when) {
	}

	/** 解析第 22 回合注入的判定表:每行「· 条件:结局」或「· 结局:条件」,条件用「、」「而」连接。int[] = {摊本, 熟客, 底细}。 */
	private static List<Row> parseInjectedTable() {
		String clock = TurnPromptBuilder.qiriTurnClock(22);
		assertThat(clock).contains(TurnPromptBuilder.QIRI_FINAL_TABLE);
		List<Row> rows = new ArrayList<>();
		for (String line : clock.split("\n")) {
			if (!line.startsWith("· ")) {
				continue;
			}
			String body = line.substring(2).replaceAll("[;。]$", "");
			String[] sides = body.split(":", 2);
			assertThat(sides).as("判定表行:" + line).hasSize(2);
			String id;
			String cond;
			if (TITLE_TO_ID.containsKey(sides[1].strip())) {
				id = TITLE_TO_ID.get(sides[1].strip());
				cond = sides[0];
			} else {
				assertThat(TITLE_TO_ID).as("判定表行没有结局名:" + line).containsKey(sides[0].strip());
				id = TITLE_TO_ID.get(sides[0].strip());
				cond = sides[1];
			}
			Predicate<int[]> all = v -> true;
			for (String atom : cond.split("、|而")) {
				all = all.and(atom(atom.strip(), line));
			}
			rows.add(new Row(id, all));
		}
		return rows;
	}

	private static final Pattern CMP = Pattern.compile("(摊本|熟客|底细) (≥|≤) (\\d+)");
	private static final Pattern RANGE = Pattern.compile("(摊本|熟客|底细)在 (\\d+)~(\\d+) 之间");

	private static Predicate<int[]> atom(String atom, String line) {
		if (atom.equals("以上都不成立")) {
			return v -> true;
		}
		if (atom.equals("底细归零")) {
			return v -> v[2] <= 0;
		}
		Matcher m = CMP.matcher(atom);
		if (m.matches()) {
			int i = axisIndex(m.group(1));
			int x = Integer.parseInt(m.group(3));
			return m.group(2).equals("≥") ? v -> v[i] >= x : v -> v[i] <= x;
		}
		m = RANGE.matcher(atom);
		if (m.matches()) {
			int i = axisIndex(m.group(1));
			int lo = Integer.parseInt(m.group(2));
			int hi = Integer.parseInt(m.group(3));
			return v -> v[i] >= lo && v[i] <= hi;
		}
		return fail("判定表里有解析不了的条件「" + atom + "」:" + line);
	}

	private static int axisIndex(String name) {
		return switch (name) {
			case "摊本" -> 0;
			case "熟客" -> 1;
			default -> 2;
		};
	}

	@Test
	void injectedTableMatchesTheCheckCellByCell() {
		List<Row> rows = parseInjectedTable();
		assertThat(rows).as("五行:归零 / 第一摊 / 降级 / 怪点子王 / 照常开张").hasSize(5);
		int cells = 0;
		for (int cover = 0; cover <= 100; cover++) {
			for (int capital : new int[] { 0, 30, 59, 60, 61, 100 }) {
				for (int reg : new int[] { 0, 49, 50, 100 }) {
					int[] v = { capital, reg, cover };
					String byTable = rows.stream().filter(r -> r.when().test(v)).findFirst().map(Row::id).orElse(null);
					assertThat(byTable).as("底细 " + cover + " 摊本 " + capital + " 熟客 " + reg + ":注入表 vs 核对")
							.isEqualTo(QiriEndingCheck.verdict(22, capital, reg, cover));
					cells++;
				}
			}
		}
		assertThat(cells).isEqualTo(101 * 6 * 4);
	}

	// ③ 第 22 回合之前一律不收 ───────────────────────────────────────────

	@Test
	void beforeTurn22EveryProposalIsDropped() {
		for (int n = 1; n <= 21; n++) {
			for (String proposed : ENDINGS) {
				// 数值满足夜集第一摊、也满足怪点子王的边上;仍然不收。
				for (int[] v : new int[][] { { 80, 60, 50 }, { 80, 10, 20 }, { 10, 0, 5 } }) {
					ObjectNode parsed = parsed(Map.of("capital", v[0], "regulars", v[1], "cover", v[2]), proposed);
					QiriEndingCheck.Result r = QiriEndingCheck.check(engine(n - 1, Map.of()), parsed);
					assertThat(r.verdict()).as("T" + n + " 提议 " + proposed).isNull();
					assertThat(parsed.get("ending").isNull()).as("T" + n + " 提议 " + proposed).isTrue();
					assertThat(r.changed()).isTrue();
				}
			}
		}
	}

	@Test
	void coverAtZeroIsTaoistOnAnyTurn() {
		for (int n : new int[] { 1, 5, 21, 22, 30 }) {
			ObjectNode parsed = parsed(Map.of("cover", 0), null);
			assertThat(QiriEndingCheck.check(engine(n - 1, Map.of()), parsed).verdict()).as("T" + n)
					.isEqualTo("taoist_summoned");
			// 负数与溢出先 clamp。
			assertThat(QiriEndingCheck.verdict(n, 100, 100, 0)).isEqualTo("taoist_summoned");
		}
	}

	// ④ 落账路径 ────────────────────────────────────────────────────────

	@Test
	void landingRewritesAndRecordsTheIssue() {
		Engine e = engine(21, Map.of("capital", 50, "regulars", 55, "cover", 45));
		land(e, Map.of("capital", 70, "cover", 30), "night_market_top");
		assertThat(e.status()).isEqualTo("ended");
		assertThat(reachedOf(e)).isEqualTo("odd_idea_king");
		assertThat(e.issues()).contains("T22 结局核对改判 night_market_top → odd_idea_king");
	}

	@Test
	void landingAtCover5WithCapital70IsTaoistWrittenByTheCheck() {
		Engine e = engine(21, Map.of("capital", 50, "regulars", 20, "cover", 12));
		land(e, Map.of("capital", 70, "cover", 5), "odd_idea_king");
		assertThat(reachedOf(e)).isEqualTo("taoist_summoned");
		assertThat(e.issues()).contains("T22 结局核对改判 odd_idea_king → taoist_summoned");
	}

	@Test
	void landingBeforeTurn22DropsTheProposalAndGameGoesOn() {
		Engine e = engine(14, Map.of("capital", 80, "regulars", 60, "cover", 50));
		land(e, Map.of(), "night_market_top");
		assertThat(e.status()).isEqualTo("ongoing");
		assertThat(reachedOf(e)).isNull();
		assertThat(e.issues()).contains("T15 结局核对改判 night_market_top → 无(本回合继续)");
	}

	@Test
	void ordinaryTurnWithoutProposalRecordsNothing() {
		Engine e = engine(6, Map.of());
		land(e, Map.of("capital", 40), null);
		assertThat(e.status()).isEqualTo("ongoing");
		assertThat(e.issues()).noneMatch(s -> s.contains("结局核对"));
	}

	@Test
	void numbersAreTheSettledOnes_missingKeyKeepsCurrentAndOverflowIsClamped() {
		// 摊本没给 → 取当前 65;熟客 140 → clamp 100;底细 45。
		ObjectNode parsed = parsed(Map.of("regulars", 140, "cover", 45), null);
		QiriEndingCheck.check(engine(21, Map.of("capital", 65, "regulars", 10, "cover", 80)), parsed);
		assertThat(parsed.path("ending").path("id").asString()).isEqualTo("night_market_top");
		ObjectNode below = parsed(Map.of("cover", -20), null);
		QiriEndingCheck.check(engine(3, Map.of()), below);
		assertThat(below.path("ending").path("id").asString()).isEqualTo("taoist_summoned");
	}

	@Test
	void verdictMissingFromThisWorldsEndingPoolLeavesParsedUntouched() {
		Engine e = engine(21, Map.of(), List.of("night_market_top", "open_as_usual", "taoist_summoned"));
		ObjectNode parsed = parsed(Map.of("capital", 70, "cover", 30), "night_market_top");
		QiriEndingCheck.Result r = QiriEndingCheck.check(e, parsed);
		assertThat(r.skipped()).isTrue();
		assertThat(r.verdict()).isEqualTo("odd_idea_king");
		assertThat(parsed.path("ending").path("id").asString()).as("不改写").isEqualTo("night_market_top");
	}

	@Test
	void checkReturnsNullWithoutTheThreeAxesAndOtherWorldsAreUntouched() {
		List<AttributeAxis> rc = registry.meta("rules_creepy").attributes();
		Engine other = QiriCoverDropTest.engine(mapper, registry, "rules_creepy", 21, Map.of());
		ObjectNode parsed = parsed(Map.of(), "open_as_usual");
		assertThat(QiriEndingCheck.check(other, parsed)).isNull();
		assertThat(rc).isNotEmpty();
		// 走落账:规则怪谈局的提议原样交给引擎(结局池里没有 → 引擎不收),不记核对 issue。
		service.landSettled(new GameSession("s", other, actions()), parsed(Map.of(), "x_end"), "A", null, leak -> {
		});
		assertThat(other.issues()).noneMatch(s -> s.contains("结局核对"));
	}

	// ── 夹具 ──────────────────────────────────────────────────────────

	private static List<String> withNull(List<String> ids) {
		List<String> out = new ArrayList<>(ids);
		out.add(null);
		return out;
	}

	private void land(Engine e, Map<String, Integer> upd, String proposed) {
		service.landSettled(new GameSession("save-qiri", e, actions()), parsed(upd, proposed), "A", null, leak -> {
		});
	}

	private Engine engine(int turn, Map<String, Integer> attrs) {
		return engine(turn, attrs, ENDINGS);
	}

	private Engine engine(int turn, Map<String, Integer> attrs, List<String> endingIds) {
		Map<String, Integer> a = new LinkedHashMap<>(Map.of("capital", 50, "regulars", 50, "cover", 50));
		a.putAll(attrs);
		Engine base = QiriCoverDropTest.engine(mapper, registry, "time_travel", turn, a);
		ObjectNode p = base.toPersistedState();
		ArrayNode endings = ((ObjectNode) p.path("world")).putArray("endings");
		Map<String, String[]> meta = Map.of(
				"night_market_top", new String[] { "夜集第一摊", "success", "百味夜集当晚,摊本 ≥ 60、熟客 ≥ 50、底细 ≥ 40" },
				"odd_idea_king", new String[] { "城中怪点子王", "success", "百味夜集当晚,摊本 ≥ 60、底细在 11~39 之间" },
				"open_as_usual", new String[] { "明日照常开张", "neutral", "百味夜集当晚,以上都不成立" },
				"taoist_summoned", new String[] { "请来了道士", "failure", "底细降到 0;或夜集时判出成功但底细 ≤ 10" });
		for (String id : endingIds) {
			String[] m = meta.get(id);
			endings.addObject().put("id", id).put("title", m[0]).put("outcome", m[1]).put("condition", m[2])
					.put("reached", false);
		}
		return QiriCoverDropTest.restore(mapper, registry, p);
	}

	private ObjectNode parsed(Map<String, ? extends Number> upd, String proposed) {
		ObjectNode parsed = mapper.createObjectNode();
		parsed.put("narrative", "夜集的灯一串串亮起来。");
		ObjectNode su = parsed.putObject("stateUpdate");
		upd.forEach((k, v) -> su.put(k, v.doubleValue()));
		su.put("timeline", "t");
		parsed.putArray("triggeredRuleIds");
		parsed.putArray("discoveredRuleIds");
		parsed.set("availableActions", actions());
		if (proposed == null) {
			parsed.putNull("ending");
		} else {
			parsed.putObject("ending").put("reached", true).put("id", proposed);
		}
		return parsed;
	}

	private ArrayNode actions() {
		ArrayNode a = mapper.createArrayNode();
		a.addObject().put("id", "A").put("text", "吆喝一声").put("hint", "街坊会看你");
		a.addObject().put("id", "B").put("text", "先把账算清").put("hint", "别写错字");
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
