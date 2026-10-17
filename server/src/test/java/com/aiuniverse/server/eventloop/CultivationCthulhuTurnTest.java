package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
	void dayThreeIsLeftForThePlayerToInfer() {
		// (5) 末句:宗门说法是第七日;不替玩家点破第三夜,借星位等现象让玩家自己推出来;当面发生时照实写。
		assertThat(TurnPromptBuilder.FUSION_TURN_COPY.get(COMBO).extraDirectives())
				.contains("宗门对外的说法是封台终仪在封台期满(第七日)举行")
				.contains("不得替玩家点破它在第三夜")
				.contains("只能借星位等看得见的现象让玩家自己推出来")
				.contains("到第三夜它当面发生时照实写");
	}

	@Test
	void timeSlotFollowsTurnNumber() {
		// ADR-035 决策 4 修订段:时段按本回合序号 n(= state.turn + 1)推进,每 4 回合一段。
		Map<Integer, String> expected = new LinkedHashMap<>();
		expected.put(1, "第一日 · 昼");
		expected.put(4, "第一日 · 昼");
		expected.put(5, "第一日 · 夜");
		expected.put(13, "第二日 · 夜");
		expected.put(16, "第二日 · 夜");
		expected.put(17, "第三日 · 昼");
		expected.put(23, "第三日 · 夜");
		expected.put(24, "第三日 · 夜");
		expected.put(25, "第三日 · 夜");
		for (Map.Entry<Integer, String> e : expected.entrySet()) {
			int n = e.getKey();
			String p = builder.buildTurnPrompt(engine(10, n - 1), "A", "退到台阶以下观星");
			assertThat(p).as("第 %d 回合", n)
					.contains("【本回合时段】" + e.getValue() + "(本局第 " + n + " 回合)。叙事与 timeline 都按这个时段写,不要提前或拖后。")
					.endsWith("\n\n请推进第 " + n + " 回合。玩家本回合选择的行动:A · 退到台阶以下观星");
		}
		// 边界:4 与 5、16 与 17 分属不同时段。
		assertThat(slotOf(4)).isNotEqualTo(slotOf(5));
		assertThat(slotOf(16)).isNotEqualTo(slotOf(17));
	}

	@Test
	void riteEveRiteAndAfterCarryTheirLines() {
		String eve = "子时将至:本回合把场面推到封台终仪前一刻;本回合末的选项必须给出终仪当场的抉择——其中要有当面让运,也要有避开。";
		String rite = "本回合即封台终仪:按玩家所选当回合裁决,并在本回合给出结局(裁决三路见附加指令 (7))。";
		String after = "封台终仪已过:本回合必须给出结局。";

		String p23 = builder.buildTurnPrompt(engine(10, 22), "A", "行动");
		assertThat(p23).contains(eve).doesNotContain(rite).doesNotContain(after);
		String p24 = builder.buildTurnPrompt(engine(10, 23), "A", "行动");
		assertThat(p24).contains(rite).doesNotContain(eve).doesNotContain(after);
		for (int n : new int[] { 25, 26, 40 }) {
			assertThat(builder.buildTurnPrompt(engine(10, n - 1), "A", "行动")).as("第 %d 回合", n)
					.contains(after).doesNotContain(eve).doesNotContain(rite);
		}
		// 第 1–22 回合只写日子与昼夜,不提封台终仪(第三夜不替玩家点破)。
		for (int n = 1; n <= 22; n++) {
			assertThat(TurnPromptBuilder.yanqiTurnClock(n)).as("第 %d 回合", n).doesNotContain("封台终仪").doesNotContain("子时");
		}
	}

	@Test
	void clockTextsAndRevisedDirectivesStayLevelOne() throws Exception {
		for (int n = 1; n <= 30; n++) {
			String clock = TurnPromptBuilder.yanqiTurnClock(n);
			assertNone("第 " + n + " 回合时段注入", clock, LEVEL_THREE, "第三级");
			assertNone("第 " + n + " 回合时段注入", clock, LEVEL_TWO_ALL, "第二级");
			assertNone("第 " + n + " 回合时段注入", clock, new String[] { QI_WANG }, "旧称");
		}
		String extra = TurnPromptBuilder.FUSION_TURN_COPY.get(COMBO).extraDirectives();
		assertThat(extra)
				.contains("时段以每回合注入的【本回合时段】为准")
				.contains("且必须与【本回合时段】一致")
				.contains("封台终仪在【本回合时段】注入指定的那一回合发生,并在那一回合当场裁决")
				.contains("让运只在封台终仪当场当面提出、当回合兑现,不得在此前的回合谈妥或约定")
				.doesNotContain("日夜由叙事推进").doesNotContain("时段节奏").doesNotContain("歇息");
		// lockstep:四种注入句在 prompts/event-loop.md 里原样在场。
		String md = Files.readString(Path.of("..", "prompts", "event-loop.md"));
		assertThat(md).contains("【本回合时段】{时段}(本局第 {n} 回合)。叙事与 timeline 都按这个时段写,不要提前或拖后。")
				.contains(TurnPromptBuilder.yanqiTurnClock(23).lines().skip(3).findFirst().orElseThrow())
				.contains(TurnPromptBuilder.yanqiTurnClock(24).lines().skip(3).findFirst().orElseThrow())
				.contains(TurnPromptBuilder.yanqiTurnClock(25).lines().skip(3).findFirst().orElseThrow());
	}

	@Test
	void otherCombosCarryNoTurnClock() {
		assertThat(TurnPromptBuilder.FUSION_TURN_COPY.get("cultivation×rules_creepy").turnClock()).isNull();
		assertThat(TurnPromptBuilder.FUSION_TURN_COPY.get("rules_creepy×apocalypse").turnClock()).isNull();
	}

	private static String slotOf(int n) {
		String clock = TurnPromptBuilder.yanqiTurnClock(n);
		return clock.substring(clock.indexOf("】") + 1, clock.indexOf("("));
	}

	@Test
	void scenesStayInsideTheSect() {
		// (9) 场景范围(Slice D 冒烟:模型自开「崖下」,把真相写成玩家亲眼看见的实物)。
		assertThat(TurnPromptBuilder.FUSION_TURN_COPY.get(COMBO).extraDirectives())
				.contains("(9)【场景范围】场景只在宗门之内、飞升台 / 记档室 / 丹房 / 观星台四处与寻常居所之间往来")
				.contains("无论窥天在哪一档,都不让玩家亲眼看见被带走之后的东西")
				.contains("真相只借印文、星位、丹、记档这些宗门内的物件显出来");
		// 知渔档真相说明只作理解局面之用。
		assertThat(axis("knowledge").resolveBand(61).narrationHint())
				.endsWith("这些只作为你理解局面的说明，不要写成玩家亲眼看见的实物或场所。");
	}

	@Test
	void actionHintsDoNotRestateHiddenLogic() {
		// (10) 选项提示(Slice D 冒烟:开局 hint 复述隐藏判定、泄露守则真假)。
		assertThat(TurnPromptBuilder.FUSION_TURN_COPY.get(COMBO).extraDirectives())
				.contains("(10)【选项提示】availableActions 的 hint 只写这一步行动本身的代价与风险,不复述 hiddenLogic 的说法")
				.contains("不点明哪条守则是真是假,也不用「识破」「推涨」这类说法");
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
		return engine(kuitian, 3);
	}

	/** 同上,state.turn 取给定值(本回合序号 = turn + 1)。 */
	private Engine engine(int kuitian, int turn) {
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
		state.put("turn", turn).put("status", "ongoing").put("timeline", "第一日 · 夜:拓纸上除了「验讫」还有一枚小「留养」")
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
