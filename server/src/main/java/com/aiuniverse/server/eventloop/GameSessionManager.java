package com.aiuniverse.server.eventloop;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 内存存档表(规格 §3:Phase 1 单机单人,不起持久化)。每个 saveId 一条 {@link GameSession}
 * (持有 {@link Engine} 真理之源 + 当前相位 + 当前合法动作集);忙态守卫的并发原语活在会话的
 * {@code AtomicReference<TurnPhase>} 里。
 *
 * <p><b>边界</b>:会话由 world-gen(INITIALIZING,设计稿 §2/§6)产出的 world 起;播种由
 * {@link com.aiuniverse.server.worldgen.GameInitService} 编排(提取 transient openingNarrative、
 * 解析初始动作含 FALLBACK),本类只负责「建 Engine + 存表」,初始合法动作由调用方显式传入。
 */
@Service
public class GameSessionManager {

	private final ConcurrentHashMap<String, GameSession> sessions = new ConcurrentHashMap<>();
	private final ObjectMapper mapper;

	public GameSessionManager(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * 从 world-gen 产出(整局真理之源根对象)新建会话并存表,置 turn FSM 初相位 {@code AWAITING_ACTION}
	 * (整局 INITIALIZING→PLAYING)。初始合法动作由播种层显式解析后传入(world 给则用,否则 FALLBACK)。
	 *
	 * @param accumulationKeys  本局的累积型数值轴 key 集合(ADR-009 F-012:这些轴 ≤0 不触底);
	 *                          由播种层据 per-archetype 元数据算出。空集 = 全 depletion(= 现状)。
	 * @param axisDisplayNames  轴 key→中文名(F-014 §5 兜底结局按中文 condition 匹配用);同样据元数据算出。
	 */
	public GameSession create(String saveId, ObjectNode world, ArrayNode initialActions,
			Set<String> accumulationKeys, Map<String, String> axisDisplayNames) {
		Engine engine = new Engine(world, mapper, accumulationKeys, axisDisplayNames);
		ArrayNode initial = initialActions != null ? (ArrayNode) initialActions.deepCopy() : mapper.createArrayNode();
		GameSession session = new GameSession(saveId, engine, initial);
		sessions.put(saveId, session);
		return session;
	}

	public GameSession get(String saveId) {
		return sessions.get(saveId);
	}

	/** 当前活跃会话数(ops / 测试用;Phase 1 无淘汰,单调增)。 */
	public int activeCount() {
		return sessions.size();
	}
}
