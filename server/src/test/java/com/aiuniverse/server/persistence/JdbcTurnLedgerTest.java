package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.EventLoopService;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.TurnEventSink;
import com.aiuniverse.server.eventloop.TurnExecutor;
import com.aiuniverse.server.eventloop.TurnPhase;
import com.aiuniverse.server.eventloop.TurnPromptBuilder;
import com.aiuniverse.server.eventloop.TurnResult;
import com.aiuniverse.server.eventloop.TurnStateMachine;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.quota.QuotaGate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-026 刀 2 · 回合受理记录({@code pg} profile,Testcontainers 真 PG,整个 pg 上下文)。
 * 编号对应 ADR-026 测试面 ①–⑦,另加 ⑧(ADR-027 决策 5 的吸收)与 busy 一条。
 *
 * <p>变异验证(读红用例名):
 * <ul>
 *   <li>摘掉 persist 里的落地更新 → ① 红;</li>
 *   <li>摘掉启动收口 → ④ 红;</li>
 *   <li>把 {@code accept} 挪到 CAS 之前 → {@link #busyRejectionLeavesNoRow} 红;</li>
 *   <li>把 {@code ledger.failed} 放进已落地分支 → ⑧ 红。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("pg")
@RequiresDocker
@Testcontainers
class JdbcTurnLedgerTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static final String FAIL_MARK = "#FAIL#";

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@Autowired
	SessionStore store;
	@Autowired
	TurnLedger ledger;
	@Autowired
	org.springframework.context.ApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.execute("DROP TRIGGER IF EXISTS fail_marked_event ON game_event");
		jdbc.execute("DROP TRIGGER IF EXISTS fail_accept ON turn_request");
		jdbc.execute("TRUNCATE turn_request, game_event, game_session");
	}

	// ── 装配 ──────────────────────────────────────────────────────────

	@Test
	void pgProfileWiresJdbcLedgerAndMigratesTurnRequest() {
		assertThat(ledger).isInstanceOf(JdbcTurnLedger.class);
		assertThat(ctx.getBeanNamesForType(TurnLedgerConfig.class)).as("配置类在,但它的 NOOP bean 只在 !pg")
				.hasSize(1);
		assertThat(ctx.getBeansOfType(TurnLedger.class)).as("pg 下只有一个 TurnLedger,不是 NOOP").hasSize(1);
		Integer tables = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
				+ "WHERE table_name = 'turn_request'", Integer.class);
		assertThat(tables).as("Flyway V2 已建表").isEqualTo(1);
	}

	// ── ① 正常回合 ─────────────────────────────────────────────────────

	@Test
	void normalTurnClosesAsSucceededWithTargetEqualSnapshotTurn() {
		GameSession s = playingSession("save-1");
		store.persist(s);
		fsm(applying("走廊的灯闪了一下。")).submitAction(s, "A", new NullSink());

		assertThat(requests("save-1")).containsExactly("0->1|A|SUCCEEDED");
		assertThat(snapshotTurn("save-1")).as("target_turn = 快照 turn").isEqualTo(1);
		assertThat(finishedAtSet("save-1")).isTrue();
	}

	// ── ② 降级 ────────────────────────────────────────────────────────

	@Test
	void degradedTurnClosesAsDegraded() {
		GameSession s = playingSession("save-1");
		store.persist(s);
		// 真 EventLoopService:主调用流中断 → degrade() → applyNoOp(turn 照样 +1)。
		EventLoopService service = new EventLoopService((req, sink) -> {
			throw new LlmException("流中断");
		}, new TurnPromptBuilder(registry), mapper);
		fsm(service).submitAction(s, "A", new NullSink());

		assertThat(s.engine().turn()).isEqualTo(1);
		assertThat(requests("save-1")).containsExactly("0->1|A|DEGRADED");
		assertThat(snapshotTurn("save-1")).isEqualTo(1);
	}

	// ── ③ executor 抛(未落地)────────────────────────────────────────────

	@Test
	void unlandedThrowMarksFailedAndSnapshotStays() {
		GameSession s = playingSession("save-1");
		store.persist(s);
		fsm((ss, a, k) -> {
			throw new IllegalStateException("叙事中途断流");
		}).submitAction(s, "A", new NullSink());

		assertThat(requests("save-1")).containsExactly("0->1|A|FAILED");
		assertThat(snapshotTurn("save-1")).as("快照未前进").isZero();
	}

	// ── ④ 受理后、落地前进程终止 → 启动收口 ─────────────────────────────────

	@Test
	void acceptedButNeverPersistedBecomesInterruptedOnReload() {
		GameSession s = playingSession("save-1");
		store.persist(s);
		// 受理之后进程「死掉」:executor 里什么也不做就再也没回来 —— 这里直接只调 accept,不走落地。
		ledger.accept(s, "A");
		assertThat(requests("save-1")).containsExactly("0->1|A|PROCESSING");

		store.loadAll(); // 新进程启动回载

		assertThat(requests("save-1")).containsExactly("0->1|A|INTERRUPTED");
		assertThat(snapshotTurn("save-1")).isZero();
	}

	// ── ⑤ 落地事务失败 ─────────────────────────────────────────────────

	@Test
	void failedLandingPersistLeavesRowProcessingAndSnapshotUnmoved() {
		GameSession s = playingSession("save-1");
		store.persist(s);
		installEventFailTrigger();
		fsm(applying(FAIL_MARK + "雨更大了。")).submitAction(s, "A", new NullSink());

		assertThat(s.engine().turn()).as("内存照常落地").isEqualTo(1);
		assertThat(requests("save-1")).as("落地更新与快照一起回滚:行仍 PROCESSING").containsExactly("0->1|A|PROCESSING");
		assertThat(snapshotTurn("save-1")).as("快照未前进 —— 两者一致").isZero();
	}

	// ── ⑥ 唯一索引会响 ────────────────────────────────────────────────

	/**
	 * 人为双写证明断言会响。⚠️ 生产路径上 CAS 与游标比对已在进程内挡住同一 base_turn 的第二次受理,
	 * 正常情况下没有东西会触发它(例外见 ADR-026 实现进度注:{@code failed} 自己写失败后同回合重试)。
	 */
	@Test
	void secondLiveRowOnSameBaseTurnViolatesUniqueIndex() {
		jdbc.update(JdbcTurnLedger.INSERT_ACCEPTED.replace(" RETURNING id", ""), "save-1", 3, 4, "A");
		assertThatThrownBy(() -> jdbc.update(JdbcTurnLedger.INSERT_ACCEPTED.replace(" RETURNING id", ""),
				"save-1", 3, 4, "B")).isInstanceOf(DuplicateKeyException.class);
		// FAILED / INTERRUPTED 不在谓词里:同 base_turn 在它们之后重试是合法的新受理。
		jdbc.update("UPDATE turn_request SET status = 'FAILED' WHERE save_id = 'save-1'");
		jdbc.update(JdbcTurnLedger.INSERT_ACCEPTED.replace(" RETURNING id", ""), "save-1", 3, 4, "B");
		assertThat(requests("save-1")).containsExactlyInAnyOrder("3->4|A|FAILED", "3->4|B|PROCESSING");
	}

	// ── ⑦ 受理事务失败 → 照跑 ─────────────────────────────────────────────

	@Test
	void failedAcceptStillRunsTheTurnAndLandingFindsNothingToClose() {
		GameSession s = playingSession("save-1");
		store.persist(s);
		jdbc.execute("CREATE OR REPLACE FUNCTION fail_accept() RETURNS trigger AS $$ BEGIN "
				+ "RAISE EXCEPTION 'injected accept failure'; END $$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER fail_accept BEFORE INSERT ON turn_request "
				+ "FOR EACH ROW EXECUTE FUNCTION fail_accept()");
		NullSink sink = new NullSink();
		fsm(applying("走廊的灯闪了一下。")).submitAction(s, "A", sink);

		assertThat(sink.errors).as("回合照跑,不报错").isEmpty();
		assertThat(s.engine().turn()).isEqualTo(1);
		assertThat(snapshotTurn("save-1")).as("落地照常写快照").isEqualTo(1);
		assertThat(requests("save-1")).as("无受理行(记录是下界)").isEmpty();
		assertThat(s.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
	}

	// ── ⑧ ADR-027 决策 5 的吸收:已落地后送达失败 ─────────────────────────

	@Test
	void landedThenDeliveryFailureClosesAsSucceededNotFailed() {
		GameSession s = playingSession("save-1");
		store.persist(s);
		fsm((ss, a, k) -> {
			ss.engine().apply(turn("走廊的灯闪了一下。"), a); // 内存落地
			throw new IllegalStateException("SSE 发送失败(delta 写失败)");
		}).submitAction(s, "A", new NullSink());

		assertThat(requests("save-1")).as("已落地分支补写盘 → 落地更新关行;不是 FAILED")
				.containsExactly("0->1|A|SUCCEEDED");
		assertThat(snapshotTurn("save-1")).isEqualTo(1);
	}

	// ── busy 拒绝不留受理行 ────────────────────────────────────────────

	@Test
	void busyRejectionLeavesNoRow() {
		GameSession s = playingSession("save-1");
		store.persist(s);
		s.phase().set(TurnPhase.GENERATING); // 另一线程正在跑这一局
		NullSink sink = new NullSink();
		fsm(applying("不该跑到这里。")).submitAction(s, "A", sink);

		assertThat(sink.errors).containsExactly("busy");
		assertThat(requests("save-1")).as("CAS 失败 → 不受理,不留孤行").isEmpty();
	}

	// ── helpers ────────────────────────────────────────────────────────

	private TurnStateMachine fsm(TurnExecutor executor) {
		return new TurnStateMachine(executor, store, QuotaGate.NOOP, ledger);
	}

	private TurnExecutor applying(String narrative) {
		return (s, a, k) -> {
			s.engine().apply(turn(narrative), a);
			return new TurnResult(false);
		};
	}

	private List<String> requests(String saveId) {
		return jdbc.query("SELECT base_turn, target_turn, action_id, status FROM turn_request WHERE save_id = ? ORDER BY id",
				(rs, i) -> rs.getInt(1) + "->" + rs.getInt(2) + "|" + rs.getString(3) + "|" + rs.getString(4), saveId);
	}

	private boolean finishedAtSet(String saveId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT bool_and(finished_at IS NOT NULL) FROM turn_request WHERE save_id = ?", Boolean.class, saveId));
	}

	private int snapshotTurn(String saveId) {
		return jdbc.queryForObject("SELECT turn FROM game_session WHERE save_id = ?", Integer.class, saveId);
	}

	private void installEventFailTrigger() {
		jdbc.execute("CREATE OR REPLACE FUNCTION fail_marked_event() RETURNS trigger AS $$ BEGIN "
				+ "IF NEW.narrative LIKE '%" + FAIL_MARK + "%' THEN RAISE EXCEPTION 'injected failure'; END IF; "
				+ "RETURN NEW; END $$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER fail_marked_event BEFORE INSERT ON game_event "
				+ "FOR EACH ROW EXECUTE FUNCTION fail_marked_event()");
	}

	private GameSession playingSession(String saveId) {
		List<AttributeAxis> axes = registry.meta("rules_creepy").attributes();
		Engine engine = new Engine(minimalWorld(), mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		return new GameSession(saveId, engine, actions());
	}

	private ObjectNode turn(String narrative) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", narrative);
		t.putObject("stateUpdate").put("hp", 90).put("san", 90);
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
		w.putObject("world").put("title", "雨夜便利店").put("background", "...")
				.put("dangerLevel", "high").put("tone", "瘆人");
		w.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		ArrayNode rules = w.putArray("rules");
		rules.addObject().put("id", 1).put("content", "熄灯后不要回头。")
				.put("isTrue", true).put("hiddenLogic", "回头即被标记。").put("discovered", false);
		ArrayNode endings = w.putArray("endings");
		endings.addObject().put("id", "dead").put("title", "死亡").put("condition", "体力归零")
				.put("outcome", "failure").put("reached", false);
		return w;
	}

	private static final class NullSink implements TurnEventSink {
		final List<String> errors = new ArrayList<>();

		@Override public void narrative(String text) { }
		@Override public void delta(ObjectNode d) { }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String code, String message) { errors.add(code); }
	}
}
