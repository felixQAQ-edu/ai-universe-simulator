package com.aiuniverse.server.eventloop;

import java.util.List;
import java.util.Map;

import com.aiuniverse.server.eventloop.BoxScene.Beat;
import com.aiuniverse.server.eventloop.BoxScene.Option;
import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxScene.PoolIntent;
import com.aiuniverse.server.eventloop.BoxScene.Pools;
import com.aiuniverse.server.eventloop.BoxScene.Situation;
import com.aiuniverse.server.eventloop.BoxScene.Stage4;
import com.aiuniverse.server.eventloop.BoxScene.Table;

/**
 * 「纸箱」局面数据表的<b>登记处</b>(ADR-028 刀 1)—— <b>唯一允许出现本局面字面量的地方</b>。
 *
 * <p>来源(单一真理源,逐字照录,一个字不改):
 * <ul>
 * <li>意图、g 变化、反馈事实、阶段 4 征兆、记忆事实、共同兜底事实:ADR-028 §已定的产品样本(Felix 2026-09-30);
 * <li>模板文字、余波征兆与选项、R1 第 17 回合补位、LEAVE_HOME 模板与离开反馈事实、「刚出门」模板:
 *     ADR-028 §附录 · 第一刀文案定稿(Felix 2026-10-01)。
 * </ul>
 *
 * <p><b>⚠️ 两处与定稿原文的对齐,照记</b>:
 * <ul>
 * <li>定稿阶段 4 的 A 槽两行都标作 {@code TO_HAND};按 ADR-028 样本,g=0 那一行是 {@code CHASE_CAR}(R3a)。
 *     文字取定稿(「追向已经开动的车」),样本表里的「追向正在离开的车」以定稿为准。
 * <li>阶段 1–3 的<b>征兆</b>:刀 1 时未定稿(为 {@code null});刀 2a 照录 ADR-028 §附录 · 第二刀文案定稿 第一节第 1 小节。
 * </ul>
 *
 * <p>余波 / 补位 / 刚出门选项的内部编号是本表给的<b>稳定 id</b>(定稿只给了文字);文字可以变,id 不变。
 *
 * <p>刀 2a 起由 {@link BoxSceneTurn} 接进回合路径;意图池与习惯短语来自
 * ADR-028 §附录 · 第二刀文案定稿 第一节(Felix 2026-10-01),只认「附录 → 表」方向,
 * 由 {@code BoxSceneTest.templatesAppearVerbatimInAdrAppendix} 守。
 */
final class BoxSceneTables {

	private BoxSceneTables() {
	}

	static final String LEAVE_HOME = "LEAVE_HOME";

	/** LEAVE_HOME 的正常文字与降级模板(第一刀附录 §6 / 第二刀附录第一节 §3 固定 C 槽,同一句)。 */
	static final String LEAVE_HOME_TEMPLATE = "从门缝钻出去";

	private static Option acc(String slot, String intent, String template, int gDelta, String feedback) {
		return new Option(slot, intent, template, gDelta, null, null, feedback);
	}

	private static Option settles(String slot, String intent, String template, Path path) {
		return new Option(slot, intent, template, 0, BoxScene.ALWAYS, path, null);
	}

	private static Option pick(String slot, String intent, String template) {
		return new Option(slot, intent, template, 0, null, null, null);
	}

	static final Table ANIMAL_LIFE_BOX = new Table(
			"animal_life",
			11,  // 纸箱 T11–14,余波 T15–17(ADR-028 §3)
			List.of(
					new Beat("S1", "屋里多了许多纸箱。纸板和胶带的味道盖住了原来的气味，孩子的玩具也被收了起来。", List.of(
							acc("A", "FOLLOW", "跟着孩子", +1, "孩子蹲下来摸了它一下"),
							acc("B", "SNIFF_BOX", "钻进纸箱闻一闻", +1, "它在纸箱里留下了自己的味道"),
							acc("C", "OLD_SPOT", "回到平时趴着的地方", 0, "没有人往它这边看"))),
					new Beat("S2", "家具一件件被搬走。它平时趴着的地方空了，屋里的脚步声比平常多。", List.of(
							acc("A", "FOLLOW", "跟紧孩子", +1, "孩子叫了它的名字"),
							acc("B", "GUARD_BOWL", "守在自己的碗旁边", +1, "有人把它的碗装进了箱子"),
							acc("C", "UNDER_BED", "躲进床底", -1, "它躲进了床底,没有人弯腰找它"))),
					new Beat("S3", "门一直开着，外面传来车声。人抱着箱子，一趟趟从它身边经过。", List.of(
							// g<2:+1 不结算;g≥2:结算 R1(反馈即 R1 记忆事实)
							new Option("A", "INTO_BOX", "跳进正在搬的箱子", +1, 2, Path.R1,
									"有人把它从箱子里抱出来,放在了门边"),
							acc("B", "TO_DOOR", "跟到门口", +1, "搬东西的人跨过它时停了一下"),
							acc("C", "UNDER_BED", "躲回床底", -1, "它躲进了床底")))),
			new Stage4(1,
					new Beat("S4_HIGH", "门口那只手伸了回来,孩子在车里叫它", List.of(
							settles("A", "TO_HAND", "跑向伸回来的那只手", Path.R2),
							settles("B", "STAY", "留在原地", Path.R3B),
							settles("C", "HIDE", "躲到看不见的地方", Path.R3C))),
					new Beat("S4_LOW", "车门已经关上,引擎声盖过了屋里的声音", List.of(
							settles("A", "CHASE_CAR", "追向已经开动的车", Path.R3A),
							settles("B", "STAY", "留在原地", Path.R3B),
							settles("C", "HIDE", "躲到看不见的地方", Path.R3C)))),
			Map.of(
					Path.R1, "门外响着车声时，它自己跳进了那个正被人抱走的箱子。", // ADR-028 §已决 J 第 1 条(Felix 2026-10-01)
					Path.R2, "车发动时,它跑向了那只手",
					Path.R3A, "它追到了门口,但没有跟上",
					Path.R3B, "车开走时,它待在原地",
					Path.R3C, "车开走时,它躲着"),
			"车开走时,它没有跟上",
			List.of(
					new Beat("A1", "新屋子第一晚，地板是滑的，门响起来是“咔哒”，不是以前的金属声。", List.of(
							pick("A", "SEEK_DRY_CORNER", "去找一个不打滑的角落"),
							pick("B", "WAIT_OLD_SOUND", "守在门边，等那个旧声音"),
							pick("C", "FOLLOW_CHILD", "跟着孩子走"))),
					new Beat("A2", "走廊里响起金属声，门没有开；那是别人家的门。", List.of(
							pick("A", "WAIT_AT_DOOR_AGAIN", "留在门边，再等一次"),
							pick("B", "SEEK_METAL_SOUND", "去找金属声从哪里传来"),
							pick("C", "BACK_TO_CORNER", "回到昨晚找到的角落"))),
					new Beat("A3", "孩子把它的旧碗放在一个陌生的角落。", List.of(
							pick("A", "TO_OLD_BOWL", "走到旧碗旁边"),
							pick("B", "DRAG_BOWL_TO_DOOR", "把碗拖回门边"),
							pick("C", "SNIFF_AND_TURN_AWAY", "闻一闻，再转身走开")))),
			List.of(
					new Beat("B1", "光落在地板上，屋里没有别的动静。", List.of(
							pick("A", "GUARD_DOOR", "守在门边"),
							pick("B", "CHECK_BOWL", "去碗旁边看看"),
							pick("C", "CALL_INTO_ROOM", "朝屋里叫一声"))),
					new Beat("B2", "光挪到了墙上，叫声没有招来任何人。", List.of(
							pick("A", "CALL_AT_DOOR", "再朝门口叫一声"),
							pick("B", "HIDE_UNDER_BED", "躲回床底"),
							pick("C", "SNIFF_WINDOW", "到窗边闻一闻"))),
					new Beat("B3", "天黑了，门缝里透进来的风带着外面的气味。", List.of(
							pick("A", "RETURN_UNDER_BED", "回到床底"),
							pick("B", "LIE_BY_EMPTY_BOWL", "趴在空碗旁边"),
							pick("C", LEAVE_HOME, LEAVE_HOME_TEMPLATE)))),
			new Beat("R1_T17", "清晨，孩子的脚步声又从同一个方向传来。它已经认得了。", List.of(
					pick("A", "MEET_FOOTSTEPS", "迎着那串脚步声过去"),
					pick("B", "STAY_NEW_CORNER", "留在找到的新角落"),
					pick("C", "WAIT_BY_OLD_BOWL", "去旧碗旁边等着"))),
			LEAVE_HOME,
			"它从门缝挤了出去。楼道的地面又冷又硬，那扇门留在了身后。",
			List.of(
					pick("A", "FOLLOW_STAIR_SCENT", "顺着楼道里的气味往前走"),
					pick("B", "WAIT_AT_OLD_DOOR", "回到那扇门前等一会儿"),
					pick("C", "HIDE_STAIR_SHADOW", "躲进楼梯拐角的阴影里")));

	private static PoolIntent pool(String intent, String meaning, String boundary, String template, String habit) {
		return new PoolIntent(intent, meaning, boundary, template, habit);
	}

	/**
	 * 处境意图池(ADR-028 §已决 D / F / G;文案逐字照录第二刀附录第一节 §2 / §3)。
	 * 池内顺序即附录「池内固定顺序」,轮换按它滑动。
	 */
	static final Pools ANIMAL_LIFE_POOLS = new Pools(
			List.of(
					pool("FOLLOW_CHILD", "跟着孩子",
							"只能跟随孩子在新屋内部移动；不能跟出家门，也不能借孩子开门制造地点转换",
							"跟着孩子走", "这些天，它常跟在孩子身后。"),
					pool("FIND_SAFE_SPOT", "寻找可以安心停留的位置",
							"只能寻找新屋内的角落、垫子、家具旁或其他休息处；描述的是寻找，不保证已经找到",
							"找一个能安心趴下的地方", "这些天，它常在屋里找能安心趴下的地方。"),
					pool("WAIT_BY_NEW_DOOR", "守在新家的门内",
							"可以听、闻或观察门外动静，但身体必须留在门内；这不是旧家的那扇门",
							"守在新的门边", "这些天，它常守在新的门边，听门外的动静。"),
					pool("INSPECT_ROOM", "查看新屋里还不熟悉的房间",
							"只能在新屋内部移动；不得进入楼道、街道、阳台外侧或其他未定义空间",
							"去看看还没闻过的房间", "这些天，它常去闻那些还不熟悉的房间。"),
					pool("RETURN_TO_BOWL", "回到从旧家带来的那只碗旁",
							"可以靠近、闻、吃喝或在旁边停留；不能把动作改写成回旧家",
							"回到旧碗旁边", "这些天，它常回到那只旧碗旁边。"),
					pool("TRACE_SOUND", "寻找新屋内部声音的来源",
							"声音来源必须位于新屋内部；如果声音来自门外，只能在门内听，不能追出去",
							"去找屋里的声音从哪里传来", "这些天，它常循着屋里的声音找过去。")),
			List.of(
					pool("WAIT_BY_OLD_DOOR", "留在旧家门内等待",
							"可以听楼道、闻门缝或守着门，但不能跨过门槛",
							"守在门边等一会儿", "这些天，它常守在门边，听楼道里的动静。"),
					pool("SEARCH_FOR_FOOD", "在旧屋里寻找还能吃的东西",
							"可以查看碗、地面、厨房或家具旁；只保证寻找，不保证找到食物，也不能为了找食物离开旧屋",
							"去找还能吃的东西", "这些天，它常在空下来的屋里找吃的。"),
					pool("CALL_IN_EMPTY_HOME", "在空屋里发出叫声",
							"可以朝门、房间或过去有人出现的方向叫；不保证有人回应，也不改变处境",
							"朝空屋里叫一声", "这些天，它常朝着没有回应的屋里叫。"),
					pool("RETURN_UNDER_BED", "回到熟悉的床底躲藏",
							"只能回到旧屋内已经存在的藏身处；不能借躲藏转移到屋外",
							"躲回床底", "这些天，它常躲回床底。"),
					pool("INSPECT_EMPTY_ROOMS", "查看已经空下来的房间",
							"可以在旧屋各房间之间移动、闻或观察留下的痕迹；不能从窗户、阳台或其他出口离开",
							"去看看已经空下来的房间", "这些天，它常一个房间一个房间地闻过去。")),
			LEAVE_HOME_TEMPLATE,
			// 轮换偏移步长(§已决 F「建议按段依次取 0 / 2 / 4 …,由数据表定」):b[段] = 步长 × 段序号。
			2);


	/**
	 * 第 (9) 条里移到 OUTSIDE 每回合共同边界的那一句(ADR-028 §已决 L 第 1 条,原句原样,附录第三节 7-D 原文)。
	 * (9) 其余「末段落在楼道口」的内容仍只在末段生效。
	 */
	static final String OUTSIDE_THRESHOLD_LINE =
			"楼道、楼梯间和门前都属于 `OUTSIDE`。它可以走到旧家的门前，但不能进入旧屋；不得写门为它打开，也不得制造 `OUTSIDE → EMPTY_HOME`。";

	/**
	 * 处境片段(ADR-028 刀 2b;§已决 · 刀 2 实现口径第 5 条):按会话里的<b>权威处境</b>注入回合 prompt,
	 * 接在《动物人生》回合指令主干之后。内容逐字取自 §附录 · 第二刀文案定稿
	 * (第一节 §2 / §3 的共同边界、第三节 7-A 四条核心对应的标题与本处境那一行、7-D 的 (7)(9) 与 OUTSIDE 专属结局;
	 * 引用块只去掉 markdown 的 {@code > } 前缀)。每段第一行的小标题与「新屋共同边界:」之类的连接语是结构措辞。
	 * <b>处境为空(局面结算之前)→ 不注入任何片段</b>,不猜默认值。
	 */
	static final Map<Situation, String> SITUATION_FRAGMENTS = Map.of(
			Situation.NEW_HOME, "\n\n" + String.join("\n", List.of(
					"【处境片段 · 当前处境:新屋(以下只在这一处境下成立)】",
					"新屋共同边界:",
					"- 六个意图的处境效果全部为 `NONE`，完成后仍是 `NEW_HOME`。",
					"- 模型不得把任何一个意图改写成离开新屋。",
					"- “孩子开门”“门外有声音”“查看房间”都不能成为未经定义的 `NEW_HOME → OUTSIDE`。",
					"- 以后若要离开新屋，必须新增确定性的转换局面，不能复用这六个意图暗中完成。",
					"- 降级模板是最终兜底文字，可以直接显示给玩家。",
					"四条核心对应在当前处境下的回应:",
					"1. **金属声 = 门要开**",
					"   - `NEW_HOME`：新家的门声已经不同；楼道里的金属声可能来自别人家的门。它仍会抬头，但门不一定为它打开。",
					"2. **靠近人 = 有吃的、不会出事**",
					"   - `NEW_HOME`：熟悉的人仍可以喂它、摸它；不得为了表现“规则失效”，让新家里的人无故踢它或拿东西砸它。",
					"3. **叫 = 有人来**",
					"   - `NEW_HOME`：叫声仍可能换来孩子、脚步或门内的回应，但不保证每一次都有人立刻出现。",
					"4. **那个位置 = 安全**",
					"   - `NEW_HOME`：旧位置已经不存在，它必须在陌生房间里重新寻找能停留的地方；这不是惩罚，也不能直接写成受伤。")),
			Situation.EMPTY_HOME, "\n\n" + String.join("\n", List.of(
					"【处境片段 · 当前处境:空下来的旧屋(以下只在这一处境下成立)】",
					"旧屋共同边界:",
					"- A、B 两个意图的处境效果全部为 `NONE`。",
					"- 只有 C 槽的 `LEAVE_HOME` 可以越过旧家门槛。",
					"- 模型不得把寻找食物、查看房间或听门外动静写成已经离开旧屋。",
					"- 玩家可以长期不选择 C；不得按停留回合数自动把它搬到外面。",
					"- 如果长期停留产生重复感，应另开局面解决，不能用自由叙事偷偷改变地点。",
					"四条核心对应在当前处境下的回应:",
					"1. **金属声 = 门要开**",
					"   - `EMPTY_HOME`：楼道里仍会有金属声，但旧家的门不会因此打开。",
					"2. **靠近人 = 有吃的、不会出事**",
					"   - `EMPTY_HOME`：屋里没有可以靠近的人。不得凭空生成人，只为了让这条规律失败。",
					"3. **叫 = 有人来**",
					"   - `EMPTY_HOME`：叫声没有招来任何人。",
					"4. **那个位置 = 安全**",
					"   - `EMPTY_HOME`：有些旧位置还在，但家具、人和日常声音已经改变，那里不再自动兑现过去的安全。")),
			Situation.OUTSIDE, "\n\n" + String.join("\n", List.of(
					"【处境片段 · 当前处境:屋外(以下只在这一处境下成立)】",
					// ADR-028 §已决 L 第 1 条:第 (9) 条里这一句原样移出,作每回合共同边界(离开回合起每个 OUTSIDE 回合都在)。
					"屋外共同边界:",
					"- " + OUTSIDE_THRESHOLD_LINE,
					"四条核心对应在当前处境下的回应:",
					"1. **金属声 = 门要开**",
					"   - `OUTSIDE`：那声音来自别的门，不是它等的那一扇。",
					"2. **靠近人 = 有吃的、不会出事**",
					"   - `OUTSIDE`：靠近陌生人可能换来踢、砸、驱赶或追赶；代价落在【身子】与【近人】。",
					"3. **叫 = 有人来**",
					"   - `OUTSIDE`：叫声可能引来不友善的人、别的动物或其他让处境变糟的东西。",
					"4. **那个位置 = 安全**",
					"   - `OUTSIDE`：位置可能已经被别的东西占着；它去了，会被赶开。",
					"**（7）【`OUTSIDE` · 接触种类与动词分区】**",
					"这一条只在权威处境为 `OUTSIDE` 时注入。",
					"正文与选项中不得出现“推”字。外面是踢、是砸、是驱赶，是手落下来的地方不对；这是另一种接触，不是屋内接触的加强版。",
					"“靠近人”在这里可能换来踢、砸、驱赶或追赶，但不要求每回合都发生伤害，也不得为了证明规则失效连续重复同一种伤害。",
					"检查条件从“处于外面三段之一”改为“权威处境等于 `OUTSIDE`”。四段生命周期本身不决定这条规则是否生效。",
					"**（9）【`OUTSIDE` 末段 · 楼道口边界】**",
					"这一条只在权威处境为 `OUTSIDE` 且已经进入末段时生效。",
					"最后一个可玩回合的场景必须落在某个楼道口。冷天往背风处钻，因此返回旧地、寻找新的地方或留在附近都可以走到楼道口。",
					"只有玩家明确选择返回旧地时，这个楼道口才是旧家的那栋楼，并允许提出【走回门前】；其他路径可以落在别的楼道口，不得偷写成已经回到旧家。",
					"**【`OUTSIDE` 专属结局】**",
					"- 【太近了】：仅当【近人】高位且【身子】归零时允许提出；",
					"- 【走回门前】：仅在末段、玩家明确选择返回旧地并抵达旧家门前后允许提出；",
					"- 【没有名字的】：仅当【身子】耗尽且【地面】低位时允许提出。",
					"`NEW_HOME` 与 `EMPTY_HOME` 不得提出这三个结局。结局存在于本局的 `endings[]` 中，不代表当前处境已经允许使用它。",
					"【没有名字的】不得写成它从来没有名字；只能写经过的人不知道孩子以前怎样叫它。")));

	/** 某处境的片段;{@code null}(处境为空)→ 空串。 */
	static String situationFragment(Situation s) {
		return s == null ? "" : SITUATION_FRAGMENTS.get(s);
	}

	/**
	 * 时钟契约三项的覆盖值(段名 = {@code LifeStage.label} / 设计标注 = {@code spanNote} / 推进语 = {@code advanceClause})。
	 * 只替换注入进时钟契约的这三项,族层模板(LifetimeFamily)不动。
	 */
	record StageText(String label, String spanNote, String advanceClause, String clockException) {

		/** 附录原文的三项(不带时钟例外)。 */
		StageText(String label, String spanNote, String advanceClause) {
			this(label, spanNote, advanceClause, null);
		}
	}

	/**
	 * 结算后、窗口末回合以内的段信息(ADR-028 §附录 · 第二刀补充文案 · 二 / 三,Felix 2026-10-01 定稿,照录)。
	 * 按<b>结算类别</b>选组(被带走 R1 / R2 → 第二组;被留下 R3a / b / c → 第三组),不按回合号。
	 * 第一组(纸箱段)与结果无关,住在时钟表里({@code LifeStageTables.BOX_*})。
	 */
	private static final Map<BoxScene.Category, StageText> ANIMAL_LIFE_AFTERMATH_STAGES = Map.of(
			BoxScene.Category.TAKEN, new StageText(
					"新屋子·最初的几天",
					"【设计标注，绝不写进正文或选项】结算结果已经确定，权威处境是 `NEW_HOME`。这几回合只写它怎样感知新屋子的地面、门声、房间、孩子和从旧家带来的碗；必须承认已经落地的结算记忆，但不得使用“搬家”“收养”或“被带走”解释发生了什么。不得重演纸箱局面，不得让它回到旧屋，也不得生成离开新屋的地点转换。R1 在三回合余波结束后，使用已定稿的第 17 回合补位；R2 不使用补位。",
					"一回合约一天。每回合推进新屋子里相邻的一天，不得在这几回合内跳到数周、数月或生命末段。"),
			BoxScene.Category.LEFT, new StageText(
					"旧屋·空下来的第一天",
					"【设计标注，绝不写进正文或选项】结算结果已经确定，权威处境是 `EMPTY_HOME`。三个回合发生在同一天：光先落在地板上，随后移到墙上，最后天黑。屋里没有人回来，叫声没有得到回应，但旁白不得使用“遗弃”“不要它了”或“被留下”解释原因。玩家没有选择 `LEAVE_HOME` 以前，它始终在旧家门内；不得因为门缝有风、闻到外面的气味或进入第 18 回合，就提前写成已经到了外面。",
					"一回合约数小时。三个回合从白天推进到天黑，不得写成已经过去了几天。"));

	private static final Map<String, Map<BoxScene.Category, StageText>> AFTERMATH_STAGES =
			Map.of(ANIMAL_LIFE_BOX.archetype(), ANIMAL_LIFE_AFTERMATH_STAGES);

	/** 某世界某结算路径的余波段信息;未登记 → {@code null}。 */
	static StageText aftermathStage(String archetype, Path result) {
		Map<BoxScene.Category, StageText> m = AFTERMATH_STAGES.get(archetype);
		return m == null || result == null ? null : m.get(result.category);
	}

	// ── 被留下余波 B1–B3 的逐拍时钟(ADR-028 §已决 L 第 2 条,Felix 2026-10-03 原文)────────────────

	/** 时钟例外:B1–B3 回合整句替换时钟契约里与之冲突的那一句(只出现在这一处;§已决 L 修正)。 */
	static final String LEFT_CLOCK_EXCEPTION =
			"【仅被留下余波适用的时钟例外】这三个回合发生在同一天，相邻两拍相隔数小时。本回合只推进到当前这一拍，不跨到第二天。";

	/** B1–B3 渲染时从第三组设计标注里删去的一句(校勘处置;附录原文保留)。 */
	static final String LEFT_SPAN_NOTE_REMOVED = "三个回合发生在同一天：光先落在地板上，随后移到墙上，最后天黑。";

	/** B1–B3 回合的推进语:第三组推进语定稿原文的前半句(§已决 L 修正,Felix 2026-10-04)。 */
	static final String LEFT_BEAT_ADVANCE = "一回合约数小时。";

	/**
	 * B1–B3 渲染时时钟契约里被改写的那一句(在渲染处替换,族层源码不改;§已决 L 修正)。
	 * 「写的是它之后的日子」与「同一天、相隔数小时」冲突,删去后半句。
	 */
	static final String FAMILY_AFTER_DAYS = "上一回合正在发生的事,本回合应当【已经过去了】,写的是它之后的日子。";

	/** {@link #FAMILY_AFTER_DAYS} 在 B1–B3 回合的改写结果。 */
	static final String FAMILY_AFTER_DAYS_LEFT = "上一回合正在发生的事,本回合应当【已经过去了】。";

	/**
	 * 族层时钟契约里被时钟例外整句替换的那一句(在渲染处替换,族层源码不改)。
	 * 族层将来改了这句 → {@link #withClockException} 找不到原文即抛,本类加载时先验一次。
	 */
	static final String FAMILY_SAME_DAY_BAN = "【绝不允许】两个回合停在同一天、同一顿饭、同一次谈话里把一件事说完;";

	/** 当前一拍的时钟与征兆指令(只注入本拍那一行;不要求逐字搬进正文)。按拍号,不按回合号。 */
	static final Map<String, String> LEFT_BEAT_CLOCK_LINES = Map.of(
			"B1", "【本回合第 1/3 拍】光落在地板上。不得提前写光移到墙上或天黑。",
			"B2", "【本回合第 2/3 拍】光挪到了墙上，叫声没有招来任何人。不得提前写天黑。",
			"B3", "【本回合第 3/3 拍】天黑了，门缝里透进来的风有外面的味道。");

	/** 本拍的时钟与征兆行;不是被留下余波的拍 → {@code null}。 */
	static String leftBeatClockLine(String beatId) {
		return beatId == null ? null : LEFT_BEAT_CLOCK_LINES.get(beatId);
	}

	/**
	 * 某结算路径、某一拍的余波段信息(渲染用)。被留下余波 B1–B3:设计标注删去 {@link #LEFT_SPAN_NOTE_REMOVED},
	 * 推进语改为 {@link #LEFT_BEAT_ADVANCE},并带上时钟例外;其余(被带走余波、补位)= {@link #aftermathStage} 原样。
	 */
	static StageText aftermathStageForBeat(String archetype, Path result, String beatId) {
		StageText base = aftermathStage(archetype, result);
		if (base == null || result == null || result.category != BoxScene.Category.LEFT
				|| leftBeatClockLine(beatId) == null) {
			return base;
		}
		if (!base.spanNote().contains(LEFT_SPAN_NOTE_REMOVED)) {
			throw new IllegalStateException("第三组设计标注里找不到要删去的那一句:" + LEFT_SPAN_NOTE_REMOVED);
		}
		if (!base.advanceClause().startsWith(LEFT_BEAT_ADVANCE)) {
			throw new IllegalStateException("第三组推进语不以这一句开头:" + LEFT_BEAT_ADVANCE);
		}
		return new StageText(base.label(), base.spanNote().replace(LEFT_SPAN_NOTE_REMOVED, ""),
				LEFT_BEAT_ADVANCE, LEFT_CLOCK_EXCEPTION);
	}

	/**
	 * 把已渲染的时钟契约里 {@link #FAMILY_SAME_DAY_BAN} 整句替换为例外,并把 {@link #FAMILY_AFTER_DAYS} 改写为
	 * {@link #FAMILY_AFTER_DAYS_LEFT};{@code exception == null} → 原样(其余回合、《寻常》不受影响)。
	 * 任一句找不到(族层改了)→ 抛,不静默不替换(§已决 L:不能把「例外」和「绝不允许」并排交给模型)。
	 */
	static String withClockException(String renderedContract, String exception) {
		if (exception == null) {
			return renderedContract;
		}
		if (!renderedContract.contains(FAMILY_SAME_DAY_BAN)) {
			throw new IllegalStateException("时钟契约里找不到要被例外替换的那一句:" + FAMILY_SAME_DAY_BAN);
		}
		if (!renderedContract.contains(FAMILY_AFTER_DAYS)) {
			throw new IllegalStateException("时钟契约里找不到要改写的那一句:" + FAMILY_AFTER_DAYS);
		}
		return renderedContract.replace(FAMILY_SAME_DAY_BAN, exception)
				.replace(FAMILY_AFTER_DAYS, FAMILY_AFTER_DAYS_LEFT);
	}

	/** 登记处:世界 → 局面表(只有登记在这里的世界会接局面层;其余世界一行不受影响)。 */
	private static final Map<String, Table> BOXES = Map.of(ANIMAL_LIFE_BOX.archetype(), ANIMAL_LIFE_BOX);
	private static final Map<String, Pools> POOLS = Map.of(ANIMAL_LIFE_BOX.archetype(), ANIMAL_LIFE_POOLS);

	static {
		// 登记面对拍(ADR-028 决策 6):局面挂在一生制世界上,且窗口落在该世界时钟表之内 —— 挂错 = 加载即抛。
		BOXES.forEach((key, table) -> {
			if (!key.equals(table.archetype()) || !POOLS.containsKey(key) || !AFTERMATH_STAGES.containsKey(key)) {
				throw new IllegalStateException("局面表登记面不一致:" + key);
			}
			if (LifeStageTables.of(key) == null) {
				throw new IllegalStateException("局面表挂在非一生制世界上:" + key);
			}
			if (!table.leaveIntent().equals(LEAVE_HOME) || !POOLS.get(key).leaveTemplate().equals(LEAVE_HOME_TEMPLATE)) {
				throw new IllegalStateException("离开意图登记不一致:" + key);
			}
			// §已决 L:B1–B3 的渲染要能找到被删的那一句与被替换的那一句 —— 任一处原文变了,加载即抛。
			for (Beat b : table.leftAftermath()) {
				for (Path p : Path.values()) {
					if (p.category == BoxScene.Category.LEFT) {
						StageText r = aftermathStageForBeat(key, p, b.id());
						withClockException(com.aiuniverse.server.archetype.LifetimeFamily.clockContract(1, "", "", "",
								"", 0, 0, ""), r.clockException());
					}
				}
			}
			// §已决 K 安全阀:全部叙事素材预跑一遍人称转换 —— 含「它们」即在类加载时拒绝,不拖到某个回合。
			BoxSceneTurn.narrativeMaterials(table, POOLS.get(key)).forEach(BoxSceneTurn::narrated);
		});
	}

	/** 某世界的局面表;未登记 → {@code null}(调用方据此走原路径)。 */
	static Table box(String archetype) {
		return BOXES.get(archetype);
	}

	/** 某世界的处境意图池;未登记 → {@code null}。 */
	static Pools pools(String archetype) {
		return POOLS.get(archetype);
	}
}
