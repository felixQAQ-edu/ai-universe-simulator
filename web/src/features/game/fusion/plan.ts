import { CROSSFADE, totalMs } from './crossfade';
import type { FusionEmblem } from './emblem';

// 揉合走哪条路径、各拍时长(ADR-019 订正 §2 / §3 / §7;刀 2c-2)。
//
// **时间线只有一套**:组合登记了标志 → 标志路径(交接 → 停顿 → 标志 → 揭晓 → 落位);
// 没登记 → 刀 2c-1 的三段交叉淡化。reduced-motion 下有登记的组合也走交叉淡化,不出标志(订正 §7)。
// 这里只看「有没有登记」,**不认识任何组合 key**。

/**
 * 标志路径的节拍(原速,毫秒;取自 dev 稿 `fc1c4f8` 的 BEATS,以 ADR-019 订正 §3 的表为准)。
 * 子拍(烧边段里各笔画、焦褐团的时间窗)由登记数据按比例给出。
 */
export const EMBLEM_BEATS = {
  settle: [0, 420], // 交接:被拖卡从松手姿态摆正、靠拢(power2.out)
  dim: [0, 360], // 交接:其余卡淡到 DIM
  hold: [420, 760], // 停顿:极轻的下压,停住但不冻住
  // 停顿:被拖卡露在宿主卡位外的那一截连同它的字退净(刀 2c-2 返工)。止点提前到 700 而不是贴着 760:
  // 真 Chrome 实测按 760 收尾时,收进卡位段的头一两帧(约 770–790ms)仍呈现出 ≤ 10/255 的尾巴(帧呈现晚一到两帧),
  // 留约 60ms(三到四帧)余量,收进卡位段里卡位外一帧都不剩。
  trim: [420, 700],
  cardText: [760, 900], // 标志:两张原卡的文字先退(不从半透明的融合面底下透出来)
  merge: [760, 1060], // 标志:融合面盖在两张卡上面淡入
  tuck: [760, 1180], // 标志:被拖卡收进宿主卡位(后 45% 淡出),止点宿主原卡隐去
  corner: [880, 1160], // 标志:重角冒出,两臂伸出 lead
  edges: [1080, 2100], // 标志:细边往外烧(各笔画的窗见登记数据)
  accent: [1980, 2380], // 标志:冷色点缀最后透出
  reveal: [2560, 3600], // 揭晓:边界推进;点缀在头 15% 退去,标志在 15%–80% 退去;止点整片补满、融合面隐去
  text: [3150, 3510], // 揭晓:边界推过约八成时文字浮现
  streak: [3700, 4220], // 揭晓:成品卡自带的斜接缝光带淡入
  restore: [4100, 4500], // 其余卡恢复
  land: [4220, 4500], // 落位:成品卡移到宿主下方(与 restore 一同在 4500 收住;见下)
} as const;

/**
 * 落位接在揭晓之后:光带淡入完(4220)起步,与其余卡恢复一同在 4500 收住 —— 总时长仍是约 4.5 秒,
 * 4500 之后卡面不再有入口动作(订正 §1「特效收尾静止」)。时长与交叉淡化的落位相同(280ms,power2.out)。
 */
export const EMBLEM_TOTAL_MS = EMBLEM_BEATS.land[1];

/** 交接段:被拖卡靠拢到宿主卡位之下半张卡(两个标题都露着),摆正。 */
export const HANDOFF_DY = 0.5;
/** 停顿段的下压。 */
export const HOLD_SCALE = 0.994;
/** 标志路径上其余卡的透明度(订正 §1:22%)。 */
export const DIM_OPACITY = 0.22;

export interface MergePlan {
  kind: 'emblem' | 'crossfade';
  /** 成品卡文字开始浮现的时刻(ms,自揉合开始):「刚揉出」的标题停驻从这一刻起算。 */
  textAt: number;
  /** 整段时长(ms,含落位)。 */
  totalMs: number;
}

export function mergePlan(emblem: FusionEmblem | null, reduced: boolean): MergePlan {
  if (emblem && !reduced) return { kind: 'emblem', textAt: EMBLEM_BEATS.text[0], totalMs: EMBLEM_TOTAL_MS };
  return { kind: 'crossfade', textAt: CROSSFADE.text[0], totalMs: totalMs(reduced) };
}

/** 不演动画(量不到卡片,卡直接出现)时文字从挂载那一刻就在:停驻从 0 起算。 */
export const TEXT_AT_IMMEDIATE = 0;
