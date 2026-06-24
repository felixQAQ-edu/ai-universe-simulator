package com.aiuniverse.server.eventloop;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;

/**
 * 组装 event-loop 单回合提示(ADR-006 线上格式)。运行时真理之源与 {@code prompts/event-loop.md}
 * (人类可读的核心资产,CONTEXT §三.6)保持同形:<b>prose 先行 + 哨兵 {@value SentinelSplitter#SENTINEL}
 * + 结构化尾巴(尾巴去掉 narrative 字段)</b>。
 *
 * <p><b>多模式注入(ADR-008 决策 3)</b>:从 {@link ArchetypeRegistry} 取本局 archetype 的元数据,注入
 * 「正在推进的模式名 + 本回合要维护的数值轴(含中文意象 + 衰减提示)+ stateUpdate 格式」。回合里 AI 据此
 * 持续维护该模式数值(末日每回合落 hunger 自然衰减,决策 2)。<b>叙事清洁度硬约束(禁内部字段名/markdown 头)
 * 保留</b>(Phase 1 v0.3),禁名单从数值轴 key 动态生成。archetype 从 {@link Engine#world()} 的 archetypes 取。
 *
 * <p>键名严格对齐 {@code schema.py}/{@code Engine.apply}(规格 §4.4):{@code stateUpdate{<各数值轴>,timeline}} /
 * {@code triggeredRuleIds[int]} / {@code discoveredRuleIds[int]} / {@code availableActions[{id,text,hint}]}(2–4) /
 * {@code ending: null | {id<string>,reached<bool>}}。
 *
 * <p>叙事长度约束(A-1)保留:目标 2–4 句/约 120–200 字、硬上限约 280 字。
 */
@Component
public final class TurnPromptBuilder {

	private final ArchetypeRegistry registry;

	public TurnPromptBuilder(ArchetypeRegistry registry) {
		this.registry = registry;
	}

	/** 数值轴 key → 叙事中文意象提示(清洁度:数值变化只用意象,不直呼字段名)。缺省回落 displayName。 */
	private static final Map<String, String> IMAGERY = Map.of(
			"hp", "身体/伤势/气力(如「伤口又裂开」)",
			"san", "神智/理智/心神(如「神智几近崩断」)",
			"hunger", "饥饿/腹中空乏/虚脱无力(如「饿得手指发抖」)");

	/** 通用骨架。注入变量:模式名 / 数值轴维护块 / 禁用字段名清单 / stateUpdate 字段格式。 */
	private static final String SKELETON = """
			你是 UG Engine 的事件流模块,正在推进一局%1$s。你会收到完整世界设定(world / character /
			rules / endings)与当前 state(含 logSummary 与近几回合 log)。规则的 isTrue 与 hiddenLogic
			是你的内部依据,用来决定后果,但绝不能出现在叙事等任何玩家可见文本里。

			【叙事清洁度 · 破第四面墙即不合格】(1)禁止把内部字段名写进叙事——%3$s 等是引擎内部命名,
			叙事里绝不出现(包括「%4$s值」这类直呼);数值变化只用中文意象:
			%2$s
			(2)禁止 markdown 标题/结构:叙事是纯散文,不要 #/## 标题、不要「第 N 回合」之类回合头、不要列表符号。

			【叙事长度 · 可玩性约束】每回合叙事简洁克制:目标 2-4 句、约 120-200 字,硬上限约 280 字;宁短勿长
			(玩家在手机上逐字读完整局,叙事过长则累、反而冲淡紧张感)。以短叙事主体为主(承接玩家行动的直接后果 +
			一笔氛围),细节点到为止、可选,不堆砌环境描写或心理独白;不为压长度牺牲氛围或逻辑自洽,该交代的后果
			与该埋的线索仍要给到,只是更凝练(写得紧,不是写得少)。

			【输出格式 · 严格遵守】先逐字输出本回合中文叙事散文(承接玩家上一步行动的后果,氛围贴合本模式、逻辑自洽),
			叙事之后另起一行,输出一行哨兵 %5$s,哨兵之后输出本回合的结构化尾巴 JSON。叙事在哨兵前、尾巴在哨兵后,
			两者之间只有这一行哨兵,不要有别的分隔符,不要用 markdown 围栏。

			结构化尾巴 JSON(不含 narrative 字段,叙事已在哨兵前流出)字段:
			- stateUpdate:{ %6$s, "timeline": "<一句话世界线>" }
			  (各数值轴是本回合结束后的新绝对值,0-100,按 hiddenLogic 结算,与历史不得矛盾、不得无故回升)
			%7$s
			- triggeredRuleIds:本回合触发的规则 id 整数数组(没有则 [])
			- discoveredRuleIds:本回合玩家验证真伪/看清机制的规则 id 整数数组(没有则 [])
			- availableActions:2-4 个合法行动,id 用大写字母 A/B/C/D,text 中文且各有取舍
			- ending:命中某 endings[].condition 时为 { "reached": true, "id": "<结局id 字符串>" },否则 null
			""";

	private static final String REPAIR_SYSTEM = """
			你上一回合输出的结构化尾巴 JSON 未通过校验。请只回修正后的结构化尾巴 JSON(纯 JSON 对象,
			不含 narrative 字段、不要叙事、不要哨兵、不要围栏),字段与范围同前(stateUpdate 的各数值轴为 0-100
			绝对值,availableActions 2-4 个,ending 为 null 或 {reached,id})。
			""";

	/** 主调用提示(不开 json_object):prose 先行 + 哨兵 + 尾巴。按本局 archetype 注入数值轴/模式名。 */
	public String buildTurnPrompt(Engine engine, String actionId, String actionText) {
		ArchetypeMeta meta = resolveMeta(engine);
		String action = actionText == null || actionText.isBlank() ? actionId : actionId + " · " + actionText;
		String system = SKELETON.formatted(
				meta.displayName(),       // %1$s 模式名
				imageryBlock(meta),       // %2$s 各轴中文意象
				forbiddenNames(meta),     // %3$s 禁用字段名清单
				exampleForbiddenKey(meta), // %4$s 「XX值」直呼示例 key
				SentinelSplitter.SENTINEL, // %5$s 哨兵
				stateUpdateAxes(meta),    // %6$s stateUpdate 数值轴字段
				decayReminder(meta));     // %7$s 衰减轴维护提醒(无则空)
		return system
				+ "\n\n世界设定与当前状态(state 是真理之源):\n"
				+ engine.contextJson()
				+ "\n\n请推进第 " + (engine.turn() + 1) + " 回合。玩家本回合选择的行动:" + action;
	}

	/** 修复提示(开回 json_object,规格 §6.4):带上校验错误 + 上次失败尾巴,只要修正后的尾巴 JSON。 */
	public String buildRepairPrompt(String failedTail, List<String> errors) {
		return REPAIR_SYSTEM
				+ "\n\n校验错误:\n- " + String.join("\n- ", errors)
				+ "\n\n你上次的结构化尾巴:\n" + failedTail;
	}

	/** archetype 从 world 取;未激活(或缺失)回落 rules_creepy(回合发生时世界已为激活模式生成)。 */
	private ArchetypeMeta resolveMeta(Engine engine) {
		String archetype = engine.world().path("archetypes").path(0).asString("");
		return registry.isActive(archetype) ? registry.meta(archetype) : registry.meta("rules_creepy");
	}

	/** 「- key(中文名):意象」逐轴。 */
	private static String imageryBlock(ArchetypeMeta meta) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : meta.attributes()) {
			String img = IMAGERY.getOrDefault(a.key(), a.displayName() + "的具体感受");
			sb.append("- ").append(a.key()).append("(").append(a.displayName()).append("):").append(img).append("\n");
		}
		return sb.toString().stripTrailing();
	}

	/** 禁用字段名清单:各数值轴 key + 引擎内部命名。 */
	private static String forbiddenNames(ArchetypeMeta meta) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : meta.attributes()) {
			sb.append(a.key()).append(" / ");
		}
		return sb.append("stateUpdate / timeline").toString();
	}

	/** 「XX值」直呼示例:取首个数值轴 key。 */
	private static String exampleForbiddenKey(ArchetypeMeta meta) {
		return meta.attributes().isEmpty() ? "hp" : meta.attributes().get(0).key();
	}

	/** stateUpdate 数值轴字段:`"hp": <0-100>, "san": <0-100>`。 */
	private static String stateUpdateAxes(ArchetypeMeta meta) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : meta.attributes()) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append('"').append(a.key()).append("\": <0-100 绝对值>");
		}
		return sb.toString();
	}

	/** 衰减轴维护提醒(末日 hunger 等):列出每回合须落衰减的轴 + 提示;无衰减轴则空串。 */
	private static String decayReminder(ArchetypeMeta meta) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : meta.attributes()) {
			if (a.decay() != null) {
				sb.append("  · ").append(a.key()).append("(").append(a.displayName()).append("):")
						.append(a.decay()).append("\n");
			}
		}
		if (sb.length() == 0) {
			return "";
		}
		return "  注意以下数值轴随回合自然变化,每回合都要在 stateUpdate 体现(给衰减后的新绝对值):\n"
				+ sb.toString().stripTrailing();
	}
}
