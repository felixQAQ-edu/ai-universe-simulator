package com.aiuniverse.server.worldgen;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/**
 * 组装 world-gen 胖调用提示(ADR-007 线上格式:<b>纯 JSON + json_object + 无哨兵</b>)。运行时真理之源
 * 与 {@code prompts/world-gen.md}(人类可读核心资产,CONTEXT §三.6)保持同形。
 *
 * <p>与回合提示(prose+哨兵+尾巴)<b>刻意不同口径</b>:world-gen 输出主体是结构(world/character/
 * rules/endings/初始 actions),且 JSON 首次失败是头号失败模式 → 把可靠性留在最险的那次生成,保 json_object。
 *
 * <p>沿用 bake-off 已验 world-gen 提示词(8 golden parity),适配生产:<b>id 类型约定</b>(F-001:
 * {@code rules[].id} 整数 / {@code endings[].id} snake_case 字符串)、<b>泄露硬化</b>(禁止把
 * {@code isTrue}/{@code hiddenLogic} 写进 {@code background}/玩家可见 {@code rules.content}/
 * {@code openingNarrative})、新增 {@code openingNarrative} + 初始 {@code availableActions}。
 *
 * <p>F-005 多样性:从场景种子池随机取一条(单一种子致沉浸感套路化,bake-off 沉浸感 3.25)。
 *
 * <p>注:模板内联于此(便于单测钉格式、零 FS 依赖);{@code prompts/world-gen.md} 为同义可读副本
 * ——与 {@link com.aiuniverse.server.eventloop.TurnPromptBuilder} 同口径。
 */
@Component
public final class WorldGenPromptBuilder {

	private static final String SYSTEM = """
			你是"通用生成引擎(UG Engine)"的世界生成模块,专精规则怪谈。你一次性产出完整世界:
			world + character 初值 + rules(真假混合)+ endings + 初始 availableActions + openingNarrative。
			你的产出会被程序按 JSON Schema 严格校验。

			【输出格式 · 严格遵守】只输出一个 JSON 对象,纯 JSON,不要 markdown 围栏(不要 ```),
			不要任何前后缀解释文字。字段:
			- schemaVersion:必须为 "0.2";mode:"single";archetypes:["rules_creepy"]
			- world:{ title, background, tone 用中文;dangerLevel ∈ {low,medium,high,extreme} 取种子给定值 }
			- character:{ attributes:{ hp, san 范围 0-100,按危险等级合理给定 }, traits:2-4 个中文, inventory:1-3 件中文 }
			- rules:6-8 条,真假混合(isTrue 有真有假,至少各一条):
			  - id 用【整数】,从 1 连续编号;
			  - content 是"贴在墙上给玩家看"的规则原文(中文,口吻像告示);
			  - hiddenLogic 是【只有引擎能看】的真实机制(触发条件 + hp/san 后果),discovered 初始一律 false。
			- endings:2-3 个,含至少一个"生存"与一个"失败"结局:
			  - id 用【snake_case 英文字符串】(如 survive_dawn、lost_mind),【不是数字】——注意与 rules[].id(整数)区分;
			  - title 必填(中文短标题 4-8 字);description 一句中文结局描述;condition 可判定的中文条件;reached 初始 false。
			- availableActions:2-4 个开局行动,id 用大写字母 A/B/C/D,text 中文且各有取舍,hint 可空。
			- openingNarrative:开场散文整段(中文,把玩家带入场景、点出墙上有规则、营造瘆人氛围),不剧透机制。

			【泄露硬约束】绝不把 isTrue / hiddenLogic 的内容,或规则真伪/正确解法,写进 background / tone /
			玩家可见的 rules.content / openingNarrative / availableActions 等任何玩家可见字段。隐藏逻辑只进 hiddenLogic。
			""";

	private static final String REPAIR_SYSTEM = """
			你上次产出的世界 JSON 未通过校验。请只回修正后的【完整 world JSON】(纯 JSON 对象,不要 markdown 围栏、
			不要解释文字),字段与约束同前(schemaVersion "0.2"、rules[].id 整数、endings[].id snake_case 字符串、
			character.attributes 含 hp/san 0-100、含 availableActions 与 openingNarrative)。
			""";

	/** F-005 多样性:场景种子池(沿用 bake-off SEED_A1/A2 形态,随机取一条提多样性)。 */
	private static final List<String> SEED_POOL = List.of(
			"雨夜便利店,玩家是临时夜班店员,凌晨 0:00–6:00;危险等级 high",
			"深夜末班地铁,玩家错过下车,车厢里只剩自己;危险等级 high",
			"山区民宿,玩家独自入住,墙上贴着住宿须知;危险等级 medium",
			"凌晨医院住院部走廊,玩家是陪护家属;危险等级 extreme");

	/** 主调用提示(开 json_object,ADR-007):随机种子 + 全 schema 要求。 */
	public String buildWorldPrompt(String archetype) {
		// Phase 1 仅 rules_creepy;archetype 透传以备多模式扩展。
		String seed = SEED_POOL.get(ThreadLocalRandom.current().nextInt(SEED_POOL.size()));
		return SYSTEM
				+ "\n\n按以下种子生成世界,只回 JSON:\n"
				+ "模式:规则怪谈(单体,archetype=" + archetype + ")\n"
				+ "场景种子:" + seed;
	}

	/** 修复提示(同样开 json_object,world-gen 本就开,零切换成本,设计稿 §4):带校验错误 + 上次产出。 */
	public String buildRepairPrompt(String failedRaw, List<String> errors) {
		return REPAIR_SYSTEM
				+ "\n\n校验错误:\n- " + String.join("\n- ", errors)
				+ "\n\n你上次的产出:\n" + failedRaw;
	}
}
