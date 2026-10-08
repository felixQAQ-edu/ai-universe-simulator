package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.persistence.TurnTrace;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-031 刀 5a · 回归夹具回放:{@code src/test/resources/trace-fixtures/*.jsonl} 每一行档 1 回放,<b>差异清单必须为空</b>。
 *
 * <p>「一致」在夹具语境下按差异清单判,不按 {@link TraceReplayer.Outcome}:夹具记的是线上那次的 commit,
 * 而 CI 与本地构建的 commit 不同(本地多为 {@code unknown}),回放器必然判「跨版本」。对回归夹具而言,
 * 跨版本而出现差异<b>正是要抓的回归</b>(或一次有意的落账行为变更 —— 那就该重新生成或撤下该夹具,不是放宽本测试)。
 *
 * <p>目录必须存在(随 README 入库);不存在 → 失败(读不到目录就绿,等于没测)。目录里没有夹具 → 通过并打一行提示。
 */
class TraceFixtureReplayTest {

	static final Path DIR = Path.of("src/test/resources/trace-fixtures");

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ArchetypeRegistry REGISTRY = new ArchetypeRegistry();

	@Test
	void everyCommittedFixtureReplaysWithoutDifferences() throws IOException {
		assertThat(DIR).as("夹具目录随 README 入库;读不到它就绿 = 没测").isDirectory();
		List<Path> files;
		try (Stream<Path> s = Files.list(DIR)) {
			files = s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList();
		}
		if (files.isEmpty()) {
			System.out.println("[trace-fixtures] 目录里没有夹具(*.jsonl),本测试无可回放 —— 通过");
			return;
		}
		int lines = assertFixturesReplay(files);
		System.out.println("[trace-fixtures] " + files.size() + " 个文件、" + lines + " 行,全部回放无差异");
	}

	/** @return 回放的行数 */
	static int assertFixturesReplay(List<Path> files) throws IOException {
		TraceReplayer replayer = new TraceReplayer(
				new EventLoopService(new TurnTraceTest.ScriptedLlm(), new TurnPromptBuilder(REGISTRY), MAPPER),
				MAPPER, REGISTRY, TurnTraceCollector.COMMIT);
		int n = 0;
		for (Path f : files) {
			String content = Files.readString(f, StandardCharsets.UTF_8);
			assertThat(content).as("%s 应以换行结尾(夹具不是线上残档,不容忍半行)", f).endsWith("\n");
			String[] lines = content.split("\n");
			for (int i = 0; i < lines.length; i++) {
				TurnTrace t = TraceFixture.decode(lines[i], MAPPER);
				assertThat(t.saveId()).as("%s:%d saveId 必须是替换后的形态", f, i + 1)
						.startsWith(TraceFixture.SAVE_ID_PREFIX);
				assertThat(replayer.replay(t).differences()).as("%s:%d 档 1 回放差异", f, i + 1).isEmpty();
				n++;
			}
		}
		return n;
	}
}
