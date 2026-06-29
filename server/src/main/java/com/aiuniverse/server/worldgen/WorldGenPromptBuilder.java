package com.aiuniverse.server.worldgen;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;

/**
 * 组装 world-gen 胖调用提示(ADR-007 线上格式:<b>纯 JSON + json_object + 无哨兵</b>)。运行时真理之源
 * 与 {@code prompts/world-gen.md}(人类可读核心资产,CONTEXT §三.6)保持同形。
 *
 * <p><b>多模式重构(ADR-008 决策 3)</b>:提示词 = <b>通用骨架(单点维护)+ per-archetype 注入块</b>。
 * 骨架固定:输出 schema、id 类型约定(F-001:{@code rules[].id} 整数 / {@code endings[].id} snake_case)、
 * 泄露硬化、json_object、{@code openingNarrative}、初始 {@code availableActions}——<b>模式无关,绝不每模式重抄</b>
 * (消毒/id 这种安全规矩重抄一次错一次)。注入块从 {@link ArchetypeRegistry} 取该模式的
 * {@code worldview}/{@code attributes(数值轴+衰减)}/{@code ruleForm}。加模式 = 一条元数据 + 一个种子池条目。
 *
 * <p>与回合提示(prose+哨兵+尾巴)<b>刻意不同口径</b>:world-gen 主体是结构,JSON 首次失败是头号失败模式
 * → 把可靠性留在最险的那次生成,保 json_object。
 *
 * <p>F-005 多样性:从该 archetype 的场景种子池随机取一条(单一种子致沉浸感套路化)。
 */
@Component
public final class WorldGenPromptBuilder {

	private final ArchetypeRegistry registry;

	public WorldGenPromptBuilder(ArchetypeRegistry registry) {
		this.registry = registry;
	}

	/** 通用骨架(模式无关,单点维护)。注入变量:displayName / worldview / 数值轴清单 / ruleForm / archetype id。 */
	private static final String SKELETON = """
			你是"通用生成引擎(UG Engine)"的世界生成模块。你一次性产出完整世界:
			world + character 初值 + rules(规则/法则,形态见下)+ endings + 初始 availableActions + openingNarrative。
			你的产出会被程序按 JSON Schema 严格校验。

			【本模式 · %1$s】
			世界观:%2$s
			规则形态:%4$s
			数值轴(character.attributes 含且仅含下列数值键,范围 0-100,按危险等级/处境合理给定初值):
			%3$s

			【输出格式 · 严格遵守】只输出一个 JSON 对象,纯 JSON,不要 markdown 围栏(不要 ```),
			不要任何前后缀解释文字。字段:
			- schemaVersion:必须为 "0.4";mode:"single";archetypes:["%5$s"]
			- world:{ title, background, tone 用中文;dangerLevel ∈ {low,medium,high,extreme} 取种子给定值 }
			- character:{ attributes:{ 上述数值轴,各 0-100 }, traits:2-4 个中文, inventory:1-3 件中文 }
			- rules:6-8 条,%6$s:
			  - id 用【整数】,从 1 连续编号;
			  - content 是给玩家看的规则/生存法则原文(中文,见上方规则形态);
			  - hiddenLogic 是【只有引擎能看】的真实机制(触发条件 + 上述数值轴的后果),discovered 初始一律 false。
			- endings:2-3 个,含至少一个"存活/成功"与一个"失败"结局:
			  - id 用【snake_case 英文字符串】(如 survive_dawn、starved、lost_mind),【不是数字】——注意与 rules[].id(整数)区分;
			  - title 必填(中文短标题 4-8 字);description 一句中文结局描述;condition 可判定的中文条件;reached 初始 false;
			  - 【outcome 必填·结局极性】每个 ending 必须标 outcome ∈ {success, failure, neutral}:
			    失败 / 死亡 / 陨落 / 发疯 / 身死道消类 = "failure";圆满 / 突破 / 飞升 / 逃生 / 达成目标类 = "success";
			    既非明确成功也非明确失败的中性收束 = "neutral"。务必如实标——引擎会据它在角色濒死时拒绝错配的成功结局。
			  - 【condition 须绑定死活前提】失败/死亡/陨落类结局的 condition 要绑定「核心数值触底或角色陨落」
			    (如「气血归零身死道消」「理智崩解发疯」「饥饿而亡」),且 condition 里**点名对应数值轴的中文名**
			    (气血/理智/灵力…)以便兜底命中;成功/存活类结局的 condition 要明确要求「角色存活且达成目标」——
			    别让一个成功结局的 condition 在角色濒死时也可能被判定命中。
			- availableActions:2-4 个开局行动,id 用大写字母 A/B/C/D,text 中文且各有取舍,hint 可空。
			- openingNarrative:开场散文整段(中文,把玩家带入场景、营造贴合本模式的氛围),不剧透隐藏机制。

			【泄露硬约束】绝不把 isTrue / hiddenLogic 的内容,或规则真伪/正确解法,写进 background / tone /
			玩家可见的 rules.content / openingNarrative / availableActions 等任何玩家可见字段。隐藏逻辑只进 hiddenLogic。
			""";

	private static final String REPAIR_HEAD = """
			你上次产出的世界 JSON 未通过校验。请只回修正后的【完整 world JSON】(纯 JSON 对象,不要 markdown 围栏、
			不要解释文字),字段与约束同前(schemaVersion "0.4"、rules[].id 整数、endings[].id snake_case 字符串、
			每个 ending 含 outcome ∈ {success,failure,neutral}、含 availableActions 与 openingNarrative)。
			本模式 character.attributes 须含数值轴:%s(各 0-100)。
			""";

	/** per-archetype 场景种子池(F-005 多样性;沿用 bake-off 形态,随机取一条)。 */
	private static final Map<String, List<String>> SEED_POOLS = Map.of(
			"rules_creepy", List.of(
					"雨夜便利店,玩家是临时夜班店员,凌晨 0:00–6:00;危险等级 high",
					"深夜末班地铁,玩家错过下车,车厢里只剩自己;危险等级 high",
					"山区民宿,玩家独自入住,墙上贴着住宿须知;危险等级 medium",
					"凌晨医院住院部走廊,玩家是陪护家属;危险等级 extreme"),
			"apocalypse", List.of(
					"丧尸围城第七天,玩家困在便利店仓库,补给将尽;危险等级 high",
					"核冬天的地下避难所,通风系统故障,外面是辐射尘暴;危险等级 extreme",
					"末世小镇幸存者营地,入夜后围栏外有东西在徘徊;危险等级 high",
					"资源枯竭的末日公路,玩家驾车寻找下一处补给点,油表见底;危险等级 medium"),
			"cthulhu", List.of(
					"阿卡姆郊外的古宅,玩家受邀整理已故亲属遗留的藏书,书房深处有一本无名黑皮书;危险等级 high",
					"雾锁的海边小镇,玩家是路过的旅人,镇民的眼睛与步态都有些不对劲;危险等级 high",
					"米斯卡托尼克大学图书馆禁阅区,玩家是夜班管理员,有人借走了不该外借的典籍;危险等级 high",
					"南极考察站,暴雪封路,队友从冰层下挖出一块刻满非人符号的玄武岩;危险等级 extreme"),
			"cultivation", List.of(
					"青云宗外门弟子,刚入门的炼气期少年,奉命独自看守后山灵药园;危险等级 medium",
					"散修游历至一座荒废的上古洞府,洞门上残留着未散尽的禁制灵光;危险等级 high",
					"宗门大比前夜,玩家是被同门排挤的废灵根弟子,意外得了一卷残破心法;危险等级 medium",
					"渡劫将至,玩家是闭关冲击筑基的修士,洞外却有仇家循着灵气波动逼近;危险等级 high"));

	/** 主调用提示(开 json_object,ADR-007):通用骨架 + archetype 注入块 + 随机种子。 */
	public String buildWorldPrompt(String archetype) {
		ArchetypeMeta meta = registry.meta(archetype);
		String prompt = SKELETON.formatted(
				meta.displayName(), meta.worldview(), axesSpec(meta), meta.ruleForm(), archetype, rulesDirective(meta));
		return prompt
				+ "\n按以下种子生成世界,只回 JSON:\n"
				+ "模式:" + meta.displayName() + "(单体,archetype=" + archetype + ")\n"
				+ "场景种子:" + pickSeed(archetype);
	}

	/** 修复提示(同样开 json_object,world-gen 本就开,零切换成本,设计稿 §4):带校验错误 + 上次产出 + 本模式数值轴。 */
	public String buildRepairPrompt(String archetype, String failedRaw, List<String> errors) {
		return REPAIR_HEAD.formatted(axisKeys(registry.meta(archetype)))
				+ "\n校验错误:\n- " + String.join("\n- ", errors)
				+ "\n\n你上次的产出:\n" + failedRaw;
	}

	/**
	 * rules 形态指令(ADR-009 F-013,据 {@link ArchetypeMeta#rulesCarryTruth}):真假守则型注入「真假混合 +
	 * isTrue 有真有假」;心法守则型(修仙)注入「无真假之分、不输出 isTrue」。骨架其余(id 整数/hiddenLogic/
	 * discovered)模式无关、单点维护。校验器本身零分派(isTrue 全局可选)。
	 */
	private static String rulesDirective(ArchetypeMeta meta) {
		return meta.rulesCarryTruth()
				? "真假混合(isTrue 有真有假,至少各一条)"
				: "皆为该模式的法则/心法/守则形态(读起来是准则与代价,无真假之分,【不要输出 isTrue 字段】)";
	}

	/** 注入块:数值轴逐行「- key(中文名,0-100[,逐回合行为提示])」。 */
	private static String axesSpec(ArchetypeMeta meta) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : meta.attributes()) {
			sb.append("- ").append(a.key()).append("(").append(a.displayName())
					.append(",").append(a.min()).append("-").append(a.max());
			if (a.behaviorHint() != null) {
				sb.append(";").append(a.behaviorHint());
			}
			sb.append(")\n");
		}
		return sb.toString().stripTrailing();
	}

	/** 数值轴 key 列表(逗号分隔,供修复提示点名)。 */
	private static String axisKeys(ArchetypeMeta meta) {
		return meta.attributes().stream().map(AttributeAxis::key).reduce((a, b) -> a + "/" + b).orElse("");
	}

	private static String pickSeed(String archetype) {
		List<String> pool = SEED_POOLS.getOrDefault(archetype, List.of());
		if (pool.isEmpty()) {
			return "(自由发挥本模式的典型开局场景)";
		}
		return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
	}
}
