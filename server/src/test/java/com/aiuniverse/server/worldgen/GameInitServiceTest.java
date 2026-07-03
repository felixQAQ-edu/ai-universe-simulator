package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnPhase;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;
import com.aiuniverse.server.moderation.ModerationGateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ②③ GameInitService 播种编排:world-gen parity(复用 8 golden world raw)+ ERROR 路径 +
 * init 消毒断言(独立)+ moderation 接缝被调用 + 生产形态(world 带初始动作 + openingNarrative)。
 *
 * <p>驱动:真实 {@link WorldGenService} + {@link WorldGenPromptBuilder} + {@link GameSessionManager},
 * 只在 LLM 边界用脚本化替身(返回录制 raw / 构造 raw)。零真实 API。
 */
class GameInitServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 返回固定 raw 的 LLM(world-gen 一发即过 → 不触发修复)。 */
	private LlmClient fixedLlm(String raw) {
		return (req, sink) -> sink.onToken(raw);
	}

	/** 计数审核网关(no-op 原样返回 + 记调用次数,证接缝被调用)。 */
	private static final class CountingModeration implements ModerationGateway {
		final AtomicInteger calls = new AtomicInteger();

		@Override
		public String review(String text) {
			calls.incrementAndGet();
			return text;
		}
	}

	private GameInitService initService(LlmClient llm, GameSessionManager sessions, ModerationGateway mod) {
		ArchetypeRegistry registry = new ArchetypeRegistry();
		WorldGenService worldGen = new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper);
		return new GameInitService(worldGen, sessions, mod, registry, mapper);
	}

	// ── world-gen parity:复用 8 golden world raw ──────────────────────────
	@TestFactory
	List<DynamicTest> seedsEachGoldenWorldSanitizedAndPlaying() {
		JsonNode cases = parityCases();
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonNode c : cases) {
			if (!"world".equals(c.get("kind").asString())) {
				continue;
			}
			String raw = c.get("raw").asString();
			JsonNode expected = mapper.readTree(raw);
			tests.add(DynamicTest.dynamicTest(c.get("id").asString(), () -> {
				GameSessionManager sessions = new GameSessionManager(mapper);
				CountingModeration mod = new CountingModeration();
				InitResponse resp = initService(fixedLlm(raw), sessions, mod).init("rules_creepy");

				// (a) init 消毒投影:无任何隐藏字段。
				String worldStr = mapper.writeValueAsString(resp.world());
				assertThat(worldStr)
						.doesNotContain("hiddenLogic").doesNotContain("isTrue")
						.doesNotContain("isCorrect").doesNotContain("groundTruth");
				// 但玩家可见的规则 content 仍在(规则怪谈墙上告示是给玩家看的)。
				assertThat(resp.world().path("rules")).isNotEmpty();
				assertThat(resp.world().path("rules").get(0).path("content").asString()).isNotBlank();

				// (b) PLAYING:会话已建、turn FSM AWAITING_ACTION、数值由 raw 播种。
				GameSession session = sessions.get(resp.saveId());
				assertThat(session).isNotNull();
				assertThat(session.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
				assertThat(session.engine().hp())
						.isEqualTo(expected.path("character").path("attributes").path("hp").asDouble());

				// 8 golden raw 早于本批,无 availableActions → 播种走 FALLBACK(确定性 A/B/C)。
				assertThat(resp.availableActions()).hasSize(3);
				List<String> ids = new ArrayList<>();
				resp.availableActions().forEach(a -> ids.add(a.path("id").asString()));
				assertThat(ids).containsExactly("A", "B", "C");
				// 旧 raw 无 openingNarrative → transient 字段空串(不崩)。
				assertThat(resp.openingNarrative()).isEmpty();
				// 审核接缝被调用(opening + 可见文本)。
				assertThat(mod.calls.get()).isPositive();
			}));
		}
		assertThat(tests).as("应有 8 条 world golden").hasSize(8);
		return tests;
	}

	// ── ERROR 路径:修复仍失败 → WorldGenException,不进 PLAYING,无半残会话 ──
	@Test
	void worldGenUnrecoverableYieldsErrorNoSession() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		// 主调用 + 修复都返非法 → WorldGenService 抛 WorldGenException。
		LlmClient bad = (req, sink) -> sink.onToken("{\"mode\":\"single\"}");

		assertThatThrownBy(() -> initService(bad, sessions, new CountingModeration()).init("rules_creepy"))
				.isInstanceOf(WorldGenException.class);
		// 无半残会话残留:create() 只在 generate() 返回后执行,ERROR 早于播种;计数为 0。
		assertThat(sessions.activeCount()).as("ERROR 不创建任何会话").isZero();
	}

	// ── init 消毒断言(独立硬安全闸):整串无任何隐藏字段名 ──
	@Test
	void initResponseNeverContainsHiddenFields() {
		String raw = productionWorld();
		GameSessionManager sessions = new GameSessionManager(mapper);
		InitResponse resp = initService(fixedLlm(raw), sessions, new CountingModeration()).init("rules_creepy");

		String whole = mapper.writeValueAsString(resp);
		assertThat(whole)
				.doesNotContain("hiddenLogic")
				.doesNotContain("isTrue")
				.doesNotContain("isCorrect")
				.doesNotContain("groundTruth");
	}

	// ── moderation 接缝:被调用(opening + 可见文本)──
	@Test
	void moderationSeamIsInvokedOnVisibleText() {
		CountingModeration mod = new CountingModeration();
		initService(fixedLlm(productionWorld()), new GameSessionManager(mapper), mod).init("rules_creepy");
		assertThat(mod.calls.get()).as("opening + title + background + 规则 content").isGreaterThanOrEqualTo(3);
	}

	// ── 生产形态:world 带合法初始动作 → 采用;openingNarrative 提取且不入持久化 state ──
	@Test
	void productionWorldUsesGivenActionsAndKeepsOpeningTransient() {
		String raw = productionWorld();
		GameSessionManager sessions = new GameSessionManager(mapper);
		InitResponse resp = initService(fixedLlm(raw), sessions, new CountingModeration()).init("rules_creepy");

		// world 给了 2 个动作 → 用它们(非 FALLBACK)。
		assertThat(resp.availableActions()).hasSize(2);
		assertThat(resp.availableActions().get(0).path("id").asString()).isEqualTo("A");
		assertThat(resp.availableActions().get(0).path("text").asString()).isEqualTo("查看告示");

		// openingNarrative 提取下发。
		assertThat(resp.openingNarrative()).isEqualTo("荧光灯忽明忽暗,墙上的告示泛黄。");
		// transient:不进持久化 state —— 消毒投影 world 根无 openingNarrative,也无 availableActions(走 session)。
		assertThat(resp.world().has("openingNarrative")).isFalse();
		assertThat(resp.world().has("availableActions")).isFalse();
		// contextJson(喂模型的视图)也不夹带 openingNarrative。
		assertThat(sessions.get(resp.saveId()).engine().contextJson()).doesNotContain("openingNarrative");
	}

	// ── 多模式(ADR-008 决策 4):archetype 入参真正接受 + 校验 + 数值轴元数据下发 ──

	@Test
	void apocalypseInitSeedsHpHungerAndExposesAxisMeta() {
		String raw = apocalypseWorld();
		GameSessionManager sessions = new GameSessionManager(mapper);
		InitResponse resp = initService(fixedLlm(raw), sessions, new CountingModeration()).init("apocalypse");

		// 数值轴由 raw 播种:hp/hunger(非 hp/san),引擎 key-agnostic 通吃。
		GameSession session = sessions.get(resp.saveId());
		assertThat(session.engine().attribute("hp")).isEqualTo(90.0);
		assertThat(session.engine().attribute("hunger")).isEqualTo(60.0);

		// 响应携带本模式数值轴元数据(前端面板渲染:体力/饥饿,顺序对)。
		List<String> keys = new ArrayList<>();
		List<String> names = new ArrayList<>();
		resp.attributes().forEach(a -> {
			keys.add(a.path("key").asString());
			names.add(a.path("displayName").asString());
		});
		assertThat(keys).containsExactly("hp", "hunger");
		assertThat(names).containsExactly("体力", "饥饿");

		// #3 行为档下发(DTO 扩展,非 wire schema):每轴带 bands:[{min,max,label}] 显式区间(axisRole 无关)。
		var hungerMeta = resp.attributes().get(1);
		assertThat(hungerMeta.path("key").asString()).isEqualTo("hunger");
		var bands = hungerMeta.path("bands");
		assertThat(bands.isArray()).isTrue();
		assertThat(bands.size()).isEqualTo(3);
		List<String> bandLabels = new ArrayList<>();
		bands.forEach(b -> bandLabels.add(b.path("label").asString()));
		// 升序连续覆盖 [0,100]:濒临饿毙 [0,20] / 饥肠辘辘 [21,50] / 饱足 [51,100]。
		assertThat(bandLabels).containsExactly("濒临饿毙", "饥肠辘辘", "饱足");
		assertThat(bands.get(0).path("min").asInt()).isEqualTo(0);
		assertThat(bands.get(0).path("max").asInt()).isEqualTo(20);
		assertThat(bands.get(2).path("min").asInt()).isEqualTo(51);
		assertThat(bands.get(2).path("max").asInt()).isEqualTo(100);
		// 下发只带 min/max/label,绝不带 narrationHint(它仅服务端注入 prompt)。
		bands.forEach(b -> assertThat(b.has("narrationHint")).as("narrationHint 不下发前端").isFalse());

		// 消毒 + 生产形态:无隐藏字段,world 给的初始动作被采用,opening 提取。
		assertThat(mapper.writeValueAsString(resp)).doesNotContain("hiddenLogic").doesNotContain("isTrue");
		assertThat(resp.availableActions()).hasSize(2);
		assertThat(resp.openingNarrative()).isEqualTo("风雪拍打着避难所的铁门。");
	}

	@Test
	void rulesCreepyInitExposesHpSanAxisMeta() {
		InitResponse resp = initService(fixedLlm(productionWorld()), new GameSessionManager(mapper),
				new CountingModeration()).init("rules_creepy");
		List<String> names = new ArrayList<>();
		resp.attributes().forEach(a -> names.add(a.path("displayName").asString()));
		assertThat(names).containsExactly("体力", "理智");
	}

	@Test
	void unknownArchetypeRejectedBeforeWorldGen() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		// 校验早于 world-gen → LLM 不被调用(传抛异常的 llm 证之)。
		LlmClient neverCalled = (req, sink) -> {
			throw new AssertionError("world-gen 不应被调用");
		};
		assertThatThrownBy(() -> initService(neverCalled, sessions, new CountingModeration()).init("not_a_mode"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("非法");
		assertThat(sessions.activeCount()).isZero();
	}

	@Test
	void inactiveButKnownArchetypeRejectedAsNotOpen() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		LlmClient neverCalled = (req, sink) -> {
			throw new AssertionError("未开放模式不应触发 world-gen");
		};
		// life_sim ∈ 枚举但本批未激活 → 未开放(仍 400)。
		assertThatThrownBy(() -> initService(neverCalled, sessions, new CountingModeration()).init("life_sim"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("未开放");
		assertThat(sessions.activeCount()).isZero();
	}

	// ── ADR-013 混合模式:双值 init → 融合轴集播种(host=修仙,修仙×规则怪谈)──────

	@Test
	void fusionInitSeedsFourFusedAxesWithReskinnedDaoxin() {
		String raw = cultivationRulesCreepyFusionWorld();
		GameSessionManager sessions = new GameSessionManager(mapper);
		InitResponse resp = initService(fixedLlm(raw), sessions, new CountingModeration())
				.init(List.of("cultivation", "rules_creepy")); // host 在前

		// 融合轴集(host 在前保序 + 存活外来 san):hp/mana/realm/san 由 raw 播种,引擎 key-agnostic 通吃 4 轴。
		GameSession session = sessions.get(resp.saveId());
		assertThat(session.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
		assertThat(session.engine().attribute("hp")).isEqualTo(70.0);
		assertThat(session.engine().attribute("mana")).isEqualTo(40.0);
		assertThat(session.engine().attribute("realm")).isEqualTo(15.0);
		assertThat(session.engine().attribute("san")).isEqualTo(80.0);

		// attributeMeta 据融合轴集渲染:顺序气血/灵力/境界/道心;san 换皮为「道心」(非规则怪谈「理智」)。
		List<String> keys = new ArrayList<>();
		List<String> names = new ArrayList<>();
		resp.attributes().forEach(a -> {
			keys.add(a.path("key").asString());
			names.add(a.path("displayName").asString());
		});
		assertThat(keys).containsExactly("hp", "mana", "realm", "san");
		assertThat(names).containsExactly("气血", "灵力", "境界", "道心");

		// 道心(san 换皮)带修仙口吻档:清明/动摇/崩缺(区间投影,axisRole 无关)。
		var daoxin = resp.attributes().get(3);
		List<String> bandLabels = new ArrayList<>();
		daoxin.path("bands").forEach(b -> bandLabels.add(b.path("label").asString()));
		assertThat(bandLabels).containsExactly("崩缺", "动摇", "清明"); // 升序区间 [0,20]/[21,50]/[51,100]

		// mode 仍 hybrid、archetypes 两个透传 + 消毒无隐藏字段(假守则的 hiddenLogic 被剥)。
		assertThat(resp.world().path("mode").asString()).isEqualTo("hybrid");
		List<String> arche = new ArrayList<>();
		resp.world().path("archetypes").forEach(a -> arche.add(a.asString()));
		assertThat(arche).containsExactly("cultivation", "rules_creepy");
		assertThat(mapper.writeValueAsString(resp)).doesNotContain("hiddenLogic").doesNotContain("isTrue");
	}

	@Test
	void fusionInitRejectsUnregisteredReversedCombo() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		LlmClient neverCalled = (req, sink) -> {
			throw new AssertionError("未登记融合组合不应触发 world-gen");
		};
		// 反向 host=规则怪谈 未登记 → 400(校验早于 world-gen)。
		assertThatThrownBy(() -> initService(neverCalled, sessions, new CountingModeration())
				.init(List.of("rules_creepy", "cultivation")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("不支持的融合组合");
		assertThat(sessions.activeCount()).isZero();
	}

	@Test
	void fusionInitRejectsMoreThanTwoArchetypes() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		LlmClient neverCalled = (req, sink) -> {
			throw new AssertionError("超过 2 个 archetype 不应触发 world-gen");
		};
		assertThatThrownBy(() -> initService(neverCalled, sessions, new CountingModeration())
				.init(List.of("cultivation", "rules_creepy", "cthulhu")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("仅支持 2 个");
		assertThat(sessions.activeCount()).isZero();
	}

	@Test
	void fusionInitRejectsInactiveOrUnknownInPair() {
		GameSessionManager sessions = new GameSessionManager(mapper);
		LlmClient neverCalled = (req, sink) -> {
			throw new AssertionError("非法/未开放成员不应触发 world-gen");
		};
		// 组合含未开放成员(life_sim)→ 未开放;含未知成员 → 非法。两者均 400、早于 world-gen。
		assertThatThrownBy(() -> initService(neverCalled, sessions, new CountingModeration())
				.init(List.of("cultivation", "life_sim")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("未开放");
		assertThatThrownBy(() -> initService(neverCalled, sessions, new CountingModeration())
				.init(List.of("cultivation", "not_a_mode")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("非法");
		assertThat(sessions.activeCount()).isZero();
	}

	@Test
	void singleValueInitViaListPathUnchanged() {
		// 单值经列表路径(size==1)与旧单值路径同结果(向后兼容,零回归)。
		InitResponse resp = initService(fixedLlm(productionWorld()), new GameSessionManager(mapper),
				new CountingModeration()).init(List.of("rules_creepy"));
		List<String> names = new ArrayList<>();
		resp.attributes().forEach(a -> names.add(a.path("displayName").asString()));
		assertThat(names).containsExactly("体力", "理智");
		assertThat(resp.world().path("mode").asString()).isEqualTo("single");
	}

	// ── 夹具 / 构造 ──────────────────────────────────────────────────────

	/** 末日生存生产形态 world(attributes={hp,hunger},含 hunger 触底结局 + 初始动作 + openingNarrative)。 */
	private String apocalypseWorld() {
		return """
				{"schemaVersion":"0.2","mode":"single","archetypes":["apocalypse"],
				 "world":{"title":"末日避难所","background":"核冬天第 30 天,补给将尽。","dangerLevel":"extreme","tone":"荒凉"},
				 "character":{"attributes":{"hp":90,"hunger":60},"traits":["机警"],"inventory":["罐头"]},
				 "rules":[{"id":1,"content":"夜间不要点火","isTrue":true,"hiddenLogic":"点火引来感染体 hp-20","discovered":false}],
				 "endings":[{"id":"rescued","title":"获救","description":"救援抵达。","condition":"撑到第 40 天","reached":false},
				            {"id":"starved","title":"饿毙","description":"你饿死了。","condition":"hunger 归零","reached":false}],
				 "availableActions":[{"id":"A","text":"清点物资","hint":""},{"id":"B","text":"加固门窗","hint":""}],
				 "openingNarrative":"风雪拍打着避难所的铁门。"}
				""";
	}

	/**
	 * 修仙×规则怪谈融合世界(ADR-013,host=修仙,场景③识海遗蜕):mode=hybrid、archetypes 两个、
	 * 融合轴 {hp,mana,realm,san}、真假守则同墙混合(真心法无 isTrue + 假伪笔 isTrue:false + hiddenLogic)、
	 * 含护道功成(success)与走火入魔(failure)结局。schemaVersion "0.4"。
	 */
	private String cultivationRulesCreepyFusionWorld() {
		return """
				{"schemaVersion":"0.4","mode":"hybrid","archetypes":["cultivation","rules_creepy"],
				 "world":{"title":"识海遗蜕","background":"你的神识误坠一位走火入魔大能的识海遗蜕,石壁上真言与伪笔同墙杂书。","dangerLevel":"extreme","tone":"阴森而缥缈"},
				 "character":{"attributes":{"hp":70,"mana":40,"realm":15,"san":80},"traits":["天灵根","心志坚定"],"inventory":["残卷"]},
				 "rules":[{"id":1,"content":"见石壁血字勿应,应则道心自乱","isTrue":false,"hiddenLogic":"假伪笔:诱人应答,应则 san-15","discovered":false},
				          {"id":2,"content":"运转《清心诀》可稳心神","hiddenLogic":"真心法:每次修习 san 回升、mana-5","discovered":false}],
				 "endings":[{"id":"protect_dao","title":"护道功成","description":"你护住了残魂道统,得其行礼相谢。","condition":"存活且护住残魂,气血未竭、道心未崩","reached":false,"outcome":"success"},
				            {"id":"possessed","title":"走火入魔","description":"道心崩缺,你被识海旧念夺舍。","condition":"道心归零或气血枯竭而神魂尽灭","reached":false,"outcome":"failure"}],
				 "availableActions":[{"id":"A","text":"辨读石壁真言","hint":"读错恐损道心"},{"id":"B","text":"运转清心诀","hint":"耗灵力"}],
				 "openingNarrative":"识海无垠,一堵石壁横亘眼前,真言与伪笔层层叠叠,墨色森森。"}
				""";
	}

	private JsonNode parityCases() {
		try (InputStream in = getClass().getResourceAsStream("/golden/validator-parity.json")) {
			assertThat(in).as("validator-parity 夹具应存在").isNotNull();
			return mapper.readTree(in).get("cases");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** 生产形态 world(带初始 availableActions + openingNarrative,模拟新提示词产出)。 */
	private String productionWorld() {
		return """
				{"schemaVersion":"0.2","mode":"single","archetypes":["rules_creepy"],
				 "world":{"title":"雨夜便利店","background":"凌晨零点,你接替夜班。","dangerLevel":"high","tone":"压抑"},
				 "character":{"attributes":{"hp":80,"san":70},"traits":["警觉"],"inventory":["手电筒"]},
				 "rules":[{"id":1,"content":"不要直视监控","isTrue":true,"hiddenLogic":"直视则 san-10","discovered":false},
				          {"id":2,"content":"红雨衣顾客别收现金","isTrue":false,"hiddenLogic":"假规则","discovered":false}],
				 "endings":[{"id":"survive_dawn","title":"撑到天亮","description":"你活到六点。","condition":"撑到 06:00","reached":false},
				            {"id":"lost_mind","title":"失心","description":"你疯了。","condition":"san<=0","reached":false}],
				 "availableActions":[{"id":"A","text":"查看告示","hint":""},{"id":"B","text":"原地不动","hint":""}],
				 "openingNarrative":"荧光灯忽明忽暗,墙上的告示泛黄。"}
				""";
	}
}
