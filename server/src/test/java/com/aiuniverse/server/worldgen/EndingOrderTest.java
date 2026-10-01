package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-028 刀 2b · world-gen 结局顺序后处理:新生成的《动物人生》世界里,「撞上」被确定性地挪到所有
 * failure 结局之前,使身子触底时引擎兜底命中「撞上」而不是「太近了」(后者只许屋外)。引擎一行不动。
 */
class EndingOrderTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private ListAppender<ILoggingEvent> logs;
	private Logger logger;

	@BeforeEach
	void attach() {
		logger = (Logger) LoggerFactory.getLogger(EndingOrder.class);
		logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void detach() {
		logger.detachAppender(logs);
	}

	/** 模型把「太近了」排在「撞上」前面 —— 两条 condition 都提到【身子】。 */
	private ObjectNode animalWorld(boolean withHit) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4").put("mode", "single");
		w.putArray("archetypes").add("animal_life");
		w.putObject("character").putObject("attributes")
				.put("body", 5).put("warmth", 40).put("ground", 40).put("close", 80);
		w.putArray("rules");
		var e = w.putArray("endings");
		e.addObject().put("id", "survived").put("title", "熬过去了")
				.put("condition", "回合走到尽头、【身子】仍在 15 以上").put("outcome", "success").put("reached", false);
		e.addObject().put("id", "too_close").put("title", "太近了")
				.put("condition", "【近人】高位而【身子】归零").put("outcome", "failure").put("reached", false);
		e.addObject().put("id", "winter").put("title", "冬天")
				.put("condition", "【暖】长期低位后身子耗尽").put("outcome", "neutral").put("reached", false);
		if (withHit) {
			e.addObject().put("id", "hit").put("title", "撞上")
					.put("condition", "【身子】在途中归零").put("outcome", "failure").put("reached", false);
		}
		e.addObject().put("id", "nameless").put("title", "没有名字的")
				.put("condition", "【身子】耗尽而【地面】低位").put("outcome", "failure").put("reached", false);
		return w;
	}

	private static List<String> titles(ObjectNode w) {
		List<String> out = new ArrayList<>();
		w.path("endings").forEach(e -> out.add(e.path("title").asString()));
		return out;
	}

	@Test
	void hitMovesBeforeEveryFailureEnding_restKeepRelativeOrder() {
		ObjectNode w = EndingOrder.apply(List.of("animal_life"), animalWorld(true));
		assertThat(titles(w)).containsExactly("熬过去了", "撞上", "太近了", "冬天", "没有名字的");
	}

	@Test
	void afterPostProcessing_bodyBottomOutPicksHit() {
		ObjectNode w = EndingOrder.apply(List.of("animal_life"), animalWorld(true));
		List<AttributeAxis> axes = new ArchetypeRegistry().meta("animal_life").attributes();
		Engine engine = new Engine(w, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "它趴下了。");
		t.putObject("stateUpdate").put("body", 0).put("warmth", 40).put("ground", 40).put("close", 80);
		t.putArray("availableActions").addObject().put("id", "A").put("text", "趴着");
		engine.apply(t, "A");
		assertThat(engine.status()).isEqualTo("ended");
		List<String> reached = new ArrayList<>();
		for (JsonNode e : engine.world().path("endings")) {
			if (e.path("reached").asBoolean()) {
				reached.add(e.path("title").asString());
			}
		}
		assertThat(reached).containsExactly("撞上");
	}

	/** 正对照:不做后处理时,同样的触底兜底命中的是「太近了」—— 证明上一条测的是后处理而不是引擎碰巧。 */
	@Test
	void withoutPostProcessing_theSameBottomOutWouldPickTooClose() {
		ObjectNode w = animalWorld(true);
		List<AttributeAxis> axes = new ArchetypeRegistry().meta("animal_life").attributes();
		Engine engine = new Engine(w, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "它趴下了。");
		t.putObject("stateUpdate").put("body", 0).put("warmth", 40).put("ground", 40).put("close", 80);
		t.putArray("availableActions").addObject().put("id", "A").put("text", "趴着");
		engine.apply(t, "A");
		assertThat(engine.world().path("endings").get(1).path("title").asString()).isEqualTo("太近了");
		assertThat(engine.world().path("endings").get(1).path("reached").asBoolean()).isTrue();
	}

	@Test
	void missingHitLogsWarnAndLeavesOrderAlone() {
		ObjectNode w = animalWorld(false);
		List<String> before = titles(w);
		EndingOrder.apply(List.of("animal_life"), w);
		assertThat(titles(w)).isEqualTo(before);
		assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("撞上"));
	}

	@Test
	void otherWorldsAndFusionAreUntouched() {
		for (List<String> a : List.of(List.of("rules_creepy"), List.of("life_sim"),
				List.of("cultivation", "rules_creepy"))) {
			ObjectNode w = animalWorld(true);
			String before = w.toString();
			EndingOrder.apply(a, w);
			assertThat(w.toString()).as(a.toString()).isEqualTo(before);
		}
		assertThat(logs.list).isEmpty();
	}
}
