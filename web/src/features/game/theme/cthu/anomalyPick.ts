import type { AnomalyMode, Chunk } from './anomalySegment';

// 克苏鲁 · 文字异常**选点**(四层分工的第 ②层)。
//
// 纯逻辑、零 React、零计时器 —— **随机源注入**,故下面每条约束都能被测试钉死,
// 而不是靠「跑十分钟看看有没有连着来两次」(同刀 3 电台抽取器的做法)。
//
// ── 三条约束 ──────────────────────────────────────────────────────────
//   ① **同一目标不连续**:刚动过的那一片,下一次不再动它;
//   ② **同一类型不连续**:刚做过 swap,下一次换一种 —— 六型轮换才不会被认成「那个抖字特效」;
//   ③ 候选全被挡住 → **返回 null**,调用方整次跳过。
//      **绝不放宽约束硬凑一次**(同电台「沉默也是内容」),更不为凑 swap 而降质替换成任意字。

/** 文字异常的六型里可自动选点的那五型(第六型「读数损坏」在仪表上,不在文字里)。 */
export const TEXT_MODES: readonly AnomalyMode[] = ['swap', 'swap2', 'tail', 'squeeze', 'shift'];

/** 选点器的可变状态。**由调用方持有**(ref),本模块只做纯变换。 */
export interface PickState {
  lastIndex: number;
  lastMode: AnomalyMode | null;
}

export function createPickState(): PickState {
  return { lastIndex: -1, lastMode: null };
}

export interface Pick {
  index: number;
  mode: AnomalyMode;
  chunk: Chunk;
}

/**
 * 从片流里挑一处异常。**不修改 `state`**(推进状态走 {@link markPicked},
 * 与「挑到了但没做成」的路径分开 —— 换行守卫会当场取消,那次不该算数)。
 *
 * @param rand 随机源(注入)
 */
export function pickAnomaly(
  chunks: Chunk[],
  state: PickState,
  rand: () => number = Math.random,
): Pick | null {
  const pool: Pick[] = [];
  chunks.forEach((chunk, index) => {
    if (!TEXT_MODES.includes(chunk.mode)) return; // punct / plain 不作文字异常目标
    if (index === state.lastIndex) return; // ① 同一目标不连续
    if (chunk.mode === state.lastMode) return; // ② 同一类型不连续
    pool.push({ index, mode: chunk.mode, chunk });
  });
  if (!pool.length) return null; // ③ 全被挡住 → 这一次什么都不发生
  return pool[Math.min(pool.length - 1, Math.floor(rand() * pool.length))];
}

/** 记下这一次真的做了(约束状态推进)。 */
export function markPicked(state: PickState, pick: Pick): void {
  state.lastIndex = pick.index;
  state.lastMode = pick.mode;
}

/** 标点余韵的候选(句末标点)。没有句末标点 → null,该次无余韵。 */
export function pickPunct(chunks: Chunk[], rand: () => number = Math.random): number | null {
  const idx = chunks.flatMap((c, i) => (c.mode === 'punct' ? [i] : []));
  if (!idx.length) return null;
  return idx[Math.min(idx.length - 1, Math.floor(rand() * idx.length))];
}

/**
 * 行尾多出来的那个字。
 *
 * **刻意不用显眼的恐怖词**(「它」「眼睛」「回来」一类):平静句子里多一个语义勉强成立的
 * 虚字,比多一个恐怖名词吓人得多 —— 后者是在**演**恐怖,前者才是「这句话不太对」。
 * 95% 一个字,5% 两个字。
 */
export function tailExtra(rand: () => number = Math.random): string {
  if (rand() < 0.05) return '去了';
  return rand() < 0.5 ? '了' : '去';
}
