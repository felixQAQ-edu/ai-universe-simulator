package com.aiuniverse.server.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aiuniverse.server.eventloop.GameSession;

/**
 * {@link TurnLedger} 的 PostgreSQL 实现(ADR-026 刀 2)。<b>只在 {@code pg} profile 装配</b>。
 *
 * <ul>
 *   <li>{@link #accept}:一段短事务 {@code INSERT … PROCESSING RETURNING id},行 id 挂到会话上
 *       ({@link GameSession#setPendingTurnRecord});事务在本方法返回前提交 —— <b>模型调用不在任何事务里</b>。</li>
 *   <li>{@link #failed}:另一段短事务把本回合的受理行标 {@code FAILED}(只在 {@code TurnStateMachine}
 *       未落地分支被调用,ADR-027 决策 5)。</li>
 *   <li>落地({@code SUCCEEDED} / {@code DEGRADED})<b>不在本类</b>:它并入 {@link JdbcSessionStore#persist}
 *       那一次事务(ADR-026 决策 2),SQL 常量放在这里只为两处共用一份。</li>
 *   <li>启动收口({@code PROCESSING → INTERRUPTED})由 {@link JdbcSessionStore#loadAll} 在回载之后执行
 *       (ADR-026 决策 4)。</li>
 * </ul>
 *
 * <p><b>best-effort,绝不抛</b>(已决 C = 照跑):受理写失败 → 会话上不挂行、ERROR、回合照跑;
 * 落地时无行可关,不报错。受理记录因此是<b>下界</b>(ADR-026 已知代价 5)。
 * 超时沿用 {@code aiuniverse.session.pg.tx-timeout-seconds}(ADR-025 决策 3):这两段事务都在准入名额之内。
 */
@Service
@Profile("pg")
public class JdbcTurnLedger implements TurnLedger {

	private static final Logger log = LoggerFactory.getLogger(JdbcTurnLedger.class);

	static final String INSERT_ACCEPTED = "INSERT INTO turn_request (save_id, base_turn, target_turn, action_id, status) "
			+ "VALUES (?, ?, ?, ?, 'PROCESSING') RETURNING id";

	/** 失败 / 落地共用:只关仍是 PROCESSING 的行(不覆盖别的结论)。 */
	static final String CLOSE_PROCESSING = "UPDATE turn_request SET status = ?, finished_at = now() "
			+ "WHERE id = ? AND status = 'PROCESSING'";

	/** 启动收口:本进程启动时仍是 PROCESSING 的行,都是上一个进程没来得及写下结论的。 */
	static final String INTERRUPT_ALL_PROCESSING = "UPDATE turn_request SET status = 'INTERRUPTED', finished_at = now() "
			+ "WHERE status = 'PROCESSING'";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;

	public JdbcTurnLedger(JdbcTemplate jdbc, PlatformTransactionManager txManager,
			@Value("${aiuniverse.session.pg.tx-timeout-seconds}") int txTimeoutSeconds) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(txManager);
		this.tx.setTimeout(txTimeoutSeconds);
	}

	@Override
	public void accept(GameSession session, String actionId) {
		// 先摘掉上一回合的把手:本次受理写失败时,persist 不得拿旧行去「关」。
		session.setPendingTurnRecord(null);
		int base = session.engine().turn();
		try {
			Long id = tx.execute(s -> jdbc.queryForObject(INSERT_ACCEPTED, Long.class,
					session.saveId(), base, base + 1, actionId));
			session.setPendingTurnRecord(new GameSession.TurnRecord(id, base + 1));
		} catch (Exception e) {
			log.error("[turn-ledger:pg] save={} 受理写库失败,回合照跑(本回合无受理记录):{}",
					session.saveId(), e.toString());
		}
	}

	@Override
	public void failed(GameSession session) {
		GameSession.TurnRecord record = session.pendingTurnRecord();
		if (record == null) {
			return;
		}
		try {
			tx.executeWithoutResult(s -> jdbc.update(CLOSE_PROCESSING, "FAILED", record.id()));
		} catch (Exception e) {
			log.error("[turn-ledger:pg] save={} 受理行 id={} 标 FAILED 失败(行留在 PROCESSING,重启时收口为 INTERRUPTED):{}",
					session.saveId(), record.id(), e.toString());
		} finally {
			session.setPendingTurnRecord(null);
		}
	}
}
