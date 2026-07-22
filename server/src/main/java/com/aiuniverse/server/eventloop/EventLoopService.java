package com.aiuniverse.server.eventloop;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

	/** 无闸门形态(ADR-016 之前行为;既有测试调用点零改)。 */
	public EventLoopService(LlmClient llm, TurnPromptBuilder promptBuilder, ObjectMapper mapper) {
		this(llm, promptBuilder, mapper, QuotaGate.NOOP);
	}

	@Autowired
	public EventLoopService(LlmClient llm, TurnPromptBuilder promptBuilder, ObjectMapper mapper, QuotaGate quota) {
		this.llm = llm;
		this.promptBuilder = promptBuilder;
		this.mapper = mapper;
		this.quota = quota;
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
			session.setCurrentActions((ArrayNode) actions.deepCopy());
		}
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
