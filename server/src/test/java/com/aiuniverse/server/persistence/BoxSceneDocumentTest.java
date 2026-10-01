package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.eventloop.BoxSceneState;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.GameSessionManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-028 刀 2a · 局面键的落盘口径:<b>是不是旧局只看文档里有没有局面键</b>(不看回合号)。
 * 三种存档:没有键的 / 有键但处于纸箱前的 / 有旧局标记的。
 */
class BoxSceneDocumentTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private ListAppender<ILoggingEvent> logs;
	private Logger stateLogger;

	@BeforeEach
	void attach() {
		stateLogger = (Logger) LoggerFactory.getLogger(BoxSceneState.class);
		logs = new ListAppender<>();
		logs.start();
		stateLogger.addAppender(logs);
	}

	@AfterEach
	void detach() {
		stateLogger.detachAppender(logs);
	}

	private ObjectNode world(String archetype) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4").put("mode", "single");
		w.putArray("archetypes").add(archetype);
		w.putObject("world").put("title", "t").put("background", "b").put("dangerLevel", "low").put("tone", "x");
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		registry.meta(archetype).attributes().forEach(a -> attrs.put(a.key(), 50));
		w.putArray("rules");
		w.putArray("endings").addObject().put("id", "e").put("title", "终").put("condition", "c")
				.put("reached", false);
		return w;
	}

	/** 经 GameSessionManager.create(新局)→ encode → 改 turn → 视需要删 / 换局面键。 */
	private ObjectNode newGameDoc(String archetype, int turn) {
		GameSessionManager mgr = new GameSessionManager(mapper);
		GameSession s = mgr.create("s1", world(archetype), mapper.createArrayNode(), Set.of(), Map.of(), Set.of());
		ObjectNode doc = SessionDocument.encode(s, mapper);
		((ObjectNode) doc.path("state")).put("turn", turn);
		return doc;
	}

	private List<String> warns() {
		return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	@Test
	void newAnimalLifeGameWritesTheSceneKeyAtCreation() {
		ObjectNode doc = newGameDoc("animal_life", 0);
		JsonNode box = doc.path(BoxSceneState.DOC_KEY);
		assertThat(box.isObject()).isTrue();
		assertThat(box.path("legacy").asBoolean()).isFalse();
		assertThat(box.path("situation").isNull()).isTrue();
	}

	@Test
	void otherWorldsNeverGetTheKey_andIgnoreOneIfPresent() {
		for (String a : List.of("rules_creepy", "life_sim", "cultivation")) {
			ObjectNode doc = newGameDoc(a, 3);
			assertThat(doc.has(BoxSceneState.DOC_KEY)).as(a).isFalse();
			doc.set(BoxSceneState.DOC_KEY, mapper.createObjectNode().put("legacy", true));
			assertThat(SessionDocument.decode("s1", doc, mapper, registry).boxScene()).as(a).isNull();
		}
		assertThat(warns()).isEmpty();
	}

	@Test
	void docWithoutKeyIsLegacyRegardlessOfTurn_warns_andTheNextSaveWritesTheMarker() {
		for (int turn : List.of(0, 5, 9, 15, 40)) {
			ObjectNode doc = newGameDoc("animal_life", turn);
			doc.remove(BoxSceneState.DOC_KEY);
			GameSession s = SessionDocument.decode("s1", doc, mapper, registry);
			assertThat(s.boxScene().isLegacy()).as("turn " + turn).isTrue();
			assertThat(SessionDocument.encode(s, mapper).path(BoxSceneState.DOC_KEY).toString())
					.isEqualTo("{\"legacy\":true}");
		}
		assertThat(warns()).hasSize(5).allMatch(m -> m.contains("旧局"));
	}

	@Test
	void docWithKeyBeforeTheBoxIsANewGame_andRoundTripsByteForByte() {
		ObjectNode doc = newGameDoc("animal_life", 9);
		GameSession s = SessionDocument.decode("s1", doc, mapper, registry);
		assertThat(s.boxScene().isLegacy()).isFalse();
		assertThat(SessionDocument.encode(s, mapper).path(BoxSceneState.DOC_KEY))
				.isEqualTo(doc.path(BoxSceneState.DOC_KEY));
		assertThat(warns()).isEmpty();
	}

	@Test
	void legacyMarkerStaysLegacy() {
		ObjectNode doc = newGameDoc("animal_life", 2);
		doc.set(BoxSceneState.DOC_KEY, mapper.createObjectNode().put("legacy", true));
		GameSession s = SessionDocument.decode("s1", doc, mapper, registry);
		assertThat(s.boxScene().isLegacy()).isTrue();
		assertThat(warns()).isEmpty(); // 已标记的旧局不再重复告警
	}

	@Test
	void settledStateRoundTrips() {
		ObjectNode doc = newGameDoc("animal_life", 20);
		ObjectNode box = (ObjectNode) doc.path(BoxSceneState.DOC_KEY);
		box.put("g", 2).put("result", "R3A").put("settledTurn", 15).put("situation", "EMPTY_HOME");
		box.putObject("slots").put("A", "WAIT_BY_OLD_DOOR").put("B", "SEARCH_FOR_FOOD").put("C", "LEAVE_HOME");
		box.putArray("history").addObject().put("turn", 19).put("intent", "WAIT_BY_OLD_DOOR");
		GameSession s = SessionDocument.decode("s1", doc, mapper, registry);
		assertThat(SessionDocument.encode(s, mapper).path(BoxSceneState.DOC_KEY)).isEqualTo(box);
	}

	@Test
	void emptyOrUnknownSituationIsRejected_neverGuessedIntoOne() {
		for (String bad : List.of("", "OUTDOORS", "new_home")) {
			ObjectNode doc = newGameDoc("animal_life", 20);
			ObjectNode box = (ObjectNode) doc.path(BoxSceneState.DOC_KEY);
			box.put("result", "R1").put("settledTurn", 14).put("situation", bad);
			assertThatThrownBy(() -> SessionDocument.decode("s1", doc, mapper, registry)).as(bad)
					.isInstanceOf(IllegalArgumentException.class);
		}
		// 已结算却没有处境(null)同样拒载:空的处境不是任何一种处境,也不替它补一个
		ObjectNode doc = newGameDoc("animal_life", 20);
		((ObjectNode) doc.path(BoxSceneState.DOC_KEY)).put("result", "R1").put("settledTurn", 14);
		assertThatThrownBy(() -> SessionDocument.decode("s1", doc, mapper, registry))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
