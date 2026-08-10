package com.aiuniverse.server.eventloop;

/**
 * 一生制时钟(ADR-020 刀 5,根治 FINDINGS <b>F-020</b>):<b>回合号 → 人生阶段</b>的纯映射。
 *
 * <p><b>为什么是这个方向</b>:ADR-020 §2 原表写的是「年龄段 → 占几回合」——那是一张
 * <b>描述</b>,读的时候完备,运行时无人执行:没有任何一句要求「本回合必须推进 N 年」,
 * 也没有任何状态记录「现在是哪一年」。刀 4 真机冒烟的结果是**第 200 回合仍未收束**、
 * 热望单调爬到 99、`T55 端菜进客厅 → T56 坐下吃饭` 只隔了两分钟。
 * F-020 的立字是:<b>缺的不是三条指令,是一个时钟</b>。本类就是那个时钟 ——
 * 把表<b>倒过来</b>,每回合据回合号算出「你在人生的哪一段、这一回合该推进多少」,
 * 以<b>兑现语义</b>注入(而非「密度随生命呼吸」这类被证伪的描述)。
 *
 * <p><b>纯计算、无新状态</b>:输入只有 {@code engine.turn()},它本来就在 view 2 里
 * (`state.turn`)、也本来就落盘,故<b>续局后时钟自动接上</b>,不需要新字段、不动
 * {@code compressLog}/{@code LOG_KEEP}(那是 logSummary 那一刀,仍挂账)。
 *
 * <p><b>⚠️ 暮年段(T36–43)是刀 5 新增的,ADR-020 原密度表里没有</b>。原表末段是
 * 「最后几年一回合一天」直接接在老年之后,反解后会让「一回合一天」覆盖 20 个回合 ——
 * <b>末段自己会变成流水账</b>,而流水账正是《寻常》第一条明确不做的东西。
 * 加暮年后总回合数自然落 <b>45–55</b>(原设计区间 40–55 的上半段);
 * 40–44 不可达是<b>有意放弃</b>:区间的意义是「别太短也别太长」,不是每个值都要可达。
 * 详见 ADR-020 §2 订正块。
 *
 * <p><b>时钟不给引擎强制</b>(照融合先例 {@code FUSION_TURN_DIRECTIVE} 逐字写着的
 * 「不写死回合数上限,硬上限是引擎层决策不混入」):本类只产出<b>位置感 + 收敛窗口</b>,
 * 收束由模型自己走到寿终、或由玩家按下「就到这里」;<b>不加硬收束</b>
 * (ADR-020 §2 补记第 5 条:N 定多少都是拍脑袋,而玩家自己按下这道门有叙事意义)。
 */
enum LifeStage {

	/** T1:0–6 岁压进一个回合(ADR-020 原表第一段)。 */
	CHILDHOOD(1, 1, "幼年", "0–6 岁", "本回合一口气覆盖约 7 年", null),
	/** T2–7:每回合约 2 年(原表「7-18 岁每回合 2-3 年」,12 年 / 6 回合 = 2.0)。 */
	ADOLESCENCE(2, 7, "少年", "7–18 岁", "本回合比上一回合晚约 2 年", null),
	/** T8–19:一年一回合(原表「19-30 岁一年一个回合」,选择最密的一段)。 */
	YOUTH(8, 19, "青年", "19–30 岁", "本回合比上一回合晚约 1 年", "不再往下想了,就这样过"),
	/** T20–27:每回合约 3 年(原表「31-55 岁每回合 3-5 年」,25 年 / 8 回合 = 3.1)。 */
	MIDLIFE(20, 27, "中年", "31–55 岁", "本回合比上一回合晚约 3 年", "日子就这么过下去,不折腾了"),
	/** T28–35:每回合约 2–3 年(原表「56-75 岁每回合 2-3 年」,20 年 / 8 回合 = 2.5)。 */
	OLD_AGE(28, 35, "老年", "56–75 岁", "本回合比上一回合晚约 2 到 3 年", "该收的收一收"),
	/** T36–43:<b>刀 5 新增段</b>,每回合约 1 年(见类注释「⚠️」)。 */
	TWILIGHT(36, 43, "暮年", "76–83 岁", "本回合比上一回合晚约 1 年", "够了,可以了"),
	/** T44+:一回合一天到数日(原表末段「最后几年一回合一天」)。 */
	FINAL(44, Integer.MAX_VALUE, "末段", "83 岁以后", "本回合比上一回合晚一天到数日", "不等了");

	/** 全局收束窗口(注入用):加暮年段后前六段固定 43 回合,故预期落在这个区间。 */
	static final int CONVERGE_FROM = 45;
	static final int CONVERGE_TO = 55;

	/** 末段起始回合(注入用:§8 气力下限的锚点由「最后 3-5 个回合」改为可数的回合号)。 */
	static final int FINAL_STAGE_FROM_TURN = 44;

	/**
	 * 「就到这里」出口的固定 hint。<b>刻意只有一条、不随阶段变</b>:随阶段变的是选项 text
	 * (那是 ADR-020 §2 补记第 2 条要的「时钟是否生效」的可见证据),hint 变则两处都在动、
	 * 读者分不清哪一处才是探针。措辞守 ADR-011(定性代价、无精确数字)且<b>必须是人生里的话</b>
	 * ——不得出现「结束游戏 / 退出」一类 UI 措辞(§2 补记第 4 条)。
	 */
	static final String EXIT_HINT = "往后的日子还在,只是不再有新的开始";

	/** 出口选项 id:刻意避开骨架规定的 A/B/C/D,故与 AI 产出的任何一项都不会撞。 */
	static final String EXIT_ACTION_ID = "X";

	private final int fromTurn;
	private final int toTurn;
	private final String label;
	private final String ageRange;
	private final String advanceClause;
	private final String exitText;

	LifeStage(int fromTurn, int toTurn, String label, String ageRange, String advanceClause, String exitText) {
		this.fromTurn = fromTurn;
		this.toTurn = toTurn;
		this.label = label;
		this.ageRange = ageRange;
		this.advanceClause = advanceClause;
		this.exitText = exitText;
	}

	/**
	 * 回合号 → 阶段。{@code turn} 是<b>正在生成的那一回合</b>(= {@code engine.turn() + 1},
	 * 与骨架末行「请推进第 N 回合」同一个 N)。
	 * 非正数一律回落 {@link #CHILDHOOD}(防御:回合号只会从 1 起)。
	 */
	static LifeStage of(int turn) {
		for (LifeStage s : values()) {
			if (turn >= s.fromTurn && turn <= s.toTurn) {
				return s;
			}
		}
		return CHILDHOOD;
	}

	/** 玩家可见阶段名(幼年 / 少年 / 青年 / 中年 / 老年 / 暮年 / 末段)。 */
	String label() {
		return label;
	}

	/** 该阶段覆盖的年龄区间。⚠️ 只作注入用的设计标注,<b>绝不显示给玩家</b>(槽内硬禁令)。 */
	String ageRange() {
		return ageRange;
	}

	/** 兑现语义的推进要求(「本回合比上一回合晚约 N 年」)——不是「密度不匀速」这类描述。 */
	String advanceClause() {
		return advanceClause;
	}

	/**
	 * 该阶段「就到这里」出口的措辞;{@code null} = <b>本阶段不出现该出口</b>。
	 *
	 * <p><b>幼年 / 少年返回 null,是对 ADR-020 §2 补记第 1 条「始终在场」的修订</b>
	 * (Felix 2026-08-10 裁定):<b>出口的前提是你有一个可以放弃的人生,少年阶段还没有</b>。
	 * 这不违背「没有主线、你随时可以不选」的立场——那个立场针对的是<b>有选择能力之后</b>。
	 */
	String exitText() {
		return exitText;
	}

	/** 本阶段是否提供「就到这里」出口。 */
	boolean hasExit() {
		return exitText != null;
	}
}
