package com.aiuniverse.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnResult;
import com.aiuniverse.server.eventloop.TurnStateMachine;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.quota.QuotaGate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * <b>已知代价 8 的钉子</b>:前端请求头带 {@code Accept: text/event-stream}({@code sse.ts}),
 * 而回合端点现在既能返 SSE 又能返 JSON 错误体。错误响应若不<b>显式</b>
 * {@code contentType(APPLICATION_JSON)},内容协商有返 <b>406</b> 的风险——
 * 那会让玩家看到的不是我们写的文案,而是一个空壳错误。
 *
 * <p>⚠️ <b>为什么这一条必须走 MockMvc,而不能像同包其它用例那样直接调方法</b>:
 * 直接断言 {@code ResponseEntity} 的 header,断言的是「<b>我自己设了这个头</b>」,
 * 而内容协商发生在<b>消息转换器</b>里 —— 那是两件事,前者证明不了后者。
 * 一个证明不了被问问题的断言,是<b>看起来在守、其实没在看</b>的探针(ADR-018 §4.14)。
 *
 * <p><b>取证边界(如实记)</b>:{@code standaloneSetup} 用的是默认消息转换器,不等于 Boot 的实际装配。
 * <b>此处差异为零的依据是一条现测事实</b>:全仓 {@code @ControllerAdvice} / {@code @ExceptionHandler} /
 * {@code WebMvcConfigurer} / 自定义 {@code HttpMessageConverter} <b>零命中</b>(2026-09-03 复核)。
 * 那条事实<b>是这条边界成立的全部依据</b> —— 哪天有人加了自定义 MVC 配置,这条边界随之失效,
 * 本用例须升级到 {@code @WebMvcTest} / {@code @SpringBootTest};<b>不许退回到弱断言</b>
 * (已知代价 8 逐字要求「由断言钉住,不许留给冒烟发现」,退成弱断言等于把那句话变成假的)。
 */
class GameControllerTurnContentTypeTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private static final class AllowingQuota implements QuotaGate {
		@Override public Decision checkInit(ClientKey key) { return Decision.ALLOW; }
		@Override public Decision checkTurn(ClientKey key) { return Decision.ALLOW; }
		@Override public void record(LlmUsage usage) { }
	}

	private ObjectNode world() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		return world;
	}

	private ArrayNode actions() {
		ArrayNode actions = mapper.createArrayNode();
		actions.addObject().put("id", "A").put("text", "查看告示");
		return actions;
	}

	/** 建 MockMvc:含 save-1(A 合法),准入容量由入参定,提交后不跑(名额不还)。 */
	private MockMvc mockMvc(int capacity) {
		GameSessionManager manager = new GameSessionManager(mapper);
		manager.create("save-1", world(), actions(), Set.of(), Map.of(), Set.of());
		GameController controller = new GameController(manager,
				new TurnStateMachine((s, a, sink) -> new TurnResult(false)), null, new AllowingQuota(),
				new TurnAdmission(capacity, r -> { }));
		return MockMvcBuilders.standaloneSetup(controller).build();
	}

	private static final String BODY = "{\"turn\":0,\"actionId\":\"%s\"}";

	@Test
	void notFoundIsJsonNot406WhenClientAcceptsOnlyEventStream() throws Exception {
		mockMvc(8).perform(post("/api/game/nope/turn")
				.accept(MediaType.TEXT_EVENT_STREAM) // ← 前端真实发的 Accept
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY.formatted("A")))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.error.code").value("session_not_found"))
				// 闸 A:404 只带 code —— 带 message 会压过前端刀 1.5 的兜底文案。
				.andExpect(jsonPath("$.error.message").doesNotExist());
	}

	@Test
	void illegalActionIsJsonNot406WhenClientAcceptsOnlyEventStream() throws Exception {
		mockMvc(8).perform(post("/api/game/save-1/turn")
				.accept(MediaType.TEXT_EVENT_STREAM)
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY.formatted("Z")))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.error.code").value("illegal_action"));
	}

	@Test
	void admissionRejectionIsJsonNot406WhenClientAcceptsOnlyEventStream() throws Exception {
		MockMvc mvc = mockMvc(1);
		mvc.perform(post("/api/game/save-1/turn").accept(MediaType.TEXT_EVENT_STREAM)
				.contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("A")))
				.andExpect(status().isOk()); // 占住唯一名额

		mvc.perform(post("/api/game/save-1/turn").accept(MediaType.TEXT_EVENT_STREAM)
				.contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("A")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.error.code").value("server_at_capacity"))
				.andExpect(jsonPath("$.error.message").value("此刻同时进行的回合太多,请过几秒再点一次"));
	}
}
