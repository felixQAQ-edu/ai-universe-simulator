package com.aiuniverse.server.web;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 回合路径的并发准入(ADR-022 方案 C)。<b>准入必须在领线程之前发生</b>——这是本刀与「换个池」的
 * 全部区别:换成上限 N 的池,一批必然被拒的请求照样能各占一个名额跑到被拒为止。
 *
 * <p><b>为什么是信号量而不是有界池 + AbortPolicy</b>(ADR-022 方案 A 的否决理由):有界池的归还
 * 白送、不会漏,但它的在途数只能靠手工维护一个计数器({@code getActiveCount()} 的 javadoc
 * <b>自陈 "approximate"</b>,而<b>一个自陈近似的读数不能拿来签字说机制生效</b>)。信号量反过来:
 * 归还要被设计成不可遗忘(见下),但<b>在途数失去了撒谎的能力</b>——它不是一份被维护的计数,
 * 而是准入决定本身的投影({@link #inFlight()}),<b>没有一条可以漏掉的更新路径,因为它根本没有更新路径</b>。
 * 两个方案各有一处会漏,选它是因为<b>它漏的那一处可以被结构消灭,而 A 漏的那一处写在 JDK 的 javadoc 里</b>。
 *
 * <p><b>归还在结构上不可遗忘</b>(ADR-022 §4):调用方<b>永远拿不到那个名额</b>——本类同时拥有
 * 获取与提交,只暴露 {@link #submit(String, Runnable)}。于是「忘记归还」<b>无法被写出来</b>
 * (取消那个步骤本身,不是更小心地执行它);且归还的 {@code finally} 在包装层、不在业务 lambda 里,
 * 故它套住的是调用方传进来的<b>任何东西</b>,包括 {@code emitter.completeWithError(e)} 自己再抛的那条路径
 * (emitter 已因 120s 超时完成时它会抛)。
 *
 * <p><b>池归本对象所有</b>(ADR-022 §5 形态 (d)):<b>生命周期跟着所有权走</b>——谁在池上挂了不变量
 * (名额与任务一一对应),谁负责关。连带收益是 {@code GameController} 里<b>手边根本没有 ExecutorService
 * 可绕</b>(已知代价 2 的缓解,由一条源码级断言钉住)。
 *
 * <p><b>拒绝日志落在本类而不是 controller</b>(ADR-022 闸 B 裁定):controller 只能在 {@code submit}
 * 返回 false <b>之后</b>再读在途数,那中间任何一次归还都会让日志打出 {@code inFlight=6/8} 配一次拒绝
 * ——<b>一个自相矛盾、读起来像机制坏了的数字</b>,而这一刀的全部价值就压在那个数字上。
 * 拒绝那一瞬间的真实在途数<b>只有本类内部读得到</b>,{@code saveId} 在签名里正是为此。
 */
@Component
public final class TurnAdmission {

	private static final Logger log = LoggerFactory.getLogger(TurnAdmission.class);

	private final Semaphore permits;
	private final int capacity;
	private final Executor executor;
	/**
	 * 非 null <b>仅当</b>池由本对象自建 —— {@link #shutdown()} 只关自己建的那一个。
	 *
	 * <p>⚠️ <b>不要「简化」成 {@code executor instanceof ExecutorService es}</b>:那把
	 * 「是不是一个 ExecutorService」写成了「我是不是拥有它」,而<b>那是两件事</b>。
	 * 本字段承载的是所有权(§5「生命周期跟着所有权走」),测试注入的 Executor 不归本对象关。
	 */
	private final ExecutorService owned;

	/** 生产形态:池自建自持(SSE 是阻塞长连接,不能占 Tomcat 容器线程)。 */
	public TurnAdmission(TurnProperties props) {
		this.capacity = props.maxConcurrent();
		this.permits = new Semaphore(props.maxConcurrent());
		this.owned = Executors.newCachedThreadPool();
		this.executor = this.owned;
	}

	/**
	 * 测试形态:注入 {@link Executor}(可注入必抛的,验提交失败路径归还名额)。
	 * 本对象<b>不拥有</b>它的生命周期,故 {@link #shutdown()} 不碰它。
	 */
	public TurnAdmission(int capacity, Executor executor) {
		this.capacity = capacity;
		this.permits = new Semaphore(capacity);
		this.executor = executor;
		this.owned = null;
	}

	/**
	 * 占到名额 → 提交并返 {@code true};占不到 → 返 {@code false},<b>一个线程都不领</b>。
	 *
	 * <p><b>归还责任的交接点</b>(ADR-022 §6,这条是立字不是注释):
	 * <b>{@code execute()} 正常返回之前,归还责任在容器线程(下面的 catch);
	 * {@code execute()} 正常返回即交接给池线程的 {@code finally}。</b>
	 * 它不是理论风险:池 {@code shutdown()} 之后(容器正在停){@code execute} 会抛
	 * {@link java.util.concurrent.RejectedExecutionException},那一刻若不归还,名额<b>永久丢失</b>。
	 *
	 * <p>⚠️ 提交失败时的重抛<b>不许</b>被映射成 {@code server_at_capacity}(ADR-022 §5.1 禁令):
	 * 「此刻太挤,过几秒再来」与「服务器正在关,过几秒也没用」是两种情形,共用一个码
	 * <b>连看错的机会都不给</b>。它落进通用非 2xx 兜底桶,难看但诚实。
	 */
	public boolean submit(String saveId, Runnable work) {
		if (!permits.tryAcquire()) {
			// 在拒绝那一瞬间读在途数(此时必然 = capacity);交给 controller 事后读会读到一个更小的数。
			log.warn("[turn-admission] save={} 拒绝 inFlight={}/{}", saveId, inFlight(), capacity);
			return false;
		}
		try {
			executor.execute(() -> {
				try {
					work.run();
				} finally {
					permits.release();
				}
			});
		} catch (RuntimeException e) {
			permits.release(); // 交接尚未发生 → 归还责任还在容器线程
			throw e;
		}
		return true;
	}

	/**
	 * 在途数 = 已发出的名额数。<b>它就是准入决定本身的投影,不可能与之漂移</b>
	 * (允许并发下过时,不允许错)。观测面的单一真相源,不许在旁边再挂一个计数器。
	 */
	public int inFlight() {
		return capacity - permits.availablePermits();
	}

	/** 名额总数(观测面读它拼 {@code inFlight/capacity})。 */
	public int capacity() {
		return capacity;
	}

	/**
	 * ⚠️ <b>关闭语义原样承继、一个字不改</b>(ADR-022 §5.1):body 就是 {@code shutdown()} 一行,
	 * 与本刀之前 {@code GameController.shutdown()} 逐字等价,只是换了个宿主。
	 * <b>不 {@code awaitTermination}、不做「停止受理」</b>——在途回合由进程退出硬掐,
	 * 玩家体感 = 那一回合丢了、盘上停在上一个完整回合(与 ADR-015 已知代价 1 同族)。
	 * <b>池换了所有者不构成修它的理由</b>,那是另一刀,缺口仍挂账。
	 */
	@PreDestroy
	void shutdown() {
		if (owned != null) {
			owned.shutdown();
		}
	}
}
