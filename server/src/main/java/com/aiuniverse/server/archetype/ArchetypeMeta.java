package com.aiuniverse.server.archetype;

import java.util.List;

/**
 * 一个 archetype(模式)的轻量元数据(ADR-008 决策 1 核心新件 / CONTEXT §三.14)。
 * <b>非强制校验</b>,两个消费方:
 * <ul>
 *   <li><b>前端</b>:按 {@link #attributes} 渲染数值面板项 + 中文名(末日显「体力/饥饿」);</li>
 *   <li><b>提示词</b>:world-gen / event-loop 按 {@link #worldview}/{@link #attributes}/{@link #ruleForm}
 *       注入「该生成/维护哪些数值 + 衰减提示 + 规则形态」(ADR-008 决策 3 注入块的素材源)。</li>
 * </ul>
 *
 * <p>加模式 = 加一条本元数据 + 一个提示词注入块,<b>不碰引擎/校验核心</b>(决策 1 泛化后)。
 *
 * @param id          archetype id(∈ CONTEXT §三.4 枚举,snake_case)
 * @param displayName 玩家可见中文名(如「末日生存」「规则怪谈」)
 * @param tagline     选择屏一句话钩子(玩家可见中文,CONTEXT §三.3)——<b>仅供选择屏卡片展示</b>,
 *                    不进 world-gen 注入(注入用 {@link #worldview});区别于长 worldview。
 * @param vibeTag     选择屏氛围/危险短标签(如「诡异 · 高危」「荒凉 · 绝境」),同样仅展示用。
 * @param worldview   世界观描述(喂 world-gen 注入块)
 * @param attributes  数值轴清单(顺序即面板/提示词呈现顺序;至少一条)
 * @param ruleForm    该模式规则形态描述(喂注入块)——末日=生存法则/资源约束,规则怪谈=真假规则
 */
public record ArchetypeMeta(
		String id,
		String displayName,
		String tagline,
		String vibeTag,
		String worldview,
		List<AttributeAxis> attributes,
		String ruleForm) {

	public ArchetypeMeta {
		attributes = List.copyOf(attributes);
	}
}
