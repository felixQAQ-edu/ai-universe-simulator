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
		assertThat(hp.decay()).as("hp 不衰减").isNull();
		assertThat(hunger.displayName()).isEqualTo("饥饿");
		assertThat(hunger.decay()).as("hunger 带衰减提示(喂提示词)").isNotNull().contains("衰减");
		assertThat(m.worldview()).isNotBlank();
		assertThat(m.ruleForm()).isNotBlank();
	}

	@Test
	void rulesCreepyHasHpAndSanSoBothModesShareMetadataPath() {
		ArchetypeMeta m = registry.meta("rules_creepy");
		assertThat(m.displayName()).isEqualTo("规则怪谈");
		assertThat(m.attributes().stream().map(AttributeAxis::key)).containsExactly("hp", "san");
		assertThat(axis(m, "hp").displayName()).isEqualTo("体力");
		assertThat(axis(m, "san").displayName()).isEqualTo("理智");
		// 规则怪谈无衰减轴(对照末日 hunger)。
		assertThat(m.attributes().stream().allMatch(a -> a.decay() == null)).isTrue();
	}

	@Test
	void knownEnumCoversContextSection34() {
		// CONTEXT §三.4 全部 5 个枚举都「已知」(init 非法判定用)。
		for (String id : List.of("rules_creepy", "life_sim", "cultivation", "cyberpunk", "apocalypse")) {
			assertThat(registry.isKnown(id)).as("已知:%s", id).isTrue();
		}
		assertThat(registry.isKnown("not_an_archetype")).isFalse();
	}

	@Test
	void onlyRulesCreepyAndApocalypseAreActivatedThisBatch() {
		assertThat(registry.isActive("rules_creepy")).isTrue();
		assertThat(registry.isActive("apocalypse")).isTrue();
		// 已知但未激活(占位枚举)→ init 应 400「未开放」。
		assertThat(registry.isActive("life_sim")).isFalse();
		assertThat(registry.isActive("cultivation")).isFalse();
		assertThat(registry.isActive("cyberpunk")).isFalse();
		// 未知 id 既不已知也不已激活。
		assertThat(registry.isActive("not_an_archetype")).isFalse();
		assertThat(registry.activeMetas()).hasSize(2);
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
