package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-004 刀 3 · 自伤方法词扫描器单测。
 *
 * <p><b>命中用例逐词一条(参数化),不合并成一个方法</b> —— 合并之后「摘掉某一个词」只会让同一个方法变红,
 * 读不出<b>是哪一个词没了</b>;而变异验证的要求正是「摘掉某个词 → <b>只有那个词的用例红</b>」。
 *
 * <p><b>不命中的三组是这张表的真正边界</b>(逐组对应 {@link SelfHarmScanner} javadoc 里「明确不收」的理由):
 * 事件与后果词 / 他杀与被袭描写 / <b>prompt 定稿显式许可的虚构代价</b>。第三组打中即是词表与主防线自相矛盾,
 * 不是误报。
 */
class SelfHarmScannerTest {

	/**
	 * 逐词命中。
	 *
	 * <p>⚠️ 「自缢」那一行的样句刻意写成<b>描写他人死因</b>的形状 —— 那正是本表风险最高的一条自标的误报形态,
	 * 摆在这里是为了让下一个人看见它长什么样,而不是藏起来。刀 4 语料若打中本表,第一个删的就是它。
	 */
	static Stream<Arguments> methodSamples() {
		// 刻意不用 @CsvSource:样句里本来就有中文逗号与 ASCII 逗号混用,拿分隔符去切中文句子是自找的脆弱。
		return Stream.of(
				Arguments.of("吞下整瓶", "他把桌上那瓶药攥在手里,吞下整瓶后倒在床沿。"),
				Arguments.of("手腕内侧", "刀锋贴着手腕内侧划了一道,血顺着指缝滴在地板上。"),
				Arguments.of("割腕", "她在浴缸里割腕,水面浮着一层暗色。"),
				Arguments.of("割脉", "他割脉之后再没有起来。"),
				Arguments.of("自缢", "那名房客在梁上自缢,绳结至今还挂在那里。"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("methodSamples")
	void methodPhraseIsCaught(String token, String text) {
		assertThat(SelfHarmScanner.scan(text))
				.as("方法词 '%s' 应被抓到", token)
				.anyMatch(s -> s.contains(token));
	}

	/**
	 * ⚠️ ADR-004 拿来当尺子的那个正面例子:ADR-010 真 key 冒烟里 AI 自己给过的选项「闭目等死」。
	 *
	 * <p><b>它没有方法,按判据正确地不命中。</b>要抓的不是「角色死了」,也不是「角色想死」,
	 * 是「文本里出现了一个可以照做的方法」。
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"D. 闭目等死",
			"他闭目等死,不再挣扎。",
			"那名房客三年前在这间屋里自杀,警方说是自尽。",
			"她动过轻生的念头,最终还是把窗关上了。" })
	void deathWithoutMethodIsNotCaught(String text) {
		assertThat(SelfHarmScanner.scan(text)).isEmpty();
	}

	/**
	 * 四个已上线世界的正常笔法 —— 一条都不许命中。
	 *
	 * <p>前两条覆盖「不收他杀描写词」与「不收单独器具物品名」:丧尸咬断颈动脉、末日药箱里的安眠药与绳索,
	 * 都是这两个世界的日常内容。
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"你站在昏黄的前台灯下,墙上的《住宿须知》纸张边缘微微卷起,字迹清晰却透着一种刻意工整的冷漠。",
			"药箱底下翻出半瓶安眠药和一卷绳索,你把它们塞进背包,又摸了摸胸口那把生锈的刀片。",
			"它扑上来咬断了那人的颈动脉,血喷在考察站的铁皮墙上,符号在血里显出非欧的折角。",
			"云雾缭绕的荒山之中,你跋涉已久,终于在暮色中瞥见一处被藤蔓遮掩的洞府。" })
	void ordinaryProseOfTheFourWorldsIsClean(String text) {
		assertThat(SelfHarmScanner.scan(text)).isEmpty();
	}

	/**
	 * ⚠️ prompt 定稿<b>逐字显式许可</b>「在虚构世界内部主动承担牺牲、禁术、污染、散功、燃烧修为等代价」。
	 *
	 * <p>这一条若变红,不是误报,是<b>词表与主防线正面自相矛盾</b> —— 那比漏抓严重得多。
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"他不再犹豫,自爆金丹,整座洞府在白光里塌了下去。",
			"她选择散功,以一身修为换那道封印再撑三日。",
			"燃烧修为的代价是往后再无破境之望,他还是把手按了上去。" })
	void fictionalSacrificeExplicitlyAllowedByThePromptIsClean(String text) {
		assertThat(SelfHarmScanner.scan(text)).isEmpty();
	}

	@Test
	void nullAndBlankAreClean() {
		assertThat(SelfHarmScanner.scan(null)).isEmpty();
		assertThat(SelfHarmScanner.scan("")).isEmpty();
	}

	/** 词表不得空 —— 空表下上面每一条「不命中」用例都会自动变绿,那是假绿灯(同刀 1 M5 的形状)。 */
	@Test
	void tokenListIsNotEmpty() {
		assertThat(SelfHarmScanner.METHOD_TOKENS).hasSize(5);
	}
}
