package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.LlmException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-036「订正(2026-11-10 二)」订正 1 ·《派单》结局核对。走真实落账路径 {@code EventLoopService.landSettled}
 * (核对 → {@code engine.apply}),断言引擎最终落下的结局。判定表每一条分支各至少一例,含冒烟里两局的原样复现。
 *
 * <p>夹具规则表(决定「已识破的假【群里说】」条数):
 * #1 群里说 · 假 · 已识破;#2 群里说 · 假 · 未识破;#3 服务守则 · 假 · 已识破;#4 群里说 · 真 · 已识破。
 * 所以开局时只有 #1 一条算数,本回合再识破 #2 才够 2 条。
 */
class PaidanEndingCheckTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final EventLoopService service = new EventLoopService((req, sink) -> {
		throw new LlmException("本测试不调模型");
	}, new TurnPromptBuilder(registry), mapper);

	private ListAppender<ILoggingEvent> logs;
	private Logger serviceLogger;

	@BeforeEach
	void captureLogs() {
		serviceLogger = (Logger) LoggerFactory.getLogger(EventLoopService.class);
		logs = new ListAppender<>();
		logs.start();
		serviceLogger.addAppender(logs);
	}

	@AfterEach
	void releaseLogs() {
		serviceLogger.detachAppender(logs);
	}

	// ── #1 致命轴归零:核对直接写失败结局,体力优先 ───────────────────────

	@Test
	void hpZeroIsCollapsedWrittenByTheCheck() {
		Landed r = land(27, start(), Map.of("hp", 0), List.of(), "rent_paid");
		assertThat(r.status).isEqualTo("ended");
		assertThat(r.reached).isEqualTo("collapsed_in_stairwell");
		assertThat(r.issues).anyMatch(s -> s.contains("结局核对改判 rent_paid → collapsed_in_stairwell"))
				.noneMatch(s -> s.contains("ADR-010 gate"));
	}

	@Test
	void ratingZeroIsDispatchStoppedOnAnyTurn() {
		Landed r = land(12, start(), Map.of("rating", 0), List.of(), "almost");
		assertThat(r.status).isEqualTo("ended");
		assertThat(r.reached).isEqualTo("dispatch_stopped");
		assertThat(r.issues).anyMatch(s -> s.contains("结局核对改判 almost → dispatch_stopped"));
	}

	@Test
	void bothAxesAtZeroTakeHpFirst() {
		Landed r = land(12, start(), Map.of("hp", 0, "rating", 0), List.of(), "dispatch_stopped");
		assertThat(r.reached).as("两轴同时 ≤ 0 → 固定取体力").isEqualTo("collapsed_in_stairwell");
		assertThat(r.issues).anyMatch(s -> s.contains("结局核对改判 dispatch_stopped → collapsed_in_stairwell"));
	}

	// ── 与引擎极性 gate(ADR-010,阈值 10)的衔接 ───────────────────────

	@Test
	void finalTurnBothAxesAtMost10WithCash80TakesHpFirst() {
		Landed r = land(27, start(), Map.of("hp", 10, "rating", 8, "cash", 85, "profile", 30), List.of(), "rent_paid");
		assertThat(r.reached).as("两轴都 ≤ 10 且余钱 ≥ 80 → 好结局不成立,取体力").isEqualTo("collapsed_in_stairwell");
		assertThat(r.issues).anyMatch(s -> s.contains("结局核对改判 rent_paid → collapsed_in_stairwell"))
				.as("核对已判,引擎 gate 不再二次改写").noneMatch(s -> s.contains("ADR-010 gate"));
	}

	@Test
	void finalTurnRatingAtMost10IsDispatchStopped() {
		Landed r = land(27, start(), Map.of("rating", 10, "cash", 90, "profile", 30), List.of(), "kept_the_pace");
		assertThat(r.reached).isEqualTo("dispatch_stopped");
		assertThat(r.issues).noneMatch(s -> s.contains("ADR-010 gate"));
	}

	// ── 订正四:第 27 回合只降级好结局,差一点不受濒零影响 ───────────────

	@Test
	void smokeGame3AlmostAtHp6StaysAlmost() {
		// 冒烟第三局原样:T27 体力 6、余钱 62、画像 61,按表是差一点,模型也提议差一点。
		Landed r = land(27, start(), Map.of("hp", 6, "cash", 62, "profile", 61), List.of(), "almost");
		assertThat(r.status).isEqualTo("ended");
		assertThat(r.reached).isEqualTo("almost");
		assertThat(r.issues).noneMatch(s -> s.contains("结局核对改判")).noneMatch(s -> s.contains("ADR-010 gate"));
	}

	@Test
	void finalTurnAlmostIsKeptWhateverHpAndRating() {
		for (Map<String, Integer> upd : List.of(Map.of("hp", 1, "cash", 30), Map.of("rating", 3, "cash", 30),
				Map.of("hp", 10, "rating", 10, "cash", 59, "profile", 90))) {
			Landed r = land(27, start(), upd, List.of(), "rent_paid");
			assertThat(r.reached).as(upd.toString()).isEqualTo("almost");
		}
	}

	@Test
	void finalTurnHp6WithCash85IsCollapsed() {
		Landed r = land(27, start(), Map.of("hp", 6, "cash", 85, "profile", 61), List.of(), "rent_paid");
		assertThat(r.reached).as("按表交上了 → 体力 ≤ 10 降级").isEqualTo("collapsed_in_stairwell");
		assertThat(r.issues).anyMatch(s -> s.contains("结局核对改判 rent_paid → collapsed_in_stairwell"));
	}

	@Test
	void finalTurnRating8WithCash85IsDispatchStopped() {
		Landed r = land(27, start(), Map.of("rating", 8, "cash", 85, "profile", 61), List.of(), "rent_paid");
		assertThat(r.reached).as("按表交上了 → 评分 ≤ 10 降级").isEqualTo("dispatch_stopped");
		assertThat(r.issues).anyMatch(s -> s.contains("结局核对改判 rent_paid → dispatch_stopped"));
	}

	@Test
	void finalTurnPassedItOnAtHp8IsCollapsed() {
		// 把话传下去按表成立(余钱 60、本回合识破 #2 → 2 条),体力 ≤ 10 → 降级。
		Landed r = land(27, start(), Map.of("hp", 8, "cash", 60), List.of(2), "passed_it_on");
		assertThat(r.reached).isEqualTo("collapsed_in_stairwell");
	}

	@Test
	void finalTurnJustAbove10KeepsTheGoodEnding() {
		Landed r = land(27, start(), Map.of("hp", 11, "rating", 11, "cash", 85, "profile", 50), List.of(), "rent_paid");
		assertThat(r.reached).isEqualTo("rent_paid");
	}

	@Test
	void beforeTurn27PassedItOnIsHeldWhenHpOrRatingAtMost10() {
		// 数值其余都成立(余钱 60、本回合识破 #2 → 2 条),只因体力 ≤ 10 不收:本回合继续,不被 gate 提前改判结束。
		Landed hpLow = land(10, start(), Map.of("hp", 10, "cash", 60), List.of(2), "passed_it_on");
		assertThat(hpLow.status).isEqualTo("ongoing");
		assertThat(hpLow.reached).isNull();
		assertThat(hpLow.issues).anyMatch(s -> s.contains("结局核对改判 passed_it_on → 无"));
		Landed ratingLow = land(10, start(), Map.of("rating", 9, "cash", 60), List.of(2), "passed_it_on");
		assertThat(ratingLow.status).isEqualTo("ongoing");
		assertThat(ratingLow.reached).isNull();
	}

	// ── #2 画像 ≥ 95:任一回合都是成了标杆 ──────────────────────────────

	@Test
	void profileAtLeast95IsBenchmarkOnAnyTurnEvenWithoutProposal() {
		Landed r = land(5, start(), Map.of("profile", 95), List.of(), null);
		assertThat(r.reached).isEqualTo("became_benchmark");
		assertThat(r.status).isEqualTo("ended");
		assertThat(r.issues).anyMatch(s -> s.contains("结局核对改判 无 → became_benchmark"));
	}

	@Test
	void profileAtLeast95OverridesAGoodEndingOnTheFinalTurn() {
		Landed r = land(27, start(), Map.of("profile", 97, "cash", 90), List.of(), "rent_paid");
		assertThat(r.reached).isEqualTo("became_benchmark");
	}

	// ── #3 第 27 回合及以后:优先序 ─────────────────────────────────────

	@Test
	void passedItOnWhenProposedAndNumbersHold() {
		// 本回合识破 #2 → 假【群里说】已识破 = 2;余钱 55 ≥ 50。
		Landed r = land(27, start(), Map.of("cash", 55, "profile", 30), List.of(2), "passed_it_on");
		assertThat(r.reached).isEqualTo("passed_it_on");
		assertThat(r.issues).noneMatch(s -> s.contains("结局核对改判"));
	}

	@Test
	void passedItOnNeedsTheProposal_numbersAloneFallThroughToTheNextEnding() {
		Landed r = land(27, start(), Map.of("cash", 65, "profile", 30), List.of(2), null);
		assertThat(r.reached).as("没提议把话传下去 → 下一档卡点的人").isEqualTo("kept_the_pace");
	}

	@Test
	void smokeCase_passedItOnWithCash2AndOnlyOneFakeGroupRuleIsRejected() {
		// 冒烟原样:余钱 2、只识破 1 条假【群里说】(#3 是服务守则、#4 是真规则,都不算)。
		Landed r = land(27, start(), Map.of("cash", 2, "profile", 30), List.of(), "passed_it_on");
		assertThat(r.reached).isEqualTo("almost");
		assertThat(r.issues).anyMatch(s -> s.contains("结局核对改判 passed_it_on → almost"));
		assertThat(infoLines()).anyMatch(s -> s.contains("passed_it_on → almost"));
	}

	@Test
	void passedItOnWithEnoughCashButOneFakeGroupRuleFallsThrough() {
		Landed r = land(27, start(), Map.of("cash", 85, "profile", 50), List.of(), "passed_it_on");
		assertThat(r.reached).as("识破数不够 → 按余钱落到交上了").isEqualTo("rent_paid");
	}

	@Test
	void keptThePaceBeatsRentPaid() {
		Landed r = land(27, start(), Map.of("cash", 85, "profile", 40), List.of(), "rent_paid");
		assertThat(r.reached).isEqualTo("kept_the_pace");
	}

	@Test
	void keptThePaceNeedsProfileAtMost40() {
		Landed r = land(27, start(), Map.of("cash", 65, "profile", 41), List.of(), "kept_the_pace");
		assertThat(r.reached).as("画像 41 → 不是卡点的人;余钱 65 不够交上了 → 差一点").isEqualTo("almost");
	}

	@Test
	void rentPaidWhenCashAtLeast80_proposedOrNot() {
		assertThat(land(27, start(), Map.of("cash", 80, "profile", 50), List.of(), "rent_paid").reached)
				.isEqualTo("rent_paid");
		Landed none = land(27, start(), Map.of("cash", 80, "profile", 50), List.of(), null);
		assertThat(none.reached).as("第 27 回合模型没提议也要给结局").isEqualTo("rent_paid");
		assertThat(none.issues).anyMatch(s -> s.contains("结局核对改判 无 → rent_paid"));
	}

	@Test
	void smokeCase_rentPaidWithCash22BecomesAlmost() {
		Landed r = land(27, start(), Map.of("cash", 22, "profile", 50), List.of(), "rent_paid");
		assertThat(r.reached).isEqualTo("almost");
		assertThat(r.issues).anyMatch(s -> s.contains("结局核对改判 rent_paid → almost"));
		assertThat(infoLines()).anyMatch(s -> s.contains("《派单》结局核对改判:rent_paid → almost"));
	}

	@Test
	void afterTurn27TheTableStillApplies() {
		Landed r = land(29, start(), Map.of("cash", 30, "profile", 50), List.of(), null);
		assertThat(r.reached).isEqualTo("almost");
		assertThat(r.status).isEqualTo("ended");
	}

	@Test
	void numbersAreTheSettledOnes_missingKeyKeepsCurrentAndOverflowIsClamped() {
		Map<String, Integer> rich = new LinkedHashMap<>(start());
		rich.put("cash", 85);
		rich.put("profile", 50);
		assertThat(land(27, rich, Map.of(), List.of(), null).reached).as("stateUpdate 没给余钱 → 取当前 85")
				.isEqualTo("rent_paid");
		assertThat(land(27, start(), Map.of("cash", 79.5, "profile", 50), List.of(), "rent_paid").reached)
				.as("79.5 < 80").isEqualTo("almost");
	}

	// ── #4 第 27 回合之前 ──────────────────────────────────────────────

	@Test
	void beforeTurn27ProposalsThatDoNotHoldAreDropped() {
		Map<String, Map<String, Number>> cases = new LinkedHashMap<>();
		cases.put("rent_paid", Map.of("cash", 90, "profile", 50));
		cases.put("kept_the_pace", Map.of("cash", 70, "profile", 20));
		cases.put("almost", Map.of());
		cases.put("became_benchmark", Map.of("profile", 80));
		cases.put("collapsed_in_stairwell", Map.of("hp", 30));
		cases.put("dispatch_stopped", Map.of("rating", 30));
		cases.forEach((proposed, upd) -> {
			Landed r = land(26, start(), upd, List.of(), proposed);
			assertThat(r.status).as(proposed).isEqualTo("ongoing");
			assertThat(r.reached).as(proposed).isNull();
			assertThat(r.issues).as(proposed).anyMatch(s -> s.contains("结局核对改判 " + proposed + " → 无"));
		});
	}

	@Test
	void beforeTurn27PassedItOnIsAcceptedOnlyWhenNumbersHold() {
		assertThat(land(10, start(), Map.of("cash", 50), List.of(2), "passed_it_on").reached)
				.isEqualTo("passed_it_on");
		Landed tooPoor = land(10, start(), Map.of("cash", 49), List.of(2), "passed_it_on");
		assertThat(tooPoor.reached).isNull();
		assertThat(tooPoor.status).isEqualTo("ongoing");
		Landed tooFew = land(10, start(), Map.of("cash", 60), List.of(), "passed_it_on");
		assertThat(tooFew.reached).isNull();
	}

	@Test
	void ordinaryTurnWithoutProposalIsUntouched() {
		Landed r = land(6, start(), Map.of("cash", 40), List.of(), null);
		assertThat(r.status).isEqualTo("ongoing");
		assertThat(r.reached).isNull();
		assertThat(r.issues).noneMatch(s -> s.contains("结局核对"));
		assertThat(infoLines()).noneMatch(s -> s.contains("结局核对"));
	}

	// ── 边界 ──────────────────────────────────────────────────────────

	@Test
	void verdictMissingFromThisWorldsEndingPoolLeavesParsedUntouched() {
		Engine engine = paidanEngine(26, start(), List.of("kept_the_pace", "almost"));
		ObjectNode parsed = parsed(Map.of("cash", 85, "profile", 50), List.of(), null);
		service.landSettled(new GameSession("s", engine, actions()), parsed, "A", null, leak -> {
		});
		assertThat(engine.status()).as("判定 rent_paid 不在池里 → 不改写,退回引擎原行为").isEqualTo("ongoing");
		assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("不在本局结局池里"));
	}

	@Test
	void otherWorldsKeepTheModelsEndingUntouched() {
		List<AttributeAxis> axes = registry.meta("rules_creepy").attributes();
		Map<String, Integer> attrs = new LinkedHashMap<>();
		axes.forEach(a -> attrs.put(a.key(), 50));
		Engine engine = engine(List.of("rules_creepy"), axes, 3, attrs, List.of("survive_dawn"), rules());
		ObjectNode parsed = mapper.createObjectNode();
		parsed.put("narrative", "测");
		parsed.putObject("stateUpdate").put("timeline", "t");
		parsed.putArray("triggeredRuleIds");
		parsed.putArray("discoveredRuleIds");
		parsed.set("availableActions", actions());
		parsed.putObject("ending").put("reached", true).put("id", "survive_dawn");
		service.landSettled(new GameSession("s", engine, actions()), parsed, "A", null, leak -> {
		});
		assertThat(engine.status()).isEqualTo("ended");
		assertThat(reachedOf(engine)).isEqualTo("survive_dawn");
		assertThat(engine.issues()).noneMatch(s -> s.contains("结局核对"));
	}

	@Test
	void checkReturnsNullWhenTheFourAxesAreMissing() {
		List<AttributeAxis> axes = registry.meta("rules_creepy").attributes();
		Map<String, Integer> attrs = new LinkedHashMap<>();
		axes.forEach(a -> attrs.put(a.key(), 50));
		Engine engine = engine(List.of("cyberpunk"), axes, 30, attrs, List.of("almost"), rules());
		ObjectNode parsed = parsed(Map.of(), List.of(), "almost");
		assertThat(PaidanEndingCheck.check(engine, parsed)).isNull();
		assertThat(parsed.path("ending").path("id").asString()).isEqualTo("almost");
	}

	// ── 夹具 ──────────────────────────────────────────────────────────

	private record Landed(String status, String reached, List<String> issues) {
	}

	/** 本回合序号 n(= state.turn + 1)。 */
	private Landed land(int n, Map<String, Integer> start, Map<String, ? extends Number> upd, List<Integer> discover,
			String proposed) {
		Engine engine = paidanEngine(n - 1, start, PAIDAN_ENDINGS);
		service.landSettled(new GameSession("save-paidan", engine, actions()), parsed(upd, discover, proposed), "A",
				null, leak -> {
				});
		return new Landed(engine.status(), reachedOf(engine), engine.issues());
	}

	private static Map<String, Integer> start() {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put("hp", 60);
		m.put("cash", 30);
		m.put("rating", 60);
		m.put("profile", 20);
		return m;
	}

	private static final List<String> PAIDAN_ENDINGS = List.of("rent_paid", "kept_the_pace", "passed_it_on",
			"collapsed_in_stairwell", "dispatch_stopped", "became_benchmark", "almost");

	private static final Map<String, String[]> ENDING_META = Map.of(
			"rent_paid", new String[] { "交上了", "success", "第三夜收工时,体力大于 0,余钱 ≥ 80" },
			"kept_the_pace", new String[] { "卡点的人", "success", "第三夜收工时,体力大于 0,余钱 ≥ 60 且画像 ≤ 40" },
			"passed_it_on", new String[] { "把话传下去", "success", "识破 2 条假群里说并当面告诉另一个跑腿员,余钱 ≥ 50" },
			"collapsed_in_stairwell", new String[] { "倒在楼道里", "failure", "体力归零" },
			"dispatch_stopped", new String[] { "停派", "failure", "评分归零" },
			"became_benchmark", new String[] { "成了标杆", "failure", "画像 ≥ 95" },
			"almost", new String[] { "差一点", "neutral", "第三夜收工时,以上结局都不满足" },
			"survive_dawn", new String[] { "撑到天亮", "success", "撑到 06:00" });

	private Engine paidanEngine(int turn, Map<String, Integer> attrs, List<String> endingIds) {
		return engine(List.of("cyberpunk"), registry.meta("cyberpunk").attributes(), turn, attrs, endingIds, rules());
	}

	private ArrayNode rules() {
		ArrayNode rules = mapper.createArrayNode();
		rule(rules, 1, "【群里说】换电柜第三格从来不卡", false, true);
		rule(rules, 2, "【群里说】凌晨两点后差评不算数", false, false);
		rule(rules, 3, "【服务守则】超时三次自动停派", false, true);
		rule(rules, 4, "【群里说】提前送达会被记住", true, true);
		return rules;
	}

	private static void rule(ArrayNode rules, int id, String content, boolean isTrue, boolean discovered) {
		rules.addObject().put("id", id).put("content", content).put("isTrue", isTrue).put("hiddenLogic", "测")
				.put("discovered", discovered);
	}

	private Engine engine(List<String> ids, List<AttributeAxis> axes, int turn, Map<String, Integer> attrs,
			List<String> endingIds, ArrayNode rules) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.put("mode", ids.size() == 1 ? "single" : "hybrid");
		ArrayNode arr = world.putArray("archetypes");
		ids.forEach(arr::add);
		world.putObject("world").put("title", "派单").put("background", "测").put("dangerLevel", "high").put("tone", "测");
		ObjectNode a = world.putObject("character").putObject("attributes");
		attrs.forEach(a::put);
		world.set("rules", rules);
		ArrayNode endings = world.putArray("endings");
		for (String id : endingIds) {
			String[] m = ENDING_META.get(id);
			endings.addObject().put("id", id).put("title", m[0]).put("outcome", m[1]).put("condition", m[2])
					.put("reached", false);
		}
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

	private ObjectNode parsed(Map<String, ? extends Number> upd, List<Integer> discover, String proposed) {
		ObjectNode parsed = mapper.createObjectNode();
		parsed.put("narrative", "雨还在下。");
		ObjectNode su = parsed.putObject("stateUpdate");
		upd.forEach((k, v) -> su.put(k, v.doubleValue()));
		su.put("timeline", "t");
		parsed.putArray("triggeredRuleIds");
		ArrayNode d = parsed.putArray("discoveredRuleIds");
		discover.forEach(d::add);
		parsed.set("availableActions", actions());
		if (proposed == null) {
			parsed.putNull("ending");
		} else {
			parsed.putObject("ending").put("reached", true).put("id", proposed);
		}
		return parsed;
	}

	private ArrayNode actions() {
		ArrayNode a = mapper.createArrayNode();
		a.addObject().put("id", "A").put("text", "接下一单").put("hint", "时限很紧");
		a.addObject().put("id", "B").put("text", "歇一口气").put("hint", "评分会掉");
		return a;
	}

	private static String reachedOf(Engine engine) {
		for (JsonNode e : engine.world().path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				return e.path("id").asString();
			}
		}
		return null;
	}

	private List<String> infoLines() {
		List<String> out = new ArrayList<>();
		for (ILoggingEvent e : logs.list) {
			if (e.getLevel() == Level.INFO) {
				out.add(e.getFormattedMessage());
			}
		}
		return out;
	}
}
