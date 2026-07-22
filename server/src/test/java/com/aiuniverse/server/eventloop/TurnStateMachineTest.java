package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.persistence.SessionStore;
import com.aiuniverse.server.quota.QuotaGate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ③ 状态机守卫(确定性,stub executor,零 LLM 零流式)。
 */
class TurnStateMachineTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private GameSession session() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		ArrayNode actions = world.putArray("availableActions");
		actions.addObject().put("id", "A").put("text", "查看告示");
		actions.addObject().put("id", "B").put("text", "离开");
		return new GameSession("save-1", new Engine(world, mapper), actions);
	}

	/** 记录调用次数 + 返回值可控的 stub。 */
	private static final class StubExecutor implements TurnExecutor {
		final AtomicInteger calls = new AtomicInteger();
		volatile boolean returnEnded = false;
		volatile CountDownLatch enter; // 非空则 execute 进入时 countDown 并阻塞在 hold 上
		volatile CountDownLatch hold;

		@Override
		public TurnResult execute(GameSession s, String actionId, TurnEventSink sink) {
			calls.incrementAndGet();
			if (enter != null) {
				enter.countDown();
				try {
					hold.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return new TurnResult(returnEnded);
		}
	}

	private static final class RecordingSink implements TurnEventSink {
		final List<String> errors = new ArrayList<>();

		@Override public void narrative(String text) { }
		@Override public void delta(ObjectNode d) { }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String code, String message) { errors.add(code); }
	}

	@Test
	void legalActionOngoingReturnsToAwaiting() {
		GameSession s = session();
		StubExecutor ex = new StubExecutor();
		RecordingSink sink = new RecordingSink();
		new TurnStateMachine(ex).submitAction(s, "A", sink);
		assertThat(ex.calls).hasValue(1);
		assertThat(sink.errors).isEmpty();
		assertThat(s.phase()).hasValue(TurnPhase.AWAITING_ACTION);
	}

	@Test
	void legalActionEndedGoesToEnded() {
		GameSession s = session();
		StubExecutor ex = new StubExecutor();
		ex.returnEnded = true;
		new TurnStateMachine(ex).submitAction(s, "B", new RecordingSink());
		assertThat(s.phase()).hasValue(TurnPhase.ENDED);
	}

	@Test
	void illegalActionEmitsErrorAndDoesNotCallExecutor() {
		GameSession s = session();
		StubExecutor ex = new StubExecutor();
		RecordingSink sink = new RecordingSink();
		new TurnStateMachine(ex).submitAction(s, "Z", sink);
		assertThat(ex.calls).hasValue(0);
		assertThat(sink.errors).containsExactly("illegal_action");
		assertThat(s.phase()).hasValue(TurnPhase.AWAITING_ACTION); // 停原地
	}

	@Test
	void busyPhaseRejectsSecondSubmitWithoutCallingExecutor() {
		GameSession s = session();
		s.phase().set(TurnPhase.GENERATING); // 模拟上一回合仍在跑
		StubExecutor ex = new StubExecutor();
		RecordingSink sink = new RecordingSink();
		new TurnStateMachine(ex).submitAction(s, "A", sink);
		assertThat(ex.calls).hasValue(0);
		assertThat(sink.errors).containsExactly("busy");
		assertThat(s.phase()).hasValue(TurnPhase.GENERATING); // 不动正在进行的回合
	}

	@Test
	void concurrentSubmitsSameTurnExactlyOnePasses() throws Exception {
		GameSession s = session();
		StubExecutor ex = new StubExecutor();
		ex.enter = new CountDownLatch(1);
		ex.hold = new CountDownLatch(1);
		TurnStateMachine fsm = new TurnStateMachine(ex);
		RecordingSink sink1 = new RecordingSink();
		RecordingSink sink2 = new RecordingSink();

		Thread t1 = new Thread(() -> fsm.submitAction(s, "A", sink1));
		t1.start();
		// 等线程 1 进入 executor(已 CAS 成功、占住 GENERATING),再发第二次。
		assertThat(ex.enter.await(2, TimeUnit.SECONDS)).isTrue();
		fsm.submitAction(s, "A", sink2); // 应被忙态守卫拒
		ex.hold.countDown();
		t1.join(2000);

		assertThat(ex.calls).as("executor 恰被调用一次").hasValue(1);
		assertThat(sink2.errors).containsExactly("busy");
		assertThat(s.phase()).hasValue(TurnPhase.AWAITING_ACTION);
	}

	// ── 守卫 0:配额(ADR-016,插在合法性之前、CAS 之前)────────────────────

	/** 拒绝一切 turn、记录收到的 ClientKey 的 stub 闸门。 */
	private static final class DenyingQuota implements QuotaGate {
		final List<ClientKey> seen = new ArrayList<>();

		@Override
		public Decision checkInit(ClientKey key) {
			return Decision.ALLOW;
		}

		@Override
		public Decision checkTurn(ClientKey key) {
			seen.add(key);
			return Decision.deny("今日回合名额已满,明天再来");
		}

		@Override
		public void record(com.aiuniverse.server.llm.LlmUsage usage) {
		}
	}

	@Test
	void quotaGuardRunsBeforeLegalityGuard() {
		GameSession s = session();
		StubExecutor ex = new StubExecutor();
		RecordingSink sink = new RecordingSink();
		DenyingQuota quota = new DenyingQuota();
		// 动作 "Z" 本身非法:若合法性守卫先跑,错误码会是 illegal_action —— 断言 quota_exceeded 证明顺序。
		new TurnStateMachine(ex, SessionStore.NOOP, quota).submitAction(s, "Z", sink,
				new QuotaGate.ClientKey("1.2.3.4", "dev-A"));
		assertThat(ex.calls).hasValue(0);
		assertThat(sink.errors).containsExactly("quota_exceeded");
		assertThat(quota.seen).containsExactly(new QuotaGate.ClientKey("1.2.3.4", "dev-A"));
	}

	@Test
	void quotaDenialLeavesPhaseUntouched() {
		GameSession s = session();
		s.phase().set(TurnPhase.GENERATING); // 守卫 0 在 CAS 之前:任何相位都零触碰
		RecordingSink sink = new RecordingSink();
		new TurnStateMachine(new StubExecutor(), SessionStore.NOOP, new DenyingQuota())
				.submitAction(s, "A", sink, null);
		assertThat(sink.errors).containsExactly("quota_exceeded");
		assertThat(s.phase()).hasValue(TurnPhase.GENERATING);
	}

	@Test
	void threeArgSubmitDelegatesWithNullKeyAndNoopQuotaAllows() {
		GameSession s = session();
		StubExecutor ex = new StubExecutor();
		new TurnStateMachine(ex).submitAction(s, "A", new RecordingSink()); // 旧签名 + NOOP 闸:行为不变
		assertThat(ex.calls).hasValue(1);
		assertThat(s.phase()).hasValue(TurnPhase.AWAITING_ACTION);
	}
}
