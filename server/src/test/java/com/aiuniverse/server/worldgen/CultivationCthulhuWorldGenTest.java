package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

/**
 * ADR-035 第三组合《验讫》(cultivation × cthulhu)Slice B · world-gen:文案槽 / 融合内核 / 种子池。
 *
 * <p>揭示阶梯的词汇断言只针对<b>玩家可见</b>的两类文本 —— 守则正文(照抄进 {@code rules[].content})与种子
 * (原样拼进提示词末尾、直接喂开场)。融合内核的说明部分只给模型看,可以写明真相,不在断言范围内。
 */
class CultivationCthulhuWorldGenTest {

	/** 揭示阶梯第三级(知渔才可写)。 */
	private static final String[] LEVEL_THREE = { "捕捞", "出栏", "育肥", "排酸", "饵" };
	/** 揭示阶梯第二级里不易误报的词(「格」「收」是高频字,机械查会误报,ADR-035 已知代价 1)。 */
	private static final String[] LEVEL_TWO = { "网", "挑拣", "成色" };
	/** 揭示阶梯第二级整张表:只用于结局相关的短文本(人工可读完,不怕误报)。 */
	private static final String[] LEVEL_TWO_ALL = { "网", "格", "收", "挑拣", "成色" };

	/** 内核里每条守则的写法:「- 实录 · 正文「……」」/「- 旧说 · 正文「……」」。 */
	private static final Pattern RULE = Pattern.compile("- (实录|旧说) · 正文「([^」]*)」");

	/** 每条守则的 hiddenLogic 素材:从「hiddenLogic:」到下一条守则或空行为止。 */
	private static final Pattern HIDDEN_LOGIC =
			Pattern.compile("hiddenLogic:(.*?)(?=\\n\\s*- (?:实录|旧说) · |\\n\\s*\\n)", Pattern.DOTALL);

	private final WorldGenPromptBuilder builder = new WorldGenPromptBuilder(new ArchetypeRegistry());
	private final String kernel = WorldGenPromptBuilder.fusionMetaPrompt("cultivation", "cthulhu");

	@Test
	void fusionPromptBuildsWithCopyAndDerivedSlots() {
		String prompt = builder.buildFusionPrompt("cultivation", "cthulhu");
		assertThat(prompt).contains("实录").contains("旧说");
		// 派生槽:2 致命轴 → 结局 3-4 条;致命轴中文名清单 = 气血/清明(与 fusedAxes 同源)。
		assertThat(prompt).contains("endings:3-4 个").contains("每个致命数值轴(气血/清明)");
		assertThat(prompt).contains("【旧说(isTrue:false)至少 3 条、实录(不带 isTrue)至少 3 条】");
		assertThat(prompt).contains("\"cultivation\",\"cthulhu\"");
	}

	@Test
	void kernelFixesExactlyEightRulesFourEach() {
		List<String[]> rules = rules();
		assertThat(rules).hasSize(8);
		assertThat(rules.stream().filter(r -> r[0].equals("实录"))).hasSize(4);
		assertThat(rules.stream().filter(r -> r[0].equals("旧说"))).hasSize(4);
		// draft-2 实录 3(拓影)已删,降级为纯叙事(ADR-035 决策 3)。
		assertThat(kernel).doesNotContain("不拓人影").doesNotContain("拓影");
	}

	@Test
	void ruleBodiesUseOnlyLevelOneVocabulary() {
		for (String[] rule : rules()) {
			assertNoHigherLevelWords(rule[0] + " 正文", rule[1]);
		}
	}

	@Test
	void hiddenLogicUsesOnlyLevelOneVocabulary() {
		// 回合侧每回合把完整 rules(含 hiddenLogic)回传模型:hiddenLogic 里的高级词会在「未觉」档绕过按档放行。
		List<String> hidden = new ArrayList<>();
		Matcher m = HIDDEN_LOGIC.matcher(kernel);
		while (m.find()) {
			hidden.add(m.group(1));
		}
		assertThat(hidden).hasSize(8);
		for (String h : hidden) {
			assertNoHigherLevelWords("hiddenLogic", h);
			assertThat(h).as("hiddenLogic 不用「饲料」这类同义说法:%s", h).doesNotContain("饲料");
		}
	}

	@Test
	void seedsUseOnlyLevelOneVocabularyAndReconfirmRuleCounts() {
		List<String> seeds = WorldGenPromptBuilder.FUSION_SEED_POOLS.get("cultivation×cthulhu");
		assertThat(seeds).hasSize(5);
		for (String seed : seeds) {
			assertNoHigherLevelWords("种子", seed);
			assertThat(seed).as("种子以「大典次日」为时点:%s", seed).contains("大典次日");
			assertThat(seed).as("种子末尾带硬性再确认:%s", seed)
					.contains("【硬性再确认】rules 共 8 条")
					.contains("旧说 ≥3 条").contains("实录 ≥3 条");
		}
	}

	@Test
	void kernelOpensOnDayAfterCeremony() {
		// ADR-035 决策 5:开场是「大典次日」;draft-2 §2 的「三日前」与之不一致,以 ADR 为准。
		assertThat(kernel).contains("大典次日").doesNotContain("三日前");
	}

	@Test
	void kernelForbidsHintsRestatingHiddenLogic() {
		// Slice D 冒烟:开局 hint「这一拜等于把自己亮给天上看」「掰丹即识破」复述了隐藏判定。
		assertThat(kernel)
				.contains("availableActions 的 hint 只写这一步行动本身的代价与风险,不复述 hiddenLogic 的说法,不点明哪条守则是真是假,")
				.contains("也不用「识破」「推涨」这类说法。");
	}

	@Test
	void kernelCarriesAdrDecisions() {
		assertThat(kernel)
				// 结局池 4 条(决策 7),neutral 那条的 id 由本刀定为 alone_through_night。
				.contains("body_fails「身殒封台」outcome=failure")
				.contains("clarity_lost「叩台」outcome=failure")
				.contains("rite_survived「守台人还」outcome=success")
				.contains("alone_through_night「独过此夜」outcome=neutral")
				.doesNotContain("stamped").doesNotContain("slept_through")
				// 让运只在封台终仪当场提出、当回合兑现(决策 4,决策 7 修订)。
				.contains("让运只在封台终仪当场当面提出、当回合兑现")
				// 「留养」新落点(决策 5)。
				.contains("除「验讫」外还有一枚小「留养」").contains("他在观星台值夜")
				// 可见字段只准第一级(决策 3)。
				.contains("【world-gen 产出的所有玩家可见字段只准用第一级词汇】")
				// 丹房夜里不出领药(决策 2 / CONTEXT §三.16 (8))。
				.contains("夜里不给「领药」一类选项");
		// 旧说 2 的跨回合效果已删(只写当回合后果)。
		assertThat(kernel).doesNotContain("起网时更易被选中");
	}

	@Test
	void endingSlotsAndEndingPoolUseOnlyLevelOneVocabulary() {
		// 决策 7 修订:结局池 id / title / condition / description 每回合都回传模型,只准第一级词汇。
		// 这几处文本短,第二级整张表(含「格」「收」)都查,不会误报。
		WorldGenPromptBuilder.FusionWorldCopy copy = WorldGenPromptBuilder.FUSION_WORLD_COPY.get("cultivation×cthulhu");
		assertEndingTextClean("endingIdExamples", copy.endingIdExamples());
		assertEndingTextClean("successConditionExample", copy.successConditionExample());
		assertEndingTextClean("failureConditionExamples", copy.failureConditionExamples());
		assertEndingTextClean("failureOutcomeExamples", copy.failureOutcomeExamples());
		assertEndingTextClean("successOutcomeExamples", copy.successOutcomeExamples());
		assertEndingTextClean("内核【结局池】段", endingPool());
	}

	@Test
	void ritePlacementsAreCompleteAndThresholdIs21() {
		// 决策 7「终仪落点补全」:rite_survived 门槛 30 → 21;叩台补写三种落法;本组合 world-gen 文本不再有「清明≥30」。
		WorldGenPromptBuilder.FusionWorldCopy copy = WorldGenPromptBuilder.FUSION_WORLD_COPY.get("cultivation×cthulhu");
		assertThat(copy.successConditionExample())
				.isEqualTo("「角色存活,封台终仪已发生,你未被带走,封台终仪当场未选择让运,清明≥21」");
		assertThat(copy.thresholdModeration()).isEqualTo("清明≥21 即可、不要再叠窥天门槛");
		assertThat(endingPool())
				.contains("封台终仪当场未选择让运,\n")
				.contains("清明≥21」;description 写明这一次过去了,并没有结束。")
				.contains("封台终仪当场有三种落法走这一条(都在那一回合把清明落到 0):")
				.contains("既没有避开也没有让运;避开了但清明≤20;当面让运而对方不应。");
		String prompt = builder.buildFusionPrompt("cultivation", "cthulhu");
		assertThat(prompt).doesNotContain("清明≥30");
		assertThat(builder.buildRepairPrompt(java.util.List.of("cultivation", "cthulhu"), "{}", java.util.List.of("e")))
				.doesNotContain("清明≥30");
	}

	@Test
	void endingPoolUsesRiteSurvivedAndSealingRite() {
		assertThat(endingPool())
				.contains("rite_survived").contains("守台人还").contains("封台终仪已发生");
		assertThat(kernel).doesNotContain("net_passed");
	}

	/** 内核【结局池】段:从段头到【初值】为止。 */
	private String endingPool() {
		int from = kernel.indexOf("【结局池 · 恰好 4 条】");
		int to = kernel.indexOf("【初值】", from);
		assertThat(from).isNotNegative();
		assertThat(to).isGreaterThan(from);
		return kernel.substring(from, to);
	}

	private static void assertEndingTextClean(String what, String text) {
		for (String w : LEVEL_THREE) {
			assertThat(text).as("%s 不得含第三级词「%s」:%s", what, w, text).doesNotContain(w);
		}
		for (String w : LEVEL_TWO_ALL) {
			assertThat(text).as("%s 不得含第二级词「%s」:%s", what, w, text).doesNotContain(w);
		}
		assertThat(text).as("%s 不得含「起网」:%s", what, text).doesNotContain("起网");
	}

	private List<String[]> rules() {
		List<String[]> out = new ArrayList<>();
		Matcher m = RULE.matcher(kernel);
		while (m.find()) {
			out.add(new String[] { m.group(1), m.group(2) });
		}
		return out;
	}

	private static void assertNoHigherLevelWords(String what, String text) {
		for (String w : LEVEL_THREE) {
			assertThat(text).as("%s 不得含第三级词「%s」:%s", what, w, text).doesNotContain(w);
		}
		for (String w : LEVEL_TWO) {
			assertThat(text).as("%s 不得含第二级词「%s」:%s", what, w, text).doesNotContain(w);
		}
	}
}
