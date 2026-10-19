// 融合方向提示的**文案**(纯函数;Felix 定稿,一字不改 —— 改文案回 Project 窗口,不在实现批动)。
//
// 填名规则(ADR-019 实施记录):来源 = 合法方向里**被拖的那张**(foreign),承接 = 宿主(host)。
// 拖反时填的是**合法方向**的名字,即把这一次拖动的两张对调。世界名一律是卡片上显示的标题,不是 id。

import type { PairClass } from './drag';

/** 磁吸方向提示(classify = ok,吸附到合法目标时显示)。 */
export function magnetHintText(sourceName: string, hostName: string): string {
  return `将『${sourceName}』揉入『${hostName}』。`;
}

/** 组合不存在(classify = none,松手时显示)。 */
export const NO_FUSION_TEXT = '两个世界尚无法彼此容纳。';

/**
 * 松手在非法目标上时的那一句。参数是**这一次拖动**的两张(host = 被拖到的那张,foreign = 手上那张)。
 * - `reversed` → 对调后合法:来源 = 这次的 host、承接 = 这次的 foreign;
 * - `none`     → 固定一句;
 * - `ok`       → 不该走到这里(合法目标不拒绝),返回 null。
 */
export function rejectHintText(
  cls: PairClass,
  hostName: string,
  foreignName: string,
): string | null {
  if (cls === 'reversed') return `反过来试试:把『${hostName}』拖到『${foreignName}』上。`;
  if (cls === 'none') return NO_FUSION_TEXT;
  return null;
}
