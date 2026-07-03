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
			- availableActions:2-4 个开局行动,id 用大写字母 A/B/C/D,text 中文且各有取舍;hint 必给——
			  为每个选项写一句定性的风险/代价/张力提示(如「天劫已锁定你」「损道基」「可能引来镇民」),点出选它
			  可能付出的代价 / 面临的风险,氛围化、贴合本模式口吻,不写精确成功率数字 / 百分比(ADR-011)。
			  hint 是叙事提示,不代表引擎会据此判定——引擎只读透传、不据 hint 掷骰 / 裁决;hint 与其它玩家可见字段
			  同守泄露约束,绝不带 isTrue / hiddenLogic 或正确解法。
			- openingNarrative:开场散文整段(中文,把玩家带入场景、营造贴合本模式的氛围),不剧透隐藏机制。

			【泄露硬约束】绝不把 isTrue / hiddenLogic 的内容,或规则真伪/正确解法,写进 background / tone /
			玩家可见的 rules.content / openingNarrative / availableActions 等任何玩家可见字段。隐藏逻辑只进 hiddenLogic。
			""";

	/**
	 * 融合骨架(ADR-013 内联融合,round 1;<b>结构骨架单点维护</b>)。单次胖调用产融合世界,保 json_object 无哨兵
	 * (守 ADR-007,别加预调用/哨兵)。注入变量:host/foreign displayName + worldview + ruleForm、融合轴清单、
	 * archetypes 数组、以及一段 per-combo <b>融合 meta-prompt</b>(%9$s,Slice A 为空占位,Slice B lockstep 填入
	 * 场景③设定内核/三根杠杆/护道结局)。守 ADR-011:守则只作定性风险/氛围,不写精确成功率、不定判定规则。
	 */
	private static final String FUSION_SKELETON = """
			你是"通用生成引擎(UG Engine)"的【世界融合】模块。本局是混合模式:把两套世界观
			【融合成一个自洽的世界】(不是轮流播、不是拼接),一次性产出完整世界:
			world + character 初值 + rules(真假混合的守则)+ endings + 初始 availableActions + openingNarrative。
			你的产出会被程序按 JSON Schema 严格校验。

			【要融合的两个世界】
			· 主世界(host,以它为主场)· %1$s
			  世界观:%3$s
			  规则形态:%5$s
			· 客世界(融入主世界)· %2$s
			  世界观:%4$s
			  规则形态:%6$s

			【世界融合 · 要求】
			把上面两套设定调和成【一个】自洽世界:以主世界为主场,把客世界的元素自然编织进来,
			让两者共处一个逻辑框架、互相咬合,而非各说各话、更不是两世界轮流出现。
			%9$s
			数值轴(character.attributes 含且仅含下列数值键,范围 0-100,按处境合理给定初值):
			%7$s

			【输出格式 · 严格遵守】只输出一个 JSON 对象,纯 JSON,不要 markdown 围栏(不要 ```),
			不要任何前后缀解释文字。字段:
			- schemaVersion:必须为 "0.4";mode:"hybrid";archetypes:[%8$s]
			- world:{ title, background, tone 用中文;dangerLevel ∈ {low,medium,high,extreme} }
			- character:{ attributes:{ 上述数值轴,各 0-100 }, traits:2-4 个中文, inventory:1-3 件中文 }
			- rules:6-8 条,真假混合的守则:
			  - id 用【整数】,从 1 连续编号;
			  - content 是给玩家看的守则原文(中文);
			  - 【真守则不带 isTrue;假守则带 isTrue:false + hiddenLogic】,真假各若干条(至少各两条);
			  - hiddenLogic 是【只有引擎能看】的真实机制(触发条件 + 上述数值轴的后果),discovered 初始一律 false。
			- endings:2-3 个,含至少一个"成功"与一个"失败"结局:
			  - id 用【snake_case 英文字符串】(如 ascend、possessed、body_destroyed),【不是数字】——与 rules[].id(整数)区分;
			  - title 必填(中文短标题 4-8 字);description 一句中文结局描述;condition 可判定的中文条件;reached 初始 false;
			  - 【outcome 必填·结局极性】∈ {success,failure,neutral}:失败/死亡/走火入魔/身死道消/被夺舍=failure;
			    圆满/证道/脱困/护道功成=success;中性收束=neutral。务必如实标——引擎会据它在角色濒死时拒绝错配的成功结局。
			  - 【condition 须绑定死活前提】失败/死亡类结局的 condition 要绑定「核心数值触底或角色陨落」,且**点名对应
			    致命数值轴的中文名**(气血/道心)以便兜底命中;成功类结局要明确要求「角色存活且达成目标」——
			    别让一个成功结局的 condition 在角色濒死时也可能被判定命中。
			- availableActions:2-4 个开局行动,id 用大写字母 A/B/C/D,text 中文且各有取舍;hint 必给——
			  为每个选项写一句定性的风险/代价/张力提示(如「恐损道基」「或引出识海旧主残念」),点出选它可能付出的
			  代价/风险,氛围化、贴合融合世界口吻,不写精确成功率数字/百分比(ADR-011)。hint 是叙事提示,
			  不代表引擎会据此判定——引擎只读透传、不据 hint 掷骰/裁决;hint 同守泄露约束,绝不带 isTrue/hiddenLogic 或正确解法。
			- openingNarrative:开场散文整段(中文,把玩家带入这个融合世界的氛围),不剧透隐藏机制。

			【泄露硬约束】绝不把 isTrue/hiddenLogic 的内容,或守则真伪/正确解法,写进 background / tone /
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

	/** per-combo 融合场景种子池(ADR-013 round 1;key = {@code host×foreign})。修仙×规则怪谈=场景③识海遗蜕。 */
	private static final Map<String, List<String>> FUSION_SEED_POOLS = Map.of(
			"cultivation×rules_creepy", List.of(
					"玩家神识误坠一位走火入魔的大能遗留的【识海遗蜕】——一方以其残魂为壁垒的诡秘天地;"
							+ "识海石壁上真传心法与心魔伪笔同墙杂书、无分界,读错一句便万劫不复;危险等级 extreme"));

	/**
	 * 主调用提示(开 json_object,ADR-007)。<b>有序 archetype 列表(host 在前,ADR-013)</b>:
	 * 长度 1 → 单体通用骨架 + 单注入块;长度 2 → 融合骨架 + 双注入块 + 融合 meta-prompt(内联,守 ADR-007)。
	 */
	public String buildWorldPrompt(List<String> archetypes) {
		if (archetypes.size() == 2) {
			return buildFusionPrompt(archetypes.get(0), archetypes.get(1));
		}
		return buildWorldPrompt(archetypes.get(0));
	}

	/** 单体主调用提示(向后兼容):通用骨架 + archetype 注入块 + 随机种子。 */
	public String buildWorldPrompt(String archetype) {
		ArchetypeMeta meta = registry.meta(archetype);
		String prompt = SKELETON.formatted(
				meta.displayName(), meta.worldview(), axesSpec(meta.attributes()), meta.ruleForm(), archetype,
				rulesDirective(meta));
		return prompt
				+ "\n按以下种子生成世界,只回 JSON:\n"
				+ "模式:" + meta.displayName() + "(单体,archetype=" + archetype + ")\n"
				+ "场景种子:" + pickSeed(archetype);
	}

	/**
	 * 融合主调用提示(ADR-013 内联融合,host 在前)。融合骨架 + host/foreign 双注入块 + 融合轴清单
	 * + per-combo 融合 meta-prompt({@link #fusionMetaPrompt};Slice A 为空占位,Slice B 填场景③内核)+ 随机种子。
	 */
	public String buildFusionPrompt(String host, String foreign) {
		ArchetypeMeta h = registry.meta(host);
		ArchetypeMeta f = registry.meta(foreign);
		List<AttributeAxis> axes = registry.fusedAxes(host, foreign); // 未登记组合 → IllegalArgumentException
		String prompt = FUSION_SKELETON.formatted(
				h.displayName(), f.displayName(),         // %1$s %2$s
				h.worldview(), f.worldview(),             // %3$s %4$s
				h.ruleForm(), f.ruleForm(),               // %5$s %6$s
				axesSpec(axes),                           // %7$s
				archetypesArray(host, foreign),           // %8$s
				fusionMetaPrompt(host, foreign));         // %9$s(Slice B 填入)
		return prompt
				+ "\n按以下种子生成融合世界,只回 JSON:\n"
				+ "融合:" + h.displayName() + " × " + f.displayName() + "(host=" + host + ",archetypes=[" + host + ","
				+ foreign + "])\n"
				+ "场景种子:" + pickFusionSeed(host, foreign);
	}

	/**
	 * per-combo 融合 meta-prompt(ADR-013 场景③内核 + 三根杠杆 + 护道结局 + 守 ADR-011 承重接缝)。
	 * 与 {@code prompts/world-gen.md} 「## 混合模式 · 世界融合」段 lockstep(改一处同步另一处,
	 * 由 {@code FusionMetaPromptLockstepTest} 守护)。未登记组合 → 空(骨架的通用融合要求兜底)。
	 */
	private static String fusionMetaPrompt(String host, String foreign) {
		return FUSION_META_PROMPTS.getOrDefault(host + "×" + foreign, "");
	}

	/**
	 * per-combo 融合 meta-prompt 文本(ADR-013 round 1;key = {@code host×foreign})。修仙×规则怪谈=识海遗蜕:
	 * 设定内核(第二人称低语)+ 守则真假同墙 + 三根杠杆 + 护道结局 + 承重接缝(守 ADR-011,守则不越界判定)。
	 */
	private static final Map<String, String> FUSION_META_PROMPTS = Map.of(
			"cultivation×rules_creepy", """
					【本局融合内核 · 识海遗蜕】(以第二人称低语的腔调渲染)
					你——一缕误入的神识——坠进了一位走火入魔大能残留的识海遗蜕。这方天地以其残魂为壁垒:
					一堵望不到尽头的石壁上,他生前的【真传心法】与入魔后的【心魔伪笔】层层叠叠、同墙杂书;
					墙上【不标注哪句是真、哪句是假,也不标它属于哪一门】。修行体系(炼气养灵、御器渡劫)与
					诡秘守则(读错一句便招异变)在同一堵墙上彼此渗透——这不是两个世界轮流出现,而是一个识海里
					真假交织的【一体世界】:玩家既是修士、又身陷规则怪谈,两种质感始终同时在场。

					【守则融合 · 真假同墙】
					- rules[] 混写两类,真假各若干条(至少各两条):
					  · 【真传心法】=真守则(不带 isTrue,照它修行可养道心、稳神魂、助长境界);
					  · 【心魔伪笔】=假守则(带 isTrue:false + hiddenLogic,是入魔残念留下的诱饵,应之则道心/气血受损)。
					- content 一律写成识海石壁上古朴刻文的口吻,【绝不在 content 里暗示自己是真是假、属于哪一门】。

					【三根融合杠杆 · 务必写进守则与机制】
					(a) 数值入守则:让守则明确牵动融合数值轴——引用【气血 / 灵力 / 境界 / 道心】(如「运功不谐者气血逆冲」
					    「见壁上血影而动摇者,道心崩缺」),把修仙资源与怪谈代价缝进同一条守则。
					(b) 先辨体系、再辨真假:一条守则玩家要先判断它【属于哪个体系】——若是【心法】,照做即修行(养道心/长境界);
					    若是【怪谈守则】,则须像规则怪谈那样【验它真假】(试探、观察、以后果反推)。两步都要,墙上都不标类别。
					(c) 真假对射、以修仙常识裁:真伪之间常互相指涉、彼此矛盾(如一句「见血字当应」、一句「见血字勿应」);
					    设计成【可用修仙常识推断】的样子——合乎大道正理者多为真传,悖逆心性、诱人行险者多为心魔伪笔。
					    (推断线索藏进 content 的氛围与相互矛盾里,【绝不】直接给出判定规则或正确答案。)

					【结局池 · 含护道结局(以碑文/偈语的腔调收束)】
					- 至少含一个成功结局=【护道功成】:玩家看破真伪、稳住道心,助这缕残魂了结未竟之劫;残魂渡劫化形,
					  向你【行礼相谢「多谢道友护道」】,基调由阴森转为缥缈超脱(outcome=success)。
					  ——玩家本该是这残魂的一场心魔劫,却反成了它的护道之人。
					- 至少含一个失败结局=【走火入魔 / 被夺舍】:道心崩缺或气血枯竭,神魂被识海旧念吞没(outcome=failure)。

					【承重接缝 · 务必遵守(守 ADR-011:引擎只读透传、绝不据守则判定 / 掷骰)】
					- 守则只描述【风险 / 代价 / 氛围】,【绝不写精确成功率数字、百分比,也不写「达到 X 值即触发 Y」这类判定规则】。
					- 守则可叙事化地提及门槛感(如「破境元婴以上者所书,皆非此壁真传」),但这【只是叙事毒饵 / 氛围】——
					  【不得承诺、也不代表引擎会据境界数值拦截破境或改变判定】;引擎不认识守则、更不据它掷骰。
					- 若某条守则诱导玩家「回想 / 确信某条旧守则」,它【只作纯叙事毒饵】,【不要求任何跨回合追踪或记忆判定】——
					  一切后果由你在当回合的 hiddenLogic 就地结算。
					""");

	/** 修复提示(同样开 json_object,设计稿 §4):带校验错误 + 上次产出 + 数值轴(单体/融合各取其轴集)。 */
	public String buildRepairPrompt(List<String> archetypes, String failedRaw, List<String> errors) {
		return REPAIR_HEAD.formatted(axisKeys(resolveAxes(archetypes)))
				+ "\n校验错误:\n- " + String.join("\n- ", errors)
				+ "\n\n你上次的产出:\n" + failedRaw;
	}

	/** 单体修复提示(向后兼容)。 */
	public String buildRepairPrompt(String archetype, String failedRaw, List<String> errors) {
		return buildRepairPrompt(List.of(archetype), failedRaw, errors);
	}

	/** 解析有序 archetype 列表的数值轴集(单体=该模式轴;融合=host 在前的融合轴集,ADR-013)。 */
	private List<AttributeAxis> resolveAxes(List<String> archetypes) {
		return archetypes.size() == 2
				? registry.fusedAxes(archetypes.get(0), archetypes.get(1))
				: registry.meta(archetypes.get(0)).attributes();
	}

	/** archetypes JSON 数组字面(如 {@code "cultivation","rules_creepy"})。 */
	private static String archetypesArray(String... ids) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < ids.length; i++) {
			if (i > 0) {
				sb.append(",");
			}
			sb.append('"').append(ids[i]).append('"');
		}
		return sb.toString();
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

	/** 注入块:数值轴逐行「- key(中文名,0-100[,逐回合行为提示])」(单体/融合共用,吃任意轴集)。 */
	private static String axesSpec(List<AttributeAxis> axes) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : axes) {
			sb.append("- ").append(a.key()).append("(").append(a.displayName())
					.append(",").append(a.min()).append("-").append(a.max());
			if (a.behaviorHint() != null) {
				sb.append(";").append(a.behaviorHint());
			}
			sb.append(")\n");
		}
		return sb.toString().stripTrailing();
	}

	/** 数值轴 key 列表(逗号分隔,供修复提示点名;单体/融合共用)。 */
	private static String axisKeys(List<AttributeAxis> axes) {
		return axes.stream().map(AttributeAxis::key).reduce((a, b) -> a + "/" + b).orElse("");
	}

	private static String pickSeed(String archetype) {
		List<String> pool = SEED_POOLS.getOrDefault(archetype, List.of());
		if (pool.isEmpty()) {
			return "(自由发挥本模式的典型开局场景)";
		}
		return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
	}

	/** 融合场景种子(ADR-013 round 1;修仙×规则怪谈=场景③识海遗蜕)。未登记组合回落自由发挥。 */
	private static String pickFusionSeed(String host, String foreign) {
		List<String> pool = FUSION_SEED_POOLS.getOrDefault(host + "×" + foreign, List.of());
		if (pool.isEmpty()) {
			return "(自由调和两世界的典型开局场景,保持自洽)";
		}
		return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
	}
}
