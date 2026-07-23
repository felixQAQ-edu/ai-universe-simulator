package com.aiuniverse.server.archetype;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
 * @param bands        行为档(#3 数值行为化,纯叙事 legibility):该轴当前数值所处的「状态/叙事色彩」分档。
 *                     <b>只染叙事、绝不 gate 选项</b>(gating=#4,推迟到状态层)。{@link AxisRole} 感知:
 *                     depletion 轴 {@code threshold}=该档<b>上界(inclusive)</b>(值越低进越危的档),
 *                     accumulation 轴 {@code threshold}=该档<b>下界(inclusive)</b>(值越高进越深的档);
 *                     见 {@link #resolveBand(int)}。<b>引擎绝不读它</b>——两个消费方:event-loop 注入当前档
 *                     的 {@code label}/{@code narrationHint}(让叙事跟着状态走)+ 前端展示当前档 {@code label}。
 *                     可空(无档 → 该轴只显数字、不注入)。良构由构造器校验(阈值单调 + 覆盖域 + label 非空)。
 * @param perilAtHigh  <b>高位即危险</b>(ADR-018,per-archetype 轻量元数据第四次扩充)。<b>纯展示层元数据——引擎与
 *                     校验绝不读它</b>,不进 JSON schema、不进 wire schema({@code schemaVersion} 保 "0.4"),
 *                     与 {@link #bands}/{@link #behaviorHint} 同族。唯一消费方 = {@link BandRange#severity()}
 *                     的派生:区分两种 accumulation 轴——<b>双刃型</b>(克苏鲁 knowledge:求知则涨、越高越接近疯狂
 *                     → {@code true},高位档标 danger)与<b>纯成长型</b>(修仙 realm:越高越强 → {@code false},
 *                     全档 neutral)。二者同为 {@link AxisRole#ACCUMULATION} 却结果相反,正是本标存在的理由;
 *                     它<b>不是 {@link #lethal} 的替身</b>——lethal 是引擎会读的字段(触底 + 结局极性 gate),
 *                     绝不为染色去动它。depletion 轴不看本标(其危险方向由 {@code lethal} 决定)。
 */
public record AttributeAxis(String key, String displayName, int min, int max, String behaviorHint, AxisRole axisRole,
		boolean lethal, boolean perilAtHigh, List<Band> bands) {

	public AttributeAxis {
		bands = bands == null ? List.of() : List.copyOf(bands);
		validateBands(key, min, max, axisRole, bands);
	}

	/** 兼容既有 8 参调用(不标高位危险):perilAtHigh 默认 false(安全方向)。 */
	public AttributeAxis(String key, String displayName, int min, int max, String behaviorHint, AxisRole axisRole,
			boolean lethal, List<Band> bands) {
		this(key, displayName, min, max, behaviorHint, axisRole, lethal, false, bands);
	}

	/** 兼容既有 7 参调用(无行为档):bands 默认空、perilAtHigh 默认 false。 */
	public AttributeAxis(String key, String displayName, int min, int max, String behaviorHint, AxisRole axisRole,
			boolean lethal) {
		this(key, displayName, min, max, behaviorHint, axisRole, lethal, false, List.of());
	}

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

	/**
	 * 一个行为档(#3 数值行为化)。纯叙事元数据,引擎不读。
	 *
	 * @param threshold     档边界(inclusive):depletion=上界、accumulation=下界(见 {@link AttributeAxis} 注)
	 * @param label         档名(玩家可见中文短词,如「濒危」「灵力枯竭」「深陷」),前端数值旁展示
	 * @param narrationHint 叙事提示(喂 event-loop 让 AI 让叙事体现该档状态;<b>不下发前端</b>,仅服务端注入)
	 */
	public record Band(int threshold, String label, String narrationHint) {
	}

	/**
	 * 行为档的<b>显式值区间投影</b>(下发前端用)。区别于 {@link Band#threshold}(内部按 axisRole 定上/下界):
	 * {@code [min,max]} 是该档的 inclusive 闭区间,<b>axisRole 无关、自描述</b>——前端只需「{@code min≤value≤max}」
	 * 即可解析当前档,无须懂 depletion/accumulation(守 ADR-003 展示层语义无关)。各区间连续、不重、覆盖整个值域。
	 *
	 * @param severity 该档的<b>风险等级</b>(ADR-018 severity 契约,服务端派生、前端只渲染)。前端按区间匹配当前档
	 *                 = 数据匹配(合法);判断该档危不危险 = 语义判断,已在此派生完毕(见 {@link #bandRanges()})。
	 */
	public record BandRange(int min, int max, String label, Severity severity) {
	}

	/**
	 * 一个行为档的风险等级(ADR-018,<b>语义产出方原则</b>第三次实例化:ADR-010「AI 标 outcome、引擎只读」/
	 * ADR-011「AI 写 hint、引擎不裁决」→ 本次「<b>服务端派生 severity、前端只渲染</b>」)。
	 * 前端四套数值主题<b>只呈现风险等级、不猜测数值好坏</b>,任何主题组件不得重新解释轴语义。
	 */
	public enum Severity {
		/** 无风险语义(常态;非致命资源轴与纯成长累积轴恒为此)。 */
		NEUTRAL,
		/** 预警(危险档的相邻档)。 */
		CAUTION,
		/** 危险(致命 depletion 轴的最低档 / 双刃 accumulation 轴的最高档)。 */
		DANGER;

		/** 下发前端的 wire 值(小写)。 */
		public String wire() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
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

	/**
	 * 0–100 <b>累积型双刃</b>轴({@link #accumulating} 的「高位即危险」变体,ADR-018):如克苏鲁 knowledge——
	 * 求知则涨(力量),但越高越接近疯狂(代价)。= accumulating + {@code perilAtHigh=true}:引擎侧与 accumulating
	 * <b>逐字相同</b>({@code ≤0} 仍不致死、{@code lethal=false}),只多一位<b>纯展示层</b>标 → 高位档染 danger。
	 * 对照 {@link #accumulating}(纯成长,如修仙境界:越高越强,全档 neutral)。
	 */
	public static AttributeAxis doubleEdged(String key, String displayName, String hint) {
		return new AttributeAxis(key, displayName, 0, 100, hint, AxisRole.ACCUMULATION, false, true, List.of());
	}

	/** 该轴是否累积型(引擎触底判定据此跳过它;true=不因 ≤0 致死)。 */
	public boolean isAccumulation() {
		return axisRole == AxisRole.ACCUMULATION;
	}

	/** 该轴是否致命(ADR-010:depletion 且 lethal=true → ≤0 死亡 + 触发结局极性 gate)。 */
	public boolean isLethal() {
		return lethal;
	}

	/** 返回挂上行为档的副本(#3:registry 据 per-archetype 草案给各轴配档)。其余字段(含 perilAtHigh)不变。 */
	public AttributeAxis withBands(Band... bands) {
		return new AttributeAxis(key, displayName, min, max, behaviorHint, axisRole, lethal, perilAtHigh,
				List.of(bands));
	}

	/**
	 * 纯函数:据当前数值解析所处的行为档(#3 数值行为化)。<b>axisRole 感知</b>(ADR-009):
	 * <ul>
	 *   <li><b>depletion</b>(值越低越危):{@code threshold}=档上界,取「≥value 的最小 threshold」(降序进带);</li>
	 *   <li><b>accumulation</b>(值越高越深):{@code threshold}=档下界,取「≤value 的最大 threshold」(升序进带)。</li>
	 * </ul>
	 * value 先 clamp 到 {@code [min,max]}。<b>无档 → {@code null}</b>(该轴只显数字、不注入)。
	 * 与档存储顺序无关(扫全集),良构(覆盖域)由构造器保证故必有命中。
	 */
	public Band resolveBand(int value) {
		if (bands.isEmpty()) {
			return null;
		}
		int v = Math.max(min, Math.min(max, value));
		Band best = null;
		if (axisRole == AxisRole.DEPLETION) {
			for (Band b : bands) { // threshold = 上界 inclusive:命中 ≥v 的最小 threshold
				if (b.threshold() >= v && (best == null || b.threshold() < best.threshold())) {
					best = b;
				}
			}
		} else {
			for (Band b : bands) { // threshold = 下界 inclusive:命中 ≤v 的最大 threshold
				if (b.threshold() <= v && (best == null || b.threshold() > best.threshold())) {
					best = b;
				}
			}
		}
		return best;
	}

	/**
	 * 行为档的显式值区间投影(下发前端,axisRole 无关)。据 axisRole 把 {@link Band#threshold} 翻成 inclusive
	 * {@code [min,max]} 闭区间(depletion threshold=上界 → 区间向下展到上一档+1 / 最低档展到 min;accumulation
	 * threshold=下界 → 区间向上展到下一档-1 / 最高档展到 max),按 min 升序。无档 → 空表。
	 *
	 * <p><b>severity 派生(ADR-018)</b>:同一趟(已按 min 升序)标风险等级,四分支——
	 * <ul>
	 *   <li><b>非致命 depletion</b>(修仙灵力):全 {@link Severity#NEUTRAL}(枯竭=力竭非必死);</li>
	 *   <li><b>致命 depletion</b>(体力/理智/饥饿/气血/道心/补给):<b>最低档</b> DANGER、次低档 CAUTION、其余 NEUTRAL;</li>
	 *   <li><b>accumulation 且 {@link #perilAtHigh}</b>(禁忌知识):<b>最高档</b> DANGER、次高档 CAUTION、其余 NEUTRAL;</li>
	 *   <li><b>其余 accumulation</b>(境界,纯成长):全 NEUTRAL。</li>
	 * </ul>
	 * <b>刻意不按 bands 的数组下标取「第二低/第二高」</b>——registry 里 depletion 轴的档是<b>降序</b>书写的
	 * (100/50/20),存储顺序不可靠;此处一律在<b>已排序</b>的区间表上按边缘位置标记。档数 {@code <2} 时
	 * 只标边缘档、不报错(退化情形合法)。
	 */
	public List<BandRange> bandRanges() {
		if (bands.isEmpty()) {
			return List.of();
		}
		List<Band> sorted = new ArrayList<>(bands);
		sorted.sort(Comparator.comparingInt(Band::threshold));
		List<BandRange> out = new ArrayList<>();
		if (axisRole == AxisRole.DEPLETION) {
			int lo = min; // threshold = 上界:区间 [上一档上界+1, 本档上界]
			for (Band b : sorted) {
				out.add(new BandRange(lo, b.threshold(), b.label(), Severity.NEUTRAL));
				lo = b.threshold() + 1;
			}
		} else {
			for (int i = 0; i < sorted.size(); i++) { // threshold = 下界:区间 [本档下界, 下一档下界-1]
				int hi = (i + 1 < sorted.size()) ? sorted.get(i + 1).threshold() - 1 : max;
				out.add(new BandRange(sorted.get(i).threshold(), hi, sorted.get(i).label(), Severity.NEUTRAL));
			}
		}
		return withSeverity(out);
	}

	/**
	 * 在已按 min 升序的区间表上标 severity(见 {@link #bandRanges()} 的四分支)。危险端 = 致命 depletion 的
	 * <b>表头</b>(值最低)/ 双刃 accumulation 的<b>表尾</b>(值最高);无危险端的轴原样返回(全 NEUTRAL)。
	 */
	private List<BandRange> withSeverity(List<BandRange> ranges) {
		boolean perilAtLow = axisRole == AxisRole.DEPLETION && lethal;
		boolean perilAtTop = axisRole == AxisRole.ACCUMULATION && perilAtHigh;
		if (!perilAtLow && !perilAtTop) {
			return List.copyOf(ranges); // 非致命资源轴 / 纯成长累积轴:全 neutral
		}
		int danger = perilAtLow ? 0 : ranges.size() - 1;
		int caution = perilAtLow ? 1 : ranges.size() - 2; // 相邻档;档数 <2 时越界 → 只标边缘档
		List<BandRange> out = new ArrayList<>(ranges.size());
		for (int i = 0; i < ranges.size(); i++) {
			Severity s = i == danger ? Severity.DANGER : (i == caution ? Severity.CAUTION : Severity.NEUTRAL);
			BandRange r = ranges.get(i);
			out.add(new BandRange(r.min(), r.max(), r.label(), s));
		}
		return List.copyOf(out);
	}

	/**
	 * 行为档良构校验(构造时即拦,registry 配错档不上线)。空档放过(可选)。要求:label 非空、threshold 在
	 * {@code [min,max]} 内、阈值两两不同、且<b>覆盖整个值域</b>——depletion 须有 threshold==max(覆盖顶档)、
	 * accumulation 须有 threshold==min(覆盖底档)。axisRole 为 {@code null} 时(不应发生)跳过覆盖校验。
	 */
	private static void validateBands(String key, int min, int max, AxisRole axisRole, List<Band> bands) {
		if (bands.isEmpty()) {
			return;
		}
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		boolean hasMin = false;
		boolean hasMax = false;
		for (Band b : bands) {
			if (b.label() == null || b.label().isBlank()) {
				throw new IllegalArgumentException("行为档 label 不可空(轴 " + key + ")");
			}
			if (b.threshold() < min || b.threshold() > max) {
				throw new IllegalArgumentException(
						"行为档 threshold " + b.threshold() + " 越界 [" + min + "," + max + "](轴 " + key + ")");
			}
			if (!seen.add(b.threshold())) {
				throw new IllegalArgumentException("行为档 threshold 重复:" + b.threshold() + "(轴 " + key + ")");
			}
			hasMin |= b.threshold() == min;
			hasMax |= b.threshold() == max;
		}
		if (axisRole == AxisRole.DEPLETION && !hasMax) {
			throw new IllegalArgumentException("depletion 轴行为档须含 threshold==" + max + " 覆盖顶档(轴 " + key + ")");
		}
		if (axisRole == AxisRole.ACCUMULATION && !hasMin) {
			throw new IllegalArgumentException("accumulation 轴行为档须含 threshold==" + min + " 覆盖底档(轴 " + key + ")");
		}
	}
}
