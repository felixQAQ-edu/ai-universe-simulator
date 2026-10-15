package com.aiuniverse.server.persistence;

import java.nio.file.Path;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

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
 */
public final class DataStatsTool {

	private DataStatsTool() {
	}

	public static void main(String[] args) throws Exception {
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
