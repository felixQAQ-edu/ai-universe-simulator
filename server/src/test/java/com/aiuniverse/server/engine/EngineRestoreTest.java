package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-015 附录 A · restore 守护测试(A 项验收硬门)—— 把 golden 护城河延伸到持久化边界
 * (同当年 transform parity 延伸到切分+回灌):
 * <ol>
 *   <li><b>落盘-回载对拍</b>:跑 N 回合 → {@link Engine#toPersistedState()} → {@link Engine#restore}
 *       → 再导出,两份 JSON <b>逐字节相等</b>;且与不落盘直跑实例的导出逐字节相等。</li>
 *   <li><b>续跑一致</b>:restore 后续跑一回合(喂相同 parsed),与直跑 N+1 回合的结果逐字节一致
 *       (持久化导出 + contextJson + toClientState 三视图都对拍)。</li>
 *   <li><b>多场景</b>:单体(golden 录制回合)+ 融合(轴集经 {@link ArchetypeRegistry#fusedAxes}
 *       原路重派生,ADR-015「轴语义集不落盘」)+ ended 局。</li>
 * </ol>
 * 附录 A 第 3 条(落盘路径不得位于 static resources 之下)属落盘层(Slice 2 {@code GameSessionManager}),
 * 不在本测试范围——Engine 不碰文件系统。
 *
 * <p>非法输入 = <b>拒载抛 {@link IllegalArgumentException},不半载</b>:缺 schemaVersion/world/state
 * 或 schemaVersion 不识,一律先全量校验后构造。
 */
class EngineRestoreTest {

	private final ObjectMapper mapper = new ObjectMapper();

	// ── 场景 1:单体(golden 录制回合,rules_creepy 轴集经 registry 重派生)─────────

	@Test
	void roundTripPersistExportIsByteIdentical_goldenSingleArchetype() {
		JsonNode fx = goldenFixture();
		List<AttributeAxis> axes = new ArchetypeRegistry().meta("rules_creepy").attributes();
		for (String path : List.of("B1", "B2", "B3")) {
			Engine direct = engineWithAxes((ObjectNode) fx.get("world"), axes);
			for (JsonNode t : fx.path("paths").path(path).path("turns")) {
				direct.apply(t.get("parsed"), t.get("actionId").asString());
			}
			String exported = mapper.writeValueAsString(direct.toPersistedState());
			Engine restored = restoreWithAxes(mapper.readTree(exported), axes);
			String reExported = mapper.writeValueAsString(restored.toPersistedState());
			assertThat(reExported).as("%s 导出→restore→再导出 逐字节相等", path).isEqualTo(exported);
		}
	}

	@Test
	void resumedEngineAppliesNextTurnIdenticallyToUninterruptedRun() {
		JsonNode fx = goldenFixture();
		List<AttributeAxis> axes = new ArchetypeRegistry().meta("rules_creepy").attributes();
		JsonNode turns = fx.path("paths").path("B1").path("turns");
		int n = turns.size();

		// 直跑:全部 N 回合一气呵成。
		Engine direct = engineWithAxes((ObjectNode) fx.get("world"), axes);
		for (JsonNode t : turns) {
			direct.apply(t.get("parsed"), t.get("actionId").asString());
		}

		// 断点续跑:N-1 回合 → 导出 → restore → 续跑第 N 回合。
		Engine beforeBreak = engineWithAxes((ObjectNode) fx.get("world"), axes);
		for (int i = 0; i < n - 1; i++) {
			beforeBreak.apply(turns.get(i).get("parsed"), turns.get(i).get("actionId").asString());
		}
		Engine resumed = restoreWithAxes(beforeBreak.toPersistedState(), axes);
		JsonNode last = turns.get(n - 1);
		resumed.apply(last.get("parsed"), last.get("actionId").asString());

		// 三视图逐字节对拍:持久化导出 / contextJson(喂模型)/ toClientState(消毒出网)。
		assertThat(mapper.writeValueAsString(resumed.toPersistedState()))
				.as("续跑后持久化导出 == 直跑").isEqualTo(mapper.writeValueAsString(direct.toPersistedState()));
		assertThat(resumed.contextJson()).as("续跑后 contextJson == 直跑").isEqualTo(direct.contextJson());
		assertThat(mapper.writeValueAsString(resumed.toClientState()))
				.as("续跑后消毒投影 == 直跑").isEqualTo(mapper.writeValueAsString(direct.toClientState()));
	}

	// ── 场景 2:融合(修仙×规则怪谈四轴;轴语义集不落盘、经 fusedAxes 原路重派生)──────

	@Test
	void fusionWorldRoundTripsAndRederivedAxisRolesStillGovernAfterRestore() {
		List<AttributeAxis> fusedAxes = new ArchetypeRegistry().fusedAxes("cultivation", "rules_creepy");
		Engine direct = engineWithAxes(fusionWorld(), fusedAxes);
		direct.apply(fusionTurn(60, 30, 20, 70), "A");
		direct.apply(fusionTurn(55, 0, 0, 65), "B"); // 灵力 0(非致命)+ 境界 0(累积)→ 不得触底

		String exported = mapper.writeValueAsString(direct.toPersistedState());
		Engine restored = restoreWithAxes(mapper.readTree(exported), fusedAxes);
		assertThat(mapper.writeValueAsString(restored.toPersistedState()))
				.as("融合局往返逐字节相等").isEqualTo(exported);
		assertThat(restored.status()).as("灵力/境界归零不触底(重派生轴集在场)").isEqualTo("ongoing");

		// restore 后轴角色仍生效:致命轴 hp 触底 → ended 且兜底挑气血(中文名)对轴失败结局。
		restored.apply(fusionTurn(0, 10, 0, 65), "A");
		assertThat(restored.status()).isEqualTo("ended");
		assertThat(reachedEndingId(restored)).as("§5 兜底按中文轴名挑气血结局").isEqualTo("body_perish");
	}

	// ── 场景 3:ended 局 ────────────────────────────────────────────────

	@Test
	void endedGameRoundTripsWithStatusAndReachedMarksPreserved() {
		List<AttributeAxis> axes = new ArchetypeRegistry().meta("rules_creepy").attributes();
		JsonNode fx = goldenFixture();
		Engine direct = engineWithAxes((ObjectNode) fx.get("world"), axes);
		// 一回合把 hp 打到 0 → 触底 ended + §5 兜底结局。
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "冷光熄灭。");
		t.putObject("stateUpdate").put("hp", 0).put("san", 40);
		direct.apply(t, "A");
		assertThat(direct.status()).isEqualTo("ended");

		String exported = mapper.writeValueAsString(direct.toPersistedState());
		Engine restored = restoreWithAxes(mapper.readTree(exported), axes);
		assertThat(restored.status()).isEqualTo("ended");
		assertThat(mapper.writeValueAsString(restored.toPersistedState())).isEqualTo(exported);
	}

	// ── 持久化格式:引擎部分五字段;容忍 session 层字段 ───────────────────────

	@Test
	void persistedStateCarriesEngineFieldsAndSchemaVersionFromWorld() {
		Engine eng = new Engine(minimalWorld(), mapper);
		eng.apply(simpleTurn(70, 60), "A");
		ObjectNode doc = eng.toPersistedState();
		assertThat(doc.path("schemaVersion").asString(null)).isEqualTo("0.4");
		assertThat(doc.path("world").isObject()).isTrue();
		assertThat(doc.path("world").has("state")).as("world 内不残留 state(顶层已有)").isFalse();
		assertThat(doc.path("world").path("character").path("attributes").path("hp").asInt())
				.as("导出须携带落账后的当前数值,非初值").isEqualTo(70);
		assertThat(doc.path("state").path("turn").asInt()).isEqualTo(1);
		assertThat(doc.path("state").path("status").asString()).isEqualTo("ongoing");
		assertThat(doc.path("state").path("log").isArray()).isTrue();
		assertThat(doc.path("triggered").isArray()).isTrue();
		assertThat(doc.path("issues").isArray()).isTrue();
	}

	@Test
	void restoreIgnoresSessionLevelFieldsInSameDocument() {
		Engine eng = new Engine(minimalWorld(), mapper);
		eng.apply(simpleTurn(70, 60), "A");
		ObjectNode doc = eng.toPersistedState();
		// Slice 2 的 GameSessionManager 会在同一文档补 session 层字段;Engine.restore 容忍并忽略。
		ArrayNode actions = doc.putArray("currentActions");
		actions.addObject().put("id", "A").put("text", "继续");
		doc.put("phaseHint", "AWAITING_ACTION");
		Engine restored = Engine.restore(doc, mapper, Set.of(), Map.of(), Set.of());
		assertThat(mapper.writeValueAsString(restored.toPersistedState()))
				.isEqualTo(mapper.writeValueAsString(eng.toPersistedState()));
	}

	// ── 非法输入:拒载抛异常,不半载 ─────────────────────────────────────

	@Test
	void restoreRejectsMissingOrUnknownSchemaVersion() {
		ObjectNode doc = validPersistedDoc();
		doc.remove("schemaVersion");
		assertThatThrownBy(() -> restorePlain(doc)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("schemaVersion");
		ObjectNode bad = validPersistedDoc();
		bad.put("schemaVersion", "0.1");
		assertThatThrownBy(() -> restorePlain(bad)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("schemaVersion");
	}

	@Test
	void restoreRejectsMissingWorldOrState() {
		ObjectNode noWorld = validPersistedDoc();
		noWorld.remove("world");
		assertThatThrownBy(() -> restorePlain(noWorld)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("world");
		ObjectNode noState = validPersistedDoc();
		noState.remove("state");
		assertThatThrownBy(() -> restorePlain(noState)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("state");
	}

	@Test
	void restoreRejectsMalformedStateFields() {
		ObjectNode noTurn = validPersistedDoc();
		((ObjectNode) noTurn.get("state")).remove("turn");
		assertThatThrownBy(() -> restorePlain(noTurn)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("turn");
		ObjectNode badLog = validPersistedDoc();
		((ObjectNode) badLog.get("state")).put("log", "not-an-array");
		assertThatThrownBy(() -> restorePlain(badLog)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("log");
		ObjectNode badTriggered = validPersistedDoc();
		badTriggered.put("triggered", "oops");
		assertThatThrownBy(() -> restorePlain(badTriggered)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("triggered");
	}

	// ── helpers ────────────────────────────────────────────────────────

	private JsonNode goldenFixture() {
		try (InputStream in = getClass().getResourceAsStream("/golden/event-loop-golden.json")) {
			assertThat(in).as("golden 夹具应存在").isNotNull();
			return mapper.readTree(in);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** 与播种层同路:轴集 → 三份语义集静态派生(单一真理源)→ 全参构造。 */
	private Engine engineWithAxes(ObjectNode world, List<AttributeAxis> axes) {
		return new Engine(world.deepCopy(), mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
	}

	/** restore 侧同一重派生路径(ADR-015:轴语义集不落盘,由 world.archetypes 经 registry 重算)。 */
	private Engine restoreWithAxes(JsonNode persisted, List<AttributeAxis> axes) {
		return Engine.restore(persisted, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
	}

	private Engine restorePlain(JsonNode persisted) {
		return Engine.restore(persisted, mapper, Set.of(), Map.of(), Set.of());
	}

	private ObjectNode validPersistedDoc() {
		Engine eng = new Engine(minimalWorld(), mapper);
		eng.apply(simpleTurn(70, 60), "A");
		return eng.toPersistedState();
	}

	private ObjectNode minimalWorld() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4");
		w.put("mode", "single");
		w.putArray("archetypes").add("rules_creepy");
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		attrs.put("hp", 100).put("san", 100);
		w.putArray("rules");
		ArrayNode endings = w.putArray("endings");
		endings.addObject().put("id", "dead").put("title", "死亡").put("condition", "hp 归零")
				.put("outcome", "failure").put("reached", false);
		return w;
	}

	private ObjectNode fusionWorld() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4");
		w.put("mode", "hybrid");
		ArrayNode arch = w.putArray("archetypes");
		arch.add("cultivation").add("rules_creepy");
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		attrs.put("hp", 80).put("mana", 60).put("realm", 10).put("san", 90);
		w.putArray("rules");
		ArrayNode endings = w.putArray("endings");
		endings.addObject().put("id", "body_perish").put("title", "身死道消")
				.put("condition", "气血归零、油尽灯枯").put("outcome", "failure").put("reached", false);
		endings.addObject().put("id", "mind_shatter").put("title", "道心崩缺")
				.put("condition", "道心归零、神魂俱灭").put("outcome", "failure").put("reached", false);
		endings.addObject().put("id", "guard_dao").put("title", "护道功成")
				.put("condition", "境界≥60 且识破全部伪笔").put("outcome", "success").put("reached", false);
		return w;
	}

	private ObjectNode fusionTurn(int hp, int mana, int realm, int san) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "识海中的字迹微微发烫。");
		ObjectNode upd = t.putObject("stateUpdate");
		upd.put("hp", hp).put("mana", mana).put("realm", realm).put("san", san);
		return t;
	}

	private ObjectNode simpleTurn(int hp, int san) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "走廊的灯闪了一下。");
		t.putObject("stateUpdate").put("hp", hp).put("san", san);
		return t;
	}

	private String reachedEndingId(Engine eng) {
		for (JsonNode e : eng.world().path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				return e.path("id").asString(null);
			}
		}
		return null;
	}
}
