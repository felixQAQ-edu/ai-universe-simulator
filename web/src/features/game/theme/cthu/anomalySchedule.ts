import type { AxisSeverity } from '../../../../api';

// 克苏鲁 · 文字异常**调度节奏**(四层分工的第 ③层里的纯逻辑那一半)。
//
// 抽成纯函数是因为:**调度比类型池更重要**。六种异常再精致,只要节奏可被学会,
// 记忆点就退化成计时器 —— 而「节奏对不对」恰恰是最难靠肉眼冒烟判断的东西。
// 随机源注入,故下面每条都能被用例钉住。
//
// ── 三段节奏 ─────────────────────────────────────────────────────────
//   baseline  进入后 55–75s **零异常** —— 先让玩家确信「这里是正常的」,
//             否则第一次异常没有可对照的常态,读起来只是「这网页有毛病」;
//   aftermath 首次异常之后**长沉默** 45–90s —— 不确认、不重复,让人怀疑自己看错了;
//   steady    **指数分布**采样(均值按状态调),截断 [12s, 90s]。
//
// ── 为什么是指数分布 ──────────────────────────────────────────────────
// 指数分布**无记忆**:已经等了多久,与还要等多久无关 —— 玩家因此无法从「上一次多久前」
// 推断「下一次什么时候」。固定间隔或均匀分布都会被学成节拍(刀 3 电台同一条教训:
// 固定心跳会被量化成「每 14 秒响一次」,那一刻它就从世界变成了 UI 计时器)。
//
// ── 频率随状态而变,但**不得形成周期**(ADR-018 §5 Q4)──────────────────
// 高位:极低频但**非绝对零**;低位:仍保持长沉默与不可预测。
// 只在低位才启动是错的 —— 那会让「异常出现」本身变成**状态播报**:
// 玩家看见一次扭曲就知道自己进危险区了,记忆点退化为状态指示器。

export type AnomalyPhase = 'baseline' | 'aftermath' | 'steady';

/** 稳态均值(ms)。**只认 severity**(服务端派生),不认轴 key、不认数值高低(§4.2)。 */
export function meanForSeverity(severity: AxisSeverity | null): number {
  if (severity === 'danger') return 22_000;
  if (severity === 'caution') return 30_000;
  // neutral 与**四种缺省**(无签名轴 / 无档表 / 老数据 / 未知值)一律按最低频 ——
  // 「不确定时不吓玩家」在这里的具体形态。
  return 42_000;
}

/** 稳态截断区间:再频也不密于 12s,再稀也不长于 90s(长于此玩家会以为坏了)。 */
export const MIN_STEADY_MS = 12_000;
export const MAX_STEADY_MS = 90_000;

/** 静读(久未滚动)时的均值折扣:盯着一段字看的时候,它更可能动一下。 */
export const STILL_READING_MS = 25_000;
const STILL_READING_FACTOR = 0.85;

export interface DelayInput {
  phase: AnomalyPhase;
  severity: AxisSeverity | null;
  /** 距上次滚动多久(ms)。 */
  sinceScrollMs: number;
}

/** 下一次异常的间隔(ms)。`rand` 注入。 */
export function nextDelayMs(
  { phase, severity, sinceScrollMs }: DelayInput,
  rand: () => number = Math.random,
): number {
  if (phase === 'baseline') return 55_000 + rand() * 20_000;
  if (phase === 'aftermath') return 45_000 + rand() * 45_000;
  // 指数分布采样(逆变换法)。rand() 取到 1 时 log(0) = -∞,故用 1 - rand() 兜住上界。
  const mean = meanForSeverity(severity);
  let d = -Math.log(1 - rand()) * mean;
  if (sinceScrollMs > STILL_READING_MS) d *= STILL_READING_FACTOR;
  return Math.min(MAX_STEADY_MS, Math.max(MIN_STEADY_MS, d));
}

/** 相位推进:基线只走一次,之后稳态。 */
export function advancePhase(phase: AnomalyPhase): AnomalyPhase {
  return phase === 'baseline' ? 'aftermath' : 'steady';
}

/** 忙碌 / 刚滚动 / 停表时的顺延步长 —— **顺延不补发**(错过就是错过)。 */
export function deferMs(rand: () => number = Math.random): number {
  return 6_000 + rand() * 8_000;
}

/** 回到前台后的重新起算步长:**绝不累积连发**(切回来立刻炸一串是最廉价的破绽)。 */
export function resumeMs(rand: () => number = Math.random): number {
  return 8_000 + rand() * 6_000;
}
