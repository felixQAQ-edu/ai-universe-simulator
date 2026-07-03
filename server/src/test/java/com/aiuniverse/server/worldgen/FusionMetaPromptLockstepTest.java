package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

/**
 * ADR-013 混合模式融合 meta-prompt · lockstep 守护(round 1:修仙×规则怪谈=识海遗蜕)。
 *
 * <p><b>lockstep</b>:融合 meta-prompt 的核心短语在两处({@code prompts/world-gen.md} 「## 混合模式 · 世界融合」段 /
 * 运行时 {@link WorldGenPromptBuilder#buildFusionPrompt})逐条一致——防止只改 .md 运行时失效、或只改一边漂移。
 */
class FusionMetaPromptLockstepTest {

	private final WorldGenPromptBuilder builder = new WorldGenPromptBuilder(new ArchetypeRegistry());

	/** 融合 meta-prompt 的共享核心短语(设定内核 + 三根杠杆 + 护道结局 + 承重接缝),两处都必须含。 */
	private static final String[] LOCKSTEP_PHRASES = {
			"识海遗蜕",                       // 设定内核
			"真传心法",                       // 守则融合:真=真传心法
			"心魔伪笔",                       // 守则融合:假=心魔伪笔
			"先辨体系、再辨真假",              // 杠杆 (b)
			"真假对射、以修仙常识裁",          // 杠杆 (c)
			"多谢道友护道",                   // 护道结局
			"绝不写精确成功率数字",            // 承重接缝:守则不写精确成功率/判定规则(守 ADR-011)
			"破境元婴以上者所书",              // 承重接缝:门槛感只作叙事毒饵、不承诺引擎判定
			"据境界数值拦截破境或改变判定",    // 承重接缝:不承诺引擎据境界拦破境(c-对4 去机制暗示)
			"不要求任何跨回合追踪",            // 承重接缝:回溯守则纯叙事、无跨回合追踪
	};

	@Test
	void fusionMetaPromptStaysLockstepBetweenMdAndRuntime() throws IOException {
		String worldMd = readMd("world-gen.md");
		String runtime = builder.buildFusionPrompt("cultivation", "rules_creepy");

		for (String phrase : LOCKSTEP_PHRASES) {
			assertThat(worldMd).as("world-gen.md 融合段含 lockstep 短语:%s", phrase).contains(phrase);
			assertThat(runtime).as("WorldGenPromptBuilder 融合 meta-prompt 含:%s", phrase).contains(phrase);
		}
	}

	@Test
	void hybridDispatchGoesThroughFusionForTwoArchetypes() {
		// buildWorldPrompt(List) 长度 2 → 融合分支(与直接 buildFusionPrompt 同结果)。
		String viaList = builder.buildWorldPrompt(List.of("cultivation", "rules_creepy"));
		assertThat(viaList).contains("识海遗蜕").contains("\"hybrid\"");
	}

	/** 从 server 模块向上定位仓库根 prompts/<name>(surefire CWD=server 模块目录)。 */
	private String readMd(String name) throws IOException {
		for (Path p : new Path[] { Path.of("..", "prompts", name), Path.of("prompts", name) }) {
			if (Files.exists(p)) {
				return Files.readString(p);
			}
		}
		throw new IOException("找不到 prompts/" + name + "(CWD=" + Path.of(".").toAbsolutePath() + ")");
	}
}
