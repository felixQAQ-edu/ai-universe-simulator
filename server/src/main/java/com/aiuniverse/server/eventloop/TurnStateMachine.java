package com.aiuniverse.server.eventloop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aiuniverse.server.persistence.SessionStore;

/**
 * 单回合状态机 + 守卫(规格 §3,确定性、零 LLM 零流式)。持有注入的 {@link TurnExecutor},
 * 守卫通过后才委托它跑 GENERATING/SETTLING;据结算结果转 {@link TurnPhase}。
 *
 * <p><b>两道守卫(均在调 executor 之前,确定性拒绝)</b>:
 * <ol>
 *   <li><b>合法性</b>:{@code actionId ∉ 当前 availableActions} → {@code event:error}、
 *       executor <b>零调用</b>、停在 {@link TurnPhase#AWAITING_ACTION}。</li>
 *   <li><b>忙态</b>:入 GENERATING 走 {@code phase.compareAndSet(AWAITING_ACTION, GENERATING)};
 *       GENERATING/SETTLING/ENDED 期间再来 → CAS 失败 → {@code event:error}、不二次调用。
 *       两线程同回合并发 → 恰一个 CAS 成功。</li>
 * </ol>
 *
 * <p>无状态、线程安全:每个 saveId 的相位活在其 {@link GameSession#phase()} 里(规格 §3:
 * 内存 {@code ConcurrentHashMap<saveId,TurnPhase>} 由 {@link GameSessionManager} 承载)。
 */
@Component
public final class TurnStateMachine {

	private final TurnExecutor executor;
	private final SessionStore store;

	/** 纯内存形态(测试 / Slice 2 之前行为)。 */
	public TurnStateMachine(TurnExecutor executor) {
		this(executor, SessionStore.NOOP);
	}

	@Autowired
	public TurnStateMachine(TurnExecutor executor, SessionStore store) {
		this.executor = executor;
		this.store = store;
	}

	/**
	 * 受理一次玩家动作。守卫 → 委托 executor → 转相位。本方法<b>阻塞</b>跑完整回合
	 * (含流式),由 web 层在独立线程调用。
	 */
	public void submitAction(GameSession session, String actionId, TurnEventSink sink) {
		// 守卫 1:合法性(确定性,不调 LLM,不改相位)。
		if (!session.hasAction(actionId)) {
			sink.error("illegal_action", "无效的行动:" + actionId);
			return;
		}
		// 守卫 2:忙态(CAS 抢入 GENERATING;失败 = 该回合正被处理或整局已结束)。
		if (!session.phase().compareAndSet(TurnPhase.AWAITING_ACTION, TurnPhase.GENERATING)) {
			sink.error("busy", "上一回合仍在结算,请稍候");
			return;
		}
		try {
			TurnResult result = executor.execute(session, actionId, sink);
			// 写盘时机 = 临界区尾部(ADR-015 勘察 2):executor 返回后、相位放回之前——
			// 忙态守卫保证每 saveId 单写者,零新锁;best-effort 不抛(写失败局面继续活在内存)。
			store.persist(session);
			session.phase().set(result.ended() ? TurnPhase.ENDED : TurnPhase.AWAITING_ACTION);
		} catch (RuntimeException e) {
			// executor 自身已尽力降级(§6);跑到这里是意料外故障 → 放回 AWAITING 不锁死该存档。
			session.phase().set(TurnPhase.AWAITING_ACTION);
			sink.error("internal_error", "回合处理失败,请重试");
		}
	}
}
