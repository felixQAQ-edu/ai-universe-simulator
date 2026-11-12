package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.archetype.FusionSummary;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-036 Slice D ·《派单》回合侧:夜班时钟 + 回合指令槽 + 禁用词订正。钉四件事:
 * <ol>
 * <li>既有六个单体世界与三个融合组合的回合提示词,与 Slice D 之前({@code main@fb2aee9})逐字节相同
 * (快照在 {@code golden/turn-prompts/},每个世界 10 个回合号);</li>
 * <li>夜班时钟在第 1、7、8、9、16、17、18、26、27、28 回合的输出符合决策 2 的表;</li>
 * <li>回合指令槽包含提前 / 卡点 / 超时裁决、记忆点原句、收工规则等,且《派单》局的回合提示词里有它和时钟;</li>
 * <li>禁用词扫描(订正后的表:「投诉」移出组织行动)+ {@code prompts/event-loop.md} lockstep。</li>
 * </ol>
 */
class PaidanTurnTest {

	/** 与快照生成时同一组 state.turn(本回合序号 = turn + 1)。 */
	private static final int[] TURNS = { 0, 6, 7, 8, 15, 16, 17, 25, 26, 27 };

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);

	// ① 既有世界逐字节不变 ─────────────────────────────────────────────

	@Test
	void existingWorldsAndFusionsAreByteIdenticalToSnapshotsFromBeforeSliceD() throws IOException {
		int compared = 0;
		for (ArchetypeMeta m : registry.activeMetas()) {
			// 《派单》与《七日开张》不在这组快照里:《派单》另有快照(PaidanPromptSnapshotTest),《七日开张》是新世界。
			if (m.id().equals("cyberpunk") || m.id().equals("time_travel")) {
				continue;
			}
			for (int t : TURNS) {
				String name = m.id() + "_T" + (t + 1) + ".txt";
				// ADR-037:锚定剔除骨架新增的现实边界块后,再与原快照逐字节比(决策 5(c))。
				assertThat(CommonPromptAdditions.stripTurn(
						builder.buildTurnPrompt(engine(List.of(m.id()), m.attributes(), t), "A", "测试行动"), name))
						.as(name).isEqualTo(snapshot(name));
				compared++;
			}
		}
		for (FusionSummary c : registry.listFusionCombos()) {
			for (int t : TURNS) {
				String name = c.host() + "_x_" + c.foreign() + "_T" + (t + 1) + ".txt";
				assertThat(CommonPromptAdditions.stripTurn(builder.buildTurnPrompt(
						engine(List.of(c.host(), c.foreign()), registry.fusedAxes(c.host(), c.foreign()), t), "A", "测试行动"), name))
						.as(name).isEqualTo(snapshot(name));
				compared++;
			}
		}
		assertThat(compared).as("六个单体 + 三个融合,各 10 个回合号").isEqualTo((6 + 3) * TURNS.length);
	}

	// ② 夜班时钟 ───────────────────────────────────────────────────────

	@Test
	void clockMatchesDecision2Table() {
		Map<Integer, String> slot = Map.of(
				1, "第一夜 · 夜班,本班第 1 单",
				7, "第一夜 · 夜班,本班第 7 单",
				8, "第一夜 · 收工",
				9, "第二夜 · 夜班,本班第 1 单",
				16, "第二夜 · 夜班,本班第 8 单",
				17, "第二夜 · 收工",
				18, "第三夜 · 夜班,本班第 1 单",
				26, "第三夜 · 夜班,本班第 9 单,本班最后一单",
				27, "第三夜 · 收工",
				28, "第三夜 · 收工");
		slot.forEach((n, s) -> {
			String head = "\n\n【本回合时段】" + s + "(本局第 " + n + " 回合)。叙事与 timeline 都按这个时段写,不要提前或拖后。";
			String tail = n == 27 ? "\n本回合必须给出结局。好结局重叠时按这个先后取:把话传下去 > 卡点的人 > 交上了 > 差一点。"
					+ TurnPromptBuilder.PAIDAN_FINAL_TABLE
					: n == 28 ? "\n本回合必须给出结局。" : "";
			assertThat(TurnPromptBuilder.paidanTurnClock(n)).as("T" + n).isEqualTo(head + tail);
		});
	}

	@Test
	void clockNeverLeaksEndingConditionsAndOnlyTurn26IsLastOrder() {
		for (int n = 1; n <= 40; n++) {
			String c = TurnPromptBuilder.paidanTurnClock(n);
			if (n != 27) { // 订正二:只有第 27 回合带结局判定表
				assertThat(c).as("T" + n).doesNotContain("画像").doesNotContain("评分").doesNotContain("余钱")
						.doesNotContain("体力").doesNotContain("判定");
			}
			assertThat(c).as("T" + n).doesNotContain("became_benchmark").doesNotContain("isTrue");
			assertThat(c.contains("本班最后一单")).as("T" + n).isEqualTo(n == 26);
			assertThat(c.contains("本回合必须给出结局")).as("T" + n).isEqualTo(n >= 27);
			assertThat(c.contains("好结局重叠")).as("T" + n).isEqualTo(n == 27);
		}
		assertThat(TurnPromptBuilder.PAIDAN_FINAL_TURN).isEqualTo(27);
		// 订正二:第 27 回合的判定表 —— 简报要求的原句 + 与服务端核对同一张表。
		assertThat(TurnPromptBuilder.paidanTurnClock(27))
				.contains("先落本回合 stateUpdate,再按表判定;余钱不到 80 不得写成交上了,叙事要和结局一致。")
				.contains("· 体力或评分归零:倒在楼道里 / 停派;\n· 体力或评分 ≤ 10:好结局都不成立,体力 ≤ 10 写倒在楼道里,否则写停派;差一点不受影响。")
				.contains("· 画像 ≥ 95:成了标杆;")
				.contains("· 把话传下去:体力大于 0,余钱 ≥ 50,已识破至少 2 条以「【群里说】」开头的假规则,且已在叙事中当面告诉了另一个跑腿员;")
				.contains("· 卡点的人:余钱 ≥ 60 且画像 ≤ 40;").contains("· 交上了:余钱 ≥ 80;").contains("· 差一点:以上都不成立。");
	}

	// ③ 回合指令槽 ─────────────────────────────────────────────────────

	@Test
	void directiveCarriesVerdictsMemoryLineOffShiftRuleAndBenchmark() {
		String d = TurnPromptBuilder.PAIDAN_TURN_DIRECTIVE;
		assertThat(d).contains("提前送达:画像上涨").contains("卡点送达:画像不变").contains("超时送达:评分下降,余钱只 +1")
				.contains("开启腿的超频")
				.contains("「已为你优化时限。」").contains("以每回合注入的【记忆点】为准").doesNotContain("向上越过 30 或 60")
				.contains("【收工回合】选项里不出现接单").contains("睡觉回体力")
				.contains("画像达到 95 及以上时,本回合必须提议结局 became_benchmark")
				.contains("快对某个人真的重要").contains("告诉了谁").contains("只能是一个人当面告诉另一个人")
				.contains("只用「跑腿员」").contains("本世界叫《派单》")
				.contains("不写跑腿员之间组织起来、一起去投诉或讨说法的情节")
				.doesNotContain("%");
	}

	/** ADR-036「订正(2026-11-10):赛博朋克底色」:worldview 不进回合提示词,底色只能在回合指令槽里每回合送达。 */
	@Test
	void directiveCarriesCyberBackdropCorrection() {
		String d = TurnPromptBuilder.PAIDAN_TURN_DIRECTIVE;
		assertThat(d).contains("【底色 · 每回合硬要求】叙事里至少出现一样今天还不存在的物件或现象,而且必须和这一单有关")
				.contains("不能只是背景。一样就够,最多两样;不要把下面的取材挨个写进去。"
						+ "你自己的机械腿和义眼不算,要的是这一单的路上、门口或顾客那里的东西。")
				.doesNotContain("以及你自己的机械腿和义眼")
				.doesNotContain("交月供")
				.contains("月供在第三夜收工时和房租一起结算,夜班途中不单独扣。")
				.contains("一双机械腿是平台入职方案装的义体,按月分期;腿记录每一步,画像就长在腿上")
				.contains("派单音直接响在脑子里")
				.contains("余钱 80 是房租加这期义体月供").contains("腿被远程降速:体力掉得更快,爬楼要靠手撑栏杆")
				.contains("黑诊所的义体医生").contains("只写后果,不写方法")
				.contains("把神经接口的算力租给平台过夜").contains("体力回不满")
				.contains("就在叙事开头逐字浮在眼前一次")
				.contains("只是一个一直在跑的账号").contains("系统替你说完你还没说出口的话")
				.contains("还在替某人点餐的语音助手")
				.contains("不写超级企业、政府、反抗者").contains("没有战斗、武器、佣兵;黑客只保留「刷机」")
				.contains("左腿在第六层发出一声短促的提示音,眼前的时限跟着少了四十秒")
				.doesNotContain("头盔").doesNotContain("助力膝").doesNotContain("联网义体").doesNotContain("改装");
	}

	@Test
	void paidanPromptCarriesDirectiveOnceAndClockRightBeforeAdvanceLine() {
		ArchetypeMeta m = registry.meta("cyberpunk");
		for (int t : TURNS) {
			int n = t + 1;
			String p = builder.buildTurnPrompt(engine(List.of("cyberpunk"), m.attributes(), t), "A", "测试行动");
			assertThat(p).as("T" + n).containsOnlyOnce(TurnPromptBuilder.PAIDAN_TURN_DIRECTIVE.strip())
					.containsOnlyOnce("\n\n【本回合时段】")
					.contains(TurnPromptBuilder.paidanTurnClock(n) + TurnPromptBuilder.PAIDAN_LEG_NORMAL + TurnPromptBuilder.PAIDAN_MEMORY_OFF
							+ "\n\n请推进第 " + n + " 回合。");
		}
	}

	// ⑤ 订正二:经济数字、腿况注入、黑诊所口径 ───────────────────────────

	@Test
	void directiveCarriesFixedEconomyNumbers() {
		String d = TurnPromptBuilder.PAIDAN_TURN_DIRECTIVE;
		assertThat(d).contains("提前送达:画像上涨,评分可能上涨,余钱 +6")
				.contains("卡点送达:画像不变,余钱 +4")
				.contains("超时送达:评分下降,余钱只 +1(超时款已经扣过)")
				.contains("偶尔有打赏:余钱另加 +2~4")
				.contains("吃饭 −2,换电 −3,修车 −4~6,找黑诊所(修腿或刷机)−12~18")
				.contains("选了出租算力:余钱 +8~12")
				.contains("每个夜班回合送达都必须按上面的数落余钱,不得不变。")
				.contains("· 体力:每单爬楼:五层及以下 −3,六到九层 −5,九层以上 −7;腿被降速时每单再 −3;开超频再 −4;淋雨、摔车另扣 −5~10。"
						+ "歇脚 +3~5,吃口热的 +6~8;收工好好睡一觉 +25,收工出租算力过夜 +12;")
				.contains("每回合体力按上面的数落,不得大幅偏离。");
		assertThat(registry.meta("cyberpunk").attributes().get(1).behaviorHint())
				.contains("提前 +6,卡点 +4").contains("超时 +1").contains("打赏另加 +2~4").contains("+8~12")
				.contains("吃饭 −2,换电 −3,修车 −4~6,找黑诊所(修腿或刷机)−12~18");
	}

	@Test
	void clinicCannotLiftTheRemoteSlowdown() {
		assertThat(TurnPromptBuilder.PAIDAN_TURN_DIRECTIVE)
				.contains("腿有没有被降速,以每回合注入的【腿况】为准")
				.contains("解除降速只能靠余钱回到 20 以上,没有别的办法")
				.contains("黑诊所的义体医生解不开平台的远程降速,只能修磕坏的腿、能刷机")
				.doesNotContain("能解锁被降速的腿");
		assertThat(registry.meta("cyberpunk").worldview()).contains("他解不开平台的远程降速,降速只有余钱回到 20 以上才解除")
				.doesNotContain("能解锁被降速的腿");
	}

	@Test
	void legStatusFollowsCashAtTurnStart() {
		ArchetypeMeta m = registry.meta("cyberpunk");
		Map<Integer, String> expected = Map.of(0, TurnPromptBuilder.PAIDAN_LEG_SLOWED, 20, TurnPromptBuilder.PAIDAN_LEG_SLOWED,
				21, TurnPromptBuilder.PAIDAN_LEG_NORMAL, 80, TurnPromptBuilder.PAIDAN_LEG_NORMAL);
		expected.forEach((cash, line) -> {
			Engine e = engine(List.of("cyberpunk"), m.attributes(), 10, cash);
			assertThat(TurnPromptBuilder.paidanLegStatus(e)).as("余钱 " + cash).isEqualTo(line);
			String p = builder.buildTurnPrompt(e, "A", "测试行动");
			assertThat(p).as("余钱 " + cash).containsOnlyOnce(line)
					.contains(TurnPromptBuilder.paidanTurnClock(11) + line + TurnPromptBuilder.PAIDAN_MEMORY_OFF + "\n\n请推进第 11 回合。");
		});
		assertThat(TurnPromptBuilder.PAIDAN_LEG_SLOWED)
				.isEqualTo("\n【腿况】这期月供不够,腿处于远程降速:体力掉得更快,爬楼要手撑栏杆。");
		assertThat(TurnPromptBuilder.PAIDAN_LEG_NORMAL).isEqualTo("\n【腿况】腿没有被降速,不得写降速。");
	}

	@Test
	void legStatusNeverAppearsInOtherWorlds() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			if (m.id().equals("time_travel")) {
				// ADR-038 Slice C:《七日开张》有自己的【记忆点】(大娘那句话,见 QiriTurnTest),但没有【腿况】。
				assertThat(builder.buildTurnPrompt(engine(List.of(m.id()), m.attributes(), 10), "A", "测试行动"))
						.as(m.id()).doesNotContain("【腿况】").doesNotContain("已为你优化时限");
			} else if (!m.id().equals("cyberpunk")) {
				assertThat(builder.buildTurnPrompt(engine(List.of(m.id()), m.attributes(), 10), "A", "测试行动"))
						.as(m.id()).doesNotContain("【腿况】").doesNotContain("【记忆点】");
			}
		}
	}

	// ④ 禁用词 + md lockstep ───────────────────────────────────────────

	/** 订正后的表(Slice D):「投诉」不在组织行动里 —— 顾客投诉是本世界机制。 */
	private static final String[] BANNED = {
			"赛博", "赛博朋克", "反乌托邦", "巨型企业",
			"资本", "剥削", "打工人",
			"骑手", "众包",
			"联合", "串联", "罢单", "维权", "讨薪", "上访", "集体停工",
			"拆迁", "清退", "整治", "驱赶", "暂住", "户籍", "执法",
			"%", "成功率" };

	@Test
	void bannedWordsOnlyAppearInTheThreeAllowedPlaces() {
		String positive = positiveText(TurnPromptBuilder.PAIDAN_TURN_DIRECTIVE);
		assertThat(positive).contains("【送达裁决").contains("【题材边界】").contains("这样写:");
		for (String w : BANNED) {
			assertThat(positive).as("正向文字里不得有「" + w + "」").doesNotContain(w);
		}
		// 订正本身:「投诉」不再列为禁用词,只在题材边界那一句约束里出现。
		assertThat(TurnPromptBuilder.PAIDAN_TURN_DIRECTIVE).doesNotContain("讨薪、投诉");
		for (String leg : List.of(TurnPromptBuilder.PAIDAN_LEG_SLOWED, TurnPromptBuilder.PAIDAN_LEG_NORMAL,
				TurnPromptBuilder.PAIDAN_MEMORY_ON, TurnPromptBuilder.PAIDAN_MEMORY_OFF)) {
			for (String w : BANNED) {
				assertThat(leg).as("腿况").doesNotContain(w);
			}
		}
		for (int n = 1; n <= 30; n++) {
			String c = TurnPromptBuilder.paidanTurnClock(n);
			for (String w : BANNED) {
				assertThat(c).as("时钟 T" + n).doesNotContain(w);
			}
		}
	}

	@Test
	void eventLoopMdQuotesDirectiveVerbatim() throws IOException {
		String md = Files.readString(repoRoot().resolve("prompts/event-loop.md"), StandardCharsets.UTF_8);
		assertThat(md).contains("```\n" + TurnPromptBuilder.PAIDAN_TURN_DIRECTIVE.strip() + "\n```");
		assertThat(md).contains("本回合必须给出结局。好结局重叠时按这个先后取:" + TurnPromptBuilder.PAIDAN_GOOD_ENDING_ORDER + "。");
		for (String line : TurnPromptBuilder.PAIDAN_FINAL_TABLE.split("\n")) {
			if (!line.isBlank()) {
				assertThat(md).as("判定表").contains(line);
			}
		}
		assertThat(md).contains(TurnPromptBuilder.PAIDAN_LEG_SLOWED.strip()).contains(TurnPromptBuilder.PAIDAN_LEG_NORMAL.strip())
				.contains(TurnPromptBuilder.PAIDAN_MEMORY_ON.strip()).contains(TurnPromptBuilder.PAIDAN_MEMORY_OFF.strip());
	}

	/** 剔掉三处允许出现禁用词的地方:「【世界名】」一条、「不要这样写」反例行、「【禁用词 · 本世界】」一段。 */
	static String positiveText(String directive) {
		List<String> keep = new ArrayList<>();
		boolean inBanned = false;
		for (String line : directive.split("\n")) {
			String s = line.strip();
			if (s.startsWith("- 【禁用词 · 本世界】")) {
				inBanned = true;
				continue;
			}
			if (inBanned && line.startsWith("  · ")) {
				continue;
			}
			inBanned = false;
			if (s.startsWith("- 【世界名】") || s.startsWith("不要这样写")) {
				continue;
			}
			keep.add(line);
		}
		return String.join("\n", keep);
	}

	// ── 夹具 ────────────────────────────────────────────────────────────

	private String snapshot(String name) throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/golden/turn-prompts/" + name)) {
			assertThat(in).as("快照缺失:" + name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static Path repoRoot() {
		Path p = Path.of("").toAbsolutePath();
		while (p != null && !Files.exists(p.resolve("prompts/event-loop.md"))) {
			p = p.getParent();
		}
		assertThat(p).as("找不到仓库根(prompts/event-loop.md)").isNotNull();
		return p;
	}

	/** 与快照生成时完全相同的最小存档:各轴 50,state.turn = 给定值。 */
	private Engine engine(List<String> ids, List<AttributeAxis> axes, int turn) {
		return engine(ids, axes, turn, 50);
	}

	/** 同上,但各轴初值 = {@code value}(腿况按余钱算,测试用它调余钱)。 */
	private Engine engine(List<String> ids, List<AttributeAxis> axes, int turn, int value) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.put("mode", ids.size() == 1 ? "single" : "hybrid");
		ArrayNode arr = world.putArray("archetypes");
		ids.forEach(arr::add);
		world.putObject("world").put("title", "测").put("background", "测").put("dangerLevel", "high").put("tone", "测");
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		axes.forEach(a -> attrs.put(a.key(), value));
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "x_end").put("title", "终").put("condition", "测")
				.put("outcome", "neutral").put("reached", false);
		ObjectNode p = mapper.createObjectNode();
		p.put("schemaVersion", "0.4");
		p.set("world", world);
		p.putObject("state").put("turn", turn).put("status", "ongoing").put("timeline", "测").put("logSummary", "")
				.putArray("log");
		p.putArray("triggered");
		p.putArray("issues");
		return Engine.restore(p, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
	}
}
