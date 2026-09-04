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
 * <p><b>已知 vs 已激活</b>(设计稿 §5,init 入参校验用)——<b>此处刻意不列 id 清单,一律指向登记处</b>:
 * <ul>
 *   <li><b>已知</b> = {@link #KNOWN} 常量里的那些;非已知 → init 400(非法 archetype)。</li>
 *   <li><b>已激活</b> = 构造器里 {@code register(...)} 的那些,<b>以登记处为准</b>;
 *       已知但未激活 = {@link #INACTIVE_DISPLAY_NAMES} 里的那些 → init 400「未开放」
 *       (占位枚举,等各自独立批 + 独立 world-gen 冒烟)。</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么这里不写 id 清单</b>:原文写过两份逐一列举的 id 清单,而<b>两份都已经漂移过</b>
 * (「已知……5 个 id」实为 7 条;「已激活……」列 5 个而构造器 {@code register} 了 6 条)。
 * <b>补上漏掉的那个只是把同一个陷阱重置</b> —— 下一个世界上线时它会再错一遍,
 * <b>而且没有任何断言在看它</b>(数量类测试钉的是 registry 本身,不钉注释)。
 * 故改为指向登记处:<b>让「忘记同步」无法被写出来,而不是同步一次</b>
 * (同 ADR-022「归还在结构上不可遗忘」之形)。
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
			"rules_creepy", "life_sim", "cultivation", "cyberpunk", "apocalypse", "cthulhu", "animal_life");

	/**
	 * 已知但未激活(占位枚举)的玩家可见中文名(CONTEXT §三.4)——选择屏渲染「敬请期待」灰显卡片用。
	 * 让 registry 成为「选择目录」的单一真理源(前端不硬编码模式清单)。保序 = 选择屏占位排序。
	 */
	private static final Map<String, String> INACTIVE_DISPLAY_NAMES;
	static {
		Map<String, String> m = new LinkedHashMap<>();
		// life_sim 已于 ADR-020 刀 1 激活(《寻常》复用既有占位 id,对外名仍「人生模拟」)→ 本表删去那一行。
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
		register(ordinaryLife());
		register(animalLife());
		assertLifetimeWorldsHaveExactlyOneLethalAxis();
	}

	/**
	 * <b>一生制世界必须恰好有一条致命轴,否则构造期抛</b>(ADR-021 刀 2 · 裁定二)。
	 *
	 * <p><b>为什么要有这条</b>:一生制的「收束阶段下限钳制」(ADR-020 §8)是<b>对着那一条命轴</b>说的;
	 * 若某个一生制世界有两条致命轴,「下限」指哪条<b>没有答案</b>,而代码会挑一条继续跑 ——
	 * <b>那又是一个静默失效</b>。
	 *
	 * <p><b>⚠️ 而本刀存在的一半理由就是修 {@code VIGOR_KEY} 那个静默失效
	 * (动物的致命轴不叫 vigor,钳制对它直接 return 且不报错)——
	 * 若修完换来另一个静默失效,等于白修。</b> 故把它做成加载即响。
	 *
	 * <p><b>约束是一生制族的,不是全局的</b>:{@code rules_creepy} 两条致命轴(hp/san)照常,
	 * 它不属这个族、也没有收束下限这回事。
	 */
	private void assertLifetimeWorldsHaveExactlyOneLethalAxis() {
		for (ArchetypeMeta meta : active.values()) {
			if (!LifetimeFamily.isLifetime(meta.id())) {
				continue;
			}
			Set<String> lethal = lethalKeys(meta.attributes());
			if (lethal.size() != 1) {
				throw new IllegalStateException(
						"一生制世界必须恰好一条致命轴(收束下限钳制才有唯一对象):" + meta.id()
								+ " 实际 " + lethal.size() + " 条 " + lethal);
			}
		}
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

	/**
	 * 由有序 archetype 列表解析数值轴集(播种 / 启动回载 / 续局三处共用的单一真理源,ADR-015):
	 * 长度 1 → 单体轴集;长度 2 → 融合轴集(host 在前,委托 {@link #fusedAxes});其余非法。
	 *
	 * @throws IllegalArgumentException 空表 / 长度 &gt;2 / archetype 未激活 / 融合组合未登记
	 */
	public List<AttributeAxis> resolveAxes(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new IllegalArgumentException("未指定 archetype");
		}
		if (ids.size() == 1) {
			return meta(ids.get(0)).attributes();
		}
		if (ids.size() == 2) {
			return fusedAxes(ids.get(0), ids.get(1));
		}
		throw new IllegalArgumentException("不支持的 archetype 组合长度:" + ids);
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
					// E'':衰减挂钩行为(治「夜间无补给窗口 × 按回合无条件衰减」的结构性必死)。
					"补给消耗与行为挂钩:奔逃 / 劳作 / 受冻的回合消耗快(约 -5~10),静卧 / 入睡 / 躲藏休整的"
							+ "回合消耗轻微(约 -1~3)——夜间躲藏休整不应全额扣减,让「熬到天亮」是可存活的策略而非"
							+ "倒计时;配给日领取 / 外出搜刮 / 以物易物才回升。由你在 stateUpdate 给出消耗或恢复后的新绝对值。"));

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
	 * 其余(key/min/max/axisRole/lethal/<b>perilAtHigh</b>)全保留 → 引擎无感。
	 *
	 * <p><b>perilAtHigh 在「换皮不换」清单内(ADR-018)</b>:换皮只改玩家看到的名字与档文案,不改这根轴的危险
	 * 方向。故融合局的 severity <b>自动等同于对应 host 侧单体</b>——<b>per-combo 对 severity 零登记</b>,
	 * 新增融合组合不为此做任何事(守 F-016 复用成本模型)。
	 */
	private static AttributeAxis applySkin(AttributeAxis axis, AxisSkin skin) {
		String hint = skin.behaviorHint() != null ? skin.behaviorHint() : axis.behaviorHint();
		return new AttributeAxis(axis.key(), skin.displayName(), axis.min(), axis.max(),
				hint, axis.axisRole(), axis.lethal(), axis.perilAtHigh(), skin.bands());
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

	/**
	 * <b>致命</b> depletion 轴 key 集合(ADR-021 刀 2):{@code depletion && lethal} ——
	 * 与 {@link #nonLethalKeys} 互补、与 {@link #accumulationKeys} 不交。保序。
	 *
	 * <p>补这一个是因为该判据此前<b>在 {@code WorldGenPromptBuilder} 里手写了两遍</b>
	 * ({@code lethalAxisNames} / {@code endingCountRange}),而刀 2 又需要第三处
	 * (一生制世界的收束下限钳制要知道「气力」是哪条轴,不能再硬编码 {@code "vigor"})。
	 */
	public static Set<String> lethalKeys(List<AttributeAxis> axes) {
		Set<String> keys = new LinkedHashSet<>();
		for (AttributeAxis a : axes) {
			if (!a.isAccumulation() && a.isLethal()) {
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

	/**
	 * 已登记融合组合目录(ADR-019 只读投影):选择屏据它判定「拖 A 到 B 上」是否合法,
	 * 不再在前端硬编码一份组合表(双真相源会漂移,后果具体——玩家拖出一个后端 400 的组合)。
	 *
	 * <p><b>顺序确定</b>:{@code FUSION_COMBOS} 是 {@code Map.of(...)}(<b>无序</b>,迭代顺序不保证也不稳定),
	 * 故这里按 key 排序后再下发——消费方与测试都不该依赖一个不保证的顺序。
	 * 与 {@link #listForSelection()} 同源、同一次请求下发(天然不会两个响应不同步)。
	 */
	public List<FusionSummary> listFusionCombos() {
		List<FusionSummary> out = new ArrayList<>();
		for (String key : new java.util.TreeSet<>(FUSION_COMBOS.keySet())) {
			int sep = key.indexOf('×');
			out.add(new FusionSummary(key.substring(0, sep), key.substring(sep + 1), key));
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
						// 累积型【双刃】(ADR-018 perilAtHigh):引擎侧与 accumulating 逐字相同(≤0 不致死),
						// 只多一位纯展示层标——高位=越接近疯狂,故最高档染 danger(对照修仙境界纯成长、全档 neutral)。
						AttributeAxis.doubleEdged("knowledge", "禁忌知识",
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

	/**
	 * 《动物人生》(ADR-021 刀 3,<b>一生制世界族第二个实例</b>)—— 一只被养过、后来流落在外的动物的一生。
	 *
	 * <p><b>它首先是族级抽象的验证载体,其次才是一个世界</b>(ADR-021 本 ADR 的存在理由):
	 * 四条轴<b>与《寻常》全部不同</b>、切分表三段断裂 vs 六段递进 —— 这正是立字二
	 * 「族级可复用的是原则、切分表 per-world 不得跨世界复用」的第一次真考。
	 *
	 * <p><b>核心机制 = 误读回收</b>(与《寻常》正好相反):《寻常》的回收是「你忘了的东西回来了」,
	 * 这里是<b>你早年学会的规律在后半生一条条失效,而系统绝不告诉你规律变了</b>。
	 * 不是慢慢学错,是<b>一开始就带着一整套错的东西上路</b>。
	 *
	 * <p><b>四轴</b>:
	 * <ul>
	 *   <li><b>身子 body</b> = {@code depleting}(depletion + lethal):<b>唯一致命轴</b>
	 *       (一生制恰好一条致命轴,构造期断言);<b>损耗不可逆</b>只写进 behaviorHint ——
	 *       ADR-021 立字四已裁定<b>不开引擎级上限机制</b>(引擎里不存在「上限」这个概念),
	 *       故它是<b>措辞约束、模型不遵守时没有东西拦得住</b>,已挂账。</li>
	 *   <li><b>暖 warmth</b> = {@code resource}(depletion + {@code lethal=false}):归零不死 ——
	 *       冻着是难受不是致命,但会拖慢一切。</li>
	 *   <li><b>地面 ground</b> = {@code accumulating}:⚠️ <b>该族第一条「会减少」的累积轴</b>
	 *       (既有三条 accumulation 的 hint 都写着「只涨或持平」,<b>一个字都不能照抄</b>)。
	 *       不标 {@code perilAtHigh} → severity 全 NEUTRAL:<b>地面归零确实不致死,
	 *       而染 danger 会让玩家把它当血条</b>,但它的语义是「你认得的地方还剩多少」,
	 *       <b>归零是荒凉不是濒死</b>;重量改由 bands 的低档措辞承担(读起来必须像<b>失去</b>)。</li>
	 *   <li><b>近人 close</b> = {@code doubleEdged}(自带 {@code perilAtHigh}):
	 *       <b>这个世界的心脏</b> —— 高位是危险档,措辞要读出<b>暴露</b>而非「亲人」。</li>
	 * </ul>
	 *
	 * <p><b>⚠️ 与《寻常》「牵挂」的对比必须记明</b>:同为关系轴,<b>极性相反</b> ——
	 * 牵挂高位无害(不标 perilAtHigh、全档 neutral),近人高位致命(doubleEdged)。
	 * 承载的正是这个世界的核心悲剧:<b>你在屋里学会的最重要的那件事,在外面是你最大的弱点。</b>
	 *
	 * <p><b>{@code rulesCarryTruth = false},但理由与《寻常》和修仙都不同(三者不得混淆)</b>:
	 * 修仙是「<b>有</b>法则,只是不成文」;《寻常》是「<b>根本没有</b>守则,rules 是事情发生过留下的痕迹」;
	 * 本世界是<b>「有规律,但规律是错的」</b> —— 规律在屋里全都为真、到了外面一条条失效。
	 * 那是<b>时间维度</b>上的真假,不是真假守则那种<b>同时并存</b>的真假,故仍不带 {@code isTrue}。
	 */
	private static ArchetypeMeta animalLife() {
		return new ArchetypeMeta(
				"animal_life",
				"动物人生",
				"你学会的规则,在外面一条条失效。",
				"视角错位 · 误读回收",
				"一只被养过、后来流落在外的动物的一生。屋里的世界小而确定,规律可以学会:"
						+ "金属声响过门就开、靠近人就有吃的、叫一声就有人来、那个位置总是安全的。"
						+ "后来它被留下了,没有人解释为什么,直到最后也不会有。"
						+ "外面地图重画,早年学会的每一条在这里一条条失效——不是慢慢学错,"
						+ "是一开始就带着一整套错的东西上路。"
						+ "这一生与「不断获得解释」相反:事件越攒越多,解释一个都没有。",
				List.of(
						// 唯一致命轴。不可逆是【措辞约束】,引擎无从保证(立字四已记账)。
						AttributeAxis.depleting("body", "身子",
								"身子是这只动物的命轴:打斗、挨饿、过冬、生病时下降,吃饱睡暖能缓一些。"
										+ "⚠️【损耗不可逆】旧伤、断牙、一个冬天没长回来的膘——"
										+ "回升幅度必须【远小于】降幅,且【绝不回到历史最高】;"
										+ "过去不以记忆的形式存在,只以损耗的形式存在。"
										+ "由你在 stateUpdate 给出新绝对值;归零即死。")
								.withBands(
										band(100, "壮", "跳得上去、跑得动、咬得住,毛是顺的"),
										band(50, "有旧伤", "有一处一直没好利索,天冷会显出来;跳之前先看一眼高度"),
										band(20, "撑不住", "走几步要停,趴下就不太想起来,呼吸声自己听得见")),
						// 归零不死:冻着是难受不是致命。
						AttributeAxis.resource("warmth", "暖",
								"暖是体温与避风:夜里、雨雪、风口、湿透时下降;晒到太阳、找到背风处、"
										+ "挨着别的活物时回升。由你在 stateUpdate 给出新绝对值。"
										+ "【归零不死】冻着是难受不是致命,但它会拖慢一切,也会让身子掉得更快。")
								.withBands(
										band(100, "暖和", "找得到背风的地方,睡得沉"),
										band(50, "冷", "毛立起来也不管用,趴下之前要转好几圈"),
										band(20, "冻着", "抖得停不下来,爪子踩上去没有知觉")),
						// ⚠️ 该族第一条【会减少】的累积轴 —— 既有三条的「只涨或持平」一个字都不能照抄。
						AttributeAxis.accumulating("ground", "地面",
								"地面是「你认得的地方还剩多少」:走熟一条路、找到新的落脚处、"
										+ "摸清一个角落时上涨。⚠️【与其它累积轴不同,它会减少】——"
										+ "拆了、封了、被别的东西占了,认得的地方就少一处,而且不会再回来。"
										+ "人看到的是「城市改造」,动物感受到的是「昨天还能穿过去的地方,今天没有了」。"
										+ "由你在 stateUpdate 给出升降后的新绝对值。"
										+ "【归零不死】地面归零不致死,只是无处可去。")
								.withBands(
										// ⚠️ 低档必须读起来像【失去】,不是【还没得到】。
										band(0, "没有地方", "认得的都不在了——拆了、封了、住进了别的东西;走到哪儿都是新的味道"),
										band(31, "只剩几处", "还能去的只剩那么几个,去之前要先在远处看一会儿"),
										band(61, "走得开", "哪条路通哪儿都清楚,穿过去不用想")),
						// 这个世界的心脏。doubleEdged 自带 perilAtHigh → 最高档 danger、次高 caution。
						AttributeAxis.doubleEdged("close", "近人",
								"近人是「敢不敢靠近人」:被喂过、被摸过、靠近之后没出事时上涨;"
										+ "被踢、被砸、被驱赶、被追之后下降。由你在 stateUpdate 给出新绝对值。"
										+ "⚠️【高位有害】在屋里敢靠近人是好事,在外面它让你一直站在人够得着的地方。"
										+ "【归零不死】低位不是失败,只是学会了躲——活得久,但那扇门再也不会为你开。")
								.withBands(
										// ⚠️ 高档措辞要读出【暴露】,不是「亲人」。
										band(0, "躲着人", "听见脚步就先让开;活得久,但那扇门再也不会为你开"),
										band(31, "敢靠近", "有人蹲下来,你会走过去"),
										band(61, "太近了", "腿边、手边、脚步声那一侧——你站的位置永远在人够得着的地方"))),
				"【动物没有守则。它有的是学会的规律,而规律会失效。】"
						+ "rules 6-8 条写成【这只动物在屋里学会的规律】,每一条都用它感知得到的东西写"
						+ "(金属声、鞋、腿边、那个位置),绝不写成人类口吻的条款、也不编号成守则。"
						+ "⚠️ 每一条在屋里【都是对的】——它们是被正确学会的;"
						+ "到了外面,它们会一条条失效,而【系统绝不提示规律变了,只让它撞上】。"
						+ "hiddenLogic 写清两件事:这条规律在屋里如何兑现、它在外面以什么方式失效(代价落在哪条轴上)。"
						+ "⚠️ 不带 isTrue——这不是真假混合的守则墙,是【时间维度】上的真假:"
						+ "先全为真,后一条条变假。",
				false);
	}

	/**
	 * 人生模拟《寻常》(ADR-020,<b>一生制世界族</b>第一个实例;非恐怖线首个世界):一局从出生玩到死亡,
	 * 回合密度随生命呼吸(幼年压缩 / 选择密集期一年一回合 / 中段加速 / 末段重新变密)。
	 * key <b>复用既有占位 {@code life_sim}</b>(裁定三:对外名仍「人生模拟」,世界名「寻常」进 {@code world.title}),
	 * 故 KNOWN / {@code schema.ts} / CONTEXT §三.4 全部无需改动。
	 *
	 * <p><b>四轴</b>——本世界的关键结构在于「归零不死」两条轴(ADR-020 §3):
	 * <ul>
	 *   <li><b>气力 vigor</b> = {@code stable}(depletion + lethal):唯一致命轴,{@code ≤0} 触底 = 一生走到尽头。
	 *       早逝三段式(§8)属提示词层,不在元数据。</li>
	 *   <li><b>热望 longing</b> / <b>路口 crossroads</b> = {@code resource}(depletion + {@code lethal=false}):
	 *       <b>引擎硬保证</b> {@code ≤0} 既不触底致死、也不触发结局极性 gate(ADR-010 决策 2 的既有机制,
	 *       修仙灵力真机验过)。归零后局照常继续,只是选项退化(§4 的可数判据属提示词层,归刀 3)。</li>
	 *   <li><b>牵挂 ties</b> = {@code accumulating}:纯累积、{@code ≤0} 不致死,且<b>不标 perilAtHigh</b>——
	 *       牵挂深不是危险(对照克苏鲁禁忌知识双刃),故 severity 全 NEUTRAL。</li>
	 * </ul>
	 *
	 * <p><b>热望与路口的语义差(ADR-020 §5 逐字锁,不得写成同义句)</b>:
	 * 热望决定<b>「你还想不想选择」</b>(内,意愿);路口决定<b>「人生还给不给你大的选择」</b>(外,机会)。
	 * 路口归零后仍是四个真实动作,只是没有一个会改变什么。
	 *
	 * <p><b>刀 1 范围</b>:worldview / ruleForm 只给本世界的底子;§2 的<b>回合密度切分表</b>与结局池归<b>刀 2</b>,
	 * 一生制的每回合指令(时间尺度 / 退化判据 / 留白禁令 / 承诺作用域)归<b>刀 3</b> 的 per-archetype 指令槽。
	 */
	private static ArchetypeMeta ordinaryLife() {
		return new ArchetypeMeta(
				"life_sim",
				"人生模拟",
				"一生只有一次,而它大部分时候都很寻常。",
				"平常 · 一生",
				"《寻常》——一个普通人的一辈子:不逆袭、不觉醒、没有主线,没有异常与敌人;"
						+ "推着人走的是时间、家人、钱和身体。这个世界的引擎是【回收】:早期那些不起眼的东西"
						+ "(一句随口的话、一个擦肩的人、一件旧物),几十年后会变形回来,以另一副样子出现在他面前。"
						+ "【铁律】回收永远不加分、不标记、不解释——系统一旦指出「这是当年那个」,它就死了;"
						+ "只把它写出来,让玩家自己认出,认不出也不补说明。一局覆盖从出生到死亡、40-55 回合,"
						+ "回合密度不匀速。氛围克制具体、不煽情——重量来自日常细节本身。",
				List.of(
						// 唯一致命轴:一生的身体账。早逝由提示词按三段式落(种子倾向→累积调制→单次不决定),
						// 引擎只管 ≤0 触底,不懂「衰老」。
						// ADR-020 刀 7 · B①(F-021 故障 ③ 补漏):由 stable 换 depleting,**只多一个 hint**
						// (两个工厂逐字只差 hint:axisRole=DEPLETION / lethal=true 不变 → severity 派生与
						// bands 一个字节不动)。换它的理由是勘察发现的一处缺口:气力原是四轴里**唯一没有
						// behaviorHint** 的,于是从不出现在 behaviorReminder() 那个「每回合提醒」块里 ——
						// 整份 prompt 说过它该怎么变的只有槽内第 (6) 条那一句,还埋在九条的第六条、
						// 且只在进入末段时才相关。**故 §8 守不住不是「自律失败」,是根本没有指令。**
						AttributeAxis.depleting("vigor", "气力",
								"气力是一生的身体账:随年龄段【自然消耗】,越老降得越快;"
										+ "劳累、疾病、意外会额外扣,休养与安稳的日子能缓一缓(缓不回年轻时的值)。"
										+ "【任何单次选择都不具决定性】——不因某一个选择骤降(ADR-020 §8 早逝三段式:"
										+ "种子设定倾向 → 累积选择调制 → 单次不决定);由你在 stateUpdate 给出新绝对值。")
								.withBands(
								band(100, "康健", "身体不提醒你它的存在,做什么都还使得上劲"),
								band(50, "耗损", "起身慢半拍、久坐腰酸,熬过的夜都要还回来"),
								band(20, "衰弱", "走一段就得歇,病躲不开了,身体开始替你做决定")),
						// 归零不死(一):内在的「想不想」。引擎不因它 ≤0 判死、也不据它 gate 结局。
						// F-023 修法:跌挂到时钟上、涨用「只有…才可能」限定死、行为侧只留单回合可判的那条。
						// 原措辞把跌挂在「反复的妥协 / 把自己往后排的日子」上 —— 那需要模型看得见历史,
						// 而 compressLog(LOG_KEEP=4)之外只剩 [T85选C],**它在结构上无法评估**(同源 F-020 §0.1);
						// 涨则三条全是日常可及的单次事件、且无限定词 → 三次真机冒烟热望一次都没降到零。
						// 对照组 crossroads 同为 resource、同引擎却降得下来,差异全在措辞,故照它的形状重写。
						AttributeAxis.resource("longing", "热望",
								"热望是「你还想不想为自己选一次」的内在意愿:它随年岁自然消磨——越往后,"
										+ "他认为「明天会有点不一样」的程度越低,每进入一个新的人生阶段都该比上一段更淡些;"
										+ "此外,本回合他若选的是四个选项里最安全、最不会改变什么的那一项,再往下压一点。"
										+ "只有久别重逢、或一件搁置多年的事忽然做成了这类事,才可能把它点回来一些。"
										+ "由你在 stateUpdate 给出升降后的新绝对值。"
										+ "【归零不死】热望归零不致死,也不是失败结局——它只意味着人还在过日子,但已经不再为自己伸手。")
								.withBands(
										band(100, "炽热", "还有真正想做的事,想起来就坐不住"),
										band(50, "转淡", "说服自己「算了」的次数,比说服自己「去吧」多"),
										band(20, "熄了", "什么都行、都可以——不是不快乐,是不再想要")),
						// 归零不死(二):外在的「给不给」。与热望刻意不可互换(§5)。
						AttributeAxis.resource("crossroads", "路口",
								"路口是「人生还给不给你大的选择」的外部余量:随年岁、已选定的路、落定的责任与既成事实而收窄;"
										+ "只有变故、迁徙、失去或从头再来这类事,才可能重新打开一个岔口。由你在 stateUpdate 给出新绝对值。"
										+ "【归零不死】路口归零不致死——此后仍给玩家四个真实动作,只是没有一个会改变什么;"
										+ "差别在他是个什么样的人,不在于会发生什么。")
								.withBands(
										band(100, "岔口尚多", "前面还有好几条路,选哪条都还来得及"),
										band(50, "路已收窄", "有些门关上的时候,你并没有听见声音"),
										band(20, "只剩一条", "日子照常过,只是没有哪个选择还能改变什么")),
						// 纯累积、非双刃:牵挂深不是危险(对照 knowledge),故不标 perilAtHigh → 全档 neutral。
						AttributeAxis.accumulating("ties", "牵挂",
								"牵挂是累积型的:与人长久相处、照顾与被照顾、共同经历的日子使之上涨(只涨或持平,"
										+ "不因一次疏远回落);牵挂越深,选择就越不只是自己的事,离别的代价也越重。"
										+ "【纯累积·不致死】牵挂低不是失败,只是「谁也不必等你」;初值给低位(如 5–20,尚未与谁真正绑在一起)。")
								.withBands(
										band(0, "轻身", "谁也不必等你,你也不必等谁"),
										band(31, "有所系", "有几个名字会让你多看一眼手机"),
										band(61, "深系", "你的很多事,已经不只是你自己的事"))),
				"【本世界没有「守则」这个东西】——注意这不是修仙那种「有心法但不成文」,而是这里根本不存在"
						+ "一份可读、可违反、可识破的规则:没有人贴出过什么,也没有什么在背后判你对错。"
						+ "取而代之,rules 的 6-8 条写成【事情发生过以后留下的痕迹】:某件事之后有些门就关上了,"
						+ "某句话说出口之后有的人就不再来了。content 是他过日子时慢慢明白过来的一件事"
						+ "(读起来像回忆或经验,不像条款、更不像攻略,别写成「必须/禁止/否则」);"
						+ "hiddenLogic 是只有引擎能看的真实判定(触发条件 + 气力/热望/路口/牵挂 的后果);"
						+ "discovered 标记他已经懂了的那几件——懂了,通常是因为已经付过代价。",
				false); // 没有守则(rules=痕迹而非条款,故不带 isTrue;与修仙「有心法但非硬规则」不同,见 ADR-020 补记
	}
}
