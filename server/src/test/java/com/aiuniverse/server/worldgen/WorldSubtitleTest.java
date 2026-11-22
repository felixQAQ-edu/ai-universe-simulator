package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.FusionSummary;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-044 最终决策 2 · 每局小标题 {@code world.subtitle} 的服务端收口。钉:
 * <ol>
 * <li>未登记固定世界名的单体世界、任何融合局(两半都登记了也一样):删键,其余逐字节不变;</li>
 * <li>登记了的三个世界:去首尾空白;空串、非字符串、超过 10 字 → 删键;漏写照常通过(不算 world-gen 失败);</li>
 * <li>字数按 code point 计:10 字保留、11 字删除,扩展区汉字(UTF-16 两个单元)算一个字;</li>
 * <li>首过与修复两条成功路径都收口;登记了的世界每局恰一行 INFO,写清保留 / 删除 / 漏写与原文。</li>
 * </ol>
 */
class WorldSubtitleTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory
			.getLogger(WorldSubtitle.class);

	@BeforeEach
	void attach() {
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void detach() {
		logger.detachAppender(logs);
	}

	private static final class ScriptedLlm implements LlmClient {
		final Deque<String> responses = new ArrayDeque<>();

		ScriptedLlm(String... full) {
			responses.addAll(List.of(full));
		}

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			String r = responses.poll();
			if (r == null) {
				throw new LlmException("脚本耗尽");
			}
			sink.onToken(r);
		}
	}

	/** 给 {@code ids} 里的世界登记固定世界名的 registry 替身(同 {@code FixedWorldTitleTest})。 */
	private static ArchetypeRegistry registering(String... ids) {
		List<String> titled = List.of(ids);
		return new ArchetypeRegistry() {
			@Override
			public ArchetypeMeta meta(String archetype) {
				ArchetypeMeta m = super.meta(archetype);
				if (!titled.contains(archetype)) {
					return m;
				}
				return new ArchetypeMeta(m.id(), m.displayName(), m.tagline(), m.vibeTag(), m.experienceMode(), m.worldview(),
						m.attributes(), m.ruleForm(), m.rulesCarryTruth(), "测试固定名");
			}
		};
	}

	/**
	 * 最小合法 world;{@code subtitleJson} 是 {@code "subtitle":} 后面的 JSON 字面(null = 不写这个键)。
	 * 不带系统台词、规则都带布尔 isTrue,《派单》的世界专属检查也能过。
	 */
	private static String world(List<String> ids, String subtitleJson) {
		String arr = String.join(",", ids.stream().map(i -> "\"" + i + "\"").toList());
		String mode = ids.size() == 1 ? "single" : "hybrid";
		String sub = subtitleJson == null ? "" : ",\"subtitle\":" + subtitleJson;
		return """
				{"schemaVersion":"0.4","mode":"%s","archetypes":[%s],
				 "world":{"title":"模型起的名"%s,"background":"凌晨零点。","dangerLevel":"high","tone":"压抑"},
				 "character":{"attributes":{"hp":80,"san":70},"traits":["警觉"],"inventory":["手电筒"]},
				 "rules":[{"id":1,"content":"不要直视监控","isTrue":true,"hiddenLogic":"直视则 san-10","discovered":false},
				          {"id":2,"content":"别收现金","isTrue":false,"hiddenLogic":"假规则","discovered":false}],
				 "endings":[{"id":"survive_dawn","title":"撑到天亮","condition":"撑到 06:00","outcome":"success","reached":false},
				            {"id":"lost_mind","title":"失心","condition":"理智归零","outcome":"failure","reached":false}],
				 "availableActions":[{"id":"A","text":"查看告示","hint":""},{"id":"B","text":"原地不动","hint":""}],
				 "openingNarrative":"荧光灯忽明忽暗。"}
				""".formatted(mode, arr, sub);
	}

	private static final String BROKEN = "{\"schemaVersion\":\"0.4\"}";

	private ObjectNode generate(ArchetypeRegistry r, List<String> ids, String subtitleJson, boolean viaRepair) {
		String w = world(ids, subtitleJson);
		ScriptedLlm llm = viaRepair ? new ScriptedLlm(BROKEN, w) : new ScriptedLlm(w);
		return new WorldGenService(llm, new WorldGenPromptBuilder(r), mapper).generate(ids);
	}

	private List<String> infoLines() {
		return logs.list.stream().filter(e -> e.getLevel() == Level.INFO).map(ILoggingEvent::getFormattedMessage).toList();
	}

	// ① 未登记的世界与融合局:删键 ─────────────────────────────────────────

	@Test
	void unregisteredSingleWorldsDropTheKeyAndAreOtherwiseUnchanged() {
		for (ArchetypeMeta m : registry.activeMetas()) {
			if (m.fixedWorldTitle() != null) {
				continue;
			}
			List<String> ids = List.of(m.id());
			for (boolean repair : new boolean[] { false, true }) {
				ObjectNode out = generate(registry, ids, "\"雨夜便利店\"", repair);
				assertThat(out.path("world").has("subtitle")).as(m.id() + " repair=" + repair).isFalse();
				// 与模型根本没写这个键时的产出逐字节相同(EndingOrder 对本夹具不动)。
				assertThat(mapper.writeValueAsString(out)).as(m.id())
						.isEqualTo(mapper.writeValueAsString(generate(registry, ids, null, repair)));
			}
		}
	}

	@Test
	void unregisteredWorldWithoutTheKeyLogsNothing() {
		generate(registry, List.of("rules_creepy"), null, false);
		assertThat(infoLines()).isEmpty();
	}

	@Test
	void fusionWorldsDropTheKeyEvenWhenBothHalvesAreRegistered() {
		ArchetypeRegistry bothRegistered = registering("cultivation", "rules_creepy", "cthulhu", "apocalypse");
		List<FusionSummary> combos = registry.listFusionCombos();
		assertThat(combos).isNotEmpty();
		for (FusionSummary c : combos) {
			List<String> ids = List.of(c.host(), c.foreign());
			for (ArchetypeRegistry r : List.of(registry, bothRegistered)) {
				ObjectNode out = generate(r, ids, "\"识海里的一页\"", false);
				assertThat(out.path("world").has("subtitle")).as(c.key()).isFalse();
				assertThat(mapper.writeValueAsString(out)).as(c.key())
						.isEqualTo(mapper.writeValueAsString(generate(r, ids, null, false)));
			}
		}
	}

	// ② 登记了的三个世界 ──────────────────────────────────────────────────

	@Test
	void registeredWorldsKeepATrimmedSubtitleOnBothPaths() {
		for (String id : List.of("cyberpunk", "life_sim", "time_travel")) {
			assertThat(registry.meta(id).fixedWorldTitle()).as(id).isNotNull();
		}
		// 《七日开张》的世界专属检查要固定结局与真假规矩条数,本夹具过不了;收口逻辑三个世界同一份,端到端用另外两个。
		for (String id : List.of("cyberpunk", "life_sim")) {
			for (boolean repair : new boolean[] { false, true }) {
				ObjectNode out = generate(registry, List.of(id), "\"  \u3000西北草滩的老三 \\n\"", repair);
				assertThat(out.path("world").path("subtitle").asString()).as(id + " repair=" + repair).isEqualTo("西北草滩的老三");
				// 固定名覆写照旧。
				assertThat(out.path("world").path("title").asString()).isEqualTo(registry.meta(id).fixedWorldTitle());
			}
		}
	}

	@Test
	void emptyOrBlankIsDropped() {
		for (String json : List.of("\"\"", "\"   \"", "\"\u3000\"")) {
			ObjectNode out = generate(registry, List.of("life_sim"), json, false);
			assertThat(out.path("world").has("subtitle")).as(json).isFalse();
		}
	}

	@Test
	void nonStringIsDropped() {
		for (String json : List.of("7", "true", "[\"柳筐街\"]", "{\"name\":\"柳筐街\"}", "null")) {
			ObjectNode out = generate(registry, List.of("life_sim"), json, false);
			assertThat(out.path("world").has("subtitle")).as(json).isFalse();
		}
	}

	@Test
	void missingSubtitleIsNotAWorldGenFailure() {
		ScriptedLlm llm = new ScriptedLlm(world(List.of("life_sim"), null));
		ObjectNode out = new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper).generate(List.of("life_sim"));
		assertThat(out.path("world").has("subtitle")).isFalse();
		assertThat(llm.responses).as("没有触发修复").isEmpty();
		assertThat(infoLines()).singleElement().asString().contains("漏写");
	}

	// ③ 字数按 code point ────────────────────────────────────────────────

	@Test
	void tenCodePointsAreKeptElevenAreDropped() {
		String ten = "一二三四五六七八九十";
		String eleven = ten + "一";
		assertThat(apply(ten)).isEqualTo(ten);
		assertThat(apply(eleven)).isNull();
		// 首尾空白不计入字数。
		assertThat(apply("  " + ten + "  ")).isEqualTo(ten);
		// 扩展区汉字(U+20000,UTF-16 两个单元):10 个 = 20 个 UTF-16 单元,按 code point 仍是 10 字。
		String ext = "\uD840\uDC00";
		String tenExt = ext.repeat(10);
		assertThat(tenExt.length()).isEqualTo(20);
		assertThat(apply(tenExt)).isEqualTo(tenExt);
		assertThat(apply(tenExt + ext)).isNull();
	}

	// ④ 日志:每局一行 ────────────────────────────────────────────────────

	@Test
	void registeredWorldLogsExactlyOneInfoLinePerGame() {
		generate(registry, List.of("life_sim"), "\" 西北草滩 \"", false);
		assertThat(infoLines()).singleElement().asString().contains("保留「西北草滩」").contains("原文=「 西北草滩 」");
		logs.list.clear();
		generate(registry, List.of("life_sim"), "\"一二三四五六七八九十一\"", false);
		assertThat(infoLines()).singleElement().asString().contains("删除(超过 10 字,共 11 字)").contains("原文=「一二三四五六七八九十一」");
		logs.list.clear();
		generate(registry, List.of("life_sim"), "7", false);
		assertThat(infoLines()).singleElement().asString().contains("删除(不是字符串)").contains("原文=7");
	}

	/** 直接调收口;返回保留下来的小标题,删掉了返回 null。 */
	private String apply(String subtitle) {
		ObjectNode w = (ObjectNode) mapper.readTree(world(List.of("life_sim"), mapper.writeValueAsString(subtitle)));
		ObjectNode out = WorldSubtitle.apply(List.of("life_sim"), registry, w);
		return out.path("world").has("subtitle") ? out.path("world").path("subtitle").asString() : null;
	}
}
