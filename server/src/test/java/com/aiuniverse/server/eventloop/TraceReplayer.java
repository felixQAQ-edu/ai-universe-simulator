package com.aiuniverse.server.eventloop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.persistence.SessionDocument;
import com.aiuniverse.server.persistence.TurnTrace;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-031 刀 2 · <b>档 1 落账回放</b>(§三 3.1),测试侧工具(已决 W-10:不加服务端端点、不进生产装配)。
 *
 * <p>步骤:
 * <ol>
 *   <li>{@code SessionDocument.decode(pre)} —— 与启动回载同一路径(经 registry 重派生轴集);</li>
 *   <li>局面编排由回合前状态重算({@link EventLoopService#scenePlan},与 {@code execute} 同一个方法);</li>
 *   <li>settled → {@link EventLoopService#landSettled};degraded → {@link EventLoopService#landDegraded}
 *       —— <b>与线上同一份落账代码</b>。本类不自己 {@code apply}、不自己提交局面、不自己更新选项;</li>
 *   <li>算 {@code post} 摘要(同 {@link TurnTrace#postSha256}:去 {@code phaseHint})与各轴落账值,与记录比对。</li>
 * </ol>
 *
 * <p>不调模型、不经 SSE / 准入 / 配额 / 游标。轨迹的 {@code commit} 与当前构建不同 → 判「跨版本」,
 * 差异照样列出,但不算失败(ADR-031 置顶 2)。
 */
final class TraceReplayer {

	enum Outcome {
		/** 同版本,摘要与各轴落账值全等。 */
		CONSISTENT,
		/** 同版本,有差异 —— 这是失败。 */
		INCONSISTENT,
		/** 轨迹来自别的代码版本;差异(若有)是报告不是失败。 */
		CROSS_VERSION
	}

	/** @param differences 人读的差异清单(摘要不同一行,每个落账值不同的轴一行);全等则为空 */
	record Report(Outcome outcome, String recordedCommit, String currentCommit, String recordedSha,
			String replayedSha, List<String> differences) {

		boolean failed() {
			return outcome == Outcome.INCONSISTENT;
		}
	}

	private final EventLoopService service;
	private final ObjectMapper mapper;
	private final ArchetypeRegistry registry;
	private final String currentCommit;

	/**
	 * @param service 只用它的落账方法(不会调模型;传一个没有脚本的 LLM 替身即可)
	 * @param currentCommit 当前构建的代码版本(通常 = {@link TurnTraceCollector#COMMIT})
	 */
	TraceReplayer(EventLoopService service, ObjectMapper mapper, ArchetypeRegistry registry, String currentCommit) {
		this.service = service;
		this.mapper = mapper;
		this.registry = registry;
		this.currentCommit = currentCommit;
	}

	Report replay(TurnTrace r) {
		GameSession session = SessionDocument.decode(r.saveId(), r.pre(), mapper, registry);
		BoxSceneTurn.Plan scene = service.scenePlan(session, r.actionId());
		switch (r.path()) {
			case TurnTrace.PATH_SETTLED -> service.landSettled(session, r.parsed().deepCopy(), r.actionId(), scene,
					leak -> {
					});
			case TurnTrace.PATH_DEGRADED -> service.landDegraded(session, r.streamedNarrative(), r.actionId(), scene,
					() -> {
					});
			default -> throw new IllegalArgumentException("轨迹 path 不认识:" + r.path());
		}
		String sha = TurnTrace.postSha256(SessionDocument.encode(session, mapper), mapper);
		List<String> diff = new ArrayList<>();
		if (!sha.equals(r.post().sha256())) {
			diff.add("post.sha256 记录=" + r.post().sha256() + " 回放=" + sha);
		}
		Map<String, Double> recorded = r.post().attributes();
		Map<String, Double> replayed = session.engine().attributes();
		Set<String> keys = new LinkedHashSet<>(recorded.keySet());
		keys.addAll(replayed.keySet());
		for (String k : keys) {
			if (!Objects.equals(recorded.get(k), replayed.get(k))) {
				diff.add("轴 " + k + " 记录=" + recorded.get(k) + " 回放=" + replayed.get(k));
			}
		}
		Outcome outcome = !Objects.equals(r.commit(), currentCommit) ? Outcome.CROSS_VERSION
				: diff.isEmpty() ? Outcome.CONSISTENT : Outcome.INCONSISTENT;
		return new Report(outcome, r.commit(), currentCommit, r.post().sha256(), sha, List.copyOf(diff));
	}
}
