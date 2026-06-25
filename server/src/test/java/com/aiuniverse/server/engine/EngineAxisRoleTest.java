package com.aiuniverse.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-009 F-012 正解:数值轴角色(depletion/accumulation)触底分支。
 *
 * <p>引擎只加一个二分——<b>accumulation 轴 ≤0 不触底致死</b>(0=安全起点,如修仙境界初入修行 / 克苏鲁全然无知),
 * <b>depletion 轴 ≤0 仍触底</b>(= 现状)。角色经 3 参构造 {@code new Engine(world, mapper, accumulationKeys)}
 * 传入;默认 2 参构造 = 全 depletion(golden parity 走此路,字节级守零回归,见 {@link EngineGoldenTest})。
 * 引擎对轴语义仍无知——只据 key 集合 gate(守 ADR-008)。
 */
class EngineAxisRoleTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 修仙最小世界:attributes={hp,mana,realm};hp/mana=depletion,realm(境界)=accumulation。 */
	private ObjectNode cultivationWorld(int hp, int mana, int realm) {
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.3");
		ObjectNode attrs = w.putObject("character").putObject("attributes");
		attrs.put("hp", hp).put("mana", mana).put("realm", realm);
		w.putArray("rules");
		var endings = w.putArray("endings");
		endings.addObject().put("id", "ascend").put("title", "飞升")
				.put("condition", "境界圆满,渡劫飞升").put("reached", false);
		endings.addObject().put("id", "qi_deviation").put("title", "气血枯竭")
				.put("condition", "hp 归零、油尽灯枯").put("reached", false);
		return w;
	}

	private ObjectNode turn(Map<String, Integer> stateUpdate) {
		ObjectNode t = mapper.createObjectNode();
		t.put("narrative", "灵气在经脉中游走。");
		ObjectNode upd = t.putObject("stateUpdate");
		stateUpdate.forEach(upd::put);
		var a = t.putArray("availableActions");
		a.addObject().put("id", "A").put("text", "继续打坐");
		a.addObject().put("id", "B").put("text", "外出历练");
		return t;
	}

	@Test
	void accumulationAxisAtZeroDoesNotTriggerEnded() {
		// 境界=0(初入修行,健康开局态),hp/mana 健康 → 引擎绝不因 realm≤0 判死。
		Engine eng = new Engine(cultivationWorld(80, 50, 0), mapper, Set.of("realm"));
		eng.apply(turn(Map.of("hp", 80, "mana", 50, "realm", 0)), "A");
		assertThat(eng.status()).as("accumulation 轴 ≤0 不触底").isEqualTo("ongoing");
		assertThat(reachedId(eng)).as("不该盖任何坏结局").isNull();
	}

	@Test
	void depletionAxisStillTriggersEndedAlongsideAccumulation() {
		// 同一三轴世界,realm=0(不致死)但 hp 触底 → 仍 ended,且兜底挑 hp 条件的坏结局(非境界)。
		Engine eng = new Engine(cultivationWorld(80, 50, 0), mapper, Set.of("realm"));
		eng.apply(turn(Map.of("hp", 0, "mana", 50, "realm", 0)), "A");
		assertThat(eng.status()).as("depletion 轴 hp≤0 仍触底").isEqualTo("ended");
		assertThat(reachedId(eng)).as("§5 兜底挑 hp 条件坏结局,非 realm").isEqualTo("qi_deviation");
	}

	@Test
	void manaIsDepletionSoBottomsOutLikeHp() {
		// 灵力(mana)是 depletion 资源池 → ≤0 触底(对照 realm 不触底),证三轴里只 realm 豁免。
		Engine eng = new Engine(cultivationWorld(80, 0, 20), mapper, Set.of("realm"));
		eng.apply(turn(Map.of("hp", 80, "mana", 0, "realm", 20)), "A");
		assertThat(eng.status()).as("depletion 灵力 ≤0 触底").isEqualTo("ended");
	}

	@Test
	void defaultTwoArgConstructorTreatsRealmAsDepletion_provesGoldenSafe() {
		// 不传累积集合(2 参,= 现状)→ realm 被当 depletion,≤0 即触底。
		// 这正是 golden parity 走的路径:旧行为字节级不变(新 accumulation 分支只在显式传集合时生效)。
		Engine eng = new Engine(cultivationWorld(80, 50, 0), mapper); // 无累积集合
		eng.apply(turn(Map.of("hp", 80, "mana", 50, "realm", 0)), "A");
		assertThat(eng.status()).as("默认全 depletion:realm≤0 仍触底(守现状)").isEqualTo("ended");
	}

	@Test
	void knowledgeAccumulationAtZeroNoLongerMisfires_cthulhuRootFix() {
		// 克苏鲁 knowledge 从「提示词正基线兜」升级为引擎根治:knowledge=0 不再误触底(F-012 关闭)。
		ObjectNode w = mapper.createObjectNode();
		w.put("schemaVersion", "0.3");
		w.putObject("character").putObject("attributes").put("hp", 90).put("san", 70).put("knowledge", 0);
		w.putArray("rules");
		w.putArray("endings").addObject().put("id", "escaped").put("title", "全身而退")
				.put("condition", "无知是福").put("reached", false);
		Engine eng = new Engine(w, mapper, Set.of("knowledge"));
		eng.apply(turn(Map.of("hp", 90, "san", 70, "knowledge", 0)), "A");
		assertThat(eng.status()).as("knowledge=0 不再误触底").isEqualTo("ongoing");
	}

	private String reachedId(Engine eng) {
		for (JsonNode e : eng.world().path("endings")) {
			if (e.path("reached").asBoolean(false)) {
				return e.path("id").asString();
			}
		}
		return null;
	}
}
