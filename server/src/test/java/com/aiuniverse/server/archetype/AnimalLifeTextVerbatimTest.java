package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.AttributeAxis.BandRange;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.TurnPromptBuilder;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-028 刀 2b · 《动物人生》世界层文字逐字守卫:只接受「ADR 附录 → 代码」一个方向。
 * 从 ADR「附录 · 第二刀文案定稿」第二节 5/6 与第三节 7-A/7-B/7-C 抽出原文,与 registry / world-gen 比对。
 *
 * <p>多行原文<b>按行保留</b>(刀 2 补充 · 2b 修正):去掉引用符 {@code > } 与空的引用行,其余每一行
 * 保持为单独一行、用 {@code \n} 连接,行内文字一字不改。prompt 侧断言「附录每一行都是 prompt 里的单独一行」——
 * 唯一的例外是首行前面挂着 prompt 既有的标签(「世界观:」「规则形态:」「- close(近人,0-100;」「  · close(近人):」),
 * 以及 world-gen 轴说明末行后面挂着既有的「)」;这几处标签是既有渲染,其他世界共用,本刀不改。
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

	private String worldGenPrompt() {
		return new WorldGenPromptBuilder(registry).buildWorldPrompt("animal_life");
	}

	/** 新局回合 prompt(第 3 回合,无局面;近人 hint 走 behaviorReminder)。 */
	private String turnPrompt() {
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4").put("mode", "single");
		w.putArray("archetypes").add("animal_life");
		w.putObject("world").put("title", "t").put("background", "b").put("dangerLevel", "low").put("tone", "x");
		w.putObject("character").putObject("attributes").put("body", 80).put("warmth", 60).put("ground", 40)
				.put("close", 50);
		w.putArray("rules");
		w.putArray("endings").addObject().put("id", "e").put("title", "撞上").put("condition", "身子归零")
				.put("outcome", "failure").put("reached", false);
		return new TurnPromptBuilder(registry).buildTurnPrompt(new Engine(w, mapper), "A", "趴着");
	}

	/**
	 * 附录每一行在 prompt 里都是单独一行;首行前挂 {@code head}(既有标签),末行后挂 {@code tail}(既有收尾符)。
	 */
	private static void assertEachLineInPrompt(String prompt, List<String> lines, String head, String tail) {
		List<String> promptLines = List.of(prompt.split("\n", -1));
		for (int i = 0; i < lines.size(); i++) {
			String expect = (i == 0 ? head : "") + lines.get(i) + (i == lines.size() - 1 ? tail : "");
			assertThat(promptLines).as("第 %d 行不是 prompt 里的单独一行:%s", i + 1, expect).contains(expect);
		}
	}

	private AttributeAxis close() {
		return registry.meta("animal_life").attributes().stream().filter(a -> a.key().equals("close"))
				.findFirst().orElseThrow();
	}

	@Test
	void worldviewAndTaglineAreAppendixItems5And6() throws Exception {
		List<String> all = adr();
		ArchetypeMeta m = registry.meta("animal_life");
		List<String> lines = section(all, "#### 5．《动物人生》世界背景", "####");
		assertThat(lines).hasSize(3);
		assertThat(m.worldview()).isEqualTo(String.join("\n", lines));
		assertEachLineInPrompt(worldGenPrompt(), lines, "世界观:", "");
		assertThat(List.of(m.tagline())).isEqualTo(section(all, "#### 6．选择屏一句话", "###"));
		assertThat(m.tagline()).isEqualTo("熟悉的一切变了，你学会的规则还作数吗？");
	}

	@Test
	void ruleFormIs7B() throws Exception {
		List<String> b = section(adr(), "#### 7-B．世界生成侧：`rules` 形态说明", "####");
		assertThat(b.get(0)).isEqualTo("用以下内容替换现有说明：");
		List<String> lines = b.subList(1, b.size());
		assertThat(lines).hasSize(14);
		assertThat(registry.meta("animal_life").ruleForm()).isEqualTo(String.join("\n", lines));
		assertEachLineInPrompt(worldGenPrompt(), lines, "规则形态:", "");
	}

	@Test
	void closeHintAndBandsAre7C() throws Exception {
		List<String> c = section(adr(), "#### 7-C．【近人】轴说明与档位", "####");
		int k = c.indexOf("档位：");
		List<String> hint = new ArrayList<>(c.subList(1, k));
		List<String> rest = c.subList(k + 1, c.size());
		List<String> bandLines = rest.stream().filter(x -> x.startsWith("- `")).toList();
		hint.addAll(rest.stream().filter(x -> !x.startsWith("- `")).toList());
		assertThat(hint).hasSize(7);
		assertThat(close().behaviorHint()).isEqualTo(String.join("\n", hint))
				.endsWith("“那扇门不会再打开”只指旧家的门，不得用于新家的门。");
		assertEachLineInPrompt(worldGenPrompt(), hint, "- close(近人,0-100;", ")");
		assertEachLineInPrompt(turnPrompt(), hint, "  · close(近人):", "");
		List<BandRange> ranges = close().bandRanges();
		assertThat(ranges).hasSize(3);
		List<AttributeAxis.Band> bands = close().bands().stream()
				.sorted(java.util.Comparator.comparingInt(AttributeAxis.Band::threshold)).toList();
		for (int i = 0; i < 3; i++) {
			AttributeAxis.Band b = bands.get(i);
			assertThat(bandLines.get(i)).isEqualTo("- `" + b.threshold() + "　" + b.label() + "`——" + b.narrationHint());
		}
	}

	/** 附录末「修订(Felix 2026-10-03)」里改后的那一行。 */
	private static String revisedOutsideLine(List<String> all) {
		int i = all.indexOf("### 修订(Felix 2026-10-03)");
		assertThat(i).as("ADR-028 附录末找不到修订节").isGreaterThanOrEqualTo(0);
		return all.subList(i, all.size()).stream().filter(l -> l.startsWith("- `OUTSIDE`：")).findFirst()
				.orElseThrow();
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
		// ADR-029:7-A 里被附录末「修订(Felix 2026-10-03)」取代的那一行,改对照修订节(原句不改)。
		String revised = revisedOutsideLine(all);
		String superseded = "- `OUTSIDE`：那是别人的钥匙、别人的门，不是它等的那一扇。";
		assertThat(body.stream().map(String::strip).toList()).as("7-A 原句仍在附录里").contains(superseded);
		body.replaceAll(l -> l.strip().equals(superseded) ? l.replace(superseded, revised) : l);
		String prompt = new WorldGenPromptBuilder(registry).buildWorldPrompt("animal_life");
		assertThat(prompt).doesNotContain(superseded).doesNotContain("钥匙");
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
