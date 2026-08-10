package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.engine.GameSchemas;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 「就到这里」出口(ADR-020 刀 5 · B,路径 (c) 服务端在<b>校验之后</b>追加)。
 *
 * <p><b>这组测试钉的是「始终在场」是构造保证而不是模型自律</b>——路径 (a)(让 AI 自己每回合
 * 记得给这一项)被否决的理由就是:用模型自律去修「模型自律守不住」是循环(F-020)。
 * 故这里断言的是:AI 只回了 A/B,出口仍然在;而<b>校验层一字未动</b>(maxItems 仍是 4)。
 */
class LifeExitActionTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder prompts = new TurnPromptBuilder(new ArchetypeRegistry());

	private static class ScriptedLlm implements LlmClient {
		final Deque<List<String>> responses = new ArrayDeque<>();

		void script(String... full) {
			for (String r : full) {
				responses.add(List.of(r));
			}
		}

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			List<String> toks = responses.poll();
			if (toks == null) {
				throw new LlmException("脚本耗尽");
			}
			toks.forEach(sink::onToken);
		}
	}

	private static final class Sink implements TurnEventSink {
		ObjectNode delta;

		@Override public void narrative(String text) { }
		@Override public void delta(ObjectNode d) { delta = d; }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String code, String msg) { }
	}

	/** 《寻常》会话(四轴);{@code endingId != null} 时 AI 提议该结局。 */
	private GameSession ordinaryLifeSession(String... archetypes) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4").put("mode", archetypes.length > 1 ? "hybrid" : "single");
		ArrayNode a = world.putArray("archetypes");
		for (String s : archetypes) {
			a.add(s);
		}
		world.putObject("world").put("title", "寻常").put("background", "…")
				.put("dangerLevel", "low").put("tone", "克制");
		world.putObject("character").putObject("attributes")
				.put("vigor", 70).put("longing", 50).put("crossroads", 40).put("ties", 30);
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "peaceful_end").put("title", "寿终·圆满")
				.put("condition", "回合走到尽头,气力仍在 15 以上").put("outcome", "success").put("reached", false);
		ArrayNode actions = world.putArray("availableActions");
		actions.addObject().put("id", "A").put("text", "上班");
		return new GameSession("save-life", new Engine(world, mapper), actions.deepCopy());
	}

	private String wire(String tailJson) {
		return "他把饭盒放回架子上。" + SentinelSplitter.SENTINEL + tailJson;
	}

	/** AI 只给 A/B 两项(它并不知道出口这回事)。 */
	private String tailWithTwoActions(String ending) {
		return "{\"stateUpdate\":{\"vigor\":68,\"longing\":48,\"crossroads\":40,\"ties\":31,"
				+ "\"timeline\":\"厂里第三年\"},"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"回自己座位\",\"hint\":\"没人会注意\"},"
				+ "{\"id\":\"B\",\"text\":\"去车间后门抽支烟\",\"hint\":\"要走一段\"}],"
				+ "\"ending\":" + ending + "}";
	}

	private ObjectNode runOneTurn(GameSession session) {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(wire(tailWithTwoActions("null")));
		Sink sink = new Sink();
		new EventLoopService(llm, prompts, mapper).execute(session, "A", sink);
		return sink.delta;
	}

	private JsonNode exitOf(ObjectNode delta) {
		for (JsonNode n : delta.path("availableActions")) {
			if (LifeStage.EXIT_ACTION_ID.equals(n.path("id").asString(""))) {
				return n;
			}
		}
		return null;
	}

	@Test
	void exitIsAppendedThoughAiOnlyReturnedTwoActions_constructionNotSelfDiscipline() {
		GameSession s = ordinaryLifeSession("life_sim");
		// 先推到青年段之后(出口从青年 T8 起才出现;T1 这组选项通向 T2 = 少年,不出现)。
		for (int i = 0; i < 7; i++) {
			runOneTurn(s);
		}
		ObjectNode delta = runOneTurn(s); // 第 8 回合落账 → 这组选项通向 T9(青年)
		assertThat(delta.path("availableActions")).hasSize(3); // AI 给 2 + 出口 1

		JsonNode exit = exitOf(delta);
		assertThat(exit).isNotNull();
		assertThat(exit.path("text").asString("")).isEqualTo("不再往下想了,就这样过"); // 青年措辞
		assertThat(exit.path("hint").asString("")).isEqualTo(LifeStage.EXIT_HINT);
		// id 避开骨架规定的 A/B/C/D,故与 AI 产出的任何一项都不会撞。
		assertThat(exit.path("id").asString("")).isEqualTo("X").isNotIn("A", "B", "C", "D");
	}

	@Test
	void appendedExitIsSelectableByTheGuard() {
		GameSession s = ordinaryLifeSession("life_sim");
		for (int i = 0; i < 8; i++) {
			runOneTurn(s);
		}
		// GameSession.hasAction(守卫 1)按 currentActions 放行 —— 追加项必须真的能被选中,
		// 否则玩家点了会被拒掉(那才是「看起来有效、实际是坏的」控件)。
		assertThat(s.hasAction(LifeStage.EXIT_ACTION_ID)).isTrue();
	}

	@Test
	void noExitInChildhoodAndAdolescence() {
		GameSession s = ordinaryLifeSession("life_sim");
		ObjectNode d1 = runOneTurn(s); // T1 落账 → 选项通向 T2(少年)
		assertThat(exitOf(d1)).as("少年不给出口:出口的前提是你有一个可以放弃的人生").isNull();
		assertThat(d1.path("availableActions")).hasSize(2);
	}

	@Test
	void noExitForOtherWorldsOrFusion() {
		// 四个既有世界不受影响(前端与 wire 皆零改动)。
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.putArray("archetypes").add("cultivation");
		world.putObject("character").putObject("attributes").put("hp", 90).put("mana", 60).put("realm", 20);
		world.putArray("rules");
		world.putArray("endings");
		ArrayNode acts = world.putArray("availableActions");
		acts.addObject().put("id", "A").put("text", "打坐");
		GameSession xian = new GameSession("save-x", new Engine(world, mapper), acts.deepCopy());

		ScriptedLlm llm = new ScriptedLlm();
		llm.script("灵气微动。" + SentinelSplitter.SENTINEL
				+ "{\"stateUpdate\":{\"hp\":90,\"mana\":55,\"realm\":21,\"timeline\":\"闭关\"},"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"继续\"},{\"id\":\"B\",\"text\":\"收功\"}],"
				+ "\"ending\":null}");
		Sink sink = new Sink();
		new EventLoopService(llm, prompts, mapper).execute(xian, "A", sink);
		assertThat(exitOf(sink.delta)).isNull();
		assertThat(sink.delta.path("availableActions")).hasSize(2);
	}

	@Test
	void noExitOnEndedTurn() {
		GameSession s = ordinaryLifeSession("life_sim");
		for (int i = 0; i < 8; i++) {
			runOneTurn(s);
		}
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(wire(tailWithTwoActions("{\"reached\":true,\"id\":\"peaceful_end\"}")));
		Sink sink = new Sink();
		new EventLoopService(llm, prompts, mapper).execute(s, "A", sink);
		assertThat(s.engine().status()).isEqualTo("ended");
		assertThat(exitOf(sink.delta)).as("局已结束,再给出口没有意义").isNull();
	}

	/**
	 * ⚠️ <b>校验层一字未动的证据</b>:出口是在 {@code validateTurn} <b>之后</b>追加的,
	 * 故 {@code TURN_SCHEMA} 的 {@code maxItems 4} 仍然有效 ——
	 * 一个真的带 5 项的模型输出,照旧会被校验拒掉(Felix 裁定:不放行动 TURN_SCHEMA)。
	 */
	@Test
	void schemaStillRejectsFiveActionsFromModel_maxItems4Untouched() {
		ObjectNode turn = mapper.createObjectNode();
		turn.put("narrative", "他把饭盒放回架子上。");
		turn.putObject("stateUpdate").put("vigor", 60);
		ArrayNode acts = turn.putArray("availableActions");
		for (String id : new String[] { "A", "B", "C", "D", "E" }) {
			acts.addObject().put("id", id).put("text", "动作" + id);
		}
		turn.putNull("ending");
		assertThat(GameSchemas.validateTurn(turn))
				.anySatisfy(e -> assertThat(e).contains("availableActions"));
	}
}
