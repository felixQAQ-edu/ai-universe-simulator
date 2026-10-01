package com.aiuniverse.server.eventloop;

import java.util.List;
import java.util.Map;

import com.aiuniverse.server.eventloop.BoxScene.Beat;
import com.aiuniverse.server.eventloop.BoxScene.Option;
import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxScene.PoolIntent;
import com.aiuniverse.server.eventloop.BoxScene.Pools;
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
					Path.R1, "搬家那天,它自己跳进了正在搬的箱子",
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

	/** 登记处:世界 → 局面表(只有登记在这里的世界会接局面层;其余世界一行不受影响)。 */
	private static final Map<String, Table> BOXES = Map.of(ANIMAL_LIFE_BOX.archetype(), ANIMAL_LIFE_BOX);
	private static final Map<String, Pools> POOLS = Map.of(ANIMAL_LIFE_BOX.archetype(), ANIMAL_LIFE_POOLS);

	static {
		// 登记面对拍(ADR-028 决策 6):局面挂在一生制世界上,且窗口落在该世界时钟表之内 —— 挂错 = 加载即抛。
		BOXES.forEach((key, table) -> {
			if (!key.equals(table.archetype()) || !POOLS.containsKey(key)) {
				throw new IllegalStateException("局面表登记面不一致:" + key);
			}
			if (LifeStageTables.of(key) == null) {
				throw new IllegalStateException("局面表挂在非一生制世界上:" + key);
			}
			if (!table.leaveIntent().equals(LEAVE_HOME) || !POOLS.get(key).leaveTemplate().equals(LEAVE_HOME_TEMPLATE)) {
				throw new IllegalStateException("离开意图登记不一致:" + key);
			}
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
