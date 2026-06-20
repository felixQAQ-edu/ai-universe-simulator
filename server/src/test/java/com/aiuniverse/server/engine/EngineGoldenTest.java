package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Golden / 特征化测试 —— parity 锁死。bake-off A/B 组 B1/B2/B3 各 10 回合的【录制】模型产出
 * 喂给 Java {@link Engine},断言 end-state 逐字段 == Python {@code scenarios.Engine}。
 *
 * <p>夹具 {@code /golden/event-loop-golden.json} 由 {@code bakeoff/replay_golden.py} 从
 * {@code bakeoff/out/calls.jsonl} 重放 Python 引擎生成(勿手改)。三路径均连推满 10 回合、
 * {@code ongoing}、0 issues、无结局命中——锁住「数值结算 / 触发·发现规则 / log 压缩」核心序列。
 */
class EngineGoldenTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private JsonNode fixture() {
		try (InputStream in = getClass().getResourceAsStream("/golden/event-loop-golden.json")) {
			assertThat(in).as("golden 夹具应存在").isNotNull();
			return mapper.readTree(in);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	void javaEngineMatchesPythonEndStateForAllThreePaths() {
		JsonNode fx = fixture();
		ObjectNode world = (ObjectNode) fx.get("world");
		for (String path : List.of("B1", "B2", "B3")) {
			JsonNode p = fx.path("paths").path(path);
			Engine eng = new Engine(world.deepCopy(), mapper); // 路径间隔离深拷贝
			for (JsonNode t : p.path("turns")) {
				eng.apply(t.get("parsed"), t.get("actionId").asString());
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
			assertThat(discoveredIds(eng)).as("%s discovered", path).isEqualTo(intList(exp.get("discovered")));
			assertThat(endingsReached(eng)).as("%s endingsReached", path)
					.isEqualTo(stringList(exp.get("endingsReached")));
			// log 尾巴逐字段(turn / narrative / playerAction)
			JsonNode actualLog = mapper.valueToTree(eng.log());
			assertThat(actualLog).as("%s log tail", path).isEqualTo(exp.get("log"));
		}
	}

	private List<Integer> intList(JsonNode arr) {
		List<Integer> out = new ArrayList<>();
		arr.forEach(n -> out.add(n.asInt()));
		return out;
	}

	private List<String> stringList(JsonNode arr) {
		List<String> out = new ArrayList<>();
		arr.forEach(n -> out.add(n.asString()));
		return out;
	}

	private List<Integer> discoveredIds(Engine eng) {
		List<Integer> out = new ArrayList<>();
		for (JsonNode r : eng.world().path("rules")) {
			if (r.path("discovered").asBoolean(false)) {
				out.add(r.path("id").asInt());
			}
		}
		return out;
	}

	private List<String> endingsReached(Engine eng) {
		List<String> out = new ArrayList<>();
		for (JsonNode e : eng.world().path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				out.add(e.path("id").asString());
			}
		}
		return out;
	}
}
