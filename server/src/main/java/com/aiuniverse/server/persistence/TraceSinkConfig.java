package com.aiuniverse.server.persistence;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link TraceSink} 装配(ADR-031 刀 3):默认 {@link FileTraceSink};{@code aiuniverse.trace.enabled=false}
 * (env {@code AIUNIVERSE_TRACE_ENABLED=false})装 {@link TraceSink#NOOP} —— 应急关闭用,不改代码、不创建目录。
 *
 * <p>两个 profile 一样装(已决 W-1:落点 = 文件,不做仅 pg 的落点)。默认值与 {@code application.yml} 一致;
 * 目录默认 = 存档目录({@code aiuniverse.session.store-dir})下的 {@code traces/},故线上
 * {@code AIUNIVERSE_SESSION_STORE_DIR=/data} → {@code /data/traces}。
 *
 * <p><b>两类启动失败分开处理(ADR-031 刀 3 补)</b>:轨迹目录位于 web 根之下 → {@link IllegalStateException}
 * 照旧抛出、不启动(保密防线,与存档目录同口径);目录不可创建 / 不可统计 → {@link TraceDirUnavailableException},
 * 这里接住、打一条 ERROR、装配 {@link TraceSink#NOOP},服务照常启动(线上单机,轨迹的失败不得让整个服务下线)。
 * 区分靠异常类型,不靠消息字符串。
 */
@Configuration
public class TraceSinkConfig {

	private static final Logger log = LoggerFactory.getLogger(TraceSinkConfig.class);

	@Bean
	TraceSink traceSink(@Value("${aiuniverse.trace.enabled:true}") boolean enabled,
			@Value("${aiuniverse.trace.dir:${aiuniverse.session.store-dir:./data}/traces}") String dir,
			@Value("${aiuniverse.trace.max-file-bytes:5242880}") long maxFileBytes,
			@Value("${aiuniverse.trace.max-total-bytes:209715200}") long maxTotalBytes, ObjectMapper mapper) {
		Path path = Path.of(dir).toAbsolutePath().normalize();
		if (!enabled) {
			log.info("[trace] 目录 = {} enabled=false 单文件上限={} 总量上限={} 当前总量=-(未启用:不写、不建目录)", path,
					maxFileBytes, maxTotalBytes);
			return TraceSink.NOOP;
		}
		return fileOrNoop(path, maxFileBytes, maxTotalBytes, mapper,
				FileSessionStore.classpathWebRoots(TraceSinkConfig.class.getClassLoader()));
	}

	/** 包内可见只为测试注入 web 根;生产经 {@link #traceSink} 取 classpath 的 web 根。 */
	static TraceSink fileOrNoop(Path path, long maxFileBytes, long maxTotalBytes, ObjectMapper mapper,
			List<Path> webRoots) {
		try {
			return new FileTraceSink(path, maxFileBytes, maxTotalBytes, mapper, webRoots);
		} catch (TraceDirUnavailableException e) {
			// web 根断言失败是 IllegalStateException,不在这里接住 —— 那一类照旧拒启。
			log.error("[trace] 目录不可用,本次进程不写轨迹:{}", e.getMessage(), e);
			return TraceSink.NOOP;
		}
	}
}
