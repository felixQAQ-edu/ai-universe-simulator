package com.aiuniverse.server.archetype;

/**
 * 一个数值轴的 per-archetype 元数据(ADR-008 决策 1 / CONTEXT §三.14)。<b>非强制校验</b>——
 * 它告诉两个消费方「该渲染/生成哪个轴、叫什么、有无特殊逐回合行为」,不进 {@code validateWorld} 硬清单。
 *
 * @param key          JSON 里的数值键(camelCase 英文,如 {@code hp}/{@code hunger}/{@code knowledge}),引擎据此通用落账
 * @param displayName  玩家可见中文名(如「体力」「饥饿」「理智」「禁忌知识」),前端面板渲染用
 * @param min          下限(默认 0)
 * @param max          上限(默认 100)
 * @param behaviorHint 该轴的特殊逐回合行为提示文本(喂提示词让 AI 知道该怎么落该轴;{@code null}=无特殊行为,
 *                     由 hiddenLogic 据剧情结算)。涵盖<b>衰减型</b>(末日 hunger 每回合自然下降)、
 *                     <b>累积型/联动型</b>(克苏鲁 knowledge 求知则上涨、且越高 san 流失越快)等。
 *                     <b>引擎绝不读它</b>——一切由 AI 在 stateUpdate 落新绝对值,引擎对轴语义无知(ADR-008 决策 2)。
 */
public record AttributeAxis(String key, String displayName, int min, int max, String behaviorHint) {

	/** 常规 0–100 轴,无特殊逐回合行为(如规则怪谈 hp/san、末日/克苏鲁 hp)。 */
	public static AttributeAxis stable(String key, String displayName) {
		return new AttributeAxis(key, displayName, 0, 100, null);
	}

	/** 0–100 衰减型轴(如末日 hunger,每回合自然下降),hint 为喂提示词的衰减提示(引擎不读)。 */
	public static AttributeAxis decaying(String key, String displayName, String hint) {
		return new AttributeAxis(key, displayName, 0, 100, hint);
	}

	/**
	 * 0–100 累积型/联动型轴(如克苏鲁 knowledge:玩家求知则上涨、且越高越加速 san 流失),
	 * hint 为喂提示词的行为/联动提示(引擎不读,联动由 AI 落,守 ADR-008 决策 1/2 引擎无知)。
	 */
	public static AttributeAxis accumulating(String key, String displayName, String hint) {
		return new AttributeAxis(key, displayName, 0, 100, hint);
	}
}
