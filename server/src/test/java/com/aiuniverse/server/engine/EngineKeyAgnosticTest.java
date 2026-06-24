package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 引擎对数值 key 语义无知确认(ADR-008 决策 1)——{@code Engine.apply} 通吃末日 {@code {hp,hunger}}
 * 如通吃规则怪谈 {@code {hp,san}}:同一通用结算序列(绝对值/clamp/跳变/触底兜底),只是 attributes 换 key。
 *
 * <p>这是「核心从 key-fixed 一次性泛化为 key-agnostic」(事实订正,见 ADR-008)的对照证据;
 * golden parity 守 {@code {hp,san}} 零回归,本测补「换成任意 key 行为一致」。
 */
class EngineKeyAgnosticTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 末日最小世界:attributes={hp,hunger},结局含 hunger 触底条目。 */
	private ObjectNode apocalypseWorld(int hp, int hunger) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.2");
		w.putObject("character").putObject("attributes").put("hp", hp).put("hunger", hunger);
		w.putArray("rules");
		var endings = w.putArray("endings");
		endings.addObject().put("id", "rescued").put("title", "获救")
				.put("condition", "撑到救援抵达").put("reached", false);
		endings.addObject().put("id", "starved").put("title", "饿毙")
				.put("condition", "hunger 归零、饥饿而死").put("reached", false);
		endings.addObject().put("id", "killed").put("title", "丧命")
				.put("condition", "hp 归零").put("reached", false);
		return w;
	}

	private ObjectNode turn(Map<String, Integer> stateUpdate) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "废墟里又熬过一天。");
		ObjectNode upd = t.putObject("stateUpdate");
		stateUpdate.forEach(upd::put);
		var a = t.putArray("availableActions");
		a.addObject().put("id", "A").put("text", "搜寻补给");
		a.addObject().put("id", "B").put("text", "原地固守");
		return t;
	}

	@Test
	void hungerAxisSettlesAndProjectsLikeAnyAxis() {
		Engine eng = new Engine(apocalypseWorld(100, 100), mapper);
		eng.apply(turn(Map.of("hp", 100, "hunger", 92)), "A"); // AI 落饥饿自然衰减 -8

		// 绝对值落账 + 通用结算:hunger 当成普通数值轴落账,引擎不认识它「会衰减」。
		assertThat(eng.attribute("hunger")).isEqualTo(92.0);
		assertThat(eng.attribute("hp")).isEqualTo(100.0);
		assertThat(eng.status()).isEqualTo("ongoing");
		// 消毒投影里 attributes 保 {hp,hunger}(非 {hp,san}),证 snapshot 对 key 无知。
		JsonNode attrs = eng.toClientState().path("character").path("attributes");
		assertThat(attrs.has("hunger")).isTrue();
		assertThat(attrs.has("san")).isFalse();
		assertThat(attrs.get("hunger").asInt()).isEqualTo(92);
	}

	@Test
	void hungerJumpFlaggedLikeHpSan() {
		Engine eng = new Engine(apocalypseWorld(100, 100), mapper);
		eng.apply(turn(Map.of("hp", 100, "hunger", 50)), "A"); // 跳变 50 > 40
		assertThat(eng.issues()).anyMatch(s -> s.contains("hunger 跳变过大"));
		assertThat(eng.status()).isEqualTo("ongoing");
	}

	@Test
	void hungerBottomOutForcesEndingLikeSan() {
		Engine eng = new Engine(apocalypseWorld(80, 20), mapper);
		eng.apply(turn(Map.of("hp", 80, "hunger", 0)), "A"); // 饥饿触底,AI 未给结局
		assertThat(eng.status()).isEqualTo("ended");
		// §5 兜底:找 condition 提及触底轴 key(hunger)的坏结局 → starved。
		assertThat(reachedId(eng)).isEqualTo("starved");
	}

	@Test
	void clampAppliesToHungerToo() {
		Engine eng = new Engine(apocalypseWorld(100, 100), mapper);
		eng.apply(turn(Map.of("hp", 100, "hunger", -30)), "A"); // schema 本应先拦;引擎仍 clamp
		assertThat(eng.attribute("hunger")).isEqualTo(0.0);
		assertThat(eng.status()).isEqualTo("ended"); // hunger<=0 触底
	}

	private String reachedId(Engine eng) {
		for (JsonNode e : eng.world().path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				return e.path("id").asString();
			}
		}
		return null;
	}
}
