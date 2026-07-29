import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSkinRuntime } from '../lifecycle';
import { readTraces, resetTraces } from '../telemetry';
import { CthuProse } from './CthuProse';

// 刀 4 · 异常调度器的**生命周期**守护。
//
// 这一组测的是「调度链还活着吗」——**最难靠冒烟发现的一类失效**:表现只是
// 「那个效果再也不出现了」,无报错、无崩溃(ADR-018 §4.11 记的正是这个特征值)。
//
// 背景(本刀勘察时用探针实测坐实):`useSkinRuntime(skinKey, turn)` 在**换回合**时
// 会 `teardown()` 清掉全部受管定时器。若调度链的 effect 只依赖 `runtime`
// (它的身份跨 turn 稳定),effect 就不会重跑 → 链被清掉后再没有人重排 →
// **记忆点从第二回合起永久静默**。故本文件用**真实接线**(真的 useSkinRuntime + 真的换 turn)
// 钉住这条,手动 new 一个 runtime 是复现不出来的(同 §4.9 的先例)。

const TEXT = '港口的雾比昨夜更沉。渔市收摊后石板路上留下发亮的黏液,顺着坡道延伸到旧灯塔。';

/**
 * 真实接线的宿主:runtime 由 `useSkinRuntime` 给,turn 可变。
 *
 * **`rootRef` 必须是稳定引用**(生产里它是 `GameScreen` 的 `useRef`)。
 * 起初这里每次渲染都新建一个 `{current}` 字面量,结果 `setVar → fireSpore → fireText`
 * 逐层失稳、调度 effect 每次渲染都重跑 —— 于是**即便把依赖里的 `turn` 删掉,
 * 用例也照样通过**:守护形同虚设。这条已用变异验证过(删 `turn` 必须变红),
 * 同 ADR-018 §4.9 的教训:守护必须复现**真实接线**,否则守的是自己造的假象。
 */
const STABLE_ROOT = { current: document.body as HTMLElement | null };

function Host({ turn, paused = false }: { turn: number; paused?: boolean }) {
  const runtime = useSkinRuntime('cthulhu', turn);
  const rootRef = STABLE_ROOT;
  return (
    <CthuProse
      text={TEXT}
      caret={false}
      runtime={runtime}
      rootRef={rootRef}
      paused={paused}
      generating={false}
      turn={turn}
      signatureSeverity="danger"
    />
  );
}

/**
 * 低频槽是否真的演了一次。**刻意只看异常与仪器两条**,不看 `cthu.spore` ——
 * 孢子暂停是持续槽自身的调制、走独立调度器,与低频槽互不知晓(假线索的设计前提)。
 * 把它算进来,「停表期不触发」这条就会被孢子的独立调度误判成失败。
 */
const anomalyFired = () =>
  readTraces().some(
    (t) =>
      (t.name === 'cthu.anomaly' || t.name === 'cthu.instrument') &&
      (t.firedAt !== undefined || !!t.state?.startsWith('pick')),
  );

beforeEach(() => {
  resetTraces();
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('异常调度器生命周期', () => {
  it('进入后的基线期内不触发(先建立「这里是正常的」)', () => {
    render(<Host turn={1} />);
    act(() => void vi.advanceTimersByTime(40_000)); // < 55s 基线下限
    expect(anomalyFired()).toBe(false);
  });

  it('基线期过后会触发', () => {
    render(<Host turn={1} />);
    act(() => void vi.advanceTimersByTime(80_000));
    expect(anomalyFired()).toBe(true);
  });

  // ★ 本文件的核心用例:把「换回合后调度链死掉」这条钉死。
  // **已用变异验证**:把链条改挂 `runtime.setTimeout`(= 刀 3 电台的写法)本用例立刻变红。
  it('★ 换回合后调度链仍然活着 —— 链条不得挂在会被 teardown 清空的受管定时器上', () => {
    const { rerender } = render(<Host turn={1} />);
    act(() => void vi.advanceTimersByTime(80_000));
    expect(anomalyFired()).toBe(true);

    resetTraces();
    act(() => void rerender(<Host turn={2} />)); // teardown 会清掉在途定时器
    act(() => void vi.advanceTimersByTime(120_000));
    expect(anomalyFired()).toBe(true); // ← 依赖里少了 turn 就会在这里变红
  });

  it('连换三个回合仍然活着(不是只救回第一次)', () => {
    const { rerender } = render(<Host turn={1} />);
    act(() => void vi.advanceTimersByTime(80_000));
    for (const turn of [2, 3, 4]) {
      resetTraces();
      act(() => void rerender(<Host turn={turn} />));
      act(() => void vi.advanceTimersByTime(120_000));
      expect(anomalyFired()).toBe(true);
    }
  });

  it('停表期(生成中 / 开场逐字)不触发 —— 正文不稳定期一律停', () => {
    render(<Host turn={1} paused />);
    act(() => void vi.advanceTimersByTime(200_000));
    expect(anomalyFired()).toBe(false);
  });

  it('停表解除后恢复触发(是顺延,不是永久停摆)', () => {
    const { rerender } = render(<Host turn={1} paused />);
    act(() => void vi.advanceTimersByTime(120_000));
    expect(anomalyFired()).toBe(false);
    act(() => void rerender(<Host turn={1} paused={false} />));
    act(() => void vi.advanceTimersByTime(120_000));
    expect(anomalyFired()).toBe(true);
  });

  it('卸载后不再触发(旧回合的回调绝不补发)', () => {
    const { unmount } = render(<Host turn={1} />);
    unmount();
    resetTraces();
    act(() => void vi.advanceTimersByTime(300_000));
    expect(anomalyFired()).toBe(false);
  });
});
