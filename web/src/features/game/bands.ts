import type { AxisBand } from '../../api';

// #3 数值行为化 · 行为档解析(纯函数,展示层辅助)。后端 init 把行为档投影成显式 inclusive 区间
// [{min,max,label}](连续覆盖 [0,100]、axisRole 无关),前端只需命中 min≤value≤max 即得当前档。
// 守 ADR-003:纯函数、无平台 IO、不接触 isTrue/hiddenLogic。

const clamp = (n: number) => Math.max(0, Math.min(100, n));

/** 据当前值就近解析行为档 label。无档表 / 未命中(理论不会,区间覆盖全域)→ null(只显数字)。 */
export function resolveBandLabel(value: number, bands?: AxisBand[]): string | null {
  if (!bands || bands.length === 0) return null;
  const v = clamp(value);
  const band = bands.find((b) => v >= b.min && v <= b.max);
  return band ? band.label : null;
}
