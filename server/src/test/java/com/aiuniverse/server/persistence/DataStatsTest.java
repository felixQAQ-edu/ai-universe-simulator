package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aiuniverse.server.llm.LlmProperties;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.quota.TokenPricing;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-033 · /data 只读统计。输入全部合成(W-11):存档走 {@link SyntheticSaves},轨迹由本测试按 {@link TurnTraceCodec} 编码写出。
 * 文件名一律用 UUID 形态的 saveId —— 报告里一个都不许出现。
 */
class DataStatsTest {

	/** 叙事哨兵:放进 title / timeline / log 正文 / 选项文案 / hint / 轨迹正文,报告里一个都不许出现。 */
	static final List<String> SENTINELS = List.of("哨兵标题甲乙丙丁", "哨兵时间线戊己庚辛", "哨兵正文壬癸子丑",
			"哨兵选项寅卯辰巳", "哨兵提示午未申酉", "哨兵轨迹正文戌亥");

	static final String SID_TURN0 = "0b5e3c2a-1111-4a1a-9a1a-000000000000";
	static final String SID_ONGOING = "0b5e3c2a-2222-4a1a-9a1a-000000000000";
	static final String SID_ENDED = "0b5e3c2a-3333-4a1a-9a1a-000000000000";
	static final String SID_FUSION = "0b5e3c2a-4444-4a1a-9a1a-000000000000";
	static final String SID_ENDED_NO_OUTCOME = "0b5e3c2a-5555-4a1a-9a1a-000000000000";

	final SyntheticSaves syn = new SyntheticSaves();
	final ObjectMapper mapper = syn.mapper;
	final DataStats.PriceSource price = new DataStats.PriceSource("synthetic-provider",
			new LlmProperties.Price(2.0, 0.04, 8.0));
	final DataStats stats = new DataStats(mapper, syn.registry, price);
	/** 测试的临时目录都在 /tmp(仓库之外),故直接用生产那道严格闸门。 */
	final MigrationPathGate gate = MigrationPathGate.strictWithWebRoots(List.of());

	@TempDir
	Path tmp;

	// ── 合成输入 ─────────────────────────────────────────────────────

	ObjectNode saveDoc(String sid, ObjectNode world, int turn, String status, List<String> issues) {
		ObjectNode doc = syn.doc(syn.session(sid, world));
		((ObjectNode) doc.path("world").path("world")).put("title", SENTINELS.get(0));
		ObjectNode st = (ObjectNode) doc.path("state");
		st.put("turn", turn).put("status", status).put("timeline", SENTINELS.get(1));
		ArrayNode log = st.putArray("log");
		if (turn > 0) {
			log.addObject().put("turn", turn).put("narrative", SENTINELS.get(2)).put("playerAction", "A");
		}
		ArrayNode actions = doc.putArray("currentActions");
		actions.addObject().put("id", "A").put("text", SENTINELS.get(3)).put("hint", SENTINELS.get(4));
		ArrayNode is = doc.putArray("issues");
		issues.forEach(is::add);
		return doc;
	}

	void writeSave(Path dir, String sid, ObjectNode doc) throws IOException {
		SyntheticSaves.write(dir, sid + ".json", mapper.writeValueAsString(doc));
	}

	TurnTrace trace(String sid, ObjectNode pre, String path, String reason, List<TurnTrace.CallUsage> usage,
			long durMs, List<String> repairErrors, String recordedAt) {
		ObjectNode parsed = null;
		String streamed = null;
		if (TurnTrace.PATH_SETTLED.equals(path)) {
			parsed = mapper.createObjectNode();
			parsed.putArray("availableActions").addObject().put("id", "A").put("text", SENTINELS.get(3))
					.put("hint", SENTINELS.get(4));
		} else {
			streamed = SENTINELS.get(5);
		}
		return new TurnTrace(TurnTrace.SCHEMA, sid, 1, recordedAt, "abc1234", "A", path, pre, parsed, reason, streamed,
				null, usage, durMs, repairErrors, new TurnTrace.Post("00", Map.of("hp", 50.0)));
	}

	static TurnTrace.CallUsage call(String name, String model, long prompt, long completion, long hit, long miss) {
		return new TurnTrace.CallUsage(name, new LlmUsage(prompt, completion, prompt + completion, hit, miss), model,
				0);
	}

	void writeTraces(Path traces, String sid, List<TurnTrace> ts, boolean partialTail) throws IOException {
		StringBuilder b = new StringBuilder();
		for (TurnTrace t : ts) {
			b.append(TurnTraceCodec.encode(t, mapper)).append('\n');
		}
		if (partialTail) {
			b.append("{\"schema\":1,\"saveId\":\"");
		}
		Files.writeString(traces.resolve(sid + ".trace.jsonl"), b.toString(), StandardCharsets.UTF_8);
	}

	/** 一个覆盖全部情形的 /data 拷贝。 */
	Path fullData() throws IOException {
		Path data = Files.createDirectories(tmp.resolve("data"));
		writeSave(data, SID_TURN0, saveDoc(SID_TURN0, syn.world("rules_creepy"), 0, "ongoing", List.of()));
		writeSave(data, SID_ONGOING, saveDoc(SID_ONGOING, syn.world("apocalypse"), 4, "ongoing",
				List.of("T5 vigor 收束下限钳制 14->15")));
		ObjectNode ended = saveDoc(SID_ENDED, syn.world("rules_creepy"), 10, "ended",
				List.of("T9 致命轴濒零拒绝成功结局 a → 改判 dead(ADR-010 gate)"));
		((ObjectNode) ended.path("world").path("endings").get(0)).put("reached", true);
		writeSave(data, SID_ENDED, ended);
		writeSave(data, SID_FUSION, saveDoc(SID_FUSION, syn.fusionWorld(), 6, "ongoing", List.of()));
		ObjectNode noOutcome = saveDoc(SID_ENDED_NO_OUTCOME, syn.world("cthulhu"), 2, "ended", List.of());
		ObjectNode e0 = (ObjectNode) noOutcome.path("world").path("endings").get(0);
		e0.remove("outcome");
		e0.put("reached", true);
		writeSave(data, SID_ENDED_NO_OUTCOME, noOutcome);
		// 非存档(月账)与拒载(未知 archetype、截断 JSON)
		SyntheticSaves.write(data, "quota-2026-10.json", "{\"month\":\"2026-10\",\"spentCny\":0.5}");
		ObjectNode unknown = saveDoc("0b5e3c2a-6666-4a1a-9a1a-000000000000", syn.world("rules_creepy"), 3,
				"ongoing", List.of());
		((ArrayNode) unknown.path("world").path("archetypes")).removeAll().add("no_such_world");
		writeSave(data, "0b5e3c2a-6666-4a1a-9a1a-000000000000", unknown);
		SyntheticSaves.write(data, "0b5e3c2a-7777-4a1a-9a1a-000000000000.json", "{\"world\":{\"archetypes\":[\"ru");

		Path traces = Files.createDirectories(data.resolve("traces"));
		ObjectNode pre = saveDoc(SID_ONGOING, syn.world("apocalypse"), 3, "ongoing", List.of());
		List<TurnTrace> g1 = new ArrayList<>();
		g1.add(trace(SID_ONGOING, pre, TurnTrace.PATH_SETTLED, null,
				List.of(call("主调用", "model-synth-a", 1000, 200, 600, 400)), 3000, null, "2026-10-08T01:00:00Z"));
		g1.add(trace(SID_ONGOING, pre, TurnTrace.PATH_SETTLED, null,
				List.of(call("主调用", "model-synth-a", 1000, 200, 600, 400), call("修复", null, 500, 100, 0, 500)),
				5000, List.of("缺字段"), "2026-10-09T02:00:00Z"));
		g1.add(trace(SID_ONGOING, pre, TurnTrace.PATH_DEGRADED, "stream_interrupted", List.of(), -1, null,
				"2026-10-12T03:00:00Z"));
		writeTraces(traces, SID_ONGOING, g1, true);
		List<TurnTrace> g2 = List.of(trace(SID_ENDED, pre, TurnTrace.PATH_SETTLED, null,
				List.of(call("主调用", "model-synth-b", 2000, 300, 1000, 1000)), 4000, null, "2026-10-10T04:00:00Z"));
		writeTraces(traces, SID_ENDED, g2, false);
		return data;
	}

	String run(Path data) throws IOException {
		Path out = tmp.resolve("report.txt");
		stats.run(data, out, gate);
		return Files.readString(out, StandardCharsets.UTF_8);
	}

	// ── 存档组 ───────────────────────────────────────────────────────

	@Test
	void firstLineIsTheFixedDisclaimer() throws IOException {
		assertThat(run(fullData())).startsWith(DataStats.FIRST_LINE + "\n");
	}

	@Test
	void turnZeroIsCountedSeparatelyAndNotInDenominator() throws IOException {
		String r = run(fullData());
		assertThat(r).contains("- 载入:5\n").contains("- turn == 0(不进分母):1\n").contains("- 有效档(turn ≥ 1):4\n");
		// 合计 = 4 / 10 / 6 / 2 → 平均 5.5,中位数 5.0;turn==0 那档若进分母会变成 4.4
		assertThat(r).contains("  - 合计:平均 5.5,中位数 5.0(n = 4)\n");
	}

	@Test
	void endedAndOngoingAreSeparateLines() throws IOException {
		String r = run(fullData());
		assertThat(r).contains("  - ended:平均 6.0,中位数 6.0(n = 2)\n")
				.contains("  - ongoing:平均 5.0,中位数 5.0(n = 2)\n")
				.contains("- 单局最大回合数:10\n");
	}

	@Test
	void fusionIsItsOwnOrderedPairGroup() throws IOException {
		String r = run(fullData());
		assertThat(r).contains("  - cultivation × rules_creepy:1 / 4(25.0%)\n")
				.contains("  - rules_creepy:1 / 4(25.0%)\n")
				.doesNotContain("  - cultivation:");
	}

	@Test
	void rejectedAndNotASaveAreCounted() throws IOException {
		String r = run(fullData());
		assertThat(r).contains("- 候选档总数:8\n").contains("- 非存档跳过:1\n").contains("- 拒载:2\n");
	}

	@Test
	void outcomeDistributionIncludesMissingOutcome() throws IOException {
		String r = run(fullData());
		assertThat(r).contains("  - failure:1 / 2(50.0%)\n").contains("  - 无 outcome:1 / 2(50.0%)\n");
	}

	@Test
	void issueCountsAndThawLine() throws IOException {
		String r = run(fullData());
		assertThat(r).contains("结局 gate 改判 1 次;收束钳制 1 次")
				.contains("- 解冻判定(有效档 ≥ 50 且单局平均回合数 > 5):不满足(有效档 4,单局平均回合数 5.5)\n");
	}

	// ── 轨迹组 ───────────────────────────────────────────────────────

	@Test
	void degradedTurnsAreCountedAsTurns() throws IOException {
		String r = run(fullData());
		assertThat(r).contains("n = 2 局 / 4 回合").contains("- 降级率:1 / 4(25.0%)\n")
				.contains("    - stream_interrupted:1 / 1(100.0%)\n")
				.contains("- 回合侧修复率:1 / 4(25.0%)\n");
	}

	@Test
	void modelDistributionComesFromDataIncludingMissing() throws IOException {
		String r = run(fullData());
		assertThat(r).contains("  - model-synth-a:2 / 4(50.0%)\n").contains("  - model-synth-b:1 / 4(25.0%)\n")
				.contains("  - (缺 model 字段):1 / 4(25.0%)\n")
				.doesNotContain("V4").doesNotContain("deepseek-flash");
	}

	@Test
	void traceMetadataTokensCostAndDuration() throws IOException {
		String r = run(fullData());
		assertThat(r).contains("- 轨迹文件:2(无法解码 0,半截末行跳过 1)\n")
				.contains("- recordedAt:2026-10-08 ~ 2026-10-12(无 recordedAt 0 回合)\n")
				.contains("  - abc1234:4 / 4(100.0%)\n")
				.contains("  - 主调用(n = 3 次调用):prompt 1333.3 / completion 233.3 / total 1566.7;缓存命中率(输入 token)2200 / 4000(55.0%)\n")
				.contains("  - 修复(n = 1 次调用):prompt 500.0 / completion 100.0 / total 600.0;缓存命中率(输入 token)0 / 500(0.0%)\n")
				.contains("不含 world-gen")
				.contains("命中 0.04 / 未命中 2.0 / 输出 8.0")
				.contains("- durMs(-1 不进分布,单独计数 1;n = 3):p50 4000 / p90 5000 / max 5000\n");
		double perTurn = (cost(600, 400, 200) * 2 + cost(0, 500, 100) + cost(1000, 1000, 300)) / 4;
		assertThat(r).contains(String.format(java.util.Locale.ROOT, "  - 平均每回合:¥%.4f(n = 4 回合)", perTurn));
	}

	double cost(long hit, long miss, long out) {
		return TokenPricing.costCny(new LlmUsage(hit + miss, out, hit + miss + out, hit, miss), price.price());
	}

	@Test
	void emptyOrMissingTracesDirYieldsZeroWithoutError() throws IOException {
		Path data = Files.createDirectories(tmp.resolve("d2"));
		writeSave(data, SID_ONGOING, saveDoc(SID_ONGOING, syn.world("apocalypse"), 4, "ongoing", List.of()));
		String noDir = run(data);
		Files.createDirectories(data.resolve("traces"));
		String emptyDir = run(data);
		for (String r : List.of(noDir, emptyDir)) {
			assertThat(r).contains("n = 0 局 / 0 回合").contains("- 降级率:— / 0\n")
					.contains("- 回合侧修复率:— / 0\n").contains(";n = 0):—\n").contains("  - 平均每回合:¥—(n = 0 回合)");
		}
	}

	// ── 输出纪律 ─────────────────────────────────────────────────────

	@Test
	void reportContainsNoNarrativeSentinelAndNoUuid() throws IOException {
		String r = run(fullData());
		SENTINELS.forEach(s -> assertThat(r).doesNotContain(s));
		assertThat(DataStats.UUID.matcher(r).find()).isFalse();
		assertThat(r).doesNotContain("quota-2026-10").doesNotContain("0b5e3c2a").doesNotContain(".trace.jsonl");
	}

	@Test
	void guardRefusesNarrativeLeak() {
		DataStats.Narrative n = new DataStats.Narrative();
		n.add(mapper.getNodeFactory().stringNode(SENTINELS.get(2)));
		assertThatThrownBy(() -> stats.guard("报告\n" + SENTINELS.get(2) + "\n", n))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("叙事闸 1 条")
				.hasMessageNotContaining(SENTINELS.get(2));
		stats.guard("报告\n", n); // 不含 → 放行
	}

	@Test
	void guardRefusesUuidAndSensitiveForms() {
		DataStats.Narrative n = new DataStats.Narrative();
		assertThatThrownBy(() -> stats.guard("x " + SID_ENDED + "\n", n)).hasMessageContaining("uuid 1 处")
				.hasMessageNotContaining(SID_ENDED);
		assertThatThrownBy(() -> stats.guard("from 10.1.2.3\n", n)).hasMessageContaining("ipv4");
		assertThatThrownBy(() -> stats.guard("Authorization: x\n", n)).hasMessageContaining("authorization");
	}

	@Test
	void refusedReportIsNotWritten() throws IOException {
		Path data = fullData();
		Path out = tmp.resolve("leak.txt");
		DataStats leaky = new DataStats(mapper, syn.registry, new DataStats.PriceSource(SID_ENDED, price.price()));
		assertThatThrownBy(() -> leaky.run(data, out, gate)).isInstanceOf(IllegalStateException.class);
		assertThat(out).doesNotExist();
	}

	@Test
	void ratioFormatAndZeroDenominator() {
		assertThat(DataStats.ratio(3, 4)).isEqualTo("3 / 4(75.0%)");
		assertThat(DataStats.ratio(0, 7)).isEqualTo("0 / 7(0.0%)");
		assertThat(DataStats.ratio(0, 0)).isEqualTo("— / 0");
	}

	@Test
	void inputOrOutputInsideRepoIsRefused() throws IOException {
		Path repo = RepoPathGuard.findRepoRoot(Path.of("").toAbsolutePath());
		Path insideIn = repo.resolve("server/target/stats-in-" + System.nanoTime());
		Files.createDirectories(insideIn);
		try {
			assertThatThrownBy(() -> stats.run(insideIn, tmp.resolve("o.txt"), gate))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("仓库目录之内");
			Path data = fullData();
			Path insideOut = repo.resolve("server/target/stats-out-" + System.nanoTime() + ".txt");
			assertThatThrownBy(() -> stats.run(data, insideOut, gate))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("仓库目录之内");
			assertThat(insideOut).doesNotExist();
		} finally {
			Files.deleteIfExists(insideIn);
		}
	}

	// ── lockstep:issue 标记与写入处同字 ───────────────────────────────

	@Test
	void issueMarkersMatchWriters() throws IOException {
		Path main = RepoPathGuard.findRepoRoot(Path.of("").toAbsolutePath()).resolve("server/src/main/java/com/aiuniverse/server");
		assertThat(Files.readString(main.resolve("engine/Engine.java"))).contains("(" + DataStats.ISSUE_GATE + ")");
		assertThat(Files.readString(main.resolve("eventloop/EventLoopService.java"))).contains(DataStats.ISSUE_CLAMP);
	}

	@Test
	void priceIsBoundFromApplicationYml() throws IOException {
		DataStats.PriceSource p = DataStats.priceFromApplicationYml();
		assertThat(p.providerKey()).isNotBlank();
		assertThat(p.price()).isNotNull();
	}
}
