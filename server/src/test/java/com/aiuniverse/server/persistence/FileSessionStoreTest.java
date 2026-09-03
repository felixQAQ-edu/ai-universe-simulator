package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.TurnPhase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-015 Slice 2 · 落盘层守护:写盘(原子、含 session 层字段)→ 回载(轴集重派生 + phase 按
 * status 重置)往返;单文件损坏/archetype 不识 → 跳过 + 保留原文件、其余照载;路径安全断言
 * (附录 A 第 3 条,Slice 1 移交)拒绝启动不降级;非法 saveId 拒写。
 *
 * <p>ADR-022 刀 2 前置追加:共用目录里的<b>非存档文件不报警</b>,而<b>真坏档仍叫得出声</b>——
 * 两者是同一枚硬币的两面,必须同时绿(且变异时须<b>分别</b>变红,一起红说明其中一条搭了另一条便车)。
 * 日志断言走 logback {@link ListAppender}:这一刀的题目就是日志,故直接测日志、不退到测判定。
 */
class FileSessionStoreTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@TempDir
	Path tmp;

	private final Logger storeLogger = (Logger) LoggerFactory.getLogger(FileSessionStore.class);
	private ListAppender<ILoggingEvent> appender;

	private FileSessionStore store() {
		return new FileSessionStore(tmp.toString(), mapper, registry);
	}

	// ── 日志取证(ListAppender)────────────────────────────────────────

	/** 在 store 构造之后挂,避开构造期那条「落盘目录 = …」INFO。 */
	private void captureLogs() {
		appender = new ListAppender<>();
		appender.start();
		storeLogger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		if (appender != null) {
			storeLogger.detachAppender(appender);
			appender.stop();
			appender = null;
		}
	}

	private List<String> logsAt(Level level) {
		return appender.list.stream().filter(e -> e.getLevel() == level)
				.map(ILoggingEvent::getFormattedMessage).toList();
	}

	// ── 写盘 → 回载 往返 ────────────────────────────────────────────────

	@Test
	void persistThenLoadAllRoundTripsEngineActionsAndPhase() {
		FileSessionStore store = store();
		GameSession session = playingSession("save-1");
		session.engine().apply(simpleTurn(70, 60), "A");
		store.persist(session);

		List<GameSession> loaded = store.loadAll();
		assertThat(loaded).hasSize(1);
		GameSession restored = loaded.get(0);
		assertThat(restored.saveId()).isEqualTo("save-1");
		// 引擎往返逐字节(附录 A 口径延伸到落盘层)。
		assertThat(mapper.writeValueAsString(restored.engine().toPersistedState()))
				.isEqualTo(mapper.writeValueAsString(session.engine().toPersistedState()));
		// session 层字段:currentActions 落盘并回载(守卫 1 依赖)。
		assertThat(mapper.writeValueAsString(restored.currentActions()))
				.isEqualTo(mapper.writeValueAsString(session.currentActions()));
		// phase 按 status 重置(ongoing → AWAITING_ACTION)。
		assertThat(restored.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
	}

	@Test
	void persistedFileCarriesSessionLevelFieldsAndHiddenAuthorFields() throws Exception {
		FileSessionStore store = store();
		store.persist(playingSession("save-doc"));
		Path file = tmp.resolve("save-doc.json");
		assertThat(file).exists();
		ObjectNode doc = (ObjectNode) mapper.readTree(Files.readString(file));
		assertThat(doc.path("currentActions").isArray()).isTrue();
		assertThat(doc.path("currentActions")).isNotEmpty();
		assertThat(doc.path("phaseHint").asString("")).isNotBlank();
		// 落盘 = 视图 1 全量(含作者字段)——这正是路径安全断言存在的理由。
		assertThat(doc.path("world").path("rules").get(0).has("isTrue")).isTrue();
		assertThat(doc.path("world").path("rules").get(0).has("hiddenLogic")).isTrue();
	}

	@Test
	void persistIsAtomicAndLeavesNoTmpFile() throws Exception {
		FileSessionStore store = store();
		GameSession session = playingSession("save-atomic");
		store.persist(session);
		store.persist(session); // 覆盖写(REPLACE_EXISTING)也不留尸
		try (Stream<Path> files = Files.list(tmp)) {
			assertThat(files.map(p -> p.getFileName().toString()))
					.containsExactly("save-atomic.json");
		}
	}

	@Test
	void endedSessionReloadsWithEndedPhase() {
		FileSessionStore store = store();
		GameSession session = playingSession("save-ended");
		session.engine().apply(simpleTurn(0, 40), "A"); // hp 触底 → ended
		assertThat(session.engine().status()).isEqualTo("ended");
		store.persist(session);

		List<GameSession> loaded = store.loadAll();
		assertThat(loaded).hasSize(1);
		assertThat(loaded.get(0).phase().get()).isEqualTo(TurnPhase.ENDED);
		assertThat(loaded.get(0).engine().status()).isEqualTo("ended");
	}

	@Test
	void fusionSessionReloadsWithRederivedFusedAxes() {
		FileSessionStore store = store();
		List<AttributeAxis> axes = registry.fusedAxes("cultivation", "rules_creepy");
		Engine engine = new Engine(fusionWorld(), mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		store.persist(new GameSession("save-fusion", engine, actions()));

		GameSession restored = store.loadAll().get(0);
		// 重派生轴集生效:灵力(非致命)/境界(累积)归零不触底。
		restored.engine().apply(fusionTurn(55, 0, 0, 65), "A");
		assertThat(restored.engine().status()).isEqualTo("ongoing");
	}

	// ── 回载容错:坏档跳过、保留原样、其余照载 ─────────────────────────────

	@Test
	void corruptedFileIsSkippedRetainedAndOthersStillLoad() throws Exception {
		FileSessionStore store = store();
		store.persist(playingSession("save-good"));
		Path corrupt = tmp.resolve("save-bad.json");
		Files.writeString(corrupt, "{ not json !!");

		List<GameSession> loaded = store.loadAll();
		assertThat(loaded).extracting(GameSession::saveId).containsExactly("save-good");
		assertThat(corrupt).as("坏档保留原样(留尸检)").exists();
		assertThat(Files.readString(corrupt)).isEqualTo("{ not json !!");
	}

	@Test
	void unknownArchetypeFileIsSkippedAndRetained() throws Exception {
		FileSessionStore store = store();
		GameSession session = playingSession("save-alien");
		ObjectNode doc = session.engine().toPersistedState();
		((ArrayNode) doc.path("world").path("archetypes")).removeAll().add("not_a_world");
		doc.set("currentActions", actions());
		Path file = tmp.resolve("save-alien.json");
		Files.writeString(file, mapper.writeValueAsString(doc));

		assertThat(store.loadAll()).isEmpty();
		assertThat(file).exists();
	}

	// ── 共用目录:非存档文件不报警 / 真坏档仍叫(ADR-022 刀 2 前置)──────────

	@Test
	void nonSaveJsonInStoreDirIsSkippedWithoutWarning() throws Exception {
		FileSessionStore store = store();
		store.persist(playingSession("save-good"));
		// ADR-016:114 月账与存档共用同一目录,一月一个、永不删除 —— 按后缀收会让 WARN 每次启动都出现
		// 且逐月增长,把人训练成自动跳过 WARN(而刀 2 的观测面正是要往这条通道里加 WARN)。
		Path quota = tmp.resolve("quota-2026-09.json");
		Files.writeString(quota, "{\"month\":\"2026-09\",\"spentCny\":0.0452}");
		captureLogs();

		List<GameSession> loaded = store.loadAll();

		assertThat(loaded).extracting(GameSession::saveId).containsExactly("save-good");
		assertThat(logsAt(Level.WARN)).as("别人的文件不是坏档,不该报警").isEmpty();
		assertThat(quota).as("别人的文件原样保留,不被本模块处置").exists();
		assertThat(Files.readString(quota)).contains("spentCny");
	}

	@Test
	void damagedSaveWithWorldStillWarnsAndIsRetained() throws Exception {
		FileSessionStore store = store();
		ObjectNode doc = playingSession("x").engine().toPersistedState();
		doc.remove("state"); // 有 world、缺 state → Engine.restore 拒载(是存档,只是坏了)
		Path file = tmp.resolve("save-damaged.json");
		Files.writeString(file, mapper.writeValueAsString(doc));
		captureLogs();

		assertThat(store.loadAll()).isEmpty();
		// 反面硬线:分流不许把真坏档一起治哑(那是「吵变哑」,方向更坏)。
		assertThat(logsAt(Level.WARN)).as("真坏档必须仍叫得出声").hasSize(1);
		assertThat(logsAt(Level.WARN).get(0)).contains("留尸检");
		assertThat(file).as("坏档保留原样(留尸检)").exists();
	}

	@Test
	void startupSummaryCountsLoadedSkippedAndRefused() throws Exception {
		FileSessionStore store = store();
		store.persist(playingSession("save-a"));
		store.persist(playingSession("save-b"));
		Files.writeString(tmp.resolve("quota-2026-08.json"), "{\"month\":\"2026-08\"}");
		Files.writeString(tmp.resolve("quota-2026-09.json"), "{\"month\":\"2026-09\"}");
		Files.writeString(tmp.resolve("save-bad.json"), "{ not json !!");
		captureLogs();

		store.loadAll();

		// 这条汇总是分流判据能成立的前提:判据若将来把自己人也筛掉,失效方式是全量静默,
		// 而「载入 0 档,跳过 N 个」是它唯一的现形处 —— 故计数本身必须被守住。
		assertThat(logsAt(Level.INFO)).anySatisfy(line -> assertThat(line)
				.contains("启动回载")
				.contains("载入 2 档")
				.contains("跳过 2 个非存档文件")
				.contains("1 档拒载"));
	}

	// ── 路径安全断言(附录 A 第 3 条):拒绝启动,不降级 ─────────────────────

	@Test
	void storeDirInsideWebRootRefusesStartup() {
		Path webRoot = tmp.resolve("classes/static");
		Path inside = webRoot.resolve("data");
		assertThatThrownBy(() -> FileSessionStore.assertOutsideWebRoot(
				inside.toAbsolutePath().normalize(), List.of(webRoot.toAbsolutePath().normalize())))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("web 根");
		// web 根自身也不行。
		assertThatThrownBy(() -> FileSessionStore.assertOutsideWebRoot(
				webRoot.toAbsolutePath().normalize(), List.of(webRoot.toAbsolutePath().normalize())))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void storeDirOutsideWebRootPasses() {
		Path webRoot = tmp.resolve("classes/static");
		Path sibling = tmp.resolve("data");
		assertThatCode(() -> FileSessionStore.assertOutsideWebRoot(
				sibling.toAbsolutePath().normalize(), List.of(webRoot.toAbsolutePath().normalize())))
				.doesNotThrowAnyException();
	}

	// ── 防御:非法 saveId 拒写(不抛、不落文件)──────────────────────────────

	@Test
	void pathySaveIdIsRefusedWithoutWriting() throws Exception {
		FileSessionStore store = store();
		GameSession session = new GameSession("../escape", playingSession("x").engine(), actions());
		assertThatCode(() -> store.persist(session)).doesNotThrowAnyException();
		try (Stream<Path> files = Files.list(tmp)) {
			assertThat(files).isEmpty();
		}
	}

	// ── helpers ────────────────────────────────────────────────────────

	private GameSession playingSession(String saveId) {
		List<AttributeAxis> axes = registry.meta("rules_creepy").attributes();
		Engine engine = new Engine(minimalWorld(), mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		return new GameSession(saveId, engine, actions());
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

	private ObjectNode fusionWorld() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4");
		w.put("mode", "hybrid");
		w.putArray("archetypes").add("cultivation").add("rules_creepy");
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		attrs.put("hp", 80).put("mana", 60).put("realm", 10).put("san", 90);
		w.putArray("rules");
		ArrayNode endings = w.putArray("endings");
		endings.addObject().put("id", "body_perish").put("title", "身死道消")
				.put("condition", "气血归零").put("outcome", "failure").put("reached", false);
		endings.addObject().put("id", "mind_shatter").put("title", "道心崩缺")
				.put("condition", "道心归零").put("outcome", "failure").put("reached", false);
		return w;
	}

	private ObjectNode fusionTurn(int hp, int mana, int realm, int san) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "识海微烫。");
		ObjectNode upd = t.putObject("stateUpdate");
		upd.put("hp", hp).put("mana", mana).put("realm", realm).put("san", san);
		return t;
	}

	private ObjectNode simpleTurn(int hp, int san) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "走廊的灯闪了一下。");
		t.putObject("stateUpdate").put("hp", hp).put("san", san);
		return t;
	}
}
