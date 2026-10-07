package com.aiuniverse.server.eventloop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aiuniverse.server.persistence.SessionStore;
import com.aiuniverse.server.persistence.TraceSink;
import com.aiuniverse.server.persistence.TurnLedger;
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

	private static final Logger log = LoggerFactory.getLogger(TurnStateMachine.class);

	private final TurnExecutor executor;
	private final SessionStore store;
	private final QuotaGate quota;
	private final TurnLedger ledger;
	private final TraceSink traces;

	/** 纯内存形态(测试 / Slice 2 之前行为)。 */
	public TurnStateMachine(TurnExecutor executor) {
		this(executor, SessionStore.NOOP, QuotaGate.NOOP);
	}

	/** 落盘形态、无闸门(ADR-016 之前行为;既有测试调用点零改)。 */
	public TurnStateMachine(TurnExecutor executor, SessionStore store) {
		this(executor, store, QuotaGate.NOOP);
	}

	/** 无受理记录(ADR-026 之前行为;既有测试调用点零改)。 */
	public TurnStateMachine(TurnExecutor executor, SessionStore store, QuotaGate quota) {
		this(executor, store, quota, TurnLedger.NOOP);
	}

	/** 无回合轨迹(ADR-031 之前行为;既有测试调用点零改)。 */
	public TurnStateMachine(TurnExecutor executor, SessionStore store, QuotaGate quota, TurnLedger ledger) {
		this(executor, store, quota, ledger, TraceSink.NOOP);
	}

	@Autowired
	public TurnStateMachine(TurnExecutor executor, SessionStore store, QuotaGate quota, TurnLedger ledger,
			TraceSink traces) {
		this.executor = executor;
		this.store = store;
		this.quota = quota;
		this.ledger = ledger;
		this.traces = traces;
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
		// CAS 之后读:忙态守卫保证此刻本线程是这一局唯一写者,两次读之间无人能推进回合
		// (ADR-027 决策 1;CAS 之前读则另一线程可能在两次读之间推进)。
		int turnBefore = session.engine().turn();
		session.takeTurnTrace(); // 清掉任何残留,本回合的收集器只能由本回合的 execute 放入(ADR-031)
		try {
			ledger.accept(session, actionId); // ADR-026 决策 1:CAS 之后、调模型之前;best-effort(见 TurnLedger)
			TurnResult result = executor.execute(session, actionId, sink);
			// 写盘时机 = 临界区尾部(ADR-015 勘察 2):executor 返回后、相位放回之前——
			// 忙态守卫保证每 saveId 单写者,零新锁;best-effort 不抛(写失败局面继续活在内存)。
			store.persist(session);
			writeTrace(session); // ADR-031:persist 之后、设相位之前;不抛
			session.phase().set(result.ended() ? TurnPhase.ENDED : TurnPhase.AWAITING_ACTION);
		} catch (RuntimeException e) {
			if (session.engine().turn() != turnBefore) {
				// ADR-027 决策 1 不变式:回合已在内存落地 ⇒ 本次 persist 一定会被尝试。
				// 判据读的是事实(engine.turn() 变没变,apply / applyNoOp 首条语句都是 turn += 1),
				// 不按异常来源枚举 —— delta / ending 的 SSE 写失败只是已知来源之一,落地之后任何一处
				// RuntimeException 都走这里。相位按引擎事实定(结局回合 ending 写失败不得被放回 AWAITING,
				// 否则一局已收束的世界还能再跑一回合)。
				// 不发 internal_error:回合没有失败,它已经落账了 —— 那句「请重试」在这里是假话;
				// 客户端游标仍是旧回合,下一次点击拿 turn_stale → 拉 /state(ADR-023 立字 4.1)。
				log.warn("[turn] save={} 回合已落地(turn {}->{}),送达/收尾失败,补写盘:{}",
						session.saveId(), turnBefore, session.engine().turn(), e.toString());
				store.persist(session);
				writeTrace(session); // 已落地 ⇒ 轨迹照写(它和写盘是同一个事实的两份记录)
				session.phase().set("ended".equals(session.engine().status())
						? TurnPhase.ENDED : TurnPhase.AWAITING_ACTION);
			} else {
				// 未落地:executor 自身已尽力降级(§6);跑到这里是意料外故障 → 放回 AWAITING 不锁死该存档。
				ledger.failed(session); // ADR-026 决策 4 / ADR-027 决策 5:只在未落地分支;须在放回相位之前
				session.takeTurnTrace(); // ADR-031 已决 W-6:未落地回合不记轨迹(只丢弃收集器)
				session.phase().set(TurnPhase.AWAITING_ACTION);
				sink.error("internal_error", "回合处理失败,请重试");
			}
		}
	}

	/**
	 * 写出本回合轨迹(ADR-031 刀 1)。只在回合<b>已落地</b>并 persist 之后调用(两处);未落地回合不调用(已决 W-6)。
	 *
	 * <p>⚠️ <b>轨迹写失败绝不拖累回合</b>(ADR-031 §2.1):catch {@code Throwable} 而不是 {@code RuntimeException} ——
	 * 本方法跑在 try 块里,若让任何东西漏出去,它会落进下面的「已落地」分支再写一次盘、再写一次轨迹,
	 * 而一个 {@code Error} 则会直接冲出 {@code submitAction}、相位停在 GENERATING/SETTLING = 该存档永久 busy
	 * (同 ADR-022 刀 2 对 {@code catch (RuntimeException)} 过窄的那次审阅)。只记 WARN,回合、相位、存档照常。
	 */
	private void writeTrace(GameSession session) {
		TurnTraceCollector collector = session.takeTurnTrace();
		if (collector == null) {
			return; // 执行器没有采集(测试桩 / 采集失败)
		}
		try {
			traces.record(collector.build(session));
		} catch (Throwable e) {
			log.warn("[trace] save={} 回合轨迹写出失败,回合不受影响:{}", session.saveId(), e.toString());
		}
	}
}
