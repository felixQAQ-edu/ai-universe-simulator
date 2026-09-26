package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.web.GameController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-025 刀 2 · {@code GET /api/game/{saveId}/history} 的守护(Testcontainers 真 PG,整个 pg profile 上下文,
 * 经 MockMvc 打 Spring 装配出来的那个 {@link GameController} —— 读的是生产那一套装配)。
 *
 * <p>行多数<b>直接插库</b>:导入档(source='import')在刀 4 之前没有写入路径,写失败空洞也要能精确摆出来。
 * 另有一条走真实 {@code GameSessionManager.create} + {@code persist},证明写路径产出的东西读得回来。
 *
 * <p>变异验证对应(ADR-018 §4.13,报告里逐条写明红的是哪一条):
 * <ul>
 *   <li>① 来源改成按有无 turn 0 推断 → 只红 {@link #nativeSessionMissingTurnZeroIsWriteFailedNotBeforeRecording};</li>
 *   <li>③ 两种缺口合并成同一形态 → 红缺口类用例,不红分页 / 硬闸 / 404;</li>
 *   <li>⑤ 按事件条数切页 → 只红 {@link #writeFailedGapStraddlingPageBoundaryIsSplitByTurnNumber}。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("pg")
@RequiresDocker
@Testcontainers
class JdbcNarrativeHistoryReaderTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	/** 快照里的隐藏字段标记值:它们只该活在 game_session 的快照列里,历史响应里一个都不许出现。 */
	static final String HIDDEN_MARK = "HIDDEN-LOGIC-MARK-7f3a";

	private final ObjectMapper mapper = new ObjectMapper();

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	GameController controller;
	@Autowired
	SessionStore store;
	@Autowired
	NarrativeHistoryReader reader;

	private MockMvc mvc;

	@BeforeEach
	void clean() {
		jdbc.execute("TRUNCATE game_event, game_session");
		mvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void pgProfileWiresJdbcReader() {
		assertThat(reader).isInstanceOf(JdbcNarrativeHistoryReader.class);
	}

	// ── 正常历史 ──────────────────────────────────────────────────────

	@Test
	void writtenByRealPersistPathAndReturnedAscending() throws Exception {
		GameSessionManager manager = new GameSessionManager(mapper, store);
		var session = manager.create("save-1", world(), actions(), Set.of(), Map.of(), Set.of(), "雨夜,便利店。");
		session.engine().applyNoOp("第一回合。", "A");
		store.persist(session);
		session.engine().applyNoOp("第二回合。", "B");
		store.persist(session);

		JsonNode page = ok("save-1", null);
		assertThat(page.path("source").asString()).isEqualTo("native");
		assertThat(page.path("sessionTurn").asInt()).isEqualTo(2);
		assertThat(page.path("fromTurn").asInt()).isZero();
		assertThat(page.path("toTurn").asInt()).isEqualTo(99);
		assertThat(page.path("nextAfterTurn").isNull()).isTrue();
		assertThat(entries(page)).containsExactly("event 0 雨夜,便利店。 null", "event 1 第一回合。 A",
				"event 2 第二回合。 B");
	}

	// ── 两种缺口 ──────────────────────────────────────────────────────

	@Test
	void nativeWriteFailedHoleIsMarkedInPlaceWithoutContent() throws Exception {
		session("save-1", "native", 5);
		event("save-1", 0, "开场。", null);
		event("save-1", 1, "一。", "A");
		event("save-1", 4, "四。", "A");
		event("save-1", 5, "五。", "B");

		assertThat(entries(ok("save-1", null))).containsExactly("event 0 开场。 null", "event 1 一。 A",
				"gap write_failed 2-3", "event 4 四。 A", "event 5 五。 B");
	}

	@Test
	void nativeSessionMissingTurnZeroIsWriteFailedNotBeforeRecording() throws Exception {
		// create 那次 persist 失败的原生局:没有 turn 0。按「有没有 turn 0」推断会把它读成导入档,
		// 把故障说成「此前未被记录」(ADR-025 决策 2 的否决项)。
		session("save-1", "native", 2);
		event("save-1", 1, "一。", "A");
		event("save-1", 2, "二。", "B");

		assertThat(entries(ok("save-1", null))).containsExactly("gap write_failed 0-0", "event 1 一。 A",
				"event 2 二。 B");
	}

	@Test
	void importedSessionDistinguishesBeforeRecordingFromWriteFailed() throws Exception {
		session("save-1", "import", 9);
		event("save-1", 5, "五。", "A");
		event("save-1", 6, "六。", "B");
		event("save-1", 9, "九。", "A");

		JsonNode page = ok("save-1", null);
		assertThat(page.path("source").asString()).isEqualTo("import");
		assertThat(entries(page)).containsExactly("gap before_recording 0-4", "event 5 五。 A", "event 6 六。 B",
				"gap write_failed 7-8", "event 9 九。 A");
	}

	@Test
	void importedSessionWithNoEventsIsBeforeRecordingThroughout() throws Exception {
		session("save-1", "import", 7);
		assertThat(entries(ok("save-1", null))).containsExactly("gap before_recording 0-7");
	}

	// ── 分页(按回合号区间)────────────────────────────────────────────

	@Test
	void pagesAreContiguousAcrossHundredTurnBoundary() throws Exception {
		session("save-1", "native", 150);
		for (int t = 0; t <= 150; t++) {
			event("save-1", t, "第" + t + "回合。", t == 0 ? null : "A");
		}
		JsonNode p1 = ok("save-1", null);
		assertThat(p1.path("fromTurn").asInt()).isZero();
		assertThat(p1.path("toTurn").asInt()).isEqualTo(99);
		assertThat(p1.path("nextAfterTurn").asInt()).isEqualTo(99);
		JsonNode p2 = ok("save-1", 99);
		assertThat(p2.path("fromTurn").asInt()).isEqualTo(100);
		assertThat(p2.path("toTurn").asInt()).isEqualTo(199);
		assertThat(p2.path("nextAfterTurn").isNull()).as("区间已到 sessionTurn").isTrue();

		List<Integer> turns = new ArrayList<>(turns(p1));
		turns.addAll(turns(p2));
		assertThat(turns).hasSize(151);
		for (int t = 0; t <= 150; t++) {
			assertThat(turns.get(t)).isEqualTo(t);
		}
	}

	@Test
	void writeFailedGapStraddlingPageBoundaryIsSplitByTurnNumber() throws Exception {
		// 空洞 98..102 跨在首页边界 99/100 上:按回合号切,首页只见 98-99、次页见 100-102。
		// 按事件条数切(LIMIT 100)会让首页多吞到 104,整段空洞落进首页 —— 本条会红(变异 ⑤)。
		session("save-1", "native", 110);
		for (int t = 0; t <= 110; t++) {
			if (t < 98 || t > 102) {
				event("save-1", t, "第" + t + "回合。", "A");
			}
		}
		JsonNode p1 = ok("save-1", null);
		List<String> e1 = entries(p1);
		assertThat(e1.get(e1.size() - 1)).isEqualTo("gap write_failed 98-99");
		assertThat(p1.path("nextAfterTurn").asInt()).isEqualTo(99);
		List<String> e2 = entries(ok("save-1", 99));
		assertThat(e2.get(0)).isEqualTo("gap write_failed 100-102");
		assertThat(e2.get(1)).startsWith("event 103 ");
	}

	@Test
	void turnsAppendedWhilePagingAreNeitherRepeatedNorSkipped() throws Exception {
		session("save-1", "native", 120);
		for (int t = 0; t <= 120; t++) {
			event("save-1", t, "第" + t + "回合。", "A");
		}
		JsonNode p1 = ok("save-1", null);
		// 翻页之间局面推进了 5 个回合
		for (int t = 121; t <= 125; t++) {
			event("save-1", t, "第" + t + "回合。", "B");
		}
		jdbc.update("UPDATE game_session SET turn = 125 WHERE save_id = 'save-1'");
		JsonNode p2 = ok("save-1", p1.path("nextAfterTurn").asInt());

		List<Integer> turns = new ArrayList<>(turns(p1));
		turns.addAll(turns(p2));
		assertThat(turns).hasSize(126).doesNotHaveDuplicates();
		assertThat(turns.get(0)).isZero();
		assertThat(turns.get(125)).isEqualTo(125);
		assertThat(p2.path("sessionTurn").asInt()).isEqualTo(125);
	}

	@Test
	void afterTurnBeyondSessionTurnIsAnEmptyLastPage() throws Exception {
		session("save-1", "native", 3);
		event("save-1", 0, "开场。", null);
		JsonNode page = ok("save-1", 99);
		assertThat(page.path("entries").size()).isZero();
		assertThat(page.path("nextAfterTurn").isNull()).isTrue();
	}

	// ── 消毒硬闸 ──────────────────────────────────────────────────────

	@Test
	void responseNeverLeaksHiddenFieldsFromSnapshot() throws Exception {
		ObjectNode snap = mapper.createObjectNode();
		snap.putObject("world").putArray("rules").addObject().put("id", 1).put("content", "熄灯后不要回头。")
				.put("isTrue", true).put("hiddenLogic", HIDDEN_MARK);
		jdbc.update("INSERT INTO game_session (save_id, snapshot, turn, status, source) "
				+ "VALUES ('save-1', ?::json, 1, 'ongoing', 'native')", mapper.writeValueAsString(snap));
		event("save-1", 0, "开场。", null);
		event("save-1", 1, "一。", "A");

		String body = mvc.perform(get("/api/game/save-1/history")).andReturn().getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(body).contains("开场。"); // 确实读到了这一局
		assertThat(body).doesNotContain(HIDDEN_MARK).doesNotContain("hiddenLogic").doesNotContain("isTrue")
				.doesNotContain("熄灯后不要回头");
	}

	// ── 错误形态 ──────────────────────────────────────────────────────

	@Test
	void unknownSaveIdIsNotFoundWithCodeOnly() throws Exception {
		MvcResult r = mvc.perform(get("/api/game/nope/history")).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(404);
		JsonNode err = mapper.readTree(r.getResponse().getContentAsString()).path("error");
		assertThat(err.path("code").asString()).isEqualTo("session_not_found");
		assertThat(err.has("message")).as("404 只带 code(ADR-022 立字 11)").isFalse();
	}

	// ── helpers ────────────────────────────────────────────────────────

	private JsonNode ok(String saveId, Integer afterTurn) throws Exception {
		String url = "/api/game/" + saveId + "/history" + (afterTurn == null ? "" : "?afterTurn=" + afterTurn);
		MvcResult r = mvc.perform(get(url)).andReturn();
		assertThat(r.getResponse().getStatus()).as(url).isEqualTo(200);
		return mapper.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
	}

	/** 条目压成一行字符串,便于整页对照。 */
	private static List<String> entries(JsonNode page) {
		List<String> out = new ArrayList<>();
		for (JsonNode e : page.path("entries")) {
			if ("event".equals(e.path("kind").asString())) {
				JsonNode a = e.path("playerAction");
				out.add("event " + e.path("turn").asInt() + " " + e.path("narrative").asString() + " "
						+ (a.isNull() ? "null" : a.asString()));
			} else {
				out.add("gap " + e.path("reason").asString() + " " + e.path("fromTurn").asInt() + "-"
						+ e.path("toTurn").asInt());
			}
		}
		return out;
	}

	private static List<Integer> turns(JsonNode page) {
		List<Integer> out = new ArrayList<>();
		for (JsonNode e : page.path("entries")) {
			out.add(e.path("turn").asInt());
		}
		return out;
	}

	private void session(String saveId, String source, int turn) {
		jdbc.update("INSERT INTO game_session (save_id, snapshot, turn, status, source) "
				+ "VALUES (?, '{}'::json, ?, 'ongoing', ?)", saveId, turn, source);
	}

	private void event(String saveId, int turn, String narrative, String action) {
		jdbc.update("INSERT INTO game_event (save_id, turn, narrative, player_action) VALUES (?, ?, ?, ?)", saveId,
				turn, narrative, action);
	}

	private ObjectNode world() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4");
		w.put("mode", "single");
		w.putArray("archetypes").add("rules_creepy");
		w.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		w.putArray("rules");
		w.putArray("endings").addObject().put("id", "dead").put("title", "死亡").put("condition", "体力归零")
				.put("outcome", "failure").put("reached", false);
		return w;
	}

	private ArrayNode actions() {
		ArrayNode actions = mapper.createArrayNode();
		actions.addObject().put("id", "A").put("text", "谨慎观察四周").put("hint", "");
		actions.addObject().put("id", "B").put("text", "原地等待").put("hint", "");
		return actions;
	}
}
