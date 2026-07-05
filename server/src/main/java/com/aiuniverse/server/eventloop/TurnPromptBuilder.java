package com.aiuniverse.server.eventloop;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;

/**
 * 组装 event-loop 单回合提示(ADR-006 线上格式)。运行时真理之源与 {@code prompts/event-loop.md}
 * (人类可读的核心资产,CONTEXT §三.6)保持同形:<b>prose 先行 + 哨兵 {@value SentinelSplitter#SENTINEL}
 * + 结构化尾巴(尾巴去掉 narrative 字段)</b>。
 *
 * <p><b>多模式注入(ADR-008 决策 3)</b>:从 {@link ArchetypeRegistry} 取本局 archetype 的元数据,注入
 * 「正在推进的模式名 + 本回合要维护的数值轴(含中文意象 + 衰减提示)+ stateUpdate 格式」。回合里 AI 据此
 * 持续维护该模式数值(末日每回合落 hunger 自然衰减,决策 2)。<b>叙事清洁度硬约束(禁内部字段名/markdown 头)
 * 保留</b>(Phase 1 v0.3),禁名单从数值轴 key 动态生成。archetype 从 {@link Engine#world()} 的 archetypes 取。
 *
 * <p>键名严格对齐 {@code schema.py}/{@code Engine.apply}(规格 §4.4):{@code stateUpdate{<各数值轴>,timeline}} /
 * {@code triggeredRuleIds[int]} / {@code discoveredRuleIds[int]} / {@code availableActions[{id,text,hint}]}(2–4) /
 * {@code ending: null | {id<string>,reached<bool>}}。
 *
 * <p>叙事长度约束(A-1)保留:目标 2–4 句/约 120–200 字、硬上限约 280 字。
 *
 * <p><b>数值行为档注入(#3,Slice B)</b>:每回合按当前绝对值算各轴所处「行为档」({@link AttributeAxis#resolveBand}),
 * 把<b>当前档</b>的 {@code label}+{@code narrationHint} 注入 User 段(只送当前档不送整张表,守成本),System 加
 * 「叙事须贴合当前状态档」指令——叙事跟着状态走,在既有 2–4 句篇幅内化入。<b>只染叙事、绝不据档位 gate 可选行动</b>
 * (=#4,留状态层)。行为档不含 isTrue/hiddenLogic(守消毒),引擎不读(守 ADR-008)。
 */
@Component
public final class TurnPromptBuilder {

	private final ArchetypeRegistry registry;

	public TurnPromptBuilder(ArchetypeRegistry registry) {
		this.registry = registry;
	}

	/** 数值轴 key → 叙事中文意象提示(清洁度:数值变化只用意象,不直呼字段名)。缺省回落 displayName。 */
	private static final Map<String, String> IMAGERY = Map.of(
			"hp", "身体/伤势/气力/气血(如「伤口又裂开」「气血翻涌」)",
			"san", "神智/理智/心神(如「神智几近崩断」)",
			"hunger", "饥饿/腹中空乏/虚脱无力(如「饿得手指发抖」)",
			"knowledge", "对禁忌真相的洞悉/脑中挥之不去的低语与灼烧(如「那些符号在脑海里反复灼烧」)",
			"mana", "灵力/法力/真元的充盈或枯竭(如「灵力将尽,真元空乏」「丹田一空」)",
			"realm", "修为境界/对天地大道的领悟/根基(如「丹田气海又凝实了几分」「隐隐触到了瓶颈」)");

	/**
	 * 融合局的 per-combo 意象换皮 override(ADR-013 Slice D;key = {@code host×foreign})。换皮轴的叙事口吻
	 * 须与显示层换皮一致(修仙×规则怪谈:san 的默认「神智/理智」口吻在识海里出戏 → 改道心口吻)。
	 * 未 override 的轴照旧走 {@link #IMAGERY}。
	 */
	private static final Map<String, Map<String, String>> FUSION_IMAGERY = Map.of(
			"cultivation×rules_creepy", Map.of(
					"san", "道心/心神/道基的清明或动摇(如「道心一颤,险些失守」「道基隐隐生裂,心魔趁隙低语」)"));

	/** 通用骨架。注入变量:模式名 / 数值轴维护块 / 禁用字段名清单 / stateUpdate 字段格式。 */
	private static final String SKELETON = """
			你是 UG Engine 的事件流模块,正在推进一局%1$s。你会收到完整世界设定(world / character /
			rules / endings)与当前 state(含 logSummary 与近几回合 log)。规则的 isTrue 与 hiddenLogic
			是你的内部依据,用来决定后果,但绝不能出现在叙事等任何玩家可见文本里。

			【叙事清洁度 · 破第四面墙即不合格】(1)禁止把内部字段名写进叙事——%3$s 等是引擎内部命名,
			叙事里绝不出现(包括「%4$s值」这类直呼);数值变化只用中文意象:
			%2$s
			(2)禁止 markdown 标题/结构:叙事是纯散文,不要 #/## 标题、不要「第 N 回合」之类回合头、不要列表符号。

			【叙事长度 · 可玩性约束】每回合叙事简洁克制:目标 2-4 句、约 120-200 字,硬上限约 280 字;宁短勿长
			(玩家在手机上逐字读完整局,叙事过长则累、反而冲淡紧张感)。以短叙事主体为主(承接玩家行动的直接后果 +
			一笔氛围),细节点到为止、可选,不堆砌环境描写或心理独白;不为压长度牺牲氛围或逻辑自洽,该交代的后果
			与该埋的线索仍要给到,只是更凝练(写得紧,不是写得少)。

			【叙事须贴合当前状态档】每回合叙事要自然体现各数值轴当前所处的「状态档」(见下方「各数值轴当前所处的
			状态档」):如体力濒危档,角色应脚步虚浮、动作迟滞、濒死感弥漫;灵力枯竭档应力竭、施不出像样法术(力竭
			非伤身);禁忌知识深陷档,真相侵蚀感知。把档位状态化入散文(动作 / 感官 / 处境),不要照搬档名或提示词
			原文、也不要直呼数值;在上条既有 2-4 句篇幅内体现,不为它额外加长。

			【输出格式 · 严格遵守】先逐字输出本回合中文叙事散文(承接玩家上一步行动的后果,氛围贴合本模式、逻辑自洽),
			叙事之后另起一行,输出一行哨兵 %5$s,哨兵之后输出本回合的结构化尾巴 JSON。叙事在哨兵前、尾巴在哨兵后,
			两者之间只有这一行哨兵,不要有别的分隔符,不要用 markdown 围栏。

			结构化尾巴 JSON(不含 narrative 字段,叙事已在哨兵前流出)字段:
			- stateUpdate:{ %6$s, "timeline": "<一句话世界线>" }
			  (各数值轴是本回合结束后的新绝对值,0-100,按 hiddenLogic 结算,与历史不得矛盾、不得无故回升)
			%7$s
			- triggeredRuleIds:本回合触发的规则 id 整数数组(没有则 [])
			- discoveredRuleIds:本回合玩家验证真伪/看清机制的规则 id 整数数组(没有则 [])
			- availableActions:2-4 个合法行动,id 用大写字母 A/B/C/D,text 中文且各有取舍;每个选项 hint 必给——
			  写一句定性的风险/代价/张力提示(如「越靠近,那低语越清晰」「恐引来它注意」「拼一把,但气血撑不住几下」),
			  点出选它可能付出的代价 / 面临的风险,氛围化、贴合本模式与当前处境,不写精确成功率数字 / 百分比(ADR-011)。
			  hint 是叙事提示,不代表引擎会据此判定——引擎只读透传、不据 hint 掷骰 / 裁决;hint 与叙事同守泄露约束,
			  绝不带 isTrue / hiddenLogic 或正确解法。
			- ending:命中某 endings[].condition 时为 { "reached": true, "id": "<结局id 字符串>" },否则 null。
			  【结局必须匹配角色死活·硬约束】结局不得与角色当前真实处境矛盾:当代表角色存续的核心数值濒零
			  (如 hp/气血等 depletion 轴 ≤ 约 10),或叙事中角色已濒死 / 重伤垂危 / 理智崩解 / 油尽灯枯 / 陨落时,
			  只能命中【失败 / 死亡 / 陨落类】结局,绝不给【成功 / 圆满 / 凯旋类】结局;成功类结局仅当角色确实安然存续
			  且达成目标时才命中。宁可返回 null(让游戏继续)也不要给与死活状态矛盾的结局。%8$s
			""";

	/**
	 * 融合局专属回合指令(ADR-013 Slice D;只在 hybrid 局注入 {@code %8$s},单体局注入空串 → 单体 prompt
	 * 逐字不变)。两件事:(1) 守则裁决——把「辨真伪」做进每回合循环(误信伪笔伤道心 / 识破有回报);
	 * (2) 张力收敛——剧情升级、向结局池收敛、不得原地回环(不写死回合数上限,硬上限是引擎层决策不混入)。
	 * 与 {@code prompts/event-loop.md} 融合段 lockstep(TurnFusionLockstepTest 守护)。
	 */
	private static final String FUSION_TURN_DIRECTIVE = """


			【融合世界 · 每回合裁决与收敛】本局是融合世界(两套世界观真假交织在同一逻辑框架里),每回合守好两件事:
			(1)【守则裁决】玩家行动触及某条守则时,按该守则的 isTrue / hiddenLogic 就地裁决——误信心魔伪笔
			    (isTrue:false)应付出代价(首当其冲是道心,亦可波及气血),把代价化入叙事;识破伪笔或印证真传
			    心法,给出叙事与数值的正向回报(稳道心 / 长境界 / 得线索),并记入 discoveredRuleIds。
			    把「辨真伪」做进每一回合的循环,不要让守则墙沦为背景板。
			(2)【张力收敛】剧情须随回合推进升级张力、向 endings 池收敛,不得原地回环(不要反复出现同质事件 /
			    同样的选项组合);每回合至少推进一点:新线索 / 新威胁 / 某条守则真伪坐实,三者取其一。
			    当某条结局的 condition 接近达成时,主动让剧情走向它、达成即给出 ending,不要拖延磨回合。""";

	private static final String REPAIR_SYSTEM = """
			你上一回合输出的结构化尾巴 JSON 未通过校验。请只回修正后的结构化尾巴 JSON(纯 JSON 对象,
			不含 narrative 字段、不要叙事、不要哨兵、不要围栏),字段与范围同前(stateUpdate 的各数值轴为 0-100
			绝对值,availableActions 2-4 个,ending 为 null 或 {reached,id})。
			""";

	/**
	 * 本回合提示词的注入语境(ADR-013 Slice D):模式名 + 数值轴集 + 意象换皮 + 融合标志。
	 * 单体局 = 该模式元数据原样(prompt 逐字不变);融合局 = 融合轴集(与播种同一真理源
	 * {@link ArchetypeRegistry#fusedAxes})+ 融合模式名 + per-combo 意象换皮 + 融合回合指令。
	 */
	private record TurnContext(String modeName, List<AttributeAxis> axes, Map<String, String> imageryOverrides,
			boolean fused) {
	}

	/** 主调用提示(不开 json_object):prose 先行 + 哨兵 + 尾巴。按本局 archetype(单体/融合)注入数值轴/模式名。 */
	public String buildTurnPrompt(Engine engine, String actionId, String actionText) {
		TurnContext ctx = resolveContext(engine);
		String action = actionText == null || actionText.isBlank() ? actionId : actionId + " · " + actionText;
		String system = SKELETON.formatted(
				ctx.modeName(),           // %1$s 模式名(融合局=「A × B(融合世界)」)
				imageryBlock(ctx),        // %2$s 各轴中文意象(融合局含换皮口吻)
				forbiddenNames(ctx.axes()), // %3$s 禁用字段名清单
				exampleForbiddenKey(ctx.axes()), // %4$s 「XX值」直呼示例 key
				SentinelSplitter.SENTINEL, // %5$s 哨兵
				stateUpdateAxes(ctx.axes()), // %6$s stateUpdate 数值轴字段(融合局含全部融合轴)
				behaviorReminder(ctx.axes()), // %7$s 特殊行为轴维护提醒(衰减/累积/联动;无则空)
				ctx.fused() ? FUSION_TURN_DIRECTIVE : ""); // %8$s 融合裁决+收敛指令(单体=空串,逐字不变)
		return system
				+ "\n\n世界设定与当前状态(state 是真理之源):\n"
				+ engine.contextJson()
				+ currentBandBlock(ctx.axes(), engine)
				+ "\n\n请推进第 " + (engine.turn() + 1) + " 回合。玩家本回合选择的行动:" + action;
	}

	/** 修复提示(开回 json_object,规格 §6.4):带上校验错误 + 上次失败尾巴,只要修正后的尾巴 JSON。 */
	public String buildRepairPrompt(String failedTail, List<String> errors) {
		return REPAIR_SYSTEM
				+ "\n\n校验错误:\n- " + String.join("\n- ", errors)
				+ "\n\n你上次的结构化尾巴:\n" + failedTail;
	}

	/**
	 * 解析本局注入语境(ADR-013 Slice D 治「event-loop 对融合失明」):
	 * <ul>
	 *   <li><b>融合局</b>({@code archetypes} 长度 2 且组合已登记)→ 融合轴集(复用
	 *       {@link ArchetypeRegistry#fusedAxes},与播种同一真理源,道心换皮自动在场)+ 融合模式名
	 *       + per-combo 意象换皮 + 融合指令;</li>
	 *   <li><b>单体局</b>(长度 1 / 未登记组合回落 {@code [0]})→ 该模式元数据,prompt 逐字不变;</li>
	 *   <li><b>兜底</b>:{@code [0]} 未激活(或缺失)回落 rules_creepy(回合发生时世界已为激活模式生成)。</li>
	 * </ul>
	 */
	private TurnContext resolveContext(Engine engine) {
		var archeNode = engine.world().path("archetypes");
		String first = archeNode.path(0).asString("");
		if (archeNode.size() == 2) {
			String second = archeNode.path(1).asString("");
			if (registry.isFusionSupported(first, second)) {
				String modeName = registry.meta(first).displayName() + " × " + registry.meta(second).displayName()
						+ "(融合世界)";
				return new TurnContext(modeName, registry.fusedAxes(first, second),
						FUSION_IMAGERY.getOrDefault(first + "×" + second, Map.of()), true);
			}
		}
		ArchetypeMeta meta = registry.isActive(first) ? registry.meta(first) : registry.meta("rules_creepy");
		return new TurnContext(meta.displayName(), meta.attributes(), Map.of(), false);
	}

	/** 「- key(中文名):意象」逐轴。融合局的换皮轴用 per-combo override 口吻(如 san=道心)。 */
	private static String imageryBlock(TurnContext ctx) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : ctx.axes()) {
			String img = ctx.imageryOverrides().getOrDefault(a.key(),
					IMAGERY.getOrDefault(a.key(), a.displayName() + "的具体感受"));
			sb.append("- ").append(a.key()).append("(").append(a.displayName()).append("):").append(img).append("\n");
		}
		return sb.toString().stripTrailing();
	}

	/** 禁用字段名清单:各数值轴 key + 引擎内部命名。 */
	private static String forbiddenNames(List<AttributeAxis> axes) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : axes) {
			sb.append(a.key()).append(" / ");
		}
		return sb.append("stateUpdate / timeline").toString();
	}

	/** 「XX值」直呼示例:取首个数值轴 key。 */
	private static String exampleForbiddenKey(List<AttributeAxis> axes) {
		return axes.isEmpty() ? "hp" : axes.get(0).key();
	}

	/** stateUpdate 数值轴字段:`"hp": <0-100>, "san": <0-100>`。 */
	private static String stateUpdateAxes(List<AttributeAxis> axes) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : axes) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append('"').append(a.key()).append("\": <0-100 绝对值>");
		}
		return sb.toString();
	}

	/**
	 * 特殊行为轴维护提醒:列出有特殊逐回合行为的轴 + 提示;无则空串。涵盖衰减型(末日 hunger)、
	 * 累积型/联动型(克苏鲁 knowledge 求知则涨、且越高 san 流失越快)——口径中立,不预设「衰减」单一方向。
	 */
	private static String behaviorReminder(List<AttributeAxis> axes) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : axes) {
			if (a.behaviorHint() != null) {
				sb.append("  · ").append(a.key()).append("(").append(a.displayName()).append("):")
						.append(a.behaviorHint()).append("\n");
			}
		}
		if (sb.length() == 0) {
			return "";
		}
		return "  注意以下数值轴有特殊的逐回合变化规律,每回合都要在 stateUpdate 严格按提示体现"
				+ "(给变化后的新绝对值,与历史不矛盾):\n"
				+ sb.toString().stripTrailing();
	}

	/**
	 * 各轴当前所处的行为档(#3 数值行为化 Slice B,每回合按当前绝对值算)。<b>只送当前档、不送整张表</b>(守成本):
	 * 逐轴输出「- key(中文名)当前【label】:narrationHint」,喂模型让本回合叙事跟着状态走。仅对有行为档且当前
	 * state 含该轴的轴注入;无档/缺值 → 跳过,全无 → 空串(不注入空块)。<b>无泄露</b>:档 label/narrationHint 是
	 * 我方手写的叙事色彩文本,不含 isTrue/hiddenLogic(守三视图消毒,view #2 模型侧)。
	 */
	private static String currentBandBlock(List<AttributeAxis> axes, Engine engine) {
		StringBuilder sb = new StringBuilder();
		for (AttributeAxis a : axes) {
			if (!engine.attributes().containsKey(a.key())) {
				continue; // 当前 state 未含该轴 → 不臆造档
			}
			AttributeAxis.Band band = a.resolveBand((int) Math.round(engine.attribute(a.key())));
			if (band != null) {
				sb.append("- ").append(a.key()).append("(").append(a.displayName()).append(")当前处于【")
						.append(band.label()).append("】档:").append(band.narrationHint()).append("\n");
			}
		}
		if (sb.length() == 0) {
			return "";
		}
		return "\n\n各数值轴当前所处的状态档(本回合开始时;叙事须自然体现对应状态,化入散文而非照搬档名):\n"
				+ sb.toString().stripTrailing();
	}
}
