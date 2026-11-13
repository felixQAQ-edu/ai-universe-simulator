package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.archetype.FusionSummary;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;
import com.aiuniverse.server.persistence.SessionDocument;
import com.aiuniverse.server.worldgen.GameInitService;
import com.aiuniverse.server.worldgen.InitResponse;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;
import com.aiuniverse.server.worldgen.WorldGenService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-039 · 下发投影 {@link ClientProjection}:时段标签全文、选项 tag 的各种情形、三处下发的接线,
 * 以及<b>存档里的选项文字不动、{@link QiriActionGuard} 的兜底与「缺怪点子选项」照常</b>。
 * 其他世界逐字节不变另由 {@link ClientProjectionParityTest} 守。
 */
class ClientProjectionTest {

	private static final String ODD = QiriActionGuard.ODD_IDEA;
	private static final String SMOOTH = QiriActionGuard.SMOOTH_OVER;

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	// ── 时段标签 ─────────────────────────────────────────────────────

	/** Felix 2026-11-13 定稿文案,n = 1…21、22、23、30 逐个钉全文。 */
	@Test
	void qiriTimeLabelFullTextForEveryTurn() {
		String[] expected = {
				"第一日 · 早市", "第一日 · 午市", "第一日 · 晚市",
				"第二日 · 早市", "第二日 · 午市", "第二日 · 晚市",
				"第三日 · 早市", "第三日 · 午市", "第三日 · 晚市",
				"第四日 · 早市", "第四日 · 午市", "第四日 · 晚市",
				"第五日 · 早市", "第五日 · 午市", "第五日 · 晚市",
				"第六日 · 早市", "第六日 · 午市", "第六日 · 晚市",
				"第七日 · 早市", "第七日 · 午市", "第七日 · 晚市" };
		for (int n = 1; n <= 21; n++) {
			assertThat(ClientProjection.qiriTimeLabel(n)).as("T" + n).isEqualTo(expected[n - 1]);
		}
		assertThat(ClientProjection.qiriTimeLabel(22)).isEqualTo("夜集 · 开张");
		assertThat(ClientProjection.qiriTimeLabel(23)).isEqualTo("夜集 · 收摊");
		assertThat(ClientProjection.qiriTimeLabel(30)).isEqualTo("夜集 · 收摊");
		// 间隔号是 U+00B7,左右各一个半角空格。
		assertThat(ClientProjection.qiriTimeLabel(9)).isEqualTo("第三日 · 晚市");
	}

	/** 标签与提示词时段行说的是同一回合的同一天、同一时段(换算同源),只是字不同。 */
	@Test
	void labelAndPromptClockAgreeOnDayAndPart() {
		String[] promptParts = { "早", "午", "晚" };
		String[] labelParts = { "早市", "午市", "晚市" };
		String days = "一二三四五六七";
		for (int n = 1; n <= 21; n++) {
			QiriCalendar cal = QiriCalendar.of(n);
			assertThat(TurnPromptBuilder.qiriTurnClock(n)).as("T" + n)
					.contains("第 " + cal.day() + " 天 · " + promptParts[cal.part()] + "(");
			assertThat(ClientProjection.qiriTimeLabel(n)).as("T" + n)
					.isEqualTo("第" + days.charAt(cal.day() - 1) + "日 · " + labelParts[cal.part()]);
		}
	}

	/** n = state.turn + 1:刚开局(turn 0)是第一日早市;打完 21 回合是夜集开张;22 回合没结局是收摊。 */
	@Test
	void timeLabelDescribesTheTurnAboutToBePlayed() {
		assertThat(ClientProjection.timeLabel(qiri(0))).isEqualTo("第一日 · 早市");
		assertThat(ClientProjection.timeLabel(qiri(1))).isEqualTo("第一日 · 午市");
		assertThat(ClientProjection.timeLabel(qiri(21))).isEqualTo("夜集 · 开张");
		assertThat(ClientProjection.timeLabel(qiri(22))).isEqualTo("夜集 · 收摊");
	}

	@Test
	void endedGameHasNoTimeLabel() {
		Engine e = qiri(21);
		ObjectNode parsed = mapper.createObjectNode();
		parsed.put("narrative", "夜集散了。");
		parsed.putObject("stateUpdate").put("timeline", "t");
		parsed.putArray("triggeredRuleIds");
		parsed.putArray("discoveredRuleIds");
		parsed.putArray("availableActions");
		parsed.putObject("ending").put("reached", true).put("id", "open_as_usual");
		e.apply(parsed, "A");
		assertThat(e.status()).isEqualTo("ended");
		assertThat(ClientProjection.timeLabel(e)).isNull();
	}

	@Test
	void onlyQiriIsRegisteredAndFusionOrOtherWorldsGetNothing() {
		assertThat(ClientProjection.TIME_LABELS).containsOnlyKeys("time_travel");
		assertThat(ClientProjection.ACTION_TAGS).containsOnlyKeys("time_travel");
		for (ArchetypeMeta m : registry.activeMetas()) {
			if (m.id().equals("time_travel")) {
				continue;
			}
			Engine e = QiriCoverDropTest.engine(mapper, registry, m.id(), 3, Map.of());
			assertThat(ClientProjection.timeLabel(e)).as(m.id()).isNull();
			assertThat(ClientProjection.actions(e, prefixed())).as(m.id()).isEqualTo(prefixed());
		}
		// 融合局:即使 archetypes 里出现 time_travel 也不查表(只查单体局,同 SINGLE_TURN_CLOCK)。
		Engine fused = QiriCoverDropTest.engine(mapper, registry, "time_travel", 3, Map.of());
		((ArrayNode) fused.world().get("archetypes")).add("rules_creepy");
		assertThat(ClientProjection.timeLabel(fused)).isNull();
		assertThat(ClientProjection.actions(fused, prefixed())).isEqualTo(prefixed());
	}

	// ── 选项 tag ─────────────────────────────────────────────────────

	@Test
	void prefixedOptionsLoseThePrefixAndGainATag() {
		ArrayNode out = ClientProjection.actions(qiri(3), prefixed());
		assertThat(mapper.writeValueAsString(out)).isEqualTo("["
				+ "{\"id\":\"A\",\"text\":\"吆喝一声\",\"hint\":\"街坊会看你\"},"
				+ "{\"id\":\"B\",\"text\":\"画三栏账\",\"hint\":\"邻摊大娘凑过来看\",\"tag\":\"怪点子\"},"
				+ "{\"id\":\"C\",\"text\":\"请街坊喝茶\",\"hint\":\"\",\"tag\":\"打圆场\"}]");
	}

	@Test
	void projectionNeverMutatesTheStoredActions() {
		ArrayNode stored = prefixed();
		String before = mapper.writeValueAsString(stored);
		ArrayNode out = ClientProjection.actions(qiri(3), stored);
		assertThat(out).isNotSameAs(stored);
		assertThat(mapper.writeValueAsString(stored)).isEqualTo(before);
	}

	@Test
	void nullStoredGivesEmptyArray() {
		assertThat(ClientProjection.actions(qiri(3), null)).isEmpty();
	}

	/** 命中与否和 {@link QiriActionGuard#prefixOf} 完全一致(用 QiriActionGuardTest 的同一组样例)。 */
	@Test
	void hitIsEquivalentToPrefixOf() {
		List<String> samples = List.of("【怪点子】削取号竹牌", "【打圆场】请街坊喝茶", "【怪点子】 削取号竹牌", "削取号竹牌【怪点子】",
				"先吆喝一声,再【打圆场】", "[怪点子]削取号竹牌", "［怪点子］削取号竹牌", "「打圆场」请街坊喝茶", " 【怪点子】削取号竹牌",
				"　【打圆场】请街坊喝茶", "【 怪点子 】削取号竹牌", "怪点子:削取号竹牌", "吆喝一声");
		for (String text : samples) {
			ArrayNode stored = mapper.createArrayNode();
			stored.addObject().put("id", "A").put("text", text).put("hint", "");
			JsonNode out = ClientProjection.actions(qiri(3), stored).get(0);
			String prefix = QiriActionGuard.prefixOf(text);
			assertThat(out.has("tag")).as(text).isEqualTo(prefix != null);
			if (prefix == null) {
				assertThat(out.get("text").asString()).as(text).isEqualTo(text);
			} else {
				assertThat(out.get("tag").asString()).as(text).isEqualTo(ODD.equals(prefix) ? "怪点子" : "打圆场");
				assertThat(out.get("text").asString()).as(text).isEqualTo("削取号竹牌".equals(text.substring(prefix.length()).strip())
						? "削取号竹牌" : "请街坊喝茶");
			}
		}
	}

	@Test
	void whitespaceAfterPrefixIsDroppedIncludingFullWidth() {
		for (String gap : List.of(" ", "　", " 　 ")) {
			ArrayNode stored = mapper.createArrayNode();
			stored.addObject().put("id", "A").put("text", ODD + gap + "削取号竹牌");
			assertThat(ClientProjection.actions(qiri(3), stored).get(0).get("text").asString()).isEqualTo("削取号竹牌");
		}
	}

	/** 只有前缀、后面没有字:不算命中,原样下发、不带 tag(不下发一个没有字的按钮)。 */
	@Test
	void prefixOnlyTextIsLeftAlone() {
		for (String text : List.of(ODD, SMOOTH + "  ")) {
			ArrayNode stored = mapper.createArrayNode();
			stored.addObject().put("id", "A").put("text", text).put("hint", "");
			JsonNode out = ClientProjection.actions(qiri(3), stored).get(0);
			assertThat(out.get("text").asString()).isEqualTo(text);
			assertThat(out.has("tag")).isFalse();
		}
	}

	/** tag 只来自服务端登记表:模型自己写的 tag 键一律丢弃,所有世界都如此。 */
	@Test
	void modelSuppliedTagIsDroppedInEveryWorld() {
		for (String id : List.of("time_travel", "rules_creepy", "cyberpunk")) {
			ArrayNode stored = mapper.createArrayNode();
			stored.addObject().put("id", "A").put("text", "吆喝一声").put("tag", "模型乱写的").put("hint", "");
			stored.addObject().put("id", "B").put("text", ODD + "画三栏账").put("tag", "模型乱写的");
			ArrayNode out = ClientProjection.actions(QiriCoverDropTest.engine(mapper, registry, id, 3, Map.of()), stored);
			assertThat(out.get(0).has("tag")).as(id).isFalse();
			if (id.equals("time_travel")) {
				assertThat(out.get(1).get("tag").asString()).isEqualTo("怪点子");
			} else {
				assertThat(out.get(1).has("tag")).as(id).isFalse();
				assertThat(out.get(1).get("text").asString()).as(id).isEqualTo(ODD + "画三栏账");
			}
		}
	}

	// ── 接线:三处下发 + 存档不动 + 兜底照常 ───────────────────────────

	private static final class ScriptedLlm implements LlmClient {
		final Deque<String> responses = new ArrayDeque<>();

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			String r = responses.poll();
			if (r == null) {
				throw new LlmException("脚本耗尽");
			}
			sink.onToken(r);
		}
	}

	private static final class DeltaSink implements TurnEventSink {
		ObjectNode delta;
		final List<String> errors = new ArrayList<>();

		@Override public void narrative(String text) { }
		@Override public void delta(ObjectNode d) { delta = d; }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String code, String msg) { errors.add(code); }
	}

	private static final String WIRE_ACTIONS = "[{\"id\":\"A\",\"text\":\"吆喝一声\",\"hint\":\"街坊会看你\"},"
			+ "{\"id\":\"B\",\"text\":\"【怪点子】画三栏账\",\"hint\":\"邻摊大娘凑过来看\"},"
			+ "{\"id\":\"C\",\"text\":\"【打圆场】请街坊喝茶\",\"hint\":\"\"}]";

	private static String tail(int capital, int regulars, int cover) {
		return "{\"stateUpdate\":{\"capital\":" + capital + ",\"regulars\":" + regulars + ",\"cover\":" + cover
				+ ",\"timeline\":\"开摊\"},\"triggeredRuleIds\":[],\"discoveredRuleIds\":[],"
				+ "\"availableActions\":" + WIRE_ACTIONS + ",\"ending\":null}";
	}

	/**
	 * 回合 delta、续局 state 都带两个字段;同一时刻 {@code session.currentActions} 与落盘文档里<b>前缀原样还在</b>,
	 * 且都没有 {@code tag} / {@code timeLabel};下一回合选【怪点子】,兜底 issue 照常记下。
	 */
	@Test
	void deltaAndStateCarryBothFieldsWhileSessionAndSaveKeepThePrefix() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		List<AttributeAxis> axes = registry.resolveAxes(List.of("time_travel"));
		Engine seed = qiri(0);
		ArrayNode initial = mapper.createArrayNode();
		initial.addObject().put("id", "A").put("text", "吆喝一声").put("hint", "");
		initial.addObject().put("id", "B").put("text", "原地不动").put("hint", "");
		GameSession session = sessions.create("save-qiri", seed.world(), initial, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		ScriptedLlm llm = new ScriptedLlm();
		EventLoopService loop = new EventLoopService(llm, new TurnPromptBuilder(registry), mapper);
		GameInitService init = new GameInitService(
				new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper), sessions, registry, mapper);

		// 第 1 回合(早回合):模型回一组带两种前缀的选项。
		llm.responses.add("天刚亮。" + SentinelSplitter.SENTINEL + tail(48, 52, 50));
		DeltaSink s1 = new DeltaSink();
		loop.execute(session, "A", s1);
		assertThat(s1.errors).isEmpty();
		assertThat(session.engine().turn()).isEqualTo(1);

		// 下发:去前缀、带 tag、带时段标签(打完第 1 回合 → 正要打第 2 回合)。
		String expectedActions = "[{\"id\":\"A\",\"text\":\"吆喝一声\",\"hint\":\"街坊会看你\"},"
				+ "{\"id\":\"B\",\"text\":\"画三栏账\",\"hint\":\"邻摊大娘凑过来看\",\"tag\":\"怪点子\"},"
				+ "{\"id\":\"C\",\"text\":\"请街坊喝茶\",\"hint\":\"\",\"tag\":\"打圆场\"}]";
		assertThat(mapper.writeValueAsString(s1.delta.get("availableActions"))).isEqualTo(expectedActions);
		assertThat(s1.delta.get("timeLabel").asString()).isEqualTo("第一日 · 午市");
		InitResponse state = init.resume("save-qiri");
		assertThat(mapper.writeValueAsString(state.availableActions())).isEqualTo(expectedActions);
		assertThat(state.timeLabel()).isEqualTo("第一日 · 午市");
		assertThat(mapper.writeValueAsString(state)).contains("\"timeLabel\":\"第一日 · 午市\"");

		// 存档:前缀原样,没有 tag / timeLabel。
		assertThat(mapper.writeValueAsString(session.currentActions())).isEqualTo(WIRE_ACTIONS);
		String doc = mapper.writeValueAsString(SessionDocument.encode(session, mapper));
		assertThat(doc).contains("\"currentActions\":" + WIRE_ACTIONS).doesNotContain("\"tag\"").doesNotContain("timeLabel");

		// 旧存档回载(文档里本来就没有这两个字段):回载后的会话照样现算得出。
		GameSession reloaded = SessionDocument.decode("save-qiri", mapper.readTree(doc), mapper, registry);
		assertThat(ClientProjection.timeLabel(reloaded.engine())).isEqualTo("第一日 · 午市");
		assertThat(mapper.writeValueAsString(ClientProjection.actions(reloaded.engine(), reloaded.currentActions())))
				.isEqualTo(expectedActions);

		// 第 2 回合选 B(【怪点子】):模型没按规矩落数(底细没降、摊本没涨)→ 兜底照常改写并记 issue。
		llm.responses.add("你画起了账。" + SentinelSplitter.SENTINEL + tail(48, 52, 50));
		DeltaSink s2 = new DeltaSink();
		loop.execute(session, "B", s2);
		assertThat(session.engine().issues()).contains("T2 怪点子兜底 capital 48→56", "T2 怪点子兜底 cover 50→44");
		// 「缺怪点子选项」看的是存档里的文字:第 1 回合玩家看到的是开局那组(没有【怪点子】)→ 记;
		// 第 2 回合看到的那组在存档里带前缀(下发时已去掉)→ 不记。
		assertThat(session.engine().issues().stream().filter(i -> i.contains("缺怪点子选项")))
				.containsExactly("T1 缺怪点子选项");
		assertThat(s2.delta.get("timeLabel").asString()).isEqualTo("第一日 · 晚市");
	}

	/** 下发的选项里没有前缀了,但「缺怪点子选项」看的是存档:模型真没给时才记,给了就不记。 */
	@Test
	void missingOddIdeaCountStillReadsTheStoredText() {
		ArrayNode stored = prefixed();
		assertThat(QiriActionGuard.hasOddIdea(stored)).isTrue();
		assertThat(QiriActionGuard.hasOddIdea(ClientProjection.actions(qiri(3), stored)))
				.as("投影后的副本里前缀已去掉 —— 所以兜底绝不能读投影结果").isFalse();
	}

	/** init 响应:序列化后带 timeLabel 与 tag;非登记世界的 init 不出现 timeLabel 键(连 null 也没有)。 */
	@Test
	void initResponseSerialization() {
		Engine e = qiri(0);
		InitResponse withLabel = new InitResponse("s", e.toClientState(), "开场", ClientProjection.actions(e, prefixed()),
				mapper.createArrayNode(), ClientProjection.timeLabel(e));
		assertThat(mapper.writeValueAsString(withLabel)).contains("\"timeLabel\":\"第一日 · 早市\"").contains("\"tag\":\"怪点子\"");
		InitResponse without = new InitResponse("s", e.toClientState(), "开场", prefixed(), mapper.createArrayNode(), null);
		assertThat(mapper.writeValueAsString(without)).doesNotContain("timeLabel");
	}

	// ── 守卫 ─────────────────────────────────────────────────────────

	/** delta 的顶层键由数值轴与结构键共用:任何轴的 key 都不得与结构键重名(含新加的 timeLabel)。 */
	@Test
	void noAxisKeyCollidesWithDeltaStructuralKeys() {
		Set<String> structural = Set.of("turn", "status", "discoveredRules", "availableActions", "timeLabel");
		for (ArchetypeMeta m : registry.activeMetas()) {
			for (AttributeAxis a : m.attributes()) {
				assertThat(structural).as(m.id()).doesNotContain(a.key());
			}
		}
		for (FusionSummary f : registry.listFusionCombos()) {
			for (AttributeAxis a : registry.fusedAxes(f.host(), f.foreign())) {
				assertThat(structural).as(f.key()).doesNotContain(a.key());
			}
		}
	}

	// ── 夹具 ─────────────────────────────────────────────────────────

	private Engine qiri(int turn) {
		return QiriCoverDropTest.engine(mapper, registry, "time_travel", turn, Map.of());
	}

	private ArrayNode prefixed() {
		ArrayNode a = mapper.createArrayNode();
		a.addObject().put("id", "A").put("text", "吆喝一声").put("hint", "街坊会看你");
		a.addObject().put("id", "B").put("text", ODD + "画三栏账").put("hint", "邻摊大娘凑过来看");
		a.addObject().put("id", "C").put("text", SMOOTH + "请街坊喝茶").put("hint", "");
		return a;
	}
}
