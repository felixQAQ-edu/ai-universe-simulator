package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.FixtureScanner;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.llm.LlmProperties;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.quota.TokenPricing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-033 · /data 只读统计(测试侧工具,不进生产装配;命令行入口 {@link DataStatsTool})。
 *
 * <p>口径一律以 ADR-033「已决」为准(编号 = stats-survey §6)。解析全部复用:存档走
 * {@link SessionDocument#isSaveDocument} / {@link SessionDocument#decode}(扫描口径同 {@code FileSessionStore.loadAll}),
 * 轨迹走 {@link TraceFileReader}(→ {@link TurnTraceCodec});计价走 {@link TokenPricing}。本类不写第二份解析器。
 *
 * <p><b>输出纪律</b>:报告只由数字与枚举 / 标签字段生成;写出前过三道闸(叙事闸、{@link FixtureScanner} 形态、UUID 形态),
 * 任一命中 → 不写文件、抛异常(只给类别与计数,不回显命中内容)。任何地方都不回显输入文件名或 saveId。
 */
public final class DataStats {

	/** 报告首行(ADR-033 已决 4,逐字)。 */
	public static final String FIRST_LINE = "本报告数据含作者自玩与冒烟局;它证明统计可复现,不代表真实玩家留存或活跃度。";

	/** 轨迹组范围(已决 6,逐字)。 */
	static final String TRACE_SCOPE = "2026-10-08 起有轨迹记录的已落地回合";

	/** 结局 gate 改判的 issue 标记(Engine.apply 写入的那一行;{@code DataStatsTest} 用源码级断言守 lockstep)。 */
	static final String ISSUE_GATE = "ADR-010 gate";
	/** 一生制收束下限钳制的 issue 标记(EventLoopService 写入;同上)。 */
	static final String ISSUE_CLAMP = "收束下限钳制";

	static final Set<String> OUTCOMES = Set.of("success", "failure", "neutral");
	/** 标签类取值(commit / model / degradeReason / 调用名)只在形如此时原样写出。 */
	static final Pattern LABEL = Pattern.compile("[\\p{L}\\p{N}._:-]{1,64}");
	static final Pattern UUID = Pattern.compile(
			"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
	/** 叙事闸只收至少这么多个字符(码点)的字符串:更短的串误报远多于真泄露(ADR-033 已知代价 4)。 */
	static final int NARRATIVE_MIN_CHARS = 4;

	static final String UNUSUAL = "(非常规取值)";

	private final ObjectMapper mapper;
	private final ArchetypeRegistry archetypes;
	private final PriceSource price;

	/** 计价来源:provider 键 + 三段单价(从 application.yml 绑定)。 */
	public record PriceSource(String providerKey, LlmProperties.Price price) {
	}

	public DataStats(ObjectMapper mapper, ArchetypeRegistry archetypes, PriceSource price) {
		this.mapper = mapper;
		this.archetypes = archetypes;
		this.price = price;
	}

	// ── 入口 ─────────────────────────────────────────────────────────

	/**
	 * 读 {@code in}(线上 /data 拷贝根目录),写报告到 {@code out}。路径闸门由调用方给({@link MigrationPathGate#strict()})。
	 * 三道闸任一命中 → 抛 {@link IllegalStateException},不写文件。
	 */
	public Path run(Path in, Path out, MigrationPathGate gate) throws IOException {
		Path inAbs = gate.check(in, "输入");
		Path outAbs = gate.check(out, "输出");
		if (!Files.isDirectory(inAbs)) {
			throw new IllegalArgumentException("输入不是目录(应为线上 /data 拷贝的根目录)");
		}
		Narrative narrative = new Narrative();
		SaveStats saves = scanSaves(inAbs, narrative);
		TraceStats traces = scanTraces(inAbs.resolve("traces"), narrative);
		String report = report(saves, traces);
		guard(report, narrative);
		Files.writeString(outAbs, report, StandardCharsets.UTF_8);
		return outAbs;
	}

	/** 三道写出闸。命中 → 抛,报错只含类别与计数。 */
	void guard(String report, Narrative narrative) {
		List<String> problems = new ArrayList<>();
		long leaks = narrative.strings.stream().filter(report::contains).count();
		if (leaks > 0) {
			problems.add("叙事闸 " + leaks + " 条");
		}
		Map<String, Long> kinds = new TreeMap<>();
		for (FixtureScanner.Hit h : FixtureScanner.scan(report, Map.of())) {
			kinds.merge(h.kind(), 1L, Long::sum);
		}
		kinds.forEach((k, n) -> problems.add(k + " " + n + " 处"));
		long uuids = UUID.matcher(report).results().count();
		if (uuids > 0) {
			problems.add("uuid " + uuids + " 处");
		}
		if (!problems.isEmpty()) {
			throw new IllegalStateException("报告未写出:写出前扫描命中 —— " + String.join(";", problems));
		}
	}

	// ── 格式 ─────────────────────────────────────────────────────────

	/** 所有比例唯一的格式化函数:「x / y(z%)」;分母为 0 → 「— / 0」。 */
	public static String ratio(long x, long y) {
		if (y == 0) {
			return "— / 0";
		}
		return x + " / " + y + "(" + String.format(Locale.ROOT, "%.1f", 100.0 * x / y) + "%)";
	}

	static String num(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	static String label(String v) {
		return v != null && LABEL.matcher(v).matches() ? v : UNUSUAL;
	}

	/** 均值(样本为空 → 「—」)。 */
	static String mean(List<? extends Number> xs) {
		return xs.isEmpty() ? "—" : num(xs.stream().mapToDouble(Number::doubleValue).average().orElse(0));
	}

	static String median(List<Integer> xs) {
		if (xs.isEmpty()) {
			return "—";
		}
		List<Integer> s = new ArrayList<>(xs);
		Collections.sort(s);
		int n = s.size();
		return n % 2 == 1 ? num(s.get(n / 2)) : num((s.get(n / 2 - 1) + s.get(n / 2)) / 2.0);
	}

	/** 最近秩分位数(p ∈ (0,1]);样本须非空。 */
	static long percentile(List<Long> sorted, double p) {
		int idx = (int) Math.ceil(p * sorted.size()) - 1;
		return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
	}

	// ── 叙事闸的收集 ───────────────────────────────────────────────────

	/** 输入里叙事字段的字符串(≥ {@link #NARRATIVE_MIN_CHARS} 个字符),只用于写出前的比对,从不写出。 */
	static final class Narrative {
		final Set<String> strings = new LinkedHashSet<>();

		void add(JsonNode n) {
			if (n == null) {
				return;
			}
			if (n.isString()) {
				String s = n.asString("");
				if (s.codePointCount(0, s.length()) >= NARRATIVE_MIN_CHARS) {
					strings.add(s);
				}
			} else if (n.isContainer()) {
				n.forEach(this::add);
			}
		}

		void addField(JsonNode arr, String field) {
			if (arr != null && arr.isArray()) {
				arr.forEach(x -> add(x.get(field)));
			}
		}

		/** 一份快照文档(存档或轨迹的 pre)里的叙事字段。 */
		void addDocument(JsonNode doc) {
			if (doc == null) {
				return;
			}
			JsonNode w = doc.path("world");
			add(w.get("world")); // title / background / tone / …
			add(w.path("character").get("traits"));
			add(w.path("character").get("inventory"));
			addField(w.get("rules"), "content");
			addField(w.get("rules"), "hiddenLogic");
			for (String f : List.of("title", "description", "condition")) {
				addField(w.get("endings"), f);
			}
			JsonNode st = doc.path("state");
			add(st.get("timeline"));
			add(st.get("logSummary"));
			addField(st.get("log"), "narrative");
			addField(doc.get("currentActions"), "text");
			addField(doc.get("currentActions"), "hint");
		}
	}

	// ── 存档组 ───────────────────────────────────────────────────────

	record SaveRow(int turn, String status, String group, String outcome, int gateIssues, int clampIssues) {
	}

	record SaveStats(int candidates, int notASave, int refused, List<SaveRow> rows) {
	}

	SaveStats scanSaves(Path dir, Narrative narrative) throws IOException {
		List<Path> candidates;
		try (Stream<Path> files = Files.list(dir)) { // 不递归:traces/ 不在候选里(同 FileSessionStore.loadAll)
			candidates = files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
		}
		int notASave = 0;
		int refused = 0;
		List<SaveRow> rows = new ArrayList<>();
		for (Path p : candidates) {
			String name = p.getFileName().toString();
			String saveId = name.substring(0, name.length() - ".json".length());
			JsonNode doc;
			try {
				doc = mapper.readTree(Files.readString(p, StandardCharsets.UTF_8));
			} catch (Exception e) {
				refused++;
				continue;
			}
			if (!SessionDocument.isSaveDocument(doc)) {
				notASave++;
				continue;
			}
			GameSession s;
			try {
				s = SessionDocument.decode(saveId, doc, mapper, archetypes);
			} catch (Exception e) {
				refused++;
				continue;
			}
			narrative.addDocument(doc);
			rows.add(row(s.engine()));
		}
		return new SaveStats(candidates.size(), notASave, refused, rows);
	}

	private SaveRow row(Engine e) {
		JsonNode world = e.world();
		List<String> ids = new ArrayList<>();
		world.path("archetypes").forEach(a -> ids.add(a.asString("")));
		String group = String.join(" × ", ids); // 融合:host 在前的有序对单列一类(已决 5)
		String outcome = null;
		if ("ended".equals(e.status())) {
			outcome = "(无已达成结局)";
			for (JsonNode end : world.path("endings")) {
				if (end.path("reached").asBoolean(false)) {
					JsonNode o = end.get("outcome");
					if (o == null || o.isNull()) {
						outcome = "无 outcome";
					} else {
						String v = o.asString("");
						outcome = OUTCOMES.contains(v) ? v : UNUSUAL;
					}
					break;
				}
			}
		}
		int gate = 0;
		int clamp = 0;
		for (String issue : e.issues()) {
			if (issue.contains(ISSUE_GATE)) {
				gate++;
			}
			if (issue.contains(ISSUE_CLAMP)) {
				clamp++;
			}
		}
		return new SaveRow(e.turn(), e.status(), group, outcome, gate, clamp);
	}

	// ── 轨迹组 ───────────────────────────────────────────────────────

	record TraceStats(int files, int undecodable, int partialTails, List<List<TurnTrace>> games) {
	}

	TraceStats scanTraces(Path dir, Narrative narrative) throws IOException {
		List<List<TurnTrace>> games = new ArrayList<>();
		int files = 0;
		int bad = 0;
		int partial = 0;
		if (Files.isDirectory(dir)) {
			List<Path> list;
			try (Stream<Path> s = Files.list(dir)) {
				list = s.filter(p -> p.getFileName().toString().endsWith(".trace.jsonl") && Files.isRegularFile(p))
						.sorted().toList();
			}
			for (Path p : list) {
				files++;
				TraceFileReader.Result r;
				try {
					r = TraceFileReader.read(p, mapper);
				} catch (Exception e) {
					bad++;
					continue;
				}
				if (r.partialTailSkipped()) {
					partial++;
				}
				for (TurnTrace t : r.traces()) {
					narrative.addDocument(t.pre());
					narrative.add(t.streamedNarrative() == null ? null : mapper.getNodeFactory().stringNode(t.streamedNarrative()));
					if (t.parsed() != null) {
						narrative.add(t.parsed().get("narrative"));
						narrative.addField(t.parsed().get("availableActions"), "text");
						narrative.addField(t.parsed().get("availableActions"), "hint");
						narrative.add(t.parsed().get("timeline"));
					}
				}
				games.add(r.traces());
			}
		}
		return new TraceStats(files, bad, partial, games);
	}

	// ── 报告 ─────────────────────────────────────────────────────────

	String report(SaveStats s, TraceStats t) {
		StringBuilder b = new StringBuilder();
		b.append(FIRST_LINE).append('\n').append('\n');
		saveSection(b, s);
		b.append('\n');
		traceSection(b, t);
		return b.toString();
	}

	private void saveSection(StringBuilder b, SaveStats s) {
		List<SaveRow> effective = s.rows().stream().filter(r -> r.turn() >= 1).toList(); // 已决 1
		long zero = s.rows().stream().filter(r -> r.turn() == 0).count();
		b.append("## 存档组(/data 根下 *.json;跨模型版本,无法切分)\n");
		b.append("- 候选档总数:").append(s.candidates()).append('\n');
		b.append("- 非存档跳过:").append(s.notASave()).append('\n');
		b.append("- 拒载:").append(s.refused()).append('\n');
		b.append("- 载入:").append(s.rows().size()).append('\n');
		b.append("- turn == 0(不进分母):").append(zero).append('\n');
		b.append("- 有效档(turn ≥ 1):").append(effective.size()).append('\n');
		b.append("- 单局回合数(降级回合计入;分母 = 有效档):\n");
		turnLine(b, "合计", effective);
		turnLine(b, "ended", effective.stream().filter(r -> "ended".equals(r.status())).toList());
		turnLine(b, "ongoing", effective.stream().filter(r -> !"ended".equals(r.status())).toList());
		b.append("- 单局最大回合数:")
				.append(effective.stream().mapToInt(SaveRow::turn).max().stream().mapToObj(String::valueOf).findFirst()
						.orElse("—"))
				.append('\n');
		b.append("- 按 archetype 分布(有效档;融合按 host 在前的有序对):\n");
		Map<String, Long> groups = new TreeMap<>();
		effective.forEach(r -> groups.merge(r.group(), 1L, Long::sum));
		groups.forEach((g, n) -> b.append("  - ").append(g).append(":").append(ratio(n, effective.size())).append('\n'));
		List<SaveRow> ended = effective.stream().filter(r -> "ended".equals(r.status())).toList();
		b.append("- ended 局结局 outcome 分布:\n");
		Map<String, Long> outcomes = new TreeMap<>();
		ended.forEach(r -> outcomes.merge(r.outcome(), 1L, Long::sum));
		outcomes.forEach((o, n) -> b.append("  - ").append(o).append(":").append(ratio(n, ended.size())).append('\n'));
		b.append("- issues(有效档,仅计数):结局 gate 改判 ")
				.append(effective.stream().mapToInt(SaveRow::gateIssues).sum())
				.append(" 次;收束钳制 ").append(effective.stream().mapToInt(SaveRow::clampIssues).sum()).append(" 次\n");
		double avg = effective.stream().mapToInt(SaveRow::turn).average().orElse(0);
		boolean thawed = effective.size() >= 50 && avg > 5;
		b.append("- 解冻判定(有效档 ≥ 50 且单局平均回合数 > 5):").append(thawed ? "满足" : "不满足")
				.append("(有效档 ").append(effective.size()).append(",单局平均回合数 ")
				.append(effective.isEmpty() ? "—" : num(avg)).append(")\n");
	}

	private static void turnLine(StringBuilder b, String name, List<SaveRow> rows) {
		List<Integer> turns = rows.stream().map(SaveRow::turn).toList();
		b.append("  - ").append(name).append(":平均 ").append(mean(turns)).append(",中位数 ").append(median(turns))
				.append("(n = ").append(rows.size()).append(")\n");
	}

	private void traceSection(StringBuilder b, TraceStats t) {
		List<TurnTrace> all = t.games().stream().flatMap(List::stream).toList();
		int turns = all.size();
		b.append("## 轨迹组(").append(TRACE_SCOPE).append(";n = ").append(t.games().size()).append(" 局 / ")
				.append(turns).append(" 回合,取实际读到的样本数)\n");
		b.append("- 轨迹文件:").append(t.files()).append("(无法解码 ").append(t.undecodable()).append(",半截末行跳过 ")
				.append(t.partialTails()).append(")\n");
		List<String> days = all.stream().map(TurnTrace::recordedAt).filter(r -> r != null && r.length() >= 10)
				.map(r -> r.substring(0, 10)).filter(d -> DAY.matcher(d).matches()).sorted().toList();
		b.append("- recordedAt:").append(days.isEmpty() ? "—" : days.get(0) + " ~ " + days.get(days.size() - 1))
				.append("(无 recordedAt ").append(turns - days.size()).append(" 回合)\n");
		b.append("- commit 分布:\n");
		dist(b, all.stream().map(x -> label(x.commit())).toList(), turns);

		List<TurnTrace.CallUsage> calls = all.stream().filter(x -> x.usage() != null).flatMap(x -> x.usage().stream())
				.toList();
		b.append("- usage[].model 分布(按调用逐条):\n");
		dist(b, calls.stream().map(c -> c.model() == null ? "(缺 model 字段)" : label(c.model())).toList(),
				calls.size());

		List<TurnTrace> degraded = all.stream().filter(x -> TurnTrace.PATH_DEGRADED.equals(x.path())).toList();
		b.append("- 降级率:").append(ratio(degraded.size(), turns)).append('\n');
		b.append("  - 原因分布:\n");
		Map<String, Long> reasons = new TreeMap<>();
		degraded.forEach(x -> reasons.merge(label(x.degradeReason()), 1L, Long::sum));
		reasons.forEach((r, n) -> b.append("    - ").append(r).append(":").append(ratio(n, degraded.size())).append('\n'));
		b.append("- 回合侧修复率:").append(ratio(all.stream().filter(x -> x.repairErrors() != null).count(), turns))
				.append('\n');

		b.append("- 平均 token(按调用名分开):\n");
		Map<String, List<LlmUsage>> byCall = new TreeMap<>();
		calls.stream().filter(c -> c.usage() != null)
				.forEach(c -> byCall.computeIfAbsent(label(c.call()), k -> new ArrayList<>()).add(c.usage()));
		byCall.forEach((call, us) -> {
			b.append("  - ").append(call).append("(n = ").append(us.size()).append(" 次调用):prompt ")
					.append(mean(us.stream().map(LlmUsage::promptTokens).filter(v -> v >= 0).toList()))
					.append(" / completion ")
					.append(mean(us.stream().map(LlmUsage::completionTokens).filter(v -> v >= 0).toList()))
					.append(" / total ").append(mean(us.stream().map(LlmUsage::totalTokens).filter(v -> v >= 0).toList()))
					.append(";缓存命中率(输入 token)");
			long hit = 0;
			long both = 0;
			for (LlmUsage u : us) {
				if (u.cacheHitTokens() >= 0 && u.cacheMissTokens() >= 0) {
					hit += u.cacheHitTokens();
					both += u.cacheHitTokens() + u.cacheMissTokens();
				}
			}
			b.append(ratio(hit, both)).append('\n');
		});

		b.append("- 按配置单价估算的回合成本(不是实际花费;provider ").append(price.providerKey())
				.append(",application.yml 当前配置,该配置注释写明取高峰档;CNY / 1M tokens:命中 ")
				.append(price.price().inputCacheHit()).append(" / 未命中 ").append(price.price().inputCacheMiss())
				.append(" / 输出 ").append(price.price().output())
				.append(";被中止的调用可能缺 usage,估算偏低):\n");
		List<Double> perTurn = all.stream().map(this::turnCost).toList();
		b.append("  - 平均每回合:¥").append(meanMoney(perTurn)).append("(n = ").append(turns).append(" 回合)\n");
		List<Double> perGame = t.games().stream().filter(g -> !g.isEmpty())
				.map(g -> g.stream().mapToDouble(this::turnCost).sum()).toList();
		b.append("  - 每局有轨迹回合之和(不含 world-gen):平均 ¥").append(meanMoney(perGame)).append(",最大 ¥")
				.append(perGame.isEmpty() ? "—" : money(Collections.max(perGame))).append("(n = ")
				.append(perGame.size()).append(" 局)\n");

		List<Long> durs = all.stream().map(TurnTrace::durMs).filter(d -> d >= 0).sorted().toList();
		long missing = all.stream().filter(x -> x.durMs() < 0).count();
		b.append("- durMs(-1 不进分布,单独计数 ").append(missing).append(";n = ").append(durs.size()).append("):");
		if (durs.isEmpty()) {
			b.append("—\n");
		} else {
			b.append("p50 ").append(percentile(durs, 0.5)).append(" / p90 ").append(percentile(durs, 0.9))
					.append(" / max ").append(durs.get(durs.size() - 1)).append('\n');
		}
	}

	private double turnCost(TurnTrace t) {
		if (t.usage() == null) {
			return 0;
		}
		return t.usage().stream().filter(c -> c.usage() != null)
				.mapToDouble(c -> TokenPricing.costCny(c.usage(), price.price())).sum();
	}

	private static String money(double v) {
		return String.format(Locale.ROOT, "%.4f", v);
	}

	private static String meanMoney(List<Double> xs) {
		return xs.isEmpty() ? "—" : money(xs.stream().mapToDouble(Double::doubleValue).average().orElse(0));
	}

	private static void dist(StringBuilder b, List<String> keys, long denom) {
		Map<String, Long> m = new TreeMap<>();
		keys.forEach(k -> m.merge(k, 1L, Long::sum));
		if (m.isEmpty()) {
			b.append("  - —(n = 0)\n");
		}
		m.forEach((k, n) -> b.append("  - ").append(k).append(":").append(ratio(n, denom)).append('\n'));
	}

	// ── 单价来源 ─────────────────────────────────────────────────────

	/**
	 * 从 classpath 上的 application.yml(与线上同一份源文件)绑定 {@link LlmProperties},绑定路径同 {@code YmlBoundPriceTest}。
	 * yml 里<b>恰好一个</b> provider 带 price 时用它;否则拒绝运行(不猜)。
	 */
	public static PriceSource priceFromApplicationYml() throws IOException {
		String prefix = LlmProperties.class.getAnnotation(ConfigurationProperties.class).value();
		List<PropertySource<?>> yml = new YamlPropertySourceLoader()
				.load("application.yml", new ClassPathResource("application.yml"));
		MutablePropertySources sources = new MutablePropertySources(); // 只有 yml:不吃本机环境变量 / 系统属性
		yml.forEach(sources::addLast);
		LlmProperties llm = new Binder(ConfigurationPropertySources.from(sources)).bindOrCreate(prefix,
				LlmProperties.class);
		List<Map.Entry<String, LlmProperties.Provider>> priced = llm.providers() == null ? List.of()
				: llm.providers().entrySet().stream().filter(e -> e.getValue().price() != null).toList();
		if (priced.size() != 1) {
			throw new IllegalStateException("application.yml 里带 price 的 provider 不是恰好一个(" + priced.size()
					+ "),不猜用哪一个");
		}
		return new PriceSource(priced.get(0).getKey(), priced.get(0).getValue().price());
	}
}
