package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-020 刀 7:F-021 故障 ①(出口按下无痕迹)与 ③(§8 气力下限未守住)。
 *
 * <p><b>①</b> 钉的是「痕迹」是<b>构造保证</b>而不是模型自觉:硬信号取自玩家选过的动作 id
 * (`log().playerAction` / `logSummary()` 的 `选X`),<b>不看 `timeline`</b>——那是软的。
 * 同时钉住<b>变形而非消失</b>:按过之后 X <b>仍在</b>(不可逆的 UI 状态必须由不可逆的事实支撑,
 * 而「收束段真的走完」现在不是硬事实)。
 *
 * <p><b>③</b> 钉的是钳制发生在 {@code Engine.apply} <b>之前</b>、且<b>记 issue</b>——
 * 那条 issue 是刀 8 冒烟要读的东西(未触发 = B① 够了;触发了 = 软的确实不行)。
 */
class ExitFeedbackAndVigorFloorTest {

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

		@Override public void narrative(String t) { }
		@Override public void delta(ObjectNode d) { delta = d; }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String c, String m) { }
	}

	private GameSession session() {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4").put("mode", "single");
		world.putArray("archetypes").add("life_sim");
		world.putObject("world").put("title", "寻常").put("background", "…")
				.put("dangerLevel", "low").put("tone", "克制");
		world.putObject("character").putObject("attributes")
				.put("vigor", 70).put("longing", 50).put("crossroads", 40).put("ties", 30);
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "peaceful_end").put("title", "寿终·圆满")
				.put("condition", "回合走到尽头,气力仍在 15 以上").put("outcome", "success").put("reached", false);
		ArrayNode acts = world.putArray("availableActions");
		acts.addObject().put("id", "A").put("text", "上班");
		return new GameSession("save-life", new Engine(world, mapper), acts.deepCopy());
	}

	private String tail(int vigor) {
		return "{\"stateUpdate\":{\"vigor\":" + vigor + ",\"longing\":40,\"crossroads\":30,\"ties\":35,"
				+ "\"timeline\":\"日子过着\"},"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"回自己座位\"},{\"id\":\"B\",\"text\":\"上楼\"}],"
				+ "\"ending\":null}";
	}

	/** 跑一回合;actionId 传 "X" 即模拟玩家按下出口。 */
	private ObjectNode turn(GameSession s, String actionId, int vigor) {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script("他把饭盒放回架子上。" + SentinelSplitter.SENTINEL + tail(vigor));
		Sink sink = new Sink();
		new EventLoopService(llm, prompts, mapper).execute(s, actionId, sink);
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

	// ── ① 出口按下后必须留下痕迹 ────────────────────────────────────

	@Test
	void exitChangesWordingOncePressed_theTraceIsTheChangeItself() {
		GameSession s = session();
		for (int i = 0; i < 8; i++) {
			turn(s, "A", 60); // 推进到青年段,出口开始出现
		}
		JsonNode before = exitOf(turn(s, "A", 60));
		assertThat(before).isNotNull();
		String textBefore = before.path("text").asString("");
		String hintBefore = before.path("hint").asString("");
		assertThat(hintBefore).isEqualTo(LifeStage.EXIT_HINT);

		JsonNode after = exitOf(turn(s, LifeStage.EXIT_ACTION_ID, 60)); // 按下 X
		assertThat(after).isNotNull();

		// F-021 故障 ① 的直接表征是「三次副标题一字不差」——故 hint 必须变。
		assertThat(after.path("hint").asString("")).isNotEqualTo(hintBefore)
				.isEqualTo(LifeStage.EXIT_HINT_AFTER);
		assertThat(after.path("text").asString("")).isNotEqualTo(textBefore)
				.isEqualTo(LifeStage.EXIT_TEXT_AFTER);
	}

	/**
	 * ⚠️ 变形不是消失:按过之后 X <b>仍在</b>。
	 * 立字:<b>不可逆的 UI 状态必须由不可逆的事实支撑</b>,而「收束段真的走完」靠模型维护
	 * `timeline`、漏写就丢 —— 若 X 按下即永久消失,模型一漏写,玩家<b>既失去出口、局也不结束</b>。
	 */
	@Test
	void exitStaysPresentAfterPress_soThePlayerCanConfirmAgain() {
		GameSession s = session();
		for (int i = 0; i < 9; i++) {
			turn(s, "A", 60);
		}
		turn(s, LifeStage.EXIT_ACTION_ID, 60);
		ObjectNode d = turn(s, "A", 60);
		assertThat(exitOf(d)).as("按过之后出口仍应在场(再按一次是确认,不是 bug)").isNotNull();
		assertThat(s.hasAction(LifeStage.EXIT_ACTION_ID)).isTrue();
	}

	/**
	 * <b>硬信号跨 `compressLog` 折叠仍然成立</b>:LOG_KEEP=4,按下后再走 6 回合,
	 * 那一条已被折成 `[T{n}选X]` 进 `logSummary` —— 判定必须仍为「已按过」。
	 * 这是选「动作 id」而不是「timeline」作载体的<b>全部理由</b>。
	 */
	@Test
	void pressedSignalSurvivesLogCompression() {
		GameSession s = session();
		for (int i = 0; i < 9; i++) {
			turn(s, "A", 60);
		}
		turn(s, LifeStage.EXIT_ACTION_ID, 60);
		for (int i = 0; i < 6; i++) {
			turn(s, "A", 60); // 把那一回合挤出 LOG_KEEP 窗口
		}
		assertThat(s.engine().logSummary()).contains("选" + LifeStage.EXIT_ACTION_ID);
		assertThat(s.engine().log()).noneSatisfy(e ->
				assertThat(e.path("playerAction").asString("")).isEqualTo(LifeStage.EXIT_ACTION_ID));
		JsonNode exit = exitOf(turn(s, "A", 60));
		assertThat(exit.path("hint").asString("")).isEqualTo(LifeStage.EXIT_HINT_AFTER);
	}

	@Test
	void pressedSignalDoesNotDependOnTimeline() {
		// 模型从不往 timeline 写收束标记(刀 5 那条软约束漏写)—— 判定照旧成立。
		GameSession s = session();
		for (int i = 0; i < 9; i++) {
			turn(s, "A", 60);
		}
		turn(s, LifeStage.EXIT_ACTION_ID, 60);
		assertThat(s.engine().timeline()).doesNotContain("收束段");
		assertThat(exitOf(turn(s, "A", 60)).path("hint").asString("")).isEqualTo(LifeStage.EXIT_HINT_AFTER);
	}

	// ── ③ §8 气力下限 ──────────────────────────────────────────────

	@Test
	void vigorClampedInFinalStageAndIssueRecorded() {
		GameSession s = session();
		for (int i = 0; i < 44; i++) { // 推到末段(第 44 回合起)
			turn(s, "A", 60);
		}
		turn(s, "A", 5); // 模型给 5,低于下限 15
		assertThat(s.engine().attribute("vigor")).isEqualTo(15.0);
		// ⚠️ 措辞要能一眼分辨是「钳制」而不是 Engine 自己那条「跳变过大」——刀 8 靠数这行。
		assertThat(s.engine().issues()).anySatisfy(i ->
				assertThat(i).contains("vigor 收束下限钳制").contains("5->15"));
		assertThat(s.engine().issues()).noneSatisfy(i ->
				assertThat(i).contains("收束下限钳制").contains("跳变过大"));
	}

	@Test
	void vigorClampedAfterExitPressedEvenBeforeFinalStage() {
		GameSession s = session();
		for (int i = 0; i < 9; i++) {
			turn(s, "A", 60);
		}
		turn(s, LifeStage.EXIT_ACTION_ID, 60); // 按下出口 → 收束段
		turn(s, "A", 3);
		assertThat(s.engine().attribute("vigor")).isEqualTo(15.0);
	}

	@Test
	void vigorNotClampedBeforeClosing() {
		GameSession s = session();
		turn(s, "A", 8); // 第 1 回合、未按出口 —— 早逝是允许的,不许在这里托底
		assertThat(s.engine().attribute("vigor")).isEqualTo(8.0);
		assertThat(s.engine().issues()).noneSatisfy(i -> assertThat(i).contains("收束下限钳制"));
	}

	@Test
	void clampNeverTouchesOtherWorlds() {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.putArray("archetypes").add("cultivation");
		world.putObject("character").putObject("attributes").put("hp", 40).put("mana", 60).put("realm", 20);
		world.putArray("rules");
		world.putArray("endings");
		ArrayNode acts = world.putArray("availableActions");
		acts.addObject().put("id", "A").put("text", "打坐");
		GameSession xian = new GameSession("save-x", new Engine(world, mapper), acts.deepCopy());

		ScriptedLlm llm = new ScriptedLlm();
		llm.script("灵气微动。" + SentinelSplitter.SENTINEL
				+ "{\"stateUpdate\":{\"hp\":3,\"mana\":10,\"realm\":21,\"timeline\":\"闭关\"},"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"继续\"},{\"id\":\"B\",\"text\":\"收功\"}],"
				+ "\"ending\":null}");
		new EventLoopService(llm, prompts, mapper).execute(xian, "A", new Sink());
		assertThat(xian.engine().attribute("hp")).isEqualTo(3.0); // 一字未动
		assertThat(xian.engine().issues()).noneSatisfy(i -> assertThat(i).contains("收束下限钳制"));
	}

	// ── B① 气力 behaviorHint(补漏:它原是四轴里唯一没有 hint 的)────────

	@Test
	void vigorNowCarriesBehaviorHintAndKeepsRoleAndLethal() {
		ArchetypeMeta meta = new ArchetypeRegistry().meta("life_sim");
		AttributeAxis vigor = meta.attributes().stream()
				.filter(a -> "vigor".equals(a.key())).findFirst().orElseThrow();

		assertThat(vigor.behaviorHint()).isNotNull()
				.contains("自然消耗")
				.contains("任何单次选择都不具决定性"); // 同时喂 ADR-020 §8 早逝三段式
		// 换工厂只多 hint:角色与致命标必须原样,否则 severity 派生与触底行为会跟着变。
		assertThat(vigor.isAccumulation()).isFalse();
		assertThat(vigor.lethal()).isTrue();
		// 三档 bands 未受影响。
		assertThat(vigor.bands()).hasSize(3);
	}

	@Test
	void vigorAppearsInPerTurnBehaviorReminder() {
		// 缺口的直接表征:补 hint 之前,气力从不出现在「特殊行为轴维护提醒」块里。
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add("life_sim");
		world.putObject("character").putObject("attributes")
				.put("vigor", 60).put("longing", 50).put("crossroads", 40).put("ties", 30);
		world.putArray("rules");
		world.putArray("endings");
		String p = prompts.buildTurnPrompt(new Engine(world, mapper), "A", "行动");
		assertThat(p).contains("每回合都要在 stateUpdate 严格按提示体现");
		assertThat(p).contains("vigor(气力):");
	}
}
