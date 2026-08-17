package com.aiuniverse.server.archetype;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一生制<b>族层</b>(ADR-021 刀 1):族级写作原则的<b>唯一存放处</b>。
 *
 * <p><b>为什么需要这一层</b>(ADR-021 立字一):ADR-020 立字二只说了「族级可复用的是原则」,
 * 却没给原则一个存放位置。勘察实测的后果是<b>一个世界、三份拷贝</b>——三条族级原则
 * (不显示岁数 / 重要的事不给专门回合 / 最后一回合是活着的)全在 {@code life_sim} 的世界层槽里,
 * 骨架零承载;其中「最后一回合是活着的」<b>已经在两个槽里各写了一遍</b>。
 * ADR-008「多个世界共用的一律回骨架」这条规矩其实早就写了,<b>只是没有地方可以「回」</b>——
 * 骨架是全世界共用的(动物不能读到人的年龄段)、世界层是 per-world 的,族级的东西两头都不属于。
 * 本类就是那个「可以回的地方」。
 *
 * <p><b>形态是「具名片段 + 原位引用」,不是「顺序拼接」</b>(ADR-021 立字二,刀 1 就地订正):
 * ADR 原稿写的是「拼接顺序 骨架 → 族 → 世界」,那对<b>整块附加</b>成立(ADR-014 / ADR-020 的两个槽),
 * 但对<b>穿插引用</b>不成立——三条原则实测分别落在世界层槽的<b>槽头 / 中段 / 后段</b>
 * (回合槽第 10 / 22 / 48 行,共 64 行),提到一个「渲染在世界层之前」的族层<b>必然重排文本</b>,
 * 与刀 1 的逐字节零回归不可同时满足。故改为:<b>族层供具名片段,世界层在原位引用。</b>
 *
 * <p><b>自动性用 lockstep 补回</b>:顺序拼接的好处是新世界自动拿到族层,而原位引用要靠模板主动引。
 * 代偿 = {@code LifetimeFamilyLockstepTest}:<b>族成员的世界层模板漏引任一族级片段 → 变红</b>。
 * 这比自动拼接强的一点是——<b>漏引会响,而错序不会</b>:自动拼接下,新世界若需要不同的插入位置,
 * 根本发现不了(它只会默默地把族层堆在最前面)。
 *
 * <p><b>⚠️ 片段带「换行槽」的理由</b>(见 {@link #aliveAtTheEnd}):同一句话在两个槽里的
 * <b>断行位置不同</b>——回合侧断在「一点;」之后、world-gen 侧断在「耗到零,」之后,续行缩进也不同
 * (4 空格 vs 2 空格)。**words 只存一份、wrapping 由各引用点自供**,合并才做得到逐字节无损;
 * 若强行统一断行,《寻常》的 prompt 会出现纯空白差异,而 ADR-018 §4.5.1 已立字
 * 「除空格外不变不算通过」。
 *
 * <p><b>本类不含任何世界专有内容</b>:切分表、per-world 写作标准、结局池、出口措辞一律留世界层。
 * 真理源 = {@code docs/lifetime-family-writing-standards.md}(<b>两处不得漂移:先改文件再同步本类,
 * 不得反向</b>,由 lockstep 守护)。
 */
public final class LifetimeFamily {

	private LifetimeFamily() {
	}

	/** 族 id(目前只有这一个族;第二个族出现时本类应先抽出族无关的骨架)。 */
	public static final String LIFETIME = "lifetime";

	/**
	 * 世界 → 族 映射。
	 *
	 * <p><b>刀 2 已收编(ADR-021 裁定三兑现)</b>:原 {@code EventLoopService.LIFETIME_EXIT_ARCHETYPES}
	 * 与本表同集同义(那张的注释自陈是「一生制世界」),已合并到这里 —— <b>「谁是一生制世界」现在只有一处答案</b>。
	 * {@code LifeStageTables} 的登记面在类加载时对拍本表,少一张时钟表即抛。
	 *
	 * <p><b>⚠️ 仍与「有没有登记内容」的隐式表刻意分开</b>({@code TURN_DIRECTIVES} /
	 * {@code WORLD_GEN_DIRECTIVES} 的 key):那两张忘了登记的后果是「没有这段指令」(可见的降级),
	 * 本表忘了登记的后果是<b>功能静默不生效</b> —— 两者性质不同,不合并。
	 */
	private static final Map<String, String> WORLD_FAMILY = Map.of("life_sim", LIFETIME);

	/** 该 archetype 是否为一生制族成员(族层片段的引用义务由 lockstep 据此清单强制)。 */
	public static boolean isLifetime(String archetype) {
		return LIFETIME.equals(WORLD_FAMILY.get(archetype));
	}

	/** 一生制族的全部成员(lockstep 遍历用)。 */
	public static Set<String> lifetimeMembers() {
		return WORLD_FAMILY.entrySet().stream()
				.filter(e -> LIFETIME.equals(e.getValue()))
				.map(Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	// ── 族级片段(words 各存一份;引用点只决定放在哪、怎么断行)────────────────────────

	/**
	 * 族级原则 ①【不显示岁数】。
	 *
	 * <p>对族内每个世界都成立:动物、校园、穿越同样不该向玩家报岁数——年龄是<b>设计用的标注</b>,
	 * 时间流逝只靠具体的物与事透出来。故属族层而非世界层。
	 *
	 * <p>续行缩进(4 空格)内嵌在片段里:本片段目前只有一个引用点,不需要换行槽;
	 * 若将来出现缩进不同的引用点,照 {@link #aliveAtTheEnd} 的办法开槽。
	 */
	public static final String NO_AGE_DISPLAY =
			"⚠️【绝不显示岁数】年龄是设计用的标注,玩家看不见——不要写「三十五岁那年」「你 42 岁」,\n"
					+ "    也不要写回合序号或时间跨度说明。时间流逝只靠具体的物与事透出来。";

	/**
	 * 族级原则 ②【重要的事不给专门回合】。
	 *
	 * <p><b>⚠️ 片段刻意不含编号「5.」</b>:编号属世界层的「措辞铁律六条」那一块,由模板自己写;
	 * 族层只存这一条的<b>词</b>。这样六条的结构、顺序、编号<b>一个字都没动</b>(ADR-021 裁定二:
	 * 铁律六条不拆、本刀不动),变的只是第 5 条的字从哪里来。
	 *
	 * <p><b>挂账(裁定二)</b>:铁律六条里的第 1 / 2 / 3 / 6 条(态度词 / 年代刻度 / 最常见做法 /
	 * 留白优先)<b>对四个既有世界也成立</b>——那它们就不是族级的,是<b>全局的</b>,应进骨架。
	 * 那是<b>另一刀</b>,且收益更大(四个既有世界都受益)。本刀不做,记在这里免得被当成「族层没做干净」。
	 */
	public static final String NO_DEDICATED_TURN =
			"重要的事不给专门回合。\n"
					+ "       挤进一个正在讲别的事的回合里。";

	/**
	 * 族级原则 ③【最后一回合是活着的】——<b>本刀合并的那两份拷贝</b>。
	 *
	 * <p>合并前:{@code WorldGenPromptBuilder} 与 {@code TurnPromptBuilder} 各写了一遍同一句话
	 * (勘察实测逐字相同)。合并后两处都引用本片段,<b>词只剩一份</b>。
	 *
	 * <p><b>两个换行槽</b>:两个引用点的断行位置不同,故把「哪里断行」做成参数而不是把词抄两遍。
	 * <ul>
	 *   <li>回合侧:{@code aliveAtTheEnd("", "\n    ")} → 断在「一点;」之后,续行 4 空格;</li>
	 *   <li>world-gen 侧:{@code aliveAtTheEnd("\n  ", "")} → 断在「耗到零,」之后,续行 2 空格。</li>
	 * </ul>
	 * 传空串 = 该处不断行。<b>词一个字不改,排版各自为政</b>——这是「一份拷贝」与「逐字节零回归」
	 * 能同时成立的唯一办法。
	 *
	 * @param wrapAfterFirst  第一句读点后的断行(空串 = 不断)
	 * @param wrapAfterSecond 第二句分号后的断行(空串 = 不断)
	 */
	public static String aliveAtTheEnd(String wrapAfterFirst, String wrapAfterSecond) {
		return ALIVE_AT_THE_END.formatted(wrapAfterFirst, wrapAfterSecond);
	}

	/** {@link #aliveAtTheEnd} 的词本体(带两个换行槽);lockstep 归一化后与真理源文件对拍。 */
	static final String ALIVE_AT_THE_END =
			"老死不是生命力耗到零,%1$s是故事走完了而你还剩一点;%2$s耗到零的是意外与病,那才是早逝。";

	/**
	 * 族级原则 ④【一生制时钟契约】——<b>ADR-021 刀 2 上提(路 2),族层第四条片段</b>。
	 *
	 * <p><b>为什么刀 1 没上提、刀 2 上提了</b>:刀 1 收窄的理由是「它的词全部来自人类专有的
	 * {@code LifeStage}」。刀 2 参数化之后<b>数据侧</b>(阶段名 / 区间 / 推进句 / 收敛窗口)不再人类专有;
	 * 剩下的两处人类专有是<b>语言选择</b>——人称(「他」vs 动物的「它」)与终点词(「寿终」vs「老死」)。
	 * 故按 Felix 2026-08-15 裁定三走路 2:<b>上提并再开两个词槽</b>。
	 * (否决路 1「不上提」:每个一生制世界各写一句时钟文本,<b>直接回到刀 1 要治的病</b>;
	 * 否决路 3「只提无人称的几句」:拆一句话为两段,最碎。)
	 *
	 * <p><b>⚠️ 由此升一条族层设计口径(ADR-021 立字二补记)</b>:
	 * <b>族级片段普遍需要「词槽」—— 族层持有句式与逻辑,per-world 差异靠槽注入。</b>
	 * 证据是同一形状<b>第二次</b>出现:{@link #aliveAtTheEnd} 的换行槽 + 本条的人称 / 终点词槽。
	 * 两次都不是临时补丁,而是这套抽象的<b>固有形态</b>——
	 * 族层要装的从来不是「一模一样的字」,是<b>一模一样的句式与逻辑</b>。
	 *
	 * @param nextTurn      正在生成的那一回合(= {@code engine.turn() + 1})
	 * @param pronoun       人称词槽(per-world,取自时钟表)
	 * @param label         当前阶段名
	 * @param spanNote      当前阶段的设计标注(⚠️ 绝不显示给玩家)
	 * @param advanceClause 兑现语义的推进要求
	 * @param convergeFrom  收敛窗口下界
	 * @param convergeTo    收敛窗口上界
	 * @param terminalWord  终点词槽(per-world,取自时钟表)
	 */
	public static String clockContract(int nextTurn, String pronoun, String label, String spanNote,
			String advanceClause, int convergeFrom, int convergeTo, String terminalWord) {
		return CLOCK_CONTRACT.formatted(nextTurn, pronoun, label, spanNote, advanceClause,
				convergeFrom, convergeTo, terminalWord);
	}

	/** {@link #clockContract} 的词本体(带 8 个槽,其中 %2$s 人称 / %8$s 终点词是 per-world 词槽)。 */
	static final String CLOCK_CONTRACT =
			"""
			【本回合的时间坐标 · 一生制时钟】现在是第 %1$d 回合,%2$s正处于【%3$s】(设计标注:%4$s)。
			    ⚠️【本回合必须推进时间,这是硬要求不是风格建议】%5$s——本回合结束时,
			    叙事必须已经落在一个【比上一回合更晚】的时间点上。
			    【绝不允许】两个回合停在同一天、同一顿饭、同一次谈话里把一件事说完;
			    上一回合正在发生的事,本回合应当【已经过去了】,写的是它之后的日子。
			    全局预期在第 %6$d-%7$d 回合走到%8$s:每往后一个回合,都要比上一个更靠近一生的尽头。\
			""";

	/**
	 * 全部族级片段的<b>词</b>(去掉换行槽与排版),供 lockstep 两个方向使用:
	 * (1) 与真理源文件对拍,防两处漂移;(2) 遍历族成员的 prompt,<b>漏引任一条即变红</b>。
	 */
	public static List<String> fragmentWords() {
		return List.of(NO_AGE_DISPLAY, NO_DEDICATED_TURN, ALIVE_AT_THE_END, CLOCK_CONTRACT);
	}
}
