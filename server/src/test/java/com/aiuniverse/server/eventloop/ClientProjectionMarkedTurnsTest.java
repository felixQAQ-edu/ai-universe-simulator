package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
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

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-040 决策 3 · 下发投影 {@link ClientProjection#markedTurns}(验证 D):升序去重、只认本前缀、已结束的局照常给、
 * 空清单 / 未登记世界 / 融合局不写键;{@code init} / {@code state} / 回合 {@code delta} 三处接线;存档往返后照样解析得出。
 * 其他世界与没升过档的《寻常》逐字节不变由 {@link ClientProjectionParityTest}(ADR-039 基线,未改)守。
 */
class ClientProjectionMarkedTurnsTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@Test
	void turnsAreAscendingDedupedAndOnlyFromTheMarkerPrefix() {
		Engine e = xun(20, 50);
		e.recordIssue("T9 牵挂升档 50→62");
		e.recordIssue("T4 ties 跳变过大 5->60(需复核)");
		e.recordIssue("T3 牵挂升档 20→35");
		e.recordIssue("T9 牵挂升档 50→62");
		e.recordIssue("T5 画像向上跨档 29→30");
		assertThat(ClientProjection.markedTurns(e)).containsExactly(3, 9);
	}

	@Test
	void emptyListMeansNoKey() {
		assertThat(ClientProjection.markedTurns(xun(20, 50))).isNull();
		Engine onlyOthers = xun(20, 50);
		onlyOthers.recordIssue("T4 ties 跳变过大 5->60(需复核)");
		assertThat(ClientProjection.markedTurns(onlyOthers)).isNull();
	}

	@Test
	void endedGameStillCarriesTheList() {
		Engine e = xun(30, 50);
		e.recordIssue("T12 牵挂升档 28→33");
		ObjectNode doc = e.toPersistedState();
		((ObjectNode) doc.get("state")).put("status", "ended");
		Engine ended = QiriCoverDropTest.restore(mapper, registry, doc);
		assertThat(ended.status()).isEqualTo("ended");
		assertThat(ClientProjection.markedTurns(ended)).containsExactly(12);
		assertThat(ClientProjection.timeLabel(ended)).isNull(); // 对照:timeLabel 已结束不给
	}

	@Test
	void unregisteredWorldsAndFusionGetNothingEvenWithTheLine() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			if (m.id().equals("life_sim")) {
				continue;
			}
			Engine e = QiriCoverDropTest.engine(mapper, registry, m.id(), 10, Map.of());
			e.recordIssue("T3 牵挂升档 20→35");
			assertThat(ClientProjection.markedTurns(e)).as(m.id()).isNull();
		}
		Engine fused = xun(10, 50);
		fused.recordIssue("T3 牵挂升档 20→35");
		((ArrayNode) fused.world().get("archetypes")).add("cultivation");
		assertThat(ClientProjection.markedTurns(fused)).isNull();
	}

	@Test
	void initResponseSerialization() {
		Engine e = xun(14, 33);
		e.recordIssue("T14 牵挂升档 28→33");
		InitResponse with = new InitResponse("s", e.toClientState(), "开场", mapper.createArrayNode(),
				mapper.createArrayNode(), null, ClientProjection.markedTurns(e));
		assertThat(mapper.writeValueAsString(with)).endsWith(",\"markedTurns\":[14]}");
		InitResponse without = new InitResponse("s", e.toClientState(), "开场", mapper.createArrayNode(),
				mapper.createArrayNode(), null, null);
		assertThat(mapper.writeValueAsString(without)).doesNotContain("markedTurns");
	}

	/** 三处接线:升档那一回合的 delta、之后的 state、存档回载都带;没升档的回合 delta 不带这个键直到第一次升档。 */
	@Test
	void deltaStateAndReloadCarryTheList() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		List<AttributeAxis> axes = registry.resolveAxes(List.of("life_sim"));
		Engine seed = xun(0, 20);
		ArrayNode initial = mapper.createArrayNode();
		initial.addObject().put("id", "A").put("text", "去看看他").put("hint", "");
		initial.addObject().put("id", "B").put("text", "改天再说").put("hint", "");
		GameSession session = sessions.create("save-xun", seed.world(), initial, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		ScriptedLlm llm = new ScriptedLlm();
		EventLoopService loop = new EventLoopService(llm, new TurnPromptBuilder(registry), mapper);
		GameInitService init = new GameInitService(
				new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper), sessions, registry, mapper);

		// 第 1 回合:牵挂 20 → 25,同档,不写键。
		llm.responses.add("那年夏天很长。" + SentinelSplitter.SENTINEL + tail(25));
		DeltaSink s1 = new DeltaSink();
		loop.execute(session, "A", s1);
		assertThat(s1.errors).isEmpty();
		assertThat(s1.delta.has("markedTurns")).isFalse();
		assertThat(mapper.writeValueAsString(init.resume("save-xun"))).doesNotContain("markedTurns");

		// 第 2 回合:25 → 33,轻身 → 有所系。
		llm.responses.add("你搬回了老房子。" + SentinelSplitter.SENTINEL + tail(33));
		DeltaSink s2 = new DeltaSink();
		loop.execute(session, "A", s2);
		assertThat(s2.errors).isEmpty();
		assertThat(session.engine().turn()).isEqualTo(2);
		assertThat(mapper.writeValueAsString(s2.delta.get("markedTurns"))).isEqualTo("[2]");
		InitResponse state = init.resume("save-xun");
		assertThat(state.markedTurns()).containsExactly(2);
		assertThat(mapper.writeValueAsString(state)).contains("\"markedTurns\":[2]");

		// 第 3 回合:同档,清单不变(仍是 [2])。
		llm.responses.add("日子照常。" + SentinelSplitter.SENTINEL + tail(40));
		DeltaSink s3 = new DeltaSink();
		loop.execute(session, "A", s3);
		assertThat(mapper.writeValueAsString(s3.delta.get("markedTurns"))).isEqualTo("[2]");

		// 存档往返:文档里没有 markedTurns 字样(只有 issue 那一行),回载后照样现算得出。
		String doc = mapper.writeValueAsString(SessionDocument.encode(session, mapper));
		assertThat(doc).doesNotContain("markedTurns").contains("T2 牵挂升档 25→33");
		GameSession reloaded = SessionDocument.decode("save-xun", mapper.readTree(doc), mapper, registry);
		assertThat(ClientProjection.markedTurns(reloaded.engine())).containsExactly(2);
	}

	// ── 夹具 ─────────────────────────────────────────────────────────

	private Engine xun(int turn, int ties) {
		return QiriCoverDropTest.engine(mapper, registry, "life_sim", turn, Map.of("ties", ties));
	}

	private static String tail(int ties) {
		return "{\"stateUpdate\":{\"vigor\":60,\"longing\":50,\"crossroads\":50,\"ties\":" + ties
				+ ",\"timeline\":\"t\"},\"triggeredRuleIds\":[],\"discoveredRuleIds\":[],"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"去看看他\",\"hint\":\"\"},"
				+ "{\"id\":\"B\",\"text\":\"改天再说\",\"hint\":\"\"}],\"ending\":null}";
	}

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
}
