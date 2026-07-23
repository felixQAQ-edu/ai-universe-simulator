import type { AxisBand, AxisSeverity } from '../../api';

// #3 数值行为化 · 行为档解析(纯函数,展示层辅助)。后端 init 把行为档投影成显式 inclusive 区间
// [{min,max,label,severity}](连续覆盖 [0,100]、axisRole 无关),前端只需命中 min≤value≤max 即得当前档。
// 守 ADR-003:纯函数、无平台 IO、不接触 isTrue/hiddenLogic。
//
// ADR-018 语义产出方原则:按区间匹配当前档 = 数据匹配(本文件干的事,合法);
// 判断这个档危不危险 = 语义判断,已由服务端在 severity 里给出 —— 前端只读不推断,
// 绝不按 key / label 文本 / 数组位置发明启发式。

const clamp = (n: number) => Math.max(0, Math.min(100, n));

const KNOWN_SEVERITIES: readonly string[] = ['neutral', 'caution', 'danger'];

/** 据当前值就近解析所处的档。无档表 / 未命中(理论不会,区间覆盖全域)→ null。 */
export function resolveBand(value: number, bands?: AxisBand[]): AxisBand | null {
  if (!bands || bands.length === 0) return null;
  const v = clamp(value);
  return bands.find((b) => v >= b.min && v <= b.max) ?? null;
}

/** 据当前值就近解析行为档 label。无档表 / 未命中 → null(只显数字)。 */
export function resolveBandLabel(value: number, bands?: AxisBand[]): string | null {
  return resolveBand(value, bands)?.label ?? null;
}

/**
 * 据当前值解析该档的风险等级(ADR-018)。**四种缺省一律安全降级为 null**——
 * 无 bands / 值未命中任何区间 / 老数据没有 severity / severity 是未知取值。
 *
 * null 的含义是「不附加危险色、不进入警告态」,数字与 label 照常显示。
 * **绝不默认 danger**(把不确定渲染成危险 = 给玩家一个假的状态播报),
 * **绝不回退旧启发式**(不按轴 key 猜、不按 label 文本猜、不按位置猜)。
 */
export function resolveSeverity(value: number, bands?: AxisBand[]): AxisSeverity | null {
  const severity = resolveBand(value, bands)?.severity;
  if (typeof severity !== 'string') return null;
  return KNOWN_SEVERITIES.includes(severity) ? severity : null;
}
