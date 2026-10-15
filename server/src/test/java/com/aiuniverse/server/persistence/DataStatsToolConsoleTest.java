package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-033 · 工具控制台不回显标识(2026-10-15 真实运行时发现:报告干净,stdout 却打出了
 * {@code [box-scene] save=<UUID> 旧局} WARN —— 那是解码旧局时引擎自己打的日志)。
 * 走 {@link DataStatsTool#main} 入口,捕获 stdout 与 stderr。
 */
class DataStatsToolConsoleTest {

	static final String SID = "0b5e3c2a-8888-4a1a-9a1a-000000000000";
	static final Pattern UUID = Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	@TempDir
	Path tmp;

	@Test
	void consoleCarriesNoSaveIdNorInputFileNameWhenDecodingLegacyAnimalSave() throws Exception {
		Path data = Files.createDirectories(tmp.resolve("data"));
		String fileName = SID + ".json";
		SyntheticSaves.write(data, fileName, new SyntheticSaves().legacyAnimal(SID));
		Path out = tmp.resolve("report.txt");

		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream oldOut = System.out;
		PrintStream oldErr = System.err;
		PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			DataStatsTool.main(new String[] { "--in", data.toString(), "--out", out.toString() });
		} finally {
			System.setOut(oldOut);
			System.setErr(oldErr);
		}
		String console = captured.toString(StandardCharsets.UTF_8);

		assertThat(console).contains("统计报告: ");
		assertThat(UUID.matcher(console).find()).as("控制台不得出现 UUID 形态的字符串:%n%s", console).isFalse();
		assertThat(console).doesNotContain(fileName);
		assertThat(Files.readString(out)).doesNotContain(SID);
	}
}
