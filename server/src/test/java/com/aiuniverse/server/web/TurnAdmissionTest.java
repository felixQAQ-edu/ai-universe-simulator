package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 并发准入本体(ADR-022 §3/§4/§6)。这里守的是三件互不替代的事:
 * <ol>
 *   <li><b>名额进出配对</b>——归还漏一次,第 N+1 个真玩家就被永久拒之门外;</li>
 *   <li><b>交接点</b>(§6):{@code execute()} 正常返回<b>之前</b>归还责任在容器线程,
 *       正常返回即交接给池线程的 {@code finally};</li>
 *   <li><b>在途数不撒谎</b>——这一刀的全部价值压在那个数字上(ADR-018 §4.14 那一族,
 *       且这次是最阴的一种:前五次是工具骗我们说「坏了」,这次会骗我们说「好了」)。</li>
 * </ol>
 *
 * <p>拒绝日志断言走 logback {@link ListAppender} 直接测日志,<b>不退到「抽个纯函数测判定」</b>
 * ——观测面就是这一刀的交付物之一(同 {@code FileSessionStoreTest} 的既有做法)。
 */
class TurnAdmissionTest {

	private final Logger admissionLogger = (Logger) LoggerFactory.getLogger(TurnAdmission.class);
	private ListAppender<ILoggingEvent> appender;

	private void captureLogs() {
		appender = new ListAppender<>();
		appender.start();
		admissionLogger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		if (appender != null) {
			admissionLogger.detachAppender(appender);
			appender.stop();
			appender = null;
		}
	}

	private List<String> logsAt(Level level) {
		return appender.list.stream().filter(e -> e.getLevel() == level)
				.map(ILoggingEvent::getFormattedMessage).toList();
	}

	/** 收下任务但<b>永不运行</b>:名额被占住不还,用来把容量顶满。 */
	private static final class ParkingExecutor implements Executor {
		final List<Runnable> parked = new ArrayList<>();

		@Override
		public void execute(Runnable command) {
			parked.add(command);
		}

		/** 让停着的任务真的跑完(触发包装层的 finally 归还)。 */
		void runAll() {
			List<Runnable> snapshot = List.copyOf(parked);
			parked.clear();
			snapshot.forEach(Runnable::run);
		}
	}

	// ── 名额与在途数 ─────────────────────────────────────────────────────

	@Test
	void admitsUpToCapacityThenRejectsWithoutSubmitting() {
		ParkingExecutor executor = new ParkingExecutor();
		TurnAdmission admission = new TurnAdmission(2, executor);

		assertThat(admission.submit("s1", () -> { })).isTrue();
		assertThat(admission.submit("s2", () -> { })).isTrue();
		assertThat(admission.submit("s3", () -> { })).as("名额用尽 → 拒").isFalse();

		assertThat(executor.parked).as("被拒的那一发一个线程都不领").hasSize(2);
		assertThat(admission.inFlight()).isEqualTo(2);
		assertThat(admission.capacity()).isEqualTo(2);
	}

	@Test
	void inFlightGoesBackToZeroAfterWorkCompletes() {
		ParkingExecutor executor = new ParkingExecutor();
		TurnAdmission admission = new TurnAdmission(2, executor);
		admission.submit("s1", () -> { });
		admission.submit("s2", () -> { });
		assertThat(admission.inFlight()).isEqualTo(2);

		executor.runAll();

		assertThat(admission.inFlight()).as("名额进出配对").isZero();
		assertThat(admission.submit("s3", () -> { })).as("归还后可再进").isTrue();
	}

	/**
	 * <b>§C 点名的那条路径</b>:任务体自己抛(现实原型 = emitter 已因 120s 超时完成时
	 * {@code completeWithError} 会再抛)。归还的 {@code finally} 在<b>包装层</b>、不在业务 lambda 里,
	 * 故它套住的是调用方传进来的<b>任何东西</b>——包括这一条。
	 */
	@Test
	void permitIsReleasedEvenWhenWorkThrows() {
		ParkingExecutor executor = new ParkingExecutor();
		TurnAdmission admission = new TurnAdmission(1, executor);
		admission.submit("s1", () -> {
			throw new IllegalStateException("completeWithError 自己再抛");
		});

		assertThatThrownBy(executor::runAll).isInstanceOf(IllegalStateException.class);

		assertThat(admission.inFlight()).as("任务体抛出仍归还").isZero();
		assertThat(admission.submit("s2", () -> { })).isTrue();
	}

	// ── 交接点(§6)──────────────────────────────────────────────────────

	/**
	 * {@code execute()} 抛出(池已 {@code shutdown()}、容器正在停)→ <b>交接尚未发生</b>,
	 * 归还责任还在容器线程。那一刻若不归还,名额<b>永久丢失</b>:这不是理论风险。
	 *
	 * <p>⚠️ 重抛出去的异常<b>不许</b>被映射成 {@code server_at_capacity}(§5.1 禁令):
	 * 「此刻太挤,过几秒再来」与「服务器正在关,过几秒也没用」是两种情形,
	 * 共用一个码<b>连看错的机会都不给</b>。
	 */
	@Test
	void permitIsReleasedWhenSubmitItselfFails() {
		Executor alwaysRejecting = r -> {
			throw new RejectedExecutionException("池已关");
		};
		TurnAdmission admission = new TurnAdmission(1, alwaysRejecting);

		assertThatThrownBy(() -> admission.submit("s1", () -> { }))
				.isInstanceOf(RejectedExecutionException.class);

		assertThat(admission.inFlight()).as("交接前失败 → 容器线程归还").isZero();
	}

	/**
	 * <b>提交时抛 {@link Error} 也归还</b> —— 这条不是「顺手把 catch 写宽」,它守的是
	 * <b>唯一真正会发生的那条路径</b>:{@code newCachedThreadPool} 起不出线程时抛的是
	 * {@code OutOfMemoryError: unable to create native thread}(512MB 容器、<b>线程栈在堆外</b>,
	 * 正是本 ADR 背景那个场景),而它是 {@code Error} 不是 {@code RuntimeException}。
	 *
	 * <p>⚠️ 只接 {@code RuntimeException} 的后果<b>不是崩溃,是慢性失血</b>:堆可能完全健康、
	 * 进程继续跑,每漏一次名额上限就少一个,直到 <b>N=0 = 全站永久 503</b>;
	 * <b>而日志会一直打 {@code 拒绝 inFlight=N/N},与真实饱和长得一模一样。</b>
	 */
	@Test
	void permitIsReleasedWhenSubmitThrowsError() {
		Executor oomExecutor = r -> {
			throw new OutOfMemoryError("unable to create native thread");
		};
		TurnAdmission admission = new TurnAdmission(1, oomExecutor);

		assertThatThrownBy(() -> admission.submit("s1", () -> { })).isInstanceOf(OutOfMemoryError.class);

		assertThat(admission.inFlight()).as("Error 也要归还,否则名额慢性失血").isZero();
		// 再来一次仍然是「占到名额 → 提交时抛」而不是「占不到名额」——漏还的话第二发会因为
		// tryAcquire 失败而**静默返 false**(连异常都没有),那正是慢性失血看起来的样子。
		assertThatThrownBy(() -> admission.submit("s2", () -> { })).isInstanceOf(OutOfMemoryError.class);
		assertThat(admission.inFlight()).isZero();
	}

	/** 提交失败重复 N+1 次仍不耗尽名额(漏还一次这条就红)。 */
	@Test
	void repeatedSubmitFailuresDoNotLeakPermits() {
		Executor alwaysRejecting = r -> {
			throw new RejectedExecutionException("池已关");
		};
		TurnAdmission admission = new TurnAdmission(2, alwaysRejecting);
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> admission.submit("s", () -> { }))
					.isInstanceOf(RejectedExecutionException.class);
		}
		assertThat(admission.inFlight()).isZero();
	}

	// ── 观测面 ───────────────────────────────────────────────────────────

	/**
	 * 拒绝 WARN 带 saveId 与<b>拒绝那一瞬间</b>的在途数。
	 *
	 * <p>⚠️ 日志落在准入对象内部<b>不是就近原则</b>(ADR-022 闸 B):controller 只能在
	 * {@code submit} 返回 false <b>之后</b>再读在途数,那中间任何一次归还都会让日志打出
	 * {@code inFlight=1/2} 配一次拒绝——<b>一个自相矛盾、读起来像机制坏了的数字</b>。
	 */
	@Test
	void rejectionLogsWarnWithSaveIdAndInFlight() {
		ParkingExecutor executor = new ParkingExecutor();
		TurnAdmission admission = new TurnAdmission(2, executor);
		admission.submit("s1", () -> { });
		admission.submit("s2", () -> { });
		captureLogs();

		admission.submit("s3", () -> { });

		assertThat(logsAt(Level.WARN)).containsExactly("[turn-admission] save=s3 拒绝 inFlight=2/2");
	}

	/** 通过的请求<b>不打日志</b>:观测面最小面锁死,「零拒绝」时这条通道该是安静的。 */
	@Test
	void admittedRequestsAreSilent() {
		TurnAdmission admission = new TurnAdmission(2, new ParkingExecutor());
		captureLogs();

		admission.submit("s1", () -> { });

		assertThat(appender.list).isEmpty();
	}

	// ── 并发下的进出配对 ─────────────────────────────────────────────────

	/**
	 * 并发压 4×容量:通过数<b>恰好等于</b>被 executor 收下的任务数,且跑完后在途数归零。
	 * (在途数若是旁挂的计数器,这条是它最容易漂移的地方。)
	 */
	@Test
	void concurrentSubmitsKeepPermitsAndTasksInLockstep() throws Exception {
		ParkingExecutor executor = new ParkingExecutor();
		TurnAdmission admission = new TurnAdmission(4, new java.util.concurrent.Executor() {
			@Override
			public synchronized void execute(Runnable command) {
				executor.execute(command);
			}
		});
		AtomicInteger admitted = new AtomicInteger();
		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			final int n = i;
			threads.add(Thread.ofPlatform().start(() -> {
				if (admission.submit("s" + n, () -> { })) {
					admitted.incrementAndGet();
				}
			}));
		}
		for (Thread t : threads) {
			t.join(2000);
		}

		assertThat(admitted).hasValue(4);
		assertThat(executor.parked).hasSize(4);
		assertThat(admission.inFlight()).isEqualTo(4);

		executor.runAll();
		assertThat(admission.inFlight()).isZero();
	}
}
