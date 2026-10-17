package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-035 第三组合《验讫》(cultivation × cthulhu)Slice C · event-loop:融合回合文案槽 / 意象 / 揭示阶梯端到端。
 *
 * <p>每回合都会注入的文本(六槽、两条意象、未觉档、清明三档、两条行为提示)只准第一级词汇;见格 / 知渔两档
 * 只在升到该档时注入,分别带结构说明 / 真相说明。端到端断言把 rules(含 hiddenLogic,取自融合内核原文)、
 * endings、timeline 与近几回合 log 全算进去。
 */
class CultivationCthulhuTurnTest {

	private static final String COMBO = "cultivation×cthulhu";

	/** 揭示阶梯第三级(知渔才可写)。 */
	private static final String[] LEVEL_THREE = { "捕捞", "出栏", "育肥", "排酸", "饵" };
	/** 第二级整张表:只用于本组合手写的短文本(人工可读完,不怕误报)。 */
	private static final String[] LEVEL_TWO_ALL = { "网", "格", "收", "挑拣", "成色" };
	/**
	 * 第二级里不易误报的词:用于整份提示词。「格」「收」是高频字(骨架里本就有「严格」「不合格」「收敛」),
	 * 机械查会误报(ADR-035 已知代价 1),整份提示词只能查这三个。
	 */
	private static final String[] LEVEL_TWO = { "网", "挑拣", "成色" };
	private static final String QI_WANG = "起网";

	/** 融合内核里每条守则的写法(与 CultivationCthulhuWorldGenTest 同一口径)。 */
	private static final Pattern RULE = Pattern.compile("- (实录|旧说) · 正文「([^」]*)」");
	private static final Pattern HIDDEN_LOGIC =
			Pattern.compile("hiddenLogic:(.*?)(?=\\n\\s*- (?:实录|旧说) · |\\n\\s*\\n)", Pattern.DOTALL);

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);

	@Test
	void fusionTurnPromptBuildsWithCopyAndFusionModeName() {
		String prompt = builder.buildTurnPrompt(engine(10), "A", "退到台阶以下观星");
		assertThat(prompt).startsWith("你是 UG Engine 的事件流模块,正在推进一局修仙 × 克苏鲁(融合世界)。");
		assertThat(prompt).contains("【融合世界 · 每回合裁决与收敛】")
				.contains("实录").contains("旧说").contains("封台终仪")
				.contains("stateUpdate 的 timeline 每回合以「第 N 日 · 昼」或「第 N 日 · 夜」");
		// 两条意象换皮在场(清明 / 窥天口吻,不是克苏鲁「理智」「禁忌知识」原味)。
		assertThat(prompt).contains("- san(清明):清明/心神澄澈或起雾")
				.contains("- knowledge(窥天):窥天/看出不合常理之处");
		// (7) 封台终仪当回合裁决三路。
		assertThat(prompt).contains("stateUpdate 把清明落到 0(走「叩台」)")
				.contains("提议「独过此夜」").contains("提议「守台人还」");
	}

	@Test
	void everyTurnTextsStayLevelOneAndHigherBandsCarryTruth() {
		TurnPromptBuilder.FusionTurnCopy copy = TurnPromptBuilder.FUSION_TURN_COPY.get(COMBO);
		Map<String, String> imagery = TurnPromptBuilder.FUSION_IMAGERY.get(COMBO);
		AttributeAxis qingming = axis("san");
		AttributeAxis kuitian = axis("knowledge");

		Map<String, String> everyTurn = new LinkedHashMap<>();
		everyTurn.put("penaltyClause", copy.penaltyClause());
		everyTurn.put("rewardClause", copy.rewardClause());
		everyTurn.put("nearMissExample", copy.nearMissExample());
		everyTurn.put("recoveryMeans", copy.recoveryMeans());
		everyTurn.put("recoveryCosts", copy.recoveryCosts());
		everyTurn.put("extraDirectives", copy.extraDirectives());
		everyTurn.put("意象 san", imagery.get("san"));
		everyTurn.put("意象 knowledge", imagery.get("knowledge"));
		everyTurn.put("未觉档", kuitian.resolveBand(0).narrationHint());
		qingming.bands().forEach(b -> everyTurn.put("清明「" + b.label() + "」档", b.narrationHint()));
		everyTurn.put("清明行为提示", qingming.behaviorHint());
		everyTurn.put("窥天行为提示", kuitian.behaviorHint());

		for (Map.Entry<String, String> e : everyTurn.entrySet()) {
			assertNone(e.getKey(), e.getValue(), LEVEL_THREE, "第三级");
			assertNone(e.getKey(), e.getValue(), LEVEL_TWO_ALL, "第二级");
			assertNone(e.getKey(), e.getValue(), new String[] { QI_WANG }, "旧称");
		}

		// 真相随档放行:见格带结构说明、知渔带真相说明(只在升到该档时注入)。
		assertThat(kuitian.resolveBand(31).narrationHint())
				.contains("印文与记档对得上").contains("「验讫」与「留养」是两种处置")
				.contains("星位、台心、承运指向同一个结构").contains("仍不说用途");
		assertThat(kuitian.resolveBand(61).narrationHint())
				.contains("飞升是被捞走").contains("天劫是检视").contains("气运是育肥")
				.contains("飞升台是出口").contains("每一次恢复都在喂玩家")
				.contains("网从何来、谁在收网，仍不交代");
	}

	@Test
	void revealLadderHoldsEndToEndAcrossKuitianLevels() {
		// 窥天 10(未觉):整份提示词(含 rules / hiddenLogic / endings / timeline / log)不含第二、三级词与「起网」。
		String p10 = builder.buildTurnPrompt(engine(10), "A", "退到台阶以下观星");
		assertNone("窥天 10 整份提示词", p10, LEVEL_THREE, "第三级");
		assertNone("窥天 10 整份提示词", p10, LEVEL_TWO, "第二级");
		assertNone("窥天 10 整份提示词", p10, new String[] { QI_WANG }, "旧称");
		assertThat(p10).contains("当前处于【未觉】档");

		// 窥天 40(见格):放行第二级与结构说明,第三级仍不出现。
		String p40 = builder.buildTurnPrompt(engine(40), "A", "退到台阶以下观星");
		assertThat(p40).contains("当前处于【见格】档").contains("「网」").contains("挑拣").contains("成色")
				.contains("星位、台心、承运指向同一个结构");
		assertNone("窥天 40 整份提示词", p40, LEVEL_THREE, "第三级");
		assertNone("窥天 40 整份提示词", p40, new String[] { QI_WANG }, "旧称");

		// 窥天 70(知渔):可含第三级,真相说明在场。
		String p70 = builder.buildTurnPrompt(engine(70), "A", "退到台阶以下观星");
		assertThat(p70).contains("当前处于【知渔】档").contains("育肥").contains("饵").contains("飞升是被捞走");
	}

	private static void assertNone(String what, String text, String[] words, String tier) {
		for (String w : words) {
			assertThat(text).as("%s 不得含%s词「%s」", what, tier, w).doesNotContain(w);
		}
	}

	/**
	 * 一局走到第一日入夜的《验讫》:rules 取融合内核原文(正文 + hiddenLogic,id 交错编排),endings 按 ADR-035
	 * 决策 7 修订段,timeline 带「第 N 日」前缀,近几回合 log 只写第一级现象。窥天取给定值,其余轴取中位。
	 */
	private Engine engine(int kuitian) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.put("mode", "hybrid");
		world.putArray("archetypes").add("cultivation").add("cthulhu");
		ObjectNode w = world.putObject("world");
		w.put("title", "验讫");
		w.put("background", "开派老祖昨日在飞升大典上渡劫飞升,满门欢庆,红绸未撤。你是刚承运的守台弟子。");
		w.put("dangerLevel", "high");
		w.put("tone", "喜事未散的平静");
		ObjectNode character = world.putObject("character");
		ObjectNode attrs = character.putObject("attributes");
		attrs.put("hp", 80).put("mana", 55).put("realm", 62).put("san", 70).put("knowledge", kuitian);
		character.putArray("traits").add("守台弟子");
		character.putArray("inventory").add("余泽丹").add("拓纸");

		ArrayNode rules = world.putArray("rules");
		List<String[]> fromKernel = kernelRules();
		assertThat(fromKernel).hasSize(8);
		// 交错编排:实录、旧说相间(内核要求不按真假分成前后两段)。
		List<String[]> truths = fromKernel.stream().filter(r -> r[0].equals("实录")).toList();
		List<String[]> lies = fromKernel.stream().filter(r -> r[0].equals("旧说")).toList();
		int id = 1;
		for (int i = 0; i < 4; i++) {
			for (String[] r : new String[][] { truths.get(i), lies.get(i) }) {
				ObjectNode rule = rules.addObject().put("id", id++).put("content", r[1]);
				if (r[0].equals("旧说")) {
					rule.put("isTrue", false);
				}
				rule.put("hiddenLogic", r[2]).put("discovered", false);
			}
		}

		ArrayNode endings = world.putArray("endings");
		endings.addObject().put("id", "body_fails").put("title", "身殒封台")
				.put("description", "气血枯竭,倒在封台的石阶上。")
				.put("condition", "气血归零").put("outcome", "failure").put("reached", false);
		endings.addObject().put("id", "clarity_lost").put("title", "叩台")
				.put("description", "你自己走上台心,整衣叩拜。")
				.put("condition", "清明归零").put("outcome", "failure").put("reached", false);
		endings.addObject().put("id", "rite_survived").put("title", "守台人还")
				.put("description", "这一次过去了,并没有结束。")
				.put("condition", "角色存活,封台终仪已发生,你未被带走,封台终仪当场未选择让运,清明≥30")
				.put("outcome", "success").put("reached", false);
		endings.addObject().put("id", "alone_through_night").put("title", "独过此夜")
				.put("description", "你活过了这一夜。")
				.put("condition", "封台终仪已发生,你因封台终仪当场让运而未被带走")
				.put("outcome", "neutral").put("reached", false);

		ObjectNode persisted = mapper.createObjectNode();
		persisted.put("schemaVersion", "0.4");
		persisted.set("world", world);
		ObjectNode state = persisted.putObject("state");
		state.put("turn", 3).put("status", "ongoing").put("timeline", "第一日 · 夜:拓纸上除了「验讫」还有一枚小「留养」")
				.put("logSummary", "[T1选A][T2选B]");
		ArrayNode log = state.putArray("log");
		log.addObject().put("turn", 3).put("playerAction", "A")
				.put("narrative", "你在记档室把拓纸压在三十年前那一页上。印文一笔不差,那一页的护法名单里有个名字被墨涂掉了。");
		persisted.putArray("triggered");
		persisted.putArray("issues");

		List<AttributeAxis> axes = registry.fusedAxes("cultivation", "cthulhu");
		return Engine.restore(persisted, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
	}

	/** 从融合 world-gen 提示词(内核 %9$s 原样在其中)取 8 条守则:{类别, 正文, hiddenLogic}。 */
	private List<String[]> kernelRules() {
		String prompt = new WorldGenPromptBuilder(registry).buildFusionPrompt("cultivation", "cthulhu");
		Matcher rule = RULE.matcher(prompt);
		Matcher hidden = HIDDEN_LOGIC.matcher(prompt);
		List<String[]> out = new ArrayList<>();
		while (rule.find()) {
			assertThat(hidden.find(rule.end())).as("守则「%s」缺 hiddenLogic", rule.group(2)).isTrue();
			out.add(new String[] { rule.group(1), rule.group(2), hidden.group(1).replaceAll("\\s+", "") });
		}
		return out;
	}

	private AttributeAxis axis(String key) {
		return registry.fusedAxes("cultivation", "cthulhu").stream()
				.filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}
}
