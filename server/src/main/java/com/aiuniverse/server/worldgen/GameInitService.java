package com.aiuniverse.server.worldgen;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.moderation.ModerationGateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * INITIALIZING 播种编排(设计稿 §3/§5/§6):world-gen 胖调用 → 提取 transient {@code openingNarrative}
 * → 解析初始动作(FALLBACK 兜底)→ 消毒玩家可见文本(审核接缝)→ 播种会话 → 建消毒 init 响应。
 *
 * <p><b>整局生命周期(设计稿 §1)</b>:INITIALIZING 全程在本方法内(尚无会话);成功 → 建会话即
 * PLAYING(turn FSM {@code AWAITING_ACTION});失败 → {@link WorldGenException} 冒泡到 controller 返
 * ERROR,<b>不创建任何会话</b>(故 ERROR 不是持久相位,而是「无半残会话」——设计稿 §4.4)。
 *
 * <p><b>消毒纪律(设计稿 §5)</b>:完整 world(含 hiddenLogic)只进引擎内存真理之源;init 响应的
 * {@code world} 走 {@code Engine.toClientState()} 消毒投影。{@code openingNarrative} 是 transient
 * init 字段,<b>不进持久化 state</b>(故从 world 根剥除后再播种,避免每回合 context/投影夹带它)。
 */
@Service
public class GameInitService {

	private final WorldGenService worldGen;
	private final GameSessionManager sessions;
	private final ModerationGateway moderation;
	private final ArchetypeRegistry archetypes;
	private final ObjectMapper mapper;

	public GameInitService(WorldGenService worldGen, GameSessionManager sessions,
			ModerationGateway moderation, ArchetypeRegistry archetypes, ObjectMapper mapper) {
		this.worldGen = worldGen;
		this.sessions = sessions;
		this.moderation = moderation;
		this.archetypes = archetypes;
		this.mapper = mapper;
	}

	/**
	 * 起一局新世界。
	 *
	 * @param archetype 模式 id(∈ CONTEXT §三.4 枚举且已激活;非法/未开放 → {@link IllegalArgumentException} → 400)
	 * @return 消毒后的 init 响应(saveId + 消毒 world + openingNarrative + 初始动作 + 数值轴元数据)
	 * @throws IllegalArgumentException archetype 非已知枚举(非法)或已知但未激活(未开放)→ controller 映 400
	 * @throws WorldGenException        world-gen 救不回 → 整局 ERROR(无会话残留)
	 */
	public InitResponse init(String archetype) {
		validateArchetype(archetype); // 非法/未开放 → IllegalArgumentException(早于 world-gen,不浪费一次生成)
		ObjectNode world = worldGen.generate(archetype); // 失败抛 WorldGenException → ERROR

		// 1. 提取 transient openingNarrative(过审核接缝),再从 world 根剥除(不入持久化 state)。
		String opening = moderation.review(world.path("openingNarrative").asString(""));
		world.remove("openingNarrative");

		// 2. 解析初始决策圈:world 给了合法的(2-4)就用,否则 FALLBACK(沿用 bake-off 兜底)。
		ArrayNode actions = resolveInitialActions(world);
		// world 根不再留 availableActions —— 决策圈走 session.currentActions(回合 delta 据它消毒下发),
		// 避免每回合 contextJson / toClientState 夹带它(与 event-loop golden 世界形态一致)。
		world.remove("availableActions");

		// 3. 玩家可见文本过审核接缝(no-op 占位,ADR-004 落地时此处已在网关后)。
		reviewVisibleText(world);

		// 4. 播种会话(INITIALIZING→PLAYING,turn FSM AWAITING_ACTION)。传入本模式三份元数据派生信息:
		//    累积型轴 key 集合(ADR-009 F-012:这些轴 ≤0 不触底)+ 轴 key→中文名(F-014 §5 兜底结局按中文匹配)
		//    + 非致命 depletion 轴 key 集合(ADR-010 F-015:这些轴 ≤0 不致死、不触发结局极性 gate,如修仙灵力)。
		String saveId = UUID.randomUUID().toString();
		GameSession session = sessions.create(saveId, world, actions,
				accumulationKeys(archetype), axisDisplayNames(archetype), nonLethalKeys(archetype));

		// 5. 消毒投影 + 初始动作 + openingNarrative + 本模式数值轴元数据(前端面板渲染)一次性下发。
		ObjectNode clientWorld = session.engine().toClientState();
		return new InitResponse(saveId, clientWorld, opening, actions, attributeMeta(archetype));
	}

	/** archetype 入参校验(ADR-008 决策 4):非已知枚举 → 非法;已知但未激活 → 未开放。两者均 → 400。 */
	private void validateArchetype(String archetype) {
		if (!archetypes.isKnown(archetype)) {
			throw new IllegalArgumentException("非法的 archetype:" + archetype);
		}
		if (!archetypes.isActive(archetype)) {
			throw new IllegalArgumentException("该模式尚未开放:" + archetype);
		}
	}

	/**
	 * 本模式累积型数值轴的 key 集合(ADR-009 F-012):喂引擎据此 gate 触底(accumulation 轴 ≤0 不致死)。
	 * 据 per-archetype 元数据算(轴角色来自元数据,引擎自身不判断);全 depletion 的模式返回空集(= 现状)。
	 */
	private Set<String> accumulationKeys(String archetype) {
		Set<String> keys = new LinkedHashSet<>();
		for (AttributeAxis a : archetypes.meta(archetype).attributes()) {
			if (a.isAccumulation()) {
				keys.add(a.key());
			}
		}
		return keys;
	}

	/** 本模式轴 key→中文名(F-014 §5:引擎兜底结局按中文 condition 匹配,如 {@code hp→气血});据元数据算。 */
	private Map<String, String> axisDisplayNames(String archetype) {
		Map<String, String> names = new LinkedHashMap<>();
		for (AttributeAxis a : archetypes.meta(archetype).attributes()) {
			names.put(a.key(), a.displayName());
		}
		return names;
	}

	/**
	 * 本模式非致命 depletion 轴的 key 集合(ADR-010 F-015):喂引擎据此判致命(这些轴 ≤0 不致死、不触发结局
	 * 极性 gate,如修仙灵力枯竭=力竭非必死)。据 per-archetype 元数据 {@code lethal=false} 的 depletion 轴算;
	 * accumulation 轴本就不触底、无须列入。全致命的模式(规则怪谈/末日/克苏鲁)返回空集(= 现状)。
	 */
	private Set<String> nonLethalKeys(String archetype) {
		Set<String> keys = new LinkedHashSet<>();
		for (AttributeAxis a : archetypes.meta(archetype).attributes()) {
			if (!a.isAccumulation() && !a.isLethal()) {
				keys.add(a.key());
			}
		}
		return keys;
	}

	/** 本模式数值轴元数据 {@code [{key,displayName}]}(顺序即面板顺序;decay/range 不下发前端)。 */
	private ArrayNode attributeMeta(String archetype) {
		ArrayNode axes = mapper.createArrayNode();
		for (AttributeAxis a : archetypes.meta(archetype).attributes()) {
			axes.addObject().put("key", a.key()).put("displayName", a.displayName());
		}
		return axes;
	}

	/** world 给了 2-4 个带 id/text 的动作则采用(深拷),否则用确定性 FALLBACK(沿用 bake-off run_scenario_B)。 */
	private ArrayNode resolveInitialActions(ObjectNode world) {
		JsonNode given = world.get("availableActions");
		if (given != null && given.isArray() && given.size() >= 2 && given.size() <= 4 && allHaveIdAndText(given)) {
			return (ArrayNode) given.deepCopy();
		}
		return fallbackActions();
	}

	private boolean allHaveIdAndText(JsonNode actions) {
		for (JsonNode a : actions) {
			if (!a.isObject() || a.path("id").asString("").isBlank() || a.path("text").asString("").isBlank()) {
				return false;
			}
		}
		return true;
	}

	/** bake-off FALLBACK(world 未带初始动作时):谨慎观察 / 原地等待 / 主动探查。 */
	private ArrayNode fallbackActions() {
		ArrayNode actions = mapper.createArrayNode();
		actions.addObject().put("id", "A").put("text", "谨慎观察四周").put("hint", "");
		actions.addObject().put("id", "B").put("text", "原地等待").put("hint", "");
		actions.addObject().put("id", "C").put("text", "主动探查异常").put("hint", "");
		return actions;
	}

	/** 玩家可见文本过审核接缝(断言被调用即可,no-op 原样返回 → 写回无副作用)。 */
	private void reviewVisibleText(ObjectNode world) {
		JsonNode w = world.get("world");
		if (w != null && w.isObject()) {
			ObjectNode wo = (ObjectNode) w;
			if (wo.hasNonNull("title")) {
				wo.put("title", moderation.review(wo.get("title").asString("")));
			}
			if (wo.hasNonNull("background")) {
				wo.put("background", moderation.review(wo.get("background").asString("")));
			}
		}
		JsonNode rules = world.get("rules");
		if (rules != null && rules.isArray()) {
			for (JsonNode r : rules) {
				if (r.isObject() && r.hasNonNull("content")) {
					((ObjectNode) r).put("content", moderation.review(r.get("content").asString("")));
				}
			}
		}
	}
}
