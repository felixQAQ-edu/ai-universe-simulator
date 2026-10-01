package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-028 刀 2b · 旧局兼容:有旧局标记的《动物人生》存档,回合 prompt 必须与 {@code 583abc9}
 * <b>在同一状态下逐字节相同</b>(旧指令、旧时钟、旧【近人】说明与档文字)。
 *
 * <p><b>金样本怎么来的</b>:在 {@code 583abc9} 的临时 worktree 里用与本类 {@link #engine} 完全相同的夹具,
 * 以 {@code new TurnPromptBuilder(new ArchetypeRegistry()).buildTurnPrompt(engine, "A", "趴着")} dump 出
 * 8 份(回合 3/12/16/19/25/30/38/44 × 近人 10/40/80 轮流,覆盖旧时钟五段与旧近人三档);
 * 同一构建 dump 两次逐字节相同(量具自检)后入库,worktree 已删。
 *
 * <p>正对照:同样的状态走<b>新局</b>路径,8 份全部与金样本不同 —— 否则「逐字节相同」也可能只是
 * 两条路径碰巧一样、而这条测试什么都没证明。
 */
class AnimalLifeLegacyGoldenTest {

	private static final int[] TURNS = { 3, 12, 16, 19, 25, 30, 38, 44 };
	private static final int[] CLOSE = { 10, 40, 80 };

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(new ArchetypeRegistry());

	static Engine engine(ObjectMapper mapper, int turn, int close) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4").put("mode", "single");
		world.putArray("archetypes").add("animal_life");
		world.putObject("world").put("title", "屋里的灯").put("background", "后来它被留下了。")
				.put("dangerLevel", "low").put("tone", "克制");
		world.putObject("character").putObject("attributes")
				.put("body", 70).put("warmth", 45).put("ground", 35).put("close", close);
		world.putArray("rules").addObject().put("id", 1).put("content", "金属声响过,门会开")
				.put("hiddenLogic", "屋里兑现").put("discovered", false);
		world.putArray("endings").addObject().put("id", "hit").put("title", "撞上")
				.put("condition", "【身子】中途归零").put("outcome", "failure").put("reached", false);
		Engine e = new Engine(world, mapper);
		for (int i = 0; i < turn; i++) {
			e.applyNoOp("第 " + (i + 1) + " 回合,它趴在床脚。", "A");
		}
		return e;
	}

	private static String golden(int turn, int close) throws Exception {
		String name = "/golden/adr028-legacy-turn-prompts/T" + turn + "-close" + close + ".txt";
		try (InputStream in = AnimalLifeLegacyGoldenTest.class.getResourceAsStream(name)) {
			assertThat(in).as("金样本缺失:" + name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void legacyTurnPromptIsByteIdenticalTo583abc9() throws Exception {
		for (int i = 0; i < TURNS.length; i++) {
			int turn = TURNS[i];
			int close = CLOSE[i % CLOSE.length];
			String actual = builder.buildTurnPrompt(engine(mapper, turn, close), "A", "趴着", "", "", true);
			assertThat(actual).as("T%d close=%d", turn, close).isEqualTo(golden(turn, close));
		}
	}

	@Test
	void newGamePathDiffersFromEveryGolden_positiveControl() throws Exception {
		for (int i = 0; i < TURNS.length; i++) {
			int turn = TURNS[i];
			int close = CLOSE[i % CLOSE.length];
			String fresh = builder.buildTurnPrompt(engine(mapper, turn, close), "A", "趴着", "", "", false);
			assertThat(fresh).as("T%d", turn).isNotEqualTo(golden(turn, close));
		}
	}
}
