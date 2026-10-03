package com.aiuniverse.server.eventloop;

import static com.aiuniverse.server.eventloop.BoxSceneTables.ANIMAL_LIFE_BOX;
import static com.aiuniverse.server.eventloop.BoxSceneTables.ANIMAL_LIFE_POOLS;
import static com.aiuniverse.server.eventloop.BoxSceneTables.LEAVE_HOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxScene.PoolIntent;
import com.aiuniverse.server.eventloop.BoxScene.Situation;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;
import com.aiuniverse.server.persistence.SessionDocument;
import com.aiuniverse.server.persistence.SessionStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-028 刀 2a:纸箱局面 / 处境 / 意图池 / 习惯句<b>接进回合路径</b>之后的行为(不依赖真模型)。
 *
 * <p>模型由脚本代替;断言只看引擎给定的东西(征兆、反馈、槽位、处境、映射),不看措辞。
 */
class BoxSceneWiringTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder prompts = new TurnPromptBuilder(new ArchetypeRegistry());

	/** 脚本化模型:每次调用弹出一条;记下收到的每份 prompt。 */
	static final class ScriptedLlm implements LlmClient {
		final Deque<Object> responses = new ArrayDeque<>();
		final List<String> prompts = new ArrayList<>();

		void script(String full) {
			responses.add(full);
		}

		void fail(RuntimeException e) {
			responses.add(e);
		}

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			prompts.add(request.prompt());
			Object r = responses.poll();
			if (r == null) {
				throw new LlmException("脚本耗尽");
			}
			if (r instanceof RuntimeException e) {
				throw e;
			}
			sink.onToken((String) r);
		}
	}

	static final class Sink implements TurnEventSink {
		final List<String> events = new ArrayList<>();
		final StringBuilder narrative = new StringBuilder();
		ObjectNode delta;
		boolean failDelta;

		@Override
		public void narrative(String t) {
			events.add("narrative");
			narrative.append(t);
		}

		@Override
		public void delta(ObjectNode d) {
			events.add("delta");
			if (failDelta) {
				throw new IllegalStateException("客户端已断开");
			}
			delta = d;
		}

		@Override
		public void ending(ObjectNode e) {
		}

		@Override
		public void error(String c, String m) {
			events.add("error:" + c);
		}
	}

	// ── 夹具 ────────────────────────────────────────────────────────────

	private ObjectNode world() {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4").put("mode", "single");
		world.putArray("archetypes").add("animal_life");
		world.putObject("world").put("title", "屋里的灯").put("background", "…")
				.put("dangerLevel", "low").put("tone", "克制");
		world.putObject("character").putObject("attributes")
				.put("body", 80).put("warmth", 60).put("ground", 50).put("close", 50);
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "hit").put("title", "撞上")
				.put("condition", "【身子】归零").put("outcome", "failure").put("reached", false);
		return world;
	}

	/** 一局《动物人生》,引擎停在第 {@code turn} 回合,局面状态 = {@code st}。 */
	private GameSession sessionAt(int turn, BoxSceneState st) {
		Engine engine = new Engine(world(), mapper);
		for (int i = 0; i < turn; i++) {
			engine.applyNoOp("屋里的日子", "A");
		}
		ArrayNode acts = mapper.createArrayNode();
		acts.addObject().put("id", "A").put("text", "趴着");
		acts.addObject().put("id", "B").put("text", "闻闻");
		GameSession s = new GameSession("save-box", engine, acts);
		s.setBoxScene(st);
		return s;
	}

	private static String tail(String... ids) {
		StringBuilder a = new StringBuilder();
		for (String id : ids) {
			if (a.length() > 0) {
				a.append(',');
			}
			a.append("{\"id\":\"").append(id).append("\",\"text\":\"模型写的").append(id)
					.append("\",\"hint\":\"h").append(id).append("\"}");
		}
		return "{\"stateUpdate\":{\"body\":80,\"warmth\":60,\"ground\":50,\"close\":50,\"timeline\":\"日子\"},"
				+ "\"availableActions\":[" + a + "],\"ending\":null}";
	}

	private static String ok(String... ids) {
		return "它把鼻子贴近地面。" + SentinelSplitter.SENTINEL + tail(ids);
	}

	private record Turn(Sink sink, ScriptedLlm llm) {
		String prompt() {
			return llm.prompts.get(0);
		}
	}

	/** 跑一回合,模型给 A/B/C 三个合规槽位。 */
	private Turn turn(GameSession s, String actionId) {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(ok("A", "B", "C"));
		Sink sink = new Sink();
		new EventLoopService(llm, prompts, mapper).execute(s, actionId, sink);
		return new Turn(sink, llm);
	}

	private static List<String> texts(JsonNode actions) {
		List<String> out = new ArrayList<>();
		actions.forEach(a -> out.add(a.path("text").asString("")));
		return out;
	}

	private static List<String> ids(JsonNode actions) {
		List<String> out = new ArrayList<>();
		actions.forEach(a -> out.add(a.path("id").asString("")));
		return out;
	}

	// ── 纸箱阶段 1–4:征兆、反馈、槽位、按已存映射结算 ──────────────────────

	@Test
	void r1PathThroughWindowWithAftermathAndTurn17Fill() {
		GameSession s = sessionAt(10, BoxSceneState.fresh());
		BoxSceneState st = s.boxScene();

		Turn t11 = turn(s, "A"); // 玩家在纸箱前的自由选项里选 A → 第 11 回合 = 阶段 1
		assertThat(t11.prompt()).contains(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.stages().get(0).omen()))
				.contains("跟着孩子").contains("钻进纸箱闻一闻").contains("回到平时趴着的地方");
		assertThat(st.slots).containsExactly(
				java.util.Map.entry("A", "FOLLOW"), java.util.Map.entry("B", "SNIFF_BOX"),
				java.util.Map.entry("C", "OLD_SPOT"));
		assertThat(ids(t11.sink().delta.path("availableActions"))).containsExactly("A", "B", "C");

		Turn t12 = turn(s, "A"); // FOLLOW +1
		assertThat(t12.prompt()).contains("孩子蹲下来摸了你一下").contains(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.stages().get(1).omen()));
		assertThat(st.g).isEqualTo(1);
		turn(s, "A"); // 阶段 2 FOLLOW → g=2,第 13 回合 = 阶段 3
		assertThat(st.g).isEqualTo(2);
		Turn t14 = turn(s, "A"); // 阶段 3 INTO_BOX,行动前 g=2 → R1 在第 14 回合结算
		assertThat(st.result).isEqualTo(Path.R1);
		assertThat(st.settledTurn).isEqualTo(14);
		assertThat(st.situation).isEqualTo(Situation.NEW_HOME);
		assertThat(t14.prompt()).contains("门外响着车声时，你自己跳进了那个正被人抱走的箱子。")
				.contains(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.takenAftermath().get(0).omen()));

		assertThat(turn(s, "A").prompt()).contains(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.takenAftermath().get(1).omen()));
		assertThat(turn(s, "A").prompt()).contains(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.takenAftermath().get(2).omen()));
		Turn t17 = turn(s, "A");
		assertThat(t17.prompt()).contains(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.r1Turn17Fill().omen()))
				.contains("门外响着车声时，你自己跳进了那个正被人抱走的箱子。"); // 记忆事实此后每回合都在
		assertThat(st.slots.values()).containsExactly("MEET_FOOTSTEPS", "STAY_NEW_CORNER", "WAIT_BY_OLD_BOWL");

		// 第 18 回合起:NEW_HOME 意图池接管三槽
		turn(s, "A");
		List<String> newHome = ANIMAL_LIFE_POOLS.newHome().stream().map(PoolIntent::intent).toList();
		assertThat(newHome).containsAll(st.slots.values());
		assertThat(st.slots).hasSize(3);
		assertThat(st.situation).isEqualTo(Situation.NEW_HOME);
	}

	@Test
	void choiceSettlesByTheMappingStoredThatTurn_notByRederivingIt() {
		// 刻意存一份「与呈现不同」的映射:阶段 1 呈现 A=FOLLOW,但本回合存的是 A=OLD_SPOT。
		GameSession s = sessionAt(11, BoxSceneState.fresh());
		s.boxScene().slots.put("A", "OLD_SPOT");
		s.boxScene().slots.put("B", "SNIFF_BOX");
		s.boxScene().slots.put("C", "FOLLOW");
		Turn t = turn(s, "A");
		assertThat(t.prompt()).contains("没有人往你这边看"); // OLD_SPOT 的反馈事实
		assertThat(s.boxScene().g).isZero();                 // OLD_SPOT g+0;若反查到 FOLLOW 会是 1
	}

	@Test
	void slotViolationTriggersTheOneRepair_andStillMissingSlotsAreFilledFromTemplates() {
		GameSession s = sessionAt(10, BoxSceneState.fresh());
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(ok("A", "B"));            // 主调用:缺 C → 视同校验失败
		llm.script(tail("A", "B", "D"));     // 修复:schema 合法但仍缺 C、多 D
		Sink sink = new Sink();
		new EventLoopService(llm, prompts, mapper).execute(s, "A", sink);

		assertThat(llm.prompts).hasSize(2); // 恰好一次修复,不新增发数
		assertThat(llm.prompts.get(1)).contains("A、B、C");
		JsonNode acts = sink.delta.path("availableActions");
		assertThat(ids(acts)).containsExactly("A", "B", "C");
		assertThat(texts(acts)).containsExactly("模型写的A", "模型写的B", "回到平时趴着的地方");
	}

	@Test
	void degradedSceneTurnUsesThisStagesTemplates_andStillSettles() {
		GameSession s = sessionAt(11, BoxSceneState.fresh());
		s.boxScene().slots.put("A", "FOLLOW");
		s.boxScene().slots.put("B", "SNIFF_BOX");
		s.boxScene().slots.put("C", "OLD_SPOT");
		ScriptedLlm llm = new ScriptedLlm();
		llm.fail(new LlmException("流中断"));
		Sink sink = new Sink();
		new EventLoopService(llm, prompts, mapper).execute(s, "B", sink);

		assertThat(s.engine().turn()).isEqualTo(12);
		assertThat(s.boxScene().g).isEqualTo(1); // SNIFF_BOX 照样结算
		assertThat(texts(sink.delta.path("availableActions")))
				.containsExactly("跟紧孩子", "守在自己的碗旁边", "躲进床底"); // 阶段 2 模板,不复用上一组
		assertThat(s.boxScene().slots.values()).containsExactly("FOLLOW", "GUARD_BOWL", "UNDER_BED");
	}

	// ── 被留下 → EMPTY_HOME → LEAVE_HOME → OUTSIDE ────────────────────────

	/** R3a:OLD_SPOT, UNDER_BED, UNDER_BED, CHASE_CAR → 第 15 回合结算,余波 15–17。 */
	private GameSession leftThroughB3() {
		GameSession s = sessionAt(10, BoxSceneState.fresh());
		turn(s, "A");      // T11 阶段 1
		turn(s, "C");      // OLD_SPOT
		turn(s, "C");      // UNDER_BED(g 下限 0)
		turn(s, "C");      // UNDER_BED → T14 阶段 4,g=0 分叉
		assertThat(s.boxScene().slots.get("A")).isEqualTo("CHASE_CAR");
		turn(s, "A");      // CHASE_CAR → R3a
		assertThat(s.boxScene().result).isEqualTo(Path.R3A);
		assertThat(s.boxScene().situation).isEqualTo(Situation.EMPTY_HOME);
		turn(s, "A");      // T16 B2
		turn(s, "A");      // T17 B3
		assertThat(s.boxScene().slots.get("C")).isEqualTo(LEAVE_HOME);
		return s;
	}

	@Test
	void leaveHomeLandsOutside_andOutsideTakesEffectFromTheNextTurn() {
		GameSession s = leftThroughB3();
		Turn t18 = turn(s, "C");
		assertThat(t18.prompt()).contains(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.leaveFeedback()));
		assertThat(s.boxScene().situation).isEqualTo(Situation.OUTSIDE);
		assertThat(s.boxScene().slots).isEmpty(); // OUTSIDE 选项回到模型自由生成
		assertThat(texts(t18.sink().delta.path("availableActions"))).containsExactly("模型写的A", "模型写的B", "模型写的C");

		Turn t19 = turn(s, "A");
		assertThat(t19.prompt()).doesNotContain("availableActions 必须恰好三个");
		assertThat(s.boxScene().situation).isEqualTo(Situation.OUTSIDE);

		// 刀 2b:片段按【权威处境】选 —— 转移那一回合仍是空旧屋片段,屋外片段从下一回合起(§已决 A 第 6 条)
		assertThat(t18.prompt()).contains(EMPTY_HOME_HEADER).doesNotContain(OUTSIDE_HEADER);
		assertThat(t19.prompt()).contains(OUTSIDE_HEADER).doesNotContain(EMPTY_HOME_HEADER)
				.doesNotContain(NEW_HOME_HEADER);
	}

	private static final String NEW_HOME_HEADER = "【处境片段 · 当前处境:新屋";
	private static final String EMPTY_HOME_HEADER = "【处境片段 · 当前处境:空下来的旧屋";
	private static final String OUTSIDE_HEADER = "【处境片段 · 当前处境:屋外";

	/** 片段只看会话里的权威处境,不看回合号:同一回合号,三种处境各拿各的;结算前(处境为空)一个都不拿。 */
	@Test
	void fragmentFollowsTheAuthoritativeSituation_notTheTurnNumber() {
		for (Situation sit : Situation.values()) {
			BoxSceneState st = BoxSceneState.fresh();
			st.result = sit == Situation.NEW_HOME ? Path.R1 : Path.R3B;
			st.settledTurn = 14;
			st.situation = sit;
			String p = turn(sessionAt(29, st), "A").prompt();
			String own = switch (sit) {
				case NEW_HOME -> NEW_HOME_HEADER;
				case EMPTY_HOME -> EMPTY_HOME_HEADER;
				case OUTSIDE -> OUTSIDE_HEADER;
			};
			assertThat(p).as(sit.name()).contains(own);
			for (String other : List.of(NEW_HOME_HEADER, EMPTY_HOME_HEADER, OUTSIDE_HEADER)) {
				if (!other.equals(own)) {
					assertThat(p).as(sit + " 不得含 " + other).doesNotContain(other);
				}
			}
		}
		// 处境为空:局面开始前的新局(第 5 回合)与没有局面状态的会话(第 29 回合)都不注入任何片段
		assertThat(turn(sessionAt(5, BoxSceneState.fresh()), "A").prompt()).doesNotContain("【处境片段");
		assertThat(turn(sessionAt(29, null), "A").prompt()).doesNotContain("【处境片段");
	}

	@Test
	void unlandedLeaveHomeDoesNotTransition() {
		GameSession s = leftThroughB3();
		ScriptedLlm llm = new ScriptedLlm();
		llm.fail(new IllegalStateException("意料外故障,落地之前")); // 非 LlmException:不走降级,回合不落地
		assertThatThrownBy(() -> new EventLoopService(llm, prompts, mapper).execute(s, "C", new Sink()))
				.isInstanceOf(IllegalStateException.class);
		assertThat(s.engine().turn()).isEqualTo(17);
		assertThat(s.boxScene().situation).isEqualTo(Situation.EMPTY_HOME);
		assertThat(s.boxScene().slots.get("C")).isEqualTo(LEAVE_HOME);
	}

	@Test
	void landedButUndeliveredLeaveHome_persistedSnapshotIsAlreadyOutside() {
		GameSession s = leftThroughB3();
		s.phase().set(TurnPhase.AWAITING_ACTION);
		List<ObjectNode> persisted = new ArrayList<>();
		SessionStore store = new SessionStore() {
			@Override
			public void persist(GameSession session) {
				persisted.add(SessionDocument.encode(session, mapper));
			}

			@Override
			public List<GameSession> loadAll() {
				return List.of();
			}
		};
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(ok("A", "B", "C"));
		Sink sink = new Sink();
		sink.failDelta = true;
		new TurnStateMachine(new EventLoopService(llm, prompts, mapper), store).submitAction(s, "C", sink);

		assertThat(persisted).hasSize(1); // ADR-027:已落地 ⇒ 补写盘
		JsonNode box = persisted.get(0).path(BoxSceneState.DOC_KEY);
		assertThat(persisted.get(0).path("state").path("turn").asInt()).isEqualTo(18);
		assertThat(box.path("situation").asString()).isEqualTo("OUTSIDE");
	}

	@Test
	void degradedLeaveHome_emitsTheLeaveFactBeforeDelta_andOffersJustOutsideTemplates() {
		GameSession s = leftThroughB3();
		ScriptedLlm llm = new ScriptedLlm();
		llm.fail(new LlmException("流中断"));
		Sink sink = new Sink();
		new EventLoopService(llm, prompts, mapper).execute(s, "C", sink);

		assertThat(sink.events).containsSubsequence("narrative", "delta");
		assertThat(sink.narrative.toString()).isEqualTo(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.leaveFeedback()));
		assertThat(s.boxScene().situation).isEqualTo(Situation.OUTSIDE);
		assertThat(texts(sink.delta.path("availableActions")))
				.containsExactly("顺着楼道里的气味往前走", "回到那扇门前等一会儿", "躲进楼梯拐角的阴影里")
				.doesNotContain(BoxSceneTables.LEAVE_HOME_TEMPLATE);
		assertThat(s.engine().log().get(s.engine().log().size() - 1).path("narrative").asString())
				.contains(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.leaveFeedback())); // 下一回合的模型知道它已经出门了
	}

	@Test
	void emptyHomePoolRotates_cIsAlwaysLeaveHome_andStayingLongNeverMovesItOutside() {
		GameSession s = leftThroughB3();
		List<String> previous = null;
		for (int i = 0; i < 20; i++) {
			turn(s, "A"); // 一直不选 C
			BoxSceneState st = s.boxScene();
			assertThat(st.situation).isEqualTo(Situation.EMPTY_HOME);
			assertThat(st.slots.get("C")).isEqualTo(LEAVE_HOME);
			assertThat(List.of(st.slots.get("A"), st.slots.get("B"))).doesNotContain(LEAVE_HOME);
			List<String> ab = List.of(st.slots.get("A"), st.slots.get("B"));
			// 段起点处偏移重置,相邻可能撞;段内必不同(刀 2b:第 18 回合起分四段,起点 22 / 28 / 36)
			boolean segmentStart = LifeStageTables.of("animal_life").stages().stream()
					.anyMatch(seg -> seg.fromTurn() == s.engine().turn() && seg.fromTurn() > 18);
			if (previous != null && !segmentStart) {
				assertThat(ab).isNotEqualTo(previous);
			}
			previous = ab;
		}
	}

	// ── 习惯句 ───────────────────────────────────────────────────────────

	@Test
	void habitIsInjectedFromTurn28Only_andNeverOutside() {
		GameSession s = leftThroughB3();
		List<String> habits = ANIMAL_LIFE_POOLS.emptyHome().stream().map(PoolIntent::habit).map(BoxSceneTurn::narrated).toList();
		String prompt27 = null;
		String prompt28 = null;
		for (int n = 18; n <= 28; n++) {
			String p = turn(s, "A").prompt();
			if (n == 27) {
				prompt27 = p;
			}
			if (n == 28) {
				prompt28 = p;
			}
		}
		assertThat(habits).noneMatch(prompt27::contains);
		assertThat(habits).anyMatch(prompt28::contains);
		assertThat(s.boxScene().history).isNotEmpty().hasSizeLessThanOrEqualTo(BoxScene.HABIT_KEEP);
		assertThat(s.boxScene().history).noneMatch(p -> p.intent().equals(LEAVE_HOME));

		// 出门之后不再注入
		turn(s, "C");
		String outside = turn(s, "A").prompt();
		assertThat(habits).noneMatch(outside::contains);
	}

	// ── 消毒 ─────────────────────────────────────────────────────────────

	@Test
	void promptAndDeltaNeverCarryIntentIdsOrG_onlyTheViewOneDocumentDoes() {
		GameSession s = sessionAt(10, BoxSceneState.fresh());
		Turn t = turn(s, "A");
		turn(s, "A");
		Turn t13 = turn(s, "A");
		for (String p : List.of(t.prompt(), t13.prompt())) {
			assertThat(p).doesNotContain("FOLLOW").doesNotContain("SNIFF_BOX").doesNotContain("INTO_BOX")
					.doesNotContain("\"g\"").doesNotContain(BoxSceneState.DOC_KEY);
		}
		String delta = t13.sink().delta.toString();
		assertThat(delta).doesNotContain(BoxSceneState.DOC_KEY).doesNotContain("FOLLOW").doesNotContain("\"g\"");
		ObjectNode doc = SessionDocument.encode(s, mapper);
		assertThat(doc.path(BoxSceneState.DOC_KEY).path("g").asInt()).isEqualTo(2);
		assertThat(doc.path(BoxSceneState.DOC_KEY).path("slots").path("A").asString()).isEqualTo("INTO_BOX");
	}

	// ── 旧局与纸箱之前:与今天逐字节相同 ──────────────────────────────────

	@Test
	void legacySessionSkipsTheSceneLayerEntirely() {
		GameSession legacy = sessionAt(10, BoxSceneState.legacyMarker());
		GameSession none = sessionAt(10, null);
		Turn a = turn(legacy, "A");
		Turn b = turn(none, "A");
		// 刀 2b:旧局走旧指令(逐字节同 583abc9,见 AnimalLifeLegacyGoldenTest);新局(无局面状态)走新主干。
		// 两者都不注入局面层。
		assertThat(a.prompt()).doesNotContain(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.stages().get(0).omen())).contains("纸箱的味道");
		assertThat(b.prompt()).doesNotContain(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.stages().get(0).omen())).doesNotContain("纸箱的味道");
		assertThat(texts(a.sink().delta.path("availableActions"))).containsExactly("模型写的A", "模型写的B", "模型写的C");
		assertThat(legacy.boxScene().isLegacy()).isTrue();
		assertThat(SessionDocument.encode(legacy, mapper).path(BoxSceneState.DOC_KEY).toString())
				.isEqualTo("{\"legacy\":true}");
	}

	@Test
	void freshGameBeforeTheWindowHasAnUnchangedPrompt() {
		GameSession fresh = sessionAt(5, BoxSceneState.fresh());
		GameSession none = sessionAt(5, null);
		assertThat(turn(fresh, "A").prompt()).isEqualTo(turn(none, "A").prompt());
		assertThat(fresh.boxScene().situation).isNull();
		assertThat(fresh.boxScene().slots).isEmpty();
	}

	// ── §已决 K(F-033)· 叙事人称:端到端 ────────────────────────────────

	/** 局面块(视图 2):从标题到「请推进」之前。 */
	static String sceneBlockOf(String prompt) {
		int i = prompt.indexOf("【本回合由引擎给定的局面事实");
		int j = prompt.indexOf("\n\n请推进第 ");
		return i < 0 ? "" : prompt.substring(i, j);
	}

	@Test
	void r1Path_t13RendersTheFeedbackInSecondPerson_t14TheMemoryFact() {
		GameSession s = sessionAt(10, BoxSceneState.fresh());
		turn(s, "A"); // T11
		turn(s, "A"); // T12 FOLLOW
		Turn t13 = turn(s, "A"); // FOLLOW → 「孩子叫了它的名字」
		String block13 = sceneBlockOf(t13.prompt());
		assertThat(block13).contains("孩子叫了你的名字").doesNotContain("孩子叫了它的名字");
		Turn t14 = turn(s, "A"); // INTO_BOX → R1
		assertThat(s.boxScene().result).isEqualTo(Path.R1);
		assertThat(sceneBlockOf(t14.prompt()))
				.contains("门外响着车声时，你自己跳进了那个正被人抱走的箱子。")
				.doesNotContain("门外响着车声时，它自己跳进了那个正被人抱走的箱子。");
	}

	@Test
	void conversionIsNeverPersisted_tablesAndDocKeepTheOriginal() {
		GameSession s = sessionAt(10, BoxSceneState.fresh());
		for (int i = 0; i < 4; i++) {
			turn(s, "A"); // 走到 T14 R1
		}
		String doc = SessionDocument.encode(s, mapper).toString();
		assertThat(doc).doesNotContain("你自己跳进").doesNotContain("孩子叫了你的名字");
		assertThat(SessionDocument.encode(s, mapper).path(BoxSceneState.DOC_KEY).path("result").asString())
				.isEqualTo("R1");
		assertThat(ANIMAL_LIFE_BOX.memoryFacts().get(Path.R1))
				.isEqualTo("门外响着车声时，它自己跳进了那个正被人抱走的箱子。");
		assertThat(ANIMAL_LIFE_BOX.stages().get(1).slot("A").feedback()).isEqualTo("孩子叫了它的名字");
	}

	@Test
	void degradedLeaveNarrative_isTheConvertedLeaveFeedback_withoutIt() {
		GameSession s = leftThroughB3();
		ScriptedLlm llm = new ScriptedLlm();
		llm.fail(new LlmException("流中断"));
		Sink sink = new Sink();
		new EventLoopService(llm, prompts, mapper).execute(s, "C", sink);
		String narrative = sink.narrative.toString();
		assertThat(narrative).isEqualTo(BoxSceneTurn.narrated(ANIMAL_LIFE_BOX.leaveFeedback()))
				.doesNotContain("它");
		assertThat(s.engine().log().get(s.engine().log().size() - 1).path("narrative").asString())
				.contains(narrative).doesNotContain(ANIMAL_LIFE_BOX.leaveFeedback());
		assertThat(ANIMAL_LIFE_BOX.leaveFeedback()).contains("它"); // 数据表原文不变
	}
}
