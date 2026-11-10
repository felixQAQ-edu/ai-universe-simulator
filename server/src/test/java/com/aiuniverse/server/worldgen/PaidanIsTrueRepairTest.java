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
 * ADR-036「订正(2026-11-10 二)」订正 2 ·《派单》规则必须带布尔 {@code isTrue}。缺失按校验失败处理、走现有一次修复;
 * 通用校验器不变 —— 其他世界缺 {@code isTrue} 照旧放行(ADR-009 可选口径)。
 */
class PaidanIsTrueRepairTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final WorldGenPromptBuilder prompts = new WorldGenPromptBuilder(new ArchetypeRegistry());

	private static class ScriptedLlm implements LlmClient {
		final Deque<String> responses = new ArrayDeque<>();
		final List<ChatRequest> requests = new ArrayList<>();

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

	@Test
	void paidanRulesMissingIsTrueTriggerOneRepairThenSucceed() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.responses.add(world("cyberpunk", false, false)); // 两条都缺
		llm.responses.add(world("cyberpunk", true, true));
		ObjectNode w = new WorldGenService(llm, prompts, mapper).generate("cyberpunk");
		assertThat(llm.requests).as("恰一次修复").hasSize(2);
		assertThat(llm.requests.get(1).prompt()).contains("rules[0].isTrue").contains("rules[1].isTrue")
				.contains("本世界每条规则都必须带布尔 isTrue");
		assertThat(w.path("rules").get(0).path("isTrue").isBoolean()).isTrue();
	}

	@Test
	void oneRuleMissingIsEnoughToRepair() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.responses.add(world("cyberpunk", true, false));
		llm.responses.add(world("cyberpunk", true, true));
		new WorldGenService(llm, prompts, mapper).generate("cyberpunk");
		assertThat(llm.requests).hasSize(2);
		assertThat(llm.requests.get(1).prompt()).contains("rules[1].isTrue").doesNotContain("rules[0].isTrue");
	}

	@Test
	void stillMissingAfterRepairIsWorldGenError() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.responses.add(world("cyberpunk", false, true));
		llm.responses.add(world("cyberpunk", false, true));
		assertThatThrownBy(() -> new WorldGenService(llm, prompts, mapper).generate("cyberpunk"))
				.isInstanceOf(WorldGenException.class);
		assertThat(llm.requests).hasSize(2);
	}

	@Test
	void paidanWithIsTrueNeedsNoRepair() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.responses.add(world("cyberpunk", true, true));
		new WorldGenService(llm, prompts, mapper).generate("cyberpunk");
		assertThat(llm.requests).hasSize(1);
	}

	@Test
	void otherWorldsMissingIsTrueAreStillAccepted() {
		for (String id : List.of("rules_creepy", "cthulhu", "cultivation")) {
			ScriptedLlm llm = new ScriptedLlm();
			llm.responses.add(world(id, false, false));
			new WorldGenService(llm, prompts, mapper).generate(id);
			assertThat(llm.requests).as(id + ":ADR-009 isTrue 可选,不触发修复").hasSize(1);
		}
		assertThat(WorldGenService.worldSpecificErrors(List.of("rules_creepy"), mapper.readTree(world("rules_creepy", false, false))))
				.isEmpty();
		assertThat(WorldGenService.worldSpecificErrors(List.of("cultivation", "rules_creepy"),
				mapper.readTree(world("cultivation", false, false)))).as("融合局不走本检查").isEmpty();
	}

	/** 一个能过 validateWorld 的最小世界;两条规则,{@code isTrue} 是否给出由参数决定。 */
	private static String world(String archetype, boolean firstHasIsTrue, boolean secondHasIsTrue) {
		return """
				{"schemaVersion":"0.4","mode":"single","archetypes":["%s"],
				 "world":{"title":"派单","background":"雨夜。","dangerLevel":"high","tone":"今晚还有三单。"},
				 "character":{"attributes":{"hp":80,"cash":20,"rating":60,"profile":10},"traits":[],"inventory":[]},
				 "rules":[{"id":1,"content":"【服务守则】提前送达会被记住",%s"hiddenLogic":"画像涨","discovered":false},
				          {"id":2,"content":"【群里说】凌晨差评不算数",%s"hiddenLogic":"假的,差评照算","discovered":false}],
				 "endings":[{"id":"rent_paid","title":"交上了","condition":"余钱 ≥ 80","outcome":"success","reached":false},
				            {"id":"almost","title":"差一点","condition":"以上都不满足","outcome":"neutral","reached":false}],
				 "availableActions":[{"id":"A","text":"接单","hint":"时限很紧"},{"id":"B","text":"歇一口气","hint":"评分会掉"}],
				 "openingNarrative":"义眼里弹出第一单。"}
				""".formatted(archetype, firstHasIsTrue ? "\"isTrue\":true," : "",
				secondHasIsTrue ? "\"isTrue\":false," : "");
	}
}
