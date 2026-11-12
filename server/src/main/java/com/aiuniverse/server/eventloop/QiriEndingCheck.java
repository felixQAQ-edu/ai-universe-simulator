package com.aiuniverse.server.eventloop;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 《七日开张》结局核对(ADR-038 决策 4 / Slice D)。在 {@code engine.apply} <b>之前</b>改写模型本回合的
 * {@code parsed.ending},让结局与落账后的数值一致;Engine 一行不动(CONTEXT §三.18(b),形状照 {@link PaidanEndingCheck})。
 *
 * <p><b>不先抽象</b>(ADR-038 勘察 6 / 已知代价 4):{@code settled} / {@code proposedId} / {@code endingExists} 三个私有小函数
 * 与 {@link PaidanEndingCheck} 逐字重复,判定表是两张独立的表 —— 改一处不会误伤另一处。等第三个单体核对出现时再判断要不要抽。
 *
 * <p>数值口径同 {@code PaidanEndingCheck.settled}:{@code stateUpdate} 给了取新值、没给取当前值,clamp 0–100;
 * 第 22 回合先落该回合的营业 / 立功(就是本回合的 stateUpdate),再判。判定表(先命中先用):
 * <ol>
 *   <li>任何回合底细 ≤ 0 → 请来了道士(核对直接写,不交引擎触底兜底);</li>
 *   <li>第 22 回合及以后,按表取第一个成立的:夜集第一摊(摊本 ≥ 60、熟客 ≥ 50、底细 ≥ 40)> 城中怪点子王(摊本 ≥ 60、
 *       <b>底细 &lt; 40</b>)> 明日照常开张;判出 success 而底细 ≤ 10 → <b>降级为请来了道士</b>;neutral 不降级
 *       (同《派单》「差一点」先例,ADR-036 订正四;ADR-038 已知代价 2);</li>
 *   <li>第 22 回合之前,模型提议的结局<b>一律不收</b>,本回合继续(底细 ≤ 0 那条除外)。</li>
 * </ol>
 *
 * <p>(「底细 &lt; 40」原为「≤ 39」,ADR-038「订正(2026-11-12 一)」决策 6 改:数值是小数,堵住 39 与 40 之间的空隙。)
 *
 * <p><b>「底细 &lt; 40 再降级」与提示词侧「11~39」写法不同,结果逐格相同</b>(ADR-038 决策 4「读法甲」,已知代价 3):
 * world-gen 的 condition 与第 22 回合注入表都按玩家看到的成立区间「11~39」写,并把「底细 ≤ 10 而摊本 ≥ 60 → 请来了道士」
 * 单列一行放在怪点子王之前;这里写成「< 40 再降级」是为了让降级规则在代码里真的起作用,与「请来了道士」的 condition
 * 「夜集时判出成功但底细 ≤ 10」对得上。逐格对拍见 {@code QiriEndingCheckTest}。
 *
 * <p>降级线取 {@link Engine#ENDING_GATE_THRESHOLD}(= 10):与引擎极性 gate(ADR-010)同一条线 —— 核对在 {@code apply}
 * 之前先判,gate 只在核对 {@code skipped} 时才真的出手(ADR-038 勘察 13)。
 *
 * <p>纯函数(只读引擎状态、只写 {@code parsed}),ADR-031 档 1 回放走同一份代码,确定性重现。
 */
final class QiriEndingCheck {

	static final String ARCHETYPE = "time_travel";

	static final String NIGHT_MARKET_TOP = "night_market_top";
	static final String ODD_IDEA_KING = "odd_idea_king";
	static final String OPEN_AS_USUAL = "open_as_usual";
	static final String TAOIST_SUMMONED = "taoist_summoned";

	/**
	 * 一次核对的结果。{@code proposed} = 模型提议的结局 id(未提议为 null);{@code verdict} = 写回 {@code parsed} 的
	 * 结局 id(null = 本回合不给结局);{@code skipped} = 核对没有改写 {@code parsed}(id 在本局结局池里不存在)。
	 */
	record Result(String proposed, String verdict, boolean skipped) {
		boolean changed() {
			return !skipped && !Objects.equals(proposed, verdict);
		}
	}

	private QiriEndingCheck() {
	}

	/**
	 * 只按数值给出判定(不看提议、不看结局池):第 {@code n} 回合、落账后摊本 / 熟客 / 底细为给定值时,核对写回的结局 id;
	 * null = 本回合继续。{@link #check} 与逐格单测共用这一份。
	 */
	static String verdict(int n, double capital, double regulars, double cover) {
		if (cover <= 0) {
			return TAOIST_SUMMONED; // 任何回合
		}
		if (n < TurnPromptBuilder.QIRI_FINAL_TURN) {
			return null; // 第 22 回合之前一律不收
		}
		String byTable = capital >= 60 && regulars >= 50 && cover >= 40 ? NIGHT_MARKET_TOP
				: capital >= 60 && cover < 40 ? ODD_IDEA_KING
				: OPEN_AS_USUAL;
		// 只降级 success(与引擎极性 gate 一致);明日照常开张(neutral)无论底细多低都保持。
		boolean success = NIGHT_MARKET_TOP.equals(byTable) || ODD_IDEA_KING.equals(byTable);
		return success && cover <= Engine.ENDING_GATE_THRESHOLD ? TAOIST_SUMMONED : byTable;
	}

	/**
	 * 核对并改写 {@code parsed.ending}。三轴(capital / regulars / cover)缺任何一条时什么都不做、返回 null
	 * (不是《七日开张》的引擎,或存档异常 —— 不在这里臆造)。
	 */
	static Result check(Engine engine, ObjectNode parsed) {
		Map<String, Double> now = engine.attributes();
		if (!now.keySet().containsAll(Set.of("capital", "regulars", "cover"))) {
			return null;
		}
		JsonNode upd = parsed.path("stateUpdate");
		String proposed = proposedId(parsed);
		String verdict = verdict(engine.turn() + 1, settled(now, upd, "capital"), settled(now, upd, "regulars"),
				settled(now, upd, "cover"));
		if (verdict != null && !endingExists(engine, verdict)) {
			return new Result(proposed, verdict, true); // 本局 id 与决策 4 不一致:不改写,退回引擎原有行为
		}
		if (verdict == null) {
			parsed.putNull("ending");
		} else {
			parsed.putObject("ending").put("reached", true).put("id", verdict);
		}
		return new Result(proposed, verdict, false);
	}

	/** 与 {@code Engine.apply} 第 2–4 步同口径:给了取新值、没给取当前值,clamp 0–100。 */
	private static double settled(Map<String, Double> now, JsonNode upd, String key) {
		double v = upd.has(key) ? upd.get(key).asDouble() : now.get(key);
		return Math.max(0, Math.min(100, v));
	}

	private static String proposedId(ObjectNode parsed) {
		JsonNode e = parsed.get("ending");
		if (e == null || !e.isObject() || !e.path("reached").asBoolean(false)) {
			return null;
		}
		return e.path("id").asString(null);
	}

	private static boolean endingExists(Engine engine, String id) {
		for (JsonNode e : engine.world().path("endings")) {
			if (id.equals(e.path("id").asString(null))) {
				return true;
			}
		}
		return false;
	}
}
