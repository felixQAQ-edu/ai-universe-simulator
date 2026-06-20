package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 校验器 accept-parity —— 把手写 {@link GameSchemas} 钉到 Python 参照 {@code schema.py}。
 *
 * <p>夹具 {@code /golden/validator-parity.json} 由 {@code bakeoff/gen_validator_parity.py} 离线
 * 生成:bake-off 录制的 38 条真实产出(30 event-loop + 8 world-gen raw)经 Python
 * {@code validate_turn}/{@code validate_world} 判定后固化(Java 运行时不调 Python)。
 *
 * <p>意图:钉「<b>Java 不会拒掉 Python 接受的真实输入</b>」(accept-parity,这些 raw 多为有效)。
 * reject-parity 已由 {@link GameSchemasTest} 的逐项破坏用例覆盖,这里不再造大批负例。
 * event-loop raw 是单 json_object、{@code narrative} 内联 → 直接 {@link LooseJson} 解析即可校验,
 * 无需回灌(回灌只在生产流式哨兵+尾巴路径)。
 */
class ValidatorParityTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private JsonNode cases() {
		try (InputStream in = getClass().getResourceAsStream("/golden/validator-parity.json")) {
			assertThat(in).as("validator-parity 夹具应存在").isNotNull();
			return mapper.readTree(in).get("cases");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@TestFactory
	List<DynamicTest> javaValidatorVerdictMatchesPythonReference() {
		JsonNode cases = cases();
		assertThat(cases).as("夹具非空").isNotEmpty();
		return cases.valueStream().map(c -> DynamicTest.dynamicTest(c.get("id").asString(), () -> {
			JsonNode parsed = LooseJson.parse(c.get("raw").asString(), mapper);
			List<String> errors = "world".equals(c.get("kind").asString())
					? GameSchemas.validateWorld(parsed)
					: GameSchemas.validateTurn(parsed);
			boolean javaValid = errors.isEmpty();
			boolean pythonValid = c.get("valid").asBoolean();
			// accept-parity:Python 接受的,Java 必须也接受(不误杀真实输入)。
			if (pythonValid) {
				assertThat(javaValid)
						.as("Python 接受但 Java 拒绝 [%s];Java 报错:%s", c.get("id").asString(), errors)
						.isTrue();
			} else {
				// Python 拒绝的(本批夹具暂无),Java 也应拒绝。
				assertThat(javaValid)
						.as("Python 拒绝但 Java 接受 [%s]", c.get("id").asString())
						.isFalse();
			}
		})).toList();
	}
}
