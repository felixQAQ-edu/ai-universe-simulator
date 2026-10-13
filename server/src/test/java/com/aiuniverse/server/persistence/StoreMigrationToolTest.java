package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.eventloop.EventLoopService;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.SentinelSplitter;
import com.aiuniverse.server.eventloop.TurnEventSink;
import com.aiuniverse.server.eventloop.TurnPromptBuilder;
import com.aiuniverse.server.eventloop.TurnStateMachine;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-032 刀 2 · 导入 / 导出离线工具(Testcontainers 真 PG;走 {@link StoreMigrationTool#run} 全流程,
 * 与 {@code main} 同一条路 —— 起工具自己的收窄上下文、Flyway migrate、导入 / 导出、报告、退出码)。
 * 每条用例各建一个独立的库。数据全部合成({@link SyntheticSaves},W-11)。
 */
@RequiresDocker
@Testcontainers
class StoreMigrationToolTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	private static final AtomicInteger DB_SEQ = new AtomicInteger();

	@TempDir
	Path tmp;

	private final SyntheticSaves syn = new SyntheticSaves();
	private final ObjectMapper mapper = new ObjectMapper();

	/** 一次工具调用的结果。 */
	record Run(int code, String report) {
	}

	private static String newDb() throws SQLException {
		String db = "mig" + DB_SEQ.incrementAndGet();
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				Statement st = c.createStatement()) {
			st.execute("CREATE DATABASE " + db);
		}
		return db;
	}

	private static String url(String db) {
		return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db;
	}

	private static JdbcTemplate jdbc(String db) {
		return new JdbcTemplate(new DriverManagerDataSource(url(db), pg.getUsername(), pg.getPassword()));
	}

	static String[] args(String verb, Path dir, String db, String... extra) {
		List<String> a = new ArrayList<>(List.of(verb, dir.toString(), "--spring.profiles.active=pg",
				"--spring.datasource.url=" + url(db), "--spring.datasource.username=" + pg.getUsername(),
				"--spring.datasource.password=" + pg.getPassword()));
		Collections.addAll(a, extra);
		return a.toArray(String[]::new);
	}

	static Run tool(String[] args, MigrationPathGate gate, StoreMigrationTool.Seams seams) {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int code = StoreMigrationTool.run(args, gate, seams, new PrintStream(buf, true, StandardCharsets.UTF_8));
		return new Run(code, buf.toString(StandardCharsets.UTF_8));
	}

	static Run tool(String verb, Path dir, String db) {
		return tool(args(verb, dir, db), MigrationPathGate.strict(), StoreMigrationTool.Seams.DEFAULT);
	}

	private static String snapshot(String db, String saveId) {
		return jdbc(db).queryForObject("SELECT snapshot::text FROM game_session WHERE save_id = ?", String.class,
				saveId);
	}

	private static long importRows(String db) {
		return jdbc(db).queryForObject("SELECT count(*) FROM game_session WHERE source = 'import'", Long.class);
	}

	private static List<String> ids(String db) {
		return jdbc(db).queryForList("SELECT save_id FROM game_session ORDER BY save_id", String.class);
	}

	private static byte[] bytes(Path p) throws IOException {
		return Files.readAllBytes(p);
	}

	// ── 测试面 1:json 列原样保存 ──────────────────────────────────────────

	@Test
	void jsonColumnKeepsWhitespaceKeyOrderAndNonAsciiByteForByte() throws Exception {
		String db = newDb();
		Path src = Files.createDirectories(tmp.resolve("src"));
		String odd = syn.oddlyFormatted("s-odd");
		SyntheticSaves.write(src, "s-odd.json", odd);
		assertThat(odd).startsWith("\n  ").contains("\\u00e9").contains("中文").contains("\t ");

		Run r = tool("import", src, db);

		assertThat(r.code()).as(r.report()).isZero();
		assertThat(snapshot(db, "s-odd").getBytes(StandardCharsets.UTF_8)).isEqualTo(bytes(src.resolve("s-odd.json")));
	}

	// ── 测试面 2:置顶两个陷阱,原文入库、逐字节相等 ─────────────────────────

	@Test
	void settlingPhaseAndLegacyAnimalDocsAreStoredAsTheirOriginalText() throws Exception {
		String db = newDb();
		Path src = Files.createDirectories(tmp.resolve("src"));
		String settling = syn.withPhase("s-phase-settling", com.aiuniverse.server.eventloop.TurnPhase.SETTLING);
		String legacy = syn.legacyAnimal("s-animal-legacy");
		SyntheticSaves.write(src, "s-phase-settling.json", settling);
		SyntheticSaves.write(src, "s-animal-legacy.json", legacy);
		// 陷阱是真的:decode → encode 与原文不等(否则这条用例没有牙)
		for (String[] c : List.of(new String[] { "s-phase-settling", settling }, new String[] { "s-animal-legacy", legacy })) {
			GameSession s = SessionDocument.decode(c[0], mapper.readTree(c[1]), mapper, new ArchetypeRegistry());
			assertThat(mapper.writeValueAsString(SessionDocument.encode(s, mapper))).as(c[0]).isNotEqualTo(c[1]);
		}

		Run r = tool("import", src, db);

		assertThat(r.code()).as(r.report()).isZero();
		assertThat(snapshot(db, "s-phase-settling").getBytes(StandardCharsets.UTF_8))
				.isEqualTo(bytes(src.resolve("s-phase-settling.json")));
		assertThat(snapshot(db, "s-animal-legacy").getBytes(StandardCharsets.UTF_8))
				.isEqualTo(bytes(src.resolve("s-animal-legacy.json")));
		assertThat(snapshot(db, "s-phase-settling")).contains("\"phaseHint\":\"SETTLING\"");
		assertThat(snapshot(db, "s-animal-legacy")).doesNotContain("legacy");
	}

	// ── 测试面 3(行为半边)+ 4:三分判据与对账读数 ───────────────────────────

	@Test
	void fullSyntheticDirectoryReconcilesAndUnreadableFilesForceNonZeroWhileReadableOnesLand() throws Exception {
		String db = newDb();
		Path src = tmp.resolve("src");
		syn.writeAll(src);
		long n;
		try (Stream<Path> s = Files.list(src)) {
			n = s.filter(p -> p.getFileName().toString().endsWith(".json")).count();
		}

		Run r = tool("import", src, db);

		assertThat(r.code()).as(r.report()).isEqualTo(StoreMigrationTool.EXIT_NEEDS_REVIEW);
		int imported = SyntheticSaves.IMPORTABLE.size();
		assertThat(n).isEqualTo(imported + 1 + SyntheticSaves.UNREADABLE.size());
		assertThat(r.report()).contains("候选 N(目录直接下 *.json)= " + n)
				.contains("imported = " + imported + "\n")
				.contains("skipped = 1(非存档 1 / 重复导入 0 / 库中已有且字节相同 0 / 库与源文件不一致·不覆盖 0)")
				.contains("integrity_failed = 0").contains("不可读 = 3")
				.contains("  - bad-truncated.json — JSON 解析失败")
				.contains("  - s-unknown-archetype.json — decode 拒载")
				.contains("  - bad name.json — 文件名非法")
				.contains("库中 source='import' 行数:导入前 0 / 导入后 " + imported)
				.contains("退出码 = 2");
		assertThat(ids(db)).containsExactlyInAnyOrderElementsOf(SyntheticSaves.IMPORTABLE);
		assertThat(importRows(db)).isEqualTo(imported);
		for (String id : SyntheticSaves.IMPORTABLE) {
			assertThat(snapshot(db, id).getBytes(StandardCharsets.UTF_8)).as(id).isEqualTo(bytes(src.resolve(id + ".json")));
		}
		// 报告不带存档内容、不带绝对路径
		assertThat(r.report()).doesNotContain(src.toString()).doesNotContain("hiddenLogic").doesNotContain("回头即被标记");
	}

	// ── 测试面 5:幂等四格 ─────────────────────────────────────────────────

	@Test
	void reimportingTheSameDirectoryIsANoOp() throws Exception {
		String db = newDb();
		Path src = Files.createDirectories(tmp.resolve("src"));
		SyntheticSaves.write(src, "s-a.json", syn.encode(syn.session("s-a", syn.world("rules_creepy"))));
		SyntheticSaves.write(src, "s-b.json", syn.encode(syn.session("s-b", syn.world("cthulhu"))));
		assertThat(tool("import", src, db).code()).isZero();
		String updatedAt = jdbc(db).queryForObject("SELECT string_agg(updated_at::text, ',' ORDER BY save_id) FROM game_session",
				String.class);

		Run again = tool("import", src, db);

		assertThat(again.code()).as(again.report()).isZero();
		assertThat(again.report()).contains("imported = 0\n").contains("重复导入 2").contains("导入前 2 / 导入后 2");
		assertThat(jdbc(db).queryForObject("SELECT string_agg(updated_at::text, ',' ORDER BY save_id) FROM game_session",
				String.class)).as("行没被碰过").isEqualTo(updatedAt);
		assertThat(snapshot(db, "s-a").getBytes(StandardCharsets.UTF_8)).isEqualTo(bytes(src.resolve("s-a.json")));
	}

	@Test
	void existingImportRowThatDiffersIsNotOverwrittenAndForcesNonZeroWhileOthersLand() throws Exception {
		String db = newDb();
		Path src = Files.createDirectories(tmp.resolve("src"));
		SyntheticSaves.write(src, "s-a.json", syn.encode(syn.session("s-a", syn.world("rules_creepy"))));
		assertThat(tool("import", src, db).code()).isZero();
		byte[] stored = snapshot(db, "s-a").getBytes(StandardCharsets.UTF_8);
		// 源文件变了(或导入后在 pg 下玩过 —— 同一格),另加一份新档
		SyntheticSaves.write(src, "s-a.json", " " + syn.encode(syn.session("s-a", syn.world("rules_creepy"))));
		SyntheticSaves.write(src, "s-new.json", syn.encode(syn.session("s-new", syn.world("apocalypse"))));

		Run r = tool("import", src, db);

		assertThat(r.code()).as(r.report()).isEqualTo(StoreMigrationTool.EXIT_NEEDS_REVIEW);
		assertThat(r.report()).contains("库与源文件不一致(不覆盖):\n  - s-a(source=import)").contains("imported = 1\n");
		assertThat(snapshot(db, "s-a").getBytes(StandardCharsets.UTF_8)).as("该行字节不变").isEqualTo(stored);
		assertThat(ids(db)).contains("s-new");
	}

	@Test
	void existingNativeRowIsNeverOverwritten() throws Exception {
		String db = newDb();
		Path empty = Files.createDirectories(tmp.resolve("empty"));
		assertThat(tool("import", empty, db).code()).isZero(); // 让 Flyway 建表
		String nativeSame = syn.encode(syn.session("s-native-same", syn.world("rules_creepy")));
		String nativeDiff = syn.encode(syn.session("s-native-diff", syn.world("rules_creepy")));
		JdbcTemplate j = jdbc(db);
		for (String[] row : List.of(new String[] { "s-native-same", nativeSame }, new String[] { "s-native-diff", nativeDiff })) {
			j.update("INSERT INTO game_session (save_id, snapshot, turn, status, source) VALUES (?, ?::json, 0, 'ongoing', 'native')",
					row[0], row[1]);
		}
		Path src = Files.createDirectories(tmp.resolve("src"));
		SyntheticSaves.write(src, "s-native-same.json", nativeSame);
		SyntheticSaves.write(src, "s-native-diff.json", nativeDiff + "\n");

		Run r = tool("import", src, db);

		assertThat(r.report()).contains("库中已有、与源文件字节相同(跳过):\n  - s-native-same(source=native)")
				.contains("库与源文件不一致(不覆盖):\n  - s-native-diff(source=native)").contains("imported = 0\n");
		assertThat(r.code()).isEqualTo(StoreMigrationTool.EXIT_NEEDS_REVIEW);
		assertThat(snapshot(db, "s-native-same")).isEqualTo(nativeSame);
		assertThat(snapshot(db, "s-native-diff")).as("原生行字节不变").isEqualTo(nativeDiff);
		assertThat(j.queryForList("SELECT DISTINCT source FROM game_session", String.class)).containsExactly("native");
	}

	// ── 测试面 6:往返 + 回退后以默认 profile 回载、续局一回合 ──────────────────

	@Test
	void fileToPgToFileRoundTripsByteForByteAndTheExportResumesUnderTheDefaultStore() throws Exception {
		String db = newDb();
		Path src = tmp.resolve("src");
		syn.writeAll(src);
		assertThat(tool("import", src, db).code()).isEqualTo(StoreMigrationTool.EXIT_NEEDS_REVIEW); // 有不可读档,见上一条

		Path out = tmp.resolve("exported");
		Run ex = tool("export", out, db);

		assertThat(ex.code()).as(ex.report()).isZero();
		int n = SyntheticSaves.IMPORTABLE.size();
		assertThat(ex.report()).contains("库中行数 N = " + n).contains("写出 = " + n).contains("拒写(save_id 非法)= 0")
				.contains("载不动(照样写出,回退后会被拒载)= 0");
		try (Stream<Path> s = Files.list(out)) {
			assertThat(s.map(p -> p.getFileName().toString()).sorted().toList())
					.containsExactlyElementsOf(SyntheticSaves.IMPORTABLE.stream().map(id -> id + ".json").sorted().toList());
		}
		for (String id : SyntheticSaves.IMPORTABLE) {
			assertThat(bytes(out.resolve(id + ".json"))).as(id).isEqualTo(bytes(src.resolve(id + ".json")));
		}

		// 回退:默认 profile 的存储(FileSessionStore)指向导出目录 → 回载 → 续局一回合
		ArchetypeRegistry registry = new ArchetypeRegistry();
		FileSessionStore store = new FileSessionStore(out.toString(), mapper, registry);
		GameSessionManager sessions = new GameSessionManager(mapper, store);
		sessions.reloadFromStore();
		assertThat(sessions.activeCount()).isEqualTo(n);
		GameSession s = sessions.get("s-rules-creepy");
		int before = s.engine().turn();
		new TurnStateMachine(new EventLoopService(scriptedLlm(), new TurnPromptBuilder(registry), mapper), store)
				.submitAction(s, "A", NO_OP_SINK);
		assertThat(s.engine().turn()).isEqualTo(before + 1);
		assertThat(mapper.readTree(Files.readString(out.resolve("s-rules-creepy.json"))).path("state").path("turn").asInt())
				.as("盘上也推进了一回合").isEqualTo(before + 1);
	}

	/**
	 * 脚本化 LLM(与 {@code LandedTurnPersistTest} 同形)。⚠️ 不用 {@code MockLlmClient}:它按字符回显整份 prompt、
	 * 每字符 sleep 40 ms 且无哨兵 → 走修复再降级。被测的是「回退后的存档能续局」,LLM 替身不在被测面上。
	 */
	private static LlmClient scriptedLlm() {
		return new LlmClient() {
			final Deque<String> responses = new ArrayDeque<>(List.of("走廊的灯又闪了一下。" + SentinelSplitter.SENTINEL
					+ "{\"stateUpdate\":{\"hp\":55,\"san\":45,\"timeline\":\"回到走廊\"},"
					+ "\"triggeredRuleIds\":[],\"discoveredRuleIds\":[],"
					+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"继续往前\"},{\"id\":\"B\",\"text\":\"退回去\"}],"
					+ "\"ending\":null}"));

			@Override
			public void streamChat(com.aiuniverse.server.llm.ChatRequest request, TokenStream sink) {
				String r = responses.poll();
				if (r == null) {
					throw new LlmException("脚本耗尽");
				}
				sink.onToken(r);
			}
		};
	}

	private static final TurnEventSink NO_OP_SINK = new TurnEventSink() {
		@Override public void narrative(String text) { }
		@Override public void delta(ObjectNode d) { }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String code, String message) { }
	};

	// ── 测试面 7:D-2 故障测试 —— 第 k 档读回被篡改 → 整批回滚、零残留 ───────────

	@Test
	void tamperedReadBackOfTheKthDocRollsBackTheWholeBatch() throws Exception {
		String db = newDb();
		Path empty = Files.createDirectories(tmp.resolve("empty"));
		SyntheticSaves.write(empty, "s-pre.json", syn.encode(syn.session("s-pre", syn.world("rules_creepy"))));
		assertThat(tool("import", empty, db).code()).isZero(); // 库里先有一行 import,检验「零残留」不是「零行」
		long before = importRows(db);
		Path src = Files.createDirectories(tmp.resolve("src"));
		List<String> batch = List.of("s-k1", "s-k2", "s-k3", "s-k4");
		for (String id : batch) {
			SyntheticSaves.write(src, id + ".json", syn.encode(syn.session(id, syn.world("rules_creepy"))));
		}
		int k = 3;
		AtomicInteger reads = new AtomicInteger();
		SessionImporter.ReadBack tampering = (jdbc, saveId) -> {
			SessionImporter.Row row = SessionImporter.DEFAULT_READ_BACK.read(jdbc, saveId);
			return reads.incrementAndGet() == k ? new SessionImporter.Row(row.snapshot().replaceFirst("\"t\"", "\"T\""),
					row.turn(), row.status()) : row;
		};

		Run r = tool(args("import", src, db), MigrationPathGate.strict(),
				new StoreMigrationTool.Seams(tampering, SessionImporter.DEFAULT_SOURCE_READER));

		assertThat(r.code()).as(r.report()).isEqualTo(StoreMigrationTool.EXIT_INTEGRITY);
		assertThat(r.report()).contains("integrity_failed = 1").contains("读回不一致:s-k3,第一个不同字节的偏移 = ")
				.contains("imported = 0(已回滚)").contains("✓ 零残留");
		assertThat(importRows(db)).as("source='import' 行数 = 导入前").isEqualTo(before);
		assertThat(ids(db)).as("前 k−1 档也不在库里").containsExactly("s-pre");
	}

	// ── 测试面 8:月账与轨迹不进库、traces/ 下文件不被打开 ─────────────────────

	@Test
	void quotaLedgerIsNotImportedAndTracesAreNeverOpened() throws Exception {
		String db = newDb();
		Path src = tmp.resolve("src");
		syn.writeAll(src);
		List<Path> opened = Collections.synchronizedList(new ArrayList<>());
		SessionImporter.SourceReader recording = p -> {
			opened.add(p);
			return Files.readAllBytes(p);
		};

		Run r = tool(args("import", src, db), MigrationPathGate.strict(),
				new StoreMigrationTool.Seams(SessionImporter.DEFAULT_READ_BACK, recording));

		assertThat(r.code()).as(r.report()).isEqualTo(StoreMigrationTool.EXIT_NEEDS_REVIEW);
		assertThat(jdbc(db).queryForObject("SELECT count(*) FROM game_session WHERE save_id LIKE 'quota-%'", Long.class))
				.isZero();
		assertThat(ids(db)).doesNotContain("s-in-traces");
		assertThat(opened).as("所有读源文件都走这一个接缝").isNotEmpty()
				.allSatisfy(p -> assertThat(p.getParent()).isEqualTo(src))
				.noneMatch(p -> p.toString().contains("traces"));
	}

	// ── 测试面 9:导出安全 ───────────────────────────────────────────────

	@Test
	void exportRefusesANonEmptyTargetAndWritesNothing() throws Exception {
		String db = newDb();
		Path out = Files.createDirectories(tmp.resolve("out"));
		Files.writeString(out.resolve(SyntheticSaves.QUOTA), "{\"month\":\"2026-07\"}");

		Run r = tool("export", out, db);

		assertThat(r.code()).isEqualTo(StoreMigrationTool.EXIT_TOOL_ERROR);
		assertThat(r.report()).contains("导出目标目录不为空");
		try (Stream<Path> s = Files.list(out)) {
			assertThat(s.toList()).hasSize(1);
		}
	}

	@Test
	void exportRefusesIllegalSaveIdsAndStillWritesUndecodableRows() throws Exception {
		String db = newDb();
		Path empty = Files.createDirectories(tmp.resolve("empty"));
		SyntheticSaves.write(empty, "s-ok.json", syn.encode(syn.session("s-ok", syn.world("rules_creepy"))));
		assertThat(tool("import", empty, db).code()).isZero();
		JdbcTemplate j = jdbc(db);
		j.update("INSERT INTO game_session (save_id, snapshot, turn, status, source) VALUES ('../escape', '{}'::json, 0, 'ongoing', 'native')");
		ObjectNode unknown = syn.doc(syn.session("s-undecodable", syn.world("rules_creepy")));
		((tools.jackson.databind.node.ArrayNode) unknown.path("world").path("archetypes")).removeAll().add("no_such_world");
		String unknownText = mapper.writeValueAsString(unknown);
		j.update("INSERT INTO game_session (save_id, snapshot, turn, status, source) VALUES ('s-undecodable', ?::json, 0, 'ongoing', 'native')",
				unknownText);
		Path out = tmp.resolve("out");

		Run r = tool("export", out, db);

		assertThat(r.code()).as(r.report()).isEqualTo(StoreMigrationTool.EXIT_NEEDS_REVIEW);
		assertThat(r.report()).contains("库中行数 N = 3").contains("写出 = 2").contains("拒写(save_id 非法)= 1\n  - ../escape")
				.contains("载不动(照样写出,回退后会被拒载)= 1\n  - s-undecodable").contains("对账:N = 写出 + 拒写 → 3 = 2 + 1 ✓");
		assertThat(Files.readString(out.resolve("s-undecodable.json"))).isEqualTo(unknownText);
		assertThat(Files.exists(tmp.resolve("escape.json"))).isFalse();
		try (Stream<Path> s = Files.list(out)) {
			assertThat(s.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly("s-ok.json", "s-undecodable.json");
		}
	}

	// ── 测试面 10(豁免半边):合成夹具在仓库内 → 严格闸门拒绝,测试豁免放行 ────────

	@Test
	void syntheticFixtureInsideTheRepoIsRefusedByTheStrictGateAndAcceptedOnlyWithTheTestExemption() throws Exception {
		String db = newDb();
		Path inRepo = Path.of("target", "store-migration-fixture-" + DB_SEQ.get()).toAbsolutePath();
		Files.createDirectories(inRepo);
		SyntheticSaves.write(inRepo, "s-fixture.json", syn.encode(syn.session("s-fixture", syn.world("rules_creepy"))));

		Run strict = tool("import", inRepo, db);
		Run exempt = tool(args("import", inRepo, db), MigrationPathGate.forSyntheticFixturesInTests(List.of()),
				StoreMigrationTool.Seams.DEFAULT);

		assertThat(strict.code()).isEqualTo(StoreMigrationTool.EXIT_TOOL_ERROR);
		assertThat(strict.report()).contains("导入源目录路径在仓库目录之内");
		assertThat(exempt.code()).as(exempt.report()).isZero();
		assertThat(ids(db)).containsExactly("s-fixture");
	}
}
