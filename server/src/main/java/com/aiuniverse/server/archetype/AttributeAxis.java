package com.aiuniverse.server.archetype;

/**
 * 一个数值轴的 per-archetype 元数据(ADR-008 决策 1 / CONTEXT §三.14;ADR-009 加 {@link #axisRole})。
 * <b>非强制校验</b>——它告诉两个消费方「该渲染/生成哪个轴、叫什么、有无特殊逐回合行为、是否会因 ≤0 致死」,
 * 不进 {@code validateWorld} 硬清单。
 *
 * @param key          JSON 里的数值键(camelCase 英文,如 {@code hp}/{@code hunger}/{@code knowledge}),引擎据此通用落账
 * @param displayName  玩家可见中文名(如「体力」「饥饿」「理智」「禁忌知识」「境界」),前端面板渲染用
 * @param min          下限(默认 0)
 * @param max          上限(默认 100)
 * @param behaviorHint 该轴的特殊逐回合行为提示文本(喂提示词让 AI 知道该怎么落该轴;{@code null}=无特殊行为,
 *                     由 hiddenLogic 据剧情结算)。涵盖<b>衰减型</b>(末日 hunger 每回合自然下降)、
 *                     <b>累积型/联动型</b>(克苏鲁 knowledge 求知则上涨、且越高 san 流失越快)等。
 *                     <b>引擎绝不读它</b>——一切由 AI 在 stateUpdate 落新绝对值,引擎对轴语义无知(ADR-008 决策 2)。
 * @param axisRole     该轴的角色(ADR-009 决策 1,引擎唯一会读的轴语义,刻意最小二分):
 *                     <ul>
 *                       <li>{@link AxisRole#DEPLETION} —— 越低越危,{@code ≤0} = 触底致死(hp/san/hunger/灵力)。</li>
 *                       <li>{@link AxisRole#ACCUMULATION} —— 0 是安全起点、往上涨,<b>引擎绝不因它 ≤0 判死</b>
 *                           (knowledge/境界)。「过高有代价」仍归 AI 落、引擎无知。</li>
 *                     </ul>
 * @param lethal       致命轴标(ADR-010 决策 2,F-015 关闭):该 depletion 轴 {@code ≤0} 是否=死亡 +
 *                     {@code ≤阈值} 是否触发结局极性 gate(§4.4)。<b>仅 hp 类生命轴 lethal</b>(hp/san/气血、
 *                     末日饥饿);<b>灵力(资源池)lethal=false</b>——枯竭=力竭非必死(关闭 F-015)。accumulation
 *                     轴恒非致命({@code ≤0} 本就不触底,lethal 对它无意义、恒 {@code false})。引擎只读这一个
 *                     bool(经播种层算出非致命 depletion 轴 key 集合传入),不懂任何具体轴语义(守 ADR-008)。
 */
public record AttributeAxis(String key, String displayName, int min, int max, String behaviorHint, AxisRole axisRole,
		boolean lethal) {

	/**
	 * 数值轴角色(ADR-009 决策 1,F-012 正解)。引擎触底判定<b>唯一</b>会读的轴语义:只区分「这轴 {@code ≤0}
	 * 要不要致死」,不懂任何具体轴(如「knowledge 高了拖累 san」「境界纯成长」——那些归 AI 落)。
	 */
	public enum AxisRole {
		/** 损耗型:越低越危,{@code ≤0} 触底致死(强制 ended + 兜底坏结局)。 */
		DEPLETION,
		/** 累积型:0 是安全起点,引擎绝不因 {@code ≤0} 判死。 */
		ACCUMULATION
	}

	/** 常规 0–100 损耗型【生命/致命】轴,无特殊逐回合行为(如规则怪谈 hp/san、末日/克苏鲁 hp)。lethal=true。 */
	public static AttributeAxis stable(String key, String displayName) {
		return new AttributeAxis(key, displayName, 0, 100, null, AxisRole.DEPLETION, true);
	}

	/**
	 * 0–100 损耗型【生命/致命】轴 + 特殊逐回合行为提示(衰减等;如末日 hunger 自然衰减),hint 为喂提示词
	 * 的行为提示(引擎不读)。角色 = {@link AxisRole#DEPLETION},{@code lethal=true}({@code ≤0} 触底致死)。
	 */
	public static AttributeAxis depleting(String key, String displayName, String hint) {
		return new AttributeAxis(key, displayName, 0, 100, hint, AxisRole.DEPLETION, true);
	}

	/** {@link #depleting} 的语义别名(衰减型致命轴;如末日 hunger 每回合自然下降,饥饿而亡)。 */
	public static AttributeAxis decaying(String key, String displayName, String hint) {
		return depleting(key, displayName, hint);
	}

	/**
	 * 0–100 损耗型【资源池·非致命】轴(ADR-010 决策 2:如修仙灵力——施法消耗、可恢复,枯竭=力竭非必死)。
	 * 角色 = {@link AxisRole#DEPLETION} 但 {@code lethal=false}:引擎绝不因它 {@code ≤0} 判死、也不据它 gate 结局
	 * (关闭 F-015)。hint 为喂提示词的行为提示(引擎不读)。
	 */
	public static AttributeAxis resource(String key, String displayName, String hint) {
		return new AttributeAxis(key, displayName, 0, 100, hint, AxisRole.DEPLETION, false);
	}

	/**
	 * 0–100 累积型/联动型轴(如克苏鲁 knowledge:求知则上涨、且越高越加速 san 流失;修仙境界:修炼则上涨、纯成长),
	 * hint 为喂提示词的行为/联动提示(引擎不读,联动由 AI 落,守 ADR-008 决策 1/2 引擎无知)。
	 * 角色 = {@link AxisRole#ACCUMULATION}(ADR-009 F-012 正解:引擎绝不因它 {@code ≤0} 判死,0=安全起点);
	 * 恒 {@code lethal=false}(累积轴本就不触底,致命标对它无意义)。
	 */
	public static AttributeAxis accumulating(String key, String displayName, String hint) {
		return new AttributeAxis(key, displayName, 0, 100, hint, AxisRole.ACCUMULATION, false);
	}

	/** 该轴是否累积型(引擎触底判定据此跳过它;true=不因 ≤0 致死)。 */
	public boolean isAccumulation() {
		return axisRole == AxisRole.ACCUMULATION;
	}

	/** 该轴是否致命(ADR-010:depletion 且 lethal=true → ≤0 死亡 + 触发结局极性 gate)。 */
	public boolean isLethal() {
		return lethal;
	}
}
