package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.AttributeAxis.BandRange;
import com.aiuniverse.server.archetype.AttributeAxis.Severity;

/**
 * ADR-035 第三组合《验讫》(cultivation × cthulhu,host=cultivation)Slice A · registry:
 * 融合轴集 / 清明·窥天换皮(含 behaviorHint override)/ 播种派生 / 档区间与 severity / 揭示阶梯的词汇护栏。
 */
class CultivationCthulhuFusionTest {

	/** 揭示阶梯第三级词汇(ADR-035 决策 3:知渔才可写)。 */
	private static final String[] LEVEL_THREE = { "捕捞", "出栏", "育肥", "排酸", "饵" };
	/** 揭示阶梯第二级里不易误报的词(「格」「收」是高频字,机械查会误报,ADR-035 已知代价 1)。 */
	private static final String[] LEVEL_TWO = { "网", "挑拣", "成色" };

	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@Test
	void comboIsRegisteredDirectionSensitiveAndFusesToFiveAxes() {
		assertThat(registry.isFusionSupported("cultivation", "cthulhu")).isTrue();
		// 方向敏感:反向 host 未登记(换皮只在 host=修仙 时成立)。
		assertThat(registry.isFusionSupported("cthulhu", "cultivation")).isFalse();
		assertThatThrownBy(() -> registry.fusedAxes("cthulhu", "cultivation"))
				.isInstanceOf(IllegalArgumentException.class);

		List<AttributeAxis> axes = registry.fusedAxes("cultivation", "cthulhu");
		// host 轴在前保序;cthulhu 撞键 hp 并掉(host 赢 → 气血),san / knowledge 换皮追加。
		assertThat(axes).extracting(AttributeAxis::key).containsExactly("hp", "mana", "realm", "san", "knowledge");
		assertThat(axes).extracting(AttributeAxis::displayName).containsExactly("气血", "灵力", "境界", "清明", "窥天");
	}

	@Test
	void derivationsYieldLethalHpSanAccumulationRealmKnowledgeNonLethalMana() {
		List<AttributeAxis> axes = registry.fusedAxes("cultivation", "cthulhu");
		assertThat(ArchetypeRegistry.lethalKeys(axes)).containsExactly("hp", "san");
		assertThat(ArchetypeRegistry.accumulationKeys(axes)).containsExactly("realm", "knowledge");
		assertThat(ArchetypeRegistry.nonLethalKeys(axes)).containsExactly("mana");
		assertThat(ArchetypeRegistry.axisDisplayNames(axes)).containsExactly(
				Map.entry("hp", "气血"), Map.entry("mana", "灵力"), Map.entry("realm", "境界"),
				Map.entry("san", "清明"), Map.entry("knowledge", "窥天"));
	}

	@Test
	void skinsKeepEngineFacingFieldsAndKuitianStaysDoubleEdged() {
		AttributeAxis qingming = axis("san");
		AttributeAxis kuitian = axis("knowledge");
		AttributeAxis originalSan = cthulhuAxis("san");
		AttributeAxis originalKnowledge = cthulhuAxis("knowledge");

		for (AttributeAxis[] pair : new AttributeAxis[][] { { qingming, originalSan }, { kuitian, originalKnowledge } }) {
			assertThat(pair[0].key()).isEqualTo(pair[1].key());
			assertThat(pair[0].min()).isEqualTo(pair[1].min());
			assertThat(pair[0].max()).isEqualTo(pair[1].max());
			assertThat(pair[0].axisRole()).isEqualTo(pair[1].axisRole());
			assertThat(pair[0].isLethal()).isEqualTo(pair[1].isLethal());
			assertThat(pair[0].perilAtHigh()).isEqualTo(pair[1].perilAtHigh());
		}
		// ADR-018:perilAtHigh 在「换皮不换」清单内 → 窥天仍是双刃轴。
		assertThat(kuitian.perilAtHigh()).isTrue();
		assertThat(qingming.perilAtHigh()).isFalse();
	}

	@Test
	void bandRangesAndSeverityMatchAdr035() {
		// 清明:致命 depletion,100/50/20 上界 → 0–20 / 21–50 / 51–100;最低档 danger、次低档 caution。
		assertThat(axis("san").bandRanges()).containsExactly(
				new BandRange(0, 20, "将失", Severity.DANGER),
				new BandRange(21, 50, "起雾", Severity.CAUTION),
				new BandRange(51, 100, "澄澈", Severity.NEUTRAL));
		// 窥天:双刃 accumulation,0/31/61 下界 → 0–30 / 31–60 / 61–100;最高档 danger、次高档 caution。
		assertThat(axis("knowledge").bandRanges()).containsExactly(
				new BandRange(0, 30, "未觉", Severity.NEUTRAL),
				new BandRange(31, 60, "见格", Severity.CAUTION),
				new BandRange(61, 100, "知渔", Severity.DANGER));
	}

	@Test
	void qingmingAndKuitianCarryOverrideHintsWhileHostAxesStayVerbatim() throws IOException {
		AttributeAxis qingming = axis("san");
		AttributeAxis kuitian = axis("knowledge");

		// 清明:draft-2 §1 原文,只把「误信饵文」改为「误信旧说」(ADR-035 决策 1)。
		String draftLine = readRepoFile("docs/fusion-yanqi-drafts/draft-2.md").lines()
				.filter(l -> l.startsWith("**行为提示**:以下情形清明下降"))
				.findFirst().orElseThrow();
		String expected = draftLine.substring("**行为提示**:".length()).replace("误信饵文", "误信旧说");
		assertThat(qingming.behaviorHint()).isEqualTo(expected);
		assertThat(qingming.behaviorHint()).isNotEqualTo(cthulhuAxis("san").behaviorHint());

		// 窥天:override 整段替换克苏鲁原 hint,必须写回联动 + 取值约定,并加「只随调查动作上涨」。
		assertThat(kuitian.behaviorHint()).isNotEqualTo(cthulhuAxis("knowledge").behaviorHint());
		assertThat(kuitian.behaviorHint())
				.contains("窥天越高,本回合清明流失就应越快")
				.contains("较低的正基线").contains("绝不给 0").contains("绝不降到 0")
				.contains("拓印、对账、验丹、观星、识破旧说").contains("其余回合只持平");

		// 气血 / 灵力 / 境界:修仙单体原样(含 behaviorHint,「恢复即饲料」不写进境界)。
		List<AttributeAxis> fused = registry.fusedAxes("cultivation", "cthulhu");
		List<AttributeAxis> single = registry.meta("cultivation").attributes();
		for (int i = 0; i < 3; i++) {
			assertThat(fused.get(i)).as("修仙原样轴 %s", single.get(i).key()).isEqualTo(single.get(i));
			assertThat(fused.get(i).behaviorHint()).isEqualTo(single.get(i).behaviorHint());
		}
	}

	@Test
	void lowLevelTextsStayWithinRevealLadder() {
		// 「将失」档护栏:危险是让人觉得舒服,而不是夺走选择。
		assertThat(axis("san").resolveBand(10).narrationHint()).contains("不要把低清明写成玩家失去选择权");

		AttributeAxis kuitian = axis("knowledge");
		String weijue = kuitian.resolveBand(0).narrationHint();
		String jiange = kuitian.resolveBand(31).narrationHint();
		String zhiyu = kuitian.resolveBand(61).narrationHint();
		// 各档写明本档可用的词汇上限。
		assertThat(weijue).contains("只写现象和印文原字");
		assertThat(jiange).contains("网").contains("格").contains("收").contains("挑拣").contains("成色");
		assertThat(zhiyu).contains("捕捞").contains("出栏").contains("育肥").contains("排酸").contains("饵");
		// 真相随档放行(Slice C):见格另带结构说明、知渔另带真相说明;未觉不带。
		assertThat(jiange).contains("给你的结构说明").contains("仍不说用途");
		assertThat(zhiyu).contains("给你的真相说明").contains("飞升是被捞走");
		assertThat(weijue).doesNotContain("说明：");

		// 可能在低窥天时注入的文本:清明 behaviorHint / 清明三档 / 窥天 behaviorHint / 未觉 / 见格
		// —— 用正面写法表达放行范围,不出现第三级词汇(列举禁词 = 把它注入提示词)。
		List<String> injectableAtLowLevel = new java.util.ArrayList<>(List.of(
				axis("san").behaviorHint(), kuitian.behaviorHint(), weijue, jiange));
		axis("san").bands().forEach(b -> injectableAtLowLevel.add(b.narrationHint()));
		for (String text : injectableAtLowLevel) {
			assertThat(text).as("第三级词汇不入低档文本:%s", text).doesNotContain(LEVEL_THREE);
		}
		// 未觉档 + 每回合都注入的文本(behaviorHint / 清明档)也不出现第二级词汇。
		injectableAtLowLevel.remove(jiange);
		for (String text : injectableAtLowLevel) {
			assertThat(text).as("第二级词汇不入第一级文本:%s", text).doesNotContain(LEVEL_TWO);
		}
	}

	private AttributeAxis axis(String key) {
		return registry.fusedAxes("cultivation", "cthulhu").stream()
				.filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}

	private AttributeAxis cthulhuAxis(String key) {
		return registry.meta("cthulhu").attributes().stream()
				.filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}

	/** 从 server 模块向上定位仓库根文件(surefire CWD=server 模块目录)。 */
	private static String readRepoFile(String relative) throws IOException {
		for (Path p : new Path[] { Path.of("..").resolve(relative), Path.of(relative) }) {
			if (Files.exists(p)) {
				return Files.readString(p);
			}
		}
		throw new IOException("找不到 " + relative + "(CWD=" + Path.of(".").toAbsolutePath() + ")");
	}
}
