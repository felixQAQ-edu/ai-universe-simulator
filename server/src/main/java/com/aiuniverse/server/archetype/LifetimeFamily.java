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
	 * <p><b>⚠️ 过渡形态,刀 2 收编</b>(ADR-021 裁定三):这是仓库里<b>第四张</b>同类硬编码世界名表
	 * (前三张:{@code TURN_DIRECTIVES} / {@code WORLD_GEN_DIRECTIVES} 的 key、
	 * {@code EventLoopService.LIFETIME_EXIT_ARCHETYPES}),与刀 2「解硬编码」的方向<b>相反</b>。
	 * 本刀仍选它,是因为另两条路都更差:动 {@code ArchetypeMeta} 加 family 字段会越过刀 1
	 * 「只搬位置不改内容」的红线;由「有没有登记族层内容」隐式派生则退回 per-world 拷贝、违背本刀目的。
	 * <b>刀 2 一并收编</b>——届时与 {@code LIFETIME_EXIT_ARCHETYPES} 合并,或一起提到 {@code ArchetypeMeta}。
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
	 * 全部族级片段的<b>词</b>(去掉换行槽与排版),供 lockstep 两个方向使用:
	 * (1) 与真理源文件对拍,防两处漂移;(2) 遍历族成员的 prompt,<b>漏引任一条即变红</b>。
	 */
	public static List<String> fragmentWords() {
		return List.of(NO_AGE_DISPLAY, NO_DEDICATED_TURN, ALIVE_AT_THE_END);
	}
}
