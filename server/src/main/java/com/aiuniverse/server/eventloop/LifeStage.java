package com.aiuniverse.server.eventloop;

/**
 * 一生制时钟的<b>一个阶段</b>(ADR-021 刀 2:由 enum 常量改为 record)。
 *
 * <p><b>为什么必须从 enum 改掉</b>(立字三):enum 的 {@code values()} 是<b>类级单例</b> ——
 * 一个 JVM 里只有一份,不可能同时是人的表和动物的表。<b>这是它在语言层面就做不成 per-archetype 的
 * 根本原因,不是「写得不够优雅」</b>。改成 record 之后,「有哪些阶段」变成
 * {@link LifeStageTable} 的一个字段,每个一生制世界一张表。
 *
 * <p><b>本文件是纯机制,不含任何世界的字面量</b> —— 人类年龄段、阶段名、出口措辞一律在
 * {@link LifeStageTables} 的 per-world 表里。该边界由 {@code LifeStageParameterizationTest}
 * 的<b>源码级</b>断言守护(改结构的刀次必须有一条断言直接看结构,ADR-018 §4.13)。
 *
 * @param fromTurn      本阶段起始回合(含)
 * @param toTurn        本阶段结束回合(含)
 * @param label         玩家可见阶段名(注入用;《寻常》= 幼年 / 少年 / …)
 * @param spanNote      这一段是什么的<b>设计标注</b>(⚠️ 绝不显示给玩家,槽内有硬禁令)。
 *                      <b>ADR-021 刀 2 由 {@code ageRange} 改名(裁定四)</b>:它自陈是「设计标注」,
 *                      而动物填的是<b>处境段</b>(屋里 / 断裂 / 外面)不是年龄 ——
 *                      <b>字段名留着「age」会诱导第二张表去填年龄,那正是「毛茸茸的人」的入口</b>
 *                      (ADR-020 §1)。取 {@code spanNote} 而非 {@code designNote}:
 *                      prompt 里已经写着「设计标注」,字段要补的是「标的是<b>哪一段</b>」——
 *                      而「这一段是不是年龄」正是 per-world 的自由。
 * @param advanceClause 兑现语义的推进要求(「本回合比上一回合晚约 N 年」)
 * @param exitText      本阶段「就到这里」出口的措辞;{@code null} = 本阶段不出现该出口
 */
record LifeStage(
		int fromTurn,
		int toTurn,
		String label,
		String spanNote,
		String advanceClause,
		String exitText) {

	/** 本阶段是否提供「就到这里」出口。 */
	boolean hasExit() {
		return exitText != null;
	}
}
