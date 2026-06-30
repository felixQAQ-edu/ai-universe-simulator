package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * #3 数值行为化 Slice B —— 提示词注入当前行为档。验:给定 state 注入正确的<b>当前档</b>(label+narrationHint)、
 * 只送当前档不送整张表、边界值归属、状态缺失轴不臆造、行为档不引入隐藏字段、A-1 长度约束仍在、.md↔运行时 lockstep。
 *
 * <p><b>只染叙事、不 gate 选项</b>:本测试只验注入文本,不涉任何选项门控(=#4)。
 */
class TurnPromptBandInjectionTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(new ArchetypeRegistry());

	/** 规则怪谈引擎,可指定 hp/san 初值(无显式 archetypes → 回落 rules_creepy)。 */
	private Engine rulesEngine(int hp, int san) {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", hp).put("san", san);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	@Test
	void injectsCurrentBandLabelAndNarrationHintForLowHp() {
		String p = builder.buildTurnPrompt(rulesEngine(15, 80), "A", "硬闯");
		// hp=15 → 濒危档(label + narrationHint);san=80 → 清明档。
		assertThat(p).contains("各数值轴当前所处的状态档");
		assertThat(p).contains("【濒危】").contains("重伤濒死");
		assertThat(p).contains("【清明】").contains("神志清明");
		// System 指令在场,叙事跟着状态走。
		assertThat(p).contains("叙事须贴合当前状态档");
	}

	@Test
	void onlyCurrentBandSentNotWholeTable() {
		String p = builder.buildTurnPrompt(rulesEngine(15, 80), "A", "硬闯");
		// hp 当前是濒危,整张表的其它档(充沛/受创)不应随当前档一起送(守成本:只送当前档)。
		assertThat(p).contains("【濒危】");
		assertThat(p).doesNotContain("【充沛】"); // hp=15 不在充沛档,不送
		assertThat(p).doesNotContain("【受创】"); // 也不送
	}

	@Test
	void boundaryValuesResolveCorrectlyInPrompt() {
		// 上界 inclusive:hp=50→受创、hp=20→濒危(与 resolveBand 单测一致,经提示词路径再验一次)。
		assertThat(builder.buildTurnPrompt(rulesEngine(50, 100), "A", "x")).contains("【受创】");
		assertThat(builder.buildTurnPrompt(rulesEngine(20, 100), "A", "x")).contains("【濒危】");
	}

	@Test
	void axisMissingFromStateNotFabricated() {
		// state 只有 hp、无 san:不臆造 san 档(只对当前 state 含有的轴注入)。
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 40); // 无 san
		world.putArray("rules");
		world.putArray("endings");
		String p = builder.buildTurnPrompt(new Engine(world, mapper), "A", "x");
		assertThat(p).contains("【受创】");      // hp=40 → 受创(在场)
		assertThat(p).doesNotContain("【清明】"); // san 缺失,不注入任何 san 档
	}

	@Test
	void bandBlockIntroducesNoHiddenFieldLeak() {
		// 行为档块本身(我方手写文本)不含 isTrue/hiddenLogic(守三视图消毒)。注意:整段提示词的清洁度
		// 指令本就会提及「isTrue/hiddenLogic」(告知模型这是内部字段),故只截取注入的「状态档」块来验。
		String p = builder.buildTurnPrompt(rulesEngine(15, 15), "A", "x");
		int from = p.indexOf("各数值轴当前所处的状态档");
		int to = p.indexOf("请推进第", from);
		assertThat(from).as("行为档块在场").isGreaterThanOrEqualTo(0);
		String bandBlock = p.substring(from, to);
		assertThat(bandBlock).contains("【濒危】").contains("【崩溃边缘】"); // 行为档在场
		assertThat(bandBlock).doesNotContain("hiddenLogic").doesNotContain("isTrue");
	}

	@Test
	void a1LengthConstraintStillStated() {
		// 行为档注入不得顶掉 A-1 长度约束。
		String p = builder.buildTurnPrompt(rulesEngine(15, 80), "A", "x");
		assertThat(p).contains("2-4 句").contains("280 字");
		// 且明确要求在既有篇幅内体现、不额外加长。
		assertThat(p).contains("不为它额外加长");
	}

	@Test
	void mdAndRuntimeStayLockstep() throws IOException {
		// .md(人类可读核心资产)与运行时同义副本须同源:共享指令短语两边都在 → 防止只改一边的漂移。
		String md = readEventLoopMd();
		String runtime = builder.buildTurnPrompt(rulesEngine(50, 50), "A", "x");
		for (String phrase : new String[] { "叙事须贴合当前状态档", "各数值轴当前所处的状态档" }) {
			assertThat(md).as(".md 含 lockstep 短语:%s", phrase).contains(phrase);
			assertThat(runtime).as("运行时含 lockstep 短语:%s", phrase).contains(phrase);
		}
	}

	/** 从 server 模块向上定位仓库根 prompts/event-loop.md(surefire CWD=server 模块目录)。 */
	private String readEventLoopMd() throws IOException {
		for (Path p : new Path[] { Path.of("..", "prompts", "event-loop.md"), Path.of("prompts", "event-loop.md") }) {
			if (Files.exists(p)) {
				return Files.readString(p);
			}
		}
		throw new IOException("找不到 prompts/event-loop.md(CWD=" + Path.of(".").toAbsolutePath() + ")");
	}
}
