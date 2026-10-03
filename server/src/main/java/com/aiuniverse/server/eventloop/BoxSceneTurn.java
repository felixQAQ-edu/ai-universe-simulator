package com.aiuniverse.server.eventloop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.eventloop.BoxScene.Beat;
import com.aiuniverse.server.eventloop.BoxScene.Option;
import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxScene.Pick;
import com.aiuniverse.server.eventloop.BoxScene.PoolIntent;
import com.aiuniverse.server.eventloop.BoxScene.Pools;
import com.aiuniverse.server.eventloop.BoxScene.Result;
import com.aiuniverse.server.eventloop.BoxScene.Situation;
import com.aiuniverse.server.eventloop.BoxScene.Table;

/**
 * 局面层的<b>回合编排</b>(ADR-028 刀 2a):给定会话层状态与玩家所选槽位,算出「这一回合引擎给定什么」——
 * 反馈事实、征兆、记忆事实、习惯句、本回合槽位,以及<b>落地时</b>要提交的状态变化。
 *
 * <p><b>纯函数</b>:{@link #plan} 不改任何东西;状态只在回合<b>落地之后</b>由 {@link #commit} 写入
 * (EventLoopService 里紧跟 {@code engine.apply} / {@code applyNoOp}、排在任何 sink 写之前 ——
 * ADR-028 §已决 A 第 7 条 / ADR-027:已落地未送达时补写盘的快照里状态与回合号一致;未落地则什么都没写)。
 *
 * <p><b>本文件一个本局面的字面量都不许有</b>(同 {@link BoxScene};由 {@code BoxSceneTest} 的源码级断言守护)。
 * 下方提示词块里的中文只是结构说明,不含任何局面文字。
 *
 * <p>回合编号口径:{@code nextTurn} 是正在生成的那一回合(= {@code engine.turn() + 1})。第 {@code firstTurn}
 * 回合的正文写阶段 1 的征兆、尾巴给阶段 1 的选项;玩家在那组选项里的选择,在下一回合生成时结算。
 */
final class BoxSceneTurn {

	private static final Logger log = LoggerFactory.getLogger(BoxSceneTurn.class);

	private BoxSceneTurn() {
	}

	/** 本回合引擎给定的一个槽位。{@code meaning} / {@code boundary} 只对处境意图池的条目有。 */
	record Slot(String slot, String intent, String template, String meaning, String boundary) {
	}

	/**
	 * 本回合的编排结果。
	 *
	 * @param slots        本回合尾巴必须给出的 A/B/C(null = 不接管,选项由模型自由给)
	 * @param degradeSlots 本回合若降级落地,下一组用的模板(null = 沿用今天:复用上一组)
	 * @param transition   玩家选了离开意图:落地时提交 EMPTY_HOME → OUTSIDE
	 * @param record       落地时要记进意图历史的一条(null = 不记)
	 * @param beatId       本回合编排所用那一拍的 {@code Beat.id}(局面阶段 / 余波 / 补位);处境期没有拍 = null。
	 *                     ADR-029 的逐字句窗口按它判定 A1 / A2 / B1–B3,不按回合号反推
	 */
	record Plan(String feedback, String omen, List<String> memoryFacts, String habit,
			List<Slot> slots, List<Slot> degradeSlots, boolean transition,
			int newG, Path newResult, Integer newSettledTurn, Situation newSituation, Pick record, String beatId) {

		boolean injectsNothing() {
			return feedback == null && omen == null && memoryFacts.isEmpty() && habit == null && slots == null;
		}
	}

	/** 窗口末回合:首回合 + 阶段 1–3 + 阶段 4 + 余波三拍 − 1(= 17)。 */
	static int windowEnd(Table t) {
		return t.firstTurn() + t.stages().size() + 1 + t.takenAftermath().size() - 1;
	}

	/**
	 * 本回合时钟契约三项的覆盖(ADR-028 §已决 B / 决策 3,刀 2 补充):<b>结算之后、窗口末回合以内</b>,
	 * 段名 / 设计标注 / 推进语由局面层<b>按结算结果</b>给出(R1 / R2 → 第二组,R3a / b / c → 第三组)。
	 *
	 * <p>结算发生在「生成下一回合」那一刻(玩家在阶段 3 做出提前结算的选择 → 生成第 14 回合时结算,
	 * {@code settledTurn = 14}),故判据看的是<b>本回合编排</b>里的结果,而不是落地前存档里的值:
	 * R1 从第 14 回合起覆盖、R2 / R3 从第 15 回合起覆盖。结算之前不覆盖,走时钟表的局面段;
	 * 窗口之后不覆盖,走时钟表。旧局 / 不接局面层 → 编排为 {@code null} → 不覆盖。
	 */
	static BoxSceneTables.StageText stageOverride(Table t, Plan p, int nextTurn) {
		if (p == null || p.newResult() == null || nextTurn > windowEnd(t)) {
			return null;
		}
		return BoxSceneTables.aftermathStage(t.archetype(), p.newResult());
	}

	/**
	 * 算本回合编排;旧局或无事可做 → {@code null}(调用方据此走今天的原路径,一个字节都不变)。
	 */
	static Plan plan(Table t, Pools pools, LifeStageTable clock, BoxSceneState st, int nextTurn, String chosenSlot) {
		if (st == null || st.isLegacy() || nextTurn < t.firstTurn()) {
			return null;
		}
		int n = nextTurn;
		int end = windowEnd(t);
		String chosen = st.slots.get(chosenSlot);

		String feedback = null;
		int g = st.g;
		Path result = st.result;
		Integer settledTurn = st.settledTurn;
		Situation situation = st.situation;
		boolean transition = false;
		Pick record = null;

		// ── 1. 结算玩家上一步的选择(只在它来自局面阶段 1–4 时)──
		if (result == null && n - 1 >= t.firstTurn()) {
			int stage = n - 1 - t.firstTurn() + 1;
			if (chosen == null) {
				// 映射丢失只可能来自损坏的文档;以当回合呈现兜底,并大声说出来(正常路径从不走这里)。
				chosen = BoxScene.present(t, stage, g).slot(chosenSlot).intent();
				log.warn("[box-scene] 阶段 {} 槽位 {} 无已存映射,按呈现兜底为 {}", stage, chosenSlot, chosen);
			}
			Result r = BoxScene.settle(t, stage, g, chosen);
			feedback = r.feedback();
			if (r.settled()) {
				result = r.path();
				settledTurn = n;
				situation = BoxScene.situationAfter(r.path());
			} else {
				g = r.newG();
			}
		} else if (result != null && situation != null && chosen != null) {
			// ── 2. 结算之后:离开意图 = 唯一的转移(余波 B3 起就可能出现);
			//       池内意图(只在窗口之后的处境期)记进历史 ──
			if (BoxScene.transition(t, situation, chosen, true) != situation) {
				transition = true;
				feedback = t.leaveFeedback();
			} else if (n - 1 > end && situation != Situation.OUTSIDE) {
				Set<String> pool = pools.of(situation).stream().map(PoolIntent::intent).collect(Collectors.toSet());
				List<Pick> next = BoxScene.record(st.history, n, chosen, pool);
				if (next != st.history) {
					record = next.get(next.size() - 1);
				}
			}
		}
		Situation effective = transition ? Situation.OUTSIDE : situation;

		// ── 3. 本回合给什么:征兆与槽位 ──
		String omen = null;
		List<Slot> slots = null;
		String beatId = null;
		if (result == null) {
			Beat beat = BoxScene.present(t, n - t.firstTurn() + 1, g);
			omen = beat.omen();
			slots = fromBeat(beat);
			beatId = beat.id();
		} else if (n - settledTurn < BoxScene.aftermath(t, result).size()) {
			Beat beat = BoxScene.aftermath(t, result).get(n - settledTurn);
			omen = beat.omen();
			slots = fromBeat(beat);
			beatId = beat.id();
		} else if (n <= end) {
			Beat beat = t.r1Turn17Fill(); // 窗口内余波之后的空档(只有提前结算才会走到)
			omen = beat.omen();
			slots = fromBeat(beat);
			beatId = beat.id();
		} else if (!transition && effective != Situation.OUTSIDE) {
			slots = fromPool(pools, clock, effective, n, end, t.leaveIntent());
		}
		List<Slot> degradeSlots = transition
				? t.justOutside().stream().map(o -> new Slot(o.slot(), o.intent(), o.template(), null, null)).toList()
				: slots;

		// ── 4. 记忆事实(结算后每回合)与习惯句(第 28 回合起,NEW_HOME / EMPTY_HOME)──
		List<String> facts = result == null ? List.of() : BoxScene.memoryFacts(t, result);
		String habit = null;
		if (effective != null && BoxScene.habitInjected(effective, n)) {
			Optional<String> h = BoxScene.habit(st.history, n);
			if (h.isPresent()) {
				habit = pools.find(effective, h.get()).map(PoolIntent::habit).orElse(null);
			}
		}

		Plan p = new Plan(feedback, omen, facts, habit, slots, degradeSlots, transition,
				g, result, settledTurn, effective, record, beatId);
		return p.injectsNothing() && record == null && !transition ? null : p;
	}

	private static List<Slot> fromBeat(Beat b) {
		List<Slot> out = new ArrayList<>();
		for (Option o : b.options()) {
			out.add(new Slot(o.slot(), o.intent(), o.template(), null, null));
		}
		return out;
	}

	/** 处境意图池按回合确定性轮换(§已决 F);段起点取时钟表的段,b[段] = 步长 × 段序号。 */
	private static List<Slot> fromPool(Pools pools, LifeStageTable clock, Situation s, int n, int end,
			String leaveIntent) {
		List<LifeStage> segments = clock.stages().stream().filter(st -> st.toTurn() > end).toList();
		LifeStage seg = clock.stageAt(n);
		int idx = Math.max(0, segments.indexOf(seg));
		int start = Math.max(seg.fromTurn(), end + 1);
		int offset = pools.rotationOffsetStep() * idx;
		List<PoolIntent> pool = pools.of(s);
		List<String> ids = pool.stream().map(PoolIntent::intent).toList();
		List<String> picked = s == Situation.EMPTY_HOME
				? BoxScene.rotateEmptyHome(ids, leaveIntent, n, start, offset)
				: BoxScene.rotateNewHome(ids, n, start, offset);
		List<Slot> out = new ArrayList<>();
		for (int i = 0; i < picked.size(); i++) {
			String id = picked.get(i);
			String slot = BoxScene.SLOTS.get(i);
			if (id.equals(leaveIntent)) {
				out.add(new Slot(slot, id, pools.leaveTemplate(), null, null));
			} else {
				PoolIntent p = pools.find(s, id).orElseThrow();
				out.add(new Slot(slot, id, p.template(), p.meaning(), p.boundary()));
			}
		}
		return out;
	}

	/**
	 * 落地提交(紧跟 {@code engine.apply} / {@code applyNoOp},排在任何 sink 写之前)。
	 *
	 * @param offered 本回合实际下发的槽位(正常落地 = {@code plan.slots()};降级 = {@code plan.degradeSlots()});
	 *                null = 本回合选项不由引擎接管,映射清空
	 */
	static void commit(BoxSceneState st, Plan p, List<Slot> offered) {
		st.g = p.newG();
		st.result = p.newResult();
		st.settledTurn = p.newSettledTurn();
		st.situation = p.newSituation();
		if (p.record() != null) {
			st.history = BoxScene.record(st.history, p.record().turn(), p.record().intent(), Set.of(p.record().intent()));
		}
		Map<String, String> slots = new LinkedHashMap<>();
		if (offered != null) {
			offered.forEach(s -> slots.put(s.slot(), s.intent()));
		}
		st.slots = slots;
	}

	/**
	 * 叙事素材注入时的人称转换(ADR-028 §已决 K,F-033):数据表里指代动物的「它」→「你」。
	 *
	 * <p><b>纯函数,只在渲染时调用</b>:数据表、ADR 附录、落盘的 boxScene 一律保持「它」;
	 * 只用在会被正文直接承认的<b>叙事素材字段</b>上(见 {@link #narrativeMaterials}),
	 * 不用在结构标题、槽位模板、意图含义与边界、处境片段、设计标注上 —— <b>不得对整个 prompt 做全局替换</b>。
	 *
	 * <p><b>安全阀</b>:素材里出现「它们」即抛 —— 那不是这只动物,硬替换会静默改错。
	 * 加载期由 {@code BoxSceneTables} 对全部素材预跑一遍,故实际在类加载时就拒绝,不会拖到某个回合。
	 */
	static String narrated(String material) {
		if (material == null) {
			return null;
		}
		if (material.contains("它们")) {
			throw new IllegalArgumentException("叙事素材含「它们」,不能按「它 = 这只动物」转换:" + material);
		}
		return material.replace("它", "你");
	}

	/**
	 * 一张局面表 + 意图池里<b>全部叙事素材字段</b>的条目(按字段确定,不按条目数确定):
	 * 阶段征兆(含阶段 4 的两个分叉)、反馈事实、结算记忆事实与共同兜底事实、余波征兆(两种)、
	 * 离开反馈、补位征兆、两个意图池的习惯短语。null(未定稿的征兆)略过。
	 */
	static List<String> narrativeMaterials(Table t, Pools pools) {
		List<String> out = new ArrayList<>();
		List<Beat> beats = new ArrayList<>(t.stages());
		beats.add(t.stage4().high());
		beats.add(t.stage4().low());
		beats.addAll(t.takenAftermath());
		beats.addAll(t.leftAftermath());
		beats.add(t.r1Turn17Fill());
		for (Beat b : beats) {
			if (b.omen() != null) {
				out.add(b.omen());
			}
			for (Option o : b.options()) {
				if (o.feedback() != null) {
					out.add(o.feedback());
				}
			}
		}
		for (Path p : Path.values()) {
			out.add(t.memoryFacts().get(p));
		}
		out.add(t.leftCommonFact());
		out.add(t.leaveFeedback());
		for (PoolIntent p : pools.newHome()) {
			out.add(p.habit());
		}
		for (PoolIntent p : pools.emptyHome()) {
			out.add(p.habit());
		}
		return out;
	}

	/** 本回合的局面注入段(视图 2):只有人话,不含意图编号、g、映射。全空 → 空串(提示词逐字不变)。 */
	static String promptBlock(Plan p) {
		if (p == null || p.injectsNothing()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("\n\n【本回合由引擎给定的局面事实(只措辞,不改事实)】");
		if (p.feedback() != null) {
			sb.append("\n- 玩家上一步行动的结果(本回合正文须写到):").append(narrated(p.feedback()));
		}
		if (p.omen() != null) {
			sb.append("\n- 本回合正文须写到的变化(可融入正文、不必逐字复述,但不能遗漏):").append(narrated(p.omen()));
		}
		if (!p.memoryFacts().isEmpty()) {
			sb.append("\n- 它带着的记忆(不必每回合复述,但不得与之矛盾):").append(String.join(";", p.memoryFacts().stream().map(BoxSceneTurn::narrated).toList()));
		}
		if (p.habit() != null) {
			sb.append("\n- 它这些天反复做的事(只承认玩家过去的行动,不新增数值、不改变处境):").append(narrated(p.habit()));
		}
		if (p.slots() != null) {
			sb.append("\n- 本回合结构化尾巴的 availableActions 必须恰好三个,id 依次为 A、B、C,不得有 D;")
					.append("每个槽位只能写下列动作(可以调整语气,不能改变动作对象、移动边界或状态效果):");
			for (Slot s : p.slots()) {
				sb.append("\n  · ").append(s.slot()).append(":").append(s.template());
				if (s.meaning() != null) {
					sb.append("(须表达:").append(s.meaning()).append(";行动边界:").append(s.boundary()).append(")");
				}
			}
		}
		return sb.toString();
	}
}
