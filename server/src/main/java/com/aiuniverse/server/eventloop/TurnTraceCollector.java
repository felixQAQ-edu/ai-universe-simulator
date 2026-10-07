package com.aiuniverse.server.eventloop;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;

import com.aiuniverse.server.llm.UsageCapture;
import com.aiuniverse.server.persistence.SessionDocument;
import com.aiuniverse.server.persistence.TurnTrace;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 本回合轨迹收集器(ADR-031 §2.1「采集」)。{@link EventLoopService} 在已经持有这些值的位置往里放,
 * {@link TurnStateMachine} 在回合落地并写盘之后取走、补上 {@code post} 摘要、交给
 * {@link com.aiuniverse.server.persistence.TraceSink}。挂在 {@link GameSession} 上(同 {@code pendingTurnRecord}
 * 的会话级、不进快照的形态),因为「已落地未送达」那条分支里 {@code execute} 没有返回值可带。
 *
 * <p><b>只在内存里累积,不写任何东西</b>(ADR-031 测试面 7):本类不引用 {@code TraceSink}、不写文件;
 * 流式回调里也不调用本类(采集点都在流结束之后)。唯一的读是类加载时读一次 {@code build-info.properties}。
 *
 * <p>临界区内由相位 CAS 串行,单线程读写,无需同步。
 */
class TurnTraceCollector {

	/** 代码版本(ADR-031 置顶 2):{@code build-info} 生成的 {@code build.commit};读不到为 {@code unknown}。 */
	static final String COMMIT = readCommit();

	private final ObjectMapper mapper;
	private final String saveId;
	private final int turnBefore;
	private final String actionId;
	private final ObjectNode pre;
	private String promptSha256;
	private final List<TurnTrace.CallUsage> usage = new ArrayList<>();
	private List<String> repairErrors;
	private String path;
	private ObjectNode parsed;
	private String degradeReason;
	private String streamedNarrative;
	private long durMs = -1;
	private String recordedAt;

	/** 包内可见只为测试替身(采集点抛异常);生产路径只经 {@link #begin}。 */
	TurnTraceCollector(ObjectMapper mapper, GameSession session, String actionId, ObjectNode pre) {
		this.mapper = mapper;
		this.saveId = session.saveId();
		this.turnBefore = session.engine().turn();
		this.actionId = actionId;
		this.pre = pre;
	}

	/** {@code execute} 开头调用:取回合前状态的全量副本(W-2)。 */
	static TurnTraceCollector begin(GameSession session, String actionId, ObjectMapper mapper) {
		return new TurnTraceCollector(mapper, session, actionId, SessionDocument.encode(session, mapper));
	}

	void prompt(String prompt) {
		this.promptSha256 = TurnTrace.sha256Hex(prompt);
	}

	void usage(String call, UsageCapture capture) {
		usage.add(new TurnTrace.CallUsage(call, capture.usage(), capture.model(), capture.reasoningChars()));
	}

	void repairErrors(List<String> errors) {
		this.repairErrors = List.copyOf(errors);
	}

	/** settle:在 {@code clampClosingVigorFloor} 之后、{@code apply} 之前调用;存副本。 */
	void settled(ObjectNode parsedAfterRewrite) {
		this.path = TurnTrace.PATH_SETTLED;
		this.parsed = parsedAfterRewrite.deepCopy();
	}

	/** degrade:{@code applyNoOp} 之前调用。 */
	void degraded(String reason, String streamedNarrative) {
		this.path = TurnTrace.PATH_DEGRADED;
		this.degradeReason = reason;
		this.streamedNarrative = streamedNarrative;
	}

	/** 回合总耗时终点(与日志里的 {@code durMs} 同一次读数)。 */
	void finished(long endedAtMs, long durMs) {
		this.durMs = durMs;
		this.recordedAt = Instant.ofEpochMilli(endedAtMs).toString();
	}

	/** 落地并写盘之后调用:补 {@code post} 摘要(去 {@code phaseHint})与各轴落账值。 */
	TurnTrace build(GameSession session) {
		ObjectNode post = SessionDocument.encode(session, mapper);
		return new TurnTrace(TurnTrace.SCHEMA, saveId, turnBefore, recordedAt, COMMIT, actionId, path, pre, parsed,
				degradeReason, streamedNarrative, promptSha256, List.copyOf(usage), durMs, repairErrors,
				new TurnTrace.Post(TurnTrace.postSha256(post, mapper),
						new LinkedHashMap<>(session.engine().attributes())));
	}

	private static String readCommit() {
		try (InputStream in = TurnTraceCollector.class.getResourceAsStream("/META-INF/build-info.properties")) {
			if (in == null) {
				return "unknown";
			}
			Properties p = new Properties();
			p.load(in);
			return p.getProperty("build.commit", "unknown");
		} catch (IOException e) {
			return "unknown";
		}
	}
}
