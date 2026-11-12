package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.persistence.FileSessionStore;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-036 Slice A2 · 固定世界名的服务端覆写(纯机制,不登记任何真实世界)。钉五件事:
 * <ol>
 * <li>未登记的世界,world-gen 结果逐字节不变(Slice B 起真实世界里只有《派单》登记);</li>
 * <li>登记了的世界(测试替身),首过与修复两条成功路径都被覆写;</li>
 * <li>融合局不覆写(两半都登记了也不覆写);</li>
 * <li>读档不覆写;</li>
 * <li>覆写在落盘之前:init 响应、存档文件、重启读回来的标题一致。</li>
 * </ol>
 *
 * <p><b>测试替身</b>:{@link ArchetypeRegistry} 没有测试注入口,替身是一个子类,覆写 {@code meta(id)}
 * 给指定的已激活世界换上带 {@code fixedWorldTitle} 的元数据;其余字段原样,提示词不受影响。
 */
class FixedWorldTitleTest {

	private static final String FIXED = "测试固定名";
	private static final String MODEL_TITLE = "雨夜便利店";

	private final ObjectMapper mapper = new ObjectMapper();

	@TempDir
	Path tmp;

	/** 给 {@code ids} 里的世界登记固定世界名的 registry 替身。 */
	private static ArchetypeRegistry registering(String... ids) {
		Set<String> titled = Set.of(ids);
		return new ArchetypeRegistry() {
			@Override
			public ArchetypeMeta meta(String archetype) {
				ArchetypeMeta m = super.meta(archetype);
				if (!titled.contains(archetype)) {
					return m;
				}
				return new ArchetypeMeta(m.id(), m.displayName(), m.tagline(), m.vibeTag(), m.worldview(),
						m.attributes(), m.ruleForm(), m.rulesCarryTruth(), FIXED);
			}
		};
	}

	private static final class ScriptedLlm implements LlmClient {
		final Deque<String> responses = new ArrayDeque<>();

		ScriptedLlm(String... full) {
			responses.addAll(List.of(full));
		}

		@Override
		public void streamChat(com.aiuniverse.server.llm.ChatRequest request,
				com.aiuniverse.server.llm.TokenStream sink) {
			String r = responses.poll();
			if (r == null) {
				throw new LlmException("脚本耗尽");
			}
			sink.onToken(r);
		}
	}

	/** 最小合法 world;{@code ids} 只写进 archetypes 字段(validateWorld 对 archetype 无知)。 */
	private static String world(List<String> ids) {
		String arr = String.join(",", ids.stream().map(i -> "\"" + i + "\"").toList());
		String mode = ids.size() == 1 ? "single" : "hybrid";
		return """
				{"schemaVersion":"0.4","mode":"%s","archetypes":[%s],
				 "world":{"title":"%s","background":"凌晨零点,你接替夜班。","dangerLevel":"high","tone":"压抑"},
				 "character":{"attributes":{"hp":80,"san":70},"traits":["警觉"],"inventory":["手电筒"]},
				 "rules":[{"id":1,"content":"不要直视监控","isTrue":true,"hiddenLogic":"直视则 san-10","discovered":false},
				          {"id":2,"content":"红雨衣顾客别收现金","isTrue":false,"hiddenLogic":"假规则,收了无事","discovered":false}],
				 "endings":[{"id":"survive_dawn","title":"撑到天亮","condition":"撑到 06:00","outcome":"success","reached":false},
				            {"id":"lost_mind","title":"失心","condition":"理智归零","outcome":"failure","reached":false}],
				 "availableActions":[{"id":"A","text":"查看告示","hint":""},{"id":"B","text":"原地不动","hint":""}],
				 "openingNarrative":"荧光灯忽明忽暗,墙上的告示泛黄。"}
				""".formatted(mode, arr, MODEL_TITLE);
	}

	/** 未过校验的产出(触发一次修复)。 */
	private static final String BROKEN = "{\"schemaVersion\":\"0.4\"}";

	private ObjectNode generate(ArchetypeRegistry registry, List<String> ids, boolean viaRepair) {
		ScriptedLlm llm = viaRepair ? new ScriptedLlm(BROKEN, world(ids)) : new ScriptedLlm(world(ids));
		return new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper).generate(ids);
	}

	// ① 未登记的世界逐字节不变 ───────────────────────────────────────────

	@Test
	void onlyPaidanAndOrdinaryLifeRegisterAFixedTitle() {
		// Slice A2 时真实世界一个都没登记;ADR-036 Slice B 起《派单》(cyberpunk)登记「派单」;
		// ADR-037 决策 3 起《寻常》(life_sim)登记「寻常」;《动物人生》刻意不登记(每一辈子的故事名不同是特色)。
		assertThat(new ArchetypeRegistry().activeMetas()).isNotEmpty().allSatisfy(m -> {
			if (m.id().equals("cyberpunk")) {
				assertThat(m.fixedWorldTitle()).isEqualTo("派单");
			} else if (m.id().equals("life_sim")) {
				assertThat(m.fixedWorldTitle()).isEqualTo("寻常");
			} else if (m.id().equals("time_travel")) { // ADR-038 Slice A:《七日开张》
				assertThat(m.fixedWorldTitle()).isEqualTo("七日开张");
			} else {
				assertThat(m.fixedWorldTitle()).as(m.id()).isNull();
			}
		});
	}

	@Test
	void unregisteredWorldsOutputIsByteIdentical() {
		ArchetypeRegistry production = new ArchetypeRegistry();
		// 登记了别的世界,也不影响未登记的世界。
		ArchetypeRegistry otherRegistered = registering("apocalypse");
		for (ArchetypeMeta m : production.activeMetas()) {
			// 只看未登记的世界(《派单》已登记,见 ②;apocalypse 在替身里登记了)。
			if (m.fixedWorldTitle() != null || m.id().equals("apocalypse")) {
				continue;
			}
			for (boolean repair : new boolean[] { false, true }) {
				List<String> ids = List.of(m.id());
				// 期望 = 模型产出原样(本夹具没有《动物人生》的「撞上」结局,EndingOrder 也不动它)。
				String expected = mapper.writeValueAsString(mapper.readTree(world(ids)));
				for (ArchetypeRegistry r : List.of(production, otherRegistered)) {
					assertThat(mapper.writeValueAsString(generate(r, ids, repair)))
							.as(m.id() + " repair=" + repair).isEqualTo(expected);
				}
			}
		}
	}

	// ② 登记了的世界,两条成功路径都覆写 ─────────────────────────────────

	@Test
	void registeredWorldIsOverwrittenOnFirstPassAndAfterRepair() {
		ArchetypeRegistry registry = registering("rules_creepy");
		List<String> ids = List.of("rules_creepy");
		for (boolean repair : new boolean[] { false, true }) {
			ObjectNode out = generate(registry, ids, repair);
			assertThat(out.path("world").path("title").asString()).as("repair=" + repair).isEqualTo(FIXED);
			// 只动 world.title 一个字段。
			ObjectNode expected = (ObjectNode) mapper.readTree(world(ids));
			((ObjectNode) expected.get("world")).put("title", FIXED);
			assertThat(out).as("repair=" + repair).isEqualTo(expected);
		}
	}

	@Test
	void realPaidanRegistrationIsOverwrittenOnBothPaths() {
		// ADR-036 Slice B:生产 registry(非替身)里的《派单》,首过与修复两条路径都落「派单」。
		for (boolean repair : new boolean[] { false, true }) {
			ObjectNode out = generate(new ArchetypeRegistry(), List.of("cyberpunk"), repair);
			assertThat(out.path("world").path("title").asString()).as("repair=" + repair).isEqualTo("派单");
		}
	}

	// ③ 融合局不覆写 ──────────────────────────────────────────────────────

	@Test
	void fusionWorldIsNeverOverwrittenEvenWhenBothHalvesAreRegistered() {
		ArchetypeRegistry registry = registering("cultivation", "rules_creepy", "cthulhu", "apocalypse");
		List<com.aiuniverse.server.archetype.FusionSummary> combos = new ArchetypeRegistry().listFusionCombos();
		assertThat(combos).isNotEmpty();
		combos.forEach(c -> {
			List<String> ids = List.of(c.host(), c.foreign());
			String expected = mapper.writeValueAsString(mapper.readTree(world(ids)));
			for (boolean repair : new boolean[] { false, true }) {
				ObjectNode out = generate(registry, ids, repair);
				assertThat(out.path("world").path("title").asString()).as(c.key()).isEqualTo(MODEL_TITLE);
				assertThat(mapper.writeValueAsString(out)).as(c.key() + " repair=" + repair).isEqualTo(expected);
			}
		});
	}

	// ④ 读档不覆写 ────────────────────────────────────────────────────────

	@Test
	void resumeNeverOverwrites() {
		ArchetypeRegistry registry = registering("rules_creepy");
		Path dir = tmp.resolve("store");
		// 存档里是模型起的名(例如登记之前开的局):登记之后回载 / 续局都原样保留。
		GameSessionManager before = new GameSessionManager(mapper, new FileSessionStore(dir.toString(), mapper, registry));
		List<AttributeAxis> axes = registry.resolveAxes(List.of("rules_creepy"));
		ObjectNode world = (ObjectNode) mapper.readTree(world(List.of("rules_creepy")));
		world.remove("openingNarrative");
		world.remove("availableActions");
		before.create("save-old", world, mapper.createArrayNode(), ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));

		GameSessionManager after = new GameSessionManager(mapper, new FileSessionStore(dir.toString(), mapper, registry));
		after.reloadFromStore();
		InitResponse resp = initService(registry, after, new ScriptedLlm()).resume("save-old");
		assertThat(resp.world().path("world").path("title").asString()).isEqualTo(MODEL_TITLE);
		assertThat(after.get("save-old").engine().world().path("world").path("title").asString()).isEqualTo(MODEL_TITLE);
	}

	// ⑤ 覆写在落盘之前 ────────────────────────────────────────────────────

	@Test
	void initResponseSavedFileAndReloadedSessionAllCarryTheFixedTitle() throws IOException {
		ArchetypeRegistry registry = registering("rules_creepy");
		Path dir = tmp.resolve("store");
		GameSessionManager sessions = new GameSessionManager(mapper, new FileSessionStore(dir.toString(), mapper, registry));
		InitResponse init = initService(registry, sessions, new ScriptedLlm(world(List.of("rules_creepy"))))
				.init("rules_creepy");
		assertThat(init.world().path("world").path("title").asString()).isEqualTo(FIXED);

		// 盘上那份(视图 1 全量):标题已是覆写后的值,模型原名一处都不剩。
		List<Path> files;
		try (Stream<Path> s = Files.list(dir)) {
			files = s.filter(p -> p.getFileName().toString().contains(init.saveId())).toList();
		}
		assertThat(files).hasSize(1);
		String onDisk = Files.readString(files.get(0));
		assertThat(onDisk).contains(FIXED).doesNotContain(MODEL_TITLE);

		// 「重启」:新 store 实例回载,续局拿到的仍是同一个标题。
		GameSessionManager restarted = new GameSessionManager(mapper,
				new FileSessionStore(dir.toString(), mapper, registry));
		restarted.reloadFromStore();
		InitResponse resumed = initService(registry, restarted, new ScriptedLlm()).resume(init.saveId());
		assertThat(resumed.world().path("world").path("title").asString()).isEqualTo(FIXED);
	}

	private GameInitService initService(ArchetypeRegistry registry, GameSessionManager sessions, LlmClient llm) {
		return new GameInitService(new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper), sessions,
				registry, mapper);
	}
}
