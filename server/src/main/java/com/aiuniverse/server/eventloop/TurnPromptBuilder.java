package com.aiuniverse.server.eventloop;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aiuniverse.server.engine.Engine;

/**
 * 组装 event-loop 单回合提示(ADR-006 线上格式)。运行时真理之源与 {@code prompts/event-loop.md}
 * (人类可读的核心资产,CONTEXT §三.6)保持同形:<b>prose 先行 + 哨兵 {@value SentinelSplitter#SENTINEL}
 * + 结构化尾巴(尾巴去掉 narrative 字段)</b>。
 *
 * <p>键名严格对齐 {@code schema.py}/{@code Engine.apply}(规格 §4.4):{@code stateUpdate{hp,san,timeline}} /
 * {@code triggeredRuleIds[int]} / {@code discoveredRuleIds[int]} / {@code availableActions[{id,text,hint}]}(2–4) /
 * {@code ending: null | {id<string>,reached<bool>}}。
 *
 * <p>注:目前模板内联于此(便于单测钉格式、零 FS 依赖);{@code prompts/event-loop.md} 为同义的可读副本。
 * 统一为「classpath 单源加载」留作后续清理(见 ROADMAP)。
 */
@Component
public final class TurnPromptBuilder {

	private static final String SYSTEM = """
			你是 UG Engine 的事件流模块,正在推进一局规则怪谈。你会收到完整世界设定(world / character /
			rules / endings)与当前 state(含 logSummary 与近几回合 log)。规则的 isTrue 与 hiddenLogic
			是你的内部依据,用来决定后果,但绝不能出现在叙事等任何玩家可见文本里。

			【叙事清洁度 · 破第四面墙即不合格】(1)禁止把内部字段名写进叙事——san / hp / stateUpdate /
			timeline 等是引擎内部命名,叙事里绝不出现(包括「san 值」「hp 值」「理智值归零」这类直呼);数值变化只用中文意象:
			san 用神智/理智/心神(如「神智几近崩断」),hp 用身体/伤势/气力(如「伤口又裂开」)。
			(2)禁止 markdown 标题/结构:叙事是纯散文,不要 #/## 标题、不要「第 N 回合」之类回合头、不要列表符号。

			【叙事长度 · 可玩性约束】每回合叙事简洁克制:目标 2-4 句、约 120-200 字,硬上限约 280 字;宁短勿长
			(玩家在手机上逐字读完整局,叙事过长则累、反而冲淡紧张感)。以短叙事主体为主(承接玩家行动的直接后果 +
			一笔瘆人氛围),细节点到为止、可选,不堆砌环境描写或心理独白;不为压长度牺牲氛围或逻辑自洽,该交代的后果
			与该埋的线索仍要给到,只是更凝练(写得紧,不是写得少)。

			【输出格式 · 严格遵守】先逐字输出本回合中文叙事散文(承接玩家上一步行动的后果,氛围瘆人、逻辑自洽),
			叙事之后另起一行,输出一行哨兵 %s,哨兵之后输出本回合的结构化尾巴 JSON。叙事在哨兵前、尾巴在哨兵后,
			两者之间只有这一行哨兵,不要有别的分隔符,不要用 markdown 围栏。

			结构化尾巴 JSON(不含 narrative 字段,叙事已在哨兵前流出)字段:
			- stateUpdate:{ "hp": <0-100 绝对值>, "san": <0-100 绝对值>, "timeline": "<一句话世界线>" }
			  (hp/san 是本回合结束后的新绝对值,按 hiddenLogic 结算,与历史不得矛盾,不得无故回血)
			- triggeredRuleIds:本回合触发的规则 id 整数数组(没有则 [])
			- discoveredRuleIds:本回合玩家验证真伪/看清机制的规则 id 整数数组(没有则 [])
			- availableActions:2-4 个合法行动,id 用大写字母 A/B/C/D,text 中文且各有取舍
			- ending:命中某 endings[].condition 时为 { "reached": true, "id": "<结局id 字符串>" },否则 null
			""".formatted(SentinelSplitter.SENTINEL);

	private static final String REPAIR_SYSTEM = """
			你上一回合输出的结构化尾巴 JSON 未通过校验。请只回修正后的结构化尾巴 JSON(纯 JSON 对象,
			不含 narrative 字段、不要叙事、不要哨兵、不要围栏),字段与范围同前(stateUpdate.hp/san 为 0-100
			绝对值,availableActions 2-4 个,ending 为 null 或 {reached,id})。
			""";

	/** 主调用提示(不开 json_object):prose 先行 + 哨兵 + 尾巴。 */
	public String buildTurnPrompt(Engine engine, String actionId, String actionText) {
		String action = actionText == null || actionText.isBlank() ? actionId : actionId + " · " + actionText;
		return SYSTEM
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
}
