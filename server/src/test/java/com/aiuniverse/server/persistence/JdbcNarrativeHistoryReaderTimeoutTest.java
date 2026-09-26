package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aiuniverse.server.web.GameController;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-025 刀 2 · 读路径同样受 pg 超时约束:DB 挂起({@code docker pause})时历史接口在上界内返回
 * 503 {@code history_read_failed},不抛原始异常、不吐异常文本。用的是 {@code application-pg.yml} 的真实配置
 * (同 {@link JdbcSessionStoreTimeoutTest} 的口径);独立容器,暂停它不影响别的测试类。
 */
@SpringBootTest
@ActiveProfiles("pg")
@RequiresDocker
@Testcontainers
class JdbcNarrativeHistoryReaderTimeoutTest {

	/** 上界:取连接 1 s + 事务/语句 2 s ≈ 3 s(application-pg.yml),再留 1 s 调度余量。 */
	static final long BOUND_MS = 4000;

	@Container
	@ServiceConnection
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	GameController controller;
	@Autowired
	JdbcTemplate jdbc;

	@Test
	void historyReturnsWithinBoundWhenDatabaseHangs() throws Exception {
		jdbc.update("INSERT INTO game_session (save_id, snapshot, turn, status, source) "
				+ "VALUES ('save-t', '{}'::json, 0, 'ongoing', 'native')"); // 也顺带暖池
		var mvc = MockMvcBuilders.standaloneSetup(controller).build();

		pg.getDockerClient().pauseContainerCmd(pg.getContainerId()).exec();
		try {
			long start = System.nanoTime();
			MvcResult r = mvc.perform(get("/api/game/save-t/history")).andReturn();
			long elapsedMs = (System.nanoTime() - start) / 1_000_000;
			System.err.println("[db-tests] DB 挂起时 history 实测阻塞 = " + elapsedMs + " ms(上界 " + BOUND_MS + " ms)");
			assertThat(elapsedMs).as("DB 挂起时读历史的阻塞上界").isLessThan(BOUND_MS);
			assertThat(r.getResponse().getStatus()).isEqualTo(503);
			var err = new ObjectMapper().readTree(r.getResponse().getContentAsString()).path("error");
			assertThat(err.path("code").asString()).isEqualTo("history_read_failed");
			assertThat(err.has("message")).as("原始异常不出网").isFalse();
		} finally {
			pg.getDockerClient().unpauseContainerCmd(pg.getContainerId()).exec();
		}
	}
}
