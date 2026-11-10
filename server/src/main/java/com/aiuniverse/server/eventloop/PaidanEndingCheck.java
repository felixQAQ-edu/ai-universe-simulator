package com.aiuniverse.server.eventloop;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 《派单》结局核对(ADR-036「订正(2026-11-10 二)」订正 1)。在 {@code engine.apply} <b>之前</b>改写模型本回合的
 * {@code parsed.ending},让结局与落账后的数值一致;Engine 一行不动(同 {@code clampClosingVigorFloor} 的落点与形状)。
 *
 * <p>数值口径与 {@code Engine.apply} 第 2–4 步相同:{@code stateUpdate} 给了就取它、没给取当前值,clamp 0–100;
 * 「已识破」= 原有 {@code discovered} 加上本回合 {@code discoveredRuleIds}。判定表(先命中先用):
 * <ol>
 *   <li>体力 ≤ 0 → 倒在楼道里;否则评分 ≤ 0 → 停派(核对直接写;引擎对已带 failure 结局的触底回合照收、不重挑);</li>
 *   <li>画像 ≥ 95 → {@code became_benchmark};</li>
 *   <li>第 27 回合及以后 → 体力 ≤ 10 → 倒在楼道里;否则评分 ≤ 10 → 停派(与引擎极性 gate 衔接,ADR-010);
 *       否则 把话传下去(须模型提议 + 数值成立)> 卡点的人 > 交上了 > 差一点;</li>
 *   <li>第 27 回合之前 → 只收数值成立、且体力与评分都 > 10 的「把话传下去」,其余提议都不收。</li>
 * </ol>
 *
 * <p>纯函数(只读引擎状态、只写 {@code parsed}),ADR-031 档 1 回放走同一份代码,确定性重现。
 */
final class PaidanEndingCheck {

	static final String ARCHETYPE = "cyberpunk";

	static final String RENT_PAID = "rent_paid";
	static final String KEPT_THE_PACE = "kept_the_pace";
	static final String PASSED_IT_ON = "passed_it_on";
	static final String BECAME_BENCHMARK = "became_benchmark";
	static final String ALMOST = "almost";
	static final String COLLAPSED = "collapsed_in_stairwell";
	static final String DISPATCH_STOPPED = "dispatch_stopped";

	static final String GROUP_PREFIX = "【群里说】";

	/**
	 * 一次核对的结果。{@code proposed} = 模型提议的结局 id(未提议为 null);{@code verdict} = 写回 {@code parsed} 的
	 * 结局 id(null = 本回合不给结局);{@code skipped} = 核对没有改写 {@code parsed}(id 在本局结局池里不存在)。
	 */
	record Result(String proposed, String verdict, boolean skipped) {
		boolean changed() {
			return !skipped && !Objects.equals(proposed, verdict);
		}
	}

	private PaidanEndingCheck() {
	}

	/**
	 * 核对并改写 {@code parsed.ending}。四轴(hp / cash / rating / profile)缺任何一条时什么都不做、返回 null
	 * (不是《派单》的引擎,或存档异常 —— 不在这里臆造)。
	 */
	static Result check(Engine engine, ObjectNode parsed) {
		Map<String, Double> now = engine.attributes();
		if (!now.keySet().containsAll(Set.of("hp", "cash", "rating", "profile"))) {
			return null;
		}
		JsonNode upd = parsed.path("stateUpdate");
		double hp = settled(now, upd, "hp");
		double cash = settled(now, upd, "cash");
		double rating = settled(now, upd, "rating");
		double profile = settled(now, upd, "profile");
		int n = engine.turn() + 1;

		String proposed = proposedId(parsed);
		String verdict;
		if (hp <= 0) {
			verdict = COLLAPSED; // 核对直接写,不交引擎触底兜底;两轴都归零时固定取体力(Felix 2026-11-10)
		} else if (rating <= 0) {
			verdict = DISPATCH_STOPPED;
		} else if (profile >= 95) {
			verdict = BECAME_BENCHMARK;
		} else {
			// 与引擎极性 gate 衔接(ADR-010):致命轴 ≤ ENDING_GATE_THRESHOLD 时引擎会拒绝 success 结局。
			// 核对在这里先判,不把 success 交给 gate 二次改写。
			boolean nearZero = hp <= Engine.ENDING_GATE_THRESHOLD || rating <= Engine.ENDING_GATE_THRESHOLD;
			boolean passedOk = !nearZero && PASSED_IT_ON.equals(proposed) && cash >= 50
					&& falseGroupRulesDiscovered(engine, parsed) >= 2;
			if (n >= TurnPromptBuilder.PAIDAN_FINAL_TURN) {
				// 两条都濒零时取体力(固定,Felix 2026-11-10;引擎 pickFailureEnding 按 attributes 的 JSON 键序,通常也是体力在前)。
				verdict = hp <= Engine.ENDING_GATE_THRESHOLD ? COLLAPSED
						: rating <= Engine.ENDING_GATE_THRESHOLD ? DISPATCH_STOPPED
						: passedOk ? PASSED_IT_ON
						: cash >= 60 && profile <= 40 ? KEPT_THE_PACE
						: cash >= 80 ? RENT_PAID
						: ALMOST;
			} else {
				verdict = passedOk ? PASSED_IT_ON : null; // 濒零时把话传下去不收,本回合继续,免得被 gate 改判提前结束
			}
		}
		if (verdict != null && !endingExists(engine, verdict)) {
			return new Result(proposed, verdict, true); // 本局 id 与决策 7 不一致:不改写,退回引擎原有行为
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

	/** 落账后已识破的、{@code isTrue=false} 且以「【群里说】」开头的规则条数。 */
	static int falseGroupRulesDiscovered(Engine engine, ObjectNode parsed) {
		Set<Integer> newly = new HashSet<>();
		for (JsonNode rid : parsed.path("discoveredRuleIds")) {
			newly.add(rid.asInt());
		}
		int count = 0;
		for (JsonNode r : engine.world().path("rules")) {
			if (!r.isObject()) {
				continue;
			}
			boolean discovered = r.path("discovered").asBoolean(false) || newly.contains(r.path("id").asInt());
			boolean fake = r.path("isTrue").isBoolean() && !r.path("isTrue").asBoolean();
			if (discovered && fake && r.path("content").asString("").strip().startsWith(GROUP_PREFIX)) {
				count++;
			}
		}
		return count;
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
