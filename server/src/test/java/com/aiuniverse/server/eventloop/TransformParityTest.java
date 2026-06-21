package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Transform parity —— 把 golden 护城河延伸到「切分器 + 回灌」(核心一招,零 API 成本)。
 *
 * <p>做法:取每条 golden 录制的 {@code parsed}(单 {@code json_object} 形态),离线重写成 ADR-006 新线上格式
 * {@code N + <<<DELTA>>> + (parsed 去掉 narrative 的尾巴)},逐字符喂过 {@link SentinelSplitter} +
 * {@link TurnReinfuser},断言:
 * <ol>
 *   <li>切出的叙事 == 原 narrative;命中哨兵;</li>
 *   <li>回灌后的 ObjectNode 逐字段 == 原 parsed;</li>
 *   <li>把回灌节点喂 {@link Engine} 连推满 10 回合,端状态 == 同一 golden fixture(== {@link com.aiuniverse.server.engine.EngineGoldenTest} 的期望)。</li>
 * </ol>
 * 即:新线上格式经切分+回灌后,与旧单 JSON 形态在引擎端<b>逐字段等价</b>——不依赖真 key。
 */
class TransformParityTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private JsonNode fixture() {
		try (InputStream in = getClass().getResourceAsStream("/golden/event-loop-golden.json")) {
			assertThat(in).as("golden 夹具应存在").isNotNull();
			return mapper.readTree(in);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** 把原 parsed 重写成线上格式,逐字符过切分器,回灌后返回 ObjectNode(并就地断言切分不变量)。 */
	private ObjectNode roundTrip(JsonNode parsed) {
		String narrative = parsed.get("narrative").asString();
		ObjectNode tailObj = ((ObjectNode) parsed).deepCopy();
		tailObj.remove("narrative");
		String wire = narrative + SentinelSplitter.SENTINEL + mapper.writeValueAsString(tailObj);

		StringBuilder narrOut = new StringBuilder();
		SentinelSplitter splitter = new SentinelSplitter(narrOut::append);
		for (int i = 0; i < wire.length(); i++) {
			splitter.accept(String.valueOf(wire.charAt(i))); // 逐字符:最严苛的跨 chunk 压力
		}
		splitter.end();

		assertThat(splitter.sentinelSeen()).as("应命中哨兵").isTrue();
		assertThat(narrOut.toString()).as("切出的叙事 == 原 narrative").isEqualTo(narrative);
		return TurnReinfuser.reinfuse(splitter.tail(), narrOut.toString(), mapper);
	}

	@Test
	void reinfusedNodeEqualsOriginalParsedFieldByField() {
		JsonNode fx = fixture();
		for (String path : List.of("B1", "B2", "B3")) {
			for (JsonNode t : fx.path("paths").path(path).path("turns")) {
				JsonNode parsed = t.get("parsed");
				ObjectNode reinfused = roundTrip(parsed);
				assertThat(reinfused).as("%s 回灌后逐字段 == 原 parsed", path).isEqualTo(parsed);
			}
		}
	}

	@Test
	void engineEndStateViaSplitReinfuseMatchesGolden() {
		JsonNode fx = fixture();
		ObjectNode world = (ObjectNode) fx.get("world");
		for (String path : List.of("B1", "B2", "B3")) {
			JsonNode p = fx.path("paths").path(path);
			Engine eng = new Engine(world.deepCopy(), mapper);
			for (JsonNode t : p.path("turns")) {
				ObjectNode reinfused = roundTrip(t.get("parsed")); // 经切分+回灌而非直喂原 parsed
				eng.apply(reinfused, t.get("actionId").asString());
			}
			JsonNode exp = p.path("expected");
			assertThat(eng.turn()).as("%s turn", path).isEqualTo(exp.get("turn").asInt());
			assertThat(eng.status()).as("%s status", path).isEqualTo(exp.get("status").asString());
			assertThat(eng.hp()).as("%s hp", path).isEqualTo(exp.get("hp").asDouble());
			assertThat(eng.san()).as("%s san", path).isEqualTo(exp.get("san").asDouble());
			assertThat(eng.timeline()).as("%s timeline", path).isEqualTo(exp.get("timeline").asString());
			assertThat(eng.triggered()).as("%s triggered", path).isEqualTo(intList(exp.get("triggered")));
			assertThat(eng.logSummary()).as("%s logSummary", path).isEqualTo(exp.get("logSummary").asString());
			assertThat(eng.issues()).as("%s issues count", path).hasSize(exp.get("issuesCount").asInt());
			JsonNode actualLog = mapper.valueToTree(eng.log());
			assertThat(actualLog).as("%s log tail", path).isEqualTo(exp.get("log"));
		}
	}

	private List<Integer> intList(JsonNode arr) {
		List<Integer> out = new ArrayList<>();
		arr.forEach(n -> out.add(n.asInt()));
		return out;
	}
}
