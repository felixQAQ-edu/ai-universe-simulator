package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.persistence.TurnTrace;
import com.aiuniverse.server.persistence.TurnTraceCodec;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-031 刀 2 · 测试面 2(档 1 往返)/ 测试面 6(跨版本与差异报告)+ 轨迹编解码。
 *
 * <p>轨迹全部由刀 1 的真实采集在测试里跑出来({@link TraceScenarios}),经 JSON 往返后回放 ——
 * 回放读到的是「写出去再读回来」的那一份,而不是内存里那个对象。
 */
class TraceReplayTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ArchetypeRegistry REGISTRY = new ArchetypeRegistry();
	private static final Map<String, TraceScenarios.Run> RUNS = new TraceScenarios(MAPPER, REGISTRY).all();

	private static EventLoopService replayService() {
		return new EventLoopService(new TurnTraceTest.ScriptedLlm(), new TurnPromptBuilder(REGISTRY), MAPPER);
	}

	private static TraceReplayer replayer(EventLoopService service) {
		return new TraceReplayer(service, MAPPER, REGISTRY, TurnTraceCollector.COMMIT);
	}

	private static TurnTrace roundTrip(TurnTrace t) {
		return TurnTraceCodec.decode(TurnTraceCodec.encode(t, MAPPER), MAPPER);
	}

	static Stream<String> scenarioNames() {
		return RUNS.keySet().stream();
	}

	// ── 测试面 2:档 1 往返 ───────────────────────────────────────────────

	@ParameterizedTest(name = "{0}")
	@MethodSource("scenarioNames")
	void everyRecordedTurnReplaysConsistently(String scenario) {
		TraceScenarios.Run run = RUNS.get(scenario);
		TraceReplayer replayer = replayer(replayService());
		for (TurnTrace t : run.traces()) {
			TraceReplayer.Report report = replayer.replay(roundTrip(t));
			assertThat(report.differences()).as("%s T%d→%d 回放差异", scenario, t.turnBefore(), t.turnBefore() + 1)
					.isEmpty();
			assertThat(report.outcome()).isEqualTo(TraceReplayer.Outcome.CONSISTENT);
		}
	}

	/** 场景前提:每个场景确实走到了它声称覆盖的那一类回合(否则「回放一致」什么也没证明)。 */
	@Test
	void scenariosCoverTheAdrCategories() {
		assertThat(RUNS.get("normal").last().path()).isEqualTo(TurnTrace.PATH_SETTLED);
		assertThat(RUNS.get("repaired").last().repairErrors()).isNotEmpty();
		assertThat(RUNS.get("repaired").last().path()).isEqualTo(TurnTrace.PATH_SETTLED);
		assertThat(RUNS.get("degraded_stream_interrupted").last().degradeReason()).isEqualTo("stream_interrupted");
		assertThat(RUNS.get("degraded_no_structured_tail").last().degradeReason()).isEqualTo("no_structured_tail");
		assertThat(RUNS.get("degraded_repair_failed").last().degradeReason()).isEqualTo("repair_failed");
		assertThat(RUNS.get("ending").session().engine().status()).isEqualTo("ended");
		// 纸箱:最后两回合 = 离开回合(EMPTY_HOME → OUTSIDE)与屋外第一回合
		assertThat(RUNS.get("box_scene_through_leave").session().boxScene().situation)
				.isEqualTo(BoxScene.Situation.OUTSIDE);
		assertThat(RUNS.get("box_scene_through_leave").traces().get(7).pre().path("boxScene").path("situation")
				.asString()).isEqualTo("EMPTY_HOME");
		TraceScenarios.Run leaveDegraded = RUNS.get("box_scene_leave_degraded");
		assertThat(leaveDegraded.last().path()).isEqualTo(TurnTrace.PATH_DEGRADED);
		assertThat(leaveDegraded.session().boxScene().situation).isEqualTo(BoxScene.Situation.OUTSIDE);
		assertThat(leaveDegraded.session().engine().log().getLast().path("narrative").asString())
				.as("离开回合降级补了离开叙事,而轨迹里不存它(由编排重算)")
				.startsWith(leaveDegraded.last().streamedNarrative())
				.isNotEqualTo(leaveDegraded.last().streamedNarrative());
		assertThat(RUNS.get("lifetime_exit_action").session().currentActions().findValues("id").stream()
				.map(n -> n.asString()).toList()).contains(LifeStageTable.EXIT_ACTION_ID);
		assertThat(RUNS.get("normal_with_leak").last().parsed().path("narrative").asString()).contains("hiddenLogic");
	}

	// ── 测试面 6:落账行为一变,回放报告不一致;跨版本只报告不失败 ──────────

	/** 一处落账行为被改动(替身:apply 之前把 hp 少记 1)。 */
	private static EventLoopService alteredLanding() {
		return new EventLoopService(new TurnTraceTest.ScriptedLlm(), new TurnPromptBuilder(REGISTRY), MAPPER) {
			@Override
			void landSettled(GameSession session, ObjectNode parsed, String actionId, BoxSceneTurn.Plan scene,
					java.util.function.Consumer<List<String>> afterLanding) {
				ObjectNode upd = (ObjectNode) parsed.get("stateUpdate");
				upd.put("hp", upd.get("hp").asDouble() - 1);
				super.landSettled(session, parsed, actionId, scene, afterLanding);
			}
		};
	}

	@Test
	void changedLandingBehaviourIsReportedAsInconsistentWithTheDifferences() {
		TurnTrace t = roundTrip(RUNS.get("normal").last());
		TraceReplayer.Report report = replayer(alteredLanding()).replay(t);
		assertThat(report.outcome()).isEqualTo(TraceReplayer.Outcome.INCONSISTENT);
		assertThat(report.failed()).isTrue();
		assertThat(report.differences()).anyMatch(d -> d.startsWith("post.sha256 记录="))
				.anyMatch(d -> d.equals("轴 hp 记录=85.0 回放=84.0"));
		assertThat(report.replayedSha()).isNotEqualTo(report.recordedSha());
	}

	@Test
	void traceFromAnotherCommitIsMarkedCrossVersionNotFailed() {
		TurnTrace t = withCommit(roundTrip(RUNS.get("normal").last()), "0000000");
		TraceReplayer.Report same = replayer(replayService()).replay(t);
		assertThat(same.outcome()).isEqualTo(TraceReplayer.Outcome.CROSS_VERSION);
		assertThat(same.failed()).isFalse();
		assertThat(same.differences()).isEmpty();

		TraceReplayer.Report changed = replayer(alteredLanding()).replay(t);
		assertThat(changed.outcome()).as("跨版本的差异是报告,不是失败").isEqualTo(TraceReplayer.Outcome.CROSS_VERSION);
		assertThat(changed.failed()).isFalse();
		assertThat(changed.differences()).as("跨版本照样列出差异").contains("轴 hp 记录=85.0 回放=84.0");
	}

	private static TurnTrace withCommit(TurnTrace t, String commit) {
		return new TurnTrace(t.schema(), t.saveId(), t.turnBefore(), t.recordedAt(), commit, t.actionId(), t.path(),
				t.pre(), t.parsed(), t.degradeReason(), t.streamedNarrative(), t.promptSha256(), t.usage(),
				t.durMs(), t.repairErrors(), t.post());
	}

	// ── 编解码 ───────────────────────────────────────────────────────────

	@ParameterizedTest(name = "{0}")
	@MethodSource("scenarioNames")
	void codecRoundTripIsByteStableAndLossless(String scenario) {
		for (TurnTrace t : RUNS.get(scenario).traces()) {
			String line = TurnTraceCodec.encode(t, MAPPER);
			assertThat(line).as("一条轨迹一行").doesNotContain("\n");
			TurnTrace back = TurnTraceCodec.decode(line, MAPPER);
			assertSameTrace(back, t);
			assertThat(TurnTraceCodec.encode(back, MAPPER)).as("encode → decode → encode 字节相同").isEqualTo(line);
		}
	}

	/**
	 * 逐字段相等。{@code pre} / {@code parsed} 按序列化文本比:JSON 树的相等对数值节点类型敏感
	 * (落账时写入的 {@code LongNode(90)} 读回来是 {@code IntNode(90)}),而轨迹要保住的是文本,不是 Java 节点类型。
	 */
	private static void assertSameTrace(TurnTrace back, TurnTrace t) {
		assertThat(MAPPER.writeValueAsString(back.pre())).isEqualTo(MAPPER.writeValueAsString(t.pre()));
		assertThat(back.parsed() == null ? null : MAPPER.writeValueAsString(back.parsed()))
				.isEqualTo(t.parsed() == null ? null : MAPPER.writeValueAsString(t.parsed()));
		assertThat(withNodes(back, null, null)).as("其余字段逐字段相等").isEqualTo(withNodes(t, null, null));
	}

	private static TurnTrace withNodes(TurnTrace t, ObjectNode pre, ObjectNode parsed) {
		return new TurnTrace(t.schema(), t.saveId(), t.turnBefore(), t.recordedAt(), t.commit(), t.actionId(),
				t.path(), pre, parsed, t.degradeReason(), t.streamedNarrative(), t.promptSha256(), t.usage(),
				t.durMs(), t.repairErrors(), t.post());
	}

	@Test
	void unknownSchemaIsRejected() {
		ObjectNode n = (ObjectNode) MAPPER.readTree(TurnTraceCodec.encode(RUNS.get("normal").last(), MAPPER));
		n.put("schema", TurnTrace.SCHEMA + 1);
		assertThatThrownBy(() -> TurnTraceCodec.decode(MAPPER.writeValueAsString(n), MAPPER))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未知轨迹 schema 2");
	}

	@Test
	void missingOrUnknownKeyIsRejectedNotDefaulted() {
		ObjectNode n = (ObjectNode) MAPPER.readTree(TurnTraceCodec.encode(RUNS.get("normal").last(), MAPPER));
		ObjectNode missing = n.deepCopy();
		missing.remove("durMs");
		assertThatThrownBy(() -> TurnTraceCodec.decode(MAPPER.writeValueAsString(missing), MAPPER))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("缺键 durMs");
		ObjectNode extra = n.deepCopy();
		extra.put("rawTail", "…");
		assertThatThrownBy(() -> TurnTraceCodec.decode(MAPPER.writeValueAsString(extra), MAPPER))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不认识的键 rawTail");
	}
}
