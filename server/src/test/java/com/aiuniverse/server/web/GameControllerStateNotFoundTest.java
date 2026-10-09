package com.aiuniverse.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 续局查询 {@code GET /api/game/{saveId}/state} 的 404 形态(2026-10-09 A 批小债):
 * 按 ADR-022 立字 11 <b>只带 code</b>(状态码本身就说得清,文案归前端;前端在此静默清 saveId,
 * 从不展示服务端 message),且显式 JSON(同回合端点的已知代价 8,不留给内容协商)。
 * 打的是 Spring 装配出来的 controller(需要真实的 {@code GameInitService.resume})。
 */
@SpringBootTest
class GameControllerStateNotFoundTest {

	@Autowired
	GameController controller;

	@Test
	void unknownSaveIsNotFoundWithCodeOnly() throws Exception {
		MockMvcBuilders.standaloneSetup(controller).build()
				.perform(get("/api/game/no-such-save/state").accept(MediaType.ALL))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.error.code").value("session_not_found"))
				.andExpect(jsonPath("$.error.message").doesNotExist());
	}
}
