import { RADIO_POOL, type RadioLayer } from './radioPool';

// 末日 · 电台**抽取约束**(四层分工的第 ②层:内容定义 / **抽取** / 调度 / 呈现)。
//
// 纯逻辑、零 React、零计时器 —— 随机源注入,故三条约束都能被测试钉死。
//
// ── 三条约束(B 批验证轮·项四,30 分钟长跑验证过)────────────────────────
//   ① **一轮内不重复**:shuffle bag —— 抽完一轮才重新洗牌,而不是每次独立随机
//      (独立随机会出现「同一条连着来三次」,那是最快毁掉可信度的事);
//   ② **同层不连播**:刚播过生活层,下一条不再是生活层 —— 让四层的质地交替出现;
//   ③ **异常层独立冷却 4 分钟**:异常是全池最稀有的东西,连着来两条就不异常了。
//
// 三条约束同时挡住所有候选时**返回 null** —— 调用方据此转为「调谐失败」:
// **沉默也是内容**,总比放宽约束硬凑一条要好。

/** 异常层冷却(ms)。 */
export const ODD_COOLDOWN_MS = 240_000;

/** 抽取器的可变状态。**由调用方持有**(ref),本模块只做纯变换。 */
export interface DrawState {
  /** 当前这一轮剩余的候选下标(空 = 该重新洗牌了)。 */
  bag: number[];
  /** 上一条的下标(避免洗牌后第一条恰好接上刚播过的那条)。 */
  lastIdx: number;
  /** 上一条的层(同层不连播)。 */
  lastLayer: RadioLayer | null;
  /** 上一条异常层的播出时刻(`Date.now()`;冷却用)。 */
  lastOddAt: number;
}

export function createDrawState(): DrawState {
  return { bag: [], lastIdx: -1, lastLayer: null, lastOddAt: 0 };
}

/** Fisher–Yates。随机源注入,测试可给定值。 */
function shuffle<T>(a: T[], rand: () => number): T[] {
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/**
 * 抽一条。**就地更新 `state`**;三条约束全挡住 → 返回 null(调用方转调谐失败)。
 *
 * @param now 当前时刻(注入,便于测冷却)
 */
export function drawIndex(
  state: DrawState,
  rand: () => number = Math.random,
  now: number = Date.now(),
): number | null {
  if (!state.bag.length) {
    state.bag = shuffle([...RADIO_POOL.keys()], rand);
    // 洗完牌若第一张正是刚播过的那条,与第二张换位 —— 「一轮不重复」在轮与轮的接缝处也要成立。
    if (state.bag[0] === state.lastIdx && state.bag.length > 1) {
      [state.bag[0], state.bag[1]] = [state.bag[1], state.bag[0]];
    }
  }

  for (let i = 0; i < state.bag.length; i++) {
    const idx = state.bag[i];
    const msg = RADIO_POOL[idx];
    if (msg.layer === state.lastLayer) continue; // ② 同层不连播
    if (msg.layer === 'odd' && now - state.lastOddAt < ODD_COOLDOWN_MS) continue; // ③ 异常冷却
    state.bag.splice(i, 1); // ① 出了 bag 就不再回来,直到下一轮
    return idx;
  }
  return null; // 全被挡住 → 沉默(调谐失败)
}

/** 记下这一条播了(约束状态推进)。与 {@link drawIndex} 分开,便于「抽到但没播」的路径。 */
export function markPlayed(state: DrawState, idx: number, now: number = Date.now()): void {
  const msg = RADIO_POOL[idx];
  state.lastIdx = idx;
  state.lastLayer = msg.layer;
  if (msg.layer === 'odd') state.lastOddAt = now;
}
