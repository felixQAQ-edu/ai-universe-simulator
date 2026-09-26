package com.aiuniverse.server.persistence;

import java.util.ArrayList;
import java.util.List;

import com.aiuniverse.server.persistence.NarrativeHistoryReader.EventEntry;
import com.aiuniverse.server.persistence.NarrativeHistoryReader.GapEntry;
import com.aiuniverse.server.persistence.NarrativeHistoryReader.HistoryEntry;
import com.aiuniverse.server.persistence.NarrativeHistoryReader.HistoryPage;

/**
 * 一页历史的组装(纯函数,无 IO):页区间 + 两种缺口的判定(ADR-025 决策 2 缺口表)。
 *
 * <p>缺口判定<b>只依据来源标记</b>({@code game_session.source}),不按「有没有 turn 0」推断 ——
 * 原生局若 create 那次 persist 失败就没有 turn 0,推断会把它的写失败空洞说成「此前未被记录」,
 * <b>把故障说成了正常</b>。
 */
final class HistoryPages {

	static final String NATIVE = "native";
	static final String IMPORT = "import";

	private HistoryPages() {
	}

	/**
	 * 本页名义区间下界(含)。首页从 turn 0 开始。
	 *
	 * <p>{@code afterTurn} 必须在 [0, Integer.MAX_VALUE) 内:入口 {@code GameController.parseCursor} 已把
	 * MAX_VALUE 判为 {@code invalid_after_turn}(它之后的回合在 int 里不可表示,照算会溢出成负数)。
	 * 这里再抛一次,是为了让绕过入口的调用方响而不是静默拿到负回合号。
	 */
	static int fromTurn(Integer afterTurn) {
		if (afterTurn == null) {
			return 0;
		}
		if (afterTurn < 0 || afterTurn == Integer.MAX_VALUE) {
			throw new IllegalArgumentException("afterTurn 越界:" + afterTurn);
		}
		return afterTurn + 1;
	}

	/** 本页名义区间上界(含)。溢出钳到 Integer.MAX_VALUE。 */
	static int toTurn(Integer afterTurn) {
		long hi = (long) fromTurn(afterTurn) + NarrativeHistoryReader.PAGE_TURNS - 1;
		return (int) Math.min(hi, Integer.MAX_VALUE);
	}

	/**
	 * @param events         本页区间内库里有的事件(任意顺序;超出区间或超出 sessionTurn 的被忽略)
	 * @param firstEventTurn 该局<b>全部</b>事件里最小的 turn(不是本页的);无事件为 null。只对导入档有意义。
	 */
	static HistoryPage assemble(String saveId, String source, String status, int sessionTurn, Integer afterTurn,
			List<EventEntry> events, Integer firstEventTurn) {
		if (!NATIVE.equals(source) && !IMPORT.equals(source)) {
			throw new IllegalStateException("未知来源标记:" + source);
		}
		int from = fromTurn(afterTurn);
		int to = toTurn(afterTurn);
		int effectiveTo = Math.min(to, sessionTurn);
		EventEntry[] byOffset = new EventEntry[Math.max(0, effectiveTo - from + 1)];
		for (EventEntry e : events) {
			if (e.turn() >= from && e.turn() <= effectiveTo) {
				byOffset[e.turn() - from] = e;
			}
		}
		List<HistoryEntry> entries = new ArrayList<>();
		int gapFrom = -1;
		String gapReason = null;
		for (int t = from; t <= effectiveTo; t++) {
			EventEntry e = byOffset[t - from];
			String reason = e != null ? null : gapReason(source, t, firstEventTurn);
			if (gapReason != null && !gapReason.equals(reason)) {
				entries.add(new GapEntry(gapReason, gapFrom, t - 1));
				gapReason = null;
			}
			if (e != null) {
				entries.add(e);
			} else if (gapReason == null) {
				gapReason = reason;
				gapFrom = t;
			}
		}
		if (gapReason != null) {
			entries.add(new GapEntry(gapReason, gapFrom, effectiveTo));
		}
		Integer next = to < sessionTurn ? to : null;
		return new HistoryPage(saveId, source, status, sessionTurn, from, to, next, List.copyOf(entries));
	}

	/**
	 * 缺失回合的性质:原生局一律写失败空洞(含缺失的 turn 0);导入档第一条事件之前(或一条都没有)=
	 * 记录开始之前,之后 = 写失败空洞。
	 */
	private static String gapReason(String source, int turn, Integer firstEventTurn) {
		if (IMPORT.equals(source) && (firstEventTurn == null || turn < firstEventTurn)) {
			return GapEntry.BEFORE_RECORDING;
		}
		return GapEntry.WRITE_FAILED;
	}
}
