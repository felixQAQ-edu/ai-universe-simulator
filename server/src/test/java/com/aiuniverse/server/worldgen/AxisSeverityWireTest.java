package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.moderation.ModerationGateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-018 刀 0 · severity 的<b>下发面</b>:axesJson 每档带 severity、init 与 resume <b>同一构建路径</b>
 * 故结果逐字节相同、融合局的 severity <b>自动等同于来源单体</b>(换皮不换 perilAtHigh → per-combo 零登记)。
 *
 * <p>走 API DTO(InitResponse)而非被校验的 wire schema → {@code schemaVersion} 保 "0.4"。
 */
class AxisSeverityWireTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	private GameInitService service(LlmClient llm, GameSessionManager sessions) {
		ModerationGateway noop = text -> text;
		return new GameInitService(new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper),
				sessions, noop, registry, mapper);
	}

	// ── 下发形态 ────────────────────────────────────────────────────────

	@Test
	void everyBandCarriesSeverityAndNoNarrationHint() {
		InitResponse resp = service((req, sink) -> sink.onToken(cthulhuWorld()), new GameSessionManager(mapper))
				.init("cthulhu");

		JsonNode knowledge = axisNode(resp.attributes(), "knowledge");
		List<String> sev = new ArrayList<>();
		knowledge.path("bands").forEach(b -> sev.add(b.path("severity").asString("")));
		// 禁忌知识(双刃累积):升序区间 [0,30]/[31,60]/[61,100] → neutral / caution / danger。
		assertThat(sev).containsExactly("neutral", "caution", "danger");

		JsonNode hp = axisNode(resp.attributes(), "hp");
		List<String> hpSev = new ArrayList<>();
		hp.path("bands").forEach(b -> hpSev.add(b.path("severity").asString("")));
		// 体力(致命 depletion):最低档 danger、次低 caution、顶档 neutral。
		assertThat(hpSev).containsExactly("danger", "caution", "neutral");

		// 每档四字段齐、仍不下发 narrationHint(仅服务端注入 prompt)。
		resp.attributes().forEach(a -> a.path("bands").forEach(b -> {
			assertThat(b.has("min")).isTrue();
			assertThat(b.has("max")).isTrue();
			assertThat(b.path("label").asString("")).isNotBlank();
			assertThat(b.path("severity").asString("")).isIn("neutral", "caution", "danger");
			assertThat(b.has("narrationHint")).as("narrationHint 不下发前端").isFalse();
		}));
	}

	// ── ⑤ init 与 resume 同一构建路径 ───────────────────────────────────────

	@Test
	void initAndResumeYieldIdenticalAxisMeta() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		InitResponse init = service((req, sink) -> sink.onToken(cthulhuWorld()), sessions).init("cthulhu");
		InitResponse resumed = service((req, sink) -> sink.onToken("{}"), sessions).resume(init.saveId());

		assertThat(resumed).isNotNull();
		// 同一构建点(GameInitService.attributeMeta)→ 整棵轴元数据逐字节相同(含每档 severity)。
		assertThat(resumed.attributes()).isEqualTo(init.attributes());
		assertThat(mapper.writeValueAsString(resumed.attributes()))
				.isEqualTo(mapper.writeValueAsString(init.attributes()));
	}

	@Test
	void fusionInitAndResumeYieldIdenticalAxisMeta() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		InitResponse init = service((req, sink) -> sink.onToken(fusionWorld()), sessions)
				.init(List.of("cultivation", "rules_creepy"));
		InitResponse resumed = service((req, sink) -> sink.onToken("{}"), sessions).resume(init.saveId());

		assertThat(resumed.attributes()).isEqualTo(init.attributes());
	}

	// ── ⑦ 融合局 severity == 来源单体(换皮不换 perilAtHigh,per-combo 零登记)──────────

	@Test
	void shihaiFusionSeverityMatchesOriginSingleWorlds() {
		// 识海遗蜕(host=修仙):气血/灵力/境界来自修仙,道心是规则怪谈 san 的换皮。
		assertSeverityMatchesOrigin(List.of("cultivation", "rules_creepy"));
	}

	@Test
	void renfangFusionSeverityMatchesOriginSingleWorlds() {
		// 缺页的人防工程(host=规则怪谈):补给是末日 hunger 的换皮,首例三致命轴。
		assertSeverityMatchesOrigin(List.of("rules_creepy", "apocalypse"));
	}

	/**
	 * 融合轴集里每根轴的 severity 序列,必须等于它在<b>来源单体</b>(host 优先)同 key 轴上的序列——
	 * 换皮只换 displayName/bands 文案,不换危险方向,故融合世界不需要任何 per-combo severity 登记。
	 */
	private void assertSeverityMatchesOrigin(List<String> combo) {
		List<AttributeAxis> fused = registry.resolveAxes(combo);
		for (AttributeAxis axis : fused) {
			AttributeAxis origin = originOf(combo, axis.key());
			assertThat(severities(axis)).as("%s 的 %s 与来源单体同 severity", combo, axis.key())
					.isEqualTo(severities(origin));
		}
	}

	/** 该 key 的来源单体轴:host 有就用 host 的,否则用 foreign 的(与 mergeAxes 的撞键规则同源)。 */
	private AttributeAxis originOf(List<String> combo, String key) {
		for (String id : combo) {
			for (AttributeAxis a : registry.meta(id).attributes()) {
				if (a.key().equals(key)) {
					return a;
				}
			}
		}
		throw new IllegalStateException("融合轴 " + key + " 在两个来源单体里都找不到:" + combo);
	}

	private static List<AttributeAxis.Severity> severities(AttributeAxis axis) {
		return axis.bandRanges().stream().map(AttributeAxis.BandRange::severity).toList();
	}

	private static JsonNode axisNode(JsonNode attributes, String key) {
		for (JsonNode a : attributes) {
			if (a.path("key").asString("").equals(key)) {
				return a;
			}
		}
		throw new IllegalStateException("轴元数据里没有 " + key);
	}

	// ── 夹具 ───────────────────────────────────────────────────────────

	private String cthulhuWorld() {
		return """
				{"schemaVersion":"0.4","mode":"single","archetypes":["cthulhu"],
				 "world":{"title":"禁阅区","background":"闭馆后的地下书库。","dangerLevel":"high","tone":"阴郁"},
				 "character":{"attributes":{"hp":80,"san":70,"knowledge":10},"traits":["博学"],"inventory":["借书证"]},
				 "rules":[{"id":1,"content":"不要抄写第七页","isTrue":true,"hiddenLogic":"抄写则 san-15","discovered":false},
				          {"id":2,"content":"馆员从不眨眼","isTrue":false,"hiddenLogic":"假线索","discovered":false}],
				 "endings":[{"id":"escape","title":"离馆","description":"你合上书。","condition":"理智≥30 且离开","outcome":"success","reached":false},
				            {"id":"mad","title":"疯狂","description":"你笑了。","condition":"理智归零","outcome":"failure","reached":false}],
				 "availableActions":[{"id":"A","text":"翻开典籍","hint":"知道得越多越危险"},{"id":"B","text":"退出书库","hint":""}],
				 "openingNarrative":"霉味里混着一丝海腥。"}
				""";
	}

	private String fusionWorld() {
		return """
				{"schemaVersion":"0.4","mode":"hybrid","archetypes":["cultivation","rules_creepy"],
				 "world":{"title":"识海遗蜕","background":"你在识海中醒来。","dangerLevel":"extreme","tone":"缥缈"},
				 "character":{"attributes":{"hp":80,"mana":60,"realm":15,"san":90},"traits":["单灵根"],"inventory":["残玉"]},
				 "rules":[{"id":1,"content":"心魔不可纵","hiddenLogic":"纵则道心-15","discovered":false},
				          {"id":2,"content":"见蜕影须叩首","isTrue":false,"hiddenLogic":"心魔伪笔","discovered":false}],
				 "endings":[{"id":"guard_dao","title":"护道功成","description":"你守住了。","condition":"道心≥50 且遗蜕散去","outcome":"success","reached":false},
				            {"id":"dao_broken","title":"道心崩碎","description":"心魔入体。","condition":"道心归零","outcome":"failure","reached":false},
				            {"id":"body_dead","title":"身死道消","description":"气血耗尽。","condition":"气血归零","outcome":"failure","reached":false}],
				 "availableActions":[{"id":"A","text":"闭目内视","hint":"稳住道心,但遗蜕会逼近"},{"id":"B","text":"叩问残碑","hint":""}],
				 "openingNarrative":"识海无风,碑影自摇。"}
				""";
	}
}
