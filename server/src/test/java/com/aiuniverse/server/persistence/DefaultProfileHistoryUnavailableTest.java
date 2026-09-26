package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aiuniverse.server.web.GameController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-025 刀 2 · 默认 profile(文件存储,无 DB)下的历史接口:501 {@code history_unavailable},只带 code
 * (已决 4 选 (a):不拿内存里那 ≤4 条冒充历史,也不返回空列表 —— 空列表会被读成「这一局什么都没发生」)。
 * 不需要 Docker。打的是 Spring 装配出来的那个 controller(证明默认 profile 装的就是不可用实现)。
 */
@SpringBootTest
class DefaultProfileHistoryUnavailableTest {

	@Autowired
	GameController controller;
	@Autowired
	NarrativeHistoryReader reader;

	private MockMvc mvc;
	private final ObjectMapper mapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void defaultProfileWiresUnavailableReader() {
		assertThat(reader).isInstanceOf(UnavailableHistoryReader.class);
	}

	@Test
	void historyIsNotImplementedWithCodeOnly() throws Exception {
		MvcResult r = mvc.perform(get("/api/game/any-save/history")).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(501);
		JsonNode err = mapper.readTree(r.getResponse().getContentAsString()).path("error");
		assertThat(err.path("code").asString()).isEqualTo("history_unavailable");
		assertThat(err.has("message")).as("文案归前端兜底表(刀 3)").isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "-1", "abc", "1.5", "+3", "", " 7", "99999999999" })
	void invalidAfterTurnIsBadRequest(String raw) throws Exception {
		MvcResult r = mvc.perform(get("/api/game/any-save/history").param("afterTurn", raw)).andReturn();
		assertThat(r.getResponse().getStatus()).as("afterTurn=[%s]", raw).isEqualTo(400);
		JsonNode err = mapper.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
				.path("error");
		assertThat(err.path("code").asString()).isEqualTo("invalid_after_turn");
	}
}
