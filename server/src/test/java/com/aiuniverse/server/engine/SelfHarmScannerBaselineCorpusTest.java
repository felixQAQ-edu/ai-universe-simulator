package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ADR-004 刀 3 · 拿基线语料跑一遍词表,数误报。
 *
 * <p>语料 = {@code docs/adr/ADR-004-baseline-corpus.md}:四个已上线世界在 <b>prompt 硬禁加入之前</b>
 * 的真实样本(2026-09-06 HAR 导出,线上 {@code dcca539})。覆盖 {@code narrative} ×16 /
 * {@code openingNarrative} ×4 / {@code rules[].content} ×32。
 *
 * <p><b>⚠️ 覆盖边界:语料里没有 {@code availableActions}</b> —— 判据的那半边({@code .text} / {@code .hint})
 * <b>量不到,不是量出来是 0</b>。
 *
 * <p><b>扫的是整个语料正文段(含标题与字数标注),不做分条抽取</b>:多扫一些 markdown 标签只会让命中数
 * <b>偏多</b>,方向保守;而写一个 markdown 解析器会引入第二个可能出错的东西,去换一点点精确度。
 *
 * <p><b>本测试是一道闸,不是一次统计</b>:词表将来加词,若那个词在这四局的正常笔法上命中,这里当场变红,
 * 逼出一次「删词还是接受」的决定。ADR-004 已立字:<b>词表变窄是合格的结果,不是失败。</b>
 *
 * <h2>⚠️ 空集不许绿灯</h2>
 * 「读不到文件」「切错了段」都会让扫描对象变成空串,而空串在任何词表下都干净 —— 那是假绿灯(同刀 1 M5 的形状)。
 * 故三道守卫先跑:文件在 / 切得出正文段 / <b>正文段里确实是语料</b>(拿一句已知原文做正对照)。
 * 变异要求:把路径改名 → <b>必须红</b>。
 */
class SelfHarmScannerBaselineCorpusTest {

	/** 语料正文段的起点标记(基线语料文件里的一级标题)。 */
	private static final String BODY_MARKER = "# 语料正文";

	/**
	 * 正对照:局 1 规则怪谈 T1 的一句原文。
	 *
	 * <p>它把守卫从「读到了某个文件」升级为「<b>读到的确实是那份语料</b>」——
	 * 只测长度的话,把路径指到任何一份长文档都能绿。
	 */
	private static final String KNOWN_LINE = "你站在昏黄的前台灯下";

	/** 语料 §四:回合侧非空白字符 3021 / 含段落分隔 3059;正文段另含 world-gen 侧与 markdown 标签,故远大于此。 */
	private static final int MIN_BODY_CHARS = 3000;

	@Test
	void baselineCorpusProducesNoHits() throws IOException {
		String body = corpusBody();

		assertThat(body).as("语料正文段字符数(空集守卫,不许拿空串绿灯)")
				.hasSizeGreaterThan(MIN_BODY_CHARS);
		assertThat(body).as("正对照:正文段里应有局 1 T1 的原文,证明读到的确实是那份语料")
				.contains(KNOWN_LINE);

		List<String> hits = SelfHarmScanner.scan(body);
		assertThat(hits)
				.as("基线语料 %d 字符 × 词表 %d 条 —— 命中即须逐条判「是不是在给一个可照做的方法」",
						body.length(), SelfHarmScanner.METHOD_TOKENS.size())
				.isEmpty();
	}

	/** 从 server 模块向上定位仓库根 docs/adr/(surefire CWD=server 模块目录),照 ContentSafetyPromptLockstepTest 的既有形态。 */
	private String corpusBody() throws IOException {
		String name = "ADR-004-baseline-corpus.md";
		for (Path p : List.of(Path.of("..", "docs", "adr", name), Path.of("docs", "adr", name))) {
			if (Files.exists(p)) {
				String all = Files.readString(p);
				int at = all.indexOf(BODY_MARKER);
				if (at < 0) {
					throw new IOException("语料文件里找不到正文段标记 '" + BODY_MARKER + "':" + p.toAbsolutePath());
				}
				return all.substring(at);
			}
		}
		throw new IOException("找不到 docs/adr/" + name + "(CWD=" + Path.of(".").toAbsolutePath() + ")");
	}
}
