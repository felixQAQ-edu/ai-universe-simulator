package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.llm.TokenStream;
import com.aiuniverse.server.persistence.SessionDocument;
import com.aiuniverse.server.persistence.SessionStore;
import com.aiuniverse.server.persistence.TraceSink;
import com.aiuniverse.server.persistence.TurnLedger;
import com.aiuniverse.server.persistence.TurnTrace;
import com.aiuniverse.server.quota.QuotaGate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-031 刀 1 · 回合轨迹的<b>采集与写出点</b>(不落盘;{@link TraceSink} 用内存替身)。
 *
 * <p>对应 ADR-031 测试面 1 / 3 / 4 / 7 / 8 / 11 / 12。每条都做过变异校验、能单独变红
 * (变异与红用例名见刀 1 交付记录)。全部经真实的 {@link TurnStateMachine} + {@link EventLoopService},
 * 模型由脚本代替 —— 写出点在状态机里,只测执行器会漏掉「写在哪」这一半。
 */
class TurnTraceTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder prompts = new TurnPromptBuilder(registry);
	private ListAppender<ILoggingEvent> logs;

	@AfterEach
	void detach() {
		if (logs != null) {
			((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TurnStateMachine.class))
					.detachAppender(logs);
		}
	}

	// ── 替身 ─────────────────────────────────────────────────────────────

	/** 测试用内存 TraceSink(ADR-031 已决 W-1):记下每条轨迹;可配置为抛。 */
	static final class RecordingTraceSink implements TraceSink {
		final List<TurnTrace> traces = new ArrayList<>();
		Throwable toThrow;

		@Override
		public void record(TurnTrace trace) {
			if (toThrow instanceof RuntimeException r) {
				throw r;
			}
			if (toThrow instanceof Error e) {
				throw e;
			}
			traces.add(trace);
		}
	}

	/** 记 persist 次数与 persist 那一刻的相位。 */
	static final class CountingStore implements SessionStore {
		int persisted;
		final List<TurnPhase> phaseAtPersist = new ArrayList<>();

		@Override
		public void persist(GameSession session) {
			persisted++;
			phaseAtPersist.add(session.phase().get());
		}

		@Override
		public List<GameSession> loadAll() {
			return List.of();
		}
	}

	/** 脚本化模型:每次调用弹出一项(字符串 = 逐字 token;异常 = 抛);记下收到的 prompt。 */
	static final class ScriptedLlm implements LlmClient {
		final Deque<Object> responses = new ArrayDeque<>();
		final List<String> prompts = new ArrayList<>();
		Runnable onEachToken = () -> { };
		LlmUsage usage;
		String model;

		ScriptedLlm then(Object r) {
			responses.add(r);
			return this;
		}

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			prompts.add(request.prompt());
			Object r = responses.poll();
			if (r == null) {
				throw new LlmException("脚本耗尽");
			}
			if (r instanceof RuntimeException e) {
				if (e instanceof LlmException) {
					sink.onToken("灯闪了一下,然后"); // 流到一半断:已流出部分要进轨迹
				}
				throw e;
			}
			String text = (String) r;
			for (int i = 0; i < text.length(); i += 4) {
				sink.onToken(text.substring(i, Math.min(text.length(), i + 4)));
				onEachToken.run();
			}
			if (usage != null) {
				sink.onUsage(usage);
			}
			if (model != null) {
				sink.onResponseMeta(model, 7);
			}
		}
	}

	static final class Sink implements TurnEventSink {
		final List<String> errors = new ArrayList<>();
		boolean failDelta;

		@Override
		public void narrative(String t) {
		}

		@Override
		public void delta(ObjectNode d) {
			if (failDelta) {
				throw new IllegalStateException("客户端已断开");
			}
		}

		@Override
		public void ending(ObjectNode e) {
		}

		@Override
		public void error(String c, String m) {
			errors.add(c);
		}
	}

	// ── 夹具 ─────────────────────────────────────────────────────────────

	private ObjectNode world(String archetype) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4").put("mode", "single");
		w.putArray("archetypes").add(archetype);
		w.putObject("world").put("title", "t").put("background", "b").put("dangerLevel", "low").put("tone", "克制");
		w.putArray("rules").addObject().put("id", 1).put("content", "午夜不可照镜").put("isTrue", true)
				.put("hiddenLogic", "照镜触发镜中怪").put("discovered", false);
		w.putArray("endings").addObject().put("id", "e").put("title", "结").put("condition", "c")
				.put("outcome", "neutral").put("reached", false);
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		switch (archetype) {
			case "rules_creepy" -> attrs.put("hp", 90).put("san", 80);
			case "life_sim" -> attrs.put("vigor", 70).put("longing", 50).put("crossroads", 40).put("ties", 30);
			case "animal_life" -> attrs.put("body", 80).put("warmth", 60).put("ground", 50).put("close", 50);
			default -> throw new IllegalArgumentException(archetype);
		}
		return w;
	}

	private GameSession session(String archetype, int advance, boolean box) {
		Engine engine = new Engine(world(archetype), mapper);
		for (int i = 0; i < advance; i++) {
			engine.applyNoOp("日子", "A");
		}
		ArrayNode acts = mapper.createArrayNode();
		acts.addObject().put("id", "A").put("text", "趴着");
		acts.addObject().put("id", "B").put("text", "闻闻");
		GameSession s = new GameSession("save-trace", engine, acts);
		if (box) {
			s.setBoxScene(BoxSceneState.fresh());
		}
		return s;
	}

	private static String wire(String stateUpdate, String... ids) {
		StringBuilder a = new StringBuilder();
		for (String id : ids) {
			if (a.length() > 0) {
				a.append(',');
			}
			a.append("{\"id\":\"").append(id).append("\",\"text\":\"t").append(id).append("\"}");
		}
		return "它停了一下,又往前走。" + SentinelSplitter.SENTINEL + "{\"stateUpdate\":{" + stateUpdate
				+ ",\"timeline\":\"tl\"},\"availableActions\":[" + a + "],\"ending\":null}";
	}

	private static final String RC_OK = wire("\"hp\":85,\"san\":70", "A", "B");

	private EventLoopService service(LlmClient llm) {
		return new EventLoopService(llm, prompts, mapper);
	}

	private TurnStateMachine machine(LlmClient llm, SessionStore store, TraceSink traces) {
		return new TurnStateMachine(service(llm), store, QuotaGate.NOOP, TurnLedger.NOOP, traces);
	}

	private ListAppender<ILoggingEvent> attachMachineLog() {
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TurnStateMachine.class);
		logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		return logs;
	}

	// ── 字段齐全(ADR-031 §1.2;不对应编号测试面,作字段清单的守护)──────────────

	@Test
	void settledTraceCarriesTheAdrFieldList() {
		ScriptedLlm llm = new ScriptedLlm().then(RC_OK);
		llm.usage = new LlmUsage(100, 20, 120, 60, 40);
		llm.model = "deepseek-flash";
		RecordingTraceSink traces = new RecordingTraceSink();
		GameSession s = session("rules_creepy", 0, false);

		machine(llm, new CountingStore(), traces).submitAction(s, "A", new Sink());

		assertThat(traces.traces).hasSize(1);
		TurnTrace t = traces.traces.get(0);
		assertThat(t.schema()).isEqualTo(TurnTrace.SCHEMA);
		assertThat(t.saveId()).isEqualTo("save-trace");
		assertThat(t.turnBefore()).isZero();
		assertThat(t.recordedAt()).isNotBlank();
		assertThat(t.commit()).isNotBlank();
		assertThat(t.actionId()).isEqualTo("A");
		assertThat(t.path()).isEqualTo(TurnTrace.PATH_SETTLED);
		assertThat(t.pre().path("state").path("turn").asInt()).isZero();
		assertThat(t.pre().path("currentActions").size()).isEqualTo(2);
		assertThat(t.pre().toString()).as("pre 是视图 1 全量").contains("hiddenLogic");
		assertThat(t.parsed().path("narrative").asString("")).startsWith("它停了一下");
		assertThat(t.degradeReason()).isNull();
		assertThat(t.streamedNarrative()).isNull();
		assertThat(t.promptSha256()).isEqualTo(TurnTrace.sha256Hex(llm.prompts.get(0)));
		assertThat(t.usage()).singleElement().satisfies(u -> {
			assertThat(u.call()).isEqualTo("主调用");
			assertThat(u.usage().cacheHitTokens()).isEqualTo(60);
			assertThat(u.model()).isEqualTo("deepseek-flash");
			assertThat(u.reasoningChars()).isEqualTo(7);
		});
		assertThat(t.durMs()).isGreaterThanOrEqualTo(0);
		assertThat(t.repairErrors()).isNull();
		assertThat(t.post().sha256()).hasSize(64);
		assertThat(t.post().attributes()).containsEntry("hp", 85.0).containsEntry("san", 70.0);
	}

	@Test
	void repairTurnRecordsBothCallsAndTheErrorList() {
		ScriptedLlm llm = new ScriptedLlm()
				.then("它停了一下。" + SentinelSplitter.SENTINEL + "{\"stateUpdate\":{\"hp\":999}}")
				.then("{\"stateUpdate\":{\"hp\":85,\"san\":70,\"timeline\":\"tl\"},"
						+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"tA\"},{\"id\":\"B\",\"text\":\"tB\"}],"
						+ "\"ending\":null}");
		RecordingTraceSink traces = new RecordingTraceSink();

		machine(llm, new CountingStore(), traces).submitAction(session("rules_creepy", 0, false), "A", new Sink());

		TurnTrace t = traces.traces.get(0);
		assertThat(t.path()).isEqualTo(TurnTrace.PATH_SETTLED);
		assertThat(t.usage()).extracting(TurnTrace.CallUsage::call).containsExactly("主调用", "修复");
		assertThat(t.repairErrors()).isNotEmpty();
	}

	// ── 测试面 1:轨迹写失败不影响回合 ──────────────────────────────────

	@Test
	void sinkThrowingRuntimeExceptionDoesNotAffectTheTurn() {
		assertTurnUnaffectedWhenSinkThrows(new IllegalStateException("盘满了"));
	}

	@Test
	void sinkThrowingErrorDoesNotAffectTheTurn() {
		assertTurnUnaffectedWhenSinkThrows(new Error("模拟 Error"));
	}

	private void assertTurnUnaffectedWhenSinkThrows(Throwable boom) {
		ListAppender<ILoggingEvent> warn = attachMachineLog();
		RecordingTraceSink traces = new RecordingTraceSink();
		traces.toThrow = boom;
		CountingStore store = new CountingStore();
		Sink sink = new Sink();
		GameSession s = session("rules_creepy", 0, false);

		assertThatCode(() -> machine(new ScriptedLlm().then(RC_OK), store, traces).submitAction(s, "A", sink))
				.doesNotThrowAnyException();

		assertThat(s.engine().turn()).as("回合照常落账").isEqualTo(1);
		assertThat(s.engine().attributes()).containsEntry("hp", 85.0);
		assertThat(s.phase().get()).as("相位照常放回").isEqualTo(TurnPhase.AWAITING_ACTION);
		assertThat(store.persisted).as("存档照常写、且只写一次(没有落进「已落地补写盘」分支)").isEqualTo(1);
		assertThat(sink.errors).isEmpty();
		assertThat(warn.list).filteredOn(e -> e.getLevel() == Level.WARN)
				.anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("[trace]"));
	}

	// ── 测试面 3:phaseHint 不进 post 摘要 ──────────────────────────────

	@Test
	void postDigestIgnoresPhaseHint_streamInterruptDegradePersistsAtGenerating() {
		assertPostDigestIndependentOfPhase(new ScriptedLlm().then(new LlmException("断")), TurnPhase.GENERATING);
	}

	@Test
	void postDigestIgnoresPhaseHint_normalTurnPersistsAtSettling() {
		assertPostDigestIndependentOfPhase(new ScriptedLlm().then(RC_OK), TurnPhase.SETTLING);
	}

	private void assertPostDigestIndependentOfPhase(ScriptedLlm llm, TurnPhase expectedAtPersist) {
		RecordingTraceSink traces = new RecordingTraceSink();
		CountingStore store = new CountingStore();
		GameSession s = session("rules_creepy", 0, false);

		machine(llm, store, traces).submitAction(s, "A", new Sink());

		// 前提:写出时的相位确实是 GENERATING / SETTLING,而现在已放回 AWAITING —— 两份文档的 phaseHint 不同。
		assertThat(store.phaseAtPersist).containsExactly(expectedAtPersist);
		assertThat(s.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
		ObjectNode now = SessionDocument.encode(s, mapper);
		assertThat(now.path("phaseHint").asString("")).isEqualTo("AWAITING_ACTION");
		assertThat(traces.traces.get(0).post().sha256())
				.as("落账没变,只是相位变了 → 摘要必须相同")
				.isEqualTo(TurnTrace.postSha256(now, mapper));
	}

	// ── 测试面 4:parsed 取在钳制之后 ────────────────────────────────────

	@Test
	void recordedParsedIsTheClampedOne() {
		int finalFrom = LifeStageTables.of("life_sim").finalStageFromTurn();
		GameSession s = session("life_sim", finalFrom, false); // 下一回合在末段内
		ScriptedLlm llm = new ScriptedLlm()
				.then(wire("\"vigor\":5,\"longing\":40,\"crossroads\":30,\"ties\":35", "A", "B"));
		RecordingTraceSink traces = new RecordingTraceSink();

		machine(llm, new CountingStore(), traces).submitAction(s, "A", new Sink());

		assertThat(s.engine().issues()).as("前提:钳制确实触发了").anyMatch(i -> i.contains("收束下限钳制 5->15"));
		assertThat(traces.traces.get(0).parsed().path("stateUpdate").path("vigor").asDouble())
				.as("轨迹里的 stateUpdate 是钳制后的值(档 1 只重放 apply)")
				.isEqualTo(15.0);
	}

	// ── 测试面 7:流式期间不做 I/O ───────────────────────────────────────

	@Test
	void traceSinkIsNeverCalledWhileTokensStream() {
		RecordingTraceSink traces = new RecordingTraceSink();
		ScriptedLlm llm = new ScriptedLlm().then(RC_OK);
		List<Integer> seenDuringStream = new ArrayList<>();
		llm.onEachToken = () -> seenDuringStream.add(traces.traces.size());

		machine(llm, new CountingStore(), traces).submitAction(session("rules_creepy", 0, false), "A", new Sink());

		assertThat(seenDuringStream).as("前提:确实流了多个 token").hasSizeGreaterThan(5);
		assertThat(seenDuringStream).as("每个 token 回调时 TraceSink 都还没被调用").containsOnly(0);
		assertThat(traces.traces).hasSize(1);
	}

	/** 源码级:执行器与收集器不碰 TraceSink、不写文件(注释剥掉后查,注释里提到它不算)。 */
	@Test
	void collectorSideNeverTouchesTheSinkOrTheFilesystem() throws IOException {
		Path dir = Path.of("src/main/java/com/aiuniverse/server/eventloop");
		for (String f : List.of("EventLoopService.java", "TurnTraceCollector.java")) {
			Path p = dir.resolve(f);
			assertThat(p).as("源码不存在 = 下面什么也没在看").isRegularFile();
			String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
			assertThat(code).as("%s 不得引用写出接缝", f).doesNotContain("TraceSink");
			assertThat(code).as("%s 不得写文件", f)
					.doesNotContain("java.nio.file").doesNotContain("FileOutputStream").doesNotContain("FileWriter");
		}
	}

	// ── 测试面 8:轨迹不进任何出网路径 ───────────────────────────────────

	@Test
	void webLayerNeverReferencesTraceTypes() throws IOException {
		Path web = Path.of("src/main/java/com/aiuniverse/server/web");
		List<Path> sources;
		try (Stream<Path> files = Files.list(web)) {
			sources = files.filter(p -> p.toString().endsWith(".java")).toList();
		}
		assertThat(sources).as("web 层遍历为空 = 假绿").anyMatch(p -> p.endsWith("GameController.java"));
		for (Path p : sources) {
			assertThat(stripComments(Files.readString(p, StandardCharsets.UTF_8)))
					.as("%s:轨迹含视图 1 全量,控制器层不得引用(ADR-031 §4.1)", p)
					.doesNotContain("TurnTrace").doesNotContain("TraceSink");
		}
	}

	private static String stripComments(String src) {
		return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	// ── 测试面 11:prompt 可由 pre + actionId 重渲染(W-9)──────────────────

	@Test
	void promptRerendersFromPre_normalTurn() {
		assertPromptRerenders(session("rules_creepy", 0, false), RC_OK);
	}

	@Test
	void promptRerendersFromPre_boxSceneTurn() {
		assertPromptRerenders(session("animal_life", 10, true),
				wire("\"body\":80,\"warmth\":60,\"ground\":50,\"close\":50", "A", "B", "C"));
	}

	@Test
	void promptRerendersFromPre_lifetimeTurn() {
		assertPromptRerenders(session("life_sim", 8, false),
				wire("\"vigor\":60,\"longing\":40,\"crossroads\":30,\"ties\":35", "A", "B"));
	}

	private void assertPromptRerenders(GameSession s, String response) {
		ScriptedLlm llm = new ScriptedLlm().then(response);
		RecordingTraceSink traces = new RecordingTraceSink();

		machine(llm, new CountingStore(), traces).submitAction(s, "A", new Sink());

		TurnTrace t = traces.traces.get(0);
		assertThat(t.promptSha256()).as("记录的哈希就是模型实际收到的那份")
				.isEqualTo(TurnTrace.sha256Hex(llm.prompts.get(0)));
		GameSession replayed = SessionDocument.decode(t.saveId(), t.pre(), mapper, registry);
		String rerendered = service(new ScriptedLlm()).renderTurnPrompt(replayed, t.actionId());
		assertThat(TurnTrace.sha256Hex(rerendered)).as("同版本下由 pre + actionId 重渲染,哈希一致")
				.isEqualTo(t.promptSha256());
	}

	// ── 测试面 12:未落地不写,降级照写 ───────────────────────────────────

	@Test
	void unlandedTurnWritesNoTrace() {
		RecordingTraceSink traces = new RecordingTraceSink();
		Sink sink = new Sink();
		GameSession s = session("rules_creepy", 0, false);

		machine(new ScriptedLlm().then(new IllegalStateException("意料外故障")), new CountingStore(), traces)
				.submitAction(s, "A", sink);

		assertThat(s.engine().turn()).as("前提:回合未落地").isZero();
		assertThat(sink.errors).containsExactly("internal_error");
		assertThat(traces.traces).isEmpty();
	}

	@Test
	void degradedTurn_streamInterrupted_writesOneTrace() {
		TurnTrace t = singleDegradedTrace(new ScriptedLlm().then(new LlmException("断")));
		assertThat(t.degradeReason()).isEqualTo("stream_interrupted");
		assertThat(t.streamedNarrative()).isEqualTo("灯闪了一下,然后");
	}

	@Test
	void degradedTurn_noStructuredTail_writesOneTrace() {
		TurnTrace t = singleDegradedTrace(new ScriptedLlm().then("它停了一下,然后什么也没交出来。"));
		assertThat(t.degradeReason()).isEqualTo("no_structured_tail");
		assertThat(t.streamedNarrative()).isEqualTo("它停了一下,然后什么也没交出来。");
	}

	@Test
	void degradedTurn_repairFailed_writesOneTrace() {
		TurnTrace t = singleDegradedTrace(new ScriptedLlm()
				.then("它停了一下。" + SentinelSplitter.SENTINEL + "{\"stateUpdate\":{\"hp\":999}}")
				.then("{仍然不是 JSON"));
		assertThat(t.degradeReason()).isEqualTo("repair_failed");
		assertThat(t.streamedNarrative()).isEqualTo("它停了一下。");
		assertThat(t.repairErrors()).isNotEmpty();
	}

	private TurnTrace singleDegradedTrace(ScriptedLlm llm) {
		RecordingTraceSink traces = new RecordingTraceSink();
		GameSession s = session("rules_creepy", 0, false);

		machine(llm, new CountingStore(), traces).submitAction(s, "A", new Sink());

		assertThat(s.engine().turn()).as("前提:降级回合已落地").isEqualTo(1);
		assertThat(traces.traces).hasSize(1);
		TurnTrace t = traces.traces.get(0);
		assertThat(t.path()).isEqualTo(TurnTrace.PATH_DEGRADED);
		assertThat(t.parsed()).isNull();
		return t;
	}

	/** ADR-027「已落地未送达」分支:回合已落地,轨迹照写。 */
	@Test
	void landedButUndeliveredTurnStillWritesTrace() {
		RecordingTraceSink traces = new RecordingTraceSink();
		CountingStore store = new CountingStore();
		Sink sink = new Sink();
		sink.failDelta = true;
		GameSession s = session("rules_creepy", 0, false);

		machine(new ScriptedLlm().then(RC_OK), store, traces).submitAction(s, "A", sink);

		assertThat(s.engine().turn()).as("前提:已落地").isEqualTo(1);
		assertThat(store.persisted).isEqualTo(1);
		assertThat(traces.traces).singleElement()
				.satisfies(t -> assertThat(t.path()).isEqualTo(TurnTrace.PATH_SETTLED));
	}
}
