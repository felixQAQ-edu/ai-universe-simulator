package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link TraceSink} 的文件实现(ADR-031 刀 3,已决 W-1 / W-3 / W-7)。
 *
 * <ul>
 *   <li><b>落点</b>:目录 {@code aiuniverse.trace.dir}(默认 = 存档目录下的 {@code traces/};线上 {@code /data/traces/}),
 *       每局一个 {@code <saveId>.trace.jsonl},每个已落地回合追加一行({@link TurnTraceCodec} 编码 + 换行)。
 *       子目录不进 {@link FileSessionStore#loadAll} 的候选集({@code Files.list} 不递归),故不构成 ADR-022
 *       「第三个租户」(W-3 的前提,由测试面 9 钉住)。</li>
 *   <li><b>写法</b>:每次写都「打开 → 追加 → 关闭」,不长期持有文件句柄;一整行(含换行)一次 write 写出。
 *       崩溃最多留下一条没有换行结尾的半截末行 —— 读取方跳过它,前面各行照常可用。</li>
 *   <li><b>上限,不做任何自动删除(W-7)</b>:单文件写入后会超过 {@code maxFileBytes} → 本条不写,该局只 WARN 一次;
 *       目录总量写入后会超过 {@code maxTotalBytes} → 本条与此后所有轨迹都不写,只 WARN 一次。
 *       总量 = 启动时统计目录下 {@code *.trace.jsonl}(不递归)之和 + 本进程写入的累加;运行中被手动删掉的文件
 *       不会让累计值回落,要重启才重新统计(runbook「回合轨迹」一节写明)。</li>
 *   <li><b>失败</b>:写失败抛 {@link UncheckedIOException},由唯一调用方 {@code TurnStateMachine} 吞并 WARN(刀 1 口径),
 *       回合、相位、存档不受影响。被上限挡下不是失败:不抛,只按上面那样 WARN 一次。</li>
 *   <li><b>保密级别同存档</b>({@code pre} 是视图 1 全量):目录与存档目录同受「必须在 web 根之外」的启动断言。</li>
 * </ul>
 *
 * <p>{@code record} 加锁:总量的「判断 + 累加」必须原子,不同局的 worker 线程会并发写不同文件。锁内只有一次小文件追加,
 * 量级同 persist。
 */
public class FileTraceSink implements TraceSink {

	private static final Logger log = LoggerFactory.getLogger(FileTraceSink.class);

	/** 与 {@link FileSessionStore} 同一条:saveId 只允许 UUID 形字符,防目录逃逸。 */
	private static final Pattern SAFE_SAVE_ID = Pattern.compile("[A-Za-z0-9-]+");

	static final String SUFFIX = ".trace.jsonl";

	private final Path dir;
	private final long maxFileBytes;
	private final long maxTotalBytes;
	private final ObjectMapper mapper;

	private long totalBytes;
	private boolean totalStopped;
	private final Set<String> fileStoppedWarned = new HashSet<>();

	public FileTraceSink(Path dir, long maxFileBytes, long maxTotalBytes, ObjectMapper mapper) {
		this(dir, maxFileBytes, maxTotalBytes, mapper,
				FileSessionStore.classpathWebRoots(FileTraceSink.class.getClassLoader()));
	}

	/** 包内可见只为测试注入 web 根(断言接线的守护);生产经上面那个构造器取 classpath 的 web 根。 */
	FileTraceSink(Path dir, long maxFileBytes, long maxTotalBytes, ObjectMapper mapper, List<Path> webRoots) {
		this.dir = dir.toAbsolutePath().normalize();
		this.maxFileBytes = maxFileBytes;
		this.maxTotalBytes = maxTotalBytes;
		this.mapper = mapper;
		FileSessionStore.assertOutsideWebRoot(this.dir, webRoots, "回合轨迹目录",
				"aiuniverse.trace.dir(env AIUNIVERSE_TRACE_DIR)");
		try {
			Files.createDirectories(this.dir);
		} catch (IOException e) {
			throw new IllegalStateException("回合轨迹目录不可创建:" + this.dir, e);
		}
		this.totalBytes = currentTotal(this.dir);
		log.info("[trace] 目录 = {} enabled=true 单文件上限={} 总量上限={} 当前总量={}", this.dir,
				maxFileBytes, maxTotalBytes, totalBytes);
	}

	Path dir() {
		return dir;
	}

	@Override
	public synchronized void record(TurnTrace trace) {
		String saveId = trace.saveId();
		if (saveId == null || !SAFE_SAVE_ID.matcher(saveId).matches()) {
			throw new IllegalArgumentException("saveId 含非法字符,拒写轨迹(防目录逃逸):" + saveId);
		}
		if (totalStopped) {
			return;
		}
		byte[] line = (TurnTraceCodec.encode(trace, mapper) + "\n").getBytes(StandardCharsets.UTF_8);
		if (totalBytes + line.length > maxTotalBytes) {
			totalStopped = true;
			log.warn("[trace] 轨迹目录总量将超上限,此后全部停写(不自动删除,手动清理见 runbook「回合轨迹」):"
					+ "当前总量={} 本条={} 上限={} 目录={}", totalBytes, line.length, maxTotalBytes, dir);
			return;
		}
		Path file = dir.resolve(saveId + SUFFIX);
		try {
			long size = Files.exists(file) ? Files.size(file) : 0L;
			if (size + line.length > maxFileBytes) {
				if (fileStoppedWarned.add(saveId)) {
					log.warn("[trace] save={} 轨迹文件将超单文件上限,该局此后停写:当前={} 本条={} 上限={}", saveId, size,
							line.length, maxFileBytes);
				}
				return;
			}
			Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
					StandardOpenOption.WRITE);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		totalBytes += line.length;
	}

	/** 启动统计:目录下(不递归)所有 {@code *.trace.jsonl} 的大小之和。 */
	static long currentTotal(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			long sum = 0;
			for (Path p : files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).toList()) {
				sum += Files.size(p);
			}
			return sum;
		} catch (IOException e) {
			throw new IllegalStateException("回合轨迹目录不可统计:" + dir, e);
		}
	}

	/** 测试用:当前累计值。 */
	synchronized long totalBytes() {
		return totalBytes;
	}
}
