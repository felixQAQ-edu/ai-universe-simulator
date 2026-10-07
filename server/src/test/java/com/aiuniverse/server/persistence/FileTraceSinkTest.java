package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-031 刀 3 · {@link FileTraceSink} 单元(测试面 10 的写出端)+ 半截末行读取(测试面 9 的后半)+ 启动现值行与开关装配。
 * 回合照常落账的那一半在 {@code eventloop.FileTraceSinkWiringTest}(要真实的 {@code TurnStateMachine})。
 */
class FileTraceSinkTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@TempDir
	Path tmp;

	private final Logger sinkLogger = (Logger) LoggerFactory.getLogger(FileTraceSink.class);
	private final Logger configLogger = (Logger) LoggerFactory.getLogger(TraceSinkConfig.class);
	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void capture() {
		appender = new ListAppender<>();
		appender.start();
		sinkLogger.addAppender(appender);
		configLogger.addAppender(appender);
	}

	@AfterEach
	void detach() {
		sinkLogger.detachAppender(appender);
		configLogger.detachAppender(appender);
		appender.stop();
	}

	private List<String> logsAt(Level level) {
		return appender.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	private TurnTrace trace(String saveId, int turnBefore) {
		ObjectNode pre = mapper.createObjectNode();
		pre.put("filler", "x".repeat(200));
		return new TurnTrace(TurnTrace.SCHEMA, saveId, turnBefore, "2026-10-07T00:00:00Z", "test", "A",
				TurnTrace.PATH_DEGRADED, pre, null, "stream_interrupted", "半句", null, List.of(), 1L, null,
				new TurnTrace.Post("abc", Map.of("hp", 90.0)));
	}

	private int lineBytes(TurnTrace t) {
		return (TurnTraceCodec.encode(t, mapper) + "\n").getBytes(StandardCharsets.UTF_8).length;
	}

	private long lines(Path file) throws Exception {
		return Files.exists(file) ? Files.readAllLines(file).size() : 0;
	}

	// ── 写出形状 ───────────────────────────────────────────────────────────

	@Test
	void appendsOneLinePerTurnToSaveIdFile() throws Exception {
		Path dir = tmp.resolve("traces");
		FileTraceSink sink = new FileTraceSink(dir, 5_242_880, 209_715_200, mapper);
		sink.record(trace("save-a", 0));
		sink.record(trace("save-a", 1));
		sink.record(trace("save-b", 0));
		Path a = dir.resolve("save-a.trace.jsonl");
		assertThat(Files.readString(a)).endsWith("\n");
		TraceFileReader.Result r = TraceFileReader.read(a, mapper);
		assertThat(r.partialTailSkipped()).isFalse();
		assertThat(r.traces()).extracting(TurnTrace::turnBefore).containsExactly(0, 1);
		assertThat(lines(dir.resolve("save-b.trace.jsonl"))).isEqualTo(1);
		assertThat(logsAt(Level.WARN)).isEmpty();
	}

	@Test
	void pathySaveIdIsRefused() {
		FileTraceSink sink = new FileTraceSink(tmp.resolve("traces"), 5_242_880, 209_715_200, mapper);
		assertThatThrownBy(() -> sink.record(trace("../escape", 0))).isInstanceOf(IllegalArgumentException.class);
	}

	// ── 测试面 9 后半:半截末行 ─────────────────────────────────────────

	@Test
	void partialLastLineIsSkippedAndEarlierLinesStillRead() throws Exception {
		Path dir = tmp.resolve("traces");
		FileTraceSink sink = new FileTraceSink(dir, 5_242_880, 209_715_200, mapper);
		sink.record(trace("save-c", 0));
		sink.record(trace("save-c", 1));
		Path file = dir.resolve("save-c.trace.jsonl");
		String full = TurnTraceCodec.encode(trace("save-c", 2), mapper);
		Files.writeString(file, full.substring(0, full.length() / 2), java.nio.file.StandardOpenOption.APPEND);

		TraceFileReader.Result r = TraceFileReader.read(file, mapper);
		assertThat(r.partialTailSkipped()).isTrue();
		assertThat(r.traces()).extracting(TurnTrace::turnBefore).containsExactly(0, 1);
	}

	// ── 测试面 10:上限 ────────────────────────────────────────────────

	@Test
	void perFileLimitStopsThatSaveOnlyAndWarnsOnce() throws Exception {
		Path dir = tmp.resolve("traces");
		int one = lineBytes(trace("save-f", 0));
		FileTraceSink sink = new FileTraceSink(dir, 2L * one, 209_715_200, mapper); // 恰好容两行
		for (int i = 0; i < 5; i++) {
			sink.record(trace("save-f", i));
		}
		sink.record(trace("save-g", 0)); // 别的局不受影响
		assertThat(lines(dir.resolve("save-f.trace.jsonl"))).isEqualTo(2);
		assertThat(lines(dir.resolve("save-g.trace.jsonl"))).isEqualTo(1);
		assertThat(logsAt(Level.WARN)).hasSize(1).first().asString().contains("save-f").contains("单文件上限");
	}

	@Test
	void totalLimitStopsEverythingAndWarnsOnce() throws Exception {
		Path dir = tmp.resolve("traces");
		int one = lineBytes(trace("save-t1", 0));
		FileTraceSink sink = new FileTraceSink(dir, 5_242_880, 3L * one, mapper); // 总共容三行
		sink.record(trace("save-t1", 0));
		sink.record(trace("save-t2", 0));
		sink.record(trace("save-t3", 0));
		sink.record(trace("save-t4", 0)); // 超总量
		sink.record(trace("save-t1", 1));
		sink.record(trace("save-t5", 0));
		assertThat(lines(dir.resolve("save-t4.trace.jsonl"))).isZero();
		assertThat(lines(dir.resolve("save-t5.trace.jsonl"))).isZero();
		assertThat(lines(dir.resolve("save-t1.trace.jsonl"))).isEqualTo(1);
		assertThat(sink.totalBytes()).isEqualTo(3L * one);
		assertThat(logsAt(Level.WARN)).hasSize(1).first().asString().contains("总量");
	}

	/** 总量从启动时目录里已有的 *.trace.jsonl 算起(不递归,其他后缀不算)。 */
	@Test
	void startupTotalCountsExistingTraceFilesOnly() throws Exception {
		Path dir = Files.createDirectories(tmp.resolve("traces"));
		Files.writeString(dir.resolve("old.trace.jsonl"), "x".repeat(100));
		Files.writeString(dir.resolve("notes.txt"), "y".repeat(1000));
		Files.createDirectories(dir.resolve("sub"));
		Files.writeString(dir.resolve("sub").resolve("deep.trace.jsonl"), "z".repeat(1000));
		FileTraceSink sink = new FileTraceSink(dir, 5_242_880, 209_715_200, mapper);
		assertThat(sink.totalBytes()).isEqualTo(100);
		// 启动时已在上限上方 → 第一条就停写
		FileTraceSink full = new FileTraceSink(dir, 5_242_880, 150, mapper);
		full.record(trace("save-x", 0));
		assertThat(dir.resolve("save-x.trace.jsonl")).doesNotExist();
	}

	// ── 启动现值行 + 开关装配 ──────────────────────────────────────────

	@Test
	void startupLineCarriesResolvedValues() {
		Path dir = tmp.resolve("custom-traces");
		new TraceSinkConfig().traceSink(true, dir.toString(), 1234, 56789, mapper);
		assertThat(logsAt(Level.INFO)).containsExactly("[trace] 目录 = " + dir.toAbsolutePath().normalize()
				+ " enabled=true 单文件上限=1234 总量上限=56789 当前总量=0");
	}

	@Test
	void disabledWiresNoopAndCreatesNothing() {
		Path dir = tmp.resolve("never");
		TraceSink sink = new TraceSinkConfig().traceSink(false, dir.toString(), 1234, 56789, mapper);
		assertThat(sink).isSameAs(TraceSink.NOOP);
		sink.record(trace("save-n", 0));
		assertThat(dir).doesNotExist();
		assertThat(logsAt(Level.INFO)).singleElement().asString().contains("enabled=false")
				.contains("单文件上限=1234").contains("总量上限=56789");
	}

	@Test
	void traceDirUnderWebRootRefusesToStartAndCreatesNothing() {
		Path root = tmp.resolve("static");
		Path dir = root.resolve("traces");
		assertThatThrownBy(() -> new FileTraceSink(dir, 1234, 56789, mapper, List.of(root)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("回合轨迹目录")
				.hasMessageContaining("aiuniverse.trace.dir");
		assertThat(dir).doesNotExist();
	}

	/** 目录缺省 = 存档目录下的 traces/(线上 AIUNIVERSE_SESSION_STORE_DIR=/data → /data/traces)。 */
	@Test
	void defaultDirIsTracesUnderSessionStoreDir() {
		Path store = tmp.resolve("store");
		new ApplicationContextRunner().withUserConfiguration(TraceSinkConfig.class)
				.withBean(ObjectMapper.class, () -> mapper)
				.withPropertyValues("aiuniverse.session.store-dir=" + store)
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx.getBean(TraceSink.class)).isInstanceOf(FileTraceSink.class);
					assertThat(((FileTraceSink) ctx.getBean(TraceSink.class)).dir())
							.isEqualTo(store.resolve("traces").toAbsolutePath().normalize());
				});
	}

	/** env 覆盖生效(以 Spring 属性形态注入,与 env 走同一条绑定):四个值都按配置解析。 */
	@Test
	void propertiesOverrideAllFour() {
		Path dir = tmp.resolve("elsewhere");
		new ApplicationContextRunner().withUserConfiguration(TraceSinkConfig.class)
				.withBean(ObjectMapper.class, () -> mapper)
				.withPropertyValues("aiuniverse.trace.dir=" + dir, "aiuniverse.trace.max-file-bytes=111",
						"aiuniverse.trace.max-total-bytes=222")
				.run(ctx -> assertThat(logsAt(Level.INFO)).contains("[trace] 目录 = "
						+ dir.toAbsolutePath().normalize() + " enabled=true 单文件上限=111 总量上限=222 当前总量=0"));
		new ApplicationContextRunner().withUserConfiguration(TraceSinkConfig.class)
				.withBean(ObjectMapper.class, () -> mapper)
				.withPropertyValues("aiuniverse.trace.enabled=false", "aiuniverse.trace.dir=" + tmp.resolve("off"))
				.run(ctx -> assertThat(ctx.getBean(TraceSink.class)).isSameAs(TraceSink.NOOP));
		assertThat(tmp.resolve("off")).doesNotExist();
	}
}
