package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.DeferredResult;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnStateMachine;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.llm.TokenStream;
import com.aiuniverse.server.quota.QuotaGate;
import com.aiuniverse.server.worldgen.GameInitService;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;
import com.aiuniverse.server.worldgen.WorldGenProperties;
import com.aiuniverse.server.worldgen.WorldGenService;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-030 实施刀 2 · {@code POST /api/game/init} 改 {@link DeferredResult} + 开局准入(决策 1–3、已决 3)。
 *
 * <p>MockMvc 实拍(standaloneSetup + {@code asyncDispatch}),不直接调方法 —— 内容协商发生在消息转换器里
 * (ADR-022 闸 C)。计数全是<b>同步</b>读的:被拒请求的工作从未被提交,计数器在调用线程上读(ADR-022 竞态绿的教训)。
 */
class GameControllerInitAdmissionTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 记 checkInit 次数;allowInit 控制放行。 */
	private static final class CountingQuota implements QuotaGate {
		int checkInits;
		boolean allowInit = true;

		@Override
		public Decision checkInit(ClientKey key) {
			checkInits++;
			return allowInit ? Decision.ALLOW : Decision.deny("今日新世界名额已满,明天再来");
		}

		@Override
		public Decision checkTurn(ClientKey key) {
			return Decision.ALLOW;
		}

		@Override
		public void record(LlmUsage usage) {
		}
	}

	/** 记 world-gen 上游调用次数;每次都失败(→ 502)。 */
	private static final class CountingFailingLlm implements LlmClient {
		int calls;

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			calls++;
			throw new LlmException("上游不可用(测试)");
		}
	}

	private final CountingQuota quota = new CountingQuota();
	private final CountingFailingLlm llm = new CountingFailingLlm();

	private GameController controller(int initCapacity, long segmentDeadlineMs) {
		ArchetypeRegistry registry = new ArchetypeRegistry();
		GameSessionManager sessions = new GameSessionManager(mapper);
		WorldGenService worldGen = new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper, quota);
		GameInitService initService = new GameInitService(worldGen, sessions, registry, mapper);
		return new GameController(sessions, new TurnStateMachine((s, a, sink) -> null), initService, quota,
				new TurnAdmission(1, Runnable::run), new InitAdmission(initCapacity, Runnable::run),
				new WorldGenProperties(segmentDeadlineMs));
	}

	private MockMvc mvc(int initCapacity) {
		return MockMvcBuilders.standaloneSetup(controller(initCapacity, WorldGenProperties.DEFAULT_SEGMENT_DEADLINE_MS))
				.build();
	}

	private MvcResult dispatchInit(MockMvc mvc, String body) throws Exception {
		MvcResult started = mvc.perform(post("/api/game/init").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(request().asyncStarted()).andReturn();
		return mvc.perform(asyncDispatch(started)).andReturn();
	}

	@Test
	void rejectedInitReturns503JsonWithFinalCopyAndNoRetryAfter() throws Exception {
		MockMvc mvc = mvc(0); // 容量 0 = 恒拒
		MvcResult started = mvc.perform(post("/api/game/init").contentType(MediaType.APPLICATION_JSON)
				.content("{\"archetype\":\"rules_creepy\"}")).andExpect(request().asyncStarted()).andReturn();
		MvcResult done = mvc.perform(asyncDispatch(started))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.error.code").value("server_at_capacity"))
				.andExpect(header().doesNotExist("Retry-After"))
				.andReturn();
		// 文案逐字(全角逗号与句号),按 UTF-8 解码后比对整句。
		String json = done.getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(mapper.readTree(json).path("error").path("message").asString())
				.isEqualTo("此刻同时开局的人太多，请过几秒再试。");
	}

	@Test
	void rejectedInitNeverChecksQuotaNorCallsWorldGen() {
		GameController controller = controller(0, WorldGenProperties.DEFAULT_SEGMENT_DEADLINE_MS);
		DeferredResult<?> result = controller.init(new GameController.InitRequest("rules_creepy", null),
				new MockHttpServletRequest());
		assertThat(result.hasResult()).as("被拒请求在容器线程上立即得到结果").isTrue();
		// 同步计数:准入在额度之前 —— 零次 checkInit(不扣额度)、零次 world-gen。
		assertThat(quota.checkInits).isZero();
		assertThat(llm.calls).isZero();
	}

	@Test
	void errorMappingsUnchangedAndAllJson() throws Exception {
		MockMvc mvc = mvc(1);
		// world-gen 失败 → 502
		MvcResult r502 = dispatchInit(mvc, "{\"archetype\":\"rules_creepy\"}");
		assertThat(r502.getResponse().getStatus()).isEqualTo(502);
		assertThat(r502.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(mapper.readTree(r502.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.path("error").path("code").asString()).isEqualTo("world_gen_failed");
		// 非法 archetype → 400(早于 world-gen)
		MvcResult r400 = dispatchInit(mvc, "{\"archetype\":\"nope\"}");
		assertThat(r400.getResponse().getStatus()).isEqualTo(400);
		assertThat(r400.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(mapper.readTree(r400.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.path("error").path("code").asString()).isEqualTo("invalid_archetype");
		// 额度拒绝 → 429
		quota.allowInit = false;
		MvcResult r429 = dispatchInit(mvc, "{\"archetype\":\"rules_creepy\"}");
		assertThat(r429.getResponse().getStatus()).isEqualTo(429);
		assertThat(r429.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(mapper.readTree(r429.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.path("error").path("code").asString()).isEqualTo("quota_exceeded");
	}

	@Test
	void deferredResultTimeoutIsTwiceSegmentDeadlinePlusThirtySeconds() throws Exception {
		// 读的是交给容器的异步超时(不是我们自己的字段):防回落到 Servlet 默认 30 s。
		assertThat(asyncTimeoutMs(WorldGenProperties.DEFAULT_SEGMENT_DEADLINE_MS)).isEqualTo(390_000L);
		// 非默认段时限:超时跟着变(env 压低段时限冒烟时不会出现「段没掐、响应先超时」)。
		assertThat(asyncTimeoutMs(5_000L)).isEqualTo(40_000L);
	}

	private long asyncTimeoutMs(long segmentDeadlineMs) throws Exception {
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(1, segmentDeadlineMs)).build();
		MvcResult started = mvc.perform(post("/api/game/init").contentType(MediaType.APPLICATION_JSON)
				.content("{\"archetype\":\"rules_creepy\"}")).andExpect(request().asyncStarted()).andReturn();
		return started.getRequest().getAsyncContext().getTimeout();
	}
}
