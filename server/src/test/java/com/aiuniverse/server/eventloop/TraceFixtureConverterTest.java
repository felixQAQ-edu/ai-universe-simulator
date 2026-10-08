package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.persistence.TurnTrace;
import com.aiuniverse.server.persistence.TurnTraceCodec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-031 刀 5a · 原始轨迹 → 回归夹具转换。原始轨迹全部由真实采集在测试里跑出({@link TraceScenarios}),
 * 不碰任何线上文件。临时目录在仓库之外({@code @TempDir}),故转换工具的「仓库之外」闸门在这里照常生效。
 */
class TraceFixtureConverterTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ArchetypeRegistry REGISTRY = new ArchetypeRegistry();
	private static final Map<String, TraceScenarios.Run> RUNS = new TraceScenarios(MAPPER, REGISTRY).all();
	private static final Path REPO = TraceFixtureConverter.findRepoRoot(Path.of("").toAbsolutePath());
	/** 形如线上的 saveId(UUID),用来查「原 saveId 不残留」。 */
	private static final String ONLINE_LIKE_ID = "3f2b9c1e-7a4d-4e8b-9c0f-1a2b3c4d5e6f";

	@TempDir
	Path tmp;

	private TraceFixtureConverter converter() {
		return new TraceFixtureConverter(MAPPER, REGISTRY, REPO);
	}

	/** 把一段跑出来的轨迹写成「线上取回的原始文件」形状(每行一条,换行结尾),可选逐条改写。 */
	private Path rawFile(String scenario, UnaryOperator<TurnTrace> edit) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (TurnTrace t : RUNS.get(scenario).traces()) {
			sb.append(TurnTraceCodec.encode(edit.apply(withSaveId(t, ONLINE_LIKE_ID)), MAPPER)).append('\n');
		}
		Path p = tmp.resolve(scenario + ".trace.jsonl");
		Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
		return p;
	}

	// ── 第 4 步:端到端演示(原始 → 转换 → 扫描报告 → 夹具回放一致)─────────

	@Test
	void endToEndDemoRawToFixtureToReplay() throws IOException {
		List<Path> fixtures = new ArrayList<>();
		for (String scenario : List.of("box_scene_through_leave", "box_scene_leave_degraded", "repaired",
				"degraded_repair_failed", "lifetime_clamp", "lifetime_exit_action", "ending")) {
			Path out = tmp.resolve(scenario + ".fixture.jsonl");
			TraceFixtureConverter.Result r = converter().convert(rawFile(scenario, t -> t),
					scenario.replace('_', '-'), out);
			assertThat(r.refusals()).as(scenario).isEmpty();
			assertThat(r.written()).isTrue();
			assertThat(r.reportText()).contains("保留字段(白名单): " + String.join(", ", TraceFixture.KEYS))
					.contains("saveId 替换为: fixture-" + scenario.replace('_', '-')).contains("已写出夹具");
			for (TurnTrace t : RUNS.get(scenario).traces()) {
				assertThat(r.reportText()).contains("actionId=" + t.actionId());
			}
			fixtures.add(out);
			if (scenario.equals("box_scene_through_leave")) {
				System.out.println("──── 演示用扫描报告样例 ────\n" + r.reportText());
			}
		}
		assertThat(TraceFixtureReplayTest.assertFixturesReplay(fixtures)).isPositive();
	}

	// ── 白名单 ───────────────────────────────────────────────────────────

	/** 期望清单在这里独立写一遍:改动 {@link TraceFixture#KEYS} 或编码而不改这里,本测试变红。 */
	@Test
	void outputKeysAreExactlyTheWhitelist() throws IOException {
		Path out = tmp.resolve("w.fixture.jsonl");
		assertThat(converter().convert(rawFile("box_scene_through_leave", t -> t), "w", out).written()).isTrue();
		for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
			JsonNode n = MAPPER.readTree(line);
			assertThat(names(n)).containsExactly("fixtureSchema", "saveId", "commit", "actionId", "path", "pre",
					"parsed", "streamedNarrative", "post");
			assertThat(names(n.get("post"))).containsExactly("sha256", "attributes");
		}
	}

	/** 白名单之外的字段里即便藏着密钥形态 / IP,也不进入输出(丢弃,不是扫描后拦截)。 */
	@Test
	void secretsInNonWhitelistedFieldsAreDroppedNotCopied() throws IOException {
		String key = "sk-" + "A1b2C3d4E5f6G7h8I9j0";
		Path in = rawFile("repaired", t -> new TurnTrace(t.schema(), t.saveId(), t.turnBefore(), "2026-10-08T01:02:03Z",
				t.commit(), t.actionId(), t.path(), t.pre(), t.parsed(), t.degradeReason(), t.streamedNarrative(),
				t.promptSha256(), List.of(new TurnTrace.CallUsage("main", new LlmUsage(1, 2, 3, 4, 5), key, 0)),
				t.durMs(), List.of("Authorization: Bearer abc.def", "from 203.0.113.7"), t.post()));
		Path out = tmp.resolve("d.fixture.jsonl");
		TraceFixtureConverter.Result r = converter().convert(in, "d", out);
		assertThat(r.refusals()).isEmpty();
		String text = Files.readString(out, StandardCharsets.UTF_8);
		assertThat(text).doesNotContain(key).doesNotContain("Bearer").doesNotContain("203.0.113.7")
				.doesNotContain("2026-10-08T01:02:03Z").doesNotContain("recordedAt").doesNotContain("usage")
				.doesNotContain("repairErrors").doesNotContain("durMs");
	}

	@Test
	void originalSaveIdDoesNotRemainAndReplayStillMatchesRecordedPost() throws IOException {
		Path out = tmp.resolve("s.fixture.jsonl");
		TraceFixtureConverter.Result r = converter().convert(rawFile("box_scene_through_leave", t -> t), "s", out);
		assertThat(r.written()).isTrue();
		String text = Files.readString(out, StandardCharsets.UTF_8);
		assertThat(text).doesNotContain(ONLINE_LIKE_ID);
		List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
		List<TurnTrace> raw = RUNS.get("box_scene_through_leave").traces();
		for (int i = 0; i < lines.size(); i++) {
			JsonNode n = MAPPER.readTree(lines.get(i));
			assertThat(n.get("saveId").asString()).isEqualTo("fixture-s");
			assertThat(n.path("post").path("sha256").asString()).as("post 是线上记录的那一份,不是重算的")
					.isEqualTo(raw.get(i).post().sha256());
		}
		assertThat(r.reportText()).as("报告里原 saveId 残留一行为 0").containsPattern("残留:原 saveId\\s+0\\n");
	}

	// ── 扫描器 ───────────────────────────────────────────────────────────

	@ParameterizedTest
	@ValueSource(strings = { "sk-0123456789abcdefXYZ", "Authorization: x", "Bearer eyJhbGciOi.x", "10.0.0.1",
			"203.0.113.7", "2001:db8:0:0:0:0:0:1", "fe80::1", "a@b.co", "api_key=1" })
	void scannerFlagsPlantedSamples(String sample) {
		assertThat(FixtureScanner.scan("前文" + sample + "后文", Map.of())).isNotEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "夜里 12:34:56 灯灭了", "版本 1.2.3 发布", "0455a7d5c3e1f0b2a9d8c7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6",
			"{\"hp\":85.0,\"san\":70}", "它停了一下,又往前走。" })
	void scannerStaysQuietOnOrdinaryContent(String sample) {
		assertThat(FixtureScanner.scan(sample, Map.of())).isEmpty();
	}

	/** 白名单字段里的命中(用 commit 承载:它不影响落账,回放仍一致,只有扫描能拦住)→ 拒绝写出。 */
	@ParameterizedTest
	@ValueSource(strings = { "sk-0123456789abcdefXYZ", "Bearer abc.def", "198.51.100.23", "me@example.com" })
	void hitInWhitelistedFieldRefusesOutput(String planted) throws IOException {
		Path out = tmp.resolve("p.fixture.jsonl");
		Files.writeString(out, "旧的残档"); // 拒绝时不许留下上一次的产物
		TraceFixtureConverter.Result r = converter().convert(rawFile("normal", t -> withCommit(t, planted)), "p", out);
		assertThat(r.written()).isFalse();
		assertThat(r.refusals()).anyMatch(s -> s.startsWith("敏感形态扫描命中"));
		assertThat(out).doesNotExist();
		assertThat(r.report()).exists();
	}

	// ── 自检与闸门 ───────────────────────────────────────────────────────

	@Test
	void lineThatDoesNotReplayConsistentlyRefusesOutput() throws IOException {
		Path in = rawFile("normal", t -> {
			Map<String, Double> attrs = new LinkedHashMap<>(t.post().attributes());
			attrs.put("hp", attrs.get("hp") + 1);
			return new TurnTrace(t.schema(), t.saveId(), t.turnBefore(), t.recordedAt(), t.commit(), t.actionId(),
					t.path(), t.pre(), t.parsed(), t.degradeReason(), t.streamedNarrative(), t.promptSha256(),
					t.usage(), t.durMs(), t.repairErrors(), new TurnTrace.Post(t.post().sha256(), attrs));
		});
		Path out = tmp.resolve("x.fixture.jsonl");
		TraceFixtureConverter.Result r = converter().convert(in, "x", out);
		assertThat(r.written()).isFalse();
		assertThat(r.refusals()).anyMatch(s -> s.contains("档 1 回放不一致"));
		assertThat(out).doesNotExist();
	}

	@Test
	void inputInsideTheRepositoryIsRefused() throws IOException {
		Path in = rawFile("normal", t -> t);
		TraceFixtureConverter asIfRepoWereTmp = new TraceFixtureConverter(MAPPER, REGISTRY, tmp);
		assertThatThrownBy(() -> asIfRepoWereTmp.convert(in, "r", Path.of("/nonexistent-outside/x.jsonl")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("输入路径在仓库目录之内");
	}

	@Test
	void outputInsideTheRepositoryIsRefused() throws IOException {
		Path in = rawFile("normal", t -> t);
		Path out = REPO.resolve("server/src/test/resources/trace-fixtures/should-not-be-written.jsonl");
		assertThatThrownBy(() -> converter().convert(in, "r", out)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("输出路径在仓库目录之内");
		assertThat(out).doesNotExist();
	}

	@Test
	void moreThanOneSaveIdInOneFileIsRefused() throws IOException {
		Path a = rawFile("normal", t -> t);
		Files.writeString(a, TurnTraceCodec.encode(RUNS.get("ending").last(), MAPPER) + "\n",
				java.nio.file.StandardOpenOption.APPEND);
		TraceFixtureConverter.Result r = converter().convert(a, "m", tmp.resolve("m.fixture.jsonl"));
		assertThat(r.written()).isFalse();
		assertThat(r.refusals()).anyMatch(s -> s.contains("个 saveId"));
	}

	// ── 工具 ─────────────────────────────────────────────────────────────

	private static List<String> names(JsonNode n) {
		List<String> out = new ArrayList<>();
		for (Iterator<String> it = n.propertyNames().iterator(); it.hasNext();) {
			out.add(it.next());
		}
		return out;
	}

	private static TurnTrace withSaveId(TurnTrace t, String saveId) {
		return new TurnTrace(t.schema(), saveId, t.turnBefore(), t.recordedAt(), t.commit(), t.actionId(), t.path(),
				t.pre(), t.parsed(), t.degradeReason(), t.streamedNarrative(), t.promptSha256(), t.usage(), t.durMs(),
				t.repairErrors(), t.post());
	}

	private static TurnTrace withCommit(TurnTrace t, String commit) {
		return new TurnTrace(t.schema(), t.saveId(), t.turnBefore(), t.recordedAt(), commit, t.actionId(), t.path(),
				t.pre(), t.parsed(), t.degradeReason(), t.streamedNarrative(), t.promptSha256(), t.usage(), t.durMs(),
				t.repairErrors(), t.post());
	}
}
