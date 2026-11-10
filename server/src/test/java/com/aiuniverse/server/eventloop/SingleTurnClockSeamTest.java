package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.archetype.FusionSummary;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-036 Slice A · 单体世界按回合注入接缝(纯机制,不含《派单》内容)。钉三件事:
 * <ol>
 * <li>未登记的世界,回合提示词逐字节不变(Slice A 时表为空,Slice D 起只登记《派单》;登记别的世界不影响它);</li>
 * <li>单体表与融合表互斥:融合局不出单体表的文字,单体局不出融合表的文字;</li>
 * <li>登记一个测试用的假时钟时,指定回合号拿到对应文字,且落在「请推进第 n 回合」之前。</li>
 * </ol>
 *
 * <p><b>「假世界」的口径</b>:registry 没有测试注入口(加一个超出本刀范围),故假时钟登记在<b>已激活的 id</b> 上
 * (用测试构造器换一张表),不往 registry 里塞假 archetype。未激活 id 在 {@code resolveContext} 回落
 * {@code rules_creepy},所以登在不存在的 id 上的时钟永远不会被查到 —— 这一点也一并钉住。
 */
class SingleTurnClockSeamTest {

	private static final int[] TURNS = { 0, 3, 6, 10, 25, 26, 27, 30 };
	private static final String MARK = "【测试假时钟】";

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder production = new TurnPromptBuilder(registry);

	private static String fakeClock(int n) {
		return "\n\n" + MARK + "第 " + n + " 回合" + (n == 27 ? ",本回合必须给出结局。" : "。");
	}

	/** 给所有已激活世界都登记假时钟的表。 */
	private Map<String, IntFunction<String>> fakeForAllActive() {
		Map<String, IntFunction<String>> m = new HashMap<>();
		registry.activeMetas().forEach(meta -> m.put(meta.id(), SingleTurnClockSeamTest::fakeClock));
		return m;
	}

	// ① 未登记的世界逐字节不变 ───────────────────────────────────────────

	@Test
	void productionTableHoldsOnlyPaidan() {
		// Slice A 时为空;Slice D 登记《派单》(cyberpunk),表里只此一项。
		assertThat(TurnPromptBuilder.SINGLE_TURN_CLOCK).containsOnlyKeys("cyberpunk");
	}

	@Test
	void unregisteredWorldsAreByteIdenticalWhenAnotherWorldIsRegistered() {
		TurnPromptBuilder onlyApocalypse = new TurnPromptBuilder(registry,
				Map.of("apocalypse", SingleTurnClockSeamTest::fakeClock));
		int compared = 0;
		for (Case c : singleCases()) {
			// 生产表里登记过的世界(Slice D 起:cyberpunk)在替换表里没有时钟,本来就会不同,不比。
			if (c.ids().get(0).equals("apocalypse")
					|| TurnPromptBuilder.SINGLE_TURN_CLOCK.containsKey(c.ids().get(0))) {
				continue;
			}
			assertThat(onlyApocalypse.buildTurnPrompt(c.engine(), "A", "测试行动"))
					.as(c.label())
					.isEqualTo(production.buildTurnPrompt(c.engine(), "A", "测试行动"))
					.doesNotContain(MARK);
			compared++;
		}
		assertThat(compared).isEqualTo((registry.activeMetas().size() - 1 - TurnPromptBuilder.SINGLE_TURN_CLOCK.size()) * TURNS.length);
	}

	@Test
	void clockKeyedOnUnknownIdIsNeverLookedUp() {
		// 未激活 / 不存在的 id 在 resolveContext 回落 rules_creepy:登在那种 id 上的时钟查不到,规则怪谈也不受影响。
		TurnPromptBuilder ghost = new TurnPromptBuilder(registry,
				Map.of("no_such_world", SingleTurnClockSeamTest::fakeClock));
		for (int t : TURNS) {
			Engine unknown = engine(List.of("no_such_world"), registry.meta("rules_creepy").attributes(), t);
			assertThat(ghost.buildTurnPrompt(unknown, "A", "测试行动"))
					.isEqualTo(production.buildTurnPrompt(unknown, "A", "测试行动"))
					.doesNotContain(MARK);
		}
	}

	// ② 单体表与融合表互斥 ──────────────────────────────────────────────

	@Test
	void fusionGamesNeverSeeSingleTableEvenWhenBothHalvesAreRegistered() {
		TurnPromptBuilder all = new TurnPromptBuilder(registry, fakeForAllActive());
		List<Case> fusion = fusionCases();
		assertThat(fusion).hasSize(registry.listFusionCombos().size() * TURNS.length).isNotEmpty();
		for (Case c : fusion) {
			assertThat(all.buildTurnPrompt(c.engine(), "A", "测试行动"))
					.as(c.label())
					.isEqualTo(production.buildTurnPrompt(c.engine(), "A", "测试行动"))
					.doesNotContain(MARK);
		}
	}

	@Test
	void singleGamesNeverSeeFusionTable() {
		// 《验讫》的融合时钟按回合注入「【本回合时段】」;它的 host / foreign 作单体局时一个字都不能出。
		TurnPromptBuilder all = new TurnPromptBuilder(registry, fakeForAllActive());
		for (String id : List.of("cultivation", "cthulhu")) {
			for (int t : TURNS) {
				Engine e = engine(List.of(id), registry.meta(id).attributes(), t);
				String p = all.buildTurnPrompt(e, "A", "测试行动");
				assertThat(p).as(id + " T" + (t + 1)).contains(MARK)
						.doesNotContain(TurnPromptBuilder.yanqiTurnClock(t + 1).strip())
						.doesNotContain("【本回合时段】");
			}
		}
		// 反向对照:同一回合号下融合局确实拿到融合时钟(证明上面的「没有」不是因为融合时钟本身为空)。
		Engine yanqi = engine(List.of("cultivation", "cthulhu"), registry.fusedAxes("cultivation", "cthulhu"), 3);
		assertThat(all.buildTurnPrompt(yanqi, "A", "测试行动")).contains("【本回合时段】");
	}

	// ③ 登记假时钟后,指定回合号拿到对应文字 ─────────────────────────────

	@Test
	void registeredFakeWorldGetsTextForThatTurnRightBeforeAdvanceLine() {
		TurnPromptBuilder fake = new TurnPromptBuilder(registry,
				Map.of("apocalypse", SingleTurnClockSeamTest::fakeClock));
		for (int t : TURNS) {
			int n = t + 1;
			Engine e = engine(List.of("apocalypse"), registry.meta("apocalypse").attributes(), t);
			String base = production.buildTurnPrompt(e, "A", "测试行动");
			String advance = "\n\n请推进第 " + n + " 回合。";
			// 与未登记时相比,唯一差别 = 在「请推进第 n 回合」之前插入 clock(n)。
			assertThat(fake.buildTurnPrompt(e, "A", "测试行动"))
					.as("T" + n)
					.isEqualTo(base.replace(advance, fakeClock(n) + advance));
			assertThat(base).containsOnlyOnce(advance);
		}
		Engine t26 = engine(List.of("apocalypse"), registry.meta("apocalypse").attributes(), 26);
		assertThat(fake.buildTurnPrompt(t26, "A", "测试行动"))
				.contains(MARK + "第 27 回合,本回合必须给出结局。\n\n请推进第 27 回合。");
	}

	// ── 夹具 ────────────────────────────────────────────────────────────

	private record Case(String label, List<String> ids, Engine engine) {
	}

	private List<Case> singleCases() {
		List<Case> out = new ArrayList<>();
		for (ArchetypeMeta m : registry.activeMetas()) {
			for (int t : TURNS) {
				out.add(new Case(m.id() + " T" + (t + 1), List.of(m.id()), engine(List.of(m.id()), m.attributes(), t)));
			}
		}
		return out;
	}

	private List<Case> fusionCases() {
		List<Case> out = new ArrayList<>();
		for (FusionSummary c : registry.listFusionCombos()) {
			List<AttributeAxis> axes = registry.fusedAxes(c.host(), c.foreign());
			for (int t : TURNS) {
				out.add(new Case(c.key() + " T" + (t + 1), List.of(c.host(), c.foreign()),
						engine(List.of(c.host(), c.foreign()), axes, t)));
			}
		}
		return out;
	}

	/** 最小可回载的存档:各轴 50,state.turn = 给定值(本回合序号 = turn + 1)。 */
	private Engine engine(List<String> ids, List<AttributeAxis> axes, int turn) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.put("mode", ids.size() == 1 ? "single" : "hybrid");
		ArrayNode arr = world.putArray("archetypes");
		ids.forEach(arr::add);
		world.putObject("world").put("title", "测").put("background", "测").put("dangerLevel", "high").put("tone", "测");
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		axes.forEach(a -> attrs.put(a.key(), 50));
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "x_end").put("title", "终").put("condition", "测")
				.put("outcome", "neutral").put("reached", false);
		ObjectNode p = mapper.createObjectNode();
		p.put("schemaVersion", "0.4");
		p.set("world", world);
		p.putObject("state").put("turn", turn).put("status", "ongoing").put("timeline", "测").put("logSummary", "")
				.putArray("log");
		p.putArray("triggered");
		p.putArray("issues");
		return Engine.restore(p, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
	}
}
