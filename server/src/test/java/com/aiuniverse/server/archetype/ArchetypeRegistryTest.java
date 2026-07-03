package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * per-archetype 元数据条目结构(设计稿 §6 测试矩阵第 2 条 / ADR-008 决策 1)——
 * 末日/规则怪谈条目结构正确、attributes key 集合对、衰减提示落点对、已知/已激活枚举判定对。
 */
class ArchetypeRegistryTest {

	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@Test
	void apocalypseHasHpAndHungerWithHungerDecaying() {
		ArchetypeMeta m = registry.meta("apocalypse");
		assertThat(m.displayName()).isEqualTo("末日生存");
		assertThat(m.attributes().stream().map(AttributeAxis::key)).containsExactly("hp", "hunger");
		AttributeAxis hp = axis(m, "hp");
		AttributeAxis hunger = axis(m, "hunger");
		assertThat(hp.displayName()).isEqualTo("体力");
		assertThat(hp.behaviorHint()).as("hp 无特殊逐回合行为").isNull();
		assertThat(hunger.displayName()).isEqualTo("饥饿");
		assertThat(hunger.behaviorHint()).as("hunger 带衰减提示(喂提示词)").isNotNull().contains("衰减");
		assertThat(m.worldview()).isNotBlank();
		assertThat(m.ruleForm()).isNotBlank();
		// 选择屏展示字段(ADR-008 决策 4):钩子 + 氛围标签,玩家可见、非空。
		assertThat(m.tagline()).as("末日一句话钩子").isNotBlank();
		assertThat(m.vibeTag()).as("末日氛围标签").isNotBlank();
	}

	@Test
	void cthulhuHasHpSanKnowledgeWithKnowledgeAccumulatingAndSanLinkage() {
		ArchetypeMeta m = registry.meta("cthulhu");
		assertThat(m.displayName()).isEqualTo("克苏鲁");
		// 签名轴:hp + san(复用)+ knowledge(克苏鲁特有,顺序即面板渲染顺序)。
		assertThat(m.attributes().stream().map(AttributeAxis::key)).containsExactly("hp", "san", "knowledge");
		assertThat(axis(m, "hp").displayName()).isEqualTo("体力");
		assertThat(axis(m, "san").displayName()).isEqualTo("理智");
		AttributeAxis knowledge = axis(m, "knowledge");
		assertThat(knowledge.displayName()).isEqualTo("禁忌知识");
		// hp/san 复用规则怪谈形态,无特殊逐回合行为;knowledge 带累积型双刃 + knowledge↔san 联动提示(喂提示词,引擎不读)。
		assertThat(axis(m, "hp").behaviorHint()).isNull();
		assertThat(axis(m, "san").behaviorHint()).isNull();
		assertThat(knowledge.behaviorHint()).as("knowledge 带行为提示").isNotNull()
				.contains("累积")    // 累积型(求知则上涨)
				.contains("san");    // knowledge↔san 联动(越高 san 流失越快)
		assertThat(m.worldview()).isNotBlank();
		assertThat(m.ruleForm()).as("禁忌知识渐揭,非真假守则").isNotBlank();
		assertThat(m.tagline()).isNotBlank();
		assertThat(m.vibeTag()).isNotBlank();
	}

	@Test
	void listForSelectionPutsActiveFirstThenInactivePlaceholders() {
		List<ArchetypeSummary> list = registry.listForSelection();
		// 已激活四条(含克苏鲁 + 修仙)在前 + 已知未开放两条占位在后。
		assertThat(list.stream().map(ArchetypeSummary::archetype))
				.containsExactly("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim", "cyberpunk");
		// 已激活四条在前、可选、钩子/标签齐。
		for (ArchetypeSummary s : list.subList(0, 4)) {
			assertThat(s.active()).as("已激活可选:%s", s.archetype()).isTrue();
			assertThat(s.displayName()).isNotBlank();
			assertThat(s.tagline()).as("可选卡片有钩子:%s", s.archetype()).isNotBlank();
			assertThat(s.vibeTag()).as("可选卡片有标签:%s", s.archetype()).isNotBlank();
		}
		// 占位两条在后、不可选、仍有中文名(渲染「敬请期待」)。
		for (ArchetypeSummary s : list.subList(4, 6)) {
			assertThat(s.active()).as("未开放占位:%s", s.archetype()).isFalse();
			assertThat(s.displayName()).isNotBlank();
		}
	}

	@Test
	void cultivationHasHpManaRealmWithCorrectAxisRolesAndNoTruthRules() {
		ArchetypeMeta m = registry.meta("cultivation");
		assertThat(m.displayName()).isEqualTo("修仙");
		// 三轴:气血(hp)/灵力(mana)/境界(realm),顺序即面板渲染顺序。
		assertThat(m.attributes().stream().map(AttributeAxis::key)).containsExactly("hp", "mana", "realm");
		AttributeAxis hp = axis(m, "hp");
		AttributeAxis mana = axis(m, "mana");
		AttributeAxis realm = axis(m, "realm");
		assertThat(hp.displayName()).isEqualTo("气血");
		assertThat(mana.displayName()).isEqualTo("灵力");
		assertThat(realm.displayName()).isEqualTo("境界");
		// ADR-009 F-012 轴角色:hp/灵力=depletion(≤0 触底),境界=accumulation(≤0 不触底)。
		assertThat(hp.isAccumulation()).as("气血=depletion").isFalse();
		assertThat(mana.isAccumulation()).as("灵力=depletion").isFalse();
		assertThat(realm.isAccumulation()).as("境界=accumulation").isTrue();
		// ADR-010 F-015 致命轴:气血致命(≤0 死),灵力=非致命资源池(枯竭=力竭非必死),境界累积本就非致命。
		assertThat(hp.isLethal()).as("气血=致命轴").isTrue();
		assertThat(mana.isLethal()).as("灵力=非致命资源池(F-015 关闭)").isFalse();
		assertThat(realm.isLethal()).as("境界=accumulation,恒非致命").isFalse();
		// 灵力带消耗提示、境界带累积提示(喂提示词,引擎不读)。
		assertThat(mana.behaviorHint()).isNotNull().contains("消耗");
		assertThat(realm.behaviorHint()).as("境界累积型").isNotNull().contains("累积");
		// ADR-009 F-013:修仙规则=心法守则型,rules 不带 isTrue。
		assertThat(m.rulesCarryTruth()).as("修仙=心法守则型,无真假").isFalse();
		assertThat(m.ruleForm()).contains("不要输出 isTrue");
		// 灵根做 trait(worldview 提示写进 character.traits),不单开数值轴。
		assertThat(m.worldview()).contains("灵根").contains("traits");
		assertThat(m.tagline()).isNotBlank();
		assertThat(m.vibeTag()).isNotBlank();
	}

	@Test
	void rulesCreepyHasHpAndSanSoBothModesShareMetadataPath() {
		ArchetypeMeta m = registry.meta("rules_creepy");
		assertThat(m.displayName()).isEqualTo("规则怪谈");
		assertThat(m.attributes().stream().map(AttributeAxis::key)).containsExactly("hp", "san");
		assertThat(axis(m, "hp").displayName()).isEqualTo("体力");
		assertThat(axis(m, "san").displayName()).isEqualTo("理智");
		// 规则怪谈无特殊行为轴(对照末日 hunger / 克苏鲁 knowledge)。
		assertThat(m.attributes().stream().allMatch(a -> a.behaviorHint() == null)).isTrue();
		// ADR-010:hp/san 都是生命/致命轴(≤0 死、触发结局极性 gate)。
		assertThat(axis(m, "hp").isLethal()).isTrue();
		assertThat(axis(m, "san").isLethal()).isTrue();
	}

	@Test
	void apocalypseHungerIsLethalDepletion_ADR010() {
		// ADR-010:末日饥饿致死 → hunger=致命 depletion 轴(对照修仙灵力非致命)。
		ArchetypeMeta m = registry.meta("apocalypse");
		assertThat(axis(m, "hp").isLethal()).as("体力致命").isTrue();
		AttributeAxis hunger = axis(m, "hunger");
		assertThat(hunger.isAccumulation()).as("饥饿=depletion").isFalse();
		assertThat(hunger.isLethal()).as("饥饿而亡 → 致命轴").isTrue();
	}

	@Test
	void knownEnumCoversContextSection34AndCthulhu() {
		// CONTEXT §三.4 原 5 枚举 + 克苏鲁(加世界流水线复用上架)都「已知」(init 非法判定用)。
		for (String id : List.of("rules_creepy", "life_sim", "cultivation", "cyberpunk", "apocalypse", "cthulhu")) {
			assertThat(registry.isKnown(id)).as("已知:%s", id).isTrue();
		}
		assertThat(registry.isKnown("not_an_archetype")).isFalse();
	}

	@Test
	void activatedArchetypesIncludeCultivation() {
		assertThat(registry.isActive("rules_creepy")).isTrue();
		assertThat(registry.isActive("apocalypse")).isTrue();
		assertThat(registry.isActive("cthulhu")).isTrue();
		assertThat(registry.isActive("cultivation")).as("修仙本批激活可玩").isTrue();
		// 已知但未激活(占位枚举)→ init 应 400「未开放」。
		assertThat(registry.isActive("life_sim")).isFalse();
		assertThat(registry.isActive("cyberpunk")).isFalse();
		// 未知 id 既不已知也不已激活。
		assertThat(registry.isActive("not_an_archetype")).isFalse();
		assertThat(registry.activeMetas()).hasSize(4);
	}

	@Test
	void metaThrowsForInactiveArchetype() {
		try {
			registry.meta("life_sim");
			assertThat(false).as("未激活 archetype 应抛异常").isTrue();
		} catch (IllegalArgumentException expected) {
			assertThat(expected.getMessage()).contains("life_sim");
		}
	}

	// ── ADR-012 混合模式轴合并(修仙 × 规则怪谈,host=修仙;纯函数、暂未接线)────────

	@Test
	void mergeUnionsAxesHostFirstThenSurvivingForeign() {
		List<AttributeAxis> fused = registry.cultivationRulesCreepyAxes();
		// 并集 = 修仙 {hp,mana,realm} + 规则怪谈存活的 san;host 轴在前保序,san 追加。
		assertThat(fused.stream().map(AttributeAxis::key)).containsExactly("hp", "mana", "realm", "san");
	}

	@Test
	void hpKeyCollisionHostWins() {
		List<AttributeAxis> fused = registry.cultivationRulesCreepyAxes();
		AttributeAxis hp = fused(fused, "hp");
		// 撞键 hp:host=修仙赢 → 取「气血」(非规则怪谈「体力」),bands 也是修仙的气血档。
		assertThat(hp.displayName()).as("hp 撞键 host 优先取气血").isEqualTo("气血");
		assertThat(hp.bands().stream().map(AttributeAxis.Band::label)).contains("气血充盈");
	}

	@Test
	void sanReSkinnedToDaoxinKeepingKeyAndAxisRole() {
		List<AttributeAxis> fused = registry.cultivationRulesCreepyAxes();
		AttributeAxis san = fused(fused, "san");
		// 换皮:displayName「理智」→「道心」+ 新 bands(修仙口吻),但 key/axisRole/lethal 不变(引擎无感)。
		assertThat(san.key()).as("换皮 key 不变").isEqualTo("san");
		assertThat(san.displayName()).as("理智→道心").isEqualTo("道心");
		assertThat(san.bands().stream().map(AttributeAxis.Band::label)).containsExactly("清明", "动摇", "崩缺");
		assertThat(san.isAccumulation()).as("san 仍 depletion").isFalse();
		assertThat(san.isLethal()).as("san 仍致命(道心崩=走火入魔)").isTrue();
		// 对照:原规则怪谈 san 是「理智」——确认换皮真生效、非取到 host 未有的原轴。
		assertThat(axis(registry.meta("rules_creepy"), "san").displayName()).isEqualTo("理智");
	}

	@Test
	void fusedAxisRolesAndLethalityPreserved() {
		List<AttributeAxis> fused = registry.cultivationRulesCreepyAxes();
		// realm 仍 accumulation、mana 仍非致命资源池、hp/san 仍致命(引擎据这些 gate 触底/结局极性)。
		assertThat(fused(fused, "realm").isAccumulation()).as("境界=accumulation").isTrue();
		assertThat(fused(fused, "mana").isAccumulation()).isFalse();
		assertThat(fused(fused, "mana").isLethal()).as("灵力=非致命资源池").isFalse();
		assertThat(fused(fused, "hp").isLethal()).as("气血致命").isTrue();
		assertThat(fused(fused, "san").isLethal()).as("道心致命").isTrue();
	}

	@Test
	void fusedAxesFeedExistingSeedingDerivationCorrectly() {
		List<AttributeAxis> fused = registry.cultivationRulesCreepyAxes();
		// 融合轴集喂现有播种派生(与单模式 GameInitService 共用同一真理源,别新造)。
		assertThat(ArchetypeRegistry.accumulationKeys(fused)).containsExactly("realm");
		assertThat(ArchetypeRegistry.nonLethalKeys(fused)).containsExactly("mana");
		assertThat(ArchetypeRegistry.axisDisplayNames(fused))
				.containsExactly(entry("hp", "气血"), entry("mana", "灵力"), entry("realm", "境界"), entry("san", "道心"));
		// 致命 depletion 轴 = 非累积 && 致命 = {hp, san}(引擎据此在濒零时 gate 结局)。
		assertThat(fused.stream().filter(a -> !a.isAccumulation() && a.isLethal()).map(AttributeAxis::key))
				.containsExactlyInAnyOrder("hp", "san");
	}

	@Test
	void mergeIsPureAndDoesNotMutateSourceMetas() {
		// 合并前后 host/foreign 元数据轴集不变(纯函数、无副作用)。
		registry.cultivationRulesCreepyAxes();
		assertThat(registry.meta("cultivation").attributes().stream().map(AttributeAxis::key))
				.containsExactly("hp", "mana", "realm");
		assertThat(axis(registry.meta("cultivation"), "hp").displayName()).isEqualTo("气血");
		assertThat(registry.meta("rules_creepy").attributes().stream().map(AttributeAxis::key))
				.containsExactly("hp", "san");
		assertThat(axis(registry.meta("rules_creepy"), "san").displayName()).isEqualTo("理智");
	}

	// ── ADR-013 融合组合登记(fusedAxes 接活 mergeAxes;有序、方向敏感)──────────

	@Test
	void fusionSupportedOnlyForRegisteredOrderedCombo() {
		// round 1 只登记「修仙×规则怪谈(host=修仙)」一组、方向敏感。
		assertThat(registry.isFusionSupported("cultivation", "rules_creepy")).isTrue();
		// 反向(host=规则怪谈)未登记 —— 换皮方向不成立。
		assertThat(registry.isFusionSupported("rules_creepy", "cultivation")).isFalse();
		// 两个已激活但未登记为融合组合。
		assertThat(registry.isFusionSupported("apocalypse", "cthulhu")).isFalse();
	}

	@Test
	void fusedAxesMatchesCulcivationRulesCreepyCombo() {
		// fusedAxes(host,foreign) 与命名 combo 便捷方法同结果(单一真理源)。
		assertThat(registry.fusedAxes("cultivation", "rules_creepy").stream().map(AttributeAxis::key))
				.containsExactly("hp", "mana", "realm", "san");
	}

	@Test
	void fusedAxesThrowsForUnregisteredCombo() {
		try {
			registry.fusedAxes("rules_creepy", "cultivation"); // 反向未登记
			assertThat(false).as("未登记融合组合应抛异常").isTrue();
		} catch (IllegalArgumentException expected) {
			assertThat(expected.getMessage()).contains("不支持的融合组合");
		}
	}

	private AttributeAxis fused(List<AttributeAxis> axes, String key) {
		return axes.stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}

	private AttributeAxis axis(ArchetypeMeta m, String key) {
		return m.attributes().stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}
}
