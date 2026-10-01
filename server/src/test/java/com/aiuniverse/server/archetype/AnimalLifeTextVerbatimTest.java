package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.AttributeAxis.BandRange;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

/**
 * ADR-028 刀 2b · 《动物人生》世界层文字逐字守卫:只接受「ADR 附录 → 代码」一个方向。
 * 从 ADR「附录 · 第二刀文案定稿」第二节 5/6 与第三节 7-A/7-B/7-C 抽出原文,与 registry / world-gen 比对。
 *
 * <p>多行原文在代码里按「非空行、去掉引用符 {@code > }、直接首尾相接」拼成一段(刀 2b 实现口径)。
 */
class AnimalLifeTextVerbatimTest {

	private static final Path ADR = Path.of("../docs/adr/ADR-028-box-scene-changeable-left-behind.md");
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	private static List<String> section(List<String> all, String start, String stopPrefix) {
		int i = all.indexOf(start);
		assertThat(i).as("ADR 里找不到「%s」", start).isGreaterThanOrEqualTo(0);
		List<String> out = new ArrayList<>();
		for (int k = i + 1; k < all.size() && !all.get(k).startsWith(stopPrefix); k++) {
			String x = all.get(k).strip();
			if (x.startsWith(">")) {
				x = x.substring(1).strip();
			}
			if (!x.isEmpty() && !x.equals("---")) {
				out.add(x);
			}
		}
		return out;
	}

	private static List<String> adr() throws Exception {
		return Files.readAllLines(ADR);
	}

	private AttributeAxis close() {
		return registry.meta("animal_life").attributes().stream().filter(a -> a.key().equals("close"))
				.findFirst().orElseThrow();
	}

	@Test
	void worldviewAndTaglineAreAppendixItems5And6() throws Exception {
		List<String> all = adr();
		ArchetypeMeta m = registry.meta("animal_life");
		assertThat(m.worldview()).isEqualTo(String.join("", section(all, "#### 5．《动物人生》世界背景", "####")));
		assertThat(List.of(m.tagline())).isEqualTo(section(all, "#### 6．选择屏一句话", "###"));
		assertThat(m.tagline()).isEqualTo("熟悉的一切变了，你学会的规则还作数吗？");
	}

	@Test
	void ruleFormIs7B() throws Exception {
		List<String> b = section(adr(), "#### 7-B．世界生成侧：`rules` 形态说明", "####");
		assertThat(b.get(0)).isEqualTo("用以下内容替换现有说明：");
		assertThat(registry.meta("animal_life").ruleForm()).isEqualTo(String.join("", b.subList(1, b.size())));
	}

	@Test
	void closeHintAndBandsAre7C() throws Exception {
		List<String> c = section(adr(), "#### 7-C．【近人】轴说明与档位", "####");
		int k = c.indexOf("档位：");
		List<String> hint = new ArrayList<>(c.subList(1, k));
		List<String> rest = c.subList(k + 1, c.size());
		List<String> bandLines = rest.stream().filter(x -> x.startsWith("- `")).toList();
		hint.addAll(rest.stream().filter(x -> !x.startsWith("- `")).toList());
		assertThat(close().behaviorHint()).isEqualTo(String.join("", hint))
				.endsWith("“那扇门不会再打开”只指旧家的门，不得用于新家的门。");
		List<BandRange> ranges = close().bandRanges();
		assertThat(ranges).hasSize(3);
		List<AttributeAxis.Band> bands = close().bands().stream()
				.sorted(java.util.Comparator.comparingInt(AttributeAxis.Band::threshold)).toList();
		for (int i = 0; i < 3; i++) {
			AttributeAxis.Band b = bands.get(i);
			assertThat(bandLines.get(i)).isEqualTo("- `" + b.threshold() + "　" + b.label() + "`——" + b.narrationHint());
		}
	}

	@Test
	void worldGenBlockIs7A_withTheFamilyLineInTodaysForm() throws Exception {
		List<String> all = adr();
		int i = all.indexOf("##### 本世界专属 · 误读回收与结局池");
		List<String> body = new ArrayList<>();
		for (int k = i + 1; !all.get(k).strip().equals("---"); k++) {
			if (!all.get(k).isBlank()) {
				body.add(all.get(k).stripTrailing());
			}
		}
		String prompt = new WorldGenPromptBuilder(registry).buildWorldPrompt("animal_life");
		String famOriginal = "- 【身子归零 = 撞上；老死 = 走完回合表、身子低但未归零】。族级共用片段“活到最后”继续生效。";
		for (String line : body) {
			if (line.equals(famOriginal)) {
				assertThat(prompt).contains("- 【身子归零 = 撞上；老死 = 走完回合表、身子低但未归零】——");
				continue;
			}
			assertThat(prompt).as("7-A 行缺失:%s", line).contains(line.replace("%s", "5-6"));
		}
		assertThat(prompt).contains("### 本世界专属 · 误读回收与结局池")
				.doesNotContain("走回去了").doesNotContain("屋里段建立的每一条规律");
	}
}
