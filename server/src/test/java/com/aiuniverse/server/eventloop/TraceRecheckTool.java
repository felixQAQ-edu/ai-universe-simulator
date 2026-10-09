package com.aiuniverse.server.eventloop;

import java.nio.file.Path;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

import tools.jackson.databind.ObjectMapper;

/**
 * 复验短局 · {@link TraceRecheck} 的命令行入口(测试侧,不进生产装配;W-10)。
 * 运行命令见 {@code docs/adr028-manual-runs.md} §九。
 *
 * <pre>
 * 参数:--in &lt;原始 .trace.jsonl&gt; --out &lt;输出前缀&gt;   (报告写到 &lt;输出前缀&gt;.recheck.txt)
 * </pre>
 *
 * 输入或输出在仓库目录之内 → 抛异常(exec:java 随之以非零退出)。报告只写文件,不打印到控制台。
 */
public final class TraceRecheckTool {

	private TraceRecheckTool() {
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
			throw new IllegalArgumentException("用法:--in <原始 .trace.jsonl> --out <输出前缀>");
		}
		Path repoRoot = TraceFixtureConverter.findRepoRoot(Path.of("").toAbsolutePath());
		Path report = new TraceRecheck(new ObjectMapper(), new ArchetypeRegistry()).run(Path.of(in), Path.of(out),
				repoRoot);
		System.out.println("核读报告: " + report.toAbsolutePath());
	}
}
