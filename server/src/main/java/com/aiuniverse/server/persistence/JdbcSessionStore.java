package com.aiuniverse.server.persistence;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.eventloop.GameSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link SessionStore} 的 PostgreSQL 实现(ADR-025 刀 1)。<b>只在 {@code pg} profile 装配</b>,
 * 与 {@link FileSessionStore} 二选一、不双写;内存仍是权威,本类只替换文件层。
 *
 * <h2>persist = 一段短事务</h2>
 * <ol>
 *   <li>upsert {@code game_session}:快照 = {@link SessionDocument#encode}(与文件实现同一份文档);
 *       <b>{@code source} 只在 INSERT 分支写 {@code 'native'},ON CONFLICT 的 UPDATE 分支不碰它</b> ——
 *       来源由首次插入该行的代码路径写下,之后不改、不推断(ADR-025 决策 2)。</li>
 *   <li>补齐 {@code game_event}:同一事务内读该 saveId 已落库的最大 turn,把候选事件里 turn 更大的
 *       <b>全部</b>插入。候选 = 内存 {@code engine.log()}(≤ {@code LOG_KEEP} 条)+ 开场叙事作 turn 0
 *       (仅当会话上还带着它 —— 即本进程内开局的会话;回载的会话没有,见 {@link GameSession#openingNarrative()})。
 *       这条规则让上一次 persist 失败的回合(含失败的 turn 0)只要仍在内存里,就在下一次成功时补上。</li>
 * </ol>
 * 快照与事件同一事务:失败则两者一起回滚,库里停在上一个完整回合,<b>快照与历史不会一新一旧</b>。
 *
 * <p><b>LLM 不在事务里</b>是写入点位置的结构性后果(两个调用点都在模型流结束之后),不是本类做到了什么;
 * 本类也<b>不是</b>两段式事务 —— 没有 turn_request、没有 version 校验(ADR-025「子集」一节)。
 *
 * <h2>best-effort,绝不抛;但不阻塞要靠超时</h2>
 * 任何异常 → 回滚 → 记 ERROR → 返回(同文件实现口径,ADR-015 已知代价 5)。
 * persist 跑在准入名额之内,「不抛」不等于「不阻塞」:取连接超时 / 事务(语句)超时 / 套接字超时
 * 三者都在 {@code application-pg.yml} 里显式配置并写明依据,不吃驱动默认值(ADR-025 决策 3)。
 */
@Service
@Profile("pg")
public class JdbcSessionStore implements SessionStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcSessionStore.class);

	/** 首次插入写 'native';冲突时只更新快照三列 + updated_at,<b>source 不在 UPDATE 列表里</b>。 */
	static final String UPSERT_SESSION = "INSERT INTO game_session (save_id, snapshot, turn, status, source) "
			+ "VALUES (?, ?::jsonb, ?, ?, 'native') "
			+ "ON CONFLICT (save_id) DO UPDATE SET snapshot = EXCLUDED.snapshot, turn = EXCLUDED.turn, "
			+ "status = EXCLUDED.status, updated_at = now()";

	static final String MAX_EVENT_TURN = "SELECT max(turn) FROM game_event WHERE save_id = ?";

	static final String INSERT_EVENT = "INSERT INTO game_event (save_id, turn, narrative, player_action) "
			+ "VALUES (?, ?, ?, ?)";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final ObjectMapper mapper;
	private final ArchetypeRegistry archetypes;

	public JdbcSessionStore(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper mapper,
			ArchetypeRegistry archetypes, @Value("${aiuniverse.session.pg.tx-timeout-seconds}") int txTimeoutSeconds) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.archetypes = archetypes;
		this.tx = new TransactionTemplate(txManager);
		this.tx.setTimeout(txTimeoutSeconds);
		log.info("[session-store:pg] DB 版会话存储已启用,persist 事务超时 = {} s", txTimeoutSeconds);
	}

	// ── 写(best-effort,绝不抛)───────────────────────────────────────

	@Override
	public void persist(GameSession session) {
		String saveId = session.saveId();
		if (saveId == null) {
			log.error("[session-store:pg] saveId 为空,拒写");
			return;
		}
		try {
			ObjectNode doc = SessionDocument.encode(session, mapper);
			String snapshot = mapper.writeValueAsString(doc);
			List<Event> candidates = candidates(session);
			int turn = session.engine().turn();
			String status = session.engine().status();
			tx.executeWithoutResult(txStatus -> {
				jdbc.update(UPSERT_SESSION, saveId, snapshot, turn, status);
				Integer stored = jdbc.queryForObject(MAX_EVENT_TURN, Integer.class, saveId);
				int floor = stored == null ? -1 : stored;
				for (Event e : candidates) {
					if (e.turn() > floor) {
						jdbc.update(INSERT_EVENT, saveId, e.turn(), e.narrative(), e.playerAction());
					}
				}
			});
		} catch (Exception e) {
			log.error("[session-store:pg] save={} 落库失败,快照与事件一起回滚(局面继续活在内存;"
					+ "本回合事件若仍在内存窗口内,下一次成功落库时补上):{}", saveId, e.toString());
		}
	}

	/** 一条待落库的历史事件(与内存 log 条目同形;turn 0 = 开场叙事,无玩家动作)。 */
	record Event(int turn, String narrative, String playerAction) {
	}

	/** 候选事件:开场叙事(若会话还带着它)作 turn 0 + 内存 log 全部条目,按 turn 升序。 */
	static List<Event> candidates(GameSession session) {
		List<Event> out = new ArrayList<>();
		String opening = session.openingNarrative();
		if (opening != null) {
			out.add(new Event(0, opening, null));
		}
		for (ObjectNode entry : session.engine().log()) {
			JsonNode action = entry.get("playerAction");
			out.add(new Event(entry.path("turn").asInt(), entry.path("narrative").asString(""),
					action == null || action.isNull() ? null : action.asString()));
		}
		return out;
	}

	// ── 启动回载(单行容错)────────────────────────────────────────────

	/**
	 * 从 {@code game_session} 还原全部会话,还原走 {@link SessionDocument#decode}(与文件实现同一套校验,
	 * 拒载不半载)。与文件实现的汇总<b>不可直接比较</b>:表里没有「非存档文件」这一类(ADR-025 已知代价 1),
	 * 故只有「载入 / 拒载」两项。
	 */
	@Override
	public List<GameSession> loadAll() {
		List<GameSession> loaded = new ArrayList<>();
		int refused = 0;
		List<String[]> rows;
		try {
			rows = jdbc.query("SELECT save_id, snapshot::text FROM game_session ORDER BY save_id",
					(rs, i) -> new String[] { rs.getString(1), rs.getString(2) });
		} catch (Exception e) {
			log.error("[session-store:pg] 读取 game_session 失败(以空档启动):{}", e.toString());
			return loaded;
		}
		for (String[] row : rows) {
			try {
				loaded.add(SessionDocument.decode(row[0], mapper.readTree(row[1]), mapper, archetypes));
			} catch (Exception e) {
				refused++;
				log.warn("[session-store:pg] 存档回载失败,跳过(行保留在库里留尸检):save={} — {}", row[0], e.toString());
			}
		}
		log.info("[session-store:pg] 启动回载:载入 {} 档,{} 档拒载(见上方 WARN)", loaded.size(), refused);
		return loaded;
	}
}
