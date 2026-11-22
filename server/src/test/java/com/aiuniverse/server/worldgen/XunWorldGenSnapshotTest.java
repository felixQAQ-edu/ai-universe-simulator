package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

/**
 * ADR-044 待审 7(《寻常》b)· 守《寻常》(life_sim)的 world-gen 提示词除末尾随机种子行外逐字节不变。
 *
 * <p><b>为什么另起</b>:《寻常》原在 {@link PaidanWorldGenTest} ①「既有世界逐字节不变」那组里(快照取自 {@code 3e72099},
 * 比对前经 ADR-037 一层锚定剔除)。ADR-044 有意改动它的 world-gen 槽(槽末【condition 一律用中文轴名】之前加【小标题】),
 * 留在那组里只能再加一层剔除;按待审 7 移出,在这里另立一张快照(照 {@code PaidanPromptSnapshotTest} 先例)。
 *
 * <p><b>快照出处</b>:ADR-044 刀 1 的实现 commit,{@code buildWorldPrompt("life_sim")} 截到最后一个「场景种子:」之前。
 * 录制时核过:与旧快照 {@code golden/world-gen-prompts/life_sim.txt} 相比,差异恰好是 ADR-037 的 tone 行与【现实边界】块
 * (旧快照比对前就剔掉的那两处)加上这一条【小标题】,别无其他。
 * <b>快照怎么更新</b>:只在有意改动《寻常》world-gen 提示词时重录,并在这里记一笔。
 */
class XunWorldGenSnapshotTest {

	private static final String SEED_MARK = "场景种子:";

	private final WorldGenPromptBuilder builder = new WorldGenPromptBuilder(new ArchetypeRegistry());

	@Test
	void xunWorldGenPromptIsByteIdenticalExceptTheSeedLine() throws IOException {
		String expected = snapshot("world-gen.txt");
		for (int i = 0; i < 5; i++) { // 种子随机:多抽几次,前缀都不变
			String p = builder.buildWorldPrompt("life_sim");
			int cut = p.lastIndexOf(SEED_MARK);
			assertThat(p.substring(0, cut)).isEqualTo(expected);
			assertThat(p.substring(cut + SEED_MARK.length())).as("种子行").isNotBlank().doesNotContain("\n");
		}
	}

	@Test
	void subtitleDirectiveSitsRightBeforeTheConditionLine() {
		String p = builder.buildWorldPrompt("life_sim");
		int sub = p.indexOf("- 【小标题】world.subtitle 给这一辈子起一个名字");
		int cond = p.indexOf("- 【condition 一律用中文轴名】气力 / 热望");
		assertThat(sub).isPositive();
		assertThat(p.indexOf("- 【小标题】", sub + 1)).as("只出现一次").isEqualTo(-1);
		// 紧挨着:小标题那一行的下一行就是 condition 那一行。
		assertThat(p.substring(sub, cond)).doesNotContain("\n- ").endsWith("\n");
	}

	private String snapshot(String name) throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/golden/life_sim/" + name)) {
			assertThat(in).as("快照缺失:" + name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
