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
 * ADR-036「订正(2026-11-10 三)」订正 2 ·《派单》系统台词「已为你优化时限。」只允许出现在 became_benchmark 的 description 里。
 * 出现在其他玩家可见文字里按校验失败处理、走现有一次修复;其他世界不查。
 */
class PaidanSystemLineRepairTest {

	private static final String LINE = "已为你优化时限。";

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
	void lineInToneTriggersOneRepairThenSucceeds() {
		// 冒烟原样:正常局 world.tone 里出现了这句话。
		ScriptedLlm llm = new ScriptedLlm();
		llm.responses.add(world("cyberpunk", "今晚还有三单。" + LINE, "", "", ""));
		llm.responses.add(world("cyberpunk", "今晚还有三单。", "", "", ""));
		ObjectNode w = new WorldGenService(llm, prompts, mapper).generate("cyberpunk");
		assertThat(llm.requests).as("恰一次修复").hasSize(2);
		assertThat(llm.requests.get(1).prompt()).contains("world.tone").contains("只允许写在 became_benchmark 的 description 里");
		assertThat(w.path("world").path("tone").asString()).doesNotContain("已为你优化时限");
	}

	@Test
	void everyPlayerVisibleFieldIsChecked() {
		String[][] cases = {
				{ "world.title", world("cyberpunk", "测", "", "", "").replace("\"title\":\"派单\"", "\"title\":\"" + LINE + "\"") },
				{ "world.background", world("cyberpunk", "测", "", "", "").replace("\"background\":\"雨夜。\"", "\"background\":\"雨夜。" + LINE + "\"") },
				{ "world.tone", world("cyberpunk", LINE, "", "", "") },
				{ "rules[1].content", world("cyberpunk", "测", LINE, "", "") },
				{ "openingNarrative", world("cyberpunk", "测", "", LINE, "") },
				{ "availableActions[0].text", world("cyberpunk", "测", "", "", "").replace("\"text\":\"接单\"", "\"text\":\"" + LINE + "\"") },
				{ "availableActions[1].hint", world("cyberpunk", "测", "", "", "").replace("\"hint\":\"评分会掉\"", "\"hint\":\"" + LINE + "\"") },
				{ "endings[0].description", world("cyberpunk", "测", "", "", LINE) },
				{ "endings[2].condition", world("cyberpunk", "测", "", "", "").replace("\"condition\":\"画像 ≥ 95\"", "\"condition\":\"" + LINE + "\"") },
		};
		for (String[] c : cases) {
			List<String> errors = WorldGenService.worldSpecificErrors(List.of("cyberpunk"), mapper.readTree(c[1]));
			assertThat(errors).as(c[0]).hasSize(1);
			assertThat(errors.get(0)).as(c[0]).startsWith(c[0] + ":");
		}
	}

	@Test
	void benchmarkDescriptionAndHiddenLogicMayCarryTheLine() {
		String w = world("cyberpunk", "测", "", "", "");
		assertThat(w).contains("被推给一个新来的跑腿员。" + LINE).contains("\"hiddenLogic\":\"系统会说" + LINE + "\"");
		assertThat(WorldGenService.worldSpecificErrors(List.of("cyberpunk"), mapper.readTree(w))).isEmpty();
		ScriptedLlm llm = new ScriptedLlm();
		llm.responses.add(w);
		new WorldGenService(llm, prompts, mapper).generate("cyberpunk");
		assertThat(llm.requests).as("无需修复").hasSize(1);
	}

	@Test
	void stillPresentAfterRepairIsWorldGenError() {
		ScriptedLlm llm = new ScriptedLlm();
		llm.responses.add(world("cyberpunk", "测", "", LINE, ""));
		llm.responses.add(world("cyberpunk", "测", "", LINE, ""));
		assertThatThrownBy(() -> new WorldGenService(llm, prompts, mapper).generate("cyberpunk"))
				.isInstanceOf(WorldGenException.class);
		assertThat(llm.requests).hasSize(2);
	}

	@Test
	void otherWorldsAreNotChecked() {
		for (String id : List.of("rules_creepy", "cthulhu")) {
			ScriptedLlm llm = new ScriptedLlm();
			llm.responses.add(world(id, LINE, LINE, LINE, LINE));
			new WorldGenService(llm, prompts, mapper).generate(id);
			assertThat(llm.requests).as(id).hasSize(1);
		}
	}

	@Test
	void directiveStatesTheRestriction() {
		String p = prompts.buildWorldPrompt("cyberpunk");
		assertThat(p).contains("这句话只允许写在 became_benchmark 的 description 里")
				.contains("world 的 title / background / tone、每条规则的 content、openingNarrative、availableActions 一律不得出现它");
	}

	/**
	 * 一个能过 validateWorld 与 isTrue 检查的最小世界;「成了标杆」的 description 以系统台词结尾、#1 的 hiddenLogic 提到它(都允许)。
	 * 参数依次拼进 tone、第二条规则 content、openingNarrative、第一个结局 description 的末尾。
	 */
	private static String world(String archetype, String tone, String rule2, String opening, String ending0Desc) {
		return """
				{"schemaVersion":"0.4","mode":"single","archetypes":["%s"],
				 "world":{"title":"派单","background":"雨夜。","dangerLevel":"high","tone":"%s"},
				 "character":{"attributes":{"hp":80,"cash":20,"rating":60,"profile":10},"traits":[],"inventory":[]},
				 "rules":[{"id":1,"content":"【服务守则】提前送达会被记住","isTrue":true,"hiddenLogic":"系统会说%s","discovered":false},
				          {"id":2,"content":"【群里说】凌晨差评不算数%s","isTrue":false,"hiddenLogic":"假的,差评照算","discovered":false}],
				 "endings":[{"id":"rent_paid","title":"交上了","condition":"余钱 ≥ 80","description":"你交上了。%s","outcome":"success","reached":false},
				            {"id":"almost","title":"差一点","condition":"以上都不满足","outcome":"neutral","reached":false},
				            {"id":"became_benchmark","title":"成了标杆","condition":"画像 ≥ 95","description":"你的路线被推给一个新来的跑腿员。%s","outcome":"failure","reached":false}],
				 "availableActions":[{"id":"A","text":"接单","hint":"时限很紧"},{"id":"B","text":"歇一口气","hint":"评分会掉"}],
				 "openingNarrative":"义眼里弹出第一单。%s"}
				""".formatted(archetype, tone, LINE, rule2, ending0Desc, LINE, opening);
	}
}
