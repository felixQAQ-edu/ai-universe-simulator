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

	/** 内核里每条守则的写法:「- 实录 · 正文「……」」/「- 旧说 · 正文「……」」。 */
	private static final Pattern RULE = Pattern.compile("- (实录|旧说) · 正文「([^」]*)」");

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
	void kernelCarriesAdrDecisions() {
		assertThat(kernel)
				// 结局池 4 条(决策 7),neutral 那条的 id 由本刀定为 alone_through_night。
				.contains("body_fails「身殒封台」outcome=failure")
				.contains("clarity_lost「叩台」outcome=failure")
				.contains("net_passed「网过」outcome=success")
				.contains("alone_through_night「独过此夜」outcome=neutral")
				.doesNotContain("stamped").doesNotContain("slept_through")
				// 让运只在起网当场提出、当回合兑现(决策 4)。
				.contains("让运只在起网当场当面提出、当回合兑现")
				// 「留养」新落点(决策 5)。
				.contains("除「验讫」外还有一枚小「留养」").contains("他在观星台值夜")
				// 可见字段只准第一级(决策 3)。
				.contains("【world-gen 产出的所有玩家可见字段只准用第一级词汇】")
				// 丹房夜里不出领药(决策 2 / CONTEXT §三.16 (8))。
				.contains("夜里不给「领药」一类选项");
		// 旧说 2 的跨回合效果已删(只写当回合后果)。
		assertThat(kernel).doesNotContain("起网时更易被选中");
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
