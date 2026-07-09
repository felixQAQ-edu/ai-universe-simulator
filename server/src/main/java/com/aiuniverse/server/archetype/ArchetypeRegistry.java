package com.aiuniverse.server.archetype;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * per-archetype 元数据登记处(ADR-008 决策 1;设计稿 §2)——「加一个模式」的单一落点:
 * 加模式 = 在此加一条 {@link ArchetypeMeta} + 写一个提示词注入块,<b>不碰引擎/校验/状态机核心</b>。
 *
 * <p><b>已知 vs 已激活</b>(设计稿 §5,init 入参校验用):
 * <ul>
 *   <li><b>已知</b> = CONTEXT §三.4 枚举的 5 个 id(rules_creepy/life_sim/cultivation/cyberpunk/apocalypse);
 *       非已知 → init 400(非法 archetype)。</li>
 *   <li><b>已激活</b> = 本批有元数据可生成的(rules_creepy + apocalypse + cthulhu + cultivation);已知但未激活
 *       (life_sim / cyberpunk)→ init 400「未开放」(占位枚举,等各自独立批 + 独立 world-gen 冒烟)。</li>
 * </ul>
 *
 * <p>本类<b>纯数据</b>(无 IO、无 LLM),元数据内联(便于单测钉结构、零 FS 依赖)。
 */
@Component
public class ArchetypeRegistry {

	/**
	 * 全部「已知」archetype id(用于 init 非法值判定)。CONTEXT §三.4 原 5 枚举 + 世界库 backlog 陆续上架的世界。
	 * {@code cthulhu}(克苏鲁)= 加世界流水线第一次正式复用(backlog 第一级,规则怪谈近亲);
	 * CONTEXT §三.4 枚举在本批冒烟验通后收口时同步追加。
	 */
	private static final Set<String> KNOWN = Set.of(
			"rules_creepy", "life_sim", "cultivation", "cyberpunk", "apocalypse", "cthulhu");

	/**
	 * 已知但未激活(占位枚举)的玩家可见中文名(CONTEXT §三.4)——选择屏渲染「敬请期待」灰显卡片用。
	 * 让 registry 成为「选择目录」的单一真理源(前端不硬编码模式清单)。保序 = 选择屏占位排序。
	 */
	private static final Map<String, String> INACTIVE_DISPLAY_NAMES;
	static {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("life_sim", "人生模拟");
		m.put("cyberpunk", "赛博朋克");
		INACTIVE_DISPLAY_NAMES = Collections.unmodifiableMap(m);
	}

	/** 已激活(有元数据)的 archetype → 元数据。保序便于稳定遍历。 */
	private final Map<String, ArchetypeMeta> active = new LinkedHashMap<>();

	public ArchetypeRegistry() {
		register(rulesCreepy());
		register(apocalypse());
		register(cthulhu());
		register(cultivation());
	}

	private void register(ArchetypeMeta meta) {
		active.put(meta.id(), meta);
	}

	/**
	 * 行为档草案(#3,Felix 2026-06-30 签字;文案=草稿,真机冒烟再调)。阈值全轴统一:
	 * depletion 切 50/20(threshold=上界 → 充沛 100 / 受创 50 / 濒危 20)、accumulation 切 30/60
	 * (threshold=下界 → 0 / 31 / 61)。{@link AttributeAxis#resolveBand(int)} axisRole 感知。
	 */
	private static AttributeAxis.Band band(int threshold, String label, String narrationHint) {
		return new AttributeAxis.Band(threshold, label, narrationHint);
	}

	/** id ∈ CONTEXT §三.4 枚举(已知)。非已知 → init 视为非法 400。 */
	public boolean isKnown(String archetype) {
		return KNOWN.contains(archetype);
	}

	/** id 已激活(本批可生成)。已知但未激活 → init 400「未开放」。 */
	public boolean isActive(String archetype) {
		return active.containsKey(archetype);
	}

	/**
	 * 取已激活 archetype 的元数据。
	 *
	 * @throws IllegalArgumentException 未激活(调用方应先 {@link #isActive} 守门)
	 */
	public ArchetypeMeta meta(String archetype) {
		ArchetypeMeta m = active.get(archetype);
		if (m == null) {
			throw new IllegalArgumentException("archetype 未激活或不存在:" + archetype);
		}
		return m;
	}

	/** 全部已激活元数据(ops / 测试用)。 */
	public List<ArchetypeMeta> activeMetas() {
		return List.copyOf(active.values());
	}

	// ── 轴合并(ADR-012 混合模式 round 1;纯函数、暂未接线的休眠件)──────────────

	/**
	 * 一个外来轴的<b>显示层换皮 override</b>(ADR-012 决策 2「语义换皮」+ ADR-014 决策 4 微扩)。合并时把外来轴
	 * 并入 host 世界观:<b>{@code key} / {@code axisRole} / {@code lethal} / {@code min}/{@code max} 一律不换</b>
	 * (引擎无感,守 ADR-008),换 {@code displayName} 与 {@code bands};{@code behaviorHint} <b>可选换</b>
	 * (ADR-014 修订 ADR-012 不换清单:原 hint 若含旧轴名(如「饥饿值」)与换皮名打架 → 给 override;
	 * 缺省 {@code null} = 沿用原 hint,round-1 换皮零变化。behaviorHint 本就是引擎一概不读的提示文本,
	 * 换它不触引擎语义)。
	 *
	 * @param displayName  换皮后的玩家可见中文名(如规则怪谈 san 的「理智」→修仙「道心」)
	 * @param bands        换皮后的行为档(host 世界观口吻重写;可空=不带档)
	 * @param behaviorHint 换皮后的逐回合行为提示({@code null}=沿用外来轴原 hint;引擎不读,仅喂提示词)
	 */
	public record AxisSkin(String displayName, List<AttributeAxis.Band> bands, String behaviorHint) {
		public AxisSkin {
			bands = bands == null ? List.of() : List.copyOf(bands);
		}

		/** 不换 behaviorHint 的换皮(ADR-012 原形态,round-1 道心走此构造)。 */
		public AxisSkin(String displayName, List<AttributeAxis.Band> bands) {
			this(displayName, bands, null);
		}
	}

	/** 修仙 × 规则怪谈(host=修仙)round 1 彩蛋:规则怪谈 san 换皮为修仙「道心」(见不可名状→掉道心,崩=走火入魔)。 */
	private static final Map<String, AxisSkin> CULTIVATION_RULES_CREEPY_SKINS = Map.of(
			"san", new AxisSkin("道心", List.of(
					band(100, "清明", "道心澄澈、心念通明,纵见异象亦不为所动"),
					band(50, "动摇", "窥见了不该见之物,道念浮动、杂念丛生,心神难安"),
					band(20, "崩缺", "道基已裂、心魔滋生,识海翻涌、几近走火入魔"))));

	/**
	 * 规则怪谈 × 末日生存(host=规则怪谈)round 1.5「守则即补给」(ADR-014):末日 hunger 换皮为「补给」
	 * (缺页人防工程的口粮账;bands 充足/紧缺/断粮,阈值沿用 depletion 50/20)。<b>behaviorHint override</b>
	 * (ADR-014 决策 4):原 hint 含「饥饿值」与换皮名打架 → 换成补给口吻(仍是 AI 落、引擎无知)。
	 * key/axisRole/lethal 不变 → 三轴全致命 {hp,san,hunger}(首例三致命轴,引擎天然支持)。
	 */
	private static final Map<String, AxisSkin> RULES_CREEPY_APOCALYPSE_SKINS = Map.of(
			"hunger", new AxisSkin("补给", List.of(
					band(100, "充足", "口粮尚有着落,数得出下一顿在哪"),
					band(50, "紧缺", "配给见底、口粮减半,饥饿开始啃噬判断"),
					band(20, "断粮", "断顿数日,眼前发黑,看什么都像吃的")),
					"补给随回合自然消耗,每回合约下降 5~10(配给日领取 / 外出搜刮 / 以物易物才回升);"
							+ "由你在 stateUpdate 给出消耗后的新绝对值,务必每回合都体现这一自然消耗。"));

	/**
	 * 已登记的融合组合(ADR-013 融合协议 + ADR-014 第二组合;key = {@code host×foreign} 有序,host 在前)。
	 * <b>方向敏感</b>——换皮 override 是 per-combo per-direction 手写(道心换皮只在 host=修仙 时成立);
	 * 未登记的有序组合(含反向 host)→ init 视为非法 400。加一组融合 = 本表项 + world-gen/event-loop 文案槽
	 * + 融合 meta-prompt + 种子池(骨架零改,ADR-014)。
	 */
	private static final Map<String, Map<String, AxisSkin>> FUSION_COMBOS = Map.of(
			fusionKey("cultivation", "rules_creepy"), CULTIVATION_RULES_CREEPY_SKINS,
			fusionKey("rules_creepy", "apocalypse"), RULES_CREEPY_APOCALYPSE_SKINS);

	private static String fusionKey(String host, String foreign) {
		return host + "×" + foreign;
	}

	/** 该有序组合(host 在前)是否已登记可融合(ADR-013);未登记 → init 400。 */
	public boolean isFusionSupported(String host, String foreign) {
		return FUSION_COMBOS.containsKey(fusionKey(host, foreign));
	}

	/**
	 * 融合两 archetype 的轴集(ADR-013 接活 ADR-012 休眠 {@link #mergeAxes};host 在前)。委托 combo 登记表取
	 * per-combo 换皮,未登记组合 → {@link IllegalArgumentException}(→ init 400)。
	 *
	 * @throws IllegalArgumentException host 或 foreign 未激活,或该有序组合未登记
	 */
	public List<AttributeAxis> fusedAxes(String host, String foreign) {
		Map<String, AxisSkin> skins = FUSION_COMBOS.get(fusionKey(host, foreign));
		if (skins == null) {
			throw new IllegalArgumentException("不支持的融合组合:" + host + " × " + foreign);
		}
		return mergeAxes(meta(host), meta(foreign), skins);
	}

	/**
	 * 融合两 archetype 的数值轴集(ADR-012 决策,混合模式 round 1;<b>纯函数、确定性、暂未接线</b>)。合并三规则:
	 * <ol>
	 *   <li>按 {@code key} 并集,<b>host 轴全保留、保序</b>;</li>
	 *   <li>foreign 轴按 key 追加,<b>撞 host key 则 host 赢</b>、foreign 同 key 轴并掉(host 的
	 *       displayName/bands/behaviorHint 赢);</li>
	 *   <li>存活的 foreign 轴可带 per-key 显示层 {@link AxisSkin} override(换皮 displayName + bands,
	 *       {@code key}/{@code axisRole}/{@code lethal} 不变,引擎无感)。</li>
	 * </ol>
	 * <b>只合并轴</b>——ruleForm / rulesCarryTruth / worldview 的融合属融合协议 ADR / 规则形态 ADR,本函数不处理。
	 *
	 * @param host    host archetype 元数据(撞键时其轴赢)
	 * @param foreign 外来 archetype 元数据(撞键时其同 key 轴并掉)
	 * @param skins   per-key 显示层换皮 override({@code null}/缺键=不换皮,原样并入)
	 * @return 融合后的轴集(host 轴在前保序,存活 foreign 轴按其原顺序追加)
	 */
	public static List<AttributeAxis> mergeAxes(ArchetypeMeta host, ArchetypeMeta foreign,
			Map<String, AxisSkin> skins) {
		Map<String, AttributeAxis> byKey = new LinkedHashMap<>();
		for (AttributeAxis a : host.attributes()) {
			byKey.put(a.key(), a); // host 全保留、保序
		}
		for (AttributeAxis a : foreign.attributes()) {
			if (byKey.containsKey(a.key())) {
				continue; // 撞键:host 赢、foreign 同 key 轴并掉
			}
			AxisSkin skin = skins == null ? null : skins.get(a.key());
			byKey.put(a.key(), skin == null ? a : applySkin(a, skin));
		}
		return List.copyOf(byKey.values());
	}

	/**
	 * 显示层换皮:换 displayName + bands,behaviorHint 仅当 skin 给了 override 才换(ADR-014,缺省沿用原 hint);
	 * 其余(key/min/max/axisRole/lethal)全保留 → 引擎无感。
	 */
	private static AttributeAxis applySkin(AttributeAxis axis, AxisSkin skin) {
		String hint = skin.behaviorHint() != null ? skin.behaviorHint() : axis.behaviorHint();
		return new AttributeAxis(axis.key(), skin.displayName(), axis.min(), axis.max(),
				hint, axis.axisRole(), axis.lethal(), skin.bands());
	}

	/**
	 * 修仙 × 规则怪谈(host=修仙)round 1 彩蛋的融合轴集(ADR-012):
	 * {气血(hp)、灵力(mana)、境界(realm)、道心(san)}——hp 撞键取修仙气血、san 换皮为道心。
	 * <b>已实现、已测、暂未接线</b>(让两 archetype 真正走进 init 是融合协议 ADR 的事)。
	 */
	public List<AttributeAxis> cultivationRulesCreepyAxes() {
		return fusedAxes("cultivation", "rules_creepy");
	}

	// ── 轴集 → 播种派生(单一真理源;GameInitService 与合并结果共用,守「复用现有派生、别新造」)───

	/**
	 * 累积型轴 key 集合(ADR-009 F-012):喂引擎据此 gate 触底(accumulation 轴 {@code ≤0} 不致死)。
	 * 据轴 {@link AttributeAxis#isAccumulation()} 算;全 depletion 的轴集返回空集(=现状)。保序。
	 */
	public static Set<String> accumulationKeys(List<AttributeAxis> axes) {
		Set<String> keys = new LinkedHashSet<>();
		for (AttributeAxis a : axes) {
			if (a.isAccumulation()) {
				keys.add(a.key());
			}
		}
		return keys;
	}

	/**
	 * 非致命 depletion 轴 key 集合(ADR-010 F-015):喂引擎据此判致命(这些轴 {@code ≤0} 不致死、不触发结局
	 * 极性 gate,如修仙灵力)。据 {@code depletion && lethal=false} 算;accumulation 轴本就不触底、不列入。保序。
	 */
	public static Set<String> nonLethalKeys(List<AttributeAxis> axes) {
		Set<String> keys = new LinkedHashSet<>();
		for (AttributeAxis a : axes) {
			if (!a.isAccumulation() && !a.isLethal()) {
				keys.add(a.key());
			}
		}
		return keys;
	}

	/** 轴 key→中文名(F-014 §5:引擎兜底结局按中文 condition 匹配,如 {@code hp→气血})。保序。 */
	public static Map<String, String> axisDisplayNames(List<AttributeAxis> axes) {
		Map<String, String> names = new LinkedHashMap<>();
		for (AttributeAxis a : axes) {
			names.put(a.key(), a.displayName());
		}
		return names;
	}

	/**
	 * 选择屏目录(ADR-008 决策 4 选择 UI 的数据源):已激活的(全字段、可选)在前,
	 * 已知但未激活的占位(active=false、tagline/vibeTag 留空,前端渲染「敬请期待」灰显)在后。
	 * 前端 {@code GET /api/archetypes} 据此渲染世界选择第一屏,不硬编码模式清单。
	 */
	public List<ArchetypeSummary> listForSelection() {
		List<ArchetypeSummary> out = new ArrayList<>();
		for (ArchetypeMeta m : active.values()) {
			out.add(new ArchetypeSummary(m.id(), m.displayName(), m.tagline(), m.vibeTag(), true));
		}
		for (Map.Entry<String, String> e : INACTIVE_DISPLAY_NAMES.entrySet()) {
			out.add(new ArchetypeSummary(e.getKey(), e.getValue(), null, null, false));
		}
		return List.copyOf(out);
	}

	// ── 元数据条目(内联;加模式在此加一条)─────────────────────────────

	/** 规则怪谈:hp/san=体力/理智,真假规则形态。补它让两模式走同一元数据驱动路径(不让规则怪谈成特例)。 */
	private static ArchetypeMeta rulesCreepy() {
		return new ArchetypeMeta(
				"rules_creepy",
				"规则怪谈",
				"一纸诡异守则,真假混杂。读懂它,或者付出代价。",
				"诡异 · 高危",
				"规则怪谈:玩家身处一个看似日常却暗藏异常的封闭场景(如雨夜便利店、末班地铁、山区民宿),"
						+ "墙上/纸上贴着一组必须遵守的规则,违反或误读会招致超自然后果。氛围瘆人、逻辑自洽。",
				List.of(
						AttributeAxis.stable("hp", "体力").withBands(
								band(100, "充沛", "行动自如、气力充盈"),
								band(50, "受创", "带伤行动,动作迟滞、隐隐作痛,体力不支"),
								band(20, "濒危", "重伤濒死,视野模糊、每个动作都伴着剧痛,随时可能倒下")),
						AttributeAxis.stable("san", "理智").withBands(
								band(100, "清明", "神志清明、判断冷静"),
								band(50, "动摇", "精神紧绷、手指发抖,理智开始动摇,疑神疑鬼"),
								band(20, "崩溃边缘", "幻觉与低语缠绕、分不清虚实,理智即将断裂"))),
				"真假混合的规则(isTrue 有真有假,至少各一条):content 是贴给玩家看的规则原文(口吻像告示),"
						+ "hiddenLogic 是只有引擎能看的真实机制(触发条件 + hp/san 后果)。玩家通过试探/观察逐步看清真伪,"
						+ "discovered 标记已识破的规则。",
				true); // 真假守则型(rules 带 isTrue 有真有假)
	}

	/** 末日生存(本批首个新模式):hp/hunger=体力/饥饿,饥饿随回合自然衰减(AI 落,引擎无知,决策 2)。 */
	private static ArchetypeMeta apocalypse() {
		return new ArchetypeMeta(
				"apocalypse",
				"末日生存",
				"废土求生,饥饿是另一个敌人。撑过下一个夜晚。",
				"荒凉 · 绝境",
				"末日生存:文明崩塌后的废墟世界(如丧尸蔓延的城市、核冬天的避难所、资源枯竭的末世聚落),"
						+ "玩家在饥饿、伤病与未知威胁之间求生。氛围荒凉、紧绷、危机四伏,资源永远不够。",
				List.of(
						AttributeAxis.stable("hp", "体力").withBands(
								band(100, "充沛", "行动自如、气力充盈"),
								band(50, "受创", "带伤行动,动作迟滞、隐隐作痛,体力不支"),
								band(20, "濒危", "重伤濒死,视野模糊、每个动作都伴着剧痛,随时可能倒下")),
						AttributeAxis.decaying("hunger", "饥饿",
								"饥饿值随回合自然衰减,每回合约下降 5~10(找到并食用补给才回升);"
										+ "由你在 stateUpdate 给出衰减后的新绝对值,务必每回合都体现这一自然消耗。")
								.withBands(
										band(100, "饱足", "进食充足、体力有支撑"),
										band(50, "饥肠辘辘", "饥饿啃噬、手脚发软,注意力难以集中"),
										band(20, "濒临饿毙", "眼前发黑,身体开始消耗自身,濒临饿死"))),
				"生存法则与资源约束(非规则怪谈的真假规则机制,但仍可有「被发现才知道的硬规矩」,复用 discovered 机制):"
						+ "如某些区域的危险规律、物资使用的代价、势力/感染体的行为底线。content 是玩家可摸索到的生存经验,"
						+ "hiddenLogic 是只有引擎能看的真实判定(触发条件 + hp/hunger 后果)。",
				true); // 真假守则型(rules 带 isTrue 有真有假)
	}

	/**
	 * 克苏鲁(加世界流水线第一次复用,backlog 第一级):hp/san=体力/理智(复用)+ knowledge=禁忌知识(克苏鲁特有)。
	 * 核心张力 = 禁忌知识的代价——knowledge 累积型双刃:求知则上涨(力量),但越高 san 流失越快(代价)。
	 * 这个 knowledge↔san 联动<b>由 AI 落、引擎无知</b>(behaviorHint 喂提示词,守 ADR-008 决策 1/2)。
	 */
	private static ArchetypeMeta cthulhu() {
		return new ArchetypeMeta(
				"cthulhu",
				"克苏鲁",
				"凝视深渊,深渊回以低语。知道得越多,离疯狂越近。",
				"深渊 · 疯狂",
				"克苏鲁神话式的不可名状之恐怖:沉睡于宇宙与海渊的旧日支配者、写满禁忌真相的古老典籍、"
						+ "阴郁的海边小镇 / 偏僻古宅 / 积尘的大学禁阅区。人类一旦窥见宇宙的真实图景,理智便开始崩解。"
						+ "氛围阴郁、压抑、缓慢逼近,恐惧来自「不该知道的事」而非血腥。",
				List.of(
						AttributeAxis.stable("hp", "体力").withBands(
								band(100, "充沛", "行动自如、气力充盈"),
								band(50, "受创", "带伤行动,动作迟滞、隐隐作痛,体力不支"),
								band(20, "濒危", "重伤濒死,视野模糊、每个动作都伴着剧痛,随时可能倒下")),
						AttributeAxis.stable("san", "理智").withBands(
								band(100, "清明", "神志清明、判断冷静"),
								band(50, "动摇", "精神紧绷、手指发抖,理智开始动摇,疑神疑鬼"),
								band(20, "崩溃边缘", "幻觉与低语缠绕、分不清虚实,理智即将断裂")),
						AttributeAxis.accumulating("knowledge", "禁忌知识",
								"累积型双刃:玩家主动钻研典籍 / 窥探禁忌 / 接触旧日之物时上涨(求知与探索使之增长),"
										+ "平时只涨或持平、不无故回落;knowledge 高则解锁更强的洞察 / 看穿真相(力量)。"
										+ "【关键联动】knowledge 越高,本回合 san 流失就应越快、越凶——知道得越多越接近真相、"
										+ "也越接近疯狂,这是禁忌知识的代价;务必在 stateUpdate 让 san 随 knowledge 的高低体现这一加速流失。"
									+ "【取值约定】初值给一个较低的正基线(如 5–15,表示「隐隐不安但尚未真正窥探」),绝不给 0;"
									+ "此后也绝不降到 0——knowledge 是累积轴,0 只是「全然无知」的起点意味、不是结局。危险来自 knowledge "
									+ "过高 →（联动）san 崩;失败由 san/hp 触底承载,不由 knowledge 触底。")
							.withBands(
									band(0, "蒙昧", "尚见世界的寻常表象,异样只是模糊不安"),
									band(31, "初窥", "窥见真相的裂隙,异样开始显形,理智隐隐承压"),
									band(61, "深陷", "深陷不可名状的真知,真相侵蚀感知、知与疯狂同涨"))),
				"禁忌知识在探索中渐揭(非规则怪谈的一纸真假守则):玩家通过行动逐步发现「有些事不该知道、"
						+ "有些东西不该看」。content 是玩家可摸索到的线索 / 禁忌知识碎片(读起来是代价与警示,不是攻略),"
						+ "hiddenLogic 是只有引擎能看的真实判定(触发条件 + hp/san/knowledge 后果);discovered 标记已揭示的"
						+ "禁忌知识(揭示一条 → 点亮,可能涨 knowledge / 解锁洞察,但随之加速 san 流失)。",
				true); // 真假守则型(rules 带 isTrue 有真有假)
	}

	/**
	 * 修仙(世界库第二级,backlog 真正压力测试):hp=气血(depletion 复用)+ 灵力(depletion 资源池)
	 * + 境界(accumulation 主角轴)。境界是<b>第二个累积轴样本</b>(克苏鲁 knowledge 第一个)——ADR-009
	 * F-012 引擎正解的落地见证:境界纯成长、不参与死亡判定(死于气血触底/渡劫,非境界),引擎据 axisRole
	 * 不因境界 ≤0 误触底。规则形态=心法/修行法则(<b>非真假守则</b>),rules 不带 isTrue(ADR-009 F-013)。
	 * 灵根做 character.traits 文字属性(天灵根/废灵根…),影响叙事但不单开数值轴(最小可玩,做厚挂 backlog)。
	 */
	private static ArchetypeMeta cultivation() {
		return new ArchetypeMeta(
				"cultivation",
				"修仙",
				"逆天改命,踏上仙途。一念成圣,一念成魔。",
				"缥缈 · 仙途",
				"东方仙侠修真世界:天地灵气氤氲,宗门林立、洞天福地隐于山海;凡人以灵根资质入道,炼气、筑基、"
						+ "结金丹,逆天夺命、追求长生与飞升。修行路上有心魔横生、渡劫天劫、同道相争与天材地宝的诱惑。"
						+ "角色入场即带一种灵根资质(如天灵根 / 双灵根 / 废灵根,写进 character.traits),它影响修行快慢与叙事际遇。"
						+ "氛围缥缈悠远、大道无情,机缘与凶险并存。",
				List.of(
						AttributeAxis.stable("hp", "气血").withBands(
								band(100, "气血充盈", "真元周流,出手有力"),
								band(50, "气血亏损", "经脉滞涩、面色发白,运功略显吃力"),
								band(20, "气血枯竭", "五脏俱损、口溢鲜血,神魂动摇、命悬一线")),
						// 灵力 = 非致命资源池(ADR-010 决策 2,关闭 F-015):depletion 但 lethal=false,
						// ≤0=力竭(惩罚/施不出法术)、非必死,引擎不因它触底致死、也不据它 gate 结局。
						AttributeAxis.resource("mana", "灵力",
								"灵力是施展术法 / 神通 / 御器 / 强行突破的资源池:施为时消耗下降,打坐吐纳 / 服食丹药 / "
										+ "汲取灵气时回升;由你在 stateUpdate 给消耗或恢复后的新绝对值,体现「法力有限、不可无限施为」。"
										+ "灵力枯竭只是力竭、施不出法术,并不直接致死(致死看气血)。")
								.withBands(
										band(100, "灵力充裕", "法术信手拈来"),
										band(50, "灵力见底", "施法滞涩、御器吃力,需省着用"),
										band(20, "灵力枯竭", "施不出像样的法术、只能凭肉身硬撑(力竭而非伤身)")),
						AttributeAxis.accumulating("realm", "境界",
								"境界是修为成长的主轴(累积型):勤修苦练 / 顿悟 / 历练 / 突破瓶颈时上涨(只涨或持平、"
										+ "不无故回落);境界越高,可施展的手段越强、越能镇压低境界凶险。"
										+ "【纯成长·不致死】境界是成长轴、不参与死亡判定——生死由气血(hp)触底 / 渡劫失败承载,"
										+ "境界低或初入修行(数值低)绝不意味失败,是循序渐进的起点。初值给低位(如 10–25,炼气初期),逐步累积。")
								.withBands(
										band(0, "初境", "修为尚浅(炼气期),只能调动微末灵力,凶险当前多靠机变求生"),
										band(31, "小成", "修为小成(筑基前后),法术渐成、可镇压寻常凶险"),
										band(61, "高深", "修为高深(金丹气象),言出法随、气势迫人,低境者难撄其锋"))),
				"修行法则 / 心法 / 修真禁忌(非真假守则,不要输出 isTrue):如「心魔不可纵,纵则走火入魔」「渡劫忌分心」"
						+ "「灵力枯竭强行运功者经脉俱断」。content 是玩家可领悟到的修行准则与禁忌(读起来是大道法则与代价,"
						+ "不是攻略);hiddenLogic 是只有引擎能看的真实判定(触发条件 + hp/灵力/境界 后果);discovered 标记"
						+ "已顿悟 / 印证的法则(顿悟一条 → 点亮,可能助益突破或避开凶险)。",
				false); // 心法守则型(rules 不带 isTrue,ADR-009 F-013)
	}
}
