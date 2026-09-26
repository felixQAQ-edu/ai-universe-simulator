package com.aiuniverse.server.persistence;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * 「跳过要响亮」的另一半(ADR-025 刀 1):测试计划跑完时汇总 <b>因无 Docker 被跳过的 DB 用例条数与原因</b>,
 * 一条打在 stderr。经 {@code META-INF/services} 注册到 JUnit Platform(surefire 走的就是它)。
 *
 * <p>计数单位 = 被跳过的<b>测试方法</b>:整类被跳过时 JUnit 只对类节点发一次 skipped,故此处按类节点下的
 * 方法数展开;计入条件是跳过原因以 {@link DockerGate#SKIP_MARKER} 开头。
 */
public class DbTestSkipSummary implements TestExecutionListener {

	private final AtomicInteger skippedTests = new AtomicInteger();
	private TestPlan plan;

	@Override
	public void testPlanExecutionStarted(TestPlan testPlan) {
		this.plan = testPlan;
		skippedTests.set(0);
	}

	@Override
	public void executionSkipped(TestIdentifier id, String reason) {
		if (reason == null || !reason.startsWith(DockerGate.SKIP_MARKER)) {
			return;
		}
		skippedTests.addAndGet(countTests(id));
	}

	private int countTests(TestIdentifier id) {
		if (id.isTest()) {
			return 1;
		}
		int n = 0;
		for (TestIdentifier child : plan.getChildren(id)) {
			n += countTests(child);
		}
		return n;
	}

	@Override
	public void testPlanExecutionFinished(TestPlan testPlan) {
		int n = skippedTests.get();
		if (n > 0) {
			System.err.println(DockerGate.SKIP_MARKER + " ⚠️ 跳过 " + n
					+ " 条 DB 用例:本机无 Docker。这些用例本次没有跑 —— 不是通过。"
					+ "(CI=true 时同样情况会失败而不是跳过)");
		}
	}
}
