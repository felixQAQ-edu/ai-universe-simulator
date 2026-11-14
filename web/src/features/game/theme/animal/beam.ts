// 《动物人生》数值形态的两个纯量(ADR-021 局内皮肤一节 决策 2)。放在组件文件之外,组件与测试共用。

/** 每行地板线下那个小接缝的位置,按行号取;行数更多时循环。 */
export const JOINTS = ['22%', '61%', '38%', '74%'] as const;

/** 光带露出的百分比:取整后夹到 0–100;非数当 0。只吃 `AxisView.value`。 */
export function beamPercent(value: number): number {
  const v = Math.round(Number(value));
  if (!Number.isFinite(v)) return 0;
  return Math.max(0, Math.min(100, v));
}
