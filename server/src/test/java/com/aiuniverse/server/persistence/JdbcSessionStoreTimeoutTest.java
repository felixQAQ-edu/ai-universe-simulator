package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.GameSession;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-025 决策 3 ·「不抛」不等于「不阻塞」:DB 挂起时 persist 必须在配置的上界内返回。
 *
 * <p>挂起 = {@code docker pause} 这个 PG 容器(进程冻结,TCP 不回包 —— 与「网络黑洞」同形,
 * 比「库拒绝连接」更坏:后者当场报错,前者只会让人干等)。用的是 <b>pg profile 的真实配置</b>
 * ({@code application-pg.yml} 的取连接 / 事务 / 套接字三个超时),不是测试里另配一份 ——
 * 否则这条测的是测试自己的数字。独立容器,暂停它不影响别的测试类。
 */
@SpringBootTest
@ActiveProfiles("pg")
@RequiresDocker
@Testcontainers
class JdbcSessionStoreTimeoutTest {

	/** 上界:取连接 1 s + 事务/语句 2 s ≈ 3 s(application-pg.yml),再留 1 s 调度余量。 */
	static final long BOUND_MS = 4000;

	@Container
	@ServiceConnection
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	SessionStore store;

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@Test
	void persistReturnsWithinBoundWhenDatabaseHangs() {
		GameSession session = session();
		store.persist(session); // 暖池:池里有一条空闲连接,暂停后会被借出来去等
		session.engine().applyNoOp("她停下了脚步。", "A");

		pg.getDockerClient().pauseContainerCmd(pg.getContainerId()).exec();
		try {
			long start = System.nanoTime();
			assertThatCode(() -> store.persist(session)).doesNotThrowAnyException();
			long elapsedMs = (System.nanoTime() - start) / 1_000_000;
			System.err.println("[db-tests] DB 挂起时 persist 实测阻塞 = " + elapsedMs + " ms(上界 " + BOUND_MS + " ms)");
			assertThat(elapsedMs).as("DB 挂起时 persist 的阻塞上界(名额占用上界)").isLessThan(BOUND_MS);
		} finally {
			pg.getDockerClient().unpauseContainerCmd(pg.getContainerId()).exec();
		}
	}

	private GameSession session() {
		List<AttributeAxis> axes = registry.meta("rules_creepy").attributes();
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4");
		w.put("mode", "single");
		w.putArray("archetypes").add("rules_creepy");
		w.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		w.putArray("rules");
		w.putArray("endings").addObject().put("id", "dead").put("title", "死亡").put("condition", "体力归零")
				.put("outcome", "failure").put("reached", false);
		Engine engine = new Engine(w, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		ArrayNode actions = mapper.createArrayNode();
		actions.addObject().put("id", "A").put("text", "等").put("hint", "");
		return new GameSession("save-t", engine, actions, "开场。");
	}
}
