package com.aiuniverse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * ADR-004 刀 1 · <b>{@code moderation} 包不得复活</b>的看门人。
 *
 * <p>撤掉的是一个<b>恒等函数</b>({@code review(text) { return text; }}),故这一刀<b>行为零变化</b>——
 * 也因此<b>没有任何行为测试会因为它回来而变红</b>:有人把接口和 no-op 实现原样加回去、
 * 再把四个调用点接上,整套 407 个用例照样全绿。<b>只有源码级断言抓得到「这个东西还在不在」</b>
 * (同 ADR-021 刀 2 的 {@code "vigor"}、刀 1 的族层,以及 ADR-022 的
 * {@code GameControllerNoThreadPoolSourceTest} 三条先例)。
 *
 * <p>⚠️ <b>人工 grep 只能证明「这次删干净了」,它守不住下一个人</b> —— 一次性核对不是守护
 * (「没有断言在看的探针不是守护」)。本条把那次核对变成会红的闸。
 *
 * <p>⚠️ <b>两个探针陷阱在写的时候就堵上</b>:
 * <ol>
 *   <li><b>判据取完整标识符 {@code ModerationGateway},而不是照抄人工核对时那句
 *       {@code grep -i moderation}。</b> {@code WorldGenPromptBuilder} 有个与内容安全<b>完全无关</b>的
 *       融合槽字段 {@code thresholdModeration}(「门槛适度示例」,早于 ADR-004)——
 *       <b>不区分大小写的判据在干净树上就有 2 命中,永远不可能为零</b>,只会逼下一个人加排除项。
 *       <br>⚠️ <b>如实标</b>:大小写敏感的裸 {@code moderation} 今天<b>实测也是 0</b>
 *       (变异验证 M4:把下面那句换成它,干净树上<b>仍然是绿的</b>)。故「取完整标识符」是
 *       <b>设计取舍不是被变异证明的必要性</b> —— 理由是它<b>指向被撤掉的那个东西本身</b>,
 *       而裸片段与 {@code thresholdModeration} 只差一个大写字母。</li>
 *   <li><b>作用域写死 = {@code src/main/java} 整棵树,而本测试住在 {@code src/test}。</b>
 *       于是它<b>结构上不会自命中</b>(断言自己必然含这个字符串),
 *       因此<b>不需要任何排除项 —— 而排除项就是洞</b>
 *       (ADR-022 刀 2「全仓扫描会打到准入类自己」的原样规避)。</li>
 * </ol>
 *
 * <p>另按 ADR-018 §4.14 那族的教训:<b>先证明真的遍历到了源码</b>,否则目录改名 / CWD 变化会让
 * 遍历返回空集,而空集上的 {@code doesNotContain} 是一盏假绿灯。
 *
 * <p><b>解冻</b>:重建条件见 ADR-004 §最终决策三 (a)(b)(c)(=§重新审视「其余四条」的前三条)。
 * 那三条任一命中 → 连同本测试一并删除,<b>并重新划接缝位置</b>(不保证还是原来那四个调用点)。
 */
class ModerationSeamRemovedSourceTest {

	private static final Path MAIN = Path.of("src/main/java");

	@Test
	void moderationPackageIsGone() {
		assertThat(MAIN)
				.as("main 源码根不存在 = 下面两条什么也没在看(CWD=%s)", Path.of(".").toAbsolutePath())
				.isDirectory();
		assertThat(MAIN.resolve("com/aiuniverse/server/moderation"))
				.as("ADR-004 §最终决策三:接缝已撤,包不得复活")
				.doesNotExist();
	}

	@Test
	void noMainSourceReferencesTheSeam() throws IOException {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(MAIN)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
		}
		// 先证明遍历到了东西,且抓到了当年唯一的消费方(改名 / 移动 → 下面的循环空转)。
		assertThat(sources).as("main 源码遍历为空 = 假绿").hasSizeGreaterThan(30);
		assertThat(sources)
				.as("当年四个调用点的宿主应在遍历范围内")
				.anyMatch(p -> p.endsWith("GameInitService.java"));

		for (Path p : sources) {
			assertThat(Files.readString(p, StandardCharsets.UTF_8))
					.as("%s 仍引用已撤的审核接缝(ADR-004 刀 1)", p)
					.doesNotContain("ModerationGateway");
		}
	}
}
