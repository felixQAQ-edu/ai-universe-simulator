import type { StatsTimeline } from '../contract';

// 《寻常》· 刻痕的纯函数(ADR-020「《寻常》局内皮肤」一节决策 3)。只吃 `StatsProps.timeline`,不读任何轴。
//
// 一回合一道刻痕;第 i 道(从 0 起)= 第 i + 1 回合。越早的越淡,最近三道是实的,往前每道淡 0.07,最淡 0.22。
// 服务端标记过的回合(ADR-040 `markedTurns`)那一道是长的,而且不再变淡 —— 这是一级记忆点「留下的那一年」留下的样子。

/** 最淡到多少(样张 `tickOpacity` 的地板)。 */
export const TICK_FLOOR = 0.22;
/** 往前每道淡多少。 */
export const TICK_STEP = 0.07;

/** 一道刻痕的不透明度:`age` = 它离最新那道隔了几道(最新的是 0)。照样张 `tickOpacity`。 */
export function tickOpacity(age: number): number {
  if (age < 3) return 1;
  // 取三位小数:写进 style 的值稳定,不出现 0.9299999 这类浮点尾巴。
  return Math.max(TICK_FLOOR, Math.round((1 - (age - 2) * TICK_STEP) * 1000) / 1000);
}

export interface Tick {
  /** 这道刻痕是第几回合(从 1 起)。 */
  turn: number;
  kept: boolean;
  opacity: number;
}

/** 按回合数排出全部刻痕。turn ≤ 0 → 一道都没有。 */
export function ticksFor(timeline: StatsTimeline): Tick[] {
  const n = Math.max(0, Math.floor(timeline.turn));
  const marked = new Set(timeline.markedTurns);
  const out: Tick[] = [];
  for (let i = 0; i < n; i++) {
    const turn = i + 1;
    const kept = marked.has(turn);
    out.push({ turn, kept, opacity: kept ? 1 : tickOpacity(n - 1 - i) });
  }
  return out;
}

/**
 * 这一次渲染是不是该演记忆点:回合往前走了,且清单里**多出了刚落地的那一回合**。
 * 只比对服务端给的清单,不判档(ADR-018 §4.2;档是服务端判的,ADR-040)。
 * 没有上一次(首帧 / 续局 / 换局)→ false:已标记的刻痕直接画长,不播。
 */
export function justMarked(prev: StatsTimeline | null, next: StatsTimeline): boolean {
  if (!prev) return false;
  if (next.turn <= prev.turn) return false;
  return next.markedTurns.includes(next.turn) && !prev.markedTurns.includes(next.turn);
}
