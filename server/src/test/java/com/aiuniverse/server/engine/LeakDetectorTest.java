package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 泄露检测单测(移植 {@code detect_leak},§1c 遥测语义)——
 * 抓字段名 + hiddenLogic 逐字 ≥8 字符子串;抓不到改写式;命中只返回证据不抛错。
 */
class LeakDetectorTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private ObjectNode world() {
		ObjectNode w = mapper.createObjectNode();
		ObjectNode r = w.putArray("rules").addObject();
		r.put("id", 1);
		r.put("hiddenLogic", "回应会让窗外之物确认店内有人触发san20");
		return w;
	}

	@Test
	void cleanTextHasNoHits() {
		assertThat(LeakDetector.detect("窗外传来三声敲击,你压低身子没有回应。", world())).isEmpty();
	}

	@Test
	void engineFieldNameIsCaught() {
		assertThat(LeakDetector.detect("根据 hiddenLogic 你会扣血。", world()))
				.anyMatch(s -> s.contains("hiddenLogic"));
	}

	@Test
	void verbatimHiddenLogicIsCaught() {
		String leaked = "你脑中闪过一念:回应会让窗外之物确认店内有人触发san20。";
		assertThat(LeakDetector.detect(leaked, world())).anyMatch(s -> s.contains("rule#1"));
	}

	@Test
	void paraphrasedLeakIsNotCaught() {
		// §1c 局限:改写式泄露抓不到(这正是它只是遥测、非实时拦截的原因)。
		String paraphrased = "你隐约觉得,出声似乎会引来外面的东西。";
		assertThat(LeakDetector.detect(paraphrased, world())).isEmpty();
	}

	@Test
	void nullTextAndNullWorldAreSafe() {
		assertThat(LeakDetector.detect(null, null)).isEmpty();
	}
}
