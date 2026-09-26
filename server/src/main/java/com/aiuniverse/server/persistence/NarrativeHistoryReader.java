package com.aiuniverse.server.persistence;

import java.util.List;

/**
 * 叙事历史的只读接缝(ADR-025 刀 2)。与 {@link SessionStore} 分开、互不依赖 —— {@code SessionStore} 接口不改。
 *
 * <ul>
 *   <li>{@code pg} profile:{@link JdbcNarrativeHistoryReader},<b>只读库、不混内存</b>
 *       (内存可能比库多一回合,ADR-025「内存 N+1 / 盘 N」;历史只报库里有的);</li>
 *   <li>其它 profile:{@link UnavailableHistoryReader},恒为 {@link Unavailable}
 *       (已决 4:<b>不</b>拿内存里那 ≤4 条冒充历史)。</li>
 * </ul>
 *
 * <p>结果是封闭的四种,调用方({@code GameController})必须逐一回答,不许有「没想到的那一种」。
 */
public interface NarrativeHistoryReader {

	/**
	 * 每页覆盖的<b>回合号</b>个数(不是事件条数),服务端写死,客户端不能指定。
	 *
	 * <p>⚠️ 按回合号区间切页而不是按条数 / offset,理由:
	 * <ol>
	 *   <li><b>空洞判定确定</b>:一页的期望范围只由 {@code afterTurn} 决定,不随「这段里缺了几条」漂移 ——
	 *       按条数切,一段写失败空洞会让本页多吞几个回合号,空洞落在哪一页就取决于它前面缺了多少;</li>
	 *   <li><b>翻页期间局面推进,不重不漏</b>:游标是回合号,新回合只会落在更大的回合号上,
	 *       不会把已翻过的页往后挤(offset 做不到这一点)。</li>
	 * </ol>
	 * 需要分页的前提:一局的回合数<b>没有硬上界</b>(LifeStageTables / TurnPromptBuilder 明写「不写死回合数上限」,
	 * ADR-020 刀 4 实测一局 200 回合未收束)。
	 */
	int PAGE_TURNS = 100;

	/**
	 * @param afterTurn 游标:{@code null} = 首页 [0, 99];否则本页 = (afterTurn, afterTurn+100]。
	 *                  调用方保证非负(非法值在 web 层 400)。
	 */
	Result read(String saveId, Integer afterTurn);

	/** 读历史的四种结局。 */
	sealed interface Result permits Found, NotFound, Unavailable, Failed {
	}

	/** 找到该局,返回一页。 */
	record Found(HistoryPage page) implements Result {
	}

	/** 库里没有这个 saveId 的行(存在性只看 game_session,不看内存)。 */
	record NotFound() implements Result {
	}

	/** 本环境没有历史存储(非 pg profile)。 */
	record Unavailable() implements Result {
	}

	/** 读库失败(含超时)。原始异常只进日志,不出网。 */
	record Failed() implements Result {
	}

	/**
	 * 一页历史。{@code fromTurn}/{@code toTurn} 是本页的<b>名义区间</b>(闭区间,由游标决定);
	 * 实际判定范围 = 名义区间 ∩ [0, sessionTurn]。{@code nextAfterTurn} 为 null = 已到 sessionTurn,没有下一页。
	 */
	record HistoryPage(String saveId, String source, String status, int sessionTurn, int fromTurn, int toTurn,
			Integer nextAfterTurn, List<HistoryEntry> entries) {
	}

	/** 页内条目:按回合升序,事件与缺口交错排列(缺口在它实际所在的位置)。 */
	sealed interface HistoryEntry permits EventEntry, GapEntry {
		String kind();
	}

	/** 库里有的一回合:只有玩家本来就看过的东西(决策 1)。turn 0 = 开场叙事,playerAction 为 null。 */
	record EventEntry(String kind, int turn, String narrative, String playerAction) implements HistoryEntry {
		public EventEntry(int turn, String narrative, String playerAction) {
			this("event", turn, narrative, playerAction);
		}
	}

	/**
	 * 一段连续缺失的回合,<b>只标回合号,不带任何内容</b>(不伪造)。{@code reason} 两值,不得合并:
	 * <ul>
	 *   <li>{@link #BEFORE_RECORDING}:导入档第一条事件之前 —— 那些回合从来没有机会被记录,不是故障;</li>
	 *   <li>{@link #WRITE_FAILED}:本该记录而因故障没记上。</li>
	 * </ul>
	 */
	record GapEntry(String kind, String reason, int fromTurn, int toTurn) implements HistoryEntry {
		public static final String BEFORE_RECORDING = "before_recording";
		public static final String WRITE_FAILED = "write_failed";

		public GapEntry(String reason, int fromTurn, int toTurn) {
			this("gap", reason, fromTurn, toTurn);
		}
	}
}
