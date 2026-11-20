import { act, render } from '@testing-library/react';
import gsap from 'gsap';
import { useMemo } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { SkinBundle, WorldSkin } from './contract';
import { useSkinRuntime } from './lifecycle';
import { useAnimatedValues } from './useAnimatedValues';

// 数值滚动遇到 turn 切换(FINDINGS F-041;ADR-018 §4.4 补充块)。
//
// 缺陷:gsap 3.15 下,context 里一段**已完成**的 to() 在 ctx.revert() 时会再触发一次 onUpdate,值 = 起点。
// teardown(turn 切换)→ revert → 旧回调把上一回合的起点写回 state:
//   · 值变化的回合:有一帧显示上上回合的值,再滚;
//   · 值不变的回合:effect 不重跑,显示一直停在旧值(档名、severity 跟着错)。
// 修法:过期 tween 的回调不写 state;teardown 时把 state 直接落到这次 tween 的目标值。
//
// 结构照 GameScreen:runtime 在父组件(`useSkinRuntime(skinKey, turn)`),滚动在子组件(StatsPanel 的位置),
// 于是 teardown(父的 effect 清理)与子组件的新 effect 在同一次提交里、按真实顺序发生。
// 时间不靠真实时钟:直接把全局时间线上的 tween seek 到 1 / 0.5(ease 'none' → 0.5 就是正中间)。

const SKIN = { valueRoll: { durationMs: 700, ease: 'none' } } as WorldSkin;

let seen: number[] = [];

function Child({ values, bundle }: { values: Record<string, number>; bundle: SkinBundle | null }) {
  const shown = useAnimatedValues(values, bundle);
  seen.push(shown.hp);
  return <span data-testid="v">{shown.hp}</span>;
}

function Host({ values, turn, skin }: { values: Record<string, number>; turn: number; skin: WorldSkin | null }) {
  const runtime = useSkinRuntime('test', turn);
  const bundle = useMemo(() => (skin ? { skin, runtime } : null), [skin, runtime]);
  return <Child values={values} bundle={bundle} />;
}

function setup(skin: WorldSkin | null, values: Record<string, number>) {
  const r = render(<Host values={values} turn={0} skin={skin} />);
  const shown = () => Number(r.getByTestId('v').textContent);
  const land = (turn: number, next: Record<string, number>) => act(() => r.rerender(<Host values={next} turn={turn} skin={skin} />));
  return { shown, land, unmount: r.unmount };
}

/** 把此刻全局时间线上的全部 tween 推到某个进度(同步触发 onUpdate / onComplete)。 */
function seek(progress: number) {
  act(() => {
    for (const t of gsap.globalTimeline.getChildren(true, true, false)) t.progress(progress);
  });
}

/** 依次落地若干回合,返回每次落地期间渲染出的**不同**的值(帧数不管:渲染期对齐本来就会多渲染一次)。 */
function framesOf(land: (turn: number, v: Record<string, number>) => void, steps: [number, number][]): number[][] {
  return steps.map(([turn, hp]) => {
    seen = [];
    land(turn, { hp });
    return [...new Set(seen)];
  });
}

function setReduced(on: boolean) {
  window.matchMedia = ((query: string) => ({
    matches: on && query.includes('reduce'),
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

beforeEach(() => {
  seen = [];
  setReduced(false);
  gsap.globalTimeline.clear();
});
afterEach(() => {
  setReduced(false);
  gsap.globalTimeline.clear();
});

describe('回合切换时数值滚动不回跳(F-041)', () => {
  it('回归 1:值变化的回合滚完 → 换回合且值不变 → 显示的是目标值,不是起点', () => {
    const { shown, land } = setup(SKIN, { hp: 70 });
    land(1, { hp: 63 });
    seek(1);
    expect(shown()).toBe(63);

    land(2, { hp: 63 }); // turn 切换 → teardown → revert;值没变,不起新 tween
    expect(shown()).toBe(63);
  });

  it('回归 2:值变化的回合滚完 → 下一回合值再变 → 过程中没有任何一帧回到上上回合的值', () => {
    const { shown, land } = setup(SKIN, { hp: 70 });
    land(1, { hp: 63 });
    seek(1);
    expect(shown()).toBe(63);

    seen = [];
    land(2, { hp: 56 });
    seek(0.5);
    seek(1);
    expect(shown()).toBe(56);
    expect(seen).not.toContain(70);
    // 从 63 往 56 滚:每一帧都在 [56, 63] 之内
    expect(seen.every((v) => v >= 56 && v <= 63)).toBe(true);
  });

  it('回归 3:滚动中途换回合(值不变)→ 落到该次目标值,不停在中间、不回到起点', () => {
    const { shown, land } = setup(SKIN, { hp: 70 });
    land(1, { hp: 63 });
    seek(0.5);
    const mid = shown();
    expect(mid).toBeGreaterThan(63);
    expect(mid).toBeLessThan(70);

    land(2, { hp: 63 });
    expect(shown()).toBe(63);
    // 旧 tween 已被收走:再推进时间也不会再写
    seek(1);
    expect(shown()).toBe(63);
  });

  it('滚动中途换回合且值又变 → 从此刻的中间值接着滚到新目标,不先跳回旧目标或起点', () => {
    const { shown, land } = setup(SKIN, { hp: 70 });
    land(1, { hp: 63 });
    seek(0.5);
    const mid = shown();

    seen = [mid];
    land(2, { hp: 56 });
    expect(seen).not.toContain(70);
    expect(seen.every((v) => v >= 56 && v <= mid)).toBe(true);
    seek(0.05); // 新滚动的头几帧(≈ 66):若先跳回过旧目标 63,这里就会往回走
    seek(0.5);
    seek(1);
    expect(shown()).toBe(56);
    // 一路往下:不先跳回旧目标(63)再从中间值重新起滚
    expect(seen.every((v, i) => i === 0 || v <= seen[i - 1])).toBe(true);
  });

  it('卸载时 teardown:不抛错', () => {
    const { land, unmount } = setup(SKIN, { hp: 70 });
    land(1, { hp: 63 });
    seek(0.5);
    expect(() => unmount()).not.toThrow();
  });
});

describe('不动画的两条路径逐字不变', () => {
  it('reduced-motion:目标值同帧落位,换回合前后每一帧都是目标值', () => {
    setReduced(true);
    const { land } = setup(SKIN, { hp: 70 });
    expect(framesOf(land, [[1, 63], [2, 63], [3, 56]])).toEqual([[63], [63], [56]]);
    expect(gsap.globalTimeline.getChildren(true, true, false)).toHaveLength(0);
  });

  it('未登记皮肤(bundle = null):原样返回目标值,不起 tween', () => {
    const { land } = setup(null, { hp: 70 });
    expect(framesOf(land, [[1, 63], [2, 63], [3, 56]])).toEqual([[63], [63], [56]]);
    expect(gsap.globalTimeline.getChildren(true, true, false)).toHaveLength(0);
  });
});
