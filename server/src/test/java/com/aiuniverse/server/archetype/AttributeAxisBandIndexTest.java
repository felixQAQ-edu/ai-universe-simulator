package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.AttributeAxis.Band;

/**
 * ADR-040 决策 1 · {@link AttributeAxis#bandIndex}:命中档在「threshold 升序」里的位置,0 = 值域最低的那一档。
 * 与 {@link AttributeAxis#resolveBand} 同一口径(取整由调用方做),与档的书写顺序无关。
 */
class AttributeAxisBandIndexTest {

	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@Test
	void accumulationIndexFollowsTheBandTable() {
		AttributeAxis ties = registry.meta("life_sim").attributes().stream().filter(a -> a.key().equals("ties"))
				.findFirst().orElseThrow();
		// 《寻常》ties 档表 0 / 31 / 61(下界 inclusive)。
		assertThat(ties.bandIndex(0)).isZero();
		assertThat(ties.bandIndex(30)).isZero();
		assertThat(ties.bandIndex(31)).isEqualTo(1);
		assertThat(ties.bandIndex(60)).isEqualTo(1);
		assertThat(ties.bandIndex(61)).isEqualTo(2);
		assertThat(ties.bandIndex(100)).isEqualTo(2);
	}

	@Test
	void depletionIndexIsAscendingByValueToo() {
		// depletion 的档书写成降序(100 / 50 / 20,上界 inclusive),序号仍按值域升序:最低那档是 0。
		AttributeAxis hp = AttributeAxis.stable("hp", "体力").withBands(
				new Band(100, "充沛", "h1"), new Band(50, "受创", "h2"), new Band(20, "濒危", "h3"));
		assertThat(hp.bandIndex(5)).isZero();
		assertThat(hp.bandIndex(20)).isZero();
		assertThat(hp.bandIndex(21)).isEqualTo(1);
		assertThat(hp.bandIndex(50)).isEqualTo(1);
		assertThat(hp.bandIndex(51)).isEqualTo(2);
	}

	@Test
	void indexMatchesResolveBandPositionInAscendingRanges() {
		// 与下发前端的 bandRanges()(按 min 升序)同序:第 i 个区间里的值,序号就是 i。
		for (AttributeAxis a : registry.meta("life_sim").attributes()) {
			var ranges = a.bandRanges();
			for (int i = 0; i < ranges.size(); i++) {
				assertThat(a.bandIndex(ranges.get(i).min())).as(a.key() + "@" + ranges.get(i).min()).isEqualTo(i);
				assertThat(a.bandIndex(ranges.get(i).max())).as(a.key() + "@" + ranges.get(i).max()).isEqualTo(i);
			}
		}
	}

	@Test
	void noBandsGivesMinusOne() {
		assertThat(AttributeAxis.stable("hp", "体力").bandIndex(50)).isEqualTo(-1);
	}
}
