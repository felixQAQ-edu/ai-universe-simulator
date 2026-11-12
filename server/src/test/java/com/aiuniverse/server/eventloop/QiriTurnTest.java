package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.ArchetypeSummary;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-038 Slice C ·《七日开张》回合侧。钉:
 * <ol>
 * <li>七日时钟 n → 文字的映射(决策 2;天数口径 8 − d,见 {@link TurnPromptBuilder#qiriTurnClock} 注释);</li>
 * <li>回合指令槽的落数、立功联动(含早回合立功)、圆回来、招牌、记忆点、题材边界,且数字与各轴 behaviorHint 同源;</li>
 * <li>《七日开张》局的回合提示词里时钟与【记忆点】的位置;指令槽只进本世界;</li>
 * <li>lockstep:{@code prompts/event-loop.md} 逐字收录指令槽、时钟模板与两行【记忆点】;</li>
 * <li>文案清单 12 项(Felix 2026-11-12 定稿)逐字在场:跨档台词在两行【记忆点】里,九个档 label 进回合提示词的当前档块,
 *     卡面两项经选择屏投影且不进回合提示词。</li>
 * </ol>
 * 跨档台词每局只浮一次的落账路径单测见 {@link QiriCoverDropTest}。
 */
class QiriTurnTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);

	static final String LINE = "隔壁摊的大娘把声音压得极低:「你们老家的人,做买卖都这么邪门儿?」";

	// ① 七日时钟 ───────────────────────────────────────────────────────

	@Test
	void clockMatchesDecision2Table() {
		Map<Integer, String> slot = new LinkedHashMap<>();
		slot.put(1, "第 1 天 · 早(进货),离百味夜集还有 7 天");
		slot.put(2, "第 1 天 · 午(开摊营业),离百味夜集还有 7 天");
		slot.put(3, "第 1 天 · 晚(开摊营业),离百味夜集还有 7 天");
		slot.put(4, "第 2 天 · 早(进货),离百味夜集还有 6 天");
		slot.put(16, "第 6 天 · 早(进货),离百味夜集还有 2 天");
		slot.put(18, "第 6 天 · 晚(开摊营业),离百味夜集还有 2 天");
		slot.put(19, "第 7 天 · 早(进货),明天就是百味夜集");
		slot.put(21, "第 7 天 · 晚(开摊营业),明天就是百味夜集");
		slot.put(22, "百味夜集 · 开摊");
		slot.put(23, "百味夜集 · 开摊");
		slot.put(30, "百味夜集 · 开摊");
		slot.forEach((n, s) -> {
			String head = "\n\n【本回合时段】" + s + "(本局第 " + n + " 回合)。叙事与 timeline 都按这个时段写,不要提前或拖后。";
			assertThat(TurnPromptBuilder.qiriTurnClock(n)).as("T" + n).startsWith(head);
		});
		assertThat(TurnPromptBuilder.QIRI_FINAL_TURN).isEqualTo(22);
	}

	@Test
	void everyTurnOneToTwentyOneHasItsDayAndPart() {
		String[] parts = { "早(进货)", "午(开摊营业)", "晚(开摊营业)" };
		for (int n = 1; n <= 21; n++) {
			int d = (n - 1) / 3 + 1;
			String c = TurnPromptBuilder.qiriTurnClock(n);
			assertThat(c).as("T" + n).contains("第 " + d + " 天 · " + parts[(n - 1) % 3])
					.contains(d == 7 ? ",明天就是百味夜集" : ",离百味夜集还有 " + (8 - d) + " 天");
		}
	}

	@Test
	void clockNeverLeaksEndingConditionsAndOnlyTurn22OnwardsAsksForAnEnding() {
		for (int n = 1; n <= 40; n++) {
			String c = TurnPromptBuilder.qiriTurnClock(n);
			assertThat(c.contains("本回合必须给出结局")).as("T" + n).isEqualTo(n >= 22);
			assertThat(c.contains("本回合按普通营业回合落数")).as("T" + n).isEqualTo(n == 22);
			assertThat(c).as("T" + n).doesNotContain("taoist_summoned").doesNotContain("isTrue").doesNotContain("%");
			if (n < 22) {
				assertThat(c).as("T" + n).doesNotContain("摊本").doesNotContain("底细").doesNotContain("熟客")
						.doesNotContain("判定").doesNotContain("结局");
			}
		}
	}

	// ② 回合指令槽 ─────────────────────────────────────────────────────

	@Test
	void directiveCarriesNumbersLinkageEarlyWinCoverUpSignboardAndBoundary() {
		String d = TurnPromptBuilder.QIRI_TURN_DIRECTIVE;
		assertThat(d).contains("本世界叫《七日开张》").contains("模式名「穿越古代」只是分类标签").contains("只叫「本朝」")
				.contains("每回合末尾注入的【本回合时段】是真理之源:早回合进货,午、晚回合开摊营业,第 22 回合是百味夜集")
				// 落数(决策 3)
				.contains("摊本:每天早回合进货固定 −6;午、晚开摊营业各 +2~+4;第 22 回合百味夜集按普通营业回合落 +2~+4;赔本事件 −5~−15;摊本归零 = 赊账度日,不结束")
				.contains("熟客:解决麻烦或让客人满意 +5~+8;得罪熟客 −8")
				.contains("底细:当众用现代招数 −6;说漏现代词 −4;被看见写阿拉伯数字 −3;大翻车 −12")
				// 立功联动 + 早回合立功(审定 4 / 5)
				.contains("午、晚回合与第 22 回合:摊本 +8~+12,代替当回合的营业收入(不再另加 +2~+4);底细 −6")
				.contains("早回合:进货那一笔不扣(省下 6),不另加 +8~+12;底细照扣 −6")
				// 圆回来
				.contains("底细 +6,同一回合摊本 −3,且这一回合不营业(没有 +2~+4)").contains("按上面的数落,不得大幅偏离")
				// 招牌、记忆点
				.contains("叙事里写出招牌眼下写的是什么").contains("以每回合注入的【记忆点】为准")
				// 题材边界(决策 8,与 world-gen 槽同写)
				.contains("不引用任何真实诗文").contains("不预言、不借用真实历史")
				.contains("不出现真实朝代、年号、都城、官制专名,官职只用通称").contains("不写朝堂、皇帝、官场")
				.contains("不写火药、炸药、毒药等的制法;现代知识只限生活、经营、卫生、算术、说话方式")
				.contains("不用时事梗或出自真人的梗").contains("不写体罚,要罚只罚扫地、罚站").contains("主角是成年人,不写婚恋线")
				.doesNotContain("%");
		// 模式名只在「【世界名】」一条。
		String rest = String.join("\n", d.lines().filter(l -> !l.startsWith("- 【世界名】")).toList());
		assertThat(rest).doesNotContain("古代");
	}

	/** CONTEXT §三.18(d):数字同时写进回合指令与该轴的 behaviorHint(两处同源)。 */
	@Test
	void directiveNumbersMatchBehaviorHints() {
		String d = TurnPromptBuilder.QIRI_TURN_DIRECTIVE;
		Map<String, List<String>> shared = Map.of(
				"capital", List.of("早回合进货固定 −6", "午、晚开摊营业各 +2~+4", "第 22 回合百味夜集按普通营业回合落 +2~+4",
						"+8~+12,代替当回合的营业收入", "进货那一笔不扣(省下 6),不另加 +8~+12", "赔本事件 −5~−15",
						"赊账度日,不结束", "这一回合不营业", "不得大幅偏离"),
				"regulars", List.of("解决麻烦或让客人满意 +5~+8", "得罪熟客 −8"),
				"cover", List.of("当众用现代招数 −6", "说漏现代词 −4", "被看见写阿拉伯数字 −3", "大翻车 −12",
						"+6,同一回合摊本 −3", "请街坊喝茶或送一份糕"));
		shared.forEach((key, frags) -> {
			String hint = axis(key).behaviorHint();
			for (String f : frags) {
				assertThat(hint).as(key + " behaviorHint:" + f).contains(f);
				assertThat(d).as("回合指令:" + f).contains(f);
			}
		});
	}

	// ③ 提示词里的位置 ─────────────────────────────────────────────────

	@Test
	void qiriPromptCarriesDirectiveClockAndMemoryLineInPlace() {
		for (int t : new int[] { 0, 2, 3, 18, 20, 21, 22, 25 }) {
			int n = t + 1;
			String p = builder.buildTurnPrompt(engine(t, 80), "A", "测试行动");
			assertThat(p).as("T" + n).containsOnlyOnce(TurnPromptBuilder.QIRI_TURN_DIRECTIVE.strip())
					.contains(TurnPromptBuilder.qiriTurnClock(n) + TurnPromptBuilder.QIRI_MEMORY_OFF + "\n\n请推进第 " + n + " 回合。")
					.containsOnlyOnce("【记忆点】本回合").doesNotContain("【腿况】");
		}
	}

	@Test
	void directiveAndClockOnlyGoToTimeTravel() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			if (m.id().equals("time_travel")) {
				continue;
			}
			String p = builder.buildTurnPrompt(engineFor(m.id(), 10), "A", "测试行动");
			assertThat(p).as(m.id()).doesNotContain("七日开张").doesNotContain("百味夜集").doesNotContain("隔壁摊的大娘");
		}
	}

	// ④ md lockstep ────────────────────────────────────────────────────

	@Test
	void eventLoopMdQuotesDirectiveClockAndMemoryLinesVerbatim() throws IOException {
		String md = Files.readString(repoRoot().resolve("prompts/event-loop.md"), StandardCharsets.UTF_8);
		assertThat(md).contains("```\n" + TurnPromptBuilder.QIRI_TURN_DIRECTIVE.strip() + "\n```");
		assertThat(md).contains("> 本回合按普通营业回合落数(立功照算),然后本回合必须给出结局。");
		assertThat(md).contains("> " + TurnPromptBuilder.QIRI_MEMORY_ON.strip())
				.contains("> " + TurnPromptBuilder.QIRI_MEMORY_OFF.strip());
		assertThat(md).contains("| 1–21 | 第 d 天(d = ⌈n/3⌉)· 早(进货)/ 午(开摊营业)/ 晚(开摊营业),离百味夜集还有 8 − d 天;第 7 天写「明天就是百味夜集」 |");
	}

	// ⑤ 文案清单 12 项 ─────────────────────────────────────────────────

	@Test
	void finalizedCopyAllTwelveVerbatim() {
		// 1–2:卡面(仅选择屏展示,不进回合提示词)。
		ArchetypeSummary card = registry.listForSelection().stream().filter(s -> s.archetype().equals("time_travel"))
				.findFirst().orElseThrow();
		assertThat(card.tagline()).isEqualTo("这条街什么都卖,就是没见过你这样卖的。");
		assertThat(card.vibeTag()).isEqualTo("市井 · 穿帮");
		// 3:跨档台词,逐字在两行【记忆点】里。
		assertThat(TurnPromptBuilder.QIRI_CROSSING_LINE).isEqualTo(LINE);
		assertThat(TurnPromptBuilder.QIRI_MEMORY_ON)
				.isEqualTo("\n【记忆点】本回合叙事开头原样写出这一句,一字不改,只写这一次:" + LINE);
		assertThat(TurnPromptBuilder.QIRI_MEMORY_OFF).isEqualTo("\n【记忆点】本回合不得出现这句话:" + LINE);
		// 4–12:九个档 label,按当前值进回合提示词的当前档块。
		Map<Integer, List<String>> byValue = Map.of(
				80, List.of("cover(底细)当前处于【藏得住】档", "capital(摊本)当前处于【够周转】档", "regulars(熟客)当前处于【老主顾】档"),
				35, List.of("cover(底细)当前处于【有点怪】档", "capital(摊本)当前处于【紧巴巴】档", "regulars(熟客)当前处于【回头客】档"),
				10, List.of("cover(底细)当前处于【快露馅】档", "capital(摊本)当前处于【靠赊账】档", "regulars(熟客)当前处于【生面孔】档"));
		byValue.forEach((v, frags) -> {
			String p = builder.buildTurnPrompt(engine(5, v, v, v), "A", "测试行动");
			frags.forEach(f -> assertThat(p).as("值 " + v).contains(f));
			assertThat(p).doesNotContain(card.tagline()).doesNotContain(card.vibeTag());
		});
	}

	// ── 夹具 ────────────────────────────────────────────────────────────

	private AttributeAxis axis(String key) {
		return registry.meta("time_travel").attributes().stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}

	private Engine engine(int turn, int cover) {
		return engine(turn, 50, 20, cover);
	}

	private Engine engine(int turn, int capital, int regulars, int cover) {
		return QiriCoverDropTest.engine(mapper, registry, "time_travel", turn,
				Map.of("capital", capital, "regulars", regulars, "cover", cover));
	}

	private Engine engineFor(String id, int turn) {
		return QiriCoverDropTest.engine(mapper, registry, id, turn, Map.of());
	}

	private static Path repoRoot() {
		Path p = Path.of("").toAbsolutePath();
		while (p != null && !Files.exists(p.resolve("prompts/event-loop.md"))) {
			p = p.getParent();
		}
		assertThat(p).as("找不到仓库根(prompts/event-loop.md)").isNotNull();
		return p;
	}
}
