import type { AxisSeverity } from '../../../../api';
import type { BleedSpec, BleedWindow } from './registry';

// 字洇成墨的调度(ADR-043 决策 5;审定 6 定数)。纯函数,随机源注入。
// 指数分布(无记忆、学不成节拍,同 cthu/anomalySchedule)按 severity 取均值,截断到登记的区间;
// neutral / 缺省 → 不调度(清明澄澈时正文安静)。

/** 这一档的调度窗口;null = 不出现。 */
export function bleedWindow(spec: BleedSpec, severity: AxisSeverity | null): BleedWindow | null {
  if (severity !== 'caution' && severity !== 'danger') return null;
  return spec.schedule[severity] ?? null;
}

/** 首次至少这么久(正文稳定后才开始计时)。 */
export const FIRST_MIN_MS = 20_000;

export function nextBleedDelayMs(win: BleedWindow, first: boolean, rand: () => number = Math.random): number {
  const d = Math.min(win.maxMs, Math.max(win.minMs, -Math.log(1 - rand()) * win.meanMs));
  return first ? Math.max(FIRST_MIN_MS, d) : d;
}

/** 正文里每个汉字的位置(UTF-16 下标与长度;不含标点、数字、拉丁)。 */
export function hanOffsets(text: string): { offset: number; length: number }[] {
  const out: { offset: number; length: number }[] = [];
  for (const m of text.matchAll(/\p{Script=Han}/gu)) out.push({ offset: m.index!, length: m[0].length });
  return out;
}
