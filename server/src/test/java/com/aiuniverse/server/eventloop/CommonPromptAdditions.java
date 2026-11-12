package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-037 新增进骨架的公共内容(测试侧逐字常量,Felix 2026-11-12 原话一字不改)+ golden 比对用的<b>锚定剔除</b>。
 *
 * <p><b>为什么是锚定剔除而不是重录金样本</b>(ADR-037 决策 5(c)):三组金样本各有出处({@code 3e72099} /
 * Slice D 之前 / {@code 583abc9}),重录会抹掉出处。剔除后再逐字节比原金样本 = 「只多这几行」成为常驻断言。
 *
 * <p><b>为什么必须带锚</b>(Felix 2026-11-12):用「前一行 + 新增内容」整串替换回「前一行」,并断言整串<b>恰好 1 次</b>。
 * 若在全文任意位置删新增行,新增内容挪了位置也能被删干净、golden 照绿 —— 位置就不受守护了。
 *
 * <p>⚠️ 剔除清单会随骨架改动累积(ADR-037 已知代价 10):<b>超过两层就改为重录</b>,出处改记为当次实现 commit。本类是第一层。
 */
public final class CommonPromptAdditions {

	private CommonPromptAdditions() {
	}

	/** tone 定义(ADR-037 决策 1),world-gen 两份骨架里作为 {@code - world:{…}} 的子条目。 */
	public static final String TONE_LINE =
			"world.tone 显示在游戏标题下面,写成一句让玩家想进入这个世界的介绍,从玩家的处境说起;不得写成写作要求、风格说明或氛围词清单。";

	/** 公共现实边界(ADR-037 决策 2),三份骨架 + 两份 md 逐字相同。 */
	public static final String BOUNDARY_BLOCK = """
			【现实边界 · 硬约束】
			不写政治隐喻、不借设定影射现实政治事件或人物;不出现现实品牌和商号;不点名现实在世人物。""";

	/** 边界块的锚:【绝境的写法】块末行。 */
	static final String BOUNDARY_ANCHOR = "不进入「具体怎么做」。";

	/** tone 行的锚:单体 world-gen 骨架的 world 行。 */
	static final String TONE_ANCHOR_SINGLE =
			"- world:{ title, background, tone 用中文;dangerLevel ∈ {low,medium,high,extreme} 取种子给定值 }";

	/** tone 行的锚:融合 world-gen 骨架的 world 行。 */
	static final String TONE_ANCHOR_FUSION =
			"- world:{ title, background, tone 用中文;dangerLevel ∈ {low,medium,high,extreme} }";

	/** 回合 prompt:剔掉锚定的边界块。{@code label} 只用于失败信息(哪个世界 / 哪个回合)。 */
	public static String stripTurn(String prompt, String label) {
		return anchoredStrip(prompt, BOUNDARY_ANCHOR, "\n\n" + BOUNDARY_BLOCK, label);
	}

	/** world-gen prompt:剔掉锚定的 tone 子条目与边界块。 */
	public static String stripWorldGen(String prompt, boolean fusion, String label) {
		String p = anchoredStrip(prompt, fusion ? TONE_ANCHOR_FUSION : TONE_ANCHOR_SINGLE, "\n  - " + TONE_LINE, label);
		return anchoredStrip(p, BOUNDARY_ANCHOR, "\n\n" + BOUNDARY_BLOCK, label);
	}

	private static String anchoredStrip(String prompt, String anchor, String added, String label) {
		String whole = anchor + added;
		assertThat(prompt.split(java.util.regex.Pattern.quote(whole), -1).length - 1)
				.as("[%s]「前一行 + 新增」整串须在 prompt 里恰好出现 1 次(ADR-037 决策 5(c) 锚定):%s", label, added.strip())
				.isEqualTo(1);
		return prompt.replace(whole, anchor);
	}
}
