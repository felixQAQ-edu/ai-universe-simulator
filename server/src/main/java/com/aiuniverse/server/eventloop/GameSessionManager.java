package com.aiuniverse.server.eventloop;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 内存存档表(规格 §3:Phase 1 单机单人,不起持久化)。每个 saveId 一条 {@link GameSession}
 * (持有 {@link Engine} 真理之源 + 当前相位 + 当前合法动作集);忙态守卫的并发原语活在会话的
 * {@code AtomicReference<TurnPhase>} 里。
 *
 * <p><b>边界</b>:会话由 world-gen(INITIALIZING,规格 §2)产出的 world 起;本批未起 world-gen,
 * 故 {@link #create} 接受外部传入的 world(供 dev 冒烟 / 后续 world-gen 调用)。
 */
@Service
public class GameSessionManager {

	private final ConcurrentHashMap<String, GameSession> sessions = new ConcurrentHashMap<>();
	private final ObjectMapper mapper;

	public GameSessionManager(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/** 从 world-gen 产出(整局真理之源根对象)新建会话;初始合法动作 = 根的 availableActions。 */
	public GameSession create(String saveId, ObjectNode world) {
		Engine engine = new Engine(world, mapper);
		JsonNode actions = world.get("availableActions");
		ArrayNode initial = actions != null && actions.isArray()
				? (ArrayNode) actions.deepCopy()
				: mapper.createArrayNode();
		GameSession session = new GameSession(saveId, engine, initial);
		sessions.put(saveId, session);
		return session;
	}

	public GameSession get(String saveId) {
		return sessions.get(saveId);
	}
}
