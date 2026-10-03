package com.aiuniverse.server.eventloop;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxScene.Situation;
import com.aiuniverse.server.eventloop.BoxScene.Table;

/**
 * 《动物人生》新局逐字句排窗(ADR-029):金属声句组与床脚句按回合给出「本回合可用原句 + 说明」,
 * 取代新局回合主干的第 (6) 条(旧局第 (6) 条原样,不经过这里)。
 *
 * <p><b>确定性纯函数</b>:{@link #judge} 只读会话事实(回合号、是否旧局、结算结果、处境、本回合拍号、局是否已结束),
 * 不读模型输出,不改任何状态。两条序列互不依赖。{@link #render} 把判定结果渲染成注入文字。
 *
 * <p><b>文字真理源是 ADR-029 §4(Felix 2026-10-03 定稿)</b>;本文件逐字照录,不在这里改措辞。
 * 拍号取自 {@link BoxSceneTurn.Plan#beatId()}(由局面层带出),不在本函数里按回合号反推。
 *
 * <p><b>保证范围</b>(ADR-029 §6):这里只能确定 prompt 提供哪些句子;流式正文是否遵守、
 * 「抬头落空先于伸手被打」的实际先后,不由本机制保证。
 */
final class VerbatimWindows {

	private VerbatimWindows() {
	}

	/** 金属声句组窗口(ADR-029 §3.1 / §4.3)。 */
	enum Metal { OLD_HOME, BOX, A1, A2, LEFT_AFTERMATH, T18, T30, T45, OTHER }

	/** 床脚句窗口(ADR-029 §3.1 / §4.4)。 */
	enum BedFoot { ALLOW, EMPTY_HOME, NEW_HOME_OR_OUTSIDE, OTHER }

	/** 本回合的两个窗口。 */
	record Windows(Metal metal, BedFoot bedFoot) {
	}

	// ── 原句(取自第 (6) 条原文)────────────────────────────────────────

	static final String S_METAL = "「楼道里有金属碰金属的声音。」";
	static final String S_LOOK_UP = "「你抬起头。」";
	static final String S_OTHER_DOOR = "「是别的门。」";
	static final String S_HEAD_DOWN = "「你把头放下去。」";
	static final String S_NO = "「不是。」";
	static final String S_LOOK_UP_A_BIT = "「你抬了一下头。」";
	static final String S_BED_FOOT = "「你去床脚那块地方趴下。」";
	/** 床脚句进「本回合可用原句」时连同第 (6) 条的括注一起给(ADR-029 §4.4)。 */
	private static final String S_BED_FOOT_LINE = S_BED_FOOT + "(不写「回到」——那暗示归属;只写「去」)";

	/** 七句原句(带句号的完整字符串,不含引号)。 */
	static final List<String> SEVEN = List.of("楼道里有金属碰金属的声音。", "你去床脚那块地方趴下。", "你抬起头。",
			"是别的门。", "你把头放下去。", "不是。", "你抬了一下头。");

	private static final Set<String> BOX_BEATS = Set.of("S1", "S2", "S3", "S4_HIGH", "S4_LOW");
	private static final Set<String> LEFT_AFTERMATH_BEATS = Set.of("B1", "B2", "B3");
	private static final Set<Path> LEFT_PATHS = Set.of(Path.R3A, Path.R3B, Path.R3C);

	// ── 注入文字(ADR-029 §4,逐字)──────────────────────────────────────

	static final String HEADER = "【逐字句窗口 · 本回合】仅可使用下方列出的原句；未列出的逐字句本回合不使用。"
			+ "使用时必须逐字相同，不改字，不在句后接其他文字。若下方没有原句，本回合不使用逐字句。";
	static final String KEY_BAN = "正文不许出现「钥匙」，不许出现「多年以后」。";
	static final String ORIGINALS_LEAD = "本回合可用原句：";

	private static final String FORBID_OTHER = "不使用这组逐字句，也不反复改写同一金属声情节来绕过这条禁令。";
	private static final String SKIP_SPARSE = "若某局已结束、地点不适合或当回合没有相应声音，就跳过，不追赶次数。";

	/**
	 * 判定本回合的两个窗口;旧局或局已结束 → {@code null}(不注入)。
	 *
	 * @param t         局面表(取首回合与窗口末回合;T18 = 窗口末回合 + 1,不写字面量)
	 * @param turn      正在生成的那一回合(= {@code engine.turn() + 1})
	 * @param legacy    旧局
	 * @param result    本回合编排的结算结果(编排为 null 时取存档值);未结算 = null
	 * @param situation 本回合编排的处境(离开那一回合已是 OUTSIDE;编排为 null 时取存档值)
	 * @param beatId    本回合编排所用的拍号({@link BoxSceneTurn.Plan#beatId()});没有拍 = null
	 * @param ended     局已结束
	 */
	static Windows judge(Table t, int turn, boolean legacy, Path result, Situation situation, String beatId,
			boolean ended) {
		if (legacy || ended) {
			return null;
		}
		boolean originalHome = result == null && turn < t.firstTurn();
		Metal metal;
		if (originalHome && (turn == 4 || turn == 9)) {
			metal = Metal.OLD_HOME;
		} else if (beatId != null && BOX_BEATS.contains(beatId)) {
			metal = Metal.BOX;
		} else if ("A1".equals(beatId)) {
			metal = Metal.A1;
		} else if ("A2".equals(beatId)) {
			metal = Metal.A2;
		} else if (beatId != null && LEFT_AFTERMATH_BEATS.contains(beatId)) {
			metal = Metal.LEFT_AFTERMATH;
		} else if (result != null && LEFT_PATHS.contains(result) && turn == BoxSceneTurn.windowEnd(t) + 1) {
			metal = Metal.T18;
		} else if (turn == 30) {
			metal = Metal.T30;
		} else if (turn == 45) {
			metal = Metal.T45;
		} else {
			metal = Metal.OTHER;
		}
		BedFoot bed;
		if (originalHome && (turn == 6 || turn == 10)) {
			bed = BedFoot.ALLOW;
		} else if (situation == Situation.EMPTY_HOME) {
			bed = BedFoot.EMPTY_HOME;
		} else if (situation == Situation.NEW_HOME || situation == Situation.OUTSIDE) {
			bed = BedFoot.NEW_HOME_OR_OUTSIDE;
		} else {
			bed = BedFoot.OTHER;
		}
		return new Windows(metal, bed);
	}

	/** 本回合允许的原句(带引号,按出场顺序)。 */
	static List<String> originals(Windows w) {
		List<String> out = new ArrayList<>();
		switch (w.metal()) {
			case OLD_HOME -> out.addAll(List.of(S_METAL, S_LOOK_UP));
			case A2, T18 -> out.addAll(List.of(S_METAL, S_LOOK_UP, S_OTHER_DOOR, S_HEAD_DOWN));
			case T30 -> out.add(S_NO);
			case T45 -> out.add(S_LOOK_UP_A_BIT);
			default -> {
			}
		}
		if (w.bedFoot() == BedFoot.ALLOW) {
			out.add(S_BED_FOOT);
		}
		return out;
	}

	private static List<String> metalNotes(Metal m) {
		return switch (m) {
			case OLD_HOME -> List.of(
					"可写「楼道里有金属碰金属的声音。」「你抬起头。」；若写出，旧家的门应当打开。不得提前写落空的句子。",
					"允许不等于必须写出；没有合适的声音和地点时，跳过，不补演。");
			case BOX -> List.of("禁用这组句子，不另加金属声抢纸箱局面的征兆。");
			case A1 -> List.of(FORBID_OTHER, "A1 的“咔哒”不能改写成金属声。");
			case A2 -> List.of(
					"已定征兆必须出现；允许第一次完整落空：「楼道里有金属碰金属的声音。」「你抬起头。」「是别的门。」「你把头放下去。」",
					"这组逐字句允许使用，但不要求逐字句一定出现；已定的 A2 征兆仍必须写到。"
							+ "不得因为没有使用逐字句，就省去走廊里的金属声和门没有打开这两个事实。");
			case LEFT_AFTERMATH -> List.of("禁用；保持光、叫声和门缝气味各自的重点。");
			case T18 -> List.of("若本回合使用完整落空句组，声音应来自旧家附近的楼道，旧家的门没有打开；"
					+ "若本回合没有写到合适的声音，就跳过这组句子，不补演。"
					+ "无论是否使用句组，本回合都不得安排“靠近人后被踢、被砸或伸手被打”。");
			case T30 -> List.of(
					"若当回合确实听到别处的金属声、等待落空，可用独立一句「不是。」；不得只为塞这句话临时制造声音或把动物搬到楼道。",
					SKIP_SPARSE);
			case T45 -> List.of("若当回合确实有相应声音，可用「你抬了一下头。」；不得在本回合补演此前跳过的落空级别。", SKIP_SPARSE);
			case OTHER -> List.of(FORBID_OTHER);
		};
	}

	private static List<String> bedNotes(BedFoot b) {
		return switch (b) {
			case ALLOW -> List.of("【允许】只在当回合行动确实使它走向床脚时才写；没有就跳过。");
			case EMPTY_HOME -> List.of("【禁用】`EMPTY_HOME` 的“躲回床底”是另一个动作，不能为了回收原句改写成“去床脚”。");
			case NEW_HOME_OR_OUTSIDE -> List.of("【禁用】`NEW_HOME` 和 `OUTSIDE` 不能凭空出现旧屋床脚。");
			case OTHER -> List.of();
		};
	}

	/** 注入文字;{@code null}(旧局 / 局已结束)→ 空串。不带首尾换行。 */
	static String render(Windows w) {
		if (w == null) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		lines.add(HEADER);
		lines.add(KEY_BAN);
		List<String> orig = originals(w);
		if (!orig.isEmpty()) {
			lines.add(ORIGINALS_LEAD);
			for (String o : orig) {
				lines.add("    " + (o.equals(S_BED_FOOT) ? S_BED_FOOT_LINE : o));
			}
		}
		lines.addAll(metalNotes(w.metal()));
		lines.addAll(bedNotes(w.bedFoot()));
		return String.join("\n", lines);
	}
}
