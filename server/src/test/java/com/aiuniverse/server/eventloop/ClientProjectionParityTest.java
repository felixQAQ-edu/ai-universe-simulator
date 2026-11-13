package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeMeta;
import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;
import com.aiuniverse.server.worldgen.GameInitService;
import com.aiuniverse.server.worldgen.InitResponse;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;
import com.aiuniverse.server.worldgen.WorldGenService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-039 验证 A · <b>《七日开张》以外的世界,三处下发(init / state / 回合 delta)逐字节不变</b>。
 *
 * <p>基线在 {@code src/test/resources/golden/adr039-client-projection/},<b>录于动生产代码之前</b>
 * ({@code main} {@code 2d79c9e} 的行为;先例:{@code PaidanPromptSnapshotTest})。之后的提交不得重录:
 * 重录等于把「不变」改成「我说它不变」。重录入口 {@code -Dadr039.record=true} 只为第一次录制留着。
 *
 * <p>每个世界跑同一条固定脚本(桩模型,零真实 API):init → state → 一个回合 → state → 一个两次都非法的回合(保守 no-op 降级)。
 * 五段各序列化成一行(整个 {@link InitResponse} 走 {@code writeValueAsString},所以多出来的 {@code null} 键也会被抓到)。
 * 脚本里每一组选项都故意带一条以「【怪点子】」开头的:未登记的世界不得剥前缀。
 * {@code saveId} 是随机 UUID,比对前换成固定字样;除此以外不做任何归一化。
 *
 * <p>覆盖:除 {@code time_travel} 外全部已激活单体世界 + 全部已登记融合组合。输入里没有模型自带的 {@code tag} 键
 * (那是 ADR-039 决策 4 第 5 条有意改变的行为,另有单测)。
 */
class ClientProjectionParityTest {

	private static final Path GOLDEN = Path.of("src/test/resources/golden/adr039-client-projection");
	private static final String ODD = "【怪点子】";

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	private static final class ScriptedLlm implements LlmClient {
		final Deque<String> responses = new ArrayDeque<>();

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			String r = responses.poll();
			if (r == null) {
				throw new LlmException("脚本耗尽");
			}
			sink.onToken(r);
		}
	}

	private static final class RecordingSink implements TurnEventSink {
		final List<String> order = new ArrayList<>();
		ObjectNode delta;

		@Override public void narrative(String text) { if (order.isEmpty() || !order.get(order.size() - 1).equals("narrative")) order.add("narrative"); }
		@Override public void delta(ObjectNode d) { order.add("delta"); delta = d; }
		@Override public void ending(ObjectNode e) { order.add("ending"); }
		@Override public void error(String code, String msg) { order.add("error:" + code); }
	}

	@Test
	void everyWorldExceptQiriIsByteIdentical() throws IOException {
		List<List<String>> cases = new ArrayList<>();
		for (ArchetypeMeta m : registry.activeMetas()) {
			if (!m.id().equals("time_travel")) {
				cases.add(List.of(m.id()));
			}
		}
		for (com.aiuniverse.server.archetype.FusionSummary c : registry.listFusionCombos()) {
			cases.add(List.of(c.host(), c.foreign()));
		}
		assertThat(cases).as("七个单体 + 三个融合").hasSize(10);

		boolean record = Boolean.getBoolean("adr039.record");
		for (List<String> ids : cases) {
			String actual = run(ids);
			Path file = GOLDEN.resolve(String.join("x", ids) + ".txt");
			if (record) {
				Files.createDirectories(GOLDEN);
				Files.writeString(file, actual, StandardCharsets.UTF_8);
				continue;
			}
			assertThat(actual).as(ids.toString()).isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
		}
		assertThat(record).as("录制模式不算通过:去掉 -Dadr039.record 再跑").isFalse();
	}

	/** 跑固定脚本,返回五段(每段一行,前面带段名)。 */
	private String run(List<String> ids) {
		List<AttributeAxis> axes = registry.resolveAxes(ids);
		ScriptedLlm llm = new ScriptedLlm();
		GameSessionManager sessions = new GameSessionManager(mapper);
		GameInitService init = new GameInitService(
				new WorldGenService(llm, new WorldGenPromptBuilder(registry), mapper), sessions, registry, mapper);
		EventLoopService loop = new EventLoopService(llm, new TurnPromptBuilder(registry), mapper);

		StringBuilder out = new StringBuilder();
		llm.responses.add(world(ids, axes));
		InitResponse first = init.init(ids);
		String saveId = first.saveId();
		out.append("init\t").append(json(first, saveId)).append('\n');
		out.append("state@0\t").append(json(init.resume(saveId), saveId)).append('\n');

		GameSession session = sessions.get(saveId);
		String good = "第一回合的叙事。" + SentinelSplitter.SENTINEL + tail(axes, 55);
		llm.responses.add(good);
		llm.responses.add(tail(axes, 55)); // 若该世界另有专属校验把主调用判了非法,修复发同一条(结果照录)
		RecordingSink s1 = new RecordingSink();
		loop.execute(session, "A", s1);
		llm.responses.clear();
		out.append("turn1 ").append(s1.order).append('\t').append(mapper.writeValueAsString(s1.delta)).append('\n');
		out.append("state@1\t").append(json(init.resume(saveId), saveId)).append('\n');

		llm.responses.add("第二回合的叙事。" + SentinelSplitter.SENTINEL + tail(axes, 150)); // 越界 → 非法
		llm.responses.add(tail(axes, 150)); // 修复也非法 → 保守 no-op
		RecordingSink s2 = new RecordingSink();
		loop.execute(session, "A", s2);
		out.append("turn2-degraded ").append(s2.order).append('\t').append(mapper.writeValueAsString(s2.delta)).append('\n');
		return out.toString();
	}

	private String json(InitResponse resp, String saveId) {
		return mapper.writeValueAsString(resp).replace(saveId, "SAVE-ID");
	}

	private static String attrs(List<AttributeAxis> axes, int value) {
		List<String> parts = new ArrayList<>();
		for (AttributeAxis a : axes) {
			parts.add("\"" + a.key() + "\":" + value);
		}
		return String.join(",", parts);
	}

	private static final String ACTIONS = "[{\"id\":\"A\",\"text\":\"查看告示\",\"hint\":\"也许有用\"},"
			+ "{\"id\":\"B\",\"text\":\"" + ODD + "原地不动\",\"hint\":\"\"},"
			+ "{\"id\":\"C\",\"text\":\"转身离开\",\"hint\":\"\"}]";

	private static String world(List<String> ids, List<AttributeAxis> axes) {
		String arr = String.join(",", ids.stream().map(i -> "\"" + i + "\"").toList());
		return """
				{"schemaVersion":"0.4","mode":"%s","archetypes":[%s],
				 "world":{"title":"雨夜便利店","background":"凌晨零点,你接替夜班。","dangerLevel":"high","tone":"压抑"},
				 "character":{"attributes":{%s},"traits":["警觉"],"inventory":["手电筒"]},
				 "rules":[{"id":1,"content":"不要直视监控","isTrue":true,"hiddenLogic":"直视则掉数值","discovered":false},
				          {"id":2,"content":"红雨衣顾客别收现金","isTrue":false,"hiddenLogic":"假规则,收了无事","discovered":false}],
				 "endings":[{"id":"survive_dawn","title":"撑到天亮","condition":"撑到 06:00","outcome":"success","reached":false},
				            {"id":"lost_all","title":"撑不住了","condition":"归零","outcome":"failure","reached":false}],
				 "availableActions":%s,
				 "openingNarrative":"荧光灯忽明忽暗,墙上的告示泛黄。"}
				""".formatted(ids.size() == 1 ? "single" : "hybrid", arr, attrs(axes, 60), ACTIONS);
	}

	private static String tail(List<AttributeAxis> axes, int value) {
		return "{\"stateUpdate\":{" + attrs(axes, value) + ",\"timeline\":\"进店\"},"
				+ "\"triggeredRuleIds\":[1],\"discoveredRuleIds\":[1],"
				+ "\"availableActions\":" + ACTIONS + ",\"ending\":null}";
	}
}
