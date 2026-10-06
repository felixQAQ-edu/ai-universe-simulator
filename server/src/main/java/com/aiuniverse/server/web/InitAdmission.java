package com.aiuniverse.server.web;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 开局路径的并发准入(ADR-030 决策 2)。<b>形状照 {@link TurnAdmission}</b>,逐条理由见那边的 javadoc
 * (ADR-022 方案 C);本类刻意<b>不抽公共基类</b>(决策 3 第 3 条:两条可以抄,三条才值得抽;
 * 动 {@code TurnAdmission} 就要重跑 ADR-022 的整套守护)。
 *
 * <ul>
 *   <li><b>占线程前拒绝</b>:{@code tryAcquire} 在容器线程上,失败即返 false,不碰池;</li>
 *   <li><b>{@code finally} 还槽</b>,在包装层 —— 调用方拿不到名额,「忘记归还」无法被写出来;</li>
 *   <li><b>{@code catch (Throwable)}</b>:{@code execute} 本身抛(含 {@code OutOfMemoryError: unable to create
 *       native thread})时在容器线程还槽再抛 —— 只接 {@code RuntimeException} 会在唯一真正会发生的那条路径上
 *       慢性漏槽,直到全站开局永久 503,而日志与真实饱和长得一模一样;</li>
 *   <li><b>池归本对象所有</b>,{@code @PreDestroy} 只关自建的那个;{@code GameController} 手边不持有线程池;</li>
 *   <li><b>拒绝 WARN 落在本类</b>(闸 B:拒绝那一瞬的在途数只有本类读得到);<b>不打客户端 IP</b>,打 archetypes。</li>
 * </ul>
 */
@Component
public final class InitAdmission {

	private static final Logger log = LoggerFactory.getLogger(InitAdmission.class);

	private final Semaphore permits;
	private final int capacity;
	private final Executor executor;
	/** 非 null <b>仅当</b>池由本对象自建(承载的是所有权,不是「是不是一个 ExecutorService」)。 */
	private final ExecutorService owned;

	/** 生产形态:池自建自持(world-gen 阻塞 10 s–2 分钟,不能占 Tomcat 容器线程)。 */
	@Autowired
	public InitAdmission(InitProperties props) {
		this.capacity = props.maxConcurrent();
		this.permits = new Semaphore(props.maxConcurrent());
		this.owned = Executors.newCachedThreadPool();
		this.executor = this.owned;
		// 启动就把解析后的**现值**说出来(同 [turn-admission] 那一行,层 1 第 6 条的理由):
		// 变松没有读数,而变松才危险 —— 冒烟压到 1 之后忘了撤 env,这一行是伤到人之前唯一看得见的地方。
		// 只打在生产构造器:测试构造器的容量由调用方直接给。
		log.info("[init-admission] 准入容量 N={}", capacity);
	}

	/** 测试形态:注入 {@link Executor};本对象<b>不拥有</b>它的生命周期,{@link #shutdown()} 不碰它。 */
	public InitAdmission(int capacity, Executor executor) {
		this.capacity = capacity;
		this.permits = new Semaphore(capacity);
		this.executor = executor;
		this.owned = null;
	}

	/**
	 * 占到名额 → 提交并返 {@code true};占不到 → 返 {@code false},<b>一个线程都不领</b>。
	 *
	 * <p>归还责任的交接点(同 ADR-022 §6):{@code execute()} 正常返回之前归容器线程(下面的 catch),
	 * 正常返回即交接给池线程的 {@code finally}。提交失败的重抛<b>不许</b>被映射成 {@code server_at_capacity}
	 * (「此刻太挤」与「服务器正在关」是两种情形)。
	 */
	public boolean submit(List<String> archetypes, Runnable work) {
		if (!permits.tryAcquire()) {
			// 真正带信息的是分母(当时生效的 N);分子是同义反复,理由同 TurnAdmission。
			log.warn("[init-admission] archetypes={} 拒绝 inFlight={}/{}", archetypes, inFlight(), capacity);
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
		} catch (Throwable e) {
			permits.release(); // 交接尚未发生 → 归还责任还在容器线程
			throw e;
		}
		return true;
	}

	/** 在途数 = 已发出的名额数(准入决定本身的投影,单一真相源)。 */
	public int inFlight() {
		return capacity - permits.availablePermits();
	}

	/** 名额总数。 */
	public int capacity() {
		return capacity;
	}

	/** 关闭语义同 {@link TurnAdmission#shutdown()}:只关自建的池,不 {@code awaitTermination}。 */
	@PreDestroy
	void shutdown() {
		if (owned != null) {
			owned.shutdown();
		}
	}
}
