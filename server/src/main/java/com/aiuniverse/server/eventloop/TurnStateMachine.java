package com.aiuniverse.server.eventloop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aiuniverse.server.persistence.SessionStore;
import com.aiuniverse.server.quota.QuotaGate;

/**
 * 单回合状态机 + 守卫(规格 §3,确定性、零 LLM 零流式)。持有注入的 {@link TurnExecutor},
 * 守卫通过后才委托它跑 GENERATING/SETTLING;据结算结果转 {@link TurnPhase}。
 *
 * <p><b>两道守卫(均在调 executor 之前,确定性拒绝)</b>:
 * <ol start="0">
 *   <li><b>配额(ADR-016 守卫 0)</b>:成本闸门拒绝 → {@code event:error code=quota_exceeded}、
 *       executor <b>零调用</b>、<b>相位零触碰</b>(在 CAS 之前,会话停留 AWAITING_ACTION,
 *       次日额度恢复可续)。</li>
 *   <li><b>忙态(守卫 2)</b>:入 GENERATING 走 {@code phase.compareAndSet(AWAITING_ACTION, GENERATING)};
 *       GENERATING/SETTLING/ENDED 期间再来 → CAS 失败 → {@code event:error}、不二次调用。
 *       两线程同回合并发 → 恰一个 CAS 成功。</li>
 * </ol>
 *
 * <p><b>守卫 1(合法性)已前移到 {@code GameController.turn}</b>(ADR-022 立字 5)——它零副作用、
 * 零代价,放在容器线程上可以在<b>不占准入名额</b>的情况下拒掉;守卫 0 与守卫 2 留在原地
 * (守卫 2 前移要在准入路径上引入一个<b>必须永不失败的写操作</b>:CAS 成功后若被拒,
 * 相位停在 GENERATING 且无人放回 = <b>该存档永久 busy,只有重启才解开</b>)。
 * <b>调用方须保证 actionId 合法</b> —— 本类不再复查(复查 = 两处判定,ADR-018 §4.1)。
 *
 * <p><b>ADR-016 守卫顺序立字的新宿主 = {@code GameController.turn} 的 javadoc(那张拒绝链)。</b>
 * ⚠️ 顺序随之<b>反转</b>并已被接受为显式裁定(ADR-022 立字 6,<b>不是搬家的静默副产品</b>):
 * 合法性现在跑在配额<b>之前</b>,连带<b>非法动作不再消耗配额额度</b>(今天会)。
 * 理由与「被池拒绝不掉额度」同源——玩家什么都没得到就不该掉额度;而 ADR-016 把配额排最前的理由
 * 「被刷时单次拒绝成本 ≈0」在合法性守卫上<b>同样成立</b>(零 LLM 调用、O(1) 内存判断)。
 *
 * <p>无状态、线程安全:每个 saveId 的相位活在其 {@link GameSession#phase()} 里(规格 §3:
 * 内存 {@code ConcurrentHashMap<saveId,TurnPhase>} 由 {@link GameSessionManager} 承载)。
 */
@Component
public final class TurnStateMachine {

	private final TurnExecutor executor;
	private final SessionStore store;
	private final QuotaGate quota;

	/** 纯内存形态(测试 / Slice 2 之前行为)。 */
	public TurnStateMachine(TurnExecutor executor) {
		this(executor, SessionStore.NOOP, QuotaGate.NOOP);
	}

	/** 落盘形态、无闸门(ADR-016 之前行为;既有测试调用点零改)。 */
	public TurnStateMachine(TurnExecutor executor, SessionStore store) {
		this(executor, store, QuotaGate.NOOP);
	}

	@Autowired
	public TurnStateMachine(TurnExecutor executor, SessionStore store, QuotaGate quota) {
		this.executor = executor;
		this.store = store;
		this.quota = quota;
	}

	/** 无客户端标识形态(既有调用点/测试零改):跳过软闸键计数,只受全局闸约束。 */
	public void submitAction(GameSession session, String actionId, TurnEventSink sink) {
		submitAction(session, actionId, sink, null);
	}

	/**
	 * 受理一次玩家动作。守卫 → 委托 executor → 转相位。本方法<b>阻塞</b>跑完整回合
	 * (含流式),由 web 层在准入名额之内的池线程调用。
	 *
	 * <p><b>前置条件</b>:{@code actionId} 已由调用方校验合法(守卫 1 前移,见类 javadoc)。
	 *
	 * @param client 软闸双键(ip + deviceId,ADR-016;可 null = 只查全局 ¥ 闸)
	 */
	public void submitAction(GameSession session, String actionId, TurnEventSink sink, QuotaGate.ClientKey client) {
		// 守卫 0:配额(ADR-016,在 CAS 之前——相位零触碰,LLM 零调用)。
		QuotaGate.Decision quotaDecision = quota.checkTurn(client);
		if (!quotaDecision.allowed()) {
			sink.error("quota_exceeded", quotaDecision.message());
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
