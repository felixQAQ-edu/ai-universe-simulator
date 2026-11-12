package com.aiuniverse.server.eventloop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 《七日开张》按选项前缀的服务端兜底与每回合数值钳制(ADR-038「订正(2026-11-12 一)」决策 2 / 3 / 4)。
 * 在 {@code engine.apply} <b>之前</b>、{@link QiriEndingCheck} 之前改写 {@code parsed.stateUpdate};Engine 一行不动
 * (CONTEXT §三.18(b),落点照 {@code clampClosingVigorFloor} / {@link PaidanEndingCheck})。
 *
 * <p>顺序:先按玩家本回合所选选项的前缀兜底,再按本世界每回合 Δ 区间钳制,最后 clamp 0–100。兜底的界都落在钳制区间之内
 * (ADR 订正一「兜底与钳制核算」),所以钳制不会把兜底写的值又钳回去。
 *
 * <p>纯函数(只读引擎状态与选项、只写 {@code parsed}),ADR-031 档 1 回放走同一份代码,确定性重现。
 */
final class QiriActionGuard {

	/** 选项前缀(Felix 2026-11-12 定稿,逐字)。 */
	static final String ODD_IDEA = "【怪点子】";
	static final String SMOOTH_OVER = "【打圆场】";

	/** 本世界每回合 Δ 区间(决策 3):{下界, 上界}。 */
	static final Map<String, double[]> DELTA_RANGE = Map.of(
			"capital", new double[] { -15, 12 },
			"regulars", new double[] { -8, 8 },
			"cover", new double[] { -12, 6 });

	/** 三轴的处理顺序(issue 的顺序因此确定)。 */
	private static final List<String> AXES = List.of("capital", "regulars", "cover");

	private QiriActionGuard() {
	}

	/** 选项文字以哪个前缀开头(字面精确匹配,必须在开头);都不是 → null。 */
	static String prefixOf(String text) {
		if (text == null) {
			return null;
		}
		if (text.startsWith(ODD_IDEA)) {
			return ODD_IDEA;
		}
		if (text.startsWith(SMOOTH_OVER)) {
			return SMOOTH_OVER;
		}
		return null;
	}

	/** 第 n 回合是否早回合(进货):n &lt; 22 且 (n − 1) mod 3 = 0。第 22 回合及以后不是。 */
	static boolean morning(int n) {
		return n < TurnPromptBuilder.QIRI_FINAL_TURN && (n - 1) % 3 == 0;
	}

	/**
	 * 只按数值给出一轴的写入值(不看引擎、不看 parsed):第 n 回合、选项前缀 {@code prefix}(null = 无前缀)、
	 * 落账前 {@code before}、模型给的 {@code proposed}(没给就传 {@code before})。逐格单测与 {@link #apply} 共用这一份。
	 *
	 * @return {兜底后的值, 最终写入值}
	 */
	static double[] settle(int n, String prefix, String axis, double before, double proposed) {
		double v = proposed;
		if (ODD_IDEA.equals(prefix)) {
			if ("cover".equals(axis)) {
				v = Math.min(v, before - 6);
			} else if ("capital".equals(axis)) {
				v = Math.max(v, before + (morning(n) ? 0 : 8));
			}
		} else if (SMOOTH_OVER.equals(prefix)) {
			if ("cover".equals(axis)) {
				v = Math.max(v, before + 6);
			} else if ("capital".equals(axis)) {
				v = Math.min(v, before - 3);
			}
		}
		double guarded = v;
		double[] r = DELTA_RANGE.get(axis);
		v = Math.max(before + r[0], Math.min(before + r[1], v));
		v = Math.max(0, Math.min(100, v));
		return new double[] { guarded, v };
	}

	/**
	 * 兜底并钳制 {@code parsed.stateUpdate},返回要记进引擎 issues 的行(按 capital / regulars / cover 顺序;
	 * 末尾可能有一条「缺怪点子选项」)。三轴缺任何一条时什么都不做、返回空表。
	 *
	 * @param actions 玩家本回合看到的那一组选项(= {@code pre.currentActions})
	 */
	static List<String> apply(Engine engine, ObjectNode parsed, String actionId, JsonNode actions) {
		Map<String, Double> now = engine.attributes();
		List<String> issues = new ArrayList<>();
		if (!now.keySet().containsAll(Set.of("capital", "regulars", "cover"))) {
			return issues;
		}
		int n = engine.turn() + 1;
		String prefix = prefixOf(textOf(actions, actionId));
		JsonNode updNode = parsed.get("stateUpdate");
		ObjectNode upd = updNode != null && updNode.isObject() ? (ObjectNode) updNode : parsed.putObject("stateUpdate");
		for (String axis : AXES) {
			double before = now.get(axis);
			boolean given = upd.has(axis) && upd.get(axis).isNumber();
			double proposed = given ? upd.get(axis).asDouble() : before;
			double[] r = settle(n, prefix, axis, before, proposed);
			if (r[1] == proposed) {
				continue;
			}
			putNumber(upd, axis, r[1]);
			if (r[0] != proposed) { // 兜底改了值(写入值可能再被 0–100 压过,仍记成兜底)
				String name = ODD_IDEA.equals(prefix) ? "怪点子" : "打圆场";
				issues.add("T" + n + " " + name + "兜底 " + axis + " " + fmt(proposed) + "→" + fmt(r[1]));
			} else {
				issues.add("T" + n + " 钳制 " + axis + " " + fmt(before) + "→" + fmt(proposed) + " 改 " + fmt(r[1]));
			}
		}
		if (!hasOddIdea(actions)) {
			issues.add("T" + n + " 缺怪点子选项");
		}
		return issues;
	}

	static boolean hasOddIdea(JsonNode actions) {
		if (actions != null) {
			for (JsonNode a : actions) {
				if (ODD_IDEA.equals(prefixOf(a.path("text").asString(null)))) {
					return true;
				}
			}
		}
		return false;
	}

	private static String textOf(JsonNode actions, String actionId) {
		if (actions != null && actionId != null) {
			for (JsonNode a : actions) {
				if (actionId.equals(a.path("id").asString(null))) {
					return a.path("text").asString(null);
				}
			}
		}
		return null;
	}

	private static void putNumber(ObjectNode node, String key, double x) {
		if (x == Math.rint(x)) {
			node.put(key, (long) x);
		} else {
			node.put(key, x);
		}
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}
}
