package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-037 / ADR-046 新增进骨架的公共内容(测试侧逐字常量)+ golden 比对用的<b>锚定剔除</b>。
 * ADR-037 那两段是 Felix 2026-11-12 原话一字不改;ADR-046 那几行是 ADR-046 D4 的定稿原文。
 *
 * <p><b>为什么是锚定剔除而不是重录金样本</b>(ADR-037 决策 5(c)):三组金样本各有出处({@code 3e72099} /
 * Slice D 之前 / {@code 583abc9}),重录会抹掉出处。剔除后再逐字节比原金样本 = 「只多这几行」成为常驻断言。
 *
 * <p><b>为什么必须带锚</b>(Felix 2026-11-12):用「前一行 + 新增内容」整串替换回「前一行」,并断言整串<b>恰好 1 次</b>。
 * 若在全文任意位置删新增行,新增内容挪了位置也能被删干净、golden 照绿 —— 位置就不受守护了。
 *
 * <p>⚠️ 剔除清单会随骨架改动累积(ADR-037 已知代价 10):<b>超过两层就改为重录</b>,出处改记为当次实现 commit。
 * 第一层 = ADR-037(边界块 / tone 行);<b>第二层 = ADR-046</b>(回合骨架尾巴字段清单里的物品三行 + 《验讫》combo (6) 末尾一句)。
 * ⚠️ <b>两层已满:再改一次回合骨架,三组回合金样本就必须重录</b>(ADR-046 已知代价 3),不得再加第三层。
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

	/**
	 * ADR-046 D4:回合骨架尾巴字段清单里新增的物品三行(单体与融合共用骨架,与 {@code prompts/event-loop.md} 逐字相同,
	 * {@code TurnTailFieldsLockstepTest} 守护)。每行行首两个空格,与骨架里 {@code stateUpdate} 的子行同缩进。
	 */
	public static final String INVENTORY_LINES = String.join("\n",
			"  物品有增减(用掉、送出、丢失、获得、数量变化)时,stateUpdate 另给 \"inventory\": [\"<物品>\", …],写本回合结束后随身物品的整份清单;",
			"  没有变化就不写这个键。用尽的物品从清单里删掉,不保留空壳。选项里要用到的随身物品(服用、拿出、交出),必须是这份清单里还有的;",
			"  本回合没写 inventory 时,这份清单就是世界设定里的 character.inventory。");

	/** 物品三行的锚:骨架里 {@code stateUpdate} 那条的括注行(ADR-046 勘察 1:三组金样本里各恰好 1 次)。 */
	static final String INVENTORY_ANCHOR =
			"  (各数值轴是本回合结束后的新绝对值,0-100,按 hiddenLogic 结算,与历史不得矛盾、不得无故回升)";

	/** ADR-046 D4:《验讫》(cultivation×cthulhu)combo 附加指令 (6) 末尾新增的一句(揭示阶梯,CONTEXT §三.16 (10))。 */
	public static final String YANQI_INVENTORY_LINE = "    stateUpdate 的 inventory 清单也只用第一级词汇,物品沿用开局时的叫法。";

	/** 《验讫》那一句的锚:combo (6) 的末行。 */
	static final String YANQI_INVENTORY_ANCHOR = "    夜里的选项不出现「领药」一类,改给过渡行动(服随身的余泽丹、调息、观星、回记档室比对)。";

	/**
	 * 回合 prompt:剔掉锚定的边界块(ADR-037,第一层)与物品三行(ADR-046,第二层)。
	 * {@code label} 只用于失败信息(哪个世界 / 哪个回合)。
	 */
	public static String stripTurn(String prompt, String label) {
		String p = anchoredStrip(prompt, BOUNDARY_ANCHOR, "\n\n" + BOUNDARY_BLOCK, label);
		return anchoredStrip(p, INVENTORY_ANCHOR, "\n" + INVENTORY_LINES, label);
	}

	/** 《验讫》回合 prompt 另剔 combo (6) 末尾那一句(ADR-046 第二层;只对 cultivation×cthulhu 调用)。 */
	public static String stripYanqiTurn(String prompt, String label) {
		return anchoredStrip(prompt, YANQI_INVENTORY_ANCHOR, "\n" + YANQI_INVENTORY_LINE, label);
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
