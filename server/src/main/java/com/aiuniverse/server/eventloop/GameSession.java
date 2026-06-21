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

	public GameSession(String saveId, Engine engine, ArrayNode initialActions) {
		this.saveId = saveId;
		this.engine = engine;
		this.currentActions = initialActions;
	}

	public String saveId() {
		return saveId;
	}

	public Engine engine() {
		return engine;
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
