package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aiuniverse.server.persistence.TurnTrace;
import com.aiuniverse.server.persistence.TurnTraceCodec;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-045 刀 2 · {@link SelfPlayDraftTool} 的守护测试。全部用 {@code @TempDir} 里现场合成的套件
 * (形状照 {@code bakeoff/selfplay/selfplay.py} 的产物),不碰任何真实轨迹。
 */
class SelfPlayDraftToolTest {

	static final ObjectMapper M = new ObjectMapper();
	static final String SID = "0b5e3c2a-1111-4a1a-9a1a-000000000001";
	static final String SID2 = "0b5e3c2a-2222-4a1a-9a1a-000000000002";
	static final String HIDDEN = "其实这条守则是心魔所写的伪笔";
	static final Pattern UUID = Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	@TempDir
	Path tmp;

	// ── 合成套件 ─────────────────────────────────────────────────────

	/** 单回合的合成参数;默认 = 两侧对得上、settled、叙事一致。 */
	static final class TurnSpec {
		boolean httpPresent = true;
		boolean tracePresent = true;
		String httpAction = "A";
		String traceAction = "A";
		String degradeReason; // 非 null → degraded
		String traceNarrativeOverride;
		String extraNarrative = ""; // 植入到 SSE 叙事里(测扫描)
	}

	static final class Suite {
		final Path root;
		final String saveId;
		final String slot;
		final Map<Integer, TurnSpec> specs = new HashMap<>();
		int turns = 2;
		boolean resume;
		Path resumeFrom;
		String opening = "山门之外,雾起。";
		Integer endingTurn;

		Suite(Path root, String saveId, String slot) {
			this.root = root;
			this.saveId = saveId;
			this.slot = slot;
		}

		TurnSpec spec(int cursor) {
			return specs.computeIfAbsent(cursor, k -> new TurnSpec());
		}

		static ObjectNode view3World(int turn) {
			ObjectNode w = M.createObjectNode();
			w.put("title", "桩世界").put("subtitle", "小题");
			w.putArray("archetypes").add("cultivation");
			w.put("background", "山门").put("tone", "清冷");
			ObjectNode ch = w.putObject("character");
			ch.putArray("traits").add("沉静").add("寡言");
			ch.putArray("inventory").add("木剑");
			ch.putObject("attributes").put("hp", 100 - turn).put("realm", turn);
			w.putArray("rules").addObject().put("id", 1).put("content", "子时勿出门").put("discovered", false);
			w.putObject("state").put("turn", turn).put("status", "ongoing").put("timeline", "tl" + turn);
			return w;
		}

		static ArrayNode meta() {
			ArrayNode a = M.createArrayNode();
			a.addObject().put("key", "hp").put("displayName", "气血");
			a.addObject().put("key", "realm").put("displayName", "境界");
			return a;
		}

		/** /state 的视图 3 形状;availableActions 刻意与轨迹 pre.currentActions 不同,证明选项取的是轨迹那份。 */
		ObjectNode state(int turn) {
			ObjectNode s = M.createObjectNode();
			s.put("saveId", saveId);
			s.set("world", view3World(turn));
			s.put("openingNarrative", "");
			s.putArray("availableActions").addObject().put("id", "A").put("text", "视图3文本").put("hint", "");
			s.set("attributes", meta());
			return s;
		}

		static ArrayNode currentActions() {
			ArrayNode a = M.createArrayNode();
			a.addObject().put("id", "A").put("text", "打坐").put("hint", "稳");
			a.addObject().put("id", "B").put("text", "下山").put("hint", "");
			return a;
		}

		static ObjectNode view1Doc(int turn) {
			ObjectNode d = M.createObjectNode();
			d.put("schemaVersion", "0.4");
			ObjectNode w = view3World(turn);
			w.remove("state");
			((ObjectNode) w.path("rules").get(0)).put("isTrue", false).put("hiddenLogic", HIDDEN);
			d.set("world", w);
			d.putObject("state").put("turn", turn).put("status", "ongoing").put("timeline", "tl" + turn)
					.put("logSummary", "");
			d.set("currentActions", currentActions());
			return d;
		}

		static String narrative(int cursor) {
			return "第" + (cursor + 1) + "回合叙事。";
		}

		static String sse(int cursor, String extra) {
			String text = narrative(cursor) + extra;
			String half = text.substring(0, 2);
			String rest = text.substring(2);
			ObjectNode delta = M.createObjectNode().put("turn", cursor + 1).put("status", "ongoing")
					.put("hp", 100 - cursor - 1).put("realm", cursor + 1);
			return "event:narrative\ndata:" + M.writeValueAsString(M.createObjectNode().put("text", half)) + "\n\n"
					+ "event:narrative\ndata:" + M.writeValueAsString(M.createObjectNode().put("text", rest)) + "\n\n"
					+ "event:delta\ndata:" + M.writeValueAsString(delta) + "\n\n";
		}

		Suite write() throws IOException {
			Files.createDirectories(root.resolve("http"));
			Files.createDirectories(root.resolve("traces"));
			Files.createDirectories(root.resolve("t0"));
			String t0Rel = "t0/" + slot + "-1.json";
			if (!resume) {
				Files.writeString(root.resolve(t0Rel), M.writeValueAsString(view1Doc(0)));
			}
			List<String> http = new ArrayList<>();
			ObjectNode start = M.createObjectNode();
			start.put("type", resume ? "resume" : "init").put("httpStatus", 200);
			ObjectNode resp = state(0);
			resp.put("openingNarrative", resume ? "" : opening);
			start.set("response", resp);
			http.add(M.writeValueAsString(start));
			StringBuilder traces = new StringBuilder();
			for (int c = 0; c < turns; c++) {
				TurnSpec s = spec(c);
				if (s.httpPresent) {
					ObjectNode l = M.createObjectNode();
					l.put("type", "turn").put("cursor", c).put("turn", c + 1);
					l.putObject("request").put("turn", c).put("actionId", s.httpAction);
					l.put("httpStatus", 200);
					l.set("pre", state(c));
					l.put("sse", sse(c, s.extraNarrative));
					l.set("post", state(c + 1));
					http.add(M.writeValueAsString(l));
				}
				if (s.tracePresent) {
					boolean deg = s.degradeReason != null;
					String narr = s.traceNarrativeOverride != null ? s.traceNarrativeOverride : narrative(c) + s.extraNarrative;
					ObjectNode parsed = deg ? null : M.createObjectNode().put("narrative", narr);
					TurnTrace t = new TurnTrace(TurnTrace.SCHEMA, saveId, c, "2026-11-22T00:00:00Z", "abc1234",
							s.traceAction, deg ? TurnTrace.PATH_DEGRADED : TurnTrace.PATH_SETTLED, view1Doc(c), parsed,
							s.degradeReason, deg ? narr : null, "sha", List.of(), 10, null,
							new TurnTrace.Post("x", Map.of("hp", 99.0)));
					traces.append(TurnTraceCodec.encode(t, M)).append("\n");
				}
			}
			// 一条坏 JSON 的「回合」行不该出现;另附一条 SSE error 行(未落地,不算回合)
			ObjectNode err = M.createObjectNode();
			err.put("type", "turn").put("cursor", turns).put("httpStatus", 200)
					.put("sse", "event:error\ndata:{\"code\":\"busy\",\"message\":\"m\"}\n\n");
			http.add(M.writeValueAsString(err));
			Files.writeString(root.resolve("http").resolve(slot + "-1.jsonl"), String.join("\n", http) + "\n");
			Files.writeString(root.resolve("traces").resolve(saveId + ".trace.jsonl"), traces.toString());

			ObjectNode summary = M.createObjectNode();
			summary.put("mode", resume ? "resume" : "new");
			if (resume) {
				summary.put("resumeFrom", resumeFrom.toString());
			} else {
				summary.putNull("resumeFrom");
			}
			summary.put("plan", "smoke").put("suiteSeed", "1").put("commit", "abc1234")
					.put("startedAt", "2026-11-22T10:00:00+11:00").put("status", "完成");
			ObjectNode slotNode = summary.putArray("slots").addObject();
			slotNode.put("slot", slot).put("kept", 1).put("result", "达标");
			ObjectNode rec = slotNode.putArray("attempts").addObject();
			rec.put("attempt", 1).put("seed", "1:" + slot + ":1").put("saveId", saveId).put("t0", t0Rel)
					.put("http", "http/" + slot + "-1.jsonl").put("turnsPlayed", turns);
			if (endingTurn != null) {
				rec.put("endingTurn", endingTurn).put("endingId", "dao_xiao").put("endingTitle", "身死道消");
			} else {
				rec.putNull("endingTurn");
			}
			if (resume) {
				rec.put("resumedFromT0", true);
			}
			Files.writeString(root.resolve("summary.json"), M.writeValueAsString(summary));
			return this;
		}
	}

	// ── 驱动 ─────────────────────────────────────────────────────────

	record Run(String console, RuntimeException thrown) {
	}

	static Run runMain(Path in) {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream oldOut = System.out;
		PrintStream oldErr = System.err;
		PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		RuntimeException thrown = null;
		try {
			SelfPlayDraftTool.main(new String[] { "--in", in.toString() });
		} catch (RuntimeException e) {
			thrown = e;
		} finally {
			System.setOut(oldOut);
			System.setErr(oldErr);
		}
		return new Run(captured.toString(StandardCharsets.UTF_8), thrown);
	}

	static String draft(Path suite, String slot) throws IOException {
		return Files.readString(suite.resolve("drafts").resolve(slot + "-1.md"));
	}

	static void assertNothingWritten(Path suite) throws IOException {
		assertThat(suite.resolve("drafts")).doesNotExist();
		try (Stream<Path> s = Files.list(suite)) {
			assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".drafts-tmp-"));
		}
	}

	// ── 正常成稿:逐字对照模板 ───────────────────────────────────────

	@Test
	void normalDraftMatchesTemplateVerbatim() throws IOException {
		Path root = tmp.resolve("suite");
		Suite s = new Suite(root, SID, "smoke-1");
		s.endingTurn = 2;
		s.write();
		Run r = runMain(root);
		assertThat(r.thrown()).isNull();

		String expected = """
				# smoke-1 · 桩世界(小题)
				- 世界:cultivation | 选项种子:1:smoke-1:1 | commit:abc1234 | 录制日期:2026-11-22
				- 结局:T2 身死道消(dao_xiao)

				## 世界(视图 3)

				- 背景:山门
				- 基调:清冷
				- 人物:traits 沉静、寡言 · inventory 木剑
				- 规则:
				  - #1 子时勿出门

				## T0 开场

				山门之外,雾起。

				## T1

				- 模型可见原文的回合:无
				- 上回合 timeline:「tl0」
				- 本回合选项:A 打坐(稳) / B 下山
				- 所选:A
				- 降级:否

				第1回合叙事。

				- 落账:气血 100 → 99;境界 0 → 1
				- 本回合写下的 timeline:「tl1」

				## T2

				- 模型可见原文的回合:T1
				- 上回合 timeline:「tl1」
				- 本回合选项:A 打坐(稳) / B 下山
				- 所选:A
				- 降级:否

				第2回合叙事。

				- 落账:气血 99 → 98;境界 1 → 2
				- 本回合写下的 timeline:「tl2」
				""";
		assertThat(draft(root, "smoke-1")).isEqualTo(expected);

		String summary = Files.readString(root.resolve("drafts/summary.md"));
		assertThat(summary).contains("| smoke-1 | 1 | ✓ | 2 | 2 | 身死道消 | 0 | 0 | 0 |");
		assertThat(summary).doesNotContain(SID);
		assertThat(r.console()).contains("成稿 1 份 | 回合 2 | 降级 0 | 对齐失败 0 | 叙事不一致 0");
		assertThat(root.resolve("summary.json")).content().doesNotContain("drafts"); // summary.json 不改
	}

	@Test
	void visibleWindowLinesForT1AndT5() throws IOException {
		Path root = tmp.resolve("suite");
		Suite s = new Suite(root, SID, "w-1");
		s.turns = 6;
		s.write();
		assertThat(runMain(root).thrown()).isNull();
		String d = draft(root, "w-1");
		assertThat(d).contains("## T1\n\n- 模型可见原文的回合:无\n");
		assertThat(d).contains("## T5\n\n- 模型可见原文的回合:T1…T4\n");
		assertThat(d).contains("## T6\n\n- 模型可见原文的回合:T2…T5\n");
		assertThat(SelfPlayDraftTool.window(1)).isEqualTo("无");
		assertThat(SelfPlayDraftTool.window(2)).isEqualTo("T1");
		assertThat(SelfPlayDraftTool.window(5)).isEqualTo("T1…T4");
	}

	@Test
	void degradedTurnShowsReasonAndCounts() throws IOException {
		Path root = tmp.resolve("suite");
		Suite s = new Suite(root, SID, "d-1");
		s.spec(1).degradeReason = "stream_interrupted";
		s.write();
		Run r = runMain(root);
		assertThat(r.thrown()).isNull();
		String d = draft(root, "d-1");
		assertThat(d).contains("## T2\n\n- 模型可见原文的回合:T1\n- 上回合 timeline:「tl1」\n- 本回合选项:A 打坐(稳) / B 下山\n- 所选:A\n- 降级:是(stream_interrupted)\n");
		assertThat(d).contains("## T1\n\n- 模型可见原文的回合:无\n- 上回合 timeline:「tl0」\n- 本回合选项:A 打坐(稳) / B 下山\n- 所选:A\n- 降级:否\n");
		assertThat(d).doesNotContain("叙事不一致"); // degraded 比对的是 streamedNarrative
		assertThat(Files.readString(root.resolve("drafts/summary.md"))).contains("| d-1 | 1 | ✓ | 2 | — | — | 1 | 0 | 0 |");
		assertThat(r.console()).contains("降级 1");
	}

	@Test
	void misalignedTurnsAreMarkedAndCountedNotGuessed() throws IOException {
		Path root = tmp.resolve("suite");
		Suite s = new Suite(root, SID, "m-1");
		s.turns = 4;
		s.spec(1).tracePresent = false;   // T2:轨迹缺
		s.spec(2).httpPresent = false;    // T3:http 缺
		s.spec(3).traceAction = "B";      // T4:所选 id 两侧不同
		s.write();
		Run r = runMain(root);
		assertThat(r.thrown()).isNull();
		String d = draft(root, "m-1");
		assertThat(d).contains("## T2\n\n- 模型可见原文的回合:T1\n- 上回合 timeline:(轨迹缺)\n- 本回合选项:(轨迹缺)\n- 所选:A\n- 降级:(轨迹缺)\n- ⚠️ 对不上:轨迹无此回合\n");
		assertThat(d).contains("- ⚠️ 对不上:http 无此回合\n\n(http 无此回合,不出叙事)\n");
		assertThat(d).contains("- 所选:B\n- 降级:否\n- ⚠️ 对不上:所选 id 不一致\n");
		assertThat(Files.readString(root.resolve("drafts/summary.md"))).contains("| m-1 | 1 | ✓ | 4 | — | — | 0 | 3 | 0 |");
		assertThat(r.console()).contains("对齐失败 3");
	}

	@Test
	void narrativeMismatchIsMarkedAndCounted() throws IOException {
		Path root = tmp.resolve("suite");
		Suite s = new Suite(root, SID, "n-1");
		s.spec(0).traceNarrativeOverride = "另一段叙事。";
		s.write();
		Run r = runMain(root);
		assertThat(r.thrown()).isNull();
		String d = draft(root, "n-1");
		assertThat(d).contains("- 降级:否\n- ⚠️ 叙事不一致(SSE 与轨迹不同)\n\n第1回合叙事。\n");
		assertThat(d.split("叙事不一致", -1)).hasSize(2);
		assertThat(r.console()).contains("叙事不一致 1");
	}

	@Test
	void partialTraceTailIsSkipped() throws IOException {
		Path root = tmp.resolve("suite");
		Suite s = new Suite(root, SID, "p-1");
		s.write();
		Files.writeString(root.resolve("traces").resolve(SID + ".trace.jsonl"), "{\"schema\":1,\"saveId\":\"",
				java.nio.file.StandardOpenOption.APPEND);
		Run r = runMain(root);
		assertThat(r.thrown()).isNull();
		assertThat(draft(root, "p-1")).doesNotContain("对不上");
	}

	// ── 续玩套件 ─────────────────────────────────────────────────────

	@Test
	void resumeSuiteTakesT0OpeningFromOriginSuite() throws IOException {
		Path origin = tmp.resolve("orig");
		Suite o = new Suite(origin, SID, "r-1");
		o.opening = "原套件的开场叙事。";
		o.write();
		Path root = tmp.resolve("resumed");
		Suite s = new Suite(root, SID, "r-1");
		s.resume = true;
		s.resumeFrom = origin;
		s.write();
		Run r = runMain(root);
		assertThat(r.thrown()).as(r.console()).isNull();
		assertThat(draft(root, "r-1")).contains("## T0 开场\n\n原套件的开场叙事。\n");
	}

	// ── 两道扫描:命中即一个文件都不写 ───────────────────────────────

	Run plantAndRun(String planted) throws IOException {
		Path root = tmp.resolve("suite");
		Suite s = new Suite(root, SID, "s-1");
		s.spec(1).extraNarrative = planted;
		s.write();
		Run r = runMain(root);
		assertThat(r.thrown()).isInstanceOf(IllegalStateException.class);
		assertNothingWritten(root);
		assertThat(r.console()).doesNotContain(planted);
		assertThat(UUID.matcher(r.console()).find()).as(r.console()).isFalse();
		return r;
	}

	@Test
	void plantedHiddenLogicRefusesWrite() throws IOException {
		Run r = plantAndRun(HIDDEN);
		assertThat(r.console()).contains("隐藏字段原文 1 处");
	}

	@Test
	void plantedLeakFieldNameRefusesWrite() throws IOException {
		Run r = plantAndRun("(hiddenLogic)");
		assertThat(r.console()).contains("隐藏字段名:hiddenLogic 1 处");
	}

	@Test
	void plantedSaveIdRefusesWrite() throws IOException {
		Run r = plantAndRun(SID);
		assertThat(r.console()).contains("残留:saveId 1 处");
	}

	@Test
	void plantedApiKeyShapeRefusesWrite() throws IOException {
		Run r = plantAndRun("sk-abcdefghijklmnop1234");
		assertThat(r.console()).contains("api-key(sk-…) 1 处");
	}

	@Test
	void otherAttemptsSaveIdCountsToo() throws IOException {
		// 本套「所有」saveId 都是字面量:另一局的 saveId 出现在这一局稿里也要拦
		Path root = tmp.resolve("suite");
		Suite s = new Suite(root, SID, "a-1");
		s.spec(0).extraNarrative = SID2;
		s.write();
		ObjectNode summary = (ObjectNode) M.readTree(Files.readString(root.resolve("summary.json")));
		ObjectNode other = ((ArrayNode) summary.path("slots")).addObject();
		other.put("slot", "a-2").put("kept", 1).put("result", "达标");
		other.putArray("attempts").addObject().put("attempt", 1).put("saveId", SID2).put("t0", "t0/a-1-1.json")
				.put("http", "http/a-1-1.jsonl").put("seed", "1:a-2:1").put("turnsPlayed", 2).putNull("endingTurn");
		Files.writeString(root.resolve("summary.json"), M.writeValueAsString(summary));
		Run r = runMain(root);
		assertThat(r.thrown()).isNotNull();
		assertThat(r.console()).contains("残留:saveId");
		assertNothingWritten(root);
	}

	// ── 路径 / 控制台 ────────────────────────────────────────────────

	@Test
	void suiteInsideRepoIsRefusedAndNothingWritten() throws IOException {
		Path repo = tmp.resolve("fake-repo");
		Files.createDirectories(repo.resolve(".git"));
		Path root = repo.resolve("suite");
		new Suite(root, SID, "g-1").write();
		Run r = runMain(root);
		assertThat(r.thrown()).isInstanceOf(IllegalStateException.class);
		assertThat(r.console()).contains("仓库目录之内");
		assertNothingWritten(root);
	}

	@Test
	void existingDraftsDirIsRefused() throws IOException {
		Path root = tmp.resolve("suite");
		new Suite(root, SID, "e-1").write();
		Files.createDirectories(root.resolve("drafts"));
		Files.writeString(root.resolve("drafts/keep.md"), "旧稿");
		Run r = runMain(root);
		assertThat(r.thrown()).isNotNull();
		assertThat(r.console()).contains("drafts/ 已存在"); // 被这道检查拦下,而不是靠换名失败碰巧拦下
		assertThat(root.resolve("drafts/keep.md")).hasContent("旧稿");
		assertThat(root.resolve("drafts/e-1-1.md")).doesNotExist();
		try (Stream<Path> st = Files.list(root)) {
			assertThat(st.map(x -> x.getFileName().toString())).noneMatch(n -> n.startsWith(".drafts-tmp-"));
		}
	}

	@Test
	void consoleNeverShowsSaveIdOnSuccess() throws IOException {
		Path root = tmp.resolve("suite");
		new Suite(root, SID, "c-1").write();
		Run r = runMain(root);
		assertThat(r.thrown()).isNull();
		assertThat(UUID.matcher(r.console()).find()).as(r.console()).isFalse();
		assertThat(r.console()).doesNotContain(".trace.jsonl");
	}

	@Test
	void corruptTraceLineErrorDoesNotEchoContentOrFileName() throws IOException {
		Path root = tmp.resolve("suite");
		new Suite(root, SID, "x-1").write();
		Path trace = root.resolve("traces").resolve(SID + ".trace.jsonl");
		// 合法 JSON、只多一个以 saveId 为名的键:codec 的报错会点名这个键 —— 工具不得把它转述到控制台
		String good = Files.readString(trace);
		ObjectNode bad = (ObjectNode) M.readTree(good.substring(0, good.indexOf('\n')));
		bad.put(SID, 1);
		Files.writeString(trace, M.writeValueAsString(bad) + "\n" + good);
		Run r = runMain(root);
		assertThat(r.thrown()).isInstanceOf(IllegalStateException.class);
		assertThat(r.thrown().getCause()).isNull();
		assertThat(UUID.matcher(r.console()).find()).as(r.console()).isFalse();
		assertThat(r.thrown().getMessage()).doesNotContain(SID);
		assertNothingWritten(root);
	}

	@Test
	void sseParserJoinsNarrativeAndHonoursSpec() {
		SelfPlayDraftTool.Sse s = SelfPlayDraftTool.Sse.parse(
				"event:narrative\r\ndata:{\"text\":\"甲\"}\r\n\r\n: 注释\nevent: narrative\ndata: {\"text\":\"乙\"}\n\n"
						+ "event:delta\ndata:{\"turn\":1,\ndata:\"hp\":9}\n\n");
		assertThat(s.narrative()).isEqualTo("甲乙");
		assertThat(s.delta().path("hp").asInt()).isEqualTo(9);
		assertThat(SelfPlayDraftTool.Sse.parse("event:error\ndata:{\"code\":\"busy\"}\n\n").delta()).isNull();
	}

	@Test
	void hiddenLiteralsCollectedOnlyUnderLeakKeys() {
		ObjectNode doc = Suite.view1Doc(0);
		((ObjectNode) doc.path("world")).put("groundTruth", "真相串");
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		SelfPlayDraftTool.collectHidden(doc, out);
		assertThat(out).containsExactlyInAnyOrder(HIDDEN, "真相串");
	}
}
