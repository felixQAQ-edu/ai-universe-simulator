package com.aiuniverse.server.persistence;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
 * <h2>一致性:整个事务读同一个快照(REPEATABLE READ)</h2>
 * 三条语句跑在<b>同一个 REPEATABLE READ 只读事务</b>里 —— PG 在这一隔离级下整个事务只取一次快照,
 * 三条语句看到的是同一个时刻的库。一页历史的正确性<b>靠的是这一点</b>。
 *
 * <p>⚠️ 此前这里写的理由是「先读会话行、再按 sessionTurn 截断事件,故不会出现事件比会话新」——
 * <b>那条理由不成立</b>:PG 默认 READ COMMITTED 是<b>每条语句</b>一个快照。两条语句之间若有 persist 提交,
 * 截断只挡得住「比 sessionTurn 大」的回合,挡不住补齐规则回填进来的「≤ sessionTurn」的回合
 * (例:读会话时 turn 2–3 还是写失败空洞,persist 在两条语句之间补齐了它们 → 同一页里会话说有洞、事件说没洞)。
 * 导入档第一条事件的回合也会随之漂移,把「此前未被记录」与「写失败」的分界算错。
 * 只读事务在这一隔离级下不会出现序列化失败(PG 只对写冲突报 40001)。
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
		// 整个事务读同一个快照 —— 见类注释「一致性」。去掉这一行,三条语句各读各的时刻。
		this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
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
