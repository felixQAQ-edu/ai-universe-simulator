package com.aiuniverse.server.eventloop;

import static com.aiuniverse.server.eventloop.CommonPromptAdditions.INVENTORY_LINES;
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

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-046 D4 · 回合骨架<b>尾巴字段清单</b>的 lockstep(仿 {@link CommonBoundaryPromptLockstepTest})。
 *
 * <p>记忆账本底稿 §1.6:现有 lockstep 都没覆盖这段 —— 往清单里加字段,md 与运行时副本不一致抓不到。本测试补上:
 * <ol>
 *   <li>(a) 物品三行({@link CommonPromptAdditions#INVENTORY_LINES})逐字出现在:回合单体(registry 全部已激活世界)、
 *       回合融合(全部已登记组合)、{@code prompts/event-loop.md}(md 侧不加 markdown,与运行时同串);</li>
 *   <li>(b) 尾巴五个字段名在运行时尾巴段与 md 尾巴段<b>各出现</b>。两边格式不同(反引号、加粗),整段逐字比做不到,
 *       故按短语守(同 {@link TurnFusionLockstepTest});</li>
 *   <li>(c) 《验讫》那一句不在这里,并入 {@link TurnFusionLockstepTest} 的 per-combo 短语表。</li>
 * </ol>
 * 每个面单独一条用例:变异时只摘一份,应只有对应那一条红(底稿另有逐字节快照,摘运行时那份时它们也会红 —— 那是位置守护,不是本测试)。
 * 本测试不测「模型会不会照做」,只测「这段话在不在 prompt 里」。
 */
class TurnTailFieldsLockstepTest {

	/** 尾巴字段名(不含 narrative:叙事在哨兵前)。 */
	private static final List<String> TAIL_FIELDS =
			List.of("stateUpdate", "triggeredRuleIds", "discoveredRuleIds", "availableActions", "ending");

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);

	// ── (a) 物品三行 ───────────────────────────────────────────────────

	@Test
	void singleTurnPromptsCarryInventoryLines() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			assertThat(builder.buildTurnPrompt(engine(List.of(m.id())), "A", "x")).as("回合单体:%s", m.id())
					.contains(INVENTORY_LINES);
		}
	}

	@Test
	void fusionTurnPromptsCarryInventoryLines() {
		for (FusionSummary c : registry.listFusionCombos()) {
			assertThat(builder.buildTurnPrompt(engine(List.of(c.host(), c.foreign())), "A", "x")).as("回合融合:%s", c.key())
					.contains(INVENTORY_LINES);
		}
	}

	@Test
	void markdownCarriesInventoryLinesVerbatim() throws IOException {
		assertThat(mdTail()).as("prompts/event-loop.md 尾巴段").contains(INVENTORY_LINES);
	}

	// ── (b) 尾巴字段名 ─────────────────────────────────────────────────

	@Test
	void runtimeTailSectionNamesEveryField() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			String tail = runtimeTail(builder.buildTurnPrompt(engine(List.of(m.id())), "A", "x"));
			for (String f : TAIL_FIELDS) {
				assertThat(tail).as("运行时尾巴段(%s)含 - %s:", m.id(), f).contains("- " + f + ":");
			}
		}
	}

	@Test
	void markdownTailSectionNamesEveryField() throws IOException {
		String tail = mdTail();
		for (String f : TAIL_FIELDS) {
			assertThat(tail).as("md 尾巴段含 - `%s`:", f).contains("- `" + f + "`:");
		}
	}

	/** 运行时尾巴段:从「结构化尾巴 JSON」到世界段之前。 */
	private static String runtimeTail(String prompt) {
		int from = prompt.indexOf("结构化尾巴 JSON(不含 narrative 字段");
		int to = prompt.indexOf("\n\n世界设定与当前状态");
		assertThat(from).as("找不到运行时尾巴段起点").isGreaterThanOrEqualTo(0);
		assertThat(to).as("找不到世界段").isGreaterThan(from);
		return prompt.substring(from, to);
	}

	/** md 尾巴段:从「结构化尾巴 JSON 字段」到「一致性要求」。 */
	private static String mdTail() throws IOException {
		String md = readMd("event-loop.md");
		int from = md.indexOf("结构化尾巴 JSON 字段");
		int to = md.indexOf("一致性要求", from);
		assertThat(from).as("找不到 md 尾巴段起点").isGreaterThanOrEqualTo(0);
		assertThat(to).as("找不到 md 尾巴段终点").isGreaterThan(from);
		return md.substring(from, to);
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
