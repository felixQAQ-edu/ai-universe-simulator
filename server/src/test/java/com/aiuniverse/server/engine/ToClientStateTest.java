package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 消毒不变量(规格 §1 / §三.9)—— 出网投影 {@link Engine#toClientState()} <b>永不</b>含
 * {@code isTrue}/{@code hiddenLogic}/{@code isCorrect}/{@code groundTruth}。
 * 覆盖三态:ongoing / ended / 已 discovered 规则。
 */
class ToClientStateTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private ObjectNode world() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.2");
		w.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		var rules = w.putArray("rules");
		ObjectNode r = rules.addObject();
		r.put("id", 1);
		r.put("content", "0 点后不要回应敲窗声");
		r.put("isTrue", true);
		r.put("hiddenLogic", "回应触发 san-20");
		r.put("isCorrect", true);   // 额外 LEAK_TOKEN,确保也被剥
		r.put("groundTruth", "x");  // 同上
		r.put("discovered", false);
		ObjectNode end = w.putArray("endings").addObject();
		end.put("id", "lost_mind");
		end.put("title", "精神崩溃");
		end.put("condition", "san值降至0");
		end.put("reached", false);
		return w;
	}

	/** 收集任意嵌套位置出现的字段名。 */
	private List<String> allFieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		collect(node, names);
		return names;
	}

	private void collect(JsonNode node, List<String> out) {
		if (node.isObject()) {
			node.properties().forEach(e -> {
				out.add(e.getKey());
				collect(e.getValue(), out);
			});
		} else if (node.isArray()) {
			node.forEach(child -> collect(child, out));
		}
	}

	@Test
	void ongoingProjectionStripsAllHiddenFields() {
		Engine eng = new Engine(world(), mapper);
		assertThat(allFieldNames(eng.toClientState())).doesNotContainAnyElementsOf(LeakDetector.LEAK_TOKENS);
	}

	@Test
	void projectionStillStrippedAfterRuleDiscovered() {
		Engine eng = new Engine(world(), mapper);
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "你发现了那条规则的真相。");
		t.putObject("stateUpdate").put("hp", 100).put("san", 90);
		t.putArray("discoveredRuleIds").add(1);
		var a = t.putArray("availableActions");
		a.addObject().put("id", "A").put("text", "继续");
		a.addObject().put("id", "B").put("text", "撤退");
		eng.apply(t, "A");

		JsonNode client = eng.toClientState();
		assertThat(allFieldNames(client)).doesNotContainAnyElementsOf(LeakDetector.LEAK_TOKENS);
		// discovered 规则的 content 仍下发(玩家可见),但隐藏字段已剥。
		JsonNode rule = client.path("rules").get(0);
		assertThat(rule.path("discovered").asBoolean()).isTrue();
		assertThat(rule.has("content")).isTrue();
		assertThat(rule.has("hiddenLogic")).isFalse();
	}

	@Test
	void endedProjectionStripsHiddenFields() {
		Engine eng = new Engine(world(), mapper);
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "理智耗尽。");
		t.putObject("stateUpdate").put("hp", 100).put("san", 0);
		var a = t.putArray("availableActions");
		a.addObject().put("id", "A").put("text", "继续");
		a.addObject().put("id", "B").put("text", "撤退");
		eng.apply(t, "A");
		assertThat(eng.status()).isEqualTo("ended");
		assertThat(allFieldNames(eng.toClientState())).doesNotContainAnyElementsOf(LeakDetector.LEAK_TOKENS);
	}

	@Test
	void contextJsonForModelStillCarriesHiddenLogic() {
		// 视图 2(喂模型)必须保留 hiddenLogic,与消毒投影刻意不同。
		Engine eng = new Engine(world(), mapper);
		assertThat(eng.contextJson()).contains("hiddenLogic");
	}
}
