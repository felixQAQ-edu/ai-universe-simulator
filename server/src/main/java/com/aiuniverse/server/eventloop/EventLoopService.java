package com.aiuniverse.server.eventloop;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.LifetimeFamily;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.engine.GameSchemas;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.UsageCapture;
import com.aiuniverse.server.quota.QuotaGate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * event-loop 单回合流式接缝(规格 §3 GENERATING/SETTLING + §4/§6)——{@link TurnExecutor} 的生产实现。
 * 组合 {@link SentinelSplitter}(切分)+ {@link TurnReinfuser}(回灌)+ {@link Engine}(数据面内核):
 *
 * <ol>
 *   <li><b>GENERATING</b>:组主调用 prompt → 驱动 {@link LlmClient} 流式;叙事经哨兵切分逐字下发
 *       {@code sink.narrative},结构化尾巴 server 缓冲。</li>
 *   <li><b>SETTLING</b>:回灌叙事({@link TurnReinfuser})→ {@code validateTurn};不通过则<b>一次修复</b>
 *       (开回 json_object,回灌<b>同一</b> canonical 叙事 N)→ {@code Engine.apply} 落账 →
 *       发 {@code delta}(及 {@code ending})事件。</li>
 *   <li><b>降级</b>:修复仍败 / 回灌叙事非法 / 流中断 → 保守 no-op(规格 §6.5/§6.6):turn++、不脏写、
 *       响亮告警、已流叙事当氛围。</li>
 * </ol>
 *
 * <p><b>消毒纪律(规格 §1)</b>:{@code delta}/{@code ending} 一律由 {@code Engine.toClientState()}
 * 消毒投影构建,绝不含 {@code isTrue}/{@code hiddenLogic}。
 */
@Service
public class EventLoopService implements TurnExecutor {

	private static final Logger log = LoggerFactory.getLogger(EventLoopService.class);

	private final LlmClient llm;
	private final TurnPromptBuilder promptBuilder;
	private final ObjectMapper mapper;
	private final QuotaGate quota;
	private final ArchetypeRegistry registry;

	/** 无闸门形态(ADR-016 之前行为;既有测试调用点零改)。 */
	public EventLoopService(LlmClient llm, TurnPromptBuilder promptBuilder, ObjectMapper mapper) {
		this(llm, promptBuilder, mapper, QuotaGate.NOOP);
	}

	public EventLoopService(LlmClient llm, TurnPromptBuilder promptBuilder, ObjectMapper mapper, QuotaGate quota) {
		this(llm, promptBuilder, mapper, quota, new ArchetypeRegistry());
	}

	/**
	 * 全参形态(ADR-021 刀 2 增 {@code registry}:收束下限钳制要问「这个世界的命轴是哪条」,
	 * 那是<b>世界元数据</b>)。缺省重载补一个 {@code new ArchetypeRegistry()} ——
	 * registry 是无状态只读表,多一个实例无害,而<b>既有 15 个测试调用点因此零改</b>
	 * (同 {@code SessionStore.NOOP} / {@code QuotaGate.NOOP} 的既定接缝形态)。
	 */
	@Autowired
	public EventLoopService(LlmClient llm, TurnPromptBuilder promptBuilder, ObjectMapper mapper,
			QuotaGate quota, ArchetypeRegistry registry) {
		this.llm = llm;
		this.promptBuilder = promptBuilder;
		this.mapper = mapper;
		this.quota = quota;
		this.registry = registry;
	}

	@Override
	public TurnResult execute(GameSession session, String actionId, TurnEventSink sink) {
		Engine engine = session.engine();
		String actionText = actionTextOf(session, actionId);
		String prompt = promptBuilder.buildTurnPrompt(engine, actionId, actionText);

		// ── GENERATING:流式 + 哨兵切分(叙事逐字下发,尾巴缓冲)──
		StringBuilder narrativeBuf = new StringBuilder();
		SentinelSplitter splitter = new SentinelSplitter(inc -> {
			narrativeBuf.append(inc);
			sink.narrative(inc);
		});
		UsageCapture usage = new UsageCapture(splitter::accept);
		try {
			llm.streamChat(new ChatRequest(prompt, false), usage);
		} catch (LlmException e) {
			// 流中断:flush 残留(不会再有哨兵),把已生成的部分叙事当氛围,再保守 no-op。
			splitter.end();
			log.warn("[event-loop] save={} 主调用流中断,保守 no-op 降级:{}", session.saveId(), e.getMessage());
			return degrade(session, actionId, narrativeBuf.toString(), sink);
		}
		splitter.end();
		logUsage(session, "主调用", usage);
		session.phase().set(TurnPhase.SETTLING);

		String narrative = narrativeBuf.toString();
		String tail = splitter.tail();

		// 叙事非法(空)或根本无尾巴 → 修复救不了(叙事已流出 / 无尾可修)→ 直接降级(§6.6)。
		if (narrative.isBlank() || !splitter.sentinelSeen() || tail.isBlank()) {
			log.warn("[event-loop] save={} 叙事空或无结构化尾巴(sentinel={}),保守 no-op 降级",
					session.saveId(), splitter.sentinelSeen());
			return degrade(session, actionId, narrative, sink);
		}

		// ── SETTLING:回灌 → 校验 → (修复) → apply ──
		ObjectNode parsed = reinfuseAndValidate(tail, narrative);
		if (parsed == null) {
			parsed = repairOnce(session, narrative, tail, sink);
		}
		if (parsed == null) {
			return degrade(session, actionId, narrative, sink);
		}
		return settle(session, parsed, actionId, sink);
	}

	/** 回灌 + 校验;通过返回节点,任何失败(解析/校验)返回 null(交修复)。校验<b>必经回灌后节点</b>(§9)。 */
	private ObjectNode reinfuseAndValidate(String tail, String narrative) {
		ObjectNode parsed;
		try {
			parsed = TurnReinfuser.reinfuse(tail, narrative, mapper);
		} catch (LlmException e) {
			return null; // 尾巴解析失败
		}
		return GameSchemas.validateTurn(parsed).isEmpty() ? parsed : null;
	}

	/**
	 * 一次修复(规格 §6.4):带校验错误回喂模型「只回修正后的结构化尾巴」,开回 json_object;
	 * <b>回灌同一个 canonical 叙事 N</b>(绝不让修复改写已流出叙事)。成功返回节点,否则 null。
	 */
	private ObjectNode repairOnce(GameSession session, String narrative, String failedTail, TurnEventSink sink) {
		// 收集校验错误用于修复提示(对解析失败的尾巴给一条通用错)。
		List<String> errors;
		try {
			ObjectNode probe = TurnReinfuser.reinfuse(failedTail, narrative, mapper);
			errors = GameSchemas.validateTurn(probe);
		} catch (LlmException e) {
			errors = List.of("结构化尾巴非合法 JSON");
		}
		String repairPrompt = promptBuilder.buildRepairPrompt(failedTail, errors);

		StringBuilder repairBuf = new StringBuilder(); // 修复发不下发叙事(叙事已 canonical),只收尾巴
		UsageCapture usage = new UsageCapture(repairBuf::append);
		try {
			llm.streamChat(new ChatRequest(repairPrompt, true), usage);
		} catch (LlmException e) {
			log.warn("[event-loop] save={} 修复调用失败:{}", session.saveId(), e.getMessage());
			return null;
		}
		log.info("[event-loop] save={} 触发一次结构化修复(校验错误 {} 条)", session.saveId(), errors.size());
		logUsage(session, "修复", usage);
		return reinfuseAndValidate(repairBuf.toString(), narrative); // 回灌同一个 N
	}

	/** 落账 + 发事件(消毒)。先 apply(数值/规则/结局),再据 status 发 delta / ending。 */
	private TurnResult settle(GameSession session, ObjectNode parsed, String actionId, TurnEventSink sink) {
		Engine engine = session.engine();
		clampClosingVigorFloor(session, parsed);
		List<String> leak = engine.apply(parsed, actionId);
		if (!leak.isEmpty()) {
			log.warn("[event-loop] save={} T{} 泄露遥测命中(非实时拦截,§1c):{}",
					session.saveId(), engine.turn(), leak);
		}
		// 可观测性(E'' 顺带):正常回合一条 INFO(action + 落账后数值 + 提议 ending),冒烟排查不再解剖 heap。
		log.info("[event-loop] save={} T{} action={} 落账 attrs={} ending={}",
				session.saveId(), engine.turn(), actionId, engine.attributes(),
				parsed.path("ending").isNull() ? "null" : parsed.path("ending").path("id").asString(""));
		updateActionsFromParsed(session, parsed);
		sink.delta(buildDelta(session));
		if ("ended".equals(engine.status())) {
			sink.ending(buildEnding(engine));
			return new TurnResult(true);
		}
		return new TurnResult(false);
	}

	/**
	 * usage 收口(ADR-016):INFO 观测 + ¥ 记账旁挂。有 usage 块记一条 INFO 并入账;
	 * 无(mock 等)静默跳过——mock 天然免疫 ¥ 记账。
	 */
	private void logUsage(GameSession session, String call, UsageCapture usage) {
		if (usage.usage() != null) {
			log.info("[event-loop] save={} usage {} {}", session.saveId(), call, usage.usage().display());
		}
		quota.record(usage.usage());
	}

	/** 保守 no-op 降级(§6.5/§6.6):turn++、不脏写、复用动作、响亮告警、发 delta 让玩家可继续。 */
	private TurnResult degrade(GameSession session, String actionId, String narrative, TurnEventSink sink) {
		Engine engine = session.engine();
		engine.applyNoOp(narrative, actionId);
		log.warn("[event-loop] save={} 回合 no-op 降级落地:turn={} hp/san 未动,复用上一组动作", session.saveId(), engine.turn());
		sink.delta(buildDelta(session)); // 复用 session.currentActions(未更新)
		return new TurnResult(false);
	}

	// ── 消毒投影下的事件构建(规格 §1:经 toClientState)─────────────────────
	private ObjectNode buildDelta(GameSession session) {
		Engine engine = session.engine();
		ObjectNode client = engine.toClientState(); // 已剥 isTrue/hiddenLogic
		ObjectNode delta = mapper.createObjectNode();
		delta.put("turn", engine.turn());
		delta.put("status", engine.status());
		// 数值轴按声明顺序逐个作 top-level 字段下发(对 key 无知):规则怪谈 hp/san、末日 hp/hunger。
		// 前端按返回的 attributes key + 元数据中文名渲染(ADR-008 决策 1 前端消费方)。
		for (Map.Entry<String, Double> e : engine.attributes().entrySet()) {
			putNumber(delta, e.getKey(), e.getValue());
		}
		// discovered 规则:只带 id + content(消毒后无隐藏字段)。
		ArrayNode discovered = delta.putArray("discoveredRules");
		for (JsonNode r : client.path("rules")) {
			if (r.path("discovered").asBoolean(false)) {
				discovered.addObject().put("id", r.path("id").asInt()).put("content", r.path("content").asString(""));
			}
		}
		// availableActions:本回合下发集(无隐藏字段);ended 回合可空,客户端忽略。
		ArrayNode actions = session.currentActions();
		delta.set("availableActions", actions == null ? mapper.createArrayNode() : actions.deepCopy());
		return delta;
	}

	private ObjectNode buildEnding(Engine engine) {
		ObjectNode client = engine.toClientState();
		for (JsonNode e : client.path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				ObjectNode out = mapper.createObjectNode();
				out.put("id", e.path("id").asString(""));
				out.put("title", e.path("title").asString(""));
				out.put("description", e.path("description").asString(""));
				return out;
			}
		}
		return mapper.createObjectNode(); // 理论不达(ended 必有 reached 结局)
	}

	private void updateActionsFromParsed(GameSession session, ObjectNode parsed) {
		JsonNode actions = parsed.get("availableActions");
		if (actions != null && actions.isArray() && !actions.isEmpty()) {
			ArrayNode next = (ArrayNode) actions.deepCopy();
			appendLifeExitAction(session, next);
			session.setCurrentActions(next);
		}
	}

	/**
	 * 一生制「就到这里」出口(ADR-020 刀 5 · B,路径 (c) 服务端追加)。
	 *
	 * <p><b>为什么在这里、而不是让 AI 产出</b>(Felix 2026-08-10 裁定):让模型自己每回合记得
	 * 给这一项,等于用<b>模型自律</b>去修「模型自律守不住」——而 F-020 刚刚证伪的就是这个。
	 * 追加在<b>校验之后</b>,故 {@code TURN_SCHEMA} 的 {@code maxItems 4} 一字未动
	 * (不放行动校验层);{@link GameSession#isLegalAction} 按 {@code currentActions} 放行,
	 * 前端按同一份 delta 渲染,<b>两侧都零改动</b>。
	 *
	 * <p><b>措辞随人生阶段查表</b>(ADR-020 §2 补记第 2 条):它同时是「时钟是否生效」的
	 * <b>可见证据</b>——T10 与 T90 的文字必然不同,因为是查出来的。
	 * <b>幼年 / 少年不追加</b>(§2 补记第 1 条的修订:出口的前提是你有一个可以放弃的人生)。
	 *
	 * <p>结局回合({@code status != ongoing})不追加——那时局已经结束,再给出口没有意义。
	 */
	private void appendLifeExitAction(GameSession session, ArrayNode actions) {
		Engine engine = session.engine();
		if (!"ongoing".equals(engine.status())) {
			return;
		}
		LifeStageTable table = lifetimeTable(engine);
		if (table == null) {
			return; // 只对单体一生制世界生效;融合局不追加(融合路径一行不动)
		}
		LifeStage stage = table.stageAt(engine.turn() + 1); // 这组选项通向的那一回合
		if (!stage.hasExit()) {
			return;
		}
		boolean pressed = exitAlreadyPressed(engine);
		actions.addObject()
				.put("id", LifeStageTable.EXIT_ACTION_ID)
				.put("text", pressed ? table.exitTextAfter() : stage.exitText())
				.put("hint", pressed ? table.exitHintAfter() : table.exitHint());
	}

	/**
	 * 「就到这里」是否已经被按过(ADR-020 刀 7 · F-021 故障 ① 的<b>硬信号</b>)。
	 *
	 * <p><b>为什么不用 {@code timeline}</b>:刀 5 让模型往 {@code timeline} 里写「已进入收束段(第 k 回合…)」,
	 * 那是<b>软的</b> —— 模型漏写就没了(槽内那句「漏写等于收束段丢失」就是它软的自陈)。
	 *
	 * <p><b>硬载体是玩家选过的动作 id 本身</b>:{@code Engine.apply} 每回合把 {@code playerAction} 记进 log;
	 * 超出 {@code LOG_KEEP} 后 {@code compressLog} 把它折成 {@code [T64选X]} 串进 {@code logSummary}。
	 * 两者<b>都落盘、都经 restore 回来</b> → 本判定<b>零新状态、跨压缩、跨续局、跨 redeploy</b>。
	 *
	 * <p>id 是我方指定的 {@code X}(避开骨架的 A/B/C/D),故子串匹配不会与别的动作混。
	 */
	private boolean exitAlreadyPressed(Engine engine) {
		for (ObjectNode e : engine.log()) {
			if (LifeStageTable.EXIT_ACTION_ID.equals(e.path("playerAction").asString(""))) {
				return true;
			}
		}
		// 折叠后的旧回合只剩 [T{n}选{id}] 这一种形态,故子串即判据。
		return engine.logSummary().contains("选" + LifeStageTable.EXIT_ACTION_ID);
	}

	/** ADR-020 §8 补记:收束阶段气力下限。 */
	private static final double CLOSING_VIGOR_FLOOR = 15;

	/**
	 * 收束阶段气力下限的<b>钳制兜底</b>(ADR-020 刀 7 · B②,F-021 故障 ③)。
	 *
	 * <p><b>⚠️ 明确是兜底,不是主力。</b> 主力是 B①(给气力补 {@code behaviorHint},每回合提醒);
	 * 本条只在主力没兜住时接一下,并<b>记一条 issue</b>。
	 *
	 * <p><b>两条同刀的理由(ADR-020 §8 补记二)</b>:只做 ① → 下次不过仍分不清是「写了还是不够」
	 * 还是「软的本来不行」;只做 ② → 永远不知道 ① 会不会自己解决。
	 * <b>两条一起才能读出信息</b> —— 冒烟后看这条 issue 有没有被触发:
	 * <b>未触发 = ① 够了,本方法是可观察的死代码;触发了 = 软的确实不行,本方法在干活。</b>
	 * (该读法已写进刀 8 冒烟清单,否则 issue 记了没人看。)
	 *
	 * <p><b>位置</b>:{@code validateTurn} 之后、{@code Engine.apply} 之前改写 {@code parsed} 的
	 * {@code stateUpdate} —— 与刀 5 出口追加<b>同一层同一手法</b>;
	 * <b>Engine 现有行为零动</b>({@code EngineGoldenTest} 直喂 Engine,不经本服务;
	 * 唯一新增是 {@code Engine.recordIssue} 这个纯增量写入口)。
	 *
	 * <p><b>触发条件</b>:单体《寻常》局 + 已进入末段(第 {@code FINAL_STAGE_FROM_TURN} 回合起)
	 * <b>或</b>已按过「就到这里」(收束段)+ 模型给的气力 {@code < 15}。
	 * 抬到 15 而非更高:gate 阈值 10 /「衰弱」档边界 20 之间的那 5 点余量(ADR-020 §8 补记)。
	 */
	private void clampClosingVigorFloor(GameSession session, ObjectNode parsed) {
		Engine engine = session.engine();
		LifeStageTable table = lifetimeTable(engine);
		if (table == null) {
			return;
		}
		// 致命轴 key 从 registry 取,不再硬编码 "vigor"(ADR-021 刀 2 · C:那条硬编码对动物人生
		// 【直接 return 且不报错】= 静默失效)。registry 构造期已断言一生制世界恰好一条致命轴。
		// ⚠️ 刻意走 registry 而不是从引擎的轴集反推:那样会依赖「这一局的引擎被正确播种」,
		//    而 2 参构造的引擎(golden parity 默认:全 depletion 全致命)会反推出 4 条轴 —— 本刀实测踩过。
		//    「哪条是这个世界的命轴」是【世界元数据】,不是会话状态。
		String vigorKey = ArchetypeRegistry.lethalKeys(registry.meta(archetypeOf(engine)).attributes())
				.iterator().next();
		int nextTurn = engine.turn() + 1;
		boolean closing = nextTurn >= table.finalStageFromTurn() || exitAlreadyPressed(engine);
		if (!closing) {
			return;
		}
		JsonNode upd = parsed.get("stateUpdate");
		if (upd == null || !upd.isObject() || !upd.has(vigorKey)) {
			return; // 模型没给气力 → Engine 缺省保留当前值,不在这里臆造
		}
		double proposed = upd.get(vigorKey).asDouble();
		if (proposed >= CLOSING_VIGOR_FLOOR) {
			return;
		}
		((ObjectNode) upd).put(vigorKey, CLOSING_VIGOR_FLOOR);
		// 记进引擎 issues(纯增量入口 Engine.recordIssue):随 toPersistedState 落盘、经 restore 回载,
		// 故冒烟后可直接从 /data/<saveId>.json 取出核对,不必翻 fly logs。
		// ⚠️ 措辞要能【一眼分辨是钳制】且带原值 —— 刀 8 冒烟靠数这行的触发次数判「B① 够不够」,
		//    与 Engine 自己那条「跳变过大 60->15(需复核)」必须读得出区别,含糊不得。
		// ⚠️ issues 不进 snapshot() → 模型看不见自己被钳制(F-020 挂账,本刀不修)。
		engine.recordIssue("T" + nextTurn + " " + vigorKey + " 收束下限钳制 " + fmtVigor(proposed)
				+ "->" + fmtVigor(CLOSING_VIGOR_FLOOR));
		log.warn("[event-loop] save={} T{} 收束阶段气力 {} 低于下限 {},已钳制(§8 兜底)",
				session.saveId(), nextTurn, fmtVigor(proposed), fmtVigor(CLOSING_VIGOR_FLOOR));
	}

	private static String fmtVigor(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}

	/**
	 * 本局若是<b>单体一生制</b>世界则返回它的时钟表,否则 {@code null}(融合局一行不动)。
	 *
	 * <p><b>ADR-021 刀 2 · B</b>:原先这里是第四张硬编码世界名表
	 * {@code LIFETIME_EXIT_ARCHETYPES = Set.of("life_sim")},与 {@code LifetimeFamily.WORLD_FAMILY}
	 * <b>同集同义</b>(那张的注释自陈是「一生制世界」)—— 已合并,<b>「谁是一生制世界」现在只有一处答案</b>。
	 */
	private LifeStageTable lifetimeTable(Engine engine) {
		if (engine.world().path("archetypes").size() != 1) {
			return null;
		}
		String archetype = archetypeOf(engine);
		return LifetimeFamily.isLifetime(archetype) ? LifeStageTables.of(archetype) : null;
	}

	private static String archetypeOf(Engine engine) {
		return engine.world().path("archetypes").path(0).asString("");
	}

	private String actionTextOf(GameSession session, String actionId) {
		ArrayNode actions = session.currentActions();
		if (actions != null) {
			for (JsonNode a : actions) {
				if (actionId.equals(a.path("id").asString(null))) {
					return a.path("text").asString("");
				}
			}
		}
		return "";
	}

	private static void putNumber(ObjectNode node, String key, double x) {
		if (x == Math.rint(x)) {
			node.put(key, (long) x);
		} else {
			node.put(key, x);
		}
	}
}
