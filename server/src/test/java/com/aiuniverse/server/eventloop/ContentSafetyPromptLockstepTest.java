package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-004 刀 2 · 【绝境的写法 · 硬约束】lockstep(五个面)。
 *
 * <p><b>为什么是五个面而不是四个</b>:回合侧只有一份骨架({@code TurnPromptBuilder.SKELETON},融合段是它的
 * {@code %8$s} 追加),而 world-gen 侧是<b>两份独立骨架</b>({@code SKELETON} + {@code FUSION_SKELETON},
 * 各自持一份 {@code 【泄露硬约束】} 拷贝就是既有先例)。<b>漏改 {@code FUSION_SKELETON} 不会让 parity 变红</b>
 * ——它只会把 16 处变成 14 处,而 14/16 在阳性对照里看起来像成功了(parity 只报变没变、不报该不该变)。
 * 故这一份必须有独立断言,且变异时必须<b>单独变红</b>。
 *
 * <p><b>整段对拍而非挑短语</b>:定稿在五处逐字节相同(.md 侧刻意不加 markdown 加粗),故这里直接拿整段
 * {@link #DESPERATION_RULE} 做 {@code contains}——这是本刀能拿到的最强守护形态。
 *
 * <p><b>本测试不测「模型会不会遵守」</b>,只测「这段话在不在 prompt 里」。前者归刀 4 真机冒烟。
 */
class ContentSafetyPromptLockstepTest {

	/**
	 * 定稿逐字(Felix 2026-09-06 裁定,一字不改)。三条设计判据见 ADR-004:
	 * (1) 并入「怎么写」这一族,不作孤立安全条款;(2)「这不是让你把绝望写轻」是<b>承重句</b>——
	 * 没有它,模型会用降低强度来满足禁令,而那正好把这段话变成「把世界写坏」的原因;
	 * (3)「虚构世界内部的主动代价」是<b>显式许可</b>而非「别误伤」。
	 */
	private static final String DESPERATION_RULE = """
			【绝境的写法 · 硬约束】
			角色可以濒死、绝望、失控,也可以在虚构世界内部主动承担牺牲、禁术、污染、散功、燃烧修为等代价;
			这些都可以保持应有的强度。
			但绝境只写处境、感受、选择与后果,不写现实可照做的自伤步骤:
			不展开具体手段、剂量、器具、部位、操作顺序或可复现流程。
			⚠️ 这不是让你把绝望写轻。写轻反而不合格;
			正确做法是保持情绪与后果的重量,把镜头停在「发生了什么 / 他选择了什么 / 代价是什么」,
			不进入「具体怎么做」。""";

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder turnBuilder = new TurnPromptBuilder(registry);
	private final WorldGenPromptBuilder worldBuilder = new WorldGenPromptBuilder(registry);

	@Test
	void turnSkeletonCarriesRule() {
		assertThat(turnBuilder.buildTurnPrompt(rulesEngine(), "A", "x"))
				.as("TurnPromptBuilder.SKELETON(单体回合)").contains(DESPERATION_RULE);
	}

	@Test
	void fusionTurnPromptCarriesRule() {
		// 融合回合走同一份 SKELETON(融合段只是 %8$s 追加),此处钉住「融合局也拿得到」这个事实。
		assertThat(turnBuilder.buildTurnPrompt(hybridEngine(), "A", "x"))
				.as("TurnPromptBuilder.SKELETON(融合回合)").contains(DESPERATION_RULE);
	}

	@Test
	void worldGenSkeletonCarriesRule() {
		assertThat(worldBuilder.buildWorldPrompt("rules_creepy"))
				.as("WorldGenPromptBuilder.SKELETON(单体 world-gen)").contains(DESPERATION_RULE);
	}

	/**
	 * ⚠️ 独立一条:{@code FUSION_SKELETON} 是最容易被漏掉的那一份,且漏掉不会让别的断言变红。
	 * 变异验证要求:只摘掉这一份 → <b>只有本条红,其余四条仍绿</b>(一起红说明它们搭了便车)。
	 */
	@Test
	void fusionWorldGenSkeletonCarriesRule() {
		for (String[] combo : new String[][] {
				{ "cultivation", "rules_creepy" }, { "rules_creepy", "apocalypse" } }) {
			assertThat(worldBuilder.buildFusionPrompt(combo[0], combo[1]))
					.as("WorldGenPromptBuilder.FUSION_SKELETON:%s × %s", combo[0], combo[1])
					.contains(DESPERATION_RULE);
		}
	}

	@Test
	void bothMarkdownAssetsCarryRuleVerbatim() throws IOException {
		// .md 侧刻意不加 markdown 加粗 —— 整段 contains 通过即证明四面逐字节同串。
		assertThat(readMd("event-loop.md")).as("prompts/event-loop.md").contains(DESPERATION_RULE);
		assertThat(readMd("world-gen.md")).as("prompts/world-gen.md").contains(DESPERATION_RULE);
	}

	// 刻意不为「这不是让你把绝望写轻」单设一条承重句断言:整段 contains 已逐字节覆盖它,
	// 那条断言在原理上不可能独立变红——一个不会独立失败的断言看起来像闸,实际什么也没守,
	// 而且它会同时打到三个面、把「摘掉 FUSION_SKELETON 只有一条红」这个隔离性弄脏。

	private Engine rulesEngine() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	private Engine hybridEngine() {
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add("cultivation").add("rules_creepy");
		world.putObject("character").putObject("attributes")
				.put("hp", 90).put("mana", 60).put("realm", 20).put("san", 70);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	/** 从 server 模块向上定位仓库根 prompts/&lt;name&gt;(surefire CWD=server 模块目录)。 */
	private String readMd(String name) throws IOException {
		for (Path p : List.of(Path.of("..", "prompts", name), Path.of("prompts", name))) {
			if (Files.exists(p)) {
				return Files.readString(p);
			}
		}
		throw new IOException("找不到 prompts/" + name + "(CWD=" + Path.of(".").toAbsolutePath() + ")");
	}
}
