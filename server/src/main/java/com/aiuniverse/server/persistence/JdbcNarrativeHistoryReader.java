package com.aiuniverse.server.persistence;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aiuniverse.server.persistence.NarrativeHistoryReader.EventEntry;

/**
 * 叙事历史的 PostgreSQL 读取(ADR-025 刀 2),只在 {@code pg} profile 装配。
 *
 * <h2>消毒硬闸:永不读快照列</h2>
 * 下面三条 SQL 只碰 {@code game_event} 与 {@code game_session} 的 {@code source / turn / status} 列。
 * 快照列是视图 1 全量(含 {@code isTrue} / {@code hiddenLogic}),<b>不是给玩家看的</b>
 * (决策 1、CONTEXT §三.9)。由一条源码级断言钉住(本文件里不许出现那个列名字面量,注释也不行 —— 故本段不写它)。
 *
 * <h2>只读库,不混内存</h2>
 * 存在性、sessionTurn、事件全部来自库。内存可能比库多一回合(persist 在断流时被跳过),历史只报库里有的。
 * 推论:会话在内存里但库里没有行(create 那次 persist 失败)→ 历史回 404,直到下一次成功 persist。
 *
 * <h2>一致性</h2>
 * 同一只读事务内先读会话行、再读事件。persist 把快照与事件写在同一事务里,故读到的 sessionTurn = N 时,
 * turn ≤ N 的事件都已提交;之后提交的更大回合被 sessionTurn 截掉,不会出现「事件比会话新」的页。
 *
 * <h2>超时</h2>
 * 与 persist 同一套 pg 配置(取连接 / 事务超时 / 套接字超时,{@code application-pg.yml}),
 * 不吃驱动默认值;读库失败(含超时)→ {@link Failed},原始异常只记日志。
 */
@Service
@Profile("pg")
public class JdbcNarrativeHistoryReader implements NarrativeHistoryReader {

	private static final Logger log = LoggerFactory.getLogger(JdbcNarrativeHistoryReader.class);

	static final String SELECT_SESSION = "SELECT source, turn, status FROM game_session WHERE save_id = ?";

	static final String SELECT_EVENTS = "SELECT turn, narrative, player_action FROM game_event "
			+ "WHERE save_id = ? AND turn BETWEEN ? AND ? ORDER BY turn";

	static final String SELECT_FIRST_EVENT_TURN = "SELECT min(turn) FROM game_event WHERE save_id = ?";

	private record SessionRow(String source, int turn, String status) {
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;

	public JdbcNarrativeHistoryReader(JdbcTemplate jdbc, PlatformTransactionManager txManager,
			@Value("${aiuniverse.session.pg.tx-timeout-seconds}") int txTimeoutSeconds) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(txManager);
		this.tx.setReadOnly(true);
		this.tx.setTimeout(txTimeoutSeconds);
	}

	@Override
	public Result read(String saveId, Integer afterTurn) {
		try {
			return tx.execute(status -> {
				List<SessionRow> rows = jdbc.query(SELECT_SESSION,
						(rs, i) -> new SessionRow(rs.getString(1), rs.getInt(2), rs.getString(3)), saveId);
				if (rows.isEmpty()) {
					return new NotFound();
				}
				SessionRow s = rows.get(0);
				int from = HistoryPages.fromTurn(afterTurn);
				int to = Math.min(HistoryPages.toTurn(afterTurn), s.turn());
				List<EventEntry> events = from > to ? List.of()
						: jdbc.query(SELECT_EVENTS,
								(rs, i) -> new EventEntry(rs.getInt(1), rs.getString(2), rs.getString(3)), saveId, from,
								to);
				Integer first = HistoryPages.IMPORT.equals(s.source())
						? jdbc.queryForObject(SELECT_FIRST_EVENT_TURN, Integer.class, saveId)
						: null;
				return new Found(HistoryPages.assemble(saveId, s.source(), s.status(), s.turn(), afterTurn, events,
						first));
			});
		} catch (Exception e) {
			log.error("[history:pg] save={} 读取历史失败:{}", saveId, e.toString());
			return new Failed();
		}
	}
}
