package com.aiuniverse.server.eventloop;

import static com.aiuniverse.server.eventloop.CommonPromptAdditions.BOUNDARY_BLOCK;
import static com.aiuniverse.server.eventloop.CommonPromptAdditions.TONE_LINE;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.FusionSummary;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-037 · 【现实边界 · 硬约束】与 tone 定义行的 lockstep(仿 {@link ContentSafetyPromptLockstepTest})。
 *
 * <p>边界块:回合单体、回合融合、world-gen 单体、world-gen 融合四个运行时面 + 两份 md;
 * tone 行:world-gen 单体、world-gen 融合 + {@code world-gen.md}。每个面单独一条用例 ——
 * 变异时只摘一份(尤其最容易漏的 {@code FUSION_SKELETON}),应只有对应那一条红。
 * 世界清单从 registry 实数,不写死。
 *
 * <p>位置不在这里守:由三组 golden 测试的锚定剔除守({@link CommonPromptAdditions})。
 * 本测试不测「模型会不会遵守」,只测「这段话在不在 prompt 里」。
 */
class CommonBoundaryPromptLockstepTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder turnBuilder = new TurnPromptBuilder(registry);
	private final WorldGenPromptBuilder worldBuilder = new WorldGenPromptBuilder(registry);

	@Test
	void singleTurnPromptsCarryBoundary() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			assertThat(turnBuilder.buildTurnPrompt(engine(List.of(m.id())), "A", "x"))
					.as("回合单体:%s", m.id()).contains(BOUNDARY_BLOCK);
		}
	}

	@Test
	void fusionTurnPromptsCarryBoundary() {
		for (FusionSummary c : registry.listFusionCombos()) {
			assertThat(turnBuilder.buildTurnPrompt(engine(List.of(c.host(), c.foreign())), "A", "x"))
					.as("回合融合:%s", c.key()).contains(BOUNDARY_BLOCK);
		}
	}

	@Test
	void singleWorldGenPromptsCarryBoundary() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			assertThat(worldBuilder.buildWorldPrompt(m.id())).as("world-gen 单体:%s", m.id()).contains(BOUNDARY_BLOCK);
		}
	}

	/** ⚠️ 独立一条:{@code FUSION_SKELETON} 是独立一份骨架,漏改不会让单体那条红。 */
	@Test
	void fusionWorldGenPromptsCarryBoundary() {
		for (FusionSummary c : registry.listFusionCombos()) {
			assertThat(worldBuilder.buildFusionPrompt(c.host(), c.foreign()))
					.as("world-gen 融合:%s", c.key()).contains(BOUNDARY_BLOCK);
		}
	}

	@Test
	void singleWorldGenPromptsCarryToneLine() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			assertThat(worldBuilder.buildWorldPrompt(m.id())).as("world-gen 单体:%s", m.id()).contains(TONE_LINE);
		}
	}

	@Test
	void fusionWorldGenPromptsCarryToneLine() {
		for (FusionSummary c : registry.listFusionCombos()) {
			assertThat(worldBuilder.buildFusionPrompt(c.host(), c.foreign()))
					.as("world-gen 融合:%s", c.key()).contains(TONE_LINE);
		}
	}

	@Test
	void markdownAssetsCarryBoundaryAndToneVerbatim() throws IOException {
		assertThat(readMd("event-loop.md")).as("prompts/event-loop.md").contains(BOUNDARY_BLOCK);
		assertThat(readMd("world-gen.md")).as("prompts/world-gen.md").contains(BOUNDARY_BLOCK).contains(TONE_LINE);
	}

	private Engine engine(List<String> ids) {
		ObjectNode world = mapper.createObjectNode();
		var arr = world.putArray("archetypes");
		ids.forEach(arr::add);
		world.put("mode", ids.size() == 1 ? "single" : "hybrid");
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		var axes = ids.size() == 1 ? registry.meta(ids.get(0)).attributes() : registry.fusedAxes(ids.get(0), ids.get(1));
		axes.forEach(a -> attrs.put(a.key(), 50));
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	private static String readMd(String name) throws IOException {
		Path p = Path.of("").toAbsolutePath();
		while (p != null && !Files.exists(p.resolve("prompts/" + name))) {
			p = p.getParent();
		}
		assertThat(p).as("找不到仓库根(prompts/%s)", name).isNotNull();
		return Files.readString(p.resolve("prompts/" + name));
	}
}
