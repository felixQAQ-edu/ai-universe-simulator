package com.aiuniverse.server.eventloop;

import java.util.List;
import java.util.Map;

import com.aiuniverse.server.archetype.LifetimeFamily;

/**
 * 一生制时钟表的<b>登记处</b>(ADR-021 刀 2)。
 *
 * <p><b>本文件是唯一允许出现 per-world 字面量的地方</b> —— 人类年龄段、阶段名、出口措辞、人称、
 * 终点词都在这里;{@link LifeStage} 与 {@link LifeStageTable} 是纯机制,一个字面量都不许有
 * (由 {@code LifeStageParameterizationTest} 的源码级断言守护)。
 *
 * <p><b>登记面必须与族成员表严格一致</b>:类加载时断言
 * {@code TABLES.keySet() == LifetimeFamily.lifetimeMembers()} ——
 * 少登记一张表 = 那个一生制世界<b>没有时钟</b>,而这恰恰是本刀在修的那类静默失效
 * (原 {@code VIGOR_KEY} 硬编码对动物<b>直接 return 且不报错</b>)。
 * <b>把「忘了登记」从静默失效换成加载即响。</b>
 */
final class LifeStageTables {

	private LifeStageTables() {
	}

	/**
	 * 《寻常》(`life_sim`)的时钟表 —— <b>ADR-020 刀 5 的七段原样搬过来,内容一字未改</b>。
	 *
	 * <p><b>⚠️ 暮年段(T36–43)是刀 5 新增的,ADR-020 原密度表里没有</b>:原表末段直接接在老年之后,
	 * 反解后「一回合一天」要覆盖 20 个回合,<b>末段自己会变成流水账</b>,而流水账正是《寻常》
	 * 第一条明确不做的东西。加暮年后总回合数自然落 <b>45–55</b>;40–44 不可达是<b>有意放弃</b>
	 * (区间的意义是「别太短也别太长」,不是每个值都要可达)。详见 ADR-020 §2 订正块。
	 *
	 * <p><b>时钟不给引擎强制</b>(照 {@code FUSION_TURN_DIRECTIVE} 逐字写着的「不写死回合数上限,
	 * 硬上限是引擎层决策不混入」):本表只产出<b>位置感 + 收敛窗口</b>,收束由模型自己走到寿终、
	 * 或由玩家按下「就到这里」。
	 */
	private static final LifeStageTable ORDINARY_LIFE = new LifeStageTable(
			"life_sim",
			List.of(
					// T1:0-6 岁压进一个回合(ADR-020 原表第一段)。
					new LifeStage(1, 1, "幼年", "0–6 岁", "本回合一口气覆盖约 7 年", null),
					// T2-7:每回合约 2 年(原表「7-18 岁每回合 2-3 年」,12 年 / 6 回合 = 2.0)。
					new LifeStage(2, 7, "少年", "7–18 岁", "本回合比上一回合晚约 2 年", null),
					// T8-19:一年一回合(原表「19-30 岁一年一个回合」,选择最密的一段)。
					new LifeStage(8, 19, "青年", "19–30 岁", "本回合比上一回合晚约 1 年", "不再往下想了,就这样过"),
					// T20-27:每回合约 3 年(原表「31-55 岁每回合 3-5 年」,25 年 / 8 回合 = 3.1)。
					new LifeStage(20, 27, "中年", "31–55 岁", "本回合比上一回合晚约 3 年", "日子就这么过下去,不折腾了"),
					// T28-35:每回合约 2-3 年(原表「56-75 岁每回合 2-3 年」,20 年 / 8 回合 = 2.5)。
					new LifeStage(28, 35, "老年", "56–75 岁", "本回合比上一回合晚约 2 到 3 年", "该收的收一收"),
					// T36-43:刀 5 新增段,每回合约 1 年(见上「⚠️」)。
					new LifeStage(36, 43, "暮年", "76–83 岁", "本回合比上一回合晚约 1 年", "够了,可以了"),
					// T44+:一回合一天到数日(原表末段「最后几年一回合一天」)。
					new LifeStage(44, Integer.MAX_VALUE, "末段", "83 岁以后", "本回合比上一回合晚一天到数日", "不等了")),
			45,  // convergeFrom:加暮年段后前六段固定 43 回合,故预期落在这个区间
			55,  // convergeTo
			44,  // finalStageFromTurn:§8 气力下限的锚点由「最后 3-5 个回合」改为可数的回合号
			// 未按下时的 hint:刻意只有一条、不随阶段变 —— 随阶段变的是选项 text(那是「时钟是否生效」
			// 的可见证据),hint 也变则两处都在动、读者分不清哪一处才是探针。守 ADR-011 且必须是人生里的话。
			"往后的日子还在,只是不再有新的开始",
			// 已按过之后(刀 7,F-021 故障 ①):口吻的关键差别是「已经在往那边走」而不是重新决定一次;
			// 且不得是系统在播报状态(《寻常》全篇没有系统说话的位置)。
			"就这么走下去",
			"不用再说一遍了",
			"他",      // 人称词槽
			"寿终");   // 终点词槽

	private static final Map<String, LifeStageTable> TABLES = Map.of(ORDINARY_LIFE.archetype(), ORDINARY_LIFE);

	static {
		if (!TABLES.keySet().equals(LifetimeFamily.lifetimeMembers())) {
			throw new IllegalStateException(
					"一生制时钟表登记面与族成员表不一致:表=" + TABLES.keySet()
							+ ",族成员=" + LifetimeFamily.lifetimeMembers()
							+ "(少一张表 = 那个世界没有时钟,而那正是本刀在修的静默失效)");
		}
	}

	/** 取某世界的时钟表;非一生制世界返回 {@code null}(调用方据此走原路径,不报错)。 */
	static LifeStageTable of(String archetype) {
		return TABLES.get(archetype);
	}
}
