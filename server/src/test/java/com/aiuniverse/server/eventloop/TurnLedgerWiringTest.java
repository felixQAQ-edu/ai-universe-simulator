package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.persistence.SessionStore;
import com.aiuniverse.server.persistence.TurnLedger;
import com.aiuniverse.server.quota.QuotaGate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-026 刀 1 · {@link TurnLedger} 在 {@link TurnStateMachine} 里的两处接线(不需要 DB)。
 *
 * <ul>
 *   <li>受理只在 CAS 成功之后:配额拒绝 / 忙态拒绝都<b>不</b>受理(ADR-026 决策 1);</li>
 *   <li>失败只在「未落地」分支(ADR-027 决策 5):已落地后抛不调 {@code failed} —— 那一回合由补写的
 *       persist 关掉受理行;</li>
 *   <li>{@code failed} 在放回相位<b>之前</b>:放回之后另一线程即可抢到 CAS、受理下一回合,
 *       晚一步的 {@code failed} 就可能记到下一回合头上。</li>
 * </ul>
 */
class TurnLedgerWiringTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 记录调用顺序;每条带调用那一刻的相位,用来钉「failed 在放回相位之前」。 */
	private static final class RecordingLedger implements TurnLedger {
		final List<String> calls = new ArrayList<>();

		@Override
		public void accept(GameSession session, String actionId) {
			calls.add("accept:" + actionId + "@" + session.engine().turn() + "/" + session.phase().get());
		}

		@Override
		public void failed(GameSession session) {
			calls.add("failed/" + session.phase().get());
		}
	}

	private static final class NullSink implements TurnEventSink {
		final List<String> errors = new ArrayList<>();

		@Override public void narrative(String text) { }
		@Override public void delta(ObjectNode d) { }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String code, String message) { errors.add(code); }
	}

	private GameSession session() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		ArrayNode actions = world.putArray("availableActions");
		actions.addObject().put("id", "A").put("text", "查看告示");
		return new GameSession("save-26", new Engine(world, mapper), actions);
	}

	private TurnStateMachine fsm(TurnExecutor executor, QuotaGate quota, TurnLedger ledger) {
		return new TurnStateMachine(executor, SessionStore.NOOP, quota, ledger);
	}

	@Test
	void normalTurnAcceptsOnceAfterCasAndNeverFails() {
		RecordingLedger ledger = new RecordingLedger();
		fsm((s, a, k) -> {
			s.engine().applyNoOp("灯闪了一下。", a);
			return new TurnResult(false);
		}, QuotaGate.NOOP, ledger).submitAction(session(), "A", new NullSink());

		assertThat(ledger.calls).as("受理时相位已是 GENERATING(CAS 之后),base = 落地前的回合号")
				.containsExactly("accept:A@0/GENERATING");
	}

	@Test
	void busyRejectionDoesNotAccept() {
		RecordingLedger ledger = new RecordingLedger();
		GameSession s = session();
		s.phase().set(TurnPhase.GENERATING); // 另一线程正在跑这一局
		NullSink sink = new NullSink();
		fsm((ss, a, k) -> new TurnResult(false), QuotaGate.NOOP, ledger).submitAction(s, "A", sink);

		assertThat(sink.errors).containsExactly("busy");
		assertThat(ledger.calls).as("CAS 失败 → 不受理(否则留孤行)").isEmpty();
	}

	@Test
	void quotaRejectionDoesNotAccept() {
		RecordingLedger ledger = new RecordingLedger();
		QuotaGate denyAll = new QuotaGate() {
			@Override public Decision checkInit(ClientKey client) { return Decision.deny("满"); }
			@Override public Decision checkTurn(ClientKey client) { return Decision.deny("满"); }
			@Override public void record(com.aiuniverse.server.llm.LlmUsage usage) { }
		};
		NullSink sink = new NullSink();
		fsm((ss, a, k) -> new TurnResult(false), denyAll, ledger).submitAction(session(), "A", sink);

		assertThat(sink.errors).containsExactly("quota_exceeded");
		assertThat(ledger.calls).as("配额拒绝相位零触碰,受理记录也不该有").isEmpty();
	}

	@Test
	void notLandedThrowMarksFailedBeforeReleasingPhase() {
		RecordingLedger ledger = new RecordingLedger();
		fsm((s, a, k) -> {
			throw new IllegalStateException("叙事中途断流");
		}, QuotaGate.NOOP, ledger).submitAction(session(), "A", new NullSink());

		assertThat(ledger.calls).containsExactly("accept:A@0/GENERATING", "failed/GENERATING");
	}

	@Test
	void landedThrowDoesNotMarkFailed() {
		RecordingLedger ledger = new RecordingLedger();
		fsm((s, a, k) -> {
			s.engine().applyNoOp("灯闪了一下。", a); // 已落地
			throw new IllegalStateException("delta 写失败");
		}, QuotaGate.NOOP, ledger).submitAction(session(), "A", new NullSink());

		assertThat(ledger.calls).as("已落地分支不调 failed(ADR-027 决策 5)").containsExactly("accept:A@0/GENERATING");
	}
}
