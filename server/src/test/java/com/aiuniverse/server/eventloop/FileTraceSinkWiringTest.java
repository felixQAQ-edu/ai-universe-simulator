package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.persistence.FileTraceSink;
import com.aiuniverse.server.persistence.TraceFileReader;
import com.aiuniverse.server.persistence.TraceSink;
import com.aiuniverse.server.persistence.TurnLedger;
import com.aiuniverse.server.persistence.TurnTrace;
import com.aiuniverse.server.quota.QuotaGate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-031 刀 3 · 文件落点接到真实 {@link TurnStateMachine} + {@link EventLoopService}(模型由脚本代替):
 * <ul>
 *   <li>端到端:几回合(含一回合降级)→ 文件行数 = 落地回合数,每一行读回后经刀 2 的回放器回放一致;</li>
 *   <li>测试面 10 的另一半:单文件 / 总量到限停写时,回合照常落账、照常写盘、相位照常。</li>
 * </ul>
 */
class FileTraceSinkWiringTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ArchetypeRegistry REGISTRY = new ArchetypeRegistry();

	@TempDir
	Path tmp;

	private final Logger sinkLogger = (Logger) LoggerFactory.getLogger(FileTraceSink.class);
	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void capture() {
		appender = new ListAppender<>();
		appender.start();
		sinkLogger.addAppender(appender);
	}

	@AfterEach
	void detach() {
		sinkLogger.detachAppender(appender);
		appender.stop();
	}

	private long warns() {
		return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
	}

	private record Played(GameSession session, TurnTraceTest.CountingStore store) {
	}

	/** 跑若干回合;每回合断言已落地(turn +1)且相位回到 AWAITING_ACTION。 */
	private Played play(TraceSink sink, Object... responses) {
		TurnTraceTest.ScriptedLlm llm = new TurnTraceTest.ScriptedLlm();
		for (Object r : responses) {
			llm.then(r);
		}
		TurnTraceTest.CountingStore store = new TurnTraceTest.CountingStore();
		TurnStateMachine machine = new TurnStateMachine(
				new EventLoopService(llm, new TurnPromptBuilder(REGISTRY), MAPPER), store, QuotaGate.NOOP,
				TurnLedger.NOOP, sink);
		// 夹具的 saveId 带下划线,不是 UUID 形,文件落点会拒写(防目录逃逸);换成线上同形的 id。
		GameSession fixture = new TraceScenarios(MAPPER, REGISTRY).session("rules_creepy", 0, false);
		GameSession s = new GameSession("save-file-1", fixture.engine(), fixture.currentActions());
		for (int i = 0; i < responses.length; i++) {
			int before = s.engine().turn();
			machine.submitAction(s, "A", new TurnTraceTest.Sink());
			assertThat(s.engine().turn()).as("第 %d 回合落地", i + 1).isEqualTo(before + 1);
			assertThat(s.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
		}
		return new Played(s, store);
	}

	@Test
	void endToEndEveryLandedTurnIsOneReplayableLine() throws Exception {
		Path dir = tmp.resolve("traces");
		FileTraceSink sink = new FileTraceSink(dir, 5_242_880, 209_715_200, MAPPER);
		Played p = play(sink, TraceScenarios.RC_OK, new LlmException("断"), TraceScenarios.RC_OK);

		Path file = dir.resolve(p.session().saveId() + ".trace.jsonl");
		TraceFileReader.Result read = TraceFileReader.read(file, MAPPER);
		assertThat(read.partialTailSkipped()).isFalse();
		assertThat(read.traces()).hasSize(p.session().engine().turn()).hasSize(3);
		assertThat(read.traces()).extracting(TurnTrace::path).containsExactly(TurnTrace.PATH_SETTLED,
				TurnTrace.PATH_DEGRADED, TurnTrace.PATH_SETTLED);

		TraceReplayer replayer = new TraceReplayer(
				new EventLoopService(new TurnTraceTest.ScriptedLlm(), new TurnPromptBuilder(REGISTRY), MAPPER), MAPPER,
				REGISTRY, TurnTraceCollector.COMMIT);
		for (TurnTrace t : read.traces()) {
			TraceReplayer.Report r = replayer.replay(t);
			assertThat(r.differences()).as("T%d 回放差异", t.turnBefore()).isEmpty();
			assertThat(r.outcome()).isEqualTo(TraceReplayer.Outcome.CONSISTENT);
		}
		assertThat(warns()).isZero();
	}

	@Test
	void perFileLimitStopsWritingButTurnsKeepLanding() throws Exception {
		Path dir = tmp.resolve("traces");
		FileTraceSink sink = new FileTraceSink(dir, 1, 209_715_200, MAPPER); // 一行都放不下
		Played p = play(sink, TraceScenarios.RC_OK, TraceScenarios.RC_OK, TraceScenarios.RC_OK);
		assertThat(p.store().persisted).isEqualTo(3);
		assertThat(dir.resolve(p.session().saveId() + ".trace.jsonl")).doesNotExist();
		assertThat(warns()).isEqualTo(1);
	}

	@Test
	void totalLimitStopsWritingButTurnsKeepLanding() throws Exception {
		Path dir = tmp.resolve("traces");
		FileTraceSink probe = new FileTraceSink(tmp.resolve("probe"), 5_242_880, 209_715_200, MAPPER);
		Played first = play(probe, TraceScenarios.RC_OK);
		long oneLine = Files.size(tmp.resolve("probe").resolve(first.session().saveId() + ".trace.jsonl"));

		FileTraceSink sink = new FileTraceSink(dir, 5_242_880, oneLine + oneLine / 2, MAPPER); // 容一行
		Played p = play(sink, TraceScenarios.RC_OK, TraceScenarios.RC_OK, TraceScenarios.RC_OK);
		assertThat(p.store().persisted).isEqualTo(3);
		List<String> lines = Files.readAllLines(dir.resolve(p.session().saveId() + ".trace.jsonl"),
				StandardCharsets.UTF_8);
		assertThat(lines).hasSize(1);
		assertThat(warns()).isEqualTo(1);
	}
}
