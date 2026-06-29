package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;

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

	private AttributeAxis axis(ArchetypeMeta m, String key) {
		return m.attributes().stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
	}
}
