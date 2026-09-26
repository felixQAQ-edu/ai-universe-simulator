package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnEventSink;
import com.aiuniverse.server.eventloop.TurnPhase;
import com.aiuniverse.server.eventloop.TurnResult;
import com.aiuniverse.server.eventloop.TurnStateMachine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-025 刀 1 · DB 版 {@link SessionStore} 的写路径守护(Testcontainers 真 PG,<b>整个 pg profile 上下文</b>:
 * 装配、Flyway、超时配置都是生产那一套,不是测试里另搭一份)。
 *
 * <p>事务失败三条(ADR-025 测试面)须<b>分别</b>可被变异打红:
 * <ol>
 *   <li>{@link #failedPersistDoesNotBreakTheTurn}:persist 不抛、相位照常放回、sink 没收到错误;</li>
 *   <li>{@link #failedPersistRollsBackSnapshotAndEventsTogether}:快照与事件同一事务回滚;</li>
 *   <li>{@link #nextSuccessfulPersistBackfillsTheMissedTurn}:下一次成功时窗口内的漏写回合补上。</li>
 * </ol>
 * 失败注入 = 库里装一个 trigger,在插入叙事含 {@link #FAIL_MARK} 的事件时抛错 —— 真 DB 层失败,
 * 发生在快照 upsert <b>之后</b>(同一事务里),代码里没有任何测试钩子。
 */
@SpringBootTest
@ActiveProfiles("pg")
@RequiresDocker
@Testcontainers
class JdbcSessionStoreTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static final String FAIL_MARK = "#FAIL#";

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@Autowired
	ApplicationContext ctx;
	@Autowired
	SessionStore store;
	@Autowired
	JdbcTemplate jdbc;

	private final Logger storeLogger = (Logger) LoggerFactory.getLogger(JdbcSessionStore.class);
	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void clean() {
		jdbc.execute("DROP TRIGGER IF EXISTS fail_marked_event ON game_event");
		jdbc.execute("TRUNCATE game_event, game_session");
		appender = new ListAppender<>();
		appender.start();
		storeLogger.addAppender(appender);
	}

	@AfterEach
	void detach() {
		storeLogger.detachAppender(appender);
		appender.stop();
	}

	// ── 装配 ──────────────────────────────────────────────────────────

	@Test
	void pgProfileWiresJdbcStoreOnlyAndMigratesSchema() {
		assertThat(store).isInstanceOf(JdbcSessionStore.class);
		assertThat(ctx.getBeanNamesForType(FileSessionStore.class)).as("pg 下文件实现不装配(不双写)").isEmpty();
		Integer tables = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
				+ "WHERE table_name IN ('game_session','game_event')", Integer.class);
		assertThat(tables).as("Flyway 已建两张表").isEqualTo(2);
	}

	// ── turn 0 = 开场叙事 ──────────────────────────────────────────────

	@Test
	void createWritesOpeningAsTurnZeroAndNothingElse() {
		GameSessionManager manager = new GameSessionManager(mapper, store);
		GameSession session = manager.create("save-1", minimalWorld(), actions(), Set.of(), Map.of(), Set.of(),
				"雨夜,便利店的灯管嗡嗡作响。");

		assertThat(events("save-1")).as("原生局 create 之后库里有且只有 turn 0")
				.containsExactly("0|雨夜,便利店的灯管嗡嗡作响。|null");
		String snapshot = jdbc.queryForObject("SELECT snapshot::text FROM game_session WHERE save_id='save-1'",
				String.class);
		assertThat(snapshot).as("开场叙事不进快照").doesNotContain("便利店的灯管");
		assertThat(mapper.writeValueAsString(session.engine().toClientState())).as("不进视图 3")
				.doesNotContain("便利店的灯管");
		assertThat(session.engine().contextJson()).as("不进喂模型的视图 2").doesNotContain("便利店的灯管");
		assertThat(mapper.readTree(snapshot).path("schemaVersion").asString()).as("schemaVersion 不动").isEqualTo("0.4");
	}

	// ── 每回合一条事件 + 往返 ──────────────────────────────────────────

	@Test
	void turnPersistAppendsEventAndSnapshotRoundTrips() {
		GameSession session = playingSession("save-1", "开场。");
		store.persist(session);
		session.engine().apply(turn("走廊的灯闪了一下。", 70, 60), "A");
		store.persist(session);

		assertThat(events("save-1")).containsExactly("0|开场。|null", "1|走廊的灯闪了一下。|A");
		List<GameSession> loaded = store.loadAll();
		assertThat(loaded).hasSize(1);
		GameSession restored = loaded.get(0);
		// 附录 A 口径延伸到 DB 实现(ADR-025 已知代价 1:两个实现各跑一遍)。
		assertThat(mapper.writeValueAsString(restored.engine().toPersistedState()))
				.isEqualTo(mapper.writeValueAsString(session.engine().toPersistedState()));
		assertThat(mapper.writeValueAsString(restored.currentActions()))
				.isEqualTo(mapper.writeValueAsString(session.currentActions()));
		assertThat(restored.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
		assertThat(restored.openingNarrative()).as("回载的会话不带开场叙事").isNull();
		assertThat(row("save-1", "turn")).isEqualTo("1");
	}

	@Test
	void degradedTurnRecordsTheNarrativeThePlayerActuallySaw() {
		GameSession session = playingSession("save-1", "开场。");
		store.persist(session);
		session.engine().applyNoOp("她推开门,走廊尽头", "B"); // 流中断:只流出了半截
		store.persist(session);
		assertThat(events("save-1")).contains("1|她推开门,走廊尽头|B");
	}

	// ── 事务失败三条(分别可被变异打红)─────────────────────────────────

	@Test
	void failedPersistDoesNotBreakTheTurn() {
		GameSession session = playingSession("save-1", "开场。");
		store.persist(session);
		installFailTrigger();
		RecordingSink sink = new RecordingSink();
		TurnStateMachine fsm = new TurnStateMachine((s, a, k) -> {
			k.narrative(FAIL_MARK + "雨更大了。");
			s.engine().apply(turn(FAIL_MARK + "雨更大了。", 90, 90), a);
			return new TurnResult(false);
		}, store);

		assertThatCode(() -> fsm.submitAction(session, "A", sink)).doesNotThrowAnyException();
		assertThat(session.phase().get()).as("相位照常放回").isEqualTo(TurnPhase.AWAITING_ACTION);
		assertThat(sink.errors).as("sink 不收错误(persist 失败不是回合失败)").isEmpty();
		assertThat(sink.narratives).containsExactly(FAIL_MARK + "雨更大了。");
		assertThat(session.engine().turn()).as("局面继续活在内存").isEqualTo(1);
		assertThat(logsAt(Level.ERROR)).as("失败记 ERROR").anyMatch(m -> m.contains("落库失败"));
	}

	@Test
	void failedPersistRollsBackSnapshotAndEventsTogether() {
		GameSession session = playingSession("save-1", "开场。");
		store.persist(session);
		String before = row("save-1", "snapshot::text");
		installFailTrigger();
		session.engine().apply(turn(FAIL_MARK + "雨更大了。", 90, 90), "A");
		persistQuietly(session);

		assertThat(row("save-1", "turn")).as("快照停在上一个完整回合").isEqualTo("0");
		assertThat(row("save-1", "snapshot::text")).as("快照内容未变(与事件同一事务回滚)").isEqualTo(before);
		assertThat(events("save-1")).as("事件未写").containsExactly("0|开场。|null");
	}

	@Test
	void nextSuccessfulPersistBackfillsTheMissedTurn() {
		GameSession session = playingSession("save-1", "开场。");
		store.persist(session);
		installFailTrigger();
		session.engine().apply(turn(FAIL_MARK + "雨更大了。", 90, 90), "A");
		persistQuietly(session); // 失败
		jdbc.execute("DROP TRIGGER fail_marked_event ON game_event");
		session.engine().apply(turn("雨停了。", 90, 90), "B");
		store.persist(session);

		assertThat(events("save-1")).as("窗口内漏写的第 1 回合被补上")
				.containsExactly("0|开场。|null", "1|" + FAIL_MARK + "雨更大了。|A", "2|雨停了。|B");
		assertThat(row("save-1", "turn")).isEqualTo("2");
	}

	@Test
	void failedCreatePersistStillGetsTurnZeroOnNextPersist() {
		installFailTrigger();
		GameSession session = playingSession("save-1", FAIL_MARK + "开场。");
		persistQuietly(session); // create 那一次失败
		assertThat(rowCount("save-1")).isZero();
		jdbc.execute("DROP TRIGGER fail_marked_event ON game_event");
		session.engine().apply(turn("第一回合。", 90, 90), "A");
		store.persist(session);
		assertThat(events("save-1")).containsExactly("0|" + FAIL_MARK + "开场。|null", "1|第一回合。|A");
	}

	// ── 来源标记:首次插入写下,之后不改,不推断 ────────────────────────────

	@Test
	void nativeRowIsMarkedNativeEvenWithoutTurnZero() {
		// 本进程外开的局(回载会话,开场叙事已不在内存):没有 turn 0,但它仍是原生局 ——
		// 按「有没有 turn 0」推断会把它读成导入档,把写失败空洞说成「此前未被记录」。
		GameSession reloaded = playingSession("save-1", null);
		reloaded.engine().apply(turn("第一回合。", 90, 90), "A");
		store.persist(reloaded);
		assertThat(row("save-1", "source")).isEqualTo("native");
		assertThat(events("save-1")).containsExactly("1|第一回合。|A");
	}

	@Test
	void persistNeverOverwritesTheSourceWrittenByTheFirstInsert() {
		GameSession session = playingSession("save-1", null);
		jdbc.update("INSERT INTO game_session (save_id, snapshot, turn, status, source) "
				+ "VALUES ('save-1', ?::json, 0, 'ongoing', 'import')",
				mapper.writeValueAsString(SessionDocument.encode(session, mapper)));
		session.engine().apply(turn("导入后的第一回合。", 90, 90), "A");
		store.persist(session);
		assertThat(row("save-1", "source")).as("UPDATE 分支不碰 source").isEqualTo("import");
		assertThat(row("save-1", "turn")).isEqualTo("1");
	}

	// ── 回载容错 ──────────────────────────────────────────────────────

	@Test
	void unloadableRowIsRefusedWithWarningAndOthersStillLoad() {
		store.persist(playingSession("save-good", null));
		jdbc.update("INSERT INTO game_session (save_id, snapshot, turn, status, source) "
				+ "VALUES ('save-bad', '{}'::json, 0, 'ongoing', 'native')");
		List<GameSession> loaded = store.loadAll();
		assertThat(loaded).extracting(GameSession::saveId).containsExactly("save-good");
		assertThat(logsAt(Level.WARN)).anyMatch(m -> m.contains("save-bad"));
		assertThat(logsAt(Level.INFO)).anyMatch(m -> m.contains("载入 1 档,1 档拒载"));
		assertThat(rowCount("save-bad")).as("行保留留尸检").isEqualTo(1);
	}

	// ── helpers ────────────────────────────────────────────────────────

	/**
	 * 只看库里状态、不管 persist 抛不抛:「失败时不抛」是 {@link #failedPersistDoesNotBreakTheTurn} 一条的事。
	 * 若这里也要求不抛,一个「persist 抛出」的变异会同时打红三条,就证明不了它们各自在守东西(§4.13)。
	 */
	private void persistQuietly(GameSession session) {
		try {
			store.persist(session);
		} catch (RuntimeException ignored) {
			// 见方法注释
		}
	}

	private void installFailTrigger() {
		jdbc.execute("CREATE OR REPLACE FUNCTION fail_marked_event() RETURNS trigger AS $$ BEGIN "
				+ "IF NEW.narrative LIKE '%" + FAIL_MARK + "%' THEN RAISE EXCEPTION 'injected failure'; END IF; "
				+ "RETURN NEW; END $$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER fail_marked_event BEFORE INSERT ON game_event "
				+ "FOR EACH ROW EXECUTE FUNCTION fail_marked_event()");
	}

	private List<String> events(String saveId) {
		return jdbc.query("SELECT turn, narrative, player_action FROM game_event WHERE save_id = ? ORDER BY turn",
				(rs, i) -> rs.getInt(1) + "|" + rs.getString(2) + "|" + rs.getString(3), saveId);
	}

	private String row(String saveId, String column) {
		return jdbc.queryForObject("SELECT " + column + " FROM game_session WHERE save_id = ?", String.class, saveId);
	}

	private int rowCount(String saveId) {
		return jdbc.queryForObject("SELECT count(*) FROM game_session WHERE save_id = ?", Integer.class, saveId);
	}

	private List<String> logsAt(Level level) {
		return appender.list.stream().filter(e -> e.getLevel() == level)
				.map(ILoggingEvent::getFormattedMessage).toList();
	}

	private GameSession playingSession(String saveId, String opening) {
		List<AttributeAxis> axes = registry.meta("rules_creepy").attributes();
		Engine engine = new Engine(minimalWorld(), mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		return new GameSession(saveId, engine, actions(), opening);
	}

	private ObjectNode turn(String narrative, int hp, int san) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", narrative);
		t.putObject("stateUpdate").put("hp", hp).put("san", san);
		return t;
	}

	private ArrayNode actions() {
		ArrayNode actions = mapper.createArrayNode();
		actions.addObject().put("id", "A").put("text", "谨慎观察四周").put("hint", "");
		actions.addObject().put("id", "B").put("text", "原地等待").put("hint", "");
		return actions;
	}

	private ObjectNode minimalWorld() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4");
		w.put("mode", "single");
		w.putArray("archetypes").add("rules_creepy");
		w.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		ArrayNode rules = w.putArray("rules");
		rules.addObject().put("id", 1).put("content", "熄灯后不要回头。")
				.put("isTrue", true).put("hiddenLogic", "回头即被标记。").put("discovered", false);
		ArrayNode endings = w.putArray("endings");
		endings.addObject().put("id", "dead").put("title", "死亡").put("condition", "体力归零")
				.put("outcome", "failure").put("reached", false);
		return w;
	}

	private static final class RecordingSink implements TurnEventSink {
		final List<String> narratives = new ArrayList<>();
		final List<String> errors = new ArrayList<>();

		@Override
		public void narrative(String text) {
			narratives.add(text);
		}

		@Override
		public void delta(ObjectNode d) {
		}

		@Override
		public void ending(ObjectNode e) {
		}

		@Override
		public void error(String code, String message) {
			errors.add(code);
		}
	}
}
