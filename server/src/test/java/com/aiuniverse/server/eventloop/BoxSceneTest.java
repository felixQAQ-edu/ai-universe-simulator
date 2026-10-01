package com.aiuniverse.server.eventloop;

import static com.aiuniverse.server.eventloop.BoxSceneTables.ANIMAL_LIFE_BOX;
import static com.aiuniverse.server.eventloop.BoxSceneTables.LEAVE_HOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.eventloop.BoxScene.Beat;
import com.aiuniverse.server.eventloop.BoxScene.Category;
import com.aiuniverse.server.eventloop.BoxScene.Option;
import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxScene.Pick;
import com.aiuniverse.server.eventloop.BoxScene.Result;
import com.aiuniverse.server.eventloop.BoxScene.Situation;

/**
 * ADR-028 刀 1:「纸箱」局面数据表 + 纯函数的确定性测试(不依赖模型、不接线)。
 */
class BoxSceneTest {

	private static final BoxScene.Table T = ANIMAL_LIFE_BOX;
	/** ADR-028 §已决 J 第 1 条(Felix 2026-10-01)逐字:R1 记忆事实。 */
	private static final String R1_MEMORY_FACT = "门外响着车声时，它自己跳进了那个正被人抱走的箱子。";

	/** 一个意图序列从 g=0 跑完,返回 (结果, 结算阶段)。 */
	private record Run(Result last, int settledAtStage, int g) {
	}

	private static Run run(String... intents) {
		int g = 0;
		for (int i = 0; i < intents.length; i++) {
			int stage = i + 1;
			Result r = BoxScene.settle(T, stage, g, intents[i]);
			if (r.settled()) {
				return new Run(r, stage, g);
			}
			g = r.newG();
		}
		return new Run(null, -1, g);
	}

	// ── 序列 → 结果与结算回合 ─────────────────────────────────────────────

	@Test
	void r1SettlesAtStage3() {
		Run r = run("FOLLOW", "GUARD_BOWL", "INTO_BOX");
		assertThat(r.last().path()).isEqualTo(Path.R1);
		assertThat(r.settledAtStage()).isEqualTo(3);
		assertThat(T.firstTurn() + r.settledAtStage() - 1).isEqualTo(13);
	}

	@Test
	void r2SettlesAtStage4() {
		Run r = run("FOLLOW", "UNDER_BED", "TO_DOOR", "TO_HAND");
		assertThat(r.last().path()).isEqualTo(Path.R2);
		assertThat(r.settledAtStage()).isEqualTo(4);
	}

	@Test
	void r3aSettlesAtStage4() {
		Run r = run("OLD_SPOT", "UNDER_BED", "UNDER_BED", "CHASE_CAR");
		assertThat(r.last().path()).isEqualTo(Path.R3A);
		assertThat(r.settledAtStage()).isEqualTo(4);
	}

	@Test
	void r3bAndR3cSettleAtStage4InBothBranches() {
		assertThat(run("OLD_SPOT", "UNDER_BED", "UNDER_BED", "STAY").last().path()).isEqualTo(Path.R3B);
		assertThat(run("OLD_SPOT", "UNDER_BED", "UNDER_BED", "HIDE").last().path()).isEqualTo(Path.R3C);
		assertThat(run("FOLLOW", "FOLLOW", "TO_DOOR", "STAY").last().path()).isEqualTo(Path.R3B);
		assertThat(run("FOLLOW", "FOLLOW", "TO_DOOR", "HIDE").last().path()).isEqualTo(Path.R3C);
		assertThat(run("FOLLOW", "FOLLOW", "TO_DOOR", "HIDE").settledAtStage()).isEqualTo(4);
	}

	/** 「行动之前的 g」:阶段 3 INTO_BOX 在 g=1 时 +1、不结算(行动后 g=2 也不结算)。 */
	@Test
	void intoBoxUsesGBeforeAction() {
		Result r = BoxScene.settle(T, 3, 1, "INTO_BOX");
		assertThat(r.settled()).isFalse();
		assertThat(r.newG()).isEqualTo(2);
		assertThat(r.feedback()).isEqualTo("有人把它从箱子里抱出来,放在了门边");
		assertThat(BoxScene.settle(T, 3, 0, "INTO_BOX").settled()).isFalse();
	}

	/** g 下限 0。 */
	@Test
	void gHasFloorZero() {
		assertThat(BoxScene.settle(T, 2, 0, "UNDER_BED").newG()).isZero();
		assertThat(BoxScene.settle(T, 3, 0, "UNDER_BED").newG()).isZero();
	}

	@Test
	void r1MemoryFactIsVerbatimFromDecisionJ_andTheAdrTableCarriesTheSameSentence() throws Exception {
		assertThat(T.memoryFacts().get(Path.R1)).isEqualTo(R1_MEMORY_FACT);
		String adr = Files.readString(java.nio.file.Path.of("../docs/adr/ADR-028-box-scene-changeable-left-behind.md"));
		assertThat(adr).contains("| R1 | 被带走·箱子 | " + R1_MEMORY_FACT + " |");
		// 另外四条一字不动
		assertThat(T.memoryFacts().get(Path.R2)).isEqualTo("车发动时,它跑向了那只手");
		assertThat(T.memoryFacts().get(Path.R3A)).isEqualTo("它追到了门口,但没有跟上");
		assertThat(T.memoryFacts().get(Path.R3B)).isEqualTo("车开走时,它待在原地");
		assertThat(T.memoryFacts().get(Path.R3C)).isEqualTo("车开走时,它躲着");
	}

	@Test
	void settledResultCarriesPathMemoryFactAndKeepsG() {
		Result r = BoxScene.settle(T, 3, 2, "INTO_BOX");
		assertThat(r.settled()).isTrue();
		assertThat(r.newG()).isEqualTo(2);
		assertThat(r.feedback()).isEqualTo(R1_MEMORY_FACT);
	}

	@Test
	void unofferedIntentThrows() {
		assertThatThrownBy(() -> BoxScene.settle(T, 1, 0, "INTO_BOX")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BoxScene.settle(T, 4, 0, "TO_HAND")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BoxScene.settle(T, 4, 1, "CHASE_CAR")).isInstanceOf(IllegalArgumentException.class);
	}

	// ── 阶段 4 按行动前 g 分叉 ────────────────────────────────────────────

	@Test
	void stage4BranchesOnGBefore() {
		Beat high = BoxScene.present(T, 4, 1);
		Beat low = BoxScene.present(T, 4, 0);
		assertThat(BoxScene.present(T, 4, 3)).isEqualTo(high);
		assertThat(high.omen()).isEqualTo("门口那只手伸了回来,孩子在车里叫它");
		assertThat(low.omen()).isEqualTo("车门已经关上,引擎声盖过了屋里的声音");
		assertThat(high.slot("A").intent()).isEqualTo("TO_HAND");
		assertThat(low.slot("A").intent()).isEqualTo("CHASE_CAR");
		assertThat(high.slot("A").template()).isEqualTo("跑向伸回来的那只手");
		assertThat(low.slot("A").template()).isEqualTo("追向已经开动的车");
	}

	/** g=0 进阶段 4 时被带走不可达,且以不同征兆呈现。 */
	@Test
	void takenUnreachableAtStage4WhenGZero() {
		Beat low = BoxScene.present(T, 4, 0);
		for (Option o : low.options()) {
			assertThat(o.settlePath().category).isEqualTo(Category.LEFT);
		}
		assertThat(low.omen()).isNotEqualTo(BoxScene.present(T, 4, 1).omen());
		assertThat(BoxScene.present(T, 4, 1).options()).anyMatch(o -> o.settlePath().category == Category.TAKEN);
	}

	// ── 公平性 / 可达性 ──────────────────────────────────────────────────

	@Test
	void eachAccumulationStageOffersRaisingAndNonRaising() {
		for (int s = 1; s <= 3; s++) {
			List<Option> opts = BoxScene.present(T, s, 0).options();
			assertThat(opts).as("阶段 %d 有提高 g 的意图", s).anyMatch(o -> o.gDelta() > 0);
			assertThat(opts).as("阶段 %d 有不提高 g 的意图", s).anyMatch(o -> o.gDelta() <= 0);
		}
	}

	@Test
	void bothTakenAndLeftReachableAcrossAllSequences() {
		Set<Path> reached = new HashSet<>();
		explore(1, 0, reached);
		assertThat(reached).containsExactlyInAnyOrder(Path.values());
	}

	private static void explore(int stage, int g, Set<Path> reached) {
		for (Option o : BoxScene.present(T, stage, g).options()) {
			Result r = BoxScene.settle(T, stage, g, o.intent());
			if (r.settled()) {
				reached.add(r.path());
			} else {
				explore(stage + 1, r.newG(), reached);
			}
		}
	}

	// ── 记忆事实 ─────────────────────────────────────────────────────────

	@Test
	void eachPathHasItsOwnMemoryFactAndLeftShareCommonFact() {
		assertThat(new HashSet<>(T.memoryFacts().values())).hasSize(Path.values().length);
		assertThat(T.leftCommonFact()).isEqualTo("车开走时,它没有跟上");
		for (Path p : Path.values()) {
			List<String> facts = BoxScene.memoryFacts(T, p);
			assertThat(facts.get(0)).isEqualTo(T.memoryFacts().get(p));
			if (p.category == Category.LEFT) {
				assertThat(facts).containsExactly(T.memoryFacts().get(p), T.leftCommonFact());
			} else {
				assertThat(facts).containsExactly(T.memoryFacts().get(p));
			}
		}
	}

	// ── 余波 / LEAVE_HOME / 刚出门 ─────────────────────────────────────────

	@Test
	void leaveHomeFirstAppearsInB3SlotC() {
		List<Beat> left = BoxScene.aftermath(T, Path.R3A);
		assertThat(left).extracting(Beat::id).containsExactly("B1", "B2", "B3");
		assertThat(left.get(0).byIntent(LEAVE_HOME)).isEmpty();
		assertThat(left.get(1).byIntent(LEAVE_HOME)).isEmpty();
		assertThat(left.get(2).slot("C").intent()).isEqualTo(LEAVE_HOME);
		assertThat(left.get(2).slot("C").template()).isEqualTo("从门缝钻出去");
		for (Beat b : BoxScene.aftermath(T, Path.R1)) {
			assertThat(b.byIntent(LEAVE_HOME)).isEmpty();
		}
		assertThat(T.r1Turn17Fill().byIntent(LEAVE_HOME)).isEmpty();
	}

	@Test
	void justOutsideTemplateHasNoLeaveHome() {
		assertThat(T.justOutside()).noneMatch(o -> o.intent().equals(LEAVE_HOME));
		assertThat(T.justOutside()).noneMatch(o -> o.template().contains("从门缝钻出去"));
		assertThat(T.leaveFeedback()).isEqualTo("它从门缝挤了出去。楼道的地面又冷又硬，那扇门留在了身后。");
	}

	@Test
	void aftermathIsThreeBeatsPerCategory() {
		assertThat(BoxScene.aftermath(T, Path.R1)).extracting(Beat::id).containsExactly("A1", "A2", "A3");
		assertThat(BoxScene.aftermath(T, Path.R2)).isEqualTo(BoxScene.aftermath(T, Path.R1));
	}

	// ── 处境 ─────────────────────────────────────────────────────────────

	@Test
	void leftPathsGoToEmptyHomeNotOutside() {
		assertThat(BoxScene.situationAfter(Path.R3A)).isEqualTo(Situation.EMPTY_HOME);
		assertThat(BoxScene.situationAfter(Path.R3B)).isEqualTo(Situation.EMPTY_HOME);
		assertThat(BoxScene.situationAfter(Path.R3C)).isEqualTo(Situation.EMPTY_HOME);
	}

	@Test
	void takenPathsGoToNewHome() {
		assertThat(BoxScene.situationAfter(Path.R1)).isEqualTo(Situation.NEW_HOME);
		assertThat(BoxScene.situationAfter(Path.R2)).isEqualTo(Situation.NEW_HOME);
	}

	@Test
	void onlyEmptyHomeLeaveHomeLandedTransitionsToOutside() {
		assertThat(BoxScene.transition(T, Situation.EMPTY_HOME, LEAVE_HOME, true)).isEqualTo(Situation.OUTSIDE);
		assertThat(BoxScene.transition(T, Situation.EMPTY_HOME, LEAVE_HOME, false)).isEqualTo(Situation.EMPTY_HOME);
		assertThat(BoxScene.transition(T, Situation.EMPTY_HOME, "RETURN_UNDER_BED", true)).isEqualTo(Situation.EMPTY_HOME);
	}

	@Test
	void newHomeAndOutsideHaveNoTransitionOut() {
		List<String> intents = new ArrayList<>(allIntents());
		intents.add(LEAVE_HOME);
		for (Situation s : List.of(Situation.NEW_HOME, Situation.OUTSIDE)) {
			for (String i : intents) {
				assertThat(BoxScene.transition(T, s, i, true)).isEqualTo(s);
			}
		}
	}

	// ── 轮换(§已决 F,合成池)─────────────────────────────────────────────

	private static final List<String> P6 = List.of("P0", "P1", "P2", "P3", "P4", "P5");
	private static final List<String> Q5 = List.of("Q0", "Q1", "Q2", "Q3", "Q4");

	@Test
	void newHomeRotationIsDeterministicAndShifts() {
		assertThat(BoxScene.rotateNewHome(P6, 20, 18, 2)).isEqualTo(BoxScene.rotateNewHome(P6, 20, 18, 2));
		assertThat(BoxScene.rotateNewHome(P6, 18, 18, 2)).containsExactly("P2", "P3", "P4");
		assertThat(BoxScene.rotateNewHome(P6, 22, 18, 0)).containsExactly("P4", "P5", "P0");
		for (int t = 18; t < 21; t++) {
			List<String> a = BoxScene.rotateNewHome(P6, t, 18, 0);
			List<String> b = BoxScene.rotateNewHome(P6, t + 1, 18, 0);
			for (int k = 0; k < 3; k++) {
				assertThat(a.get(k)).isNotEqualTo(b.get(k));
			}
		}
	}

	@Test
	void emptyHomeRotationKeepsLeaveHomeInC() {
		IntStream.range(17, 42).forEach(t -> {
			List<String> r = BoxScene.rotateEmptyHome(Q5, LEAVE_HOME, t, 17, 1);
			assertThat(r.get(2)).isEqualTo(LEAVE_HOME);
			assertThat(r.subList(0, 2)).doesNotContain(LEAVE_HOME);
		});
		assertThat(BoxScene.rotateEmptyHome(Q5, LEAVE_HOME, 17, 17, 0)).containsExactly("Q0", "Q1", LEAVE_HOME);
		assertThat(BoxScene.rotateEmptyHome(Q5, LEAVE_HOME, 18, 17, 0)).containsExactly("Q1", "Q2", LEAVE_HOME);
		assertThatThrownBy(() -> BoxScene.rotateEmptyHome(List.of("Q0", LEAVE_HOME, "Q2"), LEAVE_HOME, 17, 17, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ── 习惯句(§已决 G,合成意图)────────────────────────────────────────

	private static List<Pick> picks(Object... turnIntent) {
		List<Pick> l = new ArrayList<>();
		for (int i = 0; i < turnIntent.length; i += 2) {
			l.add(new Pick((Integer) turnIntent[i], (String) turnIntent[i + 1]));
		}
		return l;
	}

	@Test
	void habitEmptyBelowThreeEntries() {
		assertThat(BoxScene.habit(picks(28, "X", 29, "X"), 30)).isEmpty();
	}

	@Test
	void habitEmptyWhenMaxCountIsOne() {
		assertThat(BoxScene.habit(picks(27, "X", 28, "Y", 29, "Z"), 30)).isEmpty();
	}

	@Test
	void habitTieTakesMostRecentlyPicked() {
		assertThat(BoxScene.habit(picks(25, "X", 26, "Y", 27, "X", 28, "Y"), 30)).isEqualTo(Optional.of("Y"));
		assertThat(BoxScene.habit(picks(25, "Y", 26, "X", 27, "Y", 28, "X"), 30)).isEqualTo(Optional.of("X"));
	}

	@Test
	void habitIgnoresEntriesOutsideWindow() {
		// t=30:窗口 22 ≤ 回合 < 30;21 与 30 都不计
		assertThat(BoxScene.habit(picks(21, "X", 21, "X", 25, "Y", 26, "Z", 30, "X"), 30)).isEmpty();
		assertThat(BoxScene.habit(picks(22, "X", 25, "X", 26, "Z"), 30)).isEqualTo(Optional.of("X"));
	}

	@Test
	void recordKeepsEightAndOnlyCurrentPool() {
		Set<String> pool = Set.of("X", "Y");
		List<Pick> h = List.of();
		for (int t = 18; t < 28; t++) {
			h = BoxScene.record(h, t, "X", pool);
		}
		assertThat(h).hasSize(8);
		assertThat(h.get(0).turn()).isEqualTo(20);
		assertThat(BoxScene.record(h, 28, LEAVE_HOME, pool)).isEqualTo(h);
		assertThat(BoxScene.record(h, 28, "FOLLOW", pool)).isEqualTo(h);
	}

	@Test
	void habitInjectedFromTurn28OutsideNever() {
		assertThat(BoxScene.habitInjected(Situation.NEW_HOME, 27)).isFalse();
		assertThat(BoxScene.habitInjected(Situation.NEW_HOME, 28)).isTrue();
		assertThat(BoxScene.habitInjected(Situation.EMPTY_HOME, 28)).isTrue();
		assertThat(BoxScene.habitInjected(Situation.OUTSIDE, 40)).isFalse();
	}

	// ── 数据表自检 ───────────────────────────────────────────────────────

	private static List<Beat> allBeats() {
		List<Beat> b = new ArrayList<>(T.stages());
		b.add(T.stage4().high());
		b.add(T.stage4().low());
		b.addAll(T.takenAftermath());
		b.addAll(T.leftAftermath());
		b.add(T.r1Turn17Fill());
		return b;
	}

	private static Set<String> allIntents() {
		Set<String> s = new HashSet<>();
		allBeats().forEach(b -> b.options().forEach(o -> s.add(o.intent())));
		T.justOutside().forEach(o -> s.add(o.intent()));
		return s;
	}

	@Test
	void everyBeatHasExactlyAbcWithUniqueIntentsAndNonEmptyTemplates() {
		for (Beat b : allBeats()) {
			assertThat(b.options()).extracting(Option::slot).containsExactly("A", "B", "C");
			assertThat(b.options().stream().map(Option::intent).distinct().count()).isEqualTo(3);
			assertThat(b.options()).allMatch(o -> !o.template().isBlank());
		}
		assertThat(T.justOutside()).extracting(Option::slot).containsExactly("A", "B", "C");
		assertThatThrownBy(() -> new Beat("bad", null, List.of(
				new Option("A", "X", "x", 0, null, null, null),
				new Option("B", "X", "y", 0, null, null, null),
				new Option("C", "Z", "z", 0, null, null, null)))).isInstanceOf(IllegalArgumentException.class);
	}

	/** 定稿 lockstep:表里每一条来自定稿的文字都逐字出现在 ADR-028 附录里(只认文件 → 表方向)。 */
	@Test
	void templatesAppearVerbatimInAdrAppendix() throws Exception {
		String adr = Files.readString(java.nio.file.Path.of(
				"../docs/adr/ADR-028-box-scene-changeable-left-behind.md"));
		String appendix = adr.substring(adr.indexOf("## 附录 · 第一刀文案定稿"));
		for (Beat b : allBeats()) {
			for (Option o : b.options()) {
				assertThat(appendix).as(o.intent()).contains(o.template());
			}
		}
		for (Option o : T.justOutside()) {
			assertThat(appendix).contains(o.template());
		}
		for (Beat b : T.leftAftermath()) {
			assertThat(appendix).contains(b.omen());
		}
		for (Beat b : T.takenAftermath()) {
			assertThat(appendix).contains(b.omen());
		}
		assertThat(appendix).contains(T.r1Turn17Fill().omen()).contains(T.leaveFeedback());

		// 刀 2a 新入表的字符串:须在第二刀附录里逐字找到(只认附录 → 表)
		String second = adr.substring(adr.indexOf("## 附录 · 第二刀文案定稿"));
		for (Beat b : T.stages()) {
			assertThat(b.omen()).as(b.id()).isNotNull();
			assertThat(second).as(b.id()).contains(b.omen());
		}
		BoxScene.Pools pools = BoxSceneTables.ANIMAL_LIFE_POOLS;
		List<BoxScene.PoolIntent> all = new ArrayList<>(pools.newHome());
		all.addAll(pools.emptyHome());
		for (BoxScene.PoolIntent p : all) {
			assertThat(second).as(p.intent()).contains("`" + p.intent() + "`").contains(p.meaning())
					.contains(p.boundary()).contains(p.template()).contains(p.habit());
		}
		assertThat(second).contains("`" + LEAVE_HOME + "` | " + BoxSceneTables.LEAVE_HOME_TEMPLATE + " |");
		assertThat(pools.leaveTemplate()).isEqualTo(BoxSceneTables.LEAVE_HOME_TEMPLATE);
	}

	/** 池内顺序 = 附录「池内固定顺序」(轮换按它滑动)。 */
	@Test
	void poolOrderMatchesTheAppendixFixedOrder() throws Exception {
		String adr = Files.readString(java.nio.file.Path.of(
				"../docs/adr/ADR-028-box-scene-changeable-left-behind.md"));
		String second = adr.substring(adr.indexOf("## 附录 · 第二刀文案定稿"));
		BoxScene.Pools pools = BoxSceneTables.ANIMAL_LIFE_POOLS;
		StringBuilder nh = new StringBuilder();
		for (int i = 0; i < pools.newHome().size(); i++) {
			nh.append(i + 1).append(". `").append(pools.newHome().get(i).intent()).append("`\n");
		}
		StringBuilder eh = new StringBuilder();
		for (int i = 0; i < pools.emptyHome().size(); i++) {
			eh.append(i + 1).append(". `").append(pools.emptyHome().get(i).intent()).append("`\n");
		}
		assertThat(second).contains(nh.toString()).contains(eh.toString());
	}

	/** 机制文件里不得有本局面的任何字面量(意图编号、文字)。 */
	@Test
	void mechanismHasNoSceneLiterals() throws Exception {
		String src = Files.readString(java.nio.file.Path.of(
				"src/main/java/com/aiuniverse/server/eventloop/BoxScene.java"));
		for (String i : allIntents()) {
			assertThat(src).as(i).doesNotContain("\"" + i + "\"");
		}
		assertThat(src).doesNotContain("\"" + LEAVE_HOME + "\"");
		for (Beat b : allBeats()) {
			for (Option o : b.options()) {
				assertThat(src).doesNotContain(o.template());
			}
		}
		assertThat(src).doesNotContain("纸箱").doesNotContain("孩子");
	}

	/** 刀 2a 新增的机制文件同样不得有本局面字面量(意图编号、模板、池文字)。 */
	@Test
	void wiringMechanismFilesHaveNoSceneLiterals() throws Exception {
		Set<String> intents = new HashSet<>(allIntents());
		intents.add(LEAVE_HOME);
		List<String> texts = new ArrayList<>();
		allBeats().forEach(b -> b.options().forEach(o -> texts.add(o.template())));
		for (BoxScene.PoolIntent p : BoxSceneTables.ANIMAL_LIFE_POOLS.newHome()) {
			intents.add(p.intent());
			texts.add(p.template());
			texts.add(p.habit());
		}
		for (BoxScene.PoolIntent p : BoxSceneTables.ANIMAL_LIFE_POOLS.emptyHome()) {
			intents.add(p.intent());
			texts.add(p.template());
			texts.add(p.habit());
		}
		for (String f : List.of("BoxScene.java", "BoxSceneTurn.java", "BoxSceneState.java")) {
			String src = Files.readString(java.nio.file.Path.of("src/main/java/com/aiuniverse/server/eventloop/" + f));
			for (String i : intents) {
				assertThat(src).as(f + " " + i).doesNotContain("\"" + i + "\"");
			}
			for (String t : texts) {
				assertThat(src).as(f).doesNotContain(t);
			}
			assertThat(src).as(f).doesNotContain("纸箱").doesNotContain("孩子");
		}
	}
}
