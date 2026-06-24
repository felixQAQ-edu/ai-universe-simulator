package com.aiuniverse.server.archetype;

/**
 * 一个数值轴的 per-archetype 元数据(ADR-008 决策 1 / CONTEXT §三.14)。<b>非强制校验</b>——
 * 它告诉两个消费方「该渲染/生成哪个轴、叫什么、是否衰减」,不进 {@code validateWorld} 硬清单。
 *
 * @param key         JSON 里的数值键(camelCase 英文,如 {@code hp}/{@code hunger}),引擎据此通用落账
 * @param displayName 玩家可见中文名(如「体力」「饥饿」「理智」),前端面板渲染用
 * @param min         下限(默认 0)
 * @param max         上限(默认 100)
 * @param decay       衰减提示文本(喂提示词让 AI 知道该每回合衰减;{@code null}=不衰减)。
 *                    <b>引擎绝不读它</b>——衰减由 AI 在 stateUpdate 落新绝对值,引擎对此无知(ADR-008 决策 2)。
 */
public record AttributeAxis(String key, String displayName, int min, int max, String decay) {

	/** 常规 0–100 轴,不衰减(如规则怪谈 hp/san、末日 hp)。 */
	public static AttributeAxis stable(String key, String displayName) {
		return new AttributeAxis(key, displayName, 0, 100, null);
	}

	/** 0–100 衰减轴(如末日 hunger),decay 为喂提示词的提示文本(引擎不读)。 */
	public static AttributeAxis decaying(String key, String displayName, String decay) {
		return new AttributeAxis(key, displayName, 0, 100, decay);
	}
}
