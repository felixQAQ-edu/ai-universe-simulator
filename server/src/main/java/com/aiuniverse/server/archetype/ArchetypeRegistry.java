package com.aiuniverse.server.archetype;

import java.util.LinkedHashMap;
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
 *   <li><b>已激活</b> = 本批有元数据可生成的(rules_creepy + apocalypse);已知但未激活(life_sim 等)→
 *       init 400「未开放」(占位枚举,等各自独立批 + 独立 world-gen 冒烟)。</li>
 * </ul>
 *
 * <p>本类<b>纯数据</b>(无 IO、无 LLM),元数据内联(便于单测钉结构、零 FS 依赖)。
 */
@Component
public class ArchetypeRegistry {

	/** CONTEXT §三.4 枚举:全部「已知」archetype id(用于 init 非法值判定)。 */
	private static final Set<String> KNOWN = Set.of(
			"rules_creepy", "life_sim", "cultivation", "cyberpunk", "apocalypse");

	/** 已激活(有元数据)的 archetype → 元数据。保序便于稳定遍历。 */
	private final Map<String, ArchetypeMeta> active = new LinkedHashMap<>();

	public ArchetypeRegistry() {
		register(rulesCreepy());
		register(apocalypse());
	}

	private void register(ArchetypeMeta meta) {
		active.put(meta.id(), meta);
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

	// ── 元数据条目(内联;加模式在此加一条)─────────────────────────────

	/** 规则怪谈:hp/san=体力/理智,真假规则形态。补它让两模式走同一元数据驱动路径(不让规则怪谈成特例)。 */
	private static ArchetypeMeta rulesCreepy() {
		return new ArchetypeMeta(
				"rules_creepy",
				"规则怪谈",
				"规则怪谈:玩家身处一个看似日常却暗藏异常的封闭场景(如雨夜便利店、末班地铁、山区民宿),"
						+ "墙上/纸上贴着一组必须遵守的规则,违反或误读会招致超自然后果。氛围瘆人、逻辑自洽。",
				List.of(
						AttributeAxis.stable("hp", "体力"),
						AttributeAxis.stable("san", "理智")),
				"真假混合的规则(isTrue 有真有假,至少各一条):content 是贴给玩家看的规则原文(口吻像告示),"
						+ "hiddenLogic 是只有引擎能看的真实机制(触发条件 + hp/san 后果)。玩家通过试探/观察逐步看清真伪,"
						+ "discovered 标记已识破的规则。");
	}

	/** 末日生存(本批首个新模式):hp/hunger=体力/饥饿,饥饿随回合自然衰减(AI 落,引擎无知,决策 2)。 */
	private static ArchetypeMeta apocalypse() {
		return new ArchetypeMeta(
				"apocalypse",
				"末日生存",
				"末日生存:文明崩塌后的废墟世界(如丧尸蔓延的城市、核冬天的避难所、资源枯竭的末世聚落),"
						+ "玩家在饥饿、伤病与未知威胁之间求生。氛围荒凉、紧绷、危机四伏,资源永远不够。",
				List.of(
						AttributeAxis.stable("hp", "体力"),
						AttributeAxis.decaying("hunger", "饥饿",
								"饥饿值随回合自然衰减,每回合约下降 5~10(找到并食用补给才回升);"
										+ "由你在 stateUpdate 给出衰减后的新绝对值,务必每回合都体现这一自然消耗。")),
				"生存法则与资源约束(非规则怪谈的真假规则机制,但仍可有「被发现才知道的硬规矩」,复用 discovered 机制):"
						+ "如某些区域的危险规律、物资使用的代价、势力/感染体的行为底线。content 是玩家可摸索到的生存经验,"
						+ "hiddenLogic 是只有引擎能看的真实判定(触发条件 + hp/hunger 后果)。");
	}
}
