package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.LifetimeFamily;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.BoxScene.Situation;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-028 §已决 L(F-031 / F-034 修复):离开回合用屋外片段与「刚出门」槽位、屋外共同边界、
 * 被留下余波逐拍时钟、纸箱时间锚点(开场与新局 T1–10)。
 *
 * <p>下列常量是 Felix 2026-10-03 原文的<b>独立副本</b>(不从产品代码取),与产品常量、ADR 已决 L 三方对拍。
 */
class BoxSceneDecisionLTest {

	static final String EXCEPTION =
			"【仅被留下余波适用的时钟例外】这三个回合发生在同一天，相邻两拍相隔数小时。本回合只推进到当前这一拍，不跨到第二天。";
	static final String B1 = "【本回合第 1/3 拍】光落在地板上。不得提前写光移到墙上或天黑。";
	static final String B2 = "【本回合第 2/3 拍】光挪到了墙上，叫声没有招来任何人。不得提前写天黑。";
	static final String B3 = "【本回合第 3/3 拍】天黑了，门缝里透进来的风有外面的味道。";
	static final String REMOVED_SENTENCE = "三个回合发生在同一天：光先落在地板上，随后移到墙上，最后天黑。";
	static final String SAME_DAY_BAN = "【绝不允许】两个回合停在同一天、同一顿饭、同一次谈话里把一件事说完;";
	static final String THRESHOLD =
			"楼道、楼梯间和门前都属于 `OUTSIDE`。它可以走到旧家的门前，但不能进入旧屋；不得写门为它打开，也不得制造 `OUTSIDE → EMPTY_HOME`。";
	static final String WORLD_GEN_ANCHOR = "`openingNarrative` 发生在第 1 回合之前。旧屋里仍有人照常生活，纸箱尚未出现，家具尚未搬空；"
			+ "不得写搬运箱子的脚步或来接箱子的车，也不得预告它会被带走或留下。";
	static final String BOX_NOT_YET = "【纸箱局面尚未开始】这几回合仍在旧屋里经历日常、学习声音和行动得到的回应。"
			+ "纸箱、家具搬空、搬运箱子的脚步及来接箱子的车，从第 11 回合的局面征兆开始出现；本回合不得提前写出或预告结算结果。";
	/** (9) 其余内容里的一句(用来认出 (9) 那一块)。 */
	static final String NINE_HEADING = "**（9）【`OUTSIDE` 末段 · 楼道口边界】**";

	private static final Path ADR = Path.of("../docs/adr/ADR-028-box-scene-changeable-left-behind.md");

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder prompts = new TurnPromptBuilder(registry);

	// ── 夹具 ────────────────────────────────────────────────────────────

	private ObjectNode world() {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4").put("mode", "single");
		world.putArray("archetypes").add("animal_life");
		world.putObject("world").put("title", "屋里的灯").put("background", "…")
				.put("dangerLevel", "low").put("tone", "克制");
		world.putObject("character").putObject("attributes")
				.put("body", 80).put("warmth", 60).put("ground", 50).put("close", 50);
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "hit").put("title", "撞上")
				.put("condition", "【身子】归零").put("outcome", "failure").put("reached", false);
		return world;
	}

	private GameSession sessionAt(int turn, BoxSceneState st) {
		Engine engine = new Engine(world(), mapper);
		for (int i = 0; i < turn; i++) {
			engine.applyNoOp("屋里的日子", "A");
		}
		ArrayNode acts = mapper.createArrayNode();
		acts.addObject().put("id", "A").put("text", "趴着");
		GameSession s = new GameSession("save-l", engine, acts);
		s.setBoxScene(st);
		return s;
	}

	private static String ok() {
		return "你把鼻子贴近地面。" + SentinelSplitter.SENTINEL
				+ "{\"stateUpdate\":{\"body\":80,\"warmth\":60,\"ground\":50,\"close\":50,\"timeline\":\"日子\"},"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"a\",\"hint\":\"h\"},"
				+ "{\"id\":\"B\",\"text\":\"b\",\"hint\":\"h\"},{\"id\":\"C\",\"text\":\"c\",\"hint\":\"h\"}],"
				+ "\"ending\":null}";
	}

	/** 从 {@code s} 的当前回合起逐个选择推进,返回 {生成的回合号 → prompt}。 */
	private Map<Integer, String> run(GameSession s, String... choices) {
		Map<Integer, String> out = new LinkedHashMap<>();
		for (String c : choices) {
			int next = s.engine().turn() + 1;
			BoxSceneWiringTest.ScriptedLlm llm = new BoxSceneWiringTest.ScriptedLlm();
			llm.script(ok());
			new EventLoopService(llm, prompts, mapper).execute(s, c, new BoxSceneWiringTest.Sink());
			out.put(next, llm.prompts.get(0));
		}
		return out;
	}

	private static int count(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			n++;
		}
		return n;
	}

	/** R3a 到第 17 回合(B3)为止的 11–17 回合 prompt;再选 {@code at17} 生成第 18 回合。 */
	private Map<Integer, String> r3a(GameSession s, String at17) {
		return run(s, "A", "C", "C", "C", "A", "A", "A", at17);
	}

	// ── 原文三方对拍 ─────────────────────────────────────────────────────

	@Test
	void felixTextsAreVerbatimInProductAndInDecisionL() throws Exception {
		assertThat(BoxSceneTables.LEFT_CLOCK_EXCEPTION).isEqualTo(EXCEPTION);
		assertThat(BoxSceneTables.LEFT_BEAT_CLOCK_LINES).containsExactlyInAnyOrderEntriesOf(Map.of("B1", B1, "B2", B2, "B3", B3));
		assertThat(BoxSceneTables.LEFT_SPAN_NOTE_REMOVED).isEqualTo(REMOVED_SENTENCE);
		assertThat(BoxSceneTables.OUTSIDE_THRESHOLD_LINE).isEqualTo(THRESHOLD);
		assertThat(LifeStageTables.BOX_NOT_YET_STARTED).isEqualTo(BOX_NOT_YET);
		String adr = Files.readString(ADR);
		int l = adr.indexOf("### 已决 L");
		assertThat(l).as("ADR-028 里找不到「已决 L」").isPositive();
		String decision = adr.substring(l, adr.indexOf("\n### ", l + 1) < 0 ? adr.length() : adr.indexOf("\n### ", l + 1));
		for (String t : List.of(EXCEPTION, B1, B2, B3, REMOVED_SENTENCE, THRESHOLD, WORLD_GEN_ANCHOR, BOX_NOT_YET)) {
			assertThat(decision).as("已决 L 应照录:%s", t).contains(t);
		}
	}

	// ── 离开回合 / 屋外共同边界 ───────────────────────────────────────────

	@Test
	void thresholdLineIsAPerTurnOutsideBoundary_onceInTheLeaveTurnAndEveryOutsideTurn() {
		String frag = BoxSceneTables.situationFragment(Situation.OUTSIDE);
		assertThat(count(frag, THRESHOLD)).isEqualTo(1);
		assertThat(frag.indexOf(THRESHOLD)).as("在共同边界,不在 (9) 里").isLessThan(frag.indexOf(NINE_HEADING));
		assertThat(frag.indexOf(THRESHOLD)).isLessThan(frag.indexOf("四条核心对应在当前处境下的回应"));
		for (Situation other : List.of(Situation.NEW_HOME, Situation.EMPTY_HOME)) {
			assertThat(BoxSceneTables.situationFragment(other)).doesNotContain(THRESHOLD);
		}

		GameSession s = sessionAt(10, BoxSceneState.fresh());
		Map<Integer, String> p = r3a(s, "C");
		p.putAll(run(s, "A", "A", "A"));
		for (int turn = 11; turn <= 17; turn++) {
			assertThat(p.get(turn)).as("T" + turn).doesNotContain(THRESHOLD);
		}
		for (int turn = 18; turn <= 21; turn++) {
			assertThat(count(p.get(turn), THRESHOLD)).as("T" + turn).isEqualTo(1);
		}
		// (9) 其余内容原样留在 (9) 里,并仍自带「只在末段生效」的条件句(片段其余文字不动)。
		int nine = frag.indexOf(NINE_HEADING);
		assertThat(frag.substring(nine)).contains("这一条只在权威处境为 `OUTSIDE` 且已经进入末段时生效。")
				.contains("最后一个可玩回合的场景必须落在某个楼道口。");
	}

	@Test
	void nonLeaveTurnsInEmptyHomeKeepTheEmptyHomeFragment() {
		GameSession s = sessionAt(10, BoxSceneState.fresh());
		r3a(s, "A"); // 到 T18,一直留在旧屋
		assertThat(s.boxScene().situation).isEqualTo(Situation.EMPTY_HOME);
		Map<Integer, String> stay = run(s, "A");
		assertThat(stay.values().iterator().next()).contains("【处境片段 · 当前处境:空下来的旧屋")
				.doesNotContain("【处境片段 · 当前处境:屋外");
	}

	// ── 被留下余波逐拍 ───────────────────────────────────────────────────

	@Test
	void leftAftermathInjectsOnlyTheCurrentBeat_andTheClockContractCarriesTheExceptionInsteadOfTheBan() {
		Map<Integer, String> p = r3a(sessionAt(10, BoxSceneState.fresh()), "B");
		Map<Integer, String> own = Map.of(15, B1, 16, B2, 17, B3);
		for (Map.Entry<Integer, String> e : own.entrySet()) {
			String prompt = p.get(e.getKey());
			String why = "T" + e.getKey();
			assertThat(count(prompt, e.getValue())).as(why).isEqualTo(1);
			for (String other : List.of(B1, B2, B3)) {
				if (!other.equals(e.getValue())) {
					assertThat(prompt).as(why + " 不含别拍").doesNotContain(other);
				}
			}
			assertThat(prompt).as(why).contains(EXCEPTION).doesNotContain(SAME_DAY_BAN)
					.doesNotContain(REMOVED_SENTENCE);
		}
		for (int turn : List.of(11, 14, 18)) {
			assertThat(p.get(turn)).as("T" + turn).contains(SAME_DAY_BAN).doesNotContain(EXCEPTION)
					.doesNotContain(B1).doesNotContain(B2).doesNotContain(B3);
		}
	}

	@Test
	void takenAftermathAndR1FillKeepTheOriginalClockContract() {
		Map<Integer, String> r1 = run(sessionAt(10, BoxSceneState.fresh()), "A", "A", "A", "A", "A", "A", "A");
		Map<Integer, String> r2 = run(sessionAt(10, BoxSceneState.fresh()), "A", "A", "A", "B", "A", "A", "A");
		for (Map<Integer, String> p : List.of(r1, r2)) {
			for (Map.Entry<Integer, String> e : p.entrySet()) {
				assertThat(e.getValue()).as("T" + e.getKey()).contains(SAME_DAY_BAN).doesNotContain(EXCEPTION)
						.doesNotContain(B1).doesNotContain(B2).doesNotContain(B3);
			}
		}
	}

	@Test
	void theExceptionRefusesToRenderSilentlyIfTheFamilySentenceIsGone() {
		String contract = LifetimeFamily.clockContract(16, "它", "x", "y", "z", 45, 48, "老死");
		assertThat(contract).contains(SAME_DAY_BAN); // 族层今天的原句(变了 → 这里先红)
		assertThat(BoxSceneTables.withClockException(contract, EXCEPTION)).doesNotContain(SAME_DAY_BAN)
				.contains(EXCEPTION);
		assertThat(BoxSceneTables.withClockException(contract, null)).isEqualTo(contract);
		assertThatThrownBy(() -> BoxSceneTables.withClockException(contract.replace(SAME_DAY_BAN, ""), EXCEPTION))
				.isInstanceOf(IllegalStateException.class);
	}

	// ── 纸箱时间锚点 ─────────────────────────────────────────────────────

	@Test
	void turnSideAnchorOnlyInNewGameTurns1To10() {
		for (int turn = 1; turn <= 10; turn++) {
			String p = prompts.buildTurnPrompt(AnimalLifeLegacyGoldenTest.engine(mapper, turn - 1, 40), "A", "趴着",
					"", "", false);
			assertThat(count(p, BOX_NOT_YET)).as("新局 T" + turn).isEqualTo(1);
			String legacy = prompts.buildTurnPrompt(AnimalLifeLegacyGoldenTest.engine(mapper, turn - 1, 40), "A",
					"趴着", "", "", true);
			assertThat(legacy).as("旧局 T" + turn).doesNotContain(BOX_NOT_YET);
		}
		Map<Integer, String> p = r3a(sessionAt(10, BoxSceneState.fresh()), "C");
		p.forEach((turn, prompt) -> assertThat(prompt).as("T" + turn).doesNotContain(BOX_NOT_YET));
		String t30 = prompts.buildTurnPrompt(AnimalLifeLegacyGoldenTest.engine(mapper, 29, 40), "A", "趴着", "", "",
				false);
		assertThat(t30).doesNotContain(BOX_NOT_YET);
	}

	@Test
	void worldGenAnchorOnlyInAnimalLife() {
		WorldGenPromptBuilder wg = new WorldGenPromptBuilder(registry);
		assertThat(count(wg.buildWorldPrompt("animal_life"), WORLD_GEN_ANCHOR)).isEqualTo(1);
		for (String other : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim")) {
			assertThat(wg.buildWorldPrompt(other)).as(other).doesNotContain(WORLD_GEN_ANCHOR)
					.doesNotContain("纸箱局面尚未开始");
			assertThat(prompts.buildTurnPrompt(engineFor(other), "A", "x")).as(other)
					.doesNotContain(BOX_NOT_YET).doesNotContain(EXCEPTION).doesNotContain(THRESHOLD);
		}
	}

	private Engine engineFor(String archetype) {
		ObjectNode w = world();
		((ArrayNode) w.get("archetypes")).removeAll().add(archetype);
		ObjectNode attrs = mapper.createObjectNode();
		registry.meta(archetype).attributes().forEach(a -> attrs.put(a.key(), 50));
		((ObjectNode) w.get("character")).set("attributes", attrs);
		return new Engine(w, mapper);
	}
}
