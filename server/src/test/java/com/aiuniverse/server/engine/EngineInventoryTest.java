package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-046 D3 · 回合尾巴 {@code stateUpdate.inventory} 由引擎落账(测试面 2、3、4、6、7)。
 *
 * <p>三态:给了 → 整表替换;缺键 → 沿用(与 apply 前逐字节相同);空数组 → 写成 {@code []}。
 * 超过 {@link Engine#MAX_INVENTORY} 条截断并记恰好一条 issue;降级回合不动;落盘往返与视图 2 / 3 都带新清单。
 */
class EngineInventoryTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private ObjectNode world() {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.4").put("mode", "single");
		w.putArray("archetypes").add("cultivation");
		w.putObject("world").put("title", "t").put("background", "b").put("dangerLevel", "low").put("tone", "克制");
		ObjectNode ch = w.putObject("character");
		ch.putObject("attributes").put("hp", 80).put("mana", 60).put("realm", 10);
		ch.putArray("traits").add("谨慎");
		ch.putArray("inventory").add("半瓶聚气丹").add("一柄旧剑");
		w.putArray("rules").addObject().put("id", 1).put("content", "子时不可行功").put("hiddenLogic", "h").put("discovered", false);
		w.putArray("endings").addObject().put("id", "e").put("title", "结").put("condition", "c").put("reached", false);
		return w;
	}

	private Engine engine() {
		return new Engine(world(), mapper, Set.of("realm"));
	}

	/** 照真实回合尾巴的样子:数值轴 + timeline,可选 inventory(raw JSON,null = 不写这个键)。 */
	private JsonNode parsed(String inventoryJson) {
		String inv = inventoryJson == null ? "" : ",\"inventory\":" + inventoryJson;
		return mapper.readTree("{\"narrative\":\"你服下一粒丹药。\",\"stateUpdate\":{\"hp\":80,\"mana\":70,\"realm\":10,"
				+ "\"timeline\":\"夜半\"" + inv + "},\"triggeredRuleIds\":[],\"discoveredRuleIds\":[],"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"调息\"},{\"id\":\"B\",\"text\":\"出洞\"}],\"ending\":null}");
	}

	private JsonNode inventory(Engine e) {
		return e.world().path("character").path("inventory");
	}

	// ── 测试面 2:三态 ──────────────────────────────────────────────────

	@Test
	void givenListReplacesWholeInventory() {
		Engine e = engine();
		e.apply(parsed("[\"聚气丹(余一粒)\",\"一柄旧剑\",\"下品灵石\"]"), "A");
		assertThat(mapper.writeValueAsString(inventory(e))).isEqualTo("[\"聚气丹(余一粒)\",\"一柄旧剑\",\"下品灵石\"]");
		assertThat(e.issues()).isEmpty();
	}

	@Test
	void absentKeyKeepsInventoryByteIdentical() {
		Engine e = engine();
		String before = mapper.writeValueAsString(e.world().path("character"));
		e.apply(parsed(null), "A");
		ObjectNode ch = (ObjectNode) e.world().path("character").deepCopy();
		assertThat(mapper.writeValueAsString(ch)).as("character(含 inventory 与位置)与 apply 前逐字节相同").isEqualTo(before);
		assertThat(e.issues()).isEmpty();
	}

	@Test
	void emptyArrayMeansNothingLeft() {
		Engine e = engine();
		e.apply(parsed("[]"), "A");
		assertThat(inventory(e).isArray()).isTrue();
		assertThat(inventory(e)).isEmpty();
		// 下一回合缺键 → 仍是空数组,不回退到开局清单。
		e.apply(parsed(null), "B");
		assertThat(inventory(e)).isEmpty();
	}

	/** 世界里原本没有 inventory 键:给了就写上;缺键不凭空造一个。 */
	@Test
	void worldWithoutInventoryKey() {
		ObjectNode w = world();
		((ObjectNode) w.get("character")).remove("inventory");
		Engine e = new Engine(w, mapper, Set.of("realm"));
		e.apply(parsed(null), "A");
		assertThat(e.world().path("character").has("inventory")).isFalse();
		e.apply(parsed("[\"火折子\"]"), "A");
		assertThat(mapper.writeValueAsString(inventory(e))).isEqualTo("[\"火折子\"]");
	}

	// ── 测试面 3:截断 ──────────────────────────────────────────────────

	@Test
	void nineItemsTruncateToFirstEightWithExactlyOneIssue() {
		Engine e = engine();
		e.apply(parsed(items(9)), "A");
		assertThat(inventory(e)).hasSize(Engine.MAX_INVENTORY);
		assertThat(inventory(e).get(0).asString()).isEqualTo("物0");
		assertThat(inventory(e).get(7).asString()).isEqualTo("物7");
		assertThat(e.issues()).containsExactly("T1 inventory 超过 8 条,截断为前 8 条(ADR-046)");
	}

	@Test
	void eightItemsAreKeptWithoutIssue() {
		Engine e = engine();
		e.apply(parsed(items(8)), "A");
		assertThat(inventory(e)).hasSize(8);
		assertThat(e.issues()).isEmpty();
	}

	private static String items(int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < n; i++) {
			sb.append(i == 0 ? "" : ",").append("\"物").append(i).append('"');
		}
		return sb.append(']').toString();
	}

	// ── 测试面 4:降级回合不动 ─────────────────────────────────────────

	@Test
	void noOpTurnLeavesInventoryUntouched() {
		Engine e = engine();
		e.apply(parsed("[\"聚气丹(余一粒)\"]"), "A");
		String before = mapper.writeValueAsString(inventory(e));
		e.applyNoOp("墙在渗水。", "B");
		assertThat(mapper.writeValueAsString(inventory(e))).isEqualTo(before);
	}

	// ── 测试面 6:往返 ──────────────────────────────────────────────────

	@Test
	void rewrittenInventorySurvivesPersistAndRestore() {
		Engine e = engine();
		e.apply(parsed("[\"聚气丹(余一粒)\"]"), "A");
		JsonNode doc = mapper.readTree(mapper.writeValueAsString(e.toPersistedState()));
		assertThat(doc.path("world").path("character").path("inventory").toString()).isEqualTo("[\"聚气丹(余一粒)\"]");
		Engine r = Engine.restore(doc, mapper, Set.of("realm"), java.util.Map.of(), Set.of());
		assertThat(r.contextJson()).contains("\"inventory\":[\"聚气丹(余一粒)\"]");
		assertThat(mapper.writeValueAsString(r.toPersistedState())).isEqualTo(mapper.writeValueAsString(e.toPersistedState()));
	}

	/** 旧 T0 存档(inventory 是开局清单、从未改写)照常回载,清单原样。 */
	@Test
	void untouchedT0SaveRestoresAsBefore() {
		Engine t0 = engine();
		JsonNode doc = t0.toPersistedState();
		Engine r = Engine.restore(doc, mapper, Set.of("realm"), java.util.Map.of(), Set.of());
		assertThat(mapper.writeValueAsString(inventory(r))).isEqualTo("[\"半瓶聚气丹\",\"一柄旧剑\"]");
	}

	// ── 测试面 7:视图 ──────────────────────────────────────────────────

	@Test
	void rewrittenInventoryAppearsInModelAndClientViews() {
		Engine e = engine();
		e.apply(parsed("[\"聚气丹(余一粒)\"]"), "A");
		assertThat(e.contextJson()).as("视图 2").contains("\"inventory\":[\"聚气丹(余一粒)\"]");
		assertThat(e.toClientState().path("character").path("inventory").toString()).as("视图 3")
				.isEqualTo("[\"聚气丹(余一粒)\"]");
		assertThat(e.contextJson()).doesNotContain("半瓶聚气丹");
	}

	/** 落账写的是引擎手里那份 world;出口(视图 3)照旧 deepCopy,已发出的快照不随之改变。 */
	@Test
	void snapshotsAreCopiesNotLiveViews() {
		Engine e = engine();
		ObjectNode client = e.toClientState();
		e.apply(parsed("[\"火折子\"]"), "A");
		assertThat(((ArrayNode) client.path("character").path("inventory")).get(0).asString()).isEqualTo("半瓶聚气丹");
		assertThat(e.issues()).isEmpty();
	}
}
