package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

/**
 * ADR-014 第二组合「守则即补给」(rules_creepy × apocalypse,host=rules_creepy):
 * 融合轴集 / 换皮(含 behaviorHint override)/ 播种派生(首例三致命轴)/ world-gen 槽位渲染。
 */
class RulesCreepyApocalypseFusionTest {

	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@Test
	void comboIsRegisteredAndFusesToThreeAxes() {
		assertThat(registry.isFusionSupported("rules_creepy", "apocalypse")).isTrue();
		// 方向敏感:反向 host 未登记。
		assertThat(registry.isFusionSupported("apocalypse", "rules_creepy")).isFalse();

		List<AttributeAxis> axes = registry.fusedAxes("rules_creepy", "apocalypse");
		// host 轴在前保序(hp/san 体力/理智),foreign 撞键 hp 并掉、hunger 换皮「补给」追加。
		assertThat(axes).extracting(AttributeAxis::key).containsExactly("hp", "san", "hunger");
		assertThat(axes).extracting(AttributeAxis::displayName).containsExactly("体力", "理智", "补给");
	}

	@Test
	void hungerSkinOverridesBehaviorHintButKeepsEngineFacingFields() {
		AttributeAxis hunger = registry.fusedAxes("rules_creepy", "apocalypse").get(2);
		AttributeAxis original = registry.meta("apocalypse").attributes().get(1);

		// ADR-014 决策 4:behaviorHint override 位(原 hint 含「饥饿值」与换皮名打架)。
		assertThat(hunger.behaviorHint()).contains("补给随回合自然消耗").doesNotContain("饥饿值");
		// 引擎面字段一律不换(key/axisRole/lethal/min/max)。
		assertThat(hunger.key()).isEqualTo(original.key());
		assertThat(hunger.axisRole()).isEqualTo(original.axisRole());
		assertThat(hunger.isLethal()).isEqualTo(original.isLethal());
		// bands 换皮为补给口吻(充足/紧缺/断粮)。
		assertThat(hunger.bands()).extracting(AttributeAxis.Band::label).containsExactly("充足", "紧缺", "断粮");
	}

	@Test
	void roundOneSkinWithoutHintOverrideStaysUnchanged() {
		// ADR-012 原形态(不带 behaviorHint 的换皮)零变化:道心沿用规则怪谈 san 的原 hint(null)。
		AttributeAxis daoxin = registry.fusedAxes("cultivation", "rules_creepy").get(3);
		AttributeAxis originalSan = registry.meta("rules_creepy").attributes().get(1);
		assertThat(daoxin.displayName()).isEqualTo("道心");
		assertThat(daoxin.behaviorHint()).isEqualTo(originalSan.behaviorHint());
	}

	@Test
	void derivationsYieldFirstEverThreeLethalAxes() {
		List<AttributeAxis> axes = registry.fusedAxes("rules_creepy", "apocalypse");
		// 三轴全 depletion 全 lethal(首例三致命轴):accumulation={}、nonLethal={}、致命={hp,san,hunger}。
		assertThat(ArchetypeRegistry.accumulationKeys(axes)).isEmpty();
		assertThat(ArchetypeRegistry.nonLethalKeys(axes)).isEmpty();
		assertThat(ArchetypeRegistry.axisDisplayNames(axes))
				.containsExactly(
						java.util.Map.entry("hp", "体力"),
						java.util.Map.entry("san", "理智"),
						java.util.Map.entry("hunger", "补给"));
	}

	@Test
	void worldGenPromptRendersDerivedAndCopySlots() {
		String prompt = new WorldGenPromptBuilder(registry).buildFusionPrompt("rules_creepy", "apocalypse");

		// 双注入块(host/foreign worldview 头)+ hybrid 结构。
		assertThat(prompt).contains("· 规则怪谈").contains("· 末日生存")
				.contains("\"hybrid\"").contains("\"rules_creepy\",\"apocalypse\"");
		// 派生槽:结局条数随三致命轴走(4-5)+ 致命轴中文名清单。
		assertThat(prompt).contains("- endings:4-5 个").contains("每个致命数值轴(体力/理智/补给)");
		// 文案槽:真页/假页称呼进骨架 rules 约束。
		assertThat(prompt).contains("【假页(isTrue:false)至少 3 条、真页(不带 isTrue)至少 3 条】")
				.contains("真守则(真页)不带 isTrue;假守则(假页)带 isTrue:false");
		// 融合轴清单含换皮后的补给(含 behaviorHint override)。
		assertThat(prompt).contains("- hunger(补给,0-100;补给随回合自然消耗");
	}
}
