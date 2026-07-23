package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.AttributeAxis.Band;
import com.aiuniverse.server.archetype.AttributeAxis.BandRange;
import com.aiuniverse.server.archetype.AttributeAxis.Severity;

/**
 * ADR-018 刀 0 · severity 派生(<b>语义产出方原则</b>:服务端派生、前端只渲染)。四分支——
 * 非致命 depletion 全 neutral / 致命 depletion 最低档 danger + 次低 caution /
 * accumulation 且 perilAtHigh 最高档 danger + 次高 caution / 其余 accumulation 全 neutral。
 *
 * <p>关键护栏:<b>不按 bands 数组下标取「第二低/第二高」</b>——registry 里 depletion 的档是降序书写的,
 * 派生必须先按 min 排序再标边缘档(本测试用乱序书写的档表钉死这一点)。
 */
class AttributeAxisSeverityTest {

	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	// ── 真实 registry 数据上的三条验收(①②③)────────────────────────────────

	@Test
	void realmPureGrowthAccumulationIsAllNeutralAtHigh() {
		// ① 境界:accumulation 且 perilAtHigh=false(纯成长)→ 全档 neutral,高位不是危险。
		AttributeAxis realm = axisOf("cultivation", "realm");
		assertThat(realm.perilAtHigh()).isFalse();
		assertThat(severities(realm)).containsExactly(Severity.NEUTRAL, Severity.NEUTRAL, Severity.NEUTRAL);
		assertThat(severityAt(realm, 95)).as("境界高位 = 修为高深,不是危险").isEqualTo(Severity.NEUTRAL);
	}

	@Test
	void knowledgeDoubleEdgedAccumulationIsDangerAtHigh() {
		// ② 禁忌知识:accumulation 且 perilAtHigh=true(双刃)→ 最高档 danger、次高 caution、底档 neutral。
		AttributeAxis knowledge = axisOf("cthulhu", "knowledge");
		assertThat(knowledge.perilAtHigh()).isTrue();
		assertThat(knowledge.isLethal()).as("双刃标与 lethal 无关:引擎侧仍非致命(≤0 不死)").isFalse();
		assertThat(severities(knowledge)).containsExactly(Severity.NEUTRAL, Severity.CAUTION, Severity.DANGER);
		assertThat(severityAt(knowledge, 80)).as("深陷 = 越接近疯狂").isEqualTo(Severity.DANGER);
		assertThat(severityAt(knowledge, 45)).isEqualTo(Severity.CAUTION);
		assertThat(severityAt(knowledge, 5)).as("蒙昧 = 累积轴的安全起点").isEqualTo(Severity.NEUTRAL);
	}

	@Test
	void lethalDepletionIsDangerAtLow() {
		// ③ 气血:致命 depletion → 最低档 danger、次低 caution、顶档 neutral。
		AttributeAxis hp = axisOf("cultivation", "hp");
		assertThat(hp.isLethal()).isTrue();
		assertThat(severities(hp)).containsExactly(Severity.DANGER, Severity.CAUTION, Severity.NEUTRAL);
		assertThat(severityAt(hp, 8)).as("气血枯竭 = 命悬一线").isEqualTo(Severity.DANGER);
		assertThat(severityAt(hp, 35)).isEqualTo(Severity.CAUTION);
		assertThat(severityAt(hp, 90)).isEqualTo(Severity.NEUTRAL);
	}

	@Test
	void nonLethalDepletionIsAllNeutral() {
		// 灵力:depletion 但 lethal=false(枯竭=力竭非必死,F-015)→ 全 neutral,低位不染危险。
		AttributeAxis mana = axisOf("cultivation", "mana");
		assertThat(mana.isLethal()).isFalse();
		assertThat(severities(mana)).containsExactly(Severity.NEUTRAL, Severity.NEUTRAL, Severity.NEUTRAL);
		assertThat(severityAt(mana, 3)).as("灵力枯竭只是施不出法术").isEqualTo(Severity.NEUTRAL);
	}

	// ── 派生实现的护栏(不看下标、看排序后的边缘)────────────────────────────

	@Test
	void severityFollowsSortedOrderNotDeclarationOrder() {
		// 同一组档、三种书写顺序(降序 / 升序 / 乱序)→ 派生结果必须逐字相同。
		Band top = new Band(100, "充沛", "h1");
		Band mid = new Band(50, "受创", "h2");
		Band low = new Band(20, "濒危", "h3");
		List<BandRange> descending = AttributeAxis.stable("hp", "体力").withBands(top, mid, low).bandRanges();
		List<BandRange> ascending = AttributeAxis.stable("hp", "体力").withBands(low, mid, top).bandRanges();
		List<BandRange> shuffled = AttributeAxis.stable("hp", "体力").withBands(mid, top, low).bandRanges();

		assertThat(ascending).isEqualTo(descending);
		assertThat(shuffled).isEqualTo(descending);
		// 危险的是「值最低的那一档」,不是「数组第 0 个」。
		assertThat(descending.get(0).label()).isEqualTo("濒危");
		assertThat(descending.get(0).severity()).isEqualTo(Severity.DANGER);
	}

	@Test
	void degenerateBandCountsMarkEdgeOnlyWithoutError() {
		// 档数 <2:只标边缘档、不报错(退化情形合法)。
		List<BandRange> single = AttributeAxis.stable("hp", "体力")
				.withBands(new Band(100, "唯一档", "h")).bandRanges();
		assertThat(single).hasSize(1);
		assertThat(single.get(0).severity()).isEqualTo(Severity.DANGER);

		List<BandRange> two = AttributeAxis.stable("hp", "体力")
				.withBands(new Band(100, "尚可", "h1"), new Band(30, "危殆", "h2")).bandRanges();
		assertThat(two.stream().map(BandRange::severity)).containsExactly(Severity.DANGER, Severity.CAUTION);

		// 双刃累积轴的退化对称形态:边缘在顶端。
		List<BandRange> twoUp = AttributeAxis.doubleEdged("knowledge", "禁忌知识", "h")
				.withBands(new Band(0, "蒙昧", "h1"), new Band(60, "深陷", "h2")).bandRanges();
		assertThat(twoUp.stream().map(BandRange::severity)).containsExactly(Severity.CAUTION, Severity.DANGER);
	}

	@Test
	void noBandsYieldsNoRanges() {
		// 无档轴 → 空表(前端据此只显数字、不进任何危险态)。
		assertThat(AttributeAxis.stable("hp", "体力").bandRanges()).isEmpty();
	}

	// ── 全 active 轴的整表不变量(异常档在后端直接失败,不下发让前端猜)────────────

	@Test
	void everyActiveAxisHasAtMostOneDangerAndOneCaution() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			for (AttributeAxis a : m.attributes()) {
				List<BandRange> ranges = a.bandRanges();
				if (ranges.isEmpty()) {
					continue;
				}
				assertThat(ranges.stream().filter(r -> r.severity() == Severity.DANGER).count())
						.as("%s/%s 至多一个 danger", m.id(), a.key()).isLessThanOrEqualTo(1);
				assertThat(ranges.stream().filter(r -> r.severity() == Severity.CAUTION).count())
						.as("%s/%s 至多一个 caution", m.id(), a.key()).isLessThanOrEqualTo(1);
				assertThat(ranges).allSatisfy(r -> assertThat(r.severity()).isNotNull());
				// 危险档必在边缘:致命 depletion 在表头、双刃 accumulation 在表尾。
				int danger = indexOf(ranges, Severity.DANGER);
				if (danger >= 0) {
					assertThat(danger).as("%s/%s danger 必在边缘", m.id(), a.key())
							.isIn(0, ranges.size() - 1);
				}
			}
		}
	}

	@Test
	void wireValuesAreLowercase() {
		assertThat(Severity.NEUTRAL.wire()).isEqualTo("neutral");
		assertThat(Severity.CAUTION.wire()).isEqualTo("caution");
		assertThat(Severity.DANGER.wire()).isEqualTo("danger");
	}

	// ── helpers ────────────────────────────────────────────────────────

	private static int indexOf(List<BandRange> ranges, Severity s) {
		for (int i = 0; i < ranges.size(); i++) {
			if (ranges.get(i).severity() == s) {
				return i;
			}
		}
		return -1;
	}

	private static List<Severity> severities(AttributeAxis axis) {
		return axis.bandRanges().stream().map(BandRange::severity).toList();
	}

	/** 前端的解析路径同构:按区间匹配当前值 → 取该档 severity。 */
	private static Severity severityAt(AttributeAxis axis, int value) {
		return axis.bandRanges().stream().filter(r -> value >= r.min() && value <= r.max())
				.map(BandRange::severity).findFirst().orElseThrow();
	}

	private AttributeAxis axisOf(String archetype, String key) {
		return registry.meta(archetype).attributes().stream()
				.filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}
}
