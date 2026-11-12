package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.AttributeAxis.BandRange;
import com.aiuniverse.server.archetype.AttributeAxis.Severity;

/**
 * ADR-038 Slice A ·《七日开张》registry 激活(决策 1 / 3)。钉:卡面文案与固定世界名(Felix 2026-11-12 定稿,逐字)、
 * 三轴顺序与角色、派生集(致命 {cover}、累积 {regulars}、非致命 {capital})、九个档 label(定稿,逐字)与 narrationHint、
 * 档界按 {@code resolveBand} 落成的实际区间、behaviorHint 里写死的数字、进提示词的文字不出现「古代」。
 */
class QiriRegistryTest {

	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final ArchetypeMeta m = registry.meta("time_travel");

	@Test
	void cardCopyAndFixedWorldTitleVerbatim() {
		assertThat(registry.isKnown("time_travel")).isTrue();
		assertThat(registry.isActive("time_travel")).isTrue();
		assertThat(m.displayName()).isEqualTo("穿越古代");
		// 文案清单定稿(Felix 2026-11-12),逐字。
		assertThat(m.tagline()).isEqualTo("这条街什么都卖,就是没见过你这样卖的。");
		assertThat(m.vibeTag()).isEqualTo("市井 · 穿帮");
		assertThat(m.fixedWorldTitle()).isEqualTo("七日开张");
		assertThat(m.rulesCarryTruth()).isTrue();
		assertThat(m.worldview()).startsWith("《七日开张》——").contains("「本朝」").contains("失踪摊主的远房亲戚")
				.contains("「百味夜集」").contains("字迹缺笔画的小抄").contains("「这个时代的规矩」");
		assertThat(m.ruleForm()).contains("小抄").contains("hiddenLogic").contains("是谁编的");
	}

	@Test
	void threeAxesInOrderWithDerivedSets() {
		List<AttributeAxis> axes = m.attributes();
		assertThat(axes).extracting(AttributeAxis::key).containsExactly("capital", "regulars", "cover");
		assertThat(axes).extracting(AttributeAxis::displayName).containsExactly("摊本", "熟客", "底细");
		assertThat(axes).allSatisfy(a -> {
			assertThat(a.min()).isZero();
			assertThat(a.max()).isEqualTo(100);
			assertThat(a.behaviorHint()).isNotBlank();
			assertThat(a.perilAtHigh()).isFalse();
		});
		assertThat(registry.resolveAxes(List.of("time_travel"))).isEqualTo(axes);
		assertThat(ArchetypeRegistry.lethalKeys(axes)).isEqualTo(Set.of("cover"));
		assertThat(ArchetypeRegistry.accumulationKeys(axes)).isEqualTo(Set.of("regulars"));
		assertThat(ArchetypeRegistry.nonLethalKeys(axes)).isEqualTo(Set.of("capital"));
	}

	/** 九个档 label 逐字 = 文案清单定稿列;区间按 resolveBand 落成 51–100 / 21–50 / 0–20 与 0–29 / 30–59 / 60–100。 */
	@Test
	void nineBandLabelsVerbatimWithActualRanges() {
		assertThat(axis("cover").bandRanges()).containsExactly(
				new BandRange(0, 20, "快露馅", Severity.DANGER),
				new BandRange(21, 50, "有点怪", Severity.CAUTION),
				new BandRange(51, 100, "藏得住", Severity.NEUTRAL));
		assertThat(axis("capital").bandRanges()).containsExactly(
				new BandRange(0, 20, "靠赊账", Severity.NEUTRAL),
				new BandRange(21, 50, "紧巴巴", Severity.NEUTRAL),
				new BandRange(51, 100, "够周转", Severity.NEUTRAL));
		assertThat(axis("regulars").bandRanges()).containsExactly(
				new BandRange(0, 29, "生面孔", Severity.NEUTRAL),
				new BandRange(30, 59, "回头客", Severity.NEUTRAL),
				new BandRange(60, 100, "老主顾", Severity.NEUTRAL));
		// 边界值:50 归中档、20 归低档;30、60 是累积档的下界。
		assertThat(axis("cover").resolveBand(50).label()).isEqualTo("有点怪");
		assertThat(axis("cover").resolveBand(20).label()).isEqualTo("快露馅");
		assertThat(axis("regulars").resolveBand(30).label()).isEqualTo("回头客");
		assertThat(axis("regulars").resolveBand(60).label()).isEqualTo("老主顾");
	}

	@Test
	void narrationHintsMatchAdrDecision3() {
		assertThat(axis("cover").resolveBand(80).narrationHint()).isEqualTo("旁人觉得你莫非师从奇人");
		assertThat(axis("cover").resolveBand(35).narrationHint()).isEqualTo("找你办怪事的人和盘问来历的人同时变多");
		assertThat(axis("cover").resolveBand(10).narrationHint()).isEqualTo("有人围着看热闹");
		assertThat(axis("capital").resolveBand(80).narrationHint()).isEqualTo("进货不用掂量,还能多备一样新货");
		assertThat(axis("capital").resolveBand(35).narrationHint()).isEqualTo("进货先挑最便宜的,炭火省着烧");
		assertThat(axis("capital").resolveBand(10).narrationHint()).isEqualTo("赊着进货,邻摊开始替你记账");
		assertThat(axis("regulars").resolveBand(0).narrationHint()).isEqualTo("路过的人多,停下来的人少");
		assertThat(axis("regulars").resolveBand(45).narrationHint()).isEqualTo("有几张脸会在固定的时辰出现,问今天有什么新花样");
		assertThat(axis("regulars").resolveBand(90).narrationHint()).isEqualTo("熟客替你招呼生客,摊前常有人等");
	}

	/** CONTEXT §三.18(d):要落数值的行为都写明数字(决策 3 的表)。回合指令槽(Slice C)同源。 */
	@Test
	void behaviorHintsCarryDecision3Numbers() {
		assertThat(axis("capital").behaviorHint()).contains("早回合进货固定 −6").contains("午、晚开摊营业各 +2~+4")
				.contains("第 22 回合百味夜集按普通营业回合落 +2~+4")
				.contains("立功的那一回合 +8~+12,代替当回合的营业收入(第 22 回合照算)")
				.contains("早回合立功:进货那一笔不扣(省下 6),不另加 +8~+12")
				.contains("赔本事件 −5~−15").contains("圆回来那一回合 −3").contains("这一回合不营业")
				.contains("归零 = 赊账度日,不结束").contains("不得大幅偏离");
		assertThat(axis("regulars").behaviorHint()).contains("解决麻烦或让客人满意 +5~+8").contains("得罪熟客 −8");
		assertThat(axis("cover").behaviorHint()).contains("当众用现代招数 −6(早回合立功也照扣)").contains("说漏现代词 −4")
				.contains("被看见写阿拉伯数字 −3").contains("大翻车 −12").contains("圆回来 +6,同一回合摊本 −3")
				.contains("这一回合不营业").contains("立功的同一回合,摊本与底细同时落数");
	}

	/** 决策 8 / 已知代价 5:进提示词的文字不出现「古代」;模式名「穿越古代」只在 displayName 一处。 */
	@Test
	void promptBoundTextNeverSaysGuDai() {
		StringBuilder text = new StringBuilder(m.worldview()).append(m.ruleForm());
		for (AttributeAxis a : m.attributes()) {
			text.append(a.behaviorHint());
			a.bands().forEach(b -> text.append(b.label()).append(b.narrationHint()));
		}
		assertThat(text.toString()).doesNotContain("古代").doesNotContain("穿越古代");
	}

	private AttributeAxis axis(String key) {
		return m.attributes().stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}
}
