package com.aiuniverse.server.worldgen;

import java.util.ArrayList;
import java.util.List;
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
		return init(List.of(archetype));
	}

	/**
	 * 起一局新世界(ADR-013:<b>有序 archetype 列表,host 在前</b>)。长度 1 → 单体(行为不变);
	 * 长度 2 → 融合世界(host=第一个,{@code mode:"hybrid"});非法组合(未知/未激活/长度>2/未登记融合)→
	 * {@link IllegalArgumentException} → 400(早于 world-gen,不浪费一次生成)。
	 */
	public InitResponse init(List<String> archetypeIds) {
		// 校验入参并解析数值轴集(单体=该模式轴;融合=host 在前的融合轴集,ADR-013 接活 ADR-012 mergeAxes)。
		List<AttributeAxis> axes = validateAndResolveAxes(archetypeIds);
		ObjectNode world = worldGen.generate(archetypeIds); // 失败抛 WorldGenException → ERROR

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

		// 4. 播种会话(INITIALIZING→PLAYING,turn FSM AWAITING_ACTION)。三份元数据派生信息据解析出的轴集算
		//    (单体/融合共用 ArchetypeRegistry 静态派生,单一真理源):累积型轴 key(ADR-009 F-012:≤0 不触底)
		//    + 轴 key→中文名(F-014 §5 兜底结局按中文匹配)+ 非致命 depletion 轴 key(ADR-010 F-015:≤0 不致死)。
		String saveId = UUID.randomUUID().toString();
		GameSession session = sessions.create(saveId, world, actions,
				ArchetypeRegistry.accumulationKeys(axes), ArchetypeRegistry.axisDisplayNames(axes),
				ArchetypeRegistry.nonLethalKeys(axes));

		// 5. 消毒投影 + 初始动作 + openingNarrative + 本局数值轴元数据(前端面板渲染)一次性下发。
		ObjectNode clientWorld = session.engine().toClientState();
		return new InitResponse(saveId, clientWorld, opening, actions, attributeMeta(axes));
	}

	/**
	 * 校验有序 archetype 列表并解析其数值轴集(ADR-013)。非已知/未激活/长度>2/未登记融合组合 → 非法(→400)。
	 *
	 * @return 单体=该模式轴;融合=host(第一个)在前的融合轴集(委托 {@link ArchetypeRegistry#fusedAxes})
	 */
	private List<AttributeAxis> validateAndResolveAxes(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new IllegalArgumentException("未指定 archetype");
		}
		if (ids.size() > 2) {
			throw new IllegalArgumentException("混合模式 round 1 仅支持 2 个 archetype:" + ids);
		}
		for (String id : ids) {
			validateArchetype(id); // 非已知/未激活 → IllegalArgumentException
		}
		return archetypes.resolveAxes(ids); // 单体/融合共用派生;未登记组合 → IllegalArgumentException
	}

	/**
	 * 续局查询(ADR-015 Slice 2,{@code GET /api/game/{saveId}/state} 的业务面):把一条已在内存表
	 * (含启动回载)的会话状态投影给前端。<b>复用 {@link InitResponse} 形态,不新造 DTO</b>;
	 * {@code openingNarrative} 恒空串(transient 字段本就不落盘,ADR-007 §三.12——前端续局散文由
	 * {@code world.state.log} 末条补位)。<b>消毒纪律</b>:world 走 {@code toClientState()} 视图 3,
	 * 绝不漏视图 1(落盘文档)。
	 *
	 * @return 会话不存在 → {@code null}(controller 映 404,前端静默清 saveId 回正常起局)
	 */
	public InitResponse resume(String saveId) {
		GameSession session = sessions.get(saveId);
		if (session == null) {
			return null;
		}
		ObjectNode clientWorld = session.engine().toClientState();
		List<String> ids = new ArrayList<>();
		clientWorld.path("archetypes").forEach(a -> ids.add(a.asString("")));
		List<AttributeAxis> axes = archetypes.resolveAxes(ids); // 与播种/回载同一真理源
		ArrayNode actions = session.currentActions();
		return new InitResponse(saveId, clientWorld, "",
				actions == null ? mapper.createArrayNode() : actions.deepCopy(), attributeMeta(axes));
	}

	/** 单个 archetype 入参校验(ADR-008 决策 4):非已知枚举 → 非法;已知但未激活 → 未开放。两者均 → 400。 */
	private void validateArchetype(String archetype) {
		if (!archetypes.isKnown(archetype)) {
			throw new IllegalArgumentException("非法的 archetype:" + archetype);
		}
		if (!archetypes.isActive(archetype)) {
			throw new IllegalArgumentException("该模式尚未开放:" + archetype);
		}
	}

	/**
	 * 本局数值轴元数据 {@code [{key,displayName,bands?}]}(顺序即面板顺序;behaviorHint/range 不下发前端)。
	 * 有行为档的轴带上 {@code bands:[{min,max,label,severity}]}(#3,显式 inclusive 区间投影,axisRole 无关——
	 * 前端只需「{@code min≤value≤max}」解析当前档,无须懂 depletion/accumulation;守 ADR-003 展示层语义无关);
	 * <b>不下发 narrationHint</b>(它仅服务端注入 prompt)。无档轴省略 {@code bands} 字段(前端只显数字)。
	 * 融合世界据融合轴集渲染(道心换皮 displayName / bands 直接生效,前端无感,ADR-013)。
	 *
	 * <p><b>severity(ADR-018 语义产出方原则)</b>:每档的风险等级在<b>服务端</b>据 axisRole/lethal/perilAtHigh
	 * 派生完毕(见 {@code AttributeAxis.bandRanges()}),前端只按区间匹配当前档并渲染,<b>不判断危不危险</b>。
	 * 本方法是 axesJson 的<b>唯一构建点</b>——{@code init} 与 {@code resume} 共用(同一构建路径,ADR-018),
	 * 两条路径的 severity 必然一致。走 API DTO 而非被校验的 wire schema → {@code schemaVersion} 保 "0.4"。
	 */
	private ArrayNode attributeMeta(List<AttributeAxis> axes) {
		ArrayNode out = mapper.createArrayNode();
		for (AttributeAxis a : axes) {
			ObjectNode axis = out.addObject().put("key", a.key()).put("displayName", a.displayName());
			if (!a.bands().isEmpty()) {
				ArrayNode bands = axis.putArray("bands");
				for (AttributeAxis.BandRange r : a.bandRanges()) {
					bands.addObject().put("min", r.min()).put("max", r.max()).put("label", r.label())
							.put("severity", r.severity().wire());
				}
			}
		}
		return out;
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
