package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-031 刀 3 · 测试侧轨迹文件读取(已决 W-10:不进生产装配、不加服务端端点)。
 * 线上取回的 {@code <saveId>.trace.jsonl}(runbook「回合轨迹」)在本地用它逐行解码,再交给回放器。
 *
 * <p><b>半截末行</b>:{@link FileTraceSink} 每行连同换行一次写出,完整的一行必以换行结尾。文件<b>不以换行结尾</b>时,
 * 最后那段就是崩溃留下的半行 —— 跳过它(报告里计数),前面各行照常严格解码。
 * 中间行解码失败仍然抛:那不是崩溃能留下的形状,是文件坏了,不许静默。
 */
public final class TraceFileReader {

	private TraceFileReader() {
	}

	/** @param partialTailSkipped 是否跳过了一条半截末行 */
	public record Result(List<TurnTrace> traces, boolean partialTailSkipped) {
	}

	public static Result read(Path file, ObjectMapper mapper) throws IOException {
		String content = Files.readString(file, StandardCharsets.UTF_8);
		List<TurnTrace> out = new ArrayList<>();
		if (content.isEmpty()) {
			return new Result(out, false);
		}
		boolean complete = content.endsWith("\n");
		String[] lines = content.split("\n", -1);
		// complete 时最后一段是换行之后的空串;否则最后一段是半行。两种情况都不解码最后一段。
		for (int i = 0; i < lines.length - 1; i++) {
			out.add(TurnTraceCodec.decode(lines[i], mapper));
		}
		return new Result(out, !complete);
	}
}
