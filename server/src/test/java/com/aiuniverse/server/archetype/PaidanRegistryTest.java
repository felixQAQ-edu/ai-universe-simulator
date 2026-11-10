package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.AttributeAxis.BandRange;
import com.aiuniverse.server.archetype.AttributeAxis.Severity;

/**
 * ADR-036 Slice B ·《派单》registry 激活(决策 1 / 3)。钉:卡面三条文案与固定世界名、四轴顺序与角色、
 * 派生集(致命 {hp, rating}、累积 {profile}、非致命 {cash})、画像 perilAtHigh、四轴 bandRanges(档界、档名、severity)、
 * 措辞(进提示词的文字只用「跑腿员」,不出现决策 9 的禁用词)。
 */
class PaidanRegistryTest {

	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final ArchetypeMeta m = registry.meta("cyberpunk");

	@Test
	void cardCopyAndFixedWorldTitle() {
		assertThat(registry.isKnown("cyberpunk")).isTrue();
		assertThat(registry.isActive("cyberpunk")).isTrue();
		assertThat(m.displayName()).isEqualTo("赛博朋克");
		assertThat(m.tagline()).isEqualTo("你每快一次,它就记住一次。");
		assertThat(m.vibeTag()).isEqualTo("城中村 · 高压");
		assertThat(m.fixedWorldTitle()).isEqualTo("派单");
		assertThat(m.rulesCarryTruth()).isTrue();
		assertThat(m.worldview()).startsWith("《派单》——").contains("跑腿员").contains("已为你优化时限。");
		assertThat(m.ruleForm()).contains("【服务守则】").contains("【群里说】").contains("hiddenLogic");
	}

	@Test
	void onlyPaidanRegistersAFixedWorldTitle() {
		for (ArchetypeMeta meta : registry.activeMetas()) {
			if (!meta.id().equals("cyberpunk")) {
				assertThat(meta.fixedWorldTitle()).as(meta.id()).isNull();
			}
		}
	}

	@Test
	void fourAxesInOrderWithDerivedSets() {
		List<AttributeAxis> axes = m.attributes();
		assertThat(axes).extracting(AttributeAxis::key).containsExactly("hp", "cash", "rating", "profile");
		assertThat(axes).extracting(AttributeAxis::displayName).containsExactly("体力", "余钱", "评分", "画像");
		assertThat(axes).allSatisfy(a -> {
			assertThat(a.min()).isZero();
			assertThat(a.max()).isEqualTo(100);
			assertThat(a.behaviorHint()).isNotBlank();
		});

		List<AttributeAxis> resolved = registry.resolveAxes(List.of("cyberpunk"));
		assertThat(resolved).isEqualTo(axes);
		assertThat(ArchetypeRegistry.lethalKeys(axes)).isEqualTo(Set.of("hp", "rating"));
		assertThat(ArchetypeRegistry.accumulationKeys(axes)).isEqualTo(Set.of("profile"));
		assertThat(ArchetypeRegistry.nonLethalKeys(axes)).isEqualTo(Set.of("cash"));
		assertThat(ArchetypeRegistry.axisDisplayNames(axes))
				.containsEntry("rating", "评分").containsEntry("cash", "余钱");
	}

	@Test
	void profileIsPerilAtHighAndOnlyItIs() {
		assertThat(axis("profile").isAccumulation()).isTrue();
		assertThat(axis("profile").isLethal()).isFalse();
		assertThat(axis("profile").perilAtHigh()).isTrue();
		for (String key : List.of("hp", "cash", "rating")) {
			assertThat(axis(key).isAccumulation()).as(key).isFalse();
			assertThat(axis(key).perilAtHigh()).as(key).isFalse();
		}
	}

	@Test
	void bandRangesForAllFourAxes() {
		// 体力 / 评分:致命 depletion,50 / 20,最低档 danger、次低 caution。
		assertThat(axis("hp").bandRanges()).containsExactly(
				new BandRange(0, 20, "见底", Severity.DANGER),
				new BandRange(21, 50, "透支", Severity.CAUTION),
				new BandRange(51, 100, "撑得住", Severity.NEUTRAL));
		assertThat(axis("rating").bandRanges()).containsExactly(
				new BandRange(0, 20, "观察", Severity.DANGER),
				new BandRange(21, 50, "普通", Severity.CAUTION),
				new BandRange(51, 100, "优选", Severity.NEUTRAL));
		// 余钱:非致命资源轴,50 / 20,severity 全 neutral。
		assertThat(axis("cash").bandRanges()).containsExactly(
				new BandRange(0, 20, "空", Severity.NEUTRAL),
				new BandRange(21, 50, "紧", Severity.NEUTRAL),
				new BandRange(51, 100, "宽裕", Severity.NEUTRAL));
		// 画像:累积型 perilAtHigh,30 / 60(下界 inclusive),最高档 danger、次高 caution。
		assertThat(axis("profile").bandRanges()).containsExactly(
				new BandRange(0, 29, "生手", Severity.NEUTRAL),
				new BandRange(30, 59, "熟手", Severity.CAUTION),
				new BandRange(60, 100, "标杆", Severity.DANGER));
	}

	@Test
	void narrationHintsMatchAdrDecision3() {
		assertThat(axis("hp").resolveBand(10).narrationHint()).isEqualTo("手抖得握不住车把,看路开始重影");
		assertThat(axis("cash").resolveBand(35).narrationHint()).isEqualTo("每花一笔都先算一遍交租那天");
		assertThat(axis("rating").resolveBand(80).narrationHint()).isEqualTo("近单好单先推给你");
		assertThat(axis("profile").resolveBand(60).narrationHint()).isEqualTo("时限只够一路不停,一个红灯都等不起");
		assertThat(axis("cash").behaviorHint()).contains("80 = 够交房租和这期义体月供,80 以上是交完还剩的");
		assertThat(axis("rating").behaviorHint()).contains("归零");
	}

	/** ADR-036「订正(2026-11-10):赛博朋克底色」:身体是分期的高科技;头盔 / 助力膝 / 改装头盔退场。 */
	@Test
	void cyberBackdropCorrection() {
		assertThat(m.worldview()).contains("一双机械腿是平台入职方案装的义体,按月分期")
				.contains("订单、倒计时和路线投在义眼里,派单音直接响在脑子里")
				.contains("塔楼和空中连廊悬在头顶,城中村挤在下面").contains("门上浮着这户的评分,巷口浮着路况")
				.contains("招牌上混着几种文字").contains("送货机器人在垃圾和电线之间穿行").contains("雨、雾、蒸汽几乎不停")
				.contains("余钱不够交这期月供时,腿会被远程降速").contains("把神经接口的算力租给平台过夜")
				.contains("黑诊所").contains("眼前浮出一句「已为你优化时限。」")
				.doesNotContain("具体而克制").doesNotContain("头盔").doesNotContain("助力膝");
		assertThat(axis("profile").behaviorHint()).contains("开启腿的超频").contains("刷机").doesNotContain("联网义体")
				.doesNotContain("改装头盔");
		assertThat(axis("rating").behaviorHint()).contains("刷机被查到时下降").doesNotContain("改装头盔");
		assertThat(axis("cash").behaviorHint()).contains("出租神经接口的算力").contains("远程降速")
				.contains("月供在第三夜收工时和房租一起结算,夜班途中不单独扣。")
				.doesNotContain("租义体").doesNotContain("交月供");
		assertThat(axis("hp").behaviorHint()).contains("腿被降速时下降得更快").contains("回不满");
		assertThat(m.ruleForm()).contains("刷机").doesNotContain("改装头盔");
	}

	@Test
	void promptBoundTextAvoidsBannedWords() {
		// 进提示词的文字(worldview / ruleForm / 行为提示 / 档文字)只用「跑腿员」,不出现决策 9 的禁用词。
		// 卡面 displayName「赛博朋克」是裁定允许的模式标签,不在此列。
		StringBuilder text = new StringBuilder(m.worldview()).append(m.ruleForm()).append(m.tagline())
				.append(m.vibeTag());
		for (AttributeAxis a : m.attributes()) {
			text.append(a.behaviorHint());
			a.bands().forEach(b -> text.append(b.label()).append(b.narrationHint()));
		}
		for (String banned : List.of("赛博", "反乌托邦", "巨型企业", "资本", "剥削", "打工人", "骑手", "众包", "%",
				"罢单", "维权", "讨薪", "拆迁", "清退", "整治")) {
			assertThat(text.toString()).as(banned).doesNotContain(banned);
		}
	}

	private AttributeAxis axis(String key) {
		return m.attributes().stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}
}
