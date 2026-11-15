package com.aiuniverse.server.eventloop;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;

/**
 * 《寻常》牵挂升档标记(ADR-040):落账后 {@code ties} 的档序号比落账前高 → 记一行引擎 issue「T{n} 牵挂升档 a→b」;
 * 下发时由 {@link ClientProjection#markedTurns} 从 issues 解析出回合号清单。
 *
 * <p><b>判档</b>:档表从 registry 取 {@code life_sim} 的 {@code ties} 轴,{@code Math.round} 后 {@link AttributeAxis#bandIndex}
 * (Felix 2026-11-15 裁定的服务端口径,与 {@code TurnPromptBuilder.currentBandBlock} 注入当前档同一口径),不写死任何数字。
 * 一回合连跨两档也只记一行;持平、下降不记。
 *
 * <p><b>生效范围</b>:只有单体 {@code life_sim}。判定写的是 archetype id,不是「一生制家族」——
 * 《动物人生》不登记(ADR-040 决策 1)。
 *
 * <p><b>这一行 issue 属于「服务端判定的落盘标记」</b>(见 {@code Engine.recordIssue} 的 javadoc):
 * 不进提示词(issues 不在 {@code Engine.snapshot()} 里,{@code TurnPromptBuilder} 也不认这个前缀),
 * 不含 ADR-033 统计按子串计数的「ADR-010 gate」「收束下限钳制」。
 */
final class XunTiesMarker {

	static final String ARCHETYPE = "life_sim";
	static final String AXIS = "ties";

	private XunTiesMarker() {
	}

	/** 升档那一回合记进引擎 issues 的前缀(后接「 旧→新」)。解析时匹配「前缀 + 空格」,T1 不会误配 T12。 */
	static String xunTiesUpIssuePrefix(int turn) {
		return "T" + turn + " 牵挂升档";
	}

	/** 本局是单体《寻常》时返回它的 {@code ties} 轴,否则 {@code null}(其余世界不记、不读)。 */
	static AttributeAxis tiesAxis(Engine engine, ArchetypeRegistry registry) {
		if (engine.world().path("archetypes").size() != 1
				|| !ARCHETYPE.equals(engine.world().path("archetypes").path(0).asString(""))) {
			return null;
		}
		for (AttributeAxis a : registry.meta(ARCHETYPE).attributes()) {
			if (AXIS.equals(a.key())) {
				return a;
			}
		}
		return null;
	}

	/**
	 * 落账之后调用({@code before} = 落账前的 {@code ties},{@code axis} = {@link #tiesAxis} 的结果)。
	 * 回合号 = 落账后的 {@code engine.turn()},即刚落地的那一回合({@code Engine.apply} 第一步就 {@code turn += 1})。
	 */
	static void mark(Engine engine, AttributeAxis axis, Double before) {
		if (axis == null || before == null) {
			return;
		}
		Double after = engine.attributes().get(AXIS);
		if (after == null) {
			return;
		}
		int from = axis.bandIndex((int) Math.round(before));
		int to = axis.bandIndex((int) Math.round(after));
		if (from >= 0 && to > from) {
			engine.recordIssue(xunTiesUpIssuePrefix(engine.turn()) + " " + fmt(before) + "→" + fmt(after));
		}
	}

	/**
	 * 从 issues 解析出被标记的回合号:升序、去重;只认本前缀。行首须是「T&lt;数字&gt; 」,
	 * 且整行以 {@code xunTiesUpIssuePrefix(n) + " "} 开头。不读数值、不重新判档。
	 */
	static List<Integer> turns(List<String> issues) {
		TreeSet<Integer> out = new TreeSet<>();
		for (String issue : issues) {
			if (!issue.startsWith("T")) {
				continue;
			}
			int space = issue.indexOf(' ');
			if (space <= 1) {
				continue;
			}
			int n;
			try {
				n = Integer.parseInt(issue.substring(1, space));
			} catch (NumberFormatException e) {
				continue;
			}
			if (issue.startsWith(xunTiesUpIssuePrefix(n) + " ")) {
				out.add(n);
			}
		}
		return new ArrayList<>(out);
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}
}
