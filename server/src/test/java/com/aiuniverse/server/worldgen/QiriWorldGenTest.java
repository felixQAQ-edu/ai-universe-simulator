package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.ArchetypeSummary;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-038 Slice B ·《七日开张》world-gen。钉:
 * <ol>
 * <li>指令槽内容(世界名、形状、开局、初值、规矩 8 条 4 真 4 假、结局池 4 条、招牌、示例语气、题材边界)与结局条数派生槽 4;</li>
 * <li>正向文字不出现「古代」(模式名只在「【世界名】」一条与骨架的模式标签处);</li>
 * <li>lockstep:{@code prompts/world-gen.md} 逐字收录指令槽;</li>
 * <li>世界专属检查三项(isTrue 布尔 / 四个固定结局 id / 真假各 ≥ 3),命中走一次修复,修复仍败 → 整局 ERROR;
 *     分派互斥 —— 《派单》与其他世界不受影响;</li>
 * <li>文案清单(Felix 2026-11-12 定稿)在 world-gen 侧能到的 11 项逐字在场(卡面两项经选择屏投影、九个档 label 经 bandRanges 投影),
 *     且 tagline / vibeTag 不进 world-gen 提示词(CONTEXT §三.15)。跨档台词归回合侧(Slice C 的同名测试补第 12 项)。</li>
 * </ol>
 */
class QiriWorldGenTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final WorldGenPromptBuilder prompts = new WorldGenPromptBuilder(registry);

	// ① 指令槽内容 ─────────────────────────────────────────────────────

	@Test
	void promptCarriesDirective() {
		String p = prompts.buildWorldPrompt("time_travel");
		assertThat(p).contains("- endings:4 个,").contains("endings 恰好 4 条").contains(WorldGenPromptBuilder.QIRI_WORLD_GEN_DIRECTIVE);
		assertThat(p).contains("这个世界叫《七日开张》:world.title 必须恰为「七日开张」")
				.contains("提示词里的模式名「穿越古代」只是分类标签")
				.contains("一局是七天,每天分早、午、晚三个回合:早回合进货,午、晚回合开摊营业")
				.contains("朝代不命名,只叫「本朝」").contains("被街坊当成失踪摊主的远房亲戚")
				.contains("字迹缺笔画的小抄,写着「这个时代的规矩」").contains("不揭示前任是谁")
				.contains("【初值】摊本 35;熟客 0;底细 80。");
		// 规矩:恰好 8 条、4 真 4 假,后果写死数字(决策 5 的表)。
		assertThat(p).contains("rules 恰好 8 条:4 条 isTrue:true、4 条 isTrue:false,每条都必须带布尔 isTrue")
				.contains("「历史不作数」那一条必须有,且必须是真的")
				.contains("真:你记得的历史在这儿一律不作数,别预言。hiddenLogic:不信而当众预言,落空后底细 −10。")
				.contains("真:招牌先写货名,再写摊名。hiddenLogic:写反则该回合营业 +0。")
				.contains("真:借邻摊炭火,要还一枚火头签。hiddenLogic:不还则熟客 −5。")
				.contains("真:雨天卖粉食,纸包要双折封口。hiddenLogic:不信则受潮退货,摊本 −4。")
				.contains("假:新摊第一天不得明码标价。hiddenLogic:竞争的摊主编来误导新摊的;信了被反复压价,摊本 −5。")
				.contains("假:同一客人问价第二次必须减半。hiddenLogic:过路客商杜撰的;信了亏本,摊本 −6。")
				.contains("假:红绳围起来的摊位最有人气。hiddenLogic:红绳其实是暂停营业的记号;信了挤到旁边,该回合营业 +0。")
				.contains("假:开市前在账本上画一条鱼,就免当日摊费。hiddenLogic:偷懒的摊主编的;信了照样付,另罚摊本 −4。")
				.contains("每条假规矩的 hiddenLogic 都要写明是谁编的、玩家凭什么现象能识破它");
		// 结局池 4 条(决策 4;怪点子王提示词侧写 11~39,读法甲)。
		assertThat(p).contains("1) night_market_top · 夜集第一摊 · success:百味夜集当晚,摊本 ≥ 60、熟客 ≥ 50、底细 ≥ 40。")
				.contains("2) odd_idea_king · 城中怪点子王 · success:百味夜集当晚,摊本 ≥ 60、底细在 11~39 之间。")
				.contains("3) open_as_usual · 明日照常开张 · neutral:百味夜集当晚,以上都不成立。")
				.contains("4) taoist_summoned · 请来了道士 · failure:底细降到 0;或夜集时判出成功但底细 ≤ 10。condition 只绑底细一条轴。")
				.contains("只用中文轴名 摊本 / 熟客 / 底细,不写 capital / regulars / cover")
				.contains("description 写得好笑,不写成惩罚");
		// 招牌软层、示例语气、题材边界(决策 6 / 7 / 8)。
		assertThat(p).contains("每条结局的 description 都要写出最后那块招牌上写的是什么")
				.contains("【示例语气 · 示例,不是固定剧情】")
				.contains("取号竹牌被邻摊学去,当成了抽奖").contains("「第二件半价」引来一群大爷辩论什么叫第二件")
				.contains("画出三栏账,邻摊大娘问你是不是把明天的账也算出来了")
				.contains("不引用任何真实诗文").contains("不预言、不借用真实历史")
				.contains("不出现真实朝代、年号、都城、官制专名,官职只用通称").contains("不写朝堂、皇帝、官场")
				.contains("不写火药、炸药、毒药等的制法;现代知识只限生活、经营、卫生、算术、说话方式")
				.contains("不用时事梗或出自真人的梗").contains("不写体罚,要罚只罚扫地、罚站").contains("主角是成年人,不写婚恋线");
		// 公共边界仍在骨架(ADR-037),本槽只加严。
		assertThat(p).contains("【现实边界 · 硬约束】");
	}

	@Test
	void directiveOnlyGoesToTimeTravel() {
		for (String id : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim", "animal_life", "cyberpunk")) {
			assertThat(prompts.buildWorldPrompt(id)).as(id).doesNotContain("七日开张").doesNotContain("百味夜集");
		}
	}

	// ② 正向文字不出现「古代」 ─────────────────────────────────────────

	@Test
	void guDaiOnlyInModeLabels() {
		String p = prompts.buildWorldPrompt("time_travel");
		List<String> kept = new ArrayList<>();
		for (String line : p.split("\n", -1)) {
			if (line.startsWith("- 【世界名】") || line.equals("【本模式 · 穿越古代】")
					|| line.startsWith("模式:穿越古代(单体,archetype=time_travel)")) {
				continue;
			}
			kept.add(line);
		}
		String positive = String.join("\n", kept);
		assertThat(positive).doesNotContain("古代");
		// 反向对照:剔掉的确实是模式标签所在。
		assertThat(p).contains("【本模式 · 穿越古代】").contains("提示词里的模式名「穿越古代」只是分类标签");
		assertThat(positive).contains("【规矩】").contains("【结局池】").contains("【题材边界】");
	}

	// ③ lockstep ────────────────────────────────────────────────────────

	@Test
	void worldGenMdCarriesTheDirectiveVerbatim() throws IOException {
		String md = Files.readString(repoRoot().resolve("prompts/world-gen.md"));
		assertThat(md).contains("```\n" + WorldGenPromptBuilder.QIRI_WORLD_GEN_DIRECTIVE.strip() + "\n```");
	}

	// ④ 世界专属检查三项 ─────────────────────────────────────────────────

	@Test
	void validWorldPassesAllThreeChecks() {
		assertThat(errors(world(4, 4, 0, List.of()))).isEmpty();
		// 不查恰好 8 条、4 真 4 假:3 真 3 假 6 条照样通过(已知代价 8)。
		assertThat(errors(world(3, 3, 0, List.of()))).isEmpty();
		assertThat(errors(world(5, 3, 0, List.of()))).isEmpty();
	}

	@Test
	void ruleWithoutBooleanIsTrueIsAnError() {
		List<String> e = errors(world(4, 4, 1, List.of()));
		assertThat(e).containsExactly("rules[8].isTrue: 本世界每条规矩都必须带布尔 isTrue(真规矩 true、假规矩 false)");
	}

	@Test
	void missingFixedEndingIdIsAnError() {
		List<String> e = errors(world(4, 4, 0, List.of("odd_idea_king")));
		assertThat(e).singleElement().asString().startsWith("endings: 缺少固定结局 odd_idea_king");
		assertThat(errors(world(4, 4, 0, List.of("taoist_summoned", "open_as_usual")))).hasSize(2);
	}

	@Test
	void fewerThanThreeTrueOrFalseRulesIsAnError() {
		assertThat(errors(world(2, 5, 0, List.of()))).singleElement().asString()
				.startsWith("rules: isTrue:true 的规矩只有 2 条,本世界至少 3 条");
		assertThat(errors(world(5, 2, 0, List.of()))).singleElement().asString()
				.startsWith("rules: isTrue:false 的规矩只有 2 条,本世界至少 3 条");
	}

	@Test
	void dispatchIsExclusive() {
		// 同一份缺项世界:只有 time_travel 走《七日开张》检查;cyberpunk 走自己的(这里规则都带 isTrue、无系统台词 → 空);其余与融合局 → 空。
		String bad = world(2, 2, 1, List.of("odd_idea_king"));
		assertThat(WorldGenService.worldSpecificErrors(List.of("time_travel"), mapper.readTree(bad))).isNotEmpty();
		String badNoMissingIsTrue = world(2, 2, 0, List.of("odd_idea_king"));
		assertThat(WorldGenService.worldSpecificErrors(List.of("cyberpunk"), mapper.readTree(badNoMissingIsTrue))).isEmpty();
		for (List<String> ids : List.of(List.of("rules_creepy"), List.of("life_sim"), List.of("cultivation", "rules_creepy"))) {
			assertThat(WorldGenService.worldSpecificErrors(ids, mapper.readTree(bad))).as(ids.toString()).isEmpty();
		}
	}

	@Test
	void failingCheckTriggersOneRepairThenSucceedsWithFixedTitle() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.responses.add(world(2, 4, 0, List.of("night_market_top")));
		llm.responses.add(world(4, 4, 0, List.of()));
		ObjectNode w = new WorldGenService(llm, prompts, mapper).generate("time_travel");
		assertThat(llm.requests).as("恰一次修复").hasSize(2);
		assertThat(llm.requests.get(1).prompt()).contains("isTrue:true 的规矩只有 2 条").contains("缺少固定结局 night_market_top");
		assertThat(w.path("world").path("title").asString()).isEqualTo("七日开张");
	}

	@Test
	void stillFailingAfterRepairIsWorldGenError() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.responses.add(world(4, 2, 0, List.of()));
		llm.responses.add(world(4, 2, 0, List.of()));
		assertThatThrownBy(() -> new WorldGenService(llm, prompts, mapper).generate("time_travel"))
				.isInstanceOf(WorldGenException.class);
		assertThat(llm.requests).hasSize(2);
	}

	// ⑤ 文案清单 ────────────────────────────────────────────────────────

	@Test
	void finalizedCopyVerbatimOnWorldGenSide() {
		ArchetypeSummary card = registry.listForSelection().stream().filter(s -> s.archetype().equals("time_travel"))
				.findFirst().orElseThrow();
		assertThat(card.displayName()).isEqualTo("穿越古代");
		assertThat(card.tagline()).isEqualTo("这条街什么都卖,就是没见过你这样卖的。");
		assertThat(card.vibeTag()).isEqualTo("市井 · 穿帮");
		assertThat(card.active()).isTrue();
		assertThat(labels("cover")).containsExactly("快露馅", "有点怪", "藏得住");
		assertThat(labels("capital")).containsExactly("靠赊账", "紧巴巴", "够周转");
		assertThat(labels("regulars")).containsExactly("生面孔", "回头客", "老主顾");
		// 卡面文案仅供选择屏展示,不进 world-gen(CONTEXT §三.15)。
		String p = prompts.buildWorldPrompt("time_travel");
		assertThat(p).doesNotContain(card.tagline()).doesNotContain(card.vibeTag());
	}

	// ── helpers ─────────────────────────────────────────────────────────

	private List<String> labels(String key) {
		AttributeAxis a = registry.meta("time_travel").attributes().stream().filter(x -> x.key().equals(key))
				.findFirst().orElseThrow();
		return a.bandRanges().stream().map(AttributeAxis.BandRange::label).toList();
	}

	private List<String> errors(String json) {
		return WorldGenService.worldSpecificErrors(List.of("time_travel"), mapper.readTree(json));
	}

	/**
	 * 一个能过 validateWorld 的《七日开张》世界:{@code trues} 条真、{@code falses} 条假、再加 {@code noIsTrue} 条不带 isTrue;
	 * 四个固定结局里去掉 {@code dropEndings}。
	 */
	private static String world(int trues, int falses, int noIsTrue, List<String> dropEndings) {
		StringBuilder rules = new StringBuilder();
		int id = 1;
		for (int i = 0; i < trues + falses + noIsTrue; i++, id++) {
			String truth = i < trues ? "\"isTrue\":true," : i < trues + falses ? "\"isTrue\":false," : "";
			if (rules.length() > 0) {
				rules.append(",");
			}
			rules.append("{\"id\":").append(id).append(",\"content\":\"小抄第").append(id).append("条\",").append(truth)
					.append("\"hiddenLogic\":\"摊本 −4\",\"discovered\":false}");
		}
		StringBuilder endings = new StringBuilder();
		String[][] all = { { "night_market_top", "夜集第一摊", "success" }, { "odd_idea_king", "城中怪点子王", "success" },
				{ "open_as_usual", "明日照常开张", "neutral" }, { "taoist_summoned", "请来了道士", "failure" } };
		for (String[] e : all) {
			if (dropEndings.contains(e[0])) {
				continue;
			}
			if (endings.length() > 0) {
				endings.append(",");
			}
			endings.append("{\"id\":\"").append(e[0]).append("\",\"title\":\"").append(e[1])
					.append("\",\"condition\":\"底细 ≥ 40\",\"outcome\":\"").append(e[2]).append("\",\"reached\":false}");
		}
		if (endings.length() == 0) {
			endings.append("{\"id\":\"x\",\"title\":\"终\",\"condition\":\"测\",\"outcome\":\"neutral\",\"reached\":false}");
		}
		return """
				{"schemaVersion":"0.4","mode":"single","archetypes":["time_travel"],
				 "world":{"title":"随便起的名","background":"一条街。","dangerLevel":"low","tone":"你接手了一个快倒闭的小摊。"},
				 "character":{"attributes":{"capital":35,"regulars":0,"cover":80},"traits":[],"inventory":[]},
				 "rules":[%s],
				 "endings":[%s],
				 "availableActions":[{"id":"A","text":"去进货","hint":"摊本要掂量"},{"id":"B","text":"先看小抄","hint":"真假难辨"}],
				 "openingNarrative":"钱匣里有一张小抄。"}
				""".formatted(rules, endings);
	}

	private static Path repoRoot() {
		Path p = Path.of("").toAbsolutePath();
		while (p != null && !Files.exists(p.resolve("prompts/world-gen.md"))) {
			p = p.getParent();
		}
		assertThat(p).as("找不到仓库根(prompts/world-gen.md)").isNotNull();
		return p;
	}

	private static class ScriptedLlm implements LlmClient {
		final Deque<String> responses = new ArrayDeque<>();
		final List<ChatRequest> requests = new ArrayList<>();

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			requests.add(request);
			String r = responses.poll();
			if (r == null) {
				throw new LlmException("脚本耗尽");
			}
			sink.onToken(r);
		}
	}
}
