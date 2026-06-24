package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.TokenStream;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ① WorldGenService(胖调用 + json_object + LooseJson + validateWorld + 一次修复 + ERROR)。
 * 脚本化 LLM,零真实 API。覆盖设计稿 §4:happy / 一次修复 / 修复仍败 ERROR / 调用失败 ERROR。
 */
class WorldGenServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final WorldGenPromptBuilder prompts = new WorldGenPromptBuilder(new ArchetypeRegistry());

	/** 每次 streamChat 弹出下一段整串喂 sink,并记录请求(断言次数 / json_object)。 */
	private static final class ScriptedLlm implements LlmClient {
		final Deque<String> responses = new ArrayDeque<>();
		final List<ChatRequest> requests = new ArrayList<>();

		void script(String... full) {
			for (String r : full) {
				responses.add(r);
			}
		}

		@Override
		public void streamChat(ChatRequest request, TokenStream sink) {
			requests.add(request);
			String r = responses.poll();
			if (r == null) {
				throw new LlmException("脚本耗尽");
			}
			sink.onToken(r);
		}
	}

	/** 一个最小合法 world(过 validateWorld):真假规则各一、含 hp/san、2 个结局。 */
	private String validWorld() {
		return """
				{"schemaVersion":"0.2","mode":"single","archetypes":["rules_creepy"],
				 "world":{"title":"雨夜便利店","background":"凌晨零点,你接替夜班。","dangerLevel":"high","tone":"压抑"},
				 "character":{"attributes":{"hp":80,"san":70},"traits":["警觉"],"inventory":["手电筒"]},
				 "rules":[{"id":1,"content":"不要直视监控","isTrue":true,"hiddenLogic":"直视则 san-10","discovered":false},
				          {"id":2,"content":"红雨衣顾客别收现金","isTrue":false,"hiddenLogic":"假规则,收了无事","discovered":false}],
				 "endings":[{"id":"survive_dawn","title":"撑到天亮","description":"你活到六点。","condition":"撑到 06:00","reached":false},
				            {"id":"lost_mind","title":"失心","description":"你疯了。","condition":"san<=0","reached":false}],
				 "availableActions":[{"id":"A","text":"查看告示","hint":""},{"id":"B","text":"原地不动","hint":""}],
				 "openingNarrative":"荧光灯忽明忽暗,墙上的告示泛黄。"}
				""";
	}

	@Test
	void happyPathReturnsValidatedWorldNoRepair() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(validWorld());

		ObjectNode world = new WorldGenService(llm, prompts, mapper).generate("rules_creepy");

		assertThat(llm.requests).as("无修复").hasSize(1);
		assertThat(llm.requests.get(0).jsonObject()).as("world-gen 开 json_object(ADR-007)").isTrue();
		assertThat(world.path("world").path("title").asString()).isEqualTo("雨夜便利店");
		assertThat(world.path("rules")).hasSize(2);
	}

	@Test
	void invalidFirstTriggersExactlyOneRepairThenSucceeds() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script(
				"{\"schemaVersion\":\"0.2\",\"mode\":\"single\"}", // 残缺 → validateWorld 失败
				validWorld());

		ObjectNode world = new WorldGenService(llm, prompts, mapper).generate("rules_creepy");

		assertThat(llm.requests).as("恰一次修复").hasSize(2);
		assertThat(llm.requests.get(1).jsonObject()).as("修复发同样 json_object").isTrue();
		assertThat(llm.requests.get(1).prompt()).as("修复 prompt 带校验错误").contains("校验错误");
		assertThat(world.path("world").path("title").asString()).isEqualTo("雨夜便利店");
	}

	@Test
	void repairAlsoFailsThrowsWorldGenErrorNoHalfSession() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.script("{\"mode\":\"single\"}", "还是不合法的东西 not json");

		assertThatThrownBy(() -> new WorldGenService(llm, prompts, mapper).generate("rules_creepy"))
				.isInstanceOf(WorldGenException.class)
				.hasMessageContaining("重新生成");
		assertThat(llm.requests).hasSize(2);
	}

	@Test
	void llmCallFailureThrowsWorldGenErrorWithoutRepair() {
		LlmClient failing = (req, sink) -> {
			throw new LlmException("网络失败");
		};

		assertThatThrownBy(() -> new WorldGenService(failing, prompts, mapper).generate("rules_creepy"))
				.isInstanceOf(WorldGenException.class);
	}
}
