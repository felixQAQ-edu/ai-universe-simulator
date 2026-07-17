package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.persistence.SessionStore;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-015 Slice 2 · 写盘接线守护(store 用内存替身,文件形态另测):
 * <ol>
 *   <li>init 播种后写一次(起局即崩不丢局);</li>
 *   <li>回合写盘 = 临界区尾部(executor 返回后、相位放回之前——写盘瞬间 phase 仍 GENERATING/SETTLING,
 *       忙态守卫保证单写者);</li>
 *   <li>守卫拒绝(非法动作 / 忙态)不写盘;executor 意外异常不写盘(盘上仍是上一个完整回合);</li>
 *   <li>启动回载把 store 里的会话重填内存表。</li>
 * </ol>
 */
class SessionPersistenceWiringTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 记录 persist 调用的内存替身(loadAll 可预置)。 */
	private static final class RecordingStore implements SessionStore {
		final List<GameSession> persisted = new ArrayList<>();
		final List<TurnPhase> phaseAtPersist = new ArrayList<>();
		List<GameSession> toLoad = List.of();

		@Override
		public void persist(GameSession session) {
			persisted.add(session);
			phaseAtPersist.add(session.phase().get());
		}

		@Override
		public List<GameSession> loadAll() {
			return toLoad;
		}
	}

	private ObjectNode world() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		return world;
	}

	private ArrayNode actions() {
		ArrayNode actions = mapper.createArrayNode();
		actions.addObject().put("id", "A").put("text", "查看告示");
		return actions;
	}

	private static final class SilentSink implements TurnEventSink {
		@Override public void narrative(String text) { }
		@Override public void delta(ObjectNode d) { }
		@Override public void ending(ObjectNode e) { }
		@Override public void error(String code, String message) { }
	}

	// ── GameSessionManager ─────────────────────────────────────────────

	@Test
	void createPersistsOnceAfterSeeding() {
		RecordingStore store = new RecordingStore();
		GameSessionManager manager = new GameSessionManager(mapper, store);
		GameSession session = manager.create("save-1", world(), actions(), Set.of(), Map.of(), Set.of());
		assertThat(store.persisted).containsExactly(session);
	}

	@Test
	void reloadFromStoreFillsSessionMap() {
		RecordingStore store = new RecordingStore();
		GameSession restored = new GameSession("save-r", new Engine(world(), mapper), actions());
		store.toLoad = List.of(restored);
		GameSessionManager manager = new GameSessionManager(mapper, store);
		manager.reloadFromStore();
		assertThat(manager.get("save-r")).isSameAs(restored);
		assertThat(manager.activeCount()).isEqualTo(1);
	}

	// ── TurnStateMachine 临界区尾部写盘 ───────────────────────────────────

	@Test
	void successfulTurnPersistsAfterExecutorBeforePhaseRelease() {
		RecordingStore store = new RecordingStore();
		GameSession session = new GameSession("save-1", new Engine(world(), mapper), actions());
		TurnStateMachine fsm = new TurnStateMachine((s, a, sink) -> new TurnResult(false), store);
		fsm.submitAction(session, "A", new SilentSink());
		assertThat(store.persisted).containsExactly(session);
		// 写盘瞬间相位尚未放回(临界区尾部 = 单写者窗口内)。
		assertThat(store.phaseAtPersist.get(0)).isNotEqualTo(TurnPhase.AWAITING_ACTION);
		assertThat(session.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
	}

	@Test
	void guardRejectionsDoNotPersist() {
		RecordingStore store = new RecordingStore();
		GameSession session = new GameSession("save-1", new Engine(world(), mapper), actions());
		TurnStateMachine fsm = new TurnStateMachine((s, a, sink) -> new TurnResult(false), store);
		fsm.submitAction(session, "Z", new SilentSink()); // 守卫 1:非法动作
		session.phase().set(TurnPhase.GENERATING);
		fsm.submitAction(session, "A", new SilentSink()); // 守卫 2:忙态
		assertThat(store.persisted).isEmpty();
	}

	@Test
	void unexpectedExecutorFailureDoesNotPersist() {
		RecordingStore store = new RecordingStore();
		GameSession session = new GameSession("save-1", new Engine(world(), mapper), actions());
		TurnStateMachine fsm = new TurnStateMachine((s, a, sink) -> {
			throw new IllegalStateException("boom");
		}, store);
		fsm.submitAction(session, "A", new SilentSink());
		assertThat(store.persisted).as("意外故障不写盘(盘上仍是上一个完整回合)").isEmpty();
		assertThat(session.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
	}
}
