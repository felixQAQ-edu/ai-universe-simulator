package com.aiuniverse.server.eventloop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.slf4j.LoggerFactory;

import com.aiuniverse.server.engine.LeakDetector;
import com.aiuniverse.server.persistence.MigrationPathGate;
import com.aiuniverse.server.persistence.TraceFileReader;
import com.aiuniverse.server.persistence.TurnTrace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-045 刀 2 · 自玩套件成稿工具(测试侧 {@code exec:java},形态照 {@code DataStatsTool};不进生产装配)。
 * 运行命令见 ADR-045「运行方式」与 {@code bakeoff/selfplay/README.md}。
 *
 * <pre>
 * 参数:--in &lt;套件根目录&gt;(内含 http/、traces/、t0/、summary.json,由 bakeoff/selfplay/selfplay.py 产出)
 * 输出:&lt;套件根目录&gt;/drafts/(每个已开局 attempt 一份 &lt;slot&gt;-&lt;attempt&gt;.md + summary.md)
 * </pre>
 *
 * <ul>
 *   <li><b>路径</b>:输入、输出(以及续玩套件的原套件目录)都过 {@link MigrationPathGate#strict()}
 *       (= {@code RepoPathGuard.requireOutsideRepo} + web 根);不合规 → 抛,不写任何文件。{@code drafts/} 已存在也拒。</li>
 *   <li><b>对齐</b>:http 回合记录的 {@code cursor} 与轨迹的 {@code turnBefore} 是同一个数(回合前回合号)。
 *       两侧缺一、或所选 id 不同 → 该回合标「对不上」并计数,不猜。</li>
 *   <li><b>世界段只取视图 3</b>(init 响应 / 续玩的 /state 响应);轨迹的 {@code pre.world} 是视图 1,一个字段也不进稿件。</li>
 *   <li><b>两道写出闸</b>(对将要写出的全文,先在内存里扫,全过才写临时目录再整体换名):
 *       ① {@link FixtureScanner} 同组敏感形态 + 本套所有 saveId 作字面量;
 *       ② {@link LeakDetector#LEAK_TOKENS} 字段名 + 各局 T0 存档(视图 1)里挂在这四个键下的每条字符串原文作字面量
 *       (只抓逐字照抄,抓不到改写式泄露,同 CONTEXT §三.9)。任一命中 → 一个文件都不写,控制台只报类别与计数。</li>
 *   <li><b>控制台不出现 saveId</b>:运行期间关掉 engine / persistence / eventloop 三个包的日志(同 DataStatsTool);
 *       报错不带 cause、不回显输入文件名(轨迹文件名就是 saveId,解码异常的文本里可能带内容片段)。</li>
 * </ul>
 */
public final class SelfPlayDraftTool {

	private SelfPlayDraftTool() {
	}

	/** 运行期间静默的包(同 {@code DataStatsTool.SILENCED_PACKAGES})。 */
	static final List<String> SILENCED_PACKAGES = List.of("com.aiuniverse.server.engine",
			"com.aiuniverse.server.persistence", "com.aiuniverse.server.eventloop");

	static final String DRAFTS = "drafts";

	/** 工具的所有失败都走它:消息是人写的、安全的,<b>永不挂 cause</b>(cause 的文本不受控)。 */
	static final class Refused extends IllegalStateException {
		Refused(String message) {
			super(message);
		}
	}

	/** 一次成稿的计数(控制台只打这些数)。 */
	record Counts(int drafts, int turns, int degraded, int misaligned, int narrativeMismatch) {
	}

	public static void main(String[] args) {
		Map<Logger, Level> previous = new LinkedHashMap<>();
		for (String name : SILENCED_PACKAGES) {
			Logger logger = (Logger) LoggerFactory.getLogger(name);
			previous.put(logger, logger.getLevel());
			logger.setLevel(Level.OFF);
		}
		try {
			Path in = parseIn(args);
			Counts c = run(in);
			System.out.println("成稿 " + c.drafts() + " 份 | 回合 " + c.turns() + " | 降级 " + c.degraded() + " | 对齐失败 "
					+ c.misaligned() + " | 叙事不一致 " + c.narrativeMismatch());
			System.out.println("输出: " + in.toAbsolutePath().normalize().resolve(DRAFTS));
		} catch (Refused e) {
			System.err.println("成稿未写出:" + e.getMessage());
			throw e;
		} catch (RuntimeException e) {
			Refused safe = new Refused("成稿失败(" + e.getClass().getSimpleName() + ")");
			System.err.println("成稿未写出:" + safe.getMessage());
			throw safe;
		} finally {
			previous.forEach(Logger::setLevel);
		}
	}

	static Path parseIn(String[] args) {
		if (args.length != 2 || !"--in".equals(args[0])) {
			throw new Refused("用法:--in <套件根目录>");
		}
		return Path.of(args[1]);
	}

	// ── 主流程 ───────────────────────────────────────────────────────

	static Counts run(Path in) {
		ObjectMapper mapper = new ObjectMapper();
		MigrationPathGate gate = MigrationPathGate.strict();
		Path suite = checked(gate, in, "输入");
		Path out = checked(gate, suite.resolve(DRAFTS), "输出");
		if (!Files.isDirectory(suite)) {
			throw new Refused("输入不是目录(应为套件根目录)");
		}
		if (Files.exists(out)) {
			throw new Refused("drafts/ 已存在:不覆盖上一次的成稿,先移走再跑");
		}
		JsonNode summary = readJson(mapper, suite.resolve("summary.json"), "summary.json");
		boolean resume = "resume".equals(summary.path("mode").asString(""));
		Path origin = null;
		if (resume) {
			String from = summary.path("resumeFrom").asString("");
			if (from.isEmpty()) {
				throw new Refused("续玩套件的 summary.json 缺 resumeFrom");
			}
			origin = checked(gate, Path.of(from), "原套件");
		}

		Map<String, String> files = new LinkedHashMap<>(); // 文件名 → 全文(写出顺序)
		Set<String> saveIds = new LinkedHashSet<>();
		Set<String> hiddenLiterals = new LinkedHashSet<>();
		List<String> rows = new ArrayList<>();
		List<String> notStarted = new ArrayList<>();
		int turns = 0;
		int degraded = 0;
		int misaligned = 0;
		int mismatch = 0;
		String suiteCommit = "—";

		for (JsonNode slot : summary.path("slots")) {
			String slotName = slot.path("slot").asString("");
			Integer kept = slot.path("kept").isNumber() ? slot.path("kept").asInt() : null;
			boolean any = false;
			for (JsonNode rec : slot.path("attempts")) {
				String saveId = rec.path("saveId").asString("");
				if (saveId.isEmpty()) {
					continue; // 未开局
				}
				any = true;
				saveIds.add(saveId);
				int attempt = rec.path("attempt").asInt();
				Path t0Base = rec.path("resumedFromT0").asBoolean(false) ? origin : suite;
				String t0Rel = rec.path("t0").asString("");
				if (t0Base == null || t0Rel.isEmpty() || !Files.isRegularFile(t0Base.resolve(t0Rel))) {
					throw new Refused("槽位 " + slotName + " #" + attempt + " 的 T0 存档缺失:没有它就做不了隐藏字段扫描");
				}
				collectHidden(readJson(mapper, t0Base.resolve(t0Rel), "T0 存档"), hiddenLiterals);

				Draft d = draft(mapper, suite, origin, summary, slotName, rec, saveId);
				files.put(slotName + "-" + attempt + ".md", d.text());
				turns += d.turns();
				degraded += d.degraded();
				misaligned += d.misaligned();
				mismatch += d.mismatch();
				if ("—".equals(suiteCommit) && !"—".equals(d.commit())) {
					suiteCommit = d.commit();
				}
				rows.add("| " + slotName + " | " + attempt + " | " + (kept != null && kept == attempt ? "✓" : "")
						+ " | " + rec.path("turnsPlayed").asInt() + " | " + orDash(rec.path("endingTurn"))
						+ " | " + cell(rec.path("endingTitle").asString("")) + " | " + d.degraded() + " | "
						+ d.misaligned() + " | " + d.mismatch() + " |");
			}
			if (!any) {
				notStarted.add(slotName + "(" + slot.path("result").asString("") + ")");
			}
		}
		files.put("summary.md", summaryMd(summary, suiteCommit, rows, notStarted));

		scanOrRefuse(files, saveIds, hiddenLiterals);
		writeAtomically(suite, out, files);
		return new Counts(files.size() - 1, turns, degraded, misaligned, mismatch);
	}

	private static Path checked(MigrationPathGate gate, Path p, String what) {
		try {
			return gate.check(p, what);
		} catch (IOException | RuntimeException e) {
			throw new Refused(what + "路径不合规(在仓库目录之内或 web 根之下):真实数据一律放在仓库之外");
		}
	}

	// ── 单局成稿 ─────────────────────────────────────────────────────

	record Draft(String text, int turns, int degraded, int misaligned, int mismatch, String commit) {
	}

	private record HttpTurn(JsonNode line, String narrative) {
	}

	static Draft draft(ObjectMapper mapper, Path suite, Path origin, JsonNode summary, String slot, JsonNode rec,
			String saveId) {
		int attempt = rec.path("attempt").asInt();
		List<JsonNode> http = readJsonl(mapper, suite.resolve(rec.path("http").asString("")), "http 记录");
		JsonNode start = http.stream()
				.filter(l -> Set.of("init", "resume").contains(l.path("type").asString("")))
				.findFirst().orElseThrow(() -> new Refused("槽位 " + slot + " #" + attempt + " 的 http 记录没有 init / resume 行"));
		JsonNode resp = start.path("response");
		JsonNode world = resp.path("world");
		// title / subtitle / background / tone 在 InitResponse.world 的 world 子对象下(world.world.title …,
		// 同 WorldGenService.systemLineErrors、FixedWorldTitle 的读法);archetypes / character / rules 在外层。
		JsonNode worldMeta = world.path("world");
		String opening;
		if ("resume".equals(start.path("type").asString(""))) {
			opening = originOpening(mapper, origin, slot, attempt);
		} else {
			opening = resp.path("openingNarrative").asString("");
		}
		Map<String, String> axisNames = new LinkedHashMap<>();
		for (JsonNode a : resp.path("attributes")) {
			axisNames.put(a.path("key").asString(""), a.path("displayName").asString(a.path("key").asString("")));
		}

		TreeMap<Integer, HttpTurn> byCursor = new TreeMap<>();
		for (JsonNode l : http) {
			if (!"turn".equals(l.path("type").asString("")) || l.path("httpStatus").asInt() != 200) {
				continue;
			}
			Sse sse = Sse.parse(l.path("sse").asString(""));
			if (sse.delta() == null) {
				continue; // SSE error(闸停 / busy …):这一回合没落地,不是一回合
			}
			byCursor.put(l.path("cursor").asInt(), new HttpTurn(l, sse.narrative()));
		}

		TreeMap<Integer, TurnTrace> byTurnBefore = new TreeMap<>();
		Path traceFile = suite.resolve("traces").resolve(saveId + ".trace.jsonl");
		if (Files.isRegularFile(traceFile)) {
			TraceFileReader.Result r;
			try {
				r = TraceFileReader.read(traceFile, mapper);
			} catch (IOException | RuntimeException e) {
				throw new Refused("槽位 " + slot + " #" + attempt + " 的轨迹读不出来");
			}
			for (TurnTrace t : r.traces()) {
				byTurnBefore.put(t.turnBefore(), t);
			}
		}
		String commit = byTurnBefore.isEmpty() ? "—" : byTurnBefore.firstEntry().getValue().commit();

		StringBuilder sb = new StringBuilder();
		String title = worldMeta.path("title").asString("");
		String subtitle = worldMeta.path("subtitle").asString("");
		sb.append("# ").append(slot).append(" · ").append(title);
		if (!subtitle.isEmpty()) {
			sb.append("(").append(subtitle).append(")");
		}
		sb.append("\n");
		List<String> archetypes = new ArrayList<>();
		world.path("archetypes").forEach(a -> archetypes.add(a.asString("")));
		String date = summary.path("startedAt").asString("");
		sb.append("- 世界:").append(String.join("×", archetypes)).append(" | 选项种子:").append(rec.path("seed").asString(""))
				.append(" | commit:").append(commit).append(" | 录制日期:").append(date.length() >= 10 ? date.substring(0, 10) : "—")
				.append("\n");
		if (rec.path("endingTurn").isNumber()) {
			sb.append("- 结局:T").append(rec.path("endingTurn").asInt()).append(" ").append(rec.path("endingTitle").asString(""))
					.append("(").append(rec.path("endingId").asString("")).append(")\n");
		} else {
			sb.append("- 结局:未结局,停于 T").append(rec.path("turnsPlayed").asInt()).append("\n");
		}

		sb.append("\n## 世界(视图 3)\n\n");
		sb.append("- 背景:").append(worldMeta.path("background").asString("")).append("\n");
		sb.append("- 基调:").append(worldMeta.path("tone").asString("")).append("\n");
		sb.append("- 人物:traits ").append(joinStrings(world.path("character").path("traits")))
				.append(" · inventory ").append(joinStrings(world.path("character").path("inventory"))).append("\n");
		sb.append("- 规则:\n");
		for (JsonNode r : world.path("rules")) {
			sb.append("  - #").append(r.path("id").asInt()).append(" ").append(r.path("content").asString("")).append("\n");
		}
		sb.append("\n## T0 开场\n\n").append(opening).append("\n");

		Set<Integer> keys = new TreeSet<>(byCursor.keySet());
		keys.addAll(byTurnBefore.keySet());
		int degraded = 0;
		int misaligned = 0;
		int mismatch = 0;
		for (int cursor : keys) {
			int t = cursor + 1;
			HttpTurn h = byCursor.get(cursor);
			TurnTrace tr = byTurnBefore.get(cursor);
			List<String> problems = new ArrayList<>();
			if (h == null) {
				problems.add("http 无此回合");
			}
			if (tr == null) {
				problems.add("轨迹无此回合");
			}
			String httpAction = h == null ? null : h.line().path("request").path("actionId").asString(null);
			if (h != null && tr != null && !tr.actionId().equals(httpAction)) {
				problems.add("所选 id 不一致");
			}
			boolean isDegraded = tr != null && TurnTrace.PATH_DEGRADED.equals(tr.path());
			if (isDegraded) {
				degraded++;
			}
			boolean narrativeDiffers = false;
			if (h != null && tr != null) {
				String traced = isDegraded ? tr.streamedNarrative()
						: tr.parsed() == null ? null : tr.parsed().path("narrative").asString(null);
				narrativeDiffers = traced == null || !traced.strip().equals(h.narrative().strip());
			}

			sb.append("\n## T").append(t).append("\n\n");
			sb.append("- 模型可见原文的回合:").append(window(t)).append("\n");
			sb.append("- 上回合 timeline:").append(tr == null ? "(轨迹缺)" : "「" + tr.pre().path("state").path("timeline").asString("") + "」").append("\n");
			sb.append("- 本回合选项:").append(tr == null ? "(轨迹缺)" : options(tr.pre().path("currentActions"))).append("\n");
			sb.append("- 所选:").append(tr != null ? tr.actionId() : httpAction).append("\n");
			sb.append("- 降级:").append(tr == null ? "(轨迹缺)" : isDegraded ? "是(" + tr.degradeReason() + ")" : "否").append("\n");
			if (!problems.isEmpty()) {
				misaligned++;
				sb.append("- ⚠️ 对不上:").append(String.join(";", problems)).append("\n");
			}
			if (narrativeDiffers) {
				mismatch++;
				sb.append("- ⚠️ 叙事不一致(SSE 与轨迹不同)\n");
			}
			if (h == null) {
				sb.append("\n(http 无此回合,不出叙事)\n\n");
				sb.append("- 落账:(http 无此回合)\n");
				sb.append("- 本回合写下的 timeline:(http 无此回合)\n");
				continue;
			}
			// 成稿输出去掉叙事末尾空白(流末常带空行);上面的比对口径不动(两侧各自 strip)。
			sb.append("\n").append(h.narrative().stripTrailing()).append("\n\n");
			JsonNode before = h.line().path("pre").path("world").path("character").path("attributes");
			JsonNode afterState = h.line().path("post").path("world");
			JsonNode after = afterState.isMissingNode() ? h.line().path("axes")
					: afterState.path("character").path("attributes");
			List<String> moves = new ArrayList<>();
			axisNames.forEach((key, name) -> moves.add(name + " " + num(before.path(key)) + " → " + num(after.path(key))));
			sb.append("- 落账:").append(String.join(";", moves)).append("\n");
			sb.append("- 本回合写下的 timeline:")
					.append(afterState.isMissingNode() ? "(回合后 /state 缺)" : "「" + afterState.path("state").path("timeline").asString("") + "」")
					.append("\n");
		}
		return new Draft(sb.toString(), keys.size(), degraded, misaligned, mismatch, commit);
	}

	/** 续玩套件没有开场叙事(transient):去原套件同名 attempt 的 init 行取。 */
	private static String originOpening(ObjectMapper mapper, Path origin, String slot, int attempt) {
		if (origin == null) {
			throw new Refused("续玩记录没有原套件目录");
		}
		Path f = origin.resolve("http").resolve(slot + "-" + attempt + ".jsonl");
		for (JsonNode l : readJsonl(mapper, f, "原套件 http 记录")) {
			if ("init".equals(l.path("type").asString(""))) {
				return l.path("response").path("openingNarrative").asString("");
			}
		}
		throw new Refused("原套件里没有槽位 " + slot + " #" + attempt + " 的 init 记录");
	}

	/** t = 1 → 无;否则 T(max(1,t-4))…T(t-1);两端相同只写一个。 */
	static String window(int t) {
		if (t <= 1) {
			return "无";
		}
		int lo = Math.max(1, t - 4);
		int hi = t - 1;
		return lo == hi ? "T" + lo : "T" + lo + "…T" + hi;
	}

	private static String options(JsonNode actions) {
		List<String> parts = new ArrayList<>();
		for (JsonNode a : actions) {
			String hint = a.path("hint").asString("");
			parts.add(a.path("id").asString("") + " " + a.path("text").asString("") + (hint.isEmpty() ? "" : "(" + hint + ")"));
		}
		return parts.isEmpty() ? "(无)" : String.join(" / ", parts);
	}

	private static String joinStrings(JsonNode arr) {
		List<String> parts = new ArrayList<>();
		arr.forEach(x -> parts.add(x.asString("")));
		return parts.isEmpty() ? "—" : String.join("、", parts);
	}

	static String num(JsonNode n) {
		if (n == null || !n.isNumber()) {
			return "—";
		}
		double d = n.asDouble();
		return d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString((long) d) : Double.toString(d);
	}

	private static String orDash(JsonNode n) {
		return n.isNumber() ? Integer.toString(n.asInt()) : "—";
	}

	private static String cell(String s) {
		return s.isEmpty() ? "—" : s.replace("|", "\\|").replace("\n", " ");
	}

	private static String summaryMd(JsonNode summary, String commit, List<String> rows, List<String> notStarted) {
		StringBuilder sb = new StringBuilder("# 自玩套件成稿汇总\n\n");
		sb.append("- 计划:").append(summary.path("plan").asString("")).append(" | 选项种子:")
				.append(summary.path("suiteSeed").asString("")).append(" | commit:").append(commit).append(" | 模式:")
				.append(summary.path("mode").asString("")).append(" | 状态:").append(summary.path("status").asString(""))
				.append("\n\n");
		sb.append("| 槽位 | attempt | 保留 | 回合数 | 结局回合 | 结局标题 | 降级回合数 | 对齐失败数 | 叙事不一致数 |\n");
		sb.append("|---|---|---|---|---|---|---|---|---|\n");
		rows.forEach(r -> sb.append(r).append("\n"));
		if (!notStarted.isEmpty()) {
			sb.append("\n未开局的槽位:").append(String.join("、", notStarted)).append("\n");
		}
		return sb.toString();
	}

	// ── SSE ──────────────────────────────────────────────────────────

	/** 四事件里成稿只要两样:narrative 拼接(玩家所见)与是否有 delta(这一回合是否落地)。 */
	record Sse(String narrative, JsonNode delta) {

		private static final ObjectMapper MAPPER = new ObjectMapper();

		static Sse parse(String raw) {
			StringBuilder narrative = new StringBuilder();
			JsonNode delta = null;
			String event = "message";
			List<String> data = new ArrayList<>();
			List<String> lines = new ArrayList<>(List.of(raw.split("\r?\n", -1)));
			lines.add(""); // 末尾补一个空行,让最后一个事件也派发
			for (String line : lines) {
				if (line.isEmpty()) {
					if (!data.isEmpty()) {
						JsonNode obj = MAPPER.readTree(String.join("\n", data));
						if ("narrative".equals(event)) {
							narrative.append(obj.path("text").asString(""));
						} else if ("delta".equals(event)) {
							delta = obj;
						}
					}
					event = "message";
					data.clear();
					continue;
				}
				if (line.startsWith(":")) {
					continue;
				}
				int colon = line.indexOf(':');
				String field = colon < 0 ? line : line.substring(0, colon);
				String value = colon < 0 ? "" : line.substring(colon + 1);
				if (value.startsWith(" ")) {
					value = value.substring(1);
				}
				if ("event".equals(field)) {
					event = value;
				} else if ("data".equals(field)) {
					data.add(value);
				}
			}
			return new Sse(narrative.toString(), delta);
		}
	}

	// ── 两道扫描 ─────────────────────────────────────────────────────

	/** T0 存档(视图 1)里挂在 LEAK_TOKENS 四个键下的每条字符串原文(整串)。 */
	static void collectHidden(JsonNode n, Set<String> out) {
		if (n.isObject()) {
			for (Map.Entry<String, JsonNode> e : n.properties()) {
				if (LeakDetector.LEAK_TOKENS.contains(e.getKey()) && e.getValue().isString()
						&& !e.getValue().asString().isEmpty()) {
					out.add(e.getValue().asString());
				}
				collectHidden(e.getValue(), out);
			}
		} else if (n.isArray()) {
			n.forEach(c -> collectHidden(c, out));
		}
	}

	static void scanOrRefuse(Map<String, String> files, Set<String> saveIds, Set<String> hiddenLiterals) {
		Map<String, String> literals = new LinkedHashMap<>();
		int i = 0;
		for (String s : saveIds) {
			literals.put("saveId#" + (++i), s);
		}
		Map<String, Long> kinds = new TreeMap<>();
		for (String text : files.values()) {
			// ① 敏感形态 + saveId 字面量(残留:saveId#n 合并成一个类别,不暴露是哪一个)
			for (FixtureScanner.Hit h : FixtureScanner.scan(text, literals)) {
				String kind = h.kind().startsWith("残留:saveId") ? "残留:saveId" : h.kind();
				kinds.merge(kind, 1L, Long::sum);
			}
			// ② 隐藏字段名 + T0 里的隐藏字段原文
			for (String tok : LeakDetector.LEAK_TOKENS) {
				long n = occurrences(text, tok);
				if (n > 0) {
					kinds.merge("隐藏字段名:" + tok, n, Long::sum);
				}
			}
			for (String lit : hiddenLiterals) {
				long n = occurrences(text, lit);
				if (n > 0) {
					kinds.merge("隐藏字段原文", n, Long::sum);
				}
			}
		}
		if (!kinds.isEmpty()) {
			List<String> parts = new ArrayList<>();
			kinds.forEach((k, n) -> parts.add(k + " " + n + " 处"));
			throw new Refused("写出前扫描命中,一个文件都没写 —— " + String.join(";", parts));
		}
	}

	private static long occurrences(String text, String lit) {
		long n = 0;
		for (int i = text.indexOf(lit); i >= 0; i = text.indexOf(lit, i + 1)) {
			n++;
		}
		return n;
	}

	// ── 读写 ─────────────────────────────────────────────────────────

	/** 先写同级临时目录,全部写完才整体换名为 drafts/;中途失败删掉临时目录。 */
	static void writeAtomically(Path suite, Path out, Map<String, String> files) {
		Path tmp = null;
		try {
			tmp = Files.createTempDirectory(suite, ".drafts-tmp-");
			for (Map.Entry<String, String> e : files.entrySet()) {
				Files.writeString(tmp.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
			}
			Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE);
			tmp = null;
		} catch (IOException e) {
			throw new Refused("写出失败(" + e.getClass().getSimpleName() + ")");
		} finally {
			if (tmp != null) {
				deleteQuietly(tmp);
			}
		}
	}

	private static void deleteQuietly(Path dir) {
		try (Stream<Path> s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// 尽力而为
				}
			});
		} catch (IOException | UncheckedIOException ignored) {
			// 尽力而为
		}
	}

	private static JsonNode readJson(ObjectMapper mapper, Path f, String what) {
		try {
			return mapper.readTree(Files.readString(f, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			throw new Refused(what + " 读不出来");
		}
	}

	private static List<JsonNode> readJsonl(ObjectMapper mapper, Path f, String what) {
		List<JsonNode> out = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				if (!line.isBlank()) {
					out.add(mapper.readTree(line));
				}
			}
		} catch (IOException | RuntimeException e) {
			throw new Refused(what + " 读不出来");
		}
		return out;
	}
}
