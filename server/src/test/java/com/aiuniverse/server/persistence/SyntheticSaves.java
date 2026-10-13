package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.eventloop.BoxSceneState;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnPhase;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-032 刀 2 的<b>合成</b>存档(W-11:不读、不复制、不提交任何真实存档)。全部由测试现场生成:
 * 经 {@link GameSessionManager#create} 起局 → {@link SessionDocument#encode} → 与 {@code FileSessionStore.persist}
 * 同一种写法({@code mapper.writeValueAsString})落成 {@code <saveId>.json}。覆盖 ADR-032 §6.1 列的情形。
 */
final class SyntheticSaves {

	final ObjectMapper mapper = new ObjectMapper();
	final ArchetypeRegistry registry = new ArchetypeRegistry();

	/** 本类写出的、应被导入的存档 saveId(按情形命名)。 */
	static final List<String> IMPORTABLE = List.of("s-rules-creepy", "s-apocalypse", "s-cthulhu", "s-cultivation",
			"s-fusion", "s-life-sim", "s-animal-new", "s-animal-legacy", "s-ended", "s-phase-settling",
			"s-phase-generating");
	/** 写出的、应判为不可读的文件名。 */
	static final List<String> UNREADABLE = List.of("bad-truncated.json", "s-unknown-archetype.json", "bad name.json");
	/** 写出的非存档文件(月账)。 */
	static final String QUOTA = "quota-2026-07.json";

	ArrayNode actions() {
		ArrayNode a = mapper.createArrayNode();
		a.addObject().put("id", "A").put("text", "谨慎观察四周").put("hint", "");
		a.addObject().put("id", "B").put("text", "原地等待").put("hint", "");
		return a;
	}

	ObjectNode world(String archetype) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4").put("mode", "single");
		w.putArray("archetypes").add(archetype);
		w.putObject("world").put("title", "t").put("background", "b").put("dangerLevel", "low").put("tone", "x");
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		registry.meta(archetype).attributes().forEach(ax -> attrs.put(ax.key(), 50));
		w.putArray("rules").addObject().put("id", 1).put("content", "熄灯后不要回头。").put("isTrue", true)
				.put("hiddenLogic", "回头即被标记。").put("discovered", false);
		w.putArray("endings").addObject().put("id", "dead").put("title", "死亡").put("condition", "体力归零")
				.put("outcome", "failure").put("reached", false);
		return w;
	}

	ObjectNode fusionWorld() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4").put("mode", "hybrid");
		w.putArray("archetypes").add("cultivation").add("rules_creepy");
		w.putObject("world").put("title", "识海").put("background", "b").put("dangerLevel", "high").put("tone", "x");
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		List<AttributeAxis> axes = registry.resolveAxes(List.of("cultivation", "rules_creepy"));
		axes.forEach(ax -> attrs.put(ax.key(), 60));
		w.putArray("rules");
		w.putArray("endings").addObject().put("id", "body_perish").put("title", "身死道消").put("condition", "气血归零")
				.put("outcome", "failure").put("reached", false);
		return w;
	}

	GameSession session(String saveId, ObjectNode world) {
		return new GameSessionManager(mapper).create(saveId, world, actions(), Set.of(), Map.of(), Set.of());
	}

	String encode(GameSession s) {
		return mapper.writeValueAsString(SessionDocument.encode(s, mapper));
	}

	ObjectNode doc(GameSession s) {
		return SessionDocument.encode(s, mapper);
	}

	static void write(Path dir, String fileName, String text) throws IOException {
		Files.writeString(dir.resolve(fileName), text, StandardCharsets.UTF_8);
	}

	/** 按 ADR-032 §6.1 写满一个目录(含月账、traces/、坏 JSON、decode 必拒、文件名非法)。 */
	void writeAll(Path dir) throws IOException {
		Files.createDirectories(dir);
		write(dir, "s-rules-creepy.json", encode(session("s-rules-creepy", world("rules_creepy"))));
		write(dir, "s-apocalypse.json", encode(session("s-apocalypse", world("apocalypse"))));
		write(dir, "s-cthulhu.json", encode(session("s-cthulhu", world("cthulhu"))));
		write(dir, "s-cultivation.json", encode(session("s-cultivation", world("cultivation"))));
		write(dir, "s-fusion.json", encode(session("s-fusion", fusionWorld())));
		write(dir, "s-life-sim.json", encode(session("s-life-sim", world("life_sim"))));
		write(dir, "s-animal-new.json", encode(session("s-animal-new", world("animal_life"))));
		write(dir, "s-animal-legacy.json", legacyAnimal("s-animal-legacy"));
		ObjectNode ended = doc(session("s-ended", world("rules_creepy")));
		((ObjectNode) ended.path("state")).put("status", "ended");
		write(dir, "s-ended.json", mapper.writeValueAsString(ended));
		write(dir, "s-phase-settling.json", withPhase("s-phase-settling", TurnPhase.SETTLING));
		write(dir, "s-phase-generating.json", withPhase("s-phase-generating", TurnPhase.GENERATING));
		// 不可读三种
		write(dir, "bad-truncated.json", "{\"schemaVersion\":\"0.4\",\"world\":{\"archetypes\":[\"rules_cr");
		ObjectNode unknown = doc(session("s-unknown-archetype", world("rules_creepy")));
		((ArrayNode) unknown.path("world").path("archetypes")).removeAll().add("no_such_world");
		write(dir, "s-unknown-archetype.json", mapper.writeValueAsString(unknown));
		write(dir, "bad name.json", encode(session("bad-name", world("rules_creepy"))));
		// 非存档:月账
		write(dir, QUOTA, "{\"month\":\"2026-07\",\"spentCny\":0.0132}");
		// traces/:不递归,不该被读;里面放一份合法存档 —— 递归扫描的变异会把它导进库
		Path traces = Files.createDirectories(dir.resolve("traces"));
		write(traces, "s-rules-creepy.trace.jsonl", "{\"schema\":1}\n");
		write(traces, "s-in-traces.json", encode(session("s-in-traces", world("rules_creepy"))));
	}

	/** 《动物人生》旧局:没有局面键(置顶陷阱 2:decode→encode 会补上 {"legacy":true})。 */
	String legacyAnimal(String saveId) {
		ObjectNode doc = doc(session(saveId, world("animal_life")));
		doc.remove(BoxSceneState.DOC_KEY);
		((ObjectNode) doc.path("state")).put("turn", 5);
		return mapper.writeValueAsString(doc);
	}

	/** phaseHint 为 SETTLING / GENERATING 的存档(置顶陷阱 1:decode 会把相位重置)。 */
	String withPhase(String saveId, TurnPhase phase) {
		GameSession s = session(saveId, world("rules_creepy"));
		s.phase().set(phase);
		return encode(s);
	}

	/**
	 * 测试面 1:多余空白、换过的键序、非 ASCII 字符与 {@code é} 转义 —— 合法存档,但字节形态
	 * 与 encode 产出完全不同。
	 */
	String oddlyFormatted(String saveId) {
		ObjectNode doc = doc(session(saveId, world("rules_creepy")));
		ObjectNode reordered = mapper.createObjectNode();
		List<String> keys = new ArrayList<>();
		for (Iterator<String> it = doc.propertyNames().iterator(); it.hasNext();) {
			keys.add(it.next());
		}
		for (int i = keys.size() - 1; i >= 0; i--) {
			reordered.set(keys.get(i), doc.get(keys.get(i)));
		}
		String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reordered);
		String marked = pretty.replaceFirst("\"title\" : \"t\"", "\"title\"   :\t\"t\\\\u00e9 é 中文\"");
		if (marked.equals(pretty)) {
			throw new IllegalStateException("没找到要替换的 title");
		}
		return "\n  " + marked.replace("\n", "\n\t ") + "  \n";
	}
}
