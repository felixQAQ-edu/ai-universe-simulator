package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * ADR-030 决策 2 · {@link InitAdmission}(形状照 {@link TurnAdmission})。
 *
 * <p>「工作抛异常也还槽」用<b>真的另起线程</b>的执行器测:若用 {@code Runnable::run},工作抛的异常会从
 * {@code execute} 冒出来、落进容器线程那条 catch 再还一次 —— 那测的就不是池线程上的 {@code finally} 了。
 */
class InitAdmissionTest {

	private static final List<String> ARCH = List.of("rules_creepy");
	private static final String POOL_THREAD = "init-pool-test";

	private final Logger logger = (Logger) LoggerFactory.getLogger(InitAdmission.class);
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	{
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void detach() {
		logger.detachAppender(logs);
	}

	/** 像真池一样:另起一条名为 {@value #POOL_THREAD} 的线程跑,吞掉工作抛出的异常,并等它跑完。 */
	private static final Executor SEPARATE_THREAD_AND_JOIN = work -> {
		Thread t = new Thread(work, POOL_THREAD);
		t.setUncaughtExceptionHandler((th, e) -> { });
		t.start();
		try {
			t.join(5_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	};

	@Test
	void rejectionHappensOnTheCallingContainerThreadNotAPoolThread() throws Exception {
		CountDownLatch hold = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		Executor pool = work -> new Thread(work, POOL_THREAD).start();
		InitAdmission admission = new InitAdmission(1, pool);
		assertThat(admission.submit(ARCH, () -> {
			started.countDown();
			try {
				hold.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		})).isTrue();
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		AtomicReference<Boolean> ran = new AtomicReference<>(false);
		boolean admitted = admission.submit(ARCH, () -> ran.set(true));
		hold.countDown();

		assertThat(admitted).isFalse();
		assertThat(ran.get()).isFalse();
		ILoggingEvent warn = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow();
		assertThat(warn.getThreadName())
				.as("拒绝必须发生在容器线程上(被拒请求从头到尾没碰过池)")
				.isEqualTo(Thread.currentThread().getName())
				.isNotEqualTo(POOL_THREAD);
		assertThat(warn.getFormattedMessage()).contains("[rules_creepy]").contains("inFlight=1/1");
	}

	@Test
	void workThrowingRuntimeExceptionStillReleases() {
		InitAdmission admission = new InitAdmission(1, SEPARATE_THREAD_AND_JOIN);
		assertThat(admission.submit(ARCH, () -> {
			throw new IllegalStateException("工作炸了");
		})).isTrue();
		assertThat(admission.inFlight()).isZero();
	}

	@Test
	void workThrowingErrorStillReleases() {
		InitAdmission admission = new InitAdmission(1, SEPARATE_THREAD_AND_JOIN);
		assertThat(admission.submit(ARCH, () -> {
			throw new AssertionError("Error 也得还槽");
		})).isTrue();
		assertThat(admission.inFlight()).isZero();
	}

	@Test
	void executeThrowingRuntimeExceptionReleasesOnContainerThread() {
		InitAdmission admission = new InitAdmission(1, work -> {
			throw new RejectedExecutionException("池已关");
		});
		assertThatThrownBy(() -> admission.submit(ARCH, () -> { }))
				.isInstanceOf(RejectedExecutionException.class);
		assertThat(admission.inFlight()).isZero();
	}

	@Test
	void executeThrowingErrorReleasesOnContainerThread() {
		InitAdmission admission = new InitAdmission(1, work -> {
			throw new OutOfMemoryError("unable to create native thread");
		});
		assertThatThrownBy(() -> admission.submit(ARCH, () -> { })).isInstanceOf(OutOfMemoryError.class);
		assertThat(admission.inFlight()).isZero();
	}

	@Test
	void productionConstructorLogsResolvedCapacity() {
		// 非默认值 3:拿默认 4 去测,「改成打字面量 4」那个变异会假绿。
		InitAdmission admission = new InitAdmission(new InitProperties(3));
		try {
			assertThat(admission.capacity()).isEqualTo(3);
			assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
					.contains("[init-admission] 准入容量 N=3");
		} finally {
			admission.shutdown();
		}
	}
}
