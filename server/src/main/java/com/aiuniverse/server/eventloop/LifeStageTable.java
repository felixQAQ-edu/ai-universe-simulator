package com.aiuniverse.server.eventloop;

import java.util.List;

/**
 * 一个一生制世界的<b>时钟表</b>(ADR-021 刀 2 · 立字三的落地形态)。
 *
 * <p>取代原 {@code enum LifeStage} 的类级单例:阶段列表、收敛窗口、末段起点、出口措辞、
 * 以及时钟契约要用的两个<b>词槽</b>(人称 / 终点词)全部随表走 —— <b>一个一生制世界一张表</b>。
 * 《寻常》那张见 {@link LifeStageTables};动物人生的表归刀 3,本刀不写。
 *
 * <p><b>⚠️ 切分表 per-world 不得跨世界复用</b>(ADR-020 §1):动物幼年极短、成年占绝大部分、
 * 多数动物没有老年 —— <b>硬套人的段式会得到「毛茸茸的人」</b>。
 *
 * <p><b>本文件是纯机制,不含任何世界的字面量</b>(同 {@link LifeStage}),由源码级断言守护。
 *
 * @param archetype         这张表属于哪个世界
 * @param stages            阶段列表,按回合升序、区间连续不重叠(构造期校验)
 * @param convergeFrom      全局收敛窗口下界(注入用:「全局预期在第 N-M 回合走到<终点词>」)
 * @param convergeTo        全局收敛窗口上界
 * @param finalStageFromTurn 末段起始回合(§8 收束下限的可数锚点)
 * @param exitHint          「就到这里」<b>尚未按下</b>时的 hint(守 ADR-011:定性代价、无 UI 措辞)
 * @param exitTextAfter     已按过之后的选项 text(F-021 故障 ①:变形而非消失)
 * @param exitHintAfter     已按过之后的 hint
 * @param pronoun           时钟契约里指代主角的人称(《寻常》=「他」;动物应是「它」)——
 *                          族层持有句式,per-world 差异靠<b>词槽</b>注入(ADR-021 立字二补记)
 * @param terminalWord      时钟契约里「走到 X」的终点词(《寻常》=「寿终」;动物用「老死」更自然)
 */
record LifeStageTable(
		String archetype,
		List<LifeStage> stages,
		int convergeFrom,
		int convergeTo,
		int finalStageFromTurn,
		String exitHint,
		String exitTextAfter,
		String exitHintAfter,
		String pronoun,
		String terminalWord) {

	/**
	 * 出口选项 id。<b>刻意留全局、不随表走</b>(ADR-021 裁定一):
	 * <b>它是 id 不是措辞</b> —— 玩家看不见它,前端与守卫按它认人,换世界没有任何理由换 id;
	 * 而 {@link #exitHint} / {@link #exitTextAfter} 那些是<b>玩家读到的字</b>,必须 per-world。
	 * 这条区分是本刀「哪些该参数化、哪些不该」的判据本身。
	 */
	static final String EXIT_ACTION_ID = "X";

	LifeStageTable {
		if (stages == null || stages.isEmpty()) {
			throw new IllegalStateException("一生制时钟表不得为空:" + archetype);
		}
		stages = List.copyOf(stages);
		int expected = 1;
		for (LifeStage s : stages) {
			if (s.fromTurn() != expected || s.toTurn() < s.fromTurn()) {
				throw new IllegalStateException(
						"一生制时钟表区间必须从 1 起、连续且不重叠:" + archetype + " 在 " + s.label() + " 处断了");
			}
			expected = s.toTurn() == Integer.MAX_VALUE ? Integer.MAX_VALUE : s.toTurn() + 1;
		}
		if (stages.get(stages.size() - 1).toTurn() != Integer.MAX_VALUE) {
			throw new IllegalStateException("一生制时钟表最后一个阶段必须开放到 MAX_VALUE(否则长局落不到任何阶段):" + archetype);
		}
	}

	/**
	 * 回合号 → 阶段。{@code turn} 是<b>正在生成的那一回合</b>(= {@code engine.turn() + 1},
	 * 与骨架末行「请推进第 N 回合」同一个 N)。非正数一律回落首段(防御:回合号只会从 1 起)。
	 */
	LifeStage stageAt(int turn) {
		for (LifeStage s : stages) {
			if (turn >= s.fromTurn() && turn <= s.toTurn()) {
				return s;
			}
		}
		return stages.get(0);
	}
}
