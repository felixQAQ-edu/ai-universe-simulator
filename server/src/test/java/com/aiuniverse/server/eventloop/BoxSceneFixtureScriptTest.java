package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.persistence.SessionDocument;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-028 刀 3 · 夹具派生脚本({@code scripts/adr028_derive_box_fixture.py})的守护。
 *
 * <p>脚本只供本地人工短局用(零新增生产入口:不加端点、不加开关)。这里守三件事:
 * 派生结果能被 {@link SessionDocument} 正常回载且不是旧局;下一回合的编排是纸箱阶段 1;
 * 没有局面键(旧局)的存档交给脚本时脚本拒绝、什么都不写。
 *
 * <p>样例输入不是手写的:经 {@link GameSessionManager#create} 新开一局《动物人生》再
 * {@link SessionDocument#encode},与 Felix 本地新开一局落盘的文档同一条代码路径。
 * 脚本跑不起来(没有 python3)= 测试失败,不跳过。
 */
class BoxSceneFixtureScriptTest {

	private static final Path SCRIPT = Path.of("..", "scripts", "adr028_derive_box_fixture.py");

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@TempDir
	Path tmp;

	private ObjectNode world() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4").put("mode", "single");
		w.putArray("archetypes").add("animal_life");
		w.putObject("world").put("title", "t").put("background", "b").put("dangerLevel", "low").put("tone", "x");
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		registry.meta("animal_life").attributes().forEach(a -> attrs.put(a.key(), 50));
		w.putArray("rules");
		w.putArray("endings").addObject().put("id", "e").put("title", "终").put("condition", "c")
				.put("reached", false);
		return w;
	}

	/** 新开一局的落盘文档(第 0 回合,带局面键)。 */
	private ObjectNode newGameDoc() {
		GameSession s = new GameSessionManager(mapper).create("src", world(), mapper.createArrayNode()
				.add(mapper.createObjectNode().put("id", "A").put("text", "开场选项")), Set.of(), Map.of(), Set.of());
		return SessionDocument.encode(s, mapper);
	}

	private record Run(int exit, String stdout, String stderr) {
	}

	private Run runScript(Path src, Path outDir) throws IOException, InterruptedException {
		assertThat(SCRIPT).as("脚本路径(测试工作目录应为 server/)").exists();
		Process p = new ProcessBuilder("python3", SCRIPT.toString(), src.toString(), outDir.toString()).start();
		assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		return new Run(p.exitValue(), out, err);
	}

	private List<Path> filesIn(Path dir) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.toList();
		}
	}

	@Test
	void derivedFixtureReloadsAsNewGameAndNextTurnIsBoxStageOne() throws Exception {
		Path src = tmp.resolve("src.json");
		Files.writeString(src, mapper.writeValueAsString(newGameDoc()));
		byte[] before = Files.readAllBytes(src);
		Path out = Files.createDirectory(tmp.resolve("data"));

		Run r = runScript(src, out);
		assertThat(r.exit()).as(r.stderr()).isZero();
		String saveId = r.stdout();
		Path derived = out.resolve(saveId + ".json");
		assertThat(filesIn(out)).containsExactly(derived);
		assertThat(Files.readAllBytes(src)).as("原存档不改").isEqualTo(before);

		JsonNode doc = mapper.readTree(Files.readString(derived));
		GameSession s = SessionDocument.decode(saveId, doc, mapper, registry);
		assertThat(s.engine().turn()).isEqualTo(10);
		assertThat(s.phase().get()).isEqualTo(TurnPhase.AWAITING_ACTION);
		BoxSceneState st = s.boxScene();
		assertThat(st).isNotNull();
		assertThat(st.isLegacy()).as("派生结果不得是旧局").isFalse();

		// 下一回合(第 11 回合)的编排 = 纸箱阶段 1:征兆与三个槽位都来自阶段 1(g = 0)。
		BoxScene.Table t = BoxSceneTables.box("animal_life");
		String anySlot = s.currentActions().get(0).path("id").asString();
		BoxSceneTurn.Plan plan = BoxSceneTurn.plan(t, BoxSceneTables.pools("animal_life"),
				LifeStageTables.of("animal_life"), st, s.engine().turn() + 1, anySlot);
		BoxScene.Beat stage1 = BoxScene.present(t, 1, 0);
		assertThat(plan).isNotNull();
		assertThat(plan.feedback()).isNull();
		assertThat(plan.newResult()).isNull();
		assertThat(plan.omen()).isEqualTo(stage1.omen());
		assertThat(plan.slots()).extracting(BoxSceneTurn.Slot::intent)
				.containsExactlyElementsOf(stage1.options().stream().map(BoxScene.Option::intent).toList());
	}

	@Test
	void scriptRefusesASaveWithoutTheSceneKey() throws Exception {
		ObjectNode doc = newGameDoc();
		doc.remove(BoxSceneState.DOC_KEY); // 发布前的旧局长这样
		Path src = tmp.resolve("legacy.json");
		Files.writeString(src, mapper.writeValueAsString(doc));
		Path out = Files.createDirectory(tmp.resolve("data"));

		Run r = runScript(src, out);
		assertThat(r.exit()).isNotZero();
		assertThat(r.stderr()).contains("没有局面键");
		assertThat(filesIn(out)).as("拒绝时不写任何文件").isEmpty();
	}

	@Test
	void scriptRefusesALegacyMarker() throws Exception {
		ObjectNode doc = newGameDoc();
		doc.set(BoxSceneState.DOC_KEY, mapper.createObjectNode().put("legacy", true));
		Path src = tmp.resolve("legacy-marked.json");
		Files.writeString(src, mapper.writeValueAsString(doc));
		Path out = Files.createDirectory(tmp.resolve("data"));

		Run r = runScript(src, out);
		assertThat(r.exit()).isNotZero();
		assertThat(r.stderr()).contains("旧局标记");
		assertThat(filesIn(out)).isEmpty();
	}
}
