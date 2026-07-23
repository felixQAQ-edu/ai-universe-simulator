package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.AttributeAxis.AxisRole;
import com.aiuniverse.server.archetype.AttributeAxis.Band;
import com.aiuniverse.server.archetype.AttributeAxis.Severity;

/**
 * 行为档 + resolveBand 纯函数(#3 数值行为化 Slice A)——良构校验、axisRole 感知的边界归属、
 * 全 active 轴的档表良构。<b>只染叙事、不 gate 选项</b>:本测试只验「值→当前档」,不涉任何选项/引擎逻辑。
 *
 * <p>阈值约定(Felix 2026-06-30 签字):depletion 切 50/20(threshold=上界 inclusive)、
 * accumulation 切 30/60(threshold=下界 inclusive)。
 */
class AttributeAxisBandTest {

	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	// ── resolveBand:depletion(值越低进越危的档,threshold=上界,取 ≥v 的最小 threshold)─────────

	@Test
	void depletionResolvesByDescendingValue() {
		AttributeAxis hp = AttributeAxis.stable("hp", "体力").withBands(
				new Band(100, "充沛", "h1"), new Band(50, "受创", "h2"), new Band(20, "濒危", "h3"));
		assertThat(hp.resolveBand(100).label()).isEqualTo("充沛");
		assertThat(hp.resolveBand(80).label()).isEqualTo("充沛");
		assertThat(hp.resolveBand(35).label()).isEqualTo("受创");
		assertThat(hp.resolveBand(5).label()).isEqualTo("濒危");
	}

	@Test
	void depletionBoundariesAreInclusiveUpperBound() {
		AttributeAxis hp = AttributeAxis.stable("hp", "体力").withBands(
				new Band(100, "充沛", "h1"), new Band(50, "受创", "h2"), new Band(20, "濒危", "h3"));
		// 50 归受创(上界 inclusive)、51 归充沛;20 归濒危、21 归受创。
		assertThat(hp.resolveBand(51).label()).isEqualTo("充沛");
		assertThat(hp.resolveBand(50).label()).isEqualTo("受创");
		assertThat(hp.resolveBand(21).label()).isEqualTo("受创");
		assertThat(hp.resolveBand(20).label()).isEqualTo("濒危");
	}

	@Test
	void depletionClampsBelowDomainToBottomBand() {
		AttributeAxis hp = AttributeAxis.stable("hp", "体力").withBands(
				new Band(100, "充沛", "h1"), new Band(50, "受创", "h2"), new Band(20, "濒危", "h3"));
		// 0 / 负数(clamp 到 min) → 最危档;>max → clamp 到 100 → 顶档。
		assertThat(hp.resolveBand(0).label()).isEqualTo("濒危");
		assertThat(hp.resolveBand(-10).label()).isEqualTo("濒危");
		assertThat(hp.resolveBand(999).label()).isEqualTo("充沛");
	}

	// ── resolveBand:accumulation(值越高进越深的档,threshold=下界,取 ≤v 的最大 threshold)──────

	@Test
	void accumulationResolvesByAscendingValue() {
		AttributeAxis k = AttributeAxis.accumulating("knowledge", "禁忌知识", "h").withBands(
				new Band(0, "蒙昧", "h1"), new Band(31, "初窥", "h2"), new Band(61, "深陷", "h3"));
		assertThat(k.resolveBand(0).label()).isEqualTo("蒙昧");
		assertThat(k.resolveBand(15).label()).isEqualTo("蒙昧");
		assertThat(k.resolveBand(45).label()).isEqualTo("初窥");
		assertThat(k.resolveBand(90).label()).isEqualTo("深陷");
	}

	@Test
	void accumulationBoundariesAreInclusiveLowerBound() {
		AttributeAxis k = AttributeAxis.accumulating("knowledge", "禁忌知识", "h").withBands(
				new Band(0, "蒙昧", "h1"), new Band(31, "初窥", "h2"), new Band(61, "深陷", "h3"));
		// 30 归蒙昧、31 归初窥;60 归初窥、61 归深陷。
		assertThat(k.resolveBand(30).label()).isEqualTo("蒙昧");
		assertThat(k.resolveBand(31).label()).isEqualTo("初窥");
		assertThat(k.resolveBand(60).label()).isEqualTo("初窥");
		assertThat(k.resolveBand(61).label()).isEqualTo("深陷");
		assertThat(k.resolveBand(100).label()).isEqualTo("深陷");
	}

	@Test
	void noBandsResolvesToNull() {
		assertThat(AttributeAxis.stable("hp", "体力").resolveBand(50)).isNull();
	}

	// ── bandRanges:下发前端的显式区间投影(axisRole 无关、连续覆盖全域)──────────────

	@Test
	void depletionBandRangesAreContiguousAscending() {
		var ranges = AttributeAxis.stable("hp", "体力").withBands(
				new Band(100, "充沛", "h1"), new Band(50, "受创", "h2"), new Band(20, "濒危", "h3")).bandRanges();
		assertThat(ranges.stream().map(AttributeAxis.BandRange::label)).containsExactly("濒危", "受创", "充沛");
		// severity 随区间同趟派生(ADR-018):致命 depletion → 最低档 danger、次低 caution、其余 neutral。
		assertThat(ranges.get(0)).isEqualTo(new AttributeAxis.BandRange(0, 20, "濒危", Severity.DANGER));
		assertThat(ranges.get(1)).isEqualTo(new AttributeAxis.BandRange(21, 50, "受创", Severity.CAUTION));
		assertThat(ranges.get(2)).isEqualTo(new AttributeAxis.BandRange(51, 100, "充沛", Severity.NEUTRAL));
	}

	@Test
	void accumulationBandRangesAreContiguousAscending() {
		var ranges = AttributeAxis.accumulating("knowledge", "禁忌知识", "h").withBands(
				new Band(0, "蒙昧", "h1"), new Band(31, "初窥", "h2"), new Band(61, "深陷", "h3")).bandRanges();
		// accumulating(纯成长,perilAtHigh=false)→ 全 neutral;双刃变体见 AttributeAxisSeverityTest。
		assertThat(ranges.get(0)).isEqualTo(new AttributeAxis.BandRange(0, 30, "蒙昧", Severity.NEUTRAL));
		assertThat(ranges.get(1)).isEqualTo(new AttributeAxis.BandRange(31, 60, "初窥", Severity.NEUTRAL));
		assertThat(ranges.get(2)).isEqualTo(new AttributeAxis.BandRange(61, 100, "深陷", Severity.NEUTRAL));
	}

	@Test
	void bandRangesCoverWholeDomainWithoutGapOrOverlap() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			for (AttributeAxis a : m.attributes()) {
				var ranges = a.bandRanges();
				if (ranges.isEmpty()) {
					continue;
				}
				assertThat(ranges.get(0).min()).as("%s/%s 起于 min", m.id(), a.key()).isEqualTo(a.min());
				assertThat(ranges.get(ranges.size() - 1).max()).as("%s/%s 止于 max", m.id(), a.key()).isEqualTo(a.max());
				for (int i = 1; i < ranges.size(); i++) {
					assertThat(ranges.get(i).min()).as("%s/%s 区间无缝衔接", m.id(), a.key())
							.isEqualTo(ranges.get(i - 1).max() + 1);
				}
			}
		}
	}

	@Test
	void noBandsRangesEmpty() {
		assertThat(AttributeAxis.stable("hp", "体力").bandRanges()).isEmpty();
	}

	// ── 良构校验(构造时即拦)──────────────────────────────────────────

	@Test
	void blankLabelRejected() {
		assertThatThrownBy(() -> AttributeAxis.stable("hp", "体力").withBands(
				new Band(100, "充沛", "h1"), new Band(50, "  ", "h2"), new Band(20, "濒危", "h3")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("label");
	}

	@Test
	void duplicateThresholdRejected() {
		assertThatThrownBy(() -> AttributeAxis.stable("hp", "体力").withBands(
				new Band(100, "充沛", "h1"), new Band(50, "受创", "h2"), new Band(50, "濒危", "h3")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
	}

	@Test
	void thresholdOutOfRangeRejected() {
		assertThatThrownBy(() -> AttributeAxis.stable("hp", "体力").withBands(
				new Band(120, "超界", "h1"), new Band(50, "受创", "h2"), new Band(20, "濒危", "h3")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("越界");
	}

	@Test
	void depletionWithoutTopCoverageRejected() {
		// depletion 缺 threshold==max(100) → 顶档未覆盖(v=100 会 null)→ 构造拒绝。
		assertThatThrownBy(() -> AttributeAxis.stable("hp", "体力").withBands(
				new Band(80, "充沛", "h1"), new Band(50, "受创", "h2"), new Band(20, "濒危", "h3")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("顶档");
	}

	@Test
	void accumulationWithoutBottomCoverageRejected() {
		// accumulation 缺 threshold==min(0) → 底档未覆盖(v=0 会 null)→ 构造拒绝。
		assertThatThrownBy(() -> AttributeAxis.accumulating("knowledge", "禁忌知识", "h").withBands(
				new Band(10, "蒙昧", "h1"), new Band(31, "初窥", "h2"), new Band(61, "深陷", "h3")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("底档");
	}

	// ── 全 active 轴档表良构(registry 草案落地后,任一轴 0–100 每点都能解析到非空档)──────────

	@Test
	void everyActiveAxisWithBandsIsWellFormedAndTotalOverDomain() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			for (AttributeAxis a : m.attributes()) {
				if (a.bands().isEmpty()) {
					continue; // 无档轴(本批四世界目前各轴都配了档,但允许将来有空档轴)
				}
				assertThat(a.bands()).as("%s/%s 三档", m.id(), a.key()).hasSize(3);
				for (int v = a.min(); v <= a.max(); v++) {
					Band b = a.resolveBand(v);
					assertThat(b).as("%s/%s value=%d 必有档", m.id(), a.key(), v).isNotNull();
					assertThat(b.label()).isNotBlank();
					assertThat(b.narrationHint()).as("%s/%s 档带叙事提示(喂 prompt)", m.id(), a.key()).isNotBlank();
				}
			}
		}
	}

	@Test
	void cthulhuKnowledgeIsAccumulationBandedManaIsResource() {
		// 抽样:克苏鲁 knowledge=accumulation 档(升序)、修仙灵力=非致命 depletion 档(降序),
		// 验 axisRole 感知在真实 registry 数据上成立。
		AttributeAxis knowledge = axisOf("cthulhu", "knowledge");
		assertThat(knowledge.axisRole()).isEqualTo(AxisRole.ACCUMULATION);
		assertThat(knowledge.resolveBand(5).label()).isEqualTo("蒙昧");
		assertThat(knowledge.resolveBand(80).label()).isEqualTo("深陷");

		AttributeAxis mana = axisOf("cultivation", "mana");
		assertThat(mana.axisRole()).isEqualTo(AxisRole.DEPLETION);
		assertThat(mana.isLethal()).as("灵力非致命,但仍有行为档").isFalse();
		assertThat(mana.resolveBand(80).label()).isEqualTo("灵力充裕");
		assertThat(mana.resolveBand(5).label()).isEqualTo("灵力枯竭");
	}

	private AttributeAxis axisOf(String archetype, String key) {
		return registry.meta(archetype).attributes().stream()
				.filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}
}
