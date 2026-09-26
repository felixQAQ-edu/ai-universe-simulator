package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * ADR-025 刀 2 消毒硬闸的<b>源码级</b>那一半:历史读取实现的源文件里不许出现快照列的列名(不分大小写,
 * 注释也算)。响应级那一半({@code JdbcNarrativeHistoryReaderTest#responseNeverLeaksHiddenFieldsFromSnapshot})
 * 只能看见「漏进了响应」;一条只把快照列 SELECT 出来、暂时还没放进响应的 SQL,响应级断言看不见 ——
 * 而「读进来」本身就是决策 1 禁止的那一步。不需要 Docker。
 */
class HistoryReaderSourceGuardTest {

	private static final Path SOURCE = Path.of(
			"src/main/java/com/aiuniverse/server/persistence/JdbcNarrativeHistoryReader.java");

	@Test
	void historyReaderSourceNeverMentionsTheSnapshotColumn() throws Exception {
		String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
		// 先证明读到的确实是那个实现(空文件 / 读错文件上的 doesNotContain 是假绿灯)。
		assertThat(src).contains("class JdbcNarrativeHistoryReader").contains("FROM game_event")
				.contains("FROM game_session");
		assertThat(src.toLowerCase()).doesNotContain("snapshot");
	}
}
