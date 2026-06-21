package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ① 切分器不变量(规格 §4.3,最高风险先 TDD)。golden 盖不到流式独有的跨 chunk 行为,全靠手搓 fixture。
 */
class SentinelSplitterTest {

	/** 把 token 序列喂进切分器,返回 [拼接后的叙事]。 */
	private record Result(String narrative, String tail, boolean sentinelSeen) {
	}

	private Result run(List<String> tokens) {
		StringBuilder narrative = new StringBuilder();
		SentinelSplitter s = new SentinelSplitter(narrative::append);
		for (String t : tokens) {
			s.accept(t);
		}
		s.end();
		return new Result(narrative.toString(), s.tail(), s.sentinelSeen());
	}

	private List<String> chars(String s) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < s.length(); i++) {
			out.add(String.valueOf(s.charAt(i)));
		}
		return out;
	}

	@Test
	void sentinelWithinSingleChunk() {
		Result r = run(List.of("叙事在前<<<DELTA>>>{\"a\":1}"));
		assertThat(r.narrative()).isEqualTo("叙事在前");
		assertThat(r.tail()).isEqualTo("{\"a\":1}");
		assertThat(r.sentinelSeen()).isTrue();
	}

	@Test
	void sentinelSplitAcrossTwoChunks() {
		Result r = run(List.of("夜色渐深<<<DE", "LTA>>>{\"b\":2}"));
		assertThat(r.narrative()).isEqualTo("夜色渐深");
		assertThat(r.tail()).isEqualTo("{\"b\":2}");
		assertThat(r.sentinelSeen()).isTrue();
	}

	@Test
	void sentinelSplitCharByCharAcrossManyChunks() {
		// 整串逐字符喂入(哨兵被切成 11 段),仍须精确切分、不漏字不吞字。
		Result r = run(chars("收银台后的钟停了<<<DELTA>>>{\"hp\":80}"));
		assertThat(r.narrative()).isEqualTo("收银台后的钟停了");
		assertThat(r.tail()).isEqualTo("{\"hp\":80}");
		assertThat(r.sentinelSeen()).isTrue();
	}

	@Test
	void noSentinelLeavesTailEmptyAndAllNarrative() {
		// 流结束仍无哨兵 → 全部当叙事,tail 空(交下游降级,§6.6)。
		Result r = run(chars("一直在叙事但模型没吐哨兵"));
		assertThat(r.narrative()).isEqualTo("一直在叙事但模型没吐哨兵");
		assertThat(r.tail()).isEmpty();
		assertThat(r.sentinelSeen()).isFalse();
	}

	@Test
	void falseSentinelPrefixNeitherTriggersNorSwallows() {
		// "<<<DEL" 后接别的(非真哨兵)→ 不误触发、不吞字,最终整段当叙事。
		Result r = run(chars("门牌写着<<<DELETED区域>>>请勿入内"));
		assertThat(r.narrative()).isEqualTo("门牌写着<<<DELETED区域>>>请勿入内");
		assertThat(r.tail()).isEmpty();
		assertThat(r.sentinelSeen()).isFalse();
	}

	@Test
	void onlyFirstSentinelIsCut() {
		// 尾巴里再现的同串当普通内容,不二次切分。
		Result r = run(List.of("前言<<<DELTA>>>{\"note\":\"<<<DELTA>>> 字面量\"}"));
		assertThat(r.narrative()).isEqualTo("前言");
		assertThat(r.tail()).isEqualTo("{\"note\":\"<<<DELTA>>> 字面量\"}");
		assertThat(r.sentinelSeen()).isTrue();
	}

	@Test
	void emptyAndNullTokensIgnored() {
		Result r = run(new ArrayList<>(List.of("", "叙事", "", "<<<DELTA>>>", "{}")));
		assertThat(r.narrative()).isEqualTo("叙事");
		assertThat(r.tail()).isEqualTo("{}");
		assertThat(r.sentinelSeen()).isTrue();
	}

	@Test
	void sentinelAtVeryStartGivesEmptyNarrative() {
		Result r = run(chars("<<<DELTA>>>{\"only\":\"tail\"}"));
		assertThat(r.narrative()).isEmpty();
		assertThat(r.tail()).isEqualTo("{\"only\":\"tail\"}");
		assertThat(r.sentinelSeen()).isTrue();
	}
}
