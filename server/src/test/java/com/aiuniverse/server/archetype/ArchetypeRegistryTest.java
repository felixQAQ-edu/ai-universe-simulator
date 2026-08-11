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
		// 已激活五条(含克苏鲁 + 修仙 + ADR-020《寻常》)在前 + 已知未开放一条占位在后。
		assertThat(list.stream().map(ArchetypeSummary::archetype))
				.containsExactly("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim", "cyberpunk");
		// 已激活五条在前、可选、钩子/标签齐。
		for (ArchetypeSummary s : list.subList(0, 5)) {
			assertThat(s.active()).as("已激活可选:%s", s.archetype()).isTrue();
			assertThat(s.displayName()).isNotBlank();
			assertThat(s.tagline()).as("可选卡片有钩子:%s", s.archetype()).isNotBlank();
			assertThat(s.vibeTag()).as("可选卡片有标签:%s", s.archetype()).isNotBlank();
		}
		// 占位一条在后、不可选、仍有中文名(渲染「敬请期待」)。
		for (ArchetypeSummary s : list.subList(5, 6)) {
			assertThat(s.active()).as("未开放占位:%s", s.archetype()).isFalse();
			assertThat(s.displayName()).isNotBlank();
		}
	}

	/**
	 * ADR-019 只读投影:融合组合目录与 {@code isFusionSupported} **同一个真相源**——
	 * 逐条回喂 registry 自己的合法性判定,防「投影了一份别的表」;
	 * 顺序确定(`Map.of` 无序,故实现按 key 排序,消费方与测试都不依赖不保证的顺序)。
	 */
	@Test
	void listFusionCombosProjectsRegisteredCombosInStableOrder() {
		List<FusionSummary> combos = registry.listFusionCombos();
		assertThat(combos.stream().map(FusionSummary::key))
				.containsExactly("cultivation×rules_creepy", "rules_creepy×apocalypse");
		for (FusionSummary c : combos) {
			assertThat(registry.isFusionSupported(c.host(), c.foreign()))
					.as("投影的每一对都真的可融合:%s", c.key()).isTrue();
			assertThat(c.key()).isEqualTo(c.host() + "×" + c.foreign());
			// host 在前(ADR-012/013 有序双值);反向组合未登记 → 前端拖反方向应被拒。
			assertThat(registry.isFusionSupported(c.foreign(), c.host()))
					.as("方向敏感,反向未登记:%s", c.key()).isFalse();
		}
		assertThat(registry.listFusionCombos()).as("重复调用顺序一致").isEqualTo(combos);
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
		assertThat(registry.isActive("life_sim")).as("《寻常》ADR-020 刀 1 激活既有占位").isTrue();
		// 已知但未激活(占位枚举)→ init 应 400「未开放」。life_sim 激活后 cyberpunk 是仅存的守护样本。
		assertThat(registry.isActive("cyberpunk")).isFalse();
		// 未知 id 既不已知也不已激活。
		assertThat(registry.isActive("not_an_archetype")).isFalse();
		assertThat(registry.activeMetas()).hasSize(5);
	}

	@Test
	void metaThrowsForInactiveArchetype() {
		// 守「已知但未激活 → meta() 抛异常」。原样本 life_sim 已由 ADR-020 激活 → 换 cyberpunk 继续守这条。
		try {
			registry.meta("cyberpunk");
			assertThat(false).as("未激活 archetype 应抛异常").isTrue();
		} catch (IllegalArgumentException expected) {
			assertThat(expected.getMessage()).contains("cyberpunk");
		}
	}

	// ── ADR-020《寻常》:一生制世界族首个实例(刀 1 后端登记)────────────────────

	/**
	 * ADR-020 §3 的承重点:两条「归零不死」轴必须真的被算进<b>非致命集</b>(引擎硬保证 ≤0 既不触底致死、
	 * 也不触发结局极性 gate),不靠提示词自律;气力是唯一致命轴。
	 */
	@Test
	void ordinaryLifeLongingAndCrossroadsAreNonLethalResourceAxes_ADR020() {
		ArchetypeMeta m = registry.meta("life_sim");
		assertThat(m.displayName()).as("对外名复用既有占位").isEqualTo("人生模拟");
		assertThat(m.attributes().stream().map(AttributeAxis::key))
				.containsExactly("vigor", "longing", "crossroads", "ties");

		// 热望 / 路口 = depletion + lethal=false(resource);牵挂 = accumulation;气力 = 唯一致命轴。
		assertThat(ArchetypeRegistry.nonLethalKeys(m.attributes()))
				.as("归零不死两轴进非致命集").containsExactlyInAnyOrder("longing", "crossroads");
		assertThat(ArchetypeRegistry.accumulationKeys(m.attributes())).containsExactly("ties");
		assertThat(m.attributes().stream().filter(AttributeAxis::isLethal).map(AttributeAxis::key))
				.as("气力是唯一致命轴").containsExactly("vigor");
		assertThat(axis(m, "longing").isAccumulation()).as("热望是会掉的 depletion,非 accumulation").isFalse();
		assertThat(axis(m, "crossroads").isAccumulation()).isFalse();
	}

	/**
	 * ADR-018 severity 派生(不手写):两条非致命 depletion 轴与纯累积的牵挂全 NEUTRAL——
	 * 归零不死的轴绝不能被渲染成危险态;只有致命的气力最低档 DANGER、次低 CAUTION。
	 */
	@Test
	void ordinaryLifeSeverityDerivesNeutralForNonLethalAxes_ADR018() {
		ArchetypeMeta m = registry.meta("life_sim");
		for (String key : List.of("longing", "crossroads", "ties")) {
			assertThat(axis(m, key).bandRanges().stream().map(AttributeAxis.BandRange::severity))
					.as("%s 归零不死/纯累积 → 全 neutral", key)
					.containsOnly(AttributeAxis.Severity.NEUTRAL);
		}
		// 气力(致命 depletion):bandRanges 按 min 升序 → 表头 = 最低档。
		List<AttributeAxis.BandRange> vigor = axis(m, "vigor").bandRanges();
		assertThat(vigor.get(0).severity()).as("气力最低档 danger").isEqualTo(AttributeAxis.Severity.DANGER);
		assertThat(vigor.get(1).severity()).as("气力次低档 caution").isEqualTo(AttributeAxis.Severity.CAUTION);
		assertThat(vigor.get(2).severity()).isEqualTo(AttributeAxis.Severity.NEUTRAL);
	}

	/**
	 * ADR-020 §5 逐字锁:热望决定「你还想不想选择」(内,意愿)、路口决定「人生还给不给你大的选择」
	 * (外,机会)。两条 hint <b>不得写成同义句</b>——这里钉住各自的语义关键词互不出现,
	 * 防后续写作把两轴混成一件事。
	 */
	@Test
	void longingAndCrossroadsHintsAreNotInterchangeable_ADR020() {
		ArchetypeMeta m = registry.meta("life_sim");
		String longing = axis(m, "longing").behaviorHint();
		String crossroads = axis(m, "crossroads").behaviorHint();
		assertThat(longing).contains("意愿").doesNotContain("外部");
		assertThat(crossroads).contains("外部").doesNotContain("意愿");
		assertThat(longing).isNotEqualTo(crossroads);
		// 两条都须明写「归零不死」,免得后续被当成致命轴写(与 §3 的引擎保证同口径)。
		assertThat(longing).contains("归零不死");
		assertThat(crossroads).contains("归零不死");
	}

	/**
	 * F-023 修法守护:热望的<b>跌必须挂在时钟上</b>,<b>不得再写需要看得见历史的条件</b>。
	 *
	 * <p>三次真机冒烟热望一次都没降到零(刀 4 升到 99 / 刀 6 缓降到 58 / 刀 8 缓降后回升),
	 * 勘察确证根因有二:(a) 跌 3 条中 2 条要累积、涨 3 条全为单次且无限定词 —— 单方向倾斜;
	 * (b) <b>更根本</b>:「反复的妥协」「把自己往后排的日子」需要模型看得见历史,而
	 * {@code compressLog}({@code LOG_KEEP=4})之外只剩 {@code [T85选C]} ——
	 * <b>它在结构上无法评估那两条</b>(同源 F-020 §0.1)。<b>不是写得不够狠,是写了它执行不了的条件。</b>
	 *
	 * <p>对照组 {@code crossroads} 同为 {@code resource}、同引擎却降得下来,差异全在措辞:
	 * 路口跌挂「随年岁」(随时间自动、无需判断)、涨写「只有…才可能」(限定词 + 罕见事件)。
	 * 故本条钉住热望照同一形状重写后的三个要件。
	 */
	@Test
	void longingDeclineIsAnchoredToTheClockNotToInvisibleHistory_F023() {
		String longing = axis(registry.meta("life_sim"), "longing").behaviorHint();

		// (1) 跌挂时钟:模型每回合都拿得到「当前处于哪个人生阶段」,无须回看历史。
		assertThat(longing).contains("随年岁").contains("人生阶段");
		// (2) 涨被限定死(照路口句式),不再是日常可及的单次事件。
		assertThat(longing).contains("只有").contains("才可能");
		// (3) 行为侧只保留【单回合可判】的那条 —— 四个选项就在眼前,不需要历史。
		assertThat(longing).contains("本回合");
		// (4) ⚠️ 回归闸:不得再出现需要累积判断的措辞(模型看不见,写了也执行不了)。
		assertThat(longing).doesNotContain("反复").doesNotContain("连续");
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
