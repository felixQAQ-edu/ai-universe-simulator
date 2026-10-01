package com.aiuniverse.server.eventloop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 局面的<b>纯机制</b>(ADR-028 刀 1):结算、阶段 4 呈现、处境转移、意图池轮换、习惯句。
 *
 * <p><b>本文件一个本局面的字面量都不许有</b> —— 征兆、意图编号、模板文字、反馈与记忆事实全部在
 * {@link BoxSceneTables} 里(同 {@link LifeStage} / {@link LifeStageTables} 的分工,
 * 由 {@code BoxSceneTest} 的源码级断言守护)。
 *
 * <p><b>刀 1 不接线</b>:本类没有任何调用方,回合路径行为零变化。
 * 所有条件一律用<b>本回合行动之前的 g</b>(ADR-028 §局面状态);g 下限 0。
 */
final class BoxScene {

	private BoxScene() {
	}

	/** 无条件结算(阶段 4 的选项):与 g 的取值无关,不靠「g ≥ 0」这个下限推出来。 */
	static final int ALWAYS = Integer.MIN_VALUE;

	/** 协议槽位:局面回合恰好 A / B / C 三个(本世界无出口,D 不用)。 */
	static final List<String> SLOTS = List.of("A", "B", "C");

	/** 结算类别:被带走 / 被留下。 */
	enum Category { TAKEN, LEFT }

	/** 结算路径(ADR-028 §结算与记忆事实)。 */
	enum Path {
		R1(Category.TAKEN), R2(Category.TAKEN), R3A(Category.LEFT), R3B(Category.LEFT), R3C(Category.LEFT);

		final Category category;

		Path(Category category) {
			this.category = category;
		}
	}

	/** 处境(ADR-028 §已决 A)。 */
	enum Situation { NEW_HOME, EMPTY_HOME, OUTSIDE }

	/**
	 * 一个槽位选项。
	 *
	 * @param gDelta     未结算时应用的 g 变化
	 * @param settleAtG  非 null:行动前 g ≥ 它时本行动结算到 {@code settlePath}(阶段 4 的选项为 {@link #ALWAYS})
	 * @param feedback   未结算时的反馈事实(余波 / 补位 / 刚出门选项为 null)
	 */
	record Option(String slot, String intent, String template, int gDelta,
			Integer settleAtG, Path settlePath, String feedback) {

		Option {
			if (!SLOTS.contains(slot)) {
				throw new IllegalArgumentException("槽位非法:" + slot);
			}
			if (intent == null || intent.isBlank() || template == null || template.isBlank()) {
				throw new IllegalArgumentException("意图或模板为空:" + slot);
			}
			if ((settleAtG == null) != (settlePath == null)) {
				throw new IllegalArgumentException("settleAtG 与 settlePath 须同时给出:" + intent);
			}
		}
	}

	/** 一拍:征兆 + A/B/C 三个选项。征兆可为 null(该拍征兆尚未定稿)。 */
	record Beat(String id, String omen, List<Option> options) {

		Beat {
			options = List.copyOf(options);
			requireAbc(id, options);
		}

		Option slot(String s) {
			return options.stream().filter(o -> o.slot().equals(s)).findFirst().orElseThrow();
		}

		Optional<Option> byIntent(String intent) {
			return options.stream().filter(o -> o.intent().equals(intent)).findFirst();
		}
	}

	/** 阶段 4:行动前 g ≥ {@code threshold} 用 {@code high},否则用 {@code low}。 */
	record Stage4(int threshold, Beat high, Beat low) {
	}

	/** 结算路径的数据:记忆事实。 */
	record PathEntry(Path path, String memoryFact) {
	}

	/** 结算结果。{@code feedback} 在结算时为该路径的记忆事实。 */
	record Result(int newG, boolean settled, Path path, String feedback) {
	}

	/**
	 * 一张局面数据表。阶段 1–3 存在 {@code stages}(下标 0 = 阶段 1)。
	 */
	record Table(String archetype, int firstTurn, List<Beat> stages, Stage4 stage4,
			Map<Path, String> memoryFacts, String leftCommonFact,
			List<Beat> takenAftermath, List<Beat> leftAftermath, Beat r1Turn17Fill,
			String leaveIntent, String leaveFeedback, List<Option> justOutside) {

		Table {
			stages = List.copyOf(stages);
			memoryFacts = Map.copyOf(memoryFacts);
			takenAftermath = List.copyOf(takenAftermath);
			leftAftermath = List.copyOf(leftAftermath);
			justOutside = List.copyOf(justOutside);
			requireAbc("justOutside", justOutside);
			for (Path p : Path.values()) {
				if (memoryFacts.get(p) == null || memoryFacts.get(p).isBlank()) {
					throw new IllegalArgumentException("缺记忆事实:" + p);
				}
			}
		}
	}

	private static void requireAbc(String id, List<Option> options) {
		List<String> slots = options.stream().map(Option::slot).toList();
		if (!slots.equals(SLOTS)) {
			throw new IllegalArgumentException(id + " 的槽位必须恰好是 A/B/C:" + slots);
		}
		Set<String> intents = new HashSet<>();
		for (Option o : options) {
			if (!intents.add(o.intent())) {
				throw new IllegalArgumentException(id + " 意图编号重复:" + o.intent());
			}
		}
	}

	// ── 呈现 ─────────────────────────────────────────────────────────────

	/** 阶段 s(1–4)在行动前 g 下给玩家的那一拍。阶段 4 按 g 分叉。 */
	static Beat present(Table t, int stage, int gBefore) {
		if (stage >= 1 && stage <= t.stages().size()) {
			return t.stages().get(stage - 1);
		}
		if (stage == t.stages().size() + 1) {
			return gBefore >= t.stage4().threshold() ? t.stage4().high() : t.stage4().low();
		}
		throw new IllegalArgumentException("阶段越界:" + stage);
	}

	// ── 结算 ─────────────────────────────────────────────────────────────

	/** 给定阶段、行动前 g、所选意图 → 结果。意图不在该阶段该 g 下的槽位里 → 抛。 */
	static Result settle(Table t, int stage, int gBefore, String intent) {
		Option o = present(t, stage, gBefore).byIntent(intent)
				.orElseThrow(() -> new IllegalArgumentException("阶段 " + stage + " 不提供意图:" + intent));
		if (o.settleAtG() != null && gBefore >= o.settleAtG()) {
			return new Result(gBefore, true, o.settlePath(), t.memoryFacts().get(o.settlePath()));
		}
		return new Result(Math.max(0, gBefore + o.gDelta()), false, null, o.feedback());
	}

	/** 结算路径 → 它此后要带着的记忆事实:各自那条;被留下的三条另加共同兜底事实。 */
	static List<String> memoryFacts(Table t, Path path) {
		String own = t.memoryFacts().get(path);
		return path.category == Category.LEFT ? List.of(own, t.leftCommonFact()) : List.of(own);
	}

	/** 结算路径 → 余波开始时的处境。 */
	static Situation situationAfter(Path path) {
		return path.category == Category.TAKEN ? Situation.NEW_HOME : Situation.EMPTY_HOME;
	}

	/** 结算路径 → 余波三拍。 */
	static List<Beat> aftermath(Table t, Path path) {
		return path.category == Category.TAKEN ? t.takenAftermath() : t.leftAftermath();
	}

	/**
	 * 处境转移:唯一一条 = EMPTY_HOME 下选 LEAVE_HOME 且回合<b>落地</b> → OUTSIDE。
	 * NEW_HOME 与 OUTSIDE 没有任何转出(§已决 A / H)。
	 */
	static Situation transition(Table t, Situation current, String intent, boolean landed) {
		if (current == Situation.EMPTY_HOME && landed && t.leaveIntent().equals(intent)) {
			return Situation.OUTSIDE;
		}
		return current;
	}

	// ── 轮换(§已决 F)────────────────────────────────────────────────────

	/** NEW_HOME:{@code o = (t − S + b) mod n},A/B/C = P[o], P[o+1], P[o+2]。 */
	static List<String> rotateNewHome(List<String> pool, int turn, int segmentStart, int offset) {
		int n = pool.size();
		if (n < 3) {
			throw new IllegalArgumentException("新屋池至少 3 项");
		}
		int o = Math.floorMod(turn - segmentStart + offset, n);
		return List.of(pool.get(o), pool.get((o + 1) % n), pool.get((o + 2) % n));
	}

	/** EMPTY_HOME:A/B = Q[o], Q[o+1],C 恒为离开意图;池不得含离开意图。 */
	static List<String> rotateEmptyHome(List<String> pool, String leaveIntent, int turn, int segmentStart, int offset) {
		int m = pool.size();
		if (m < 3 || pool.contains(leaveIntent)) {
			throw new IllegalArgumentException("旧屋池须 ≥3 项且不含离开意图");
		}
		int o = Math.floorMod(turn - segmentStart + offset, m);
		return List.of(pool.get(o), pool.get((o + 1) % m), leaveIntent);
	}

	// ── 习惯句(§已决 G)──────────────────────────────────────────────────

	/** 意图历史条目。 */
	record Pick(int turn, String intent) {
	}

	static final int HABIT_KEEP = 8;
	static final int HABIT_MIN_ENTRIES = 3;
	static final int HABIT_MIN_COUNT = 2;
	static final int HABIT_FROM_TURN = 28;

	/** 记录一条:只记当前处境池里的意图;超过 8 条丢最早一条。 */
	static List<Pick> record(List<Pick> history, int turn, String intent, Set<String> currentPool) {
		if (!currentPool.contains(intent)) {
			return history;
		}
		List<Pick> next = new ArrayList<>(history);
		next.add(new Pick(turn, intent));
		while (next.size() > HABIT_KEEP) {
			next.remove(0);
		}
		return List.copyOf(next);
	}

	/** 「它这些天最常做的事」:窗口 {@code t−8 ≤ 回合 < t};条目 < 3 或最高计数 < 2 → 空;并列取最近被选的。 */
	static Optional<String> habit(List<Pick> history, int turn) {
		List<Pick> window = history.stream()
				.filter(p -> p.turn() >= turn - HABIT_KEEP && p.turn() < turn).toList();
		if (window.size() < HABIT_MIN_ENTRIES) {
			return Optional.empty();
		}
		Map<String, Integer> count = new HashMap<>();
		Map<String, Integer> last = new HashMap<>();
		for (Pick p : window) {
			count.merge(p.intent(), 1, Integer::sum);
			last.merge(p.intent(), p.turn(), Math::max);
		}
		int max = count.values().stream().max(Integer::compare).orElse(0);
		if (max < HABIT_MIN_COUNT) {
			return Optional.empty();
		}
		return count.entrySet().stream().filter(e -> e.getValue() == max)
				.max((a, b) -> Integer.compare(last.get(a.getKey()), last.get(b.getKey())))
				.map(Map.Entry::getKey);
	}

	/** 是否注入习惯句:第 28 回合起,且处境为 NEW_HOME / EMPTY_HOME。 */
	static boolean habitInjected(Situation situation, int turn) {
		return turn >= HABIT_FROM_TURN && situation != Situation.OUTSIDE;
	}
}
