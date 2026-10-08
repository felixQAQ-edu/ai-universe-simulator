package com.aiuniverse.server.eventloop;

import java.nio.file.Path;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-031 刀 5a · {@link TraceFixtureConverter} 的命令行入口(测试侧,不进生产装配;W-10)。
 * 运行命令见 runbook §七「转换为回归夹具」。
 *
 * <pre>
 * 参数:--in &lt;原始 .trace.jsonl&gt; --name &lt;夹具名,[a-z0-9-]&gt; --out &lt;夹具 .jsonl&gt;
 * </pre>
 *
 * 拒绝时抛异常(exec:java 随之以非零退出),报告照样写在 {@code <out>.report.txt}。
 */
public final class TraceFixtureTool {

	private TraceFixtureTool() {
	}

	public static void main(String[] args) throws Exception {
		String in = null;
		String name = null;
		String out = null;
		for (int i = 0; i + 1 < args.length; i += 2) {
			switch (args[i]) {
				case "--in" -> in = args[i + 1];
				case "--name" -> name = args[i + 1];
				case "--out" -> out = args[i + 1];
				default -> throw new IllegalArgumentException("不认识的参数 " + args[i]);
			}
		}
		if (in == null || name == null || out == null || args.length != 6) {
			throw new IllegalArgumentException("用法:--in <原始 .trace.jsonl> --name <夹具名> --out <夹具 .jsonl>");
		}
		Path repoRoot = TraceFixtureConverter.findRepoRoot(Path.of("").toAbsolutePath());
		TraceFixtureConverter.Result r = new TraceFixtureConverter(new ObjectMapper(), new ArchetypeRegistry(), repoRoot)
				.convert(Path.of(in), name, Path.of(out));
		System.out.println(r.reportText());
		System.out.println("报告: " + r.report().toAbsolutePath());
		if (!r.written()) {
			throw new IllegalStateException("拒绝写出夹具:" + r.refusals());
		}
		System.out.println("夹具: " + r.fixture().toAbsolutePath() + "(核对报告后再手动拷入仓库)");
	}
}
