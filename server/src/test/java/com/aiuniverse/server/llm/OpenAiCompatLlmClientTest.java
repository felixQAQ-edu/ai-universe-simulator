package com.aiuniverse.server.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容客户端的【不打网络】单测:请求体构造、错误映射、缺 key 降级。
 * 真实流式由手动集成冒烟覆盖(挂真 key 时 curl),保持本套测试确定性、零成本。
 */
class OpenAiCompatLlmClientTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private LlmProperties.Provider deepseek() {
		return new LlmProperties.Provider("DeepSeek V4-Flash", "https://api.deepseek.com",
				"deepseek-v4-flash", "DEEPSEEK_API_KEY", false, 1_000_000,
				new LlmProperties.Price(1.0, 0.02, 2.0));
	}

	private OpenAiCompatLlmClient client(LlmProperties.Provider p, String fakeKey) {
		// 注入假 env,绝不读真实环境变量(确定性)。
		return new OpenAiCompatLlmClient(p, new ThinkingAdapter(), mapper, env -> fakeKey);
	}

	@Test
	void buildBodyHasModelStreamMessagesAndThinkingExtra() throws Exception {
		OpenAiCompatLlmClient c = client(deepseek(), "sk-fake");
		JsonNode body = mapper.readTree(c.buildBody(new ChatRequest("雨夜便利店")));

		assertThat(body.get("model").asText()).isEqualTo("deepseek-v4-flash");
		assertThat(body.get("stream").asBoolean()).isTrue();
		assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
		assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("雨夜便利店");
		// 思考开关经单点 adapter 注入到顶层 extra_body(非思考 → disabled)。
		assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
	}

	@Test
	void mainCallOmitsJsonResponseFormatRepairCallOpensIt() throws Exception {
		OpenAiCompatLlmClient c = client(deepseek(), "sk-fake");
		// 主调用(jsonObject=false):叙事+哨兵+尾巴非纯 JSON,不能开 json_object(ADR-006 §4.3)。
		JsonNode main = mapper.readTree(c.buildBody(new ChatRequest("叙事先行")));
		assertThat(main.has("response_format")).isFalse();
		// 修复发(jsonObject=true):开回 json_object(规格 §6.4)。
		JsonNode repair = mapper.readTree(c.buildBody(new ChatRequest("只回尾巴", true)));
		assertThat(repair.path("response_format").path("type").asText()).isEqualTo("json_object");
	}

	@Test
	void missingApiKeyDegradesToLlmExceptionNotNpe() {
		OpenAiCompatLlmClient c = client(deepseek(), null); // env 返回 null = 未设置
		assertThatThrownBy(() -> c.streamChat(new ChatRequest("hi"), t -> {}))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("DEEPSEEK_API_KEY");
	}

	@Test
	void httpErrorMapsStatusWithoutLeakingKey() {
		LlmException ex = OpenAiCompatLlmClient.httpError(401, "{\"error\":\"invalid_api_key\"}");
		assertThat(ex).isInstanceOf(LlmException.class);
		assertThat(ex.getMessage()).contains("401");
		assertThat(ex.getMessage()).doesNotContain("sk-"); // 永不泄露 key
	}
}
