package com.aiuniverse.server.eventloop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.persistence.SessionDocument;
import com.aiuniverse.server.persistence.TraceFileReader;
import com.aiuniverse.server.persistence.TurnTrace;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 复验短局 · 轨迹核读报告(测试侧工具,W-10;由 Felix 在本机运行,见 {@code docs/adr028-manual-runs.md} §九)。
 *
 * <p>输入一份线上取回的原始 {@code .trace.jsonl},逐回合输出:局面编排(由 {@code pre} 经 {@link EventLoopService#scenePlan}
 * 重算,与线上同一份代码)、正文、本回合给出的选项、逐字句窗口(由 {@link EventLoopService#renderTurnPrompt} 重渲染,
 * 先核对 {@code promptSha256})、以及只标记不判定的检查项。
 *
 * <p>与 {@link TraceFixtureConverter} 同一套闸门:输入与输出都必须在仓库目录<b>之外</b>;报告不回显输入文件名、
 * 不含 saveId,也不含引擎字段名 {@code isTrue} / {@code hiddenLogic}(正文里若出现,遮蔽并计数)。
 * 报告只引用正文、选项与编排结果 —— 不输出 {@code pre} 里的规则真伪与隐藏逻辑。
 */
final class TraceRecheck {

	/** 七句原句的「疑似改写」关键片段(报告头部原样列出)。{@code 不是。} 只认独立成句,不设片段。 */
	static final Map<String, List<String>> FRAGMENTS = new LinkedHashMap<>();
	static {
		FRAGMENTS.put("楼道里有金属碰金属的声音。", List.of("金属碰金属", "金属声"));
		FRAGMENTS.put("你去床脚那块地方趴下。", List.of("床脚"));
		FRAGMENTS.put("你抬起头。", List.of("抬起头"));
		FRAGMENTS.put("是别的门。", List.of("别的门"));
		FRAGMENTS.put("你把头放下去。", List.of("头放下", "把头低"));
		FRAGMENTS.put("不是。", List.of());
		FRAGMENTS.put("你抬了一下头。", List.of("抬了一下头", "抬了下头"));
	}

	static final List<String> KEY_WORDS = List.of("钥匙", "多年以后");
	static final List<String> EXPLAIN_WORDS = List.of("搬家", "遗弃", "收养", "被带走", "被留下", "不要它了");
	static final List<String> F031_WORDS = List.of("纸箱", "箱子", "胶带", "搬", "车");
	static final List<String> NIGHT_WORDS = List.of("天黑", "夜", "晚上", "第二天", "次日");
	static final List<String> INSIDE_WORDS = List.of("屋里", "回到屋", "门内");
	static final Set<String> LEFT_AFTERMATH_BEATS = Set.of("B1", "B2", "B3");
	static final List<String> ENGINE_FIELDS = List.of("isTrue", "hiddenLogic");

	static final String SHA_WARNING = "⚠️⚠️ 重渲染不一致,窗口判读不可信";
	static final String MASK = "[引擎字段名已遮蔽]";

	private final ObjectMapper mapper;
	private final ArchetypeRegistry registry;
	private final EventLoopService service;

	TraceRecheck(ObjectMapper mapper, ArchetypeRegistry registry) {
		this.mapper = mapper;
		this.registry = registry;
		this.service = new EventLoopService(new TurnTraceTest.ScriptedLlm(), new TurnPromptBuilder(registry), mapper);
	}

	/** 读文件、写 {@code <outPrefix>.recheck.txt};两条路径都必须在 {@code repoRoot} 之外。返回报告文件。 */
	Path run(Path in, Path outPrefix, Path repoRoot) throws IOException {
		Path out = outPrefix.resolveSibling(outPrefix.getFileName() + ".recheck.txt");
		TraceFixtureConverter.requireOutsideRepo(repoRoot, in, "输入");
		TraceFixtureConverter.requireOutsideRepo(repoRoot, out, "输出");
		TraceFileReader.Result read = TraceFileReader.read(in, mapper);
		Files.writeString(out, report(read.traces(), read.partialTailSkipped()), StandardCharsets.UTF_8);
		return out;
	}

	String report(List<TurnTrace> traces, boolean partialTailSkipped) {
		StringBuilder body = new StringBuilder();
		List<String> shaMismatch = new ArrayList<>();
		for (int i = 0; i < traces.size(); i++) {
			TurnTrace t = traces.get(i);
			TurnTrace next = i + 1 < traces.size() ? traces.get(i + 1) : null;
			line(body, t, next, shaMismatch);
		}

		StringBuilder head = new StringBuilder();
		head.append("# 复验短局 · 轨迹核读报告\n");
		if (!shaMismatch.isEmpty()) {
			head.append(SHA_WARNING).append(":以下回合重渲染的 prompt 与轨迹记录的 promptSha256 不同 —— ")
					.append(String.join("、", shaMismatch))
					.append("。这些回合的「本回合允许的原句」与「允许 / 禁用」标注不可信(多半是本地代码与线上版本不同)。\n\n");
		}
		head.append("输入文件: (不回显)").append(partialTailSkipped ? "(末尾半行已跳过)" : "").append('\n');
		head.append("轨迹行数: ").append(traces.size()).append('\n');
		head.append("轨迹记录的代码版本: ")
				.append(traces.stream().map(TurnTrace::commit).distinct().toList()).append("  本地构建: ")
				.append(TurnTraceCollector.COMMIT).append('\n');
		head.append("prompt 重渲染核对: ")
				.append(shaMismatch.isEmpty() ? "全部一致" : "不一致 " + shaMismatch.size() + " 回合(见顶部警告)")
				.append("\n\n");
		head.append("## 判读规则(只标记,不判定;判读交给人)\n");
		head.append("- 「逐字出现」= 正文里有与原句完全相同的子串(含句号)。「不是。」只在独立成句时算"
				+ "(前面是行首 / 。!?/ 」/ 引号 / 换行)。\n");
		head.append("- 「疑似改写」= 正文里出现下列关键片段之一,且不落在逐字出现的那一段里:\n");
		FRAGMENTS.forEach((s, f) -> head.append("    ").append(s).append(" ← ")
				.append(f.isEmpty() ? "(不设片段)" : String.join(" / ", f)).append('\n'));
		head.append("- 每处标「允许窗口内」(该原句在本回合 prompt 的「本回合可用原句」里)或「⚠️ 禁用窗口出现」。\n");
		head.append("- 其余检查只列命中的原句,不下结论。句子按 。!?换行 切分。\n");
		head.append("- 局面编排(处境 / 结算结果 / 拍号)由回合前状态重算;「处境」是本回合编排之后的处境"
				+ "(结算 / 离开在生成这一回合时发生)。\n\n");

		head.append("## 开场(第 0 回合)\n");
		head.append("轨迹不含开场叙事(开场叙事不进存档文档,pre 里取不到):开场须人工截图。\n");
		if (!traces.isEmpty() && traces.get(0).turnBefore() != 0) {
			head.append("⚠️ 第一行轨迹不是从第 0 回合开始(回合前 = ").append(traces.get(0).turnBefore())
					.append("):前面的回合不在这份文件里。\n");
		}
		head.append('\n');

		String text = head.append(body).toString();
		int masked = 0;
		for (String f : ENGINE_FIELDS) {
			masked += count(text, f);
			text = text.replace(f, MASK);
		}
		for (TurnTrace t : traces) {
			text = text.replace(t.saveId(), "<saveId>");
		}
		if (masked > 0) {
			text = text.replaceFirst("\n\n## 判读规则", "\n⚠️ 正文 / 选项里出现引擎字段名 " + masked
					+ " 处,已遮蔽为 " + MASK + "(本身就是泄露,须人工核读该回合)\n\n## 判读规则");
		}
		return text;
	}

	private void line(StringBuilder out, TurnTrace t, TurnTrace next, List<String> shaMismatch) {
		int turn = t.turnBefore() + 1;
		GameSession s = SessionDocument.decode(t.saveId(), t.pre(), mapper, registry);
		BoxSceneTurn.Plan scene = service.scenePlan(s, t.actionId());
		String prompt = service.renderTurnPrompt(s, t.actionId());
		boolean shaOk = TurnTrace.sha256Hex(prompt).equals(t.promptSha256());
		if (!shaOk) {
			shaMismatch.add("T" + turn);
		}

		out.append("════════ 第 ").append(turn).append(" 回合(在第 ").append(t.turnBefore()).append(" 回合按 ")
				.append(t.actionId()).append(")path=").append(t.path());
		if (t.degradeReason() != null) {
			out.append(" 降级原因=").append(t.degradeReason());
		}
		out.append('\n');
		out.append("局面: ");
		if (scene == null) {
			BoxSceneState st = s.boxScene();
			out.append(st == null ? "无局面键(非《动物人生》或旧档)" : st.isLegacy() ? "旧局(不接局面层)" : "本回合无局面编排")
					.append('\n');
		} else {
			out.append("处境=").append(scene.newSituation() == null ? "无" : scene.newSituation())
					.append(" 结算结果=").append(scene.newResult() == null ? "未结算" : scene.newResult())
					.append(" 拍号=").append(scene.beatId() == null ? "无" : scene.beatId())
					.append(scene.transition() ? " 【离开回合】" : "").append('\n');
		}

		// 正文:settled 取回灌后的 narrative;degraded 取已流出叙事 + 编排重算的离开叙事(若有)
		String narrative;
		ReplayResult replayed = replay(t);
		if (TurnTrace.PATH_SETTLED.equals(t.path())) {
			narrative = t.parsed().path("narrative").asString("");
		} else {
			narrative = t.streamedNarrative() == null ? "" : t.streamedNarrative();
			if (replayed.leaveNarrative() != null) {
				narrative = narrative + replayed.leaveNarrative();
				out.append("(降级的离开回合:正文末尾的离开叙事由编排重算,与线上同一份代码)\n");
			}
		}
		out.append("── 正文 ──\n").append(narrative.isEmpty() ? "(空)" : narrative).append('\n');

		out.append("── 本回合给出的选项 ──");
		JsonNode actions;
		if (next != null) {
			out.append("(取自下一行的回合前状态)\n");
			actions = next.pre().path("currentActions");
		} else {
			out.append("(最后一行:由档 1 落账回放重算,与线上同一份落账代码)\n");
			actions = replayed.actions();
		}
		String optionsText = options(actions);
		out.append(optionsText.isEmpty() ? "(取不到 / 无选项)\n" : optionsText);

		out.append("── 逐字句窗口 ──").append(shaOk ? "(prompt 重渲染与记录一致)" : "(" + SHA_WARNING + ")")
				.append('\n');
		List<String> allowed = allowedOriginals(prompt);
		if (allowed == null) {
			out.append("本回合 prompt 无逐字句窗口块(旧局 / 已结束 / 非本世界)\n");
			allowed = List.of();
		} else {
			out.append("本回合允许的原句: ").append(allowed.isEmpty() ? "无" : String.join(" ", allowed)).append('\n');
		}
		List<String> verbatim = verbatimFindings(narrative, allowed);
		out.append(verbatim.isEmpty() ? "七句原句:正文未出现(逐字或疑似改写均无)\n" : String.join("\n", verbatim) + "\n");
		hits(out, "钥匙 / 多年以后", narrative, KEY_WORDS);

		out.append("── 只标记的检查 ──\n");
		hits(out, "解释词", narrative, EXPLAIN_WORDS);
		List<String> it = sentencesWith(narrative, List.of("它"));
		out.append("人称「它」: ").append(count(narrative, "它")).append(" 次").append(it.isEmpty() ? "" : "\n")
				.append(String.join("\n", it)).append('\n');
		if (turn <= 10) {
			hits(out, "F-031 T1–10 纸箱类", narrative, F031_WORDS);
		}
		if (scene != null && scene.beatId() != null && LEFT_AFTERMATH_BEATS.contains(scene.beatId())) {
			hits(out, scene.beatId() + " 夜 / 次日", narrative, NIGHT_WORDS);
		}
		if (scene != null && scene.transition()) {
			out.append("离开回合 · 选项全文见上;");
			hits(out, "离开回合 屋内", narrative, INSIDE_WORDS);
		}
		out.append('\n');
	}

	private record ReplayResult(String leaveNarrative, JsonNode actions) {
	}

	/** 在一份新解码的会话上跑与线上同一份落账代码,取离开叙事与落账后的选项。 */
	private ReplayResult replay(TurnTrace t) {
		GameSession s = SessionDocument.decode(t.saveId(), t.pre(), mapper, registry);
		BoxSceneTurn.Plan scene = service.scenePlan(s, t.actionId());
		String leave = null;
		if (TurnTrace.PATH_SETTLED.equals(t.path())) {
			service.landSettled(s, t.parsed().deepCopy(), t.actionId(), scene, leak -> {
			});
		} else {
			leave = service.landDegraded(s, t.streamedNarrative() == null ? "" : t.streamedNarrative(), t.actionId(),
					scene, () -> {
					});
		}
		return new ReplayResult(leave, s.currentActions());
	}

	private static String options(JsonNode actions) {
		StringBuilder sb = new StringBuilder();
		if (actions != null && actions.isArray()) {
			for (JsonNode a : actions) {
				sb.append("  ").append(a.path("id").asString("?")).append(" ").append(a.path("text").asString(""));
				String hint = a.path("hint").asString("");
				if (!hint.isEmpty()) {
					sb.append("  —— ").append(hint);
				}
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	/** 取出「本回合可用原句」(不带引号);prompt 里没有窗口块 → {@code null}。 */
	static List<String> allowedOriginals(String prompt) {
		int h = prompt.indexOf(VerbatimWindows.HEADER);
		if (h < 0) {
			return null;
		}
		List<String> out = new ArrayList<>();
		String[] lines = prompt.substring(h).split("\n");
		boolean inList = false;
		for (int i = 1; i < lines.length; i++) {
			String l = lines[i];
			if (l.equals(VerbatimWindows.ORIGINALS_LEAD)) {
				inList = true;
			} else if (inList && l.startsWith("    「")) {
				out.add(l.substring(l.indexOf('「') + 1, l.indexOf('」')));
			} else if (inList) {
				break;
			}
		}
		return out;
	}

	private static final Pattern STANDALONE_NO = Pattern.compile("(^|[。！？!?」“\"\\n])不是。");

	/** 七句原句的逐字 / 疑似改写命中,每处一行。 */
	static List<String> verbatimFindings(String narrative, List<String> allowed) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : FRAGMENTS.entrySet()) {
			String sentence = e.getKey();
			String tag = allowed.contains(sentence) ? "允许窗口内" : "⚠️ 禁用窗口出现";
			List<int[]> exact = new ArrayList<>();
			if (sentence.equals("不是。")) {
				Matcher m = STANDALONE_NO.matcher(narrative);
				while (m.find()) {
					exact.add(new int[] { m.end() - 3, m.end() });
				}
			} else {
				for (int i = narrative.indexOf(sentence); i >= 0; i = narrative.indexOf(sentence, i + 1)) {
					exact.add(new int[] { i, i + sentence.length() });
				}
			}
			for (int[] x : exact) {
				out.add("逐字出现「" + sentence + "」 → " + tag);
			}
			for (String frag : e.getValue()) {
				for (int i = narrative.indexOf(frag); i >= 0; i = narrative.indexOf(frag, i + 1)) {
					final int at = i;
					if (exact.stream().anyMatch(x -> at >= x[0] && at < x[1])) {
						continue;
					}
					out.add("疑似改写「" + sentence + "」(片段「" + frag + "」)→ " + tag + ":" + sentenceAt(narrative, at));
				}
			}
		}
		return out;
	}

	private static void hits(StringBuilder out, String label, String text, List<String> words) {
		List<String> s = sentencesWith(text, words);
		out.append(label).append(": ").append(s.isEmpty() ? "无命中" : s.size() + " 句").append('\n');
		s.forEach(x -> out.append(x).append('\n'));
	}

	/** 含任一词的句子,每句一行,前缀标出命中的词。 */
	static List<String> sentencesWith(String text, List<String> words) {
		List<String> out = new ArrayList<>();
		for (String sen : sentences(text)) {
			List<String> hit = words.stream().filter(sen::contains).toList();
			if (!hit.isEmpty()) {
				out.add("    [" + String.join("/", hit) + "] " + sen);
			}
		}
		return out;
	}

	static List<String> sentences(String text) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\n') {
				flush(out, cur);
				continue;
			}
			cur.append(c);
			if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') {
				while (i + 1 < text.length() && (text.charAt(i + 1) == '」' || text.charAt(i + 1) == '”')) {
					cur.append(text.charAt(++i));
				}
				flush(out, cur);
			}
		}
		flush(out, cur);
		return out;
	}

	private static void flush(List<String> out, StringBuilder cur) {
		String s = cur.toString().strip();
		if (!s.isEmpty()) {
			out.add(s);
		}
		cur.setLength(0);
	}

	private static String sentenceAt(String text, int at) {
		int pos = 0;
		for (String s : sentences(text)) {
			int i = text.indexOf(s, pos);
			if (i >= 0 && at >= i && at < i + s.length()) {
				return s;
			}
			if (i >= 0) {
				pos = i + s.length();
			}
		}
		return "";
	}

	static int count(String text, String word) {
		int n = 0;
		for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + word.length())) {
			n++;
		}
		return n;
	}
}
