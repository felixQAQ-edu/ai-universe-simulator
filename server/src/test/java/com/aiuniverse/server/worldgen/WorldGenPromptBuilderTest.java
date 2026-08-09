package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

/**
 * ④ world-gen 提示词生产化(设计稿 §7、ADR-007、ADR-008 决策 3):钉住通用骨架核心约定不被改丢——
 * id 类型约定(F-001)、泄露硬化、openingNarrative + 初始动作、纯 JSON 无围栏——以及 per-archetype 注入块
 * (worldview/数值轴/中文名/衰减)按 archetype 分派对。
 */
class WorldGenPromptBuilderTest {

	private final WorldGenPromptBuilder builder = new WorldGenPromptBuilder(new ArchetypeRegistry());

	@Test
	void worldPromptCarriesSchemaAndIdTypeAndLeakHardening() {
		String p = builder.buildWorldPrompt("rules_creepy");

		// 通用骨架:id 类型约定(F-001):rules 整数 / endings snake_case 字符串。
		assertThat(p).contains("整数").contains("snake_case");
		// 泄露硬化:禁止隐藏逻辑进玩家可见字段。
		assertThat(p).contains("hiddenLogic").contains("openingNarrative");
		// 纯 JSON、无围栏(异于回合的哨兵)。
		assertThat(p).contains("纯 JSON").doesNotContain("<<<DELTA>>>");
		// 初始决策圈 + 开场叙事。
		assertThat(p).contains("availableActions").contains("openingNarrative");
		// 透传 archetype + 规则怪谈注入块(数值轴体力/理智)。
		assertThat(p).contains("rules_creepy").contains("规则怪谈");
		assertThat(p).contains("hp(体力").contains("san(理智");
	}

	@Test
	void worldPromptInjectsApocalypseBlockWithHungerDecay() {
		String p = builder.buildWorldPrompt("apocalypse");

		// 通用骨架照旧(单点维护,不per模式重抄)。
		assertThat(p).contains("整数").contains("snake_case").contains("纯 JSON").contains("openingNarrative");
		// 末日注入块:世界观 + 数值轴 hp(体力)/hunger(饥饿,带衰减提示)+ archetype。
		assertThat(p).contains("apocalypse").contains("末日生存");
		assertThat(p).contains("hp(体力").contains("hunger(饥饿");
		assertThat(p).contains("衰减"); // 衰减提示注入(回合 AI 据此持续落 hunger 衰减)
		// 末日不应注入规则怪谈的 san 轴。
		assertThat(p).doesNotContain("san(理智");
	}

	@Test
	void worldPromptInjectsCthulhuBlockWithKnowledgeAxisAndBaselineConvention() {
		String p = builder.buildWorldPrompt("cthulhu");

		// 通用骨架照旧(单点维护,加世界不重抄)。
		assertThat(p).contains("整数").contains("snake_case").contains("纯 JSON").contains("openingNarrative");
		// 克苏鲁注入块:模式名 + 三轴 hp/san/knowledge(复用 hp/san + 特有 knowledge)。
		assertThat(p).contains("cthulhu").contains("克苏鲁");
		assertThat(p).contains("hp(体力").contains("san(理智").contains("knowledge(禁忌知识");
		// knowledge 累积型双刃 + knowledge↔san 联动注入(回合 AI 据此落联动)。
		assertThat(p).contains("累积").contains("联动");
		// F-012 约定:正基线、绝不给 0(让 AI 别把 knowledge 落 0 触发引擎误触底)。
		assertThat(p).contains("绝不给 0");
	}

	@Test
	void worldPromptInjectsCultivationBlockWithThreeAxesAndNoTruthRules() {
		String p = builder.buildWorldPrompt("cultivation");

		// 通用骨架照旧(单点维护,加世界不重抄)+ schemaVersion 升 0.4(ADR-010)。
		assertThat(p).contains("整数").contains("snake_case").contains("纯 JSON").contains("openingNarrative");
		assertThat(p).contains("\"0.4\""); // schemaVersion 0.3→0.4(ADR-010 outcome 新增)
		// ADR-010:结局极性 outcome 指引注入(AI 标 success/failure/neutral)。
		assertThat(p).contains("outcome").contains("failure").contains("success");
		// 修仙注入块:模式名 + 三轴 气血/灵力/境界。
		assertThat(p).contains("cultivation").contains("修仙");
		assertThat(p).contains("hp(气血").contains("mana(灵力").contains("realm(境界");
		// 境界=累积型主角轴 + 灵力消耗提示注入。
		assertThat(p).contains("累积").contains("消耗");
		// ADR-009 F-013:心法守则型 → rules 不输出 isTrue(对照真假守则世界)。
		assertThat(p).contains("不要输出 isTrue");
		assertThat(p).doesNotContain("真假混合"); // 修仙不走真假守则口径
	}

	@Test
	void worldPromptCarriesQualitativeActionHintDirective() {
		// #1 选择反馈定性版(ADR-011):hint 由「可空」升为「必给」——每选项一句定性风险/代价/张力提示。
		String p = builder.buildWorldPrompt("rules_creepy");
		assertThat(p).contains("hint 必给");
		assertThat(p).contains("一句定性的风险/代价/张力提示");
		// 不掷骰边界:不写精确成功率数字 + hint 是叙事提示不据此判定(呼应引擎只读透传)。
		assertThat(p).contains("不写精确成功率数字");
		assertThat(p).contains("hint 是叙事提示,不代表引擎会据此判定");
		// 骨架里已不再是旧的「hint 可空」措辞。
		assertThat(p).doesNotContain("hint 可空");
	}

	// ── ADR-013 混合模式融合分支(修仙×规则怪谈,host=修仙)──────────────

	@Test
	void fusionPromptCarriesBothBlocksHybridModeAndFusedAxes() {
		String p = builder.buildFusionPrompt("cultivation", "rules_creepy");

		// 通用输出格式骨架照旧(id 类型/纯 JSON/openingNarrative/schemaVersion 0.4),不重抄错。
		assertThat(p).contains("整数").contains("snake_case").contains("纯 JSON").contains("openingNarrative");
		assertThat(p).contains("\"0.4\"").doesNotContain("<<<DELTA>>>"); // 保 json_object 无哨兵(守 ADR-007)
		// mode:hybrid + archetypes 两个(host 在前)。
		assertThat(p).contains("\"hybrid\"").contains("\"cultivation\",\"rules_creepy\"");
		// 双注入块:两个 archetype 的 displayName + worldview 片段都在(不是轮流、是并列注入一次融合)。
		assertThat(p).contains("修仙").contains("规则怪谈").contains("识海");
		// 融合轴清单:host 气血/灵力/境界 + 换皮道心(san 换皮为道心,前端/提示词都用道心)。
		assertThat(p).contains("hp(气血").contains("mana(灵力").contains("realm(境界").contains("san(道心");
	}

	@Test
	void fusionPromptCarriesThreeLeversProtectiveEndingAndAdr011Guardrails() {
		String p = builder.buildFusionPrompt("cultivation", "rules_creepy");

		// 三根杠杆:数值入守则 / 先辨体系再辨真假 / 真假对射用修仙常识裁。
		assertThat(p).contains("数值入守则");
		assertThat(p).contains("先辨体系、再辨真假");
		assertThat(p).contains("真假对射、以修仙常识裁");
		// 守则真假同墙:真传心法(不带 isTrue)+ 心魔伪笔(带 isTrue:false + hiddenLogic)。
		assertThat(p).contains("真传心法").contains("心魔伪笔");
		// 护道结局位(success)+ 走火入魔(failure)。
		assertThat(p).contains("护道功成").contains("多谢道友护道").contains("走火入魔");
		// 承重接缝(守 ADR-011):守则不写精确成功率/判定规则、门槛只作叙事毒饵、回溯守则无跨回合追踪。
		assertThat(p).contains("绝不写精确成功率数字");
		assertThat(p).contains("不得承诺、也不代表引擎会据境界数值拦截破境或改变判定");
		assertThat(p).contains("不要求任何跨回合追踪");
	}

	@Test
	void fusionRepairPromptPointsFusedAxisKeys() {
		String p = builder.buildRepairPrompt(List.of("cultivation", "rules_creepy"), "{\"mode\":\"hybrid\"}",
				List.of("rules: 缺失或非数组"));
		assertThat(p).contains("完整 world JSON").contains("rules: 缺失或非数组");
		assertThat(p).contains("hp/mana/realm/san"); // 修复点名融合轴集
	}

	@Test
	void repairPromptCarriesErrorsAndFailedRawAndAxes() {
		String p = builder.buildRepairPrompt("apocalypse", "{\"mode\":\"single\"}", List.of("rules: 缺失或非数组"));
		assertThat(p).contains("校验错误").contains("rules: 缺失或非数组").contains("{\"mode\":\"single\"}");
		assertThat(p).contains("完整 world JSON");
		assertThat(p).contains("hp/hunger"); // 修复点名本模式数值轴
	}

	// ── ADR-020 刀 2 · 结局条数派生槽(B2)+《寻常》种子池 ─────────────────────

	/**
	 * 派生槽 parity 线:四个既有世界一律拿缺省 {@code "2-3"} = 抽槽前的常量原值,
	 * <b>这一行是四世界 world-gen prompt 逐字节零回归的守护点</b>(同 ADR-014 参数化融合骨架的证法)。
	 */
	@Test
	void endingCountSlotDefaultsToPreExtractionConstantForAllFourWorlds_ADR020() {
		for (String a : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation")) {
			assertThat(builder.buildWorldPrompt(a))
					.as("%s 结局条数须仍是抽槽前的 2-3", a)
					.contains("- endings:2-3 个,含至少一个");
		}
	}

	/** 《寻常》拿 {@code "5-6"}(区间而非精确 6:精确计数对生成脆,守 ADR-007 头号失败模式)。 */
	@Test
	void ordinaryLifeGetsWiderEndingCountRange_ADR020() {
		String p = builder.buildWorldPrompt("life_sim");
		assertThat(p).contains("- endings:5-6 个,含至少一个");
		assertThat(p).doesNotContain("- endings:2-3 个");
	}

	/**
	 * 《寻常》种子池:开局家庭种子**随机且不公平**(四条难度不同),但公平性硬约束逐字在场——
	 * 「不是好牌局的圆满打个折」。约束抽成常量、每次只注入被挑中的那一条种子 → prompt 里<b>恰好一次</b>。
	 */
	@Test
	void ordinaryLifeSeedCarriesFairStartConstraintExactlyOnce_ADR020() {
		for (int i = 0; i < 20; i++) { // 种子随机挑 → 多跑几次覆盖池中各条
			String p = builder.buildWorldPrompt("life_sim");
			assertThat(p).contains("任何一种开局都必须能通向圆满结局")
					.contains("不是好牌局的圆满打个折");
			assertThat(p.split("任何一种开局都必须能通向圆满结局", -1).length - 1)
					.as("公平性硬约束在 prompt 里恰好出现一次").isEqualTo(1);
			// 种子仍守既有格式:末尾的危险等级没有被硬约束挤断(骨架据它取 dangerLevel)。
			assertThat(p).containsPattern("危险等级 (low|medium|high|extreme)。【开局公平性");
		}
	}

	// ── ADR-020 §10 · world-gen 侧 per-archetype 指令槽 ────────────────────────

	/**
	 * 槽的 parity 线:未登记世界拿空串,且 {@code %8$s} 挂在骨架<b>末行行尾</b>而非独占一行——
	 * 独占一行时空串会多留一个换行,四世界 prompt 当场不再逐字节相同。这条同时钉住「不多留换行」。
	 */
	@Test
	void worldGenDirectiveSlotIsEmptyForFourWorldsAndLeavesNoBlankLine_ADR020() {
		for (String a : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation")) {
			String p = builder.buildWorldPrompt(a);
			assertThat(p).as("%s 不该拿到任何 per-archetype world-gen 指令", a)
					.doesNotContain("【本世界专属");
			// 骨架末行紧接「按以下种子生成世界」,中间不得多出空行(空串槽的 parity 证据)。
			assertThat(p).contains("隐藏逻辑只进 hiddenLogic。\n\n按以下种子生成世界");
		}
	}

	/**
	 * 《寻常》的 E:六条结局池 + 极性 + 早逝三段式 + 回收一条 + 中文轴名。
	 * 按 ADR-020 §10 补记的「读几次」判据,这些都是<b>一辈子只读一次</b>的东西 → 落 world-gen 槽;
	 * 逐回合生效的(密度表 / 铁律 / 退化判据 / 留白 / 承诺作用域 / 收束气力下限)归 event-loop 槽(刀 3)。
	 */
	@Test
	void ordinaryLifeWorldGenDirectiveCarriesEndingPoolAndEarlyDeath_ADR020() {
		String p = builder.buildWorldPrompt("life_sim");
		assertThat(p).contains("【本世界专属 · 结局池与早逝");
		// 六条结局齐 + 三种极性都出现。
		for (String t : List.of("寿终·圆满", "寿终·有憾", "寿终·无人", "早逝", "自动播放", "未竟")) {
			assertThat(p).as("结局池缺 %s", t).contains(t);
		}
		assertThat(p).contains("outcome=success").contains("outcome=failure").contains("outcome=neutral");
		// B1 立字分工(ADR-020 §8 补记):气力归零=早逝 / 寿终=走完回合表未归零。
		assertThat(p).contains("气力归零 = 早逝").contains("老死不是生命力耗到零");
		// 早逝三段式 + 明确否决「完全由种子决定」。
		assertThat(p).contains("早逝三段式").contains("绝不写成「早逝完全由开局种子决定」");
		// 回收一条且只回收一条,且不解释。
		assertThat(p).contains("只回收一条").contains("不解释、不点破");
		// condition 用中文轴名(pickFailureEnding 按中文名 contains 匹配,写英文 key 会错配)。
		assertThat(p).contains("气力 / 热望 / 路口 / 牵挂");
		assertThat(p).doesNotContain("condition 例:vigor");
		// 逐回合的东西不许提前混进 world-gen 槽(「读几次」判据的守护点,刀 3 才写它们)。
		// 注意只看槽内:worldview 里那句时间结构概述("40-55 回合,回合密度不匀速")是 B 的一部分、合法。
		String slot = p.substring(p.indexOf("【本世界专属"));
		assertThat(slot).as("回合密度六段表属 event-loop 槽").doesNotContain("回合密度");
		assertThat(slot).as("收束气力下限属 event-loop 槽").doesNotContain("最后 3-5 回合");
	}
}
