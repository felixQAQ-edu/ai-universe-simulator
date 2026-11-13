// 九档算盘的珠子映射(ADR-038 Slice G-2 决策 2)。纯函数,只吃一个整数 —— 通用层给的 `AxisView.value`
// (滚动中的显示值)。不认轴、不读 severity:颜色由形态组件按 severity 上,位置只由数值定。
//
// 读法:靠梁的算数;上珠当五,下珠当一。

/** 一档(一根杆):上珠算没算上、下珠算上几颗(从靠梁那颗起)。 */
export interface RodState {
  upper: boolean;
  lower: number;
}

/** 0–9 的一位数 → 一档的珠子。 */
export function rodFor(digit: number): RodState {
  return { upper: digit >= 5, lower: digit % 5 };
}

/** 数值 → 三档(百 / 十 / 个)。先取整再夹到 0–100,与面板上显示的数同源。 */
export function rodsFor(value: number): [RodState, RodState, RodState] {
  const v = Math.max(0, Math.min(100, Math.round(Number.isFinite(value) ? value : 0)));
  return [rodFor(Math.floor(v / 100)), rodFor(Math.floor(v / 10) % 10), rodFor(v % 10)];
}

/** 珠子原位的 top(px,样张原值):上珠 4;下珠第 j 颗 48 + 11j。算盘高 96,梁在 27–29。 */
export const UPPER_TOP = 4;
export const lowerTop = (j: number) => 48 + j * 11;
/** 「算上」时相对原位的位移(px,样张原值):上珠往下靠梁,下珠往上靠梁。 */
export const UPPER_ON_DY = 12;
export const LOWER_ON_DY = -18;
