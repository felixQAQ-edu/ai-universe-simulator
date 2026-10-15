package com.aiuniverse.server.persistence;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-033 · /data 只读统计的命令行入口(测试侧,不进生产装配;形态照 {@code TraceRecheckTool})。
 * 运行命令见 ADR-033「运行方式」。
 *
 * <pre>
 * 参数:--in &lt;线上 /data 拷贝的根目录&gt; --out &lt;报告文件&gt;
 * </pre>
 *
 * 输入或输出在仓库目录之内(或 web 根之下)、写出前扫描命中 → 抛异常(exec:java 随之以非零退出),不写报告。
 * 报告只写文件;控制台只打印报告路径,不回显输入文件名。
 * <p>
 * 运行期间关掉引擎 / 持久化 / 事件循环三个包的日志:解码存档时这些包自己会打带 saveId 的日志
 * (如《动物人生》旧局的 {@code [box-scene] save=… 旧局} WARN),而 ADR-033 立字「任何地方不回显标识」——
 * 报告干净而控制台泄漏,同样算泄漏(2026-10-15 真实运行时发现)。只改本进程内的 logger 级别、结束后还原,
 * 不碰任何 logback 配置文件。
 */
public final class DataStatsTool {

	private DataStatsTool() {
	}

	/** 运行期间静默的包。只读工具不需要它们的日志,而它们会把 saveId 打到控制台。 */
	static final List<String> SILENCED_PACKAGES = List.of("com.aiuniverse.server.engine",
			"com.aiuniverse.server.persistence", "com.aiuniverse.server.eventloop");

	public static void main(String[] args) throws Exception {
		Map<Logger, Level> previous = new LinkedHashMap<>();
		for (String name : SILENCED_PACKAGES) {
			Logger logger = (Logger) LoggerFactory.getLogger(name);
			previous.put(logger, logger.getLevel());
			logger.setLevel(Level.OFF);
		}
		try {
			run(args);
		} finally {
			previous.forEach(Logger::setLevel);
		}
	}

	private static void run(String[] args) throws Exception {
		String in = null;
		String out = null;
		for (int i = 0; i + 1 < args.length; i += 2) {
			switch (args[i]) {
				case "--in" -> in = args[i + 1];
				case "--out" -> out = args[i + 1];
				default -> throw new IllegalArgumentException("不认识的参数 " + args[i]);
			}
		}
		if (in == null || out == null || args.length != 4) {
			throw new IllegalArgumentException("用法:--in <线上 /data 拷贝的根目录> --out <报告文件>");
		}
		Path report = new DataStats(new ObjectMapper(), new ArchetypeRegistry(), DataStats.priceFromApplicationYml())
				.run(Path.of(in), Path.of(out), MigrationPathGate.strict());
		System.out.println("统计报告: " + report);
	}
}
