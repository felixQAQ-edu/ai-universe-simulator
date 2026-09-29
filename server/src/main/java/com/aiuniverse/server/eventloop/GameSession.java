package com.aiuniverse.server.eventloop;

import java.util.concurrent.atomic.AtomicReference;

import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * 一条存档(saveId)的内存会话:持有真理之源 {@link Engine}、当前回合相位、当前已下发的合法动作集。
 * Phase 1 单机单人,不起持久化(规格 §3 忙态守卫:内存 {@code ConcurrentHashMap<saveId,..>} +
 * {@code compareAndSet})。
 *
 * <p><b>并发</b>:{@link #phase} 是 {@link AtomicReference},入 GENERATING 走 {@code compareAndSet}
 * 保证一回合恰一个线程过(防双花)。临界区内串行,故 {@link #currentActions} 的读改无需额外锁。
 */
public final class GameSession {

	private final String saveId;
	private final Engine engine;
	private final AtomicReference<TurnPhase> phase = new AtomicReference<>(TurnPhase.AWAITING_ACTION);
	/** 当前回合下发给玩家的 availableActions(完整对象 {id,text,hint});合法性校验与 no-op 复用都读它。 */
	private ArrayNode currentActions;
	/**
	 * 开场叙事(ADR-025 已决 2:进历史作 turn 0)。<b>只有 DB 版 {@code SessionStore} 读它</b>,
	 * 写进 {@code game_event} turn 0;<b>不进快照</b>({@code toPersistedState} 不含它)、不进喂模型的视图 2、
	 * 不进 {@code /state} 的视图 3 —— ADR-007「openingNarrative 不进持久化 state」照旧成立。
	 * 回载构造出来的会话恒为 {@code null}(它从未被存进快照,也补不回来),无害。
	 */
	private final String openingNarrative;

	/**
	 * 本回合的受理行(ADR-026 决策 1):{@code pg} 版 {@code TurnLedger.accept} 写下,{@code JdbcSessionStore.persist}
	 * 据它在同一事务里关掉受理行(决策 2)。<b>不进 Engine、不进快照、不进任何视图</b>(同 {@link #openingNarrative});
	 * 默认 profile 下恒为 {@code null}。临界区内由 CAS 串行,{@code volatile} 只为跨回合换线程的可见性。
	 */
	private volatile TurnRecord pendingTurnRecord;
	/**
	 * 最近一次 no-op 降级落地的回合号(ADR-026 决策 2:降级回合标 {@code DEGRADED} 而非 {@code SUCCEEDED})。
	 * 由 {@code EventLoopService.degrade} 设,只有 {@code pg} 版 store 读;记的是「第几回合是降级落地的」这一事实,
	 * 故不需要复位 —— 下一回合的 target 不会等于它。-1 = 从未降级。
	 */
	private volatile int degradedTurn = -1;

	/** 一条受理行的内存把手:行 id + 它确认落地时快照应到的回合号。 */
	public record TurnRecord(long id, int targetTurn) {
	}

	public GameSession(String saveId, Engine engine, ArrayNode initialActions) {
		this(saveId, engine, initialActions, null);
	}

	public GameSession(String saveId, Engine engine, ArrayNode initialActions, String openingNarrative) {
		this.saveId = saveId;
		this.engine = engine;
		this.currentActions = initialActions;
		this.openingNarrative = openingNarrative;
	}

	public String saveId() {
		return saveId;
	}

	public Engine engine() {
		return engine;
	}

	/** 开场叙事;回载的会话为 {@code null}。见字段注释。 */
	public String openingNarrative() {
		return openingNarrative;
	}

	/** 本回合的受理行;没有(默认 profile / 受理写库失败)为 {@code null}。 */
	public TurnRecord pendingTurnRecord() {
		return pendingTurnRecord;
	}

	public void setPendingTurnRecord(TurnRecord record) {
		this.pendingTurnRecord = record;
	}

	/** 降级落地的回合号记下来(见字段注释)。 */
	public void markDegraded(int turn) {
		this.degradedTurn = turn;
	}

	public int degradedTurn() {
		return degradedTurn;
	}

	public AtomicReference<TurnPhase> phase() {
		return phase;
	}

	public ArrayNode currentActions() {
		return currentActions;
	}

	public void setCurrentActions(ArrayNode actions) {
		if (actions != null) {
			this.currentActions = actions;
		}
	}

	/** 规格 §3 VALIDATING_ACTION:所选 id 是否 ∈ 当前 availableActions(Phase 1 只允许选 id)。 */
	public boolean hasAction(String actionId) {
		if (actionId == null || currentActions == null) {
			return false;
		}
		for (JsonNode a : currentActions) {
			if (actionId.equals(a.path("id").asString(null))) {
				return true;
			}
		}
		return false;
	}
}
