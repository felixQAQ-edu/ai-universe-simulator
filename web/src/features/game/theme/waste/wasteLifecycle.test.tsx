import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSkinRuntime } from '../lifecycle';
import { readTraces, resetTraces } from '../telemetry';
import { WasteAmbient } from './WasteAmbient';
import { WasteRadio } from './WasteRadio';

// 末日 · 调度链的**生命周期**守护(刀 4 补写,起因见文件末尾的变异验证说明)。
//
// 守的是 ADR-018 §4.11 的**第二种子模式**:
//   「周期性调度 + 生命周期清理」的组合里,**清理之后必须有人负责重新开始**。
//
// 刀 3 的电台与远光把调度链挂在 `runtime.setTimeout` 上,而 effect 只依赖 `runtime`
// (它的身份**跨 turn 稳定**)。换回合时 `useSkinRuntime` 调 `teardown()` 清掉全部受管定时器,
// effect 却不会重跑 —— **没有人重新排期**,两个效果从第二回合起永久静默,且无报错。
// 与第一种子模式(远光的锁永占)一样,表现只是「那个效果再也不出现了」。

const runtimeHost = (Comp: React.ComponentType<{ turn: number }>) => Comp;

/** 电台宿主:真实接线(真的 useSkinRuntime + 真的换 turn)。 */
function RadioHost({ turn }: { turn: number }) {
  const runtime = useSkinRuntime('apocalypse', turn);
  return <WasteRadio runtime={runtime} paused={false} generating={false} turn={turn} />;
}

/** 远光宿主。`rootRef` 用稳定引用(生产里是 GameScreen 的 useRef)。 */
const STABLE_ROOT = { current: null as HTMLElement | null };

function AmbientHost({ turn }: { turn: number }) {
  const runtime = useSkinRuntime('apocalypse', turn);
  return (
    <WasteAmbient
      runtime={runtime}
      rootRef={STABLE_ROOT}
      paused={false}
      generating={false}
      turn={turn}
      setRootClass={() => {}}
      onIntroDone={() => {}}
      signatureTick={0}
    />
  );
}

void runtimeHost;

const fired = (name: string) =>
  readTraces().some((t) => t.name === name && (t.scheduledAt !== undefined || t.firedAt !== undefined));

beforeEach(() => {
  resetTraces();
  vi.useFakeTimers();
  // 固定随机源:0.1 同时满足 BURST_CHANCE(0.25)与 FAR_CHANCE(0.4),
  // 让「该不该播」不再是掷骰子 —— 本文件测的是**链条活没活**,不是概率。
  vi.spyOn(Math, 'random').mockReturnValue(0.1);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('末日电台:调度链跨回合存活', () => {
  it('第一回合会播', () => {
    render(<RadioHost turn={1} />);
    act(() => void vi.advanceTimersByTime(30_000));
    expect(fired('waste.radio')).toBe(true);
  });

  // ★ 刀 3 遗留缺陷的回归钉子。
  it('★ 换回合后仍会播 —— teardown 清掉受管定时器后必须有人重新排期', () => {
    const { rerender } = render(<RadioHost turn={1} />);
    act(() => void vi.advanceTimersByTime(30_000));
    expect(fired('waste.radio')).toBe(true);

    resetTraces();
    act(() => void rerender(<RadioHost turn={2} />));
    act(() => void vi.advanceTimersByTime(60_000));
    expect(fired('waste.radio')).toBe(true); // ← 修复前:这里恒 false(永久静默)
  });

  it('连换三个回合仍会播(不是只救回第一次)', () => {
    const { rerender } = render(<RadioHost turn={1} />);
    act(() => void vi.advanceTimersByTime(30_000));
    for (const turn of [2, 3, 4]) {
      resetTraces();
      act(() => void rerender(<RadioHost turn={turn} />));
      act(() => void vi.advanceTimersByTime(60_000));
      expect(fired('waste.radio')).toBe(true);
    }
  });

  it('卸载后不再排期(旧回合的回调绝不补发)', () => {
    const { unmount } = render(<RadioHost turn={1} />);
    unmount();
    resetTraces();
    act(() => void vi.advanceTimersByTime(120_000));
    expect(fired('waste.radio')).toBe(false);
  });
});

describe('末日远光:调度链跨回合存活', () => {
  it('★ 换回合后仍会演 —— 与电台同一条失效模式,同一条修法', () => {
    const { rerender } = render(<AmbientHost turn={1} />);
    act(() => void vi.advanceTimersByTime(70_000));
    expect(fired('waste.farlight')).toBe(true);

    resetTraces();
    act(() => void rerender(<AmbientHost turn={2} />));
    act(() => void vi.advanceTimersByTime(90_000));
    expect(fired('waste.farlight')).toBe(true);
  });

  it('卸载后不再排期', () => {
    const { unmount } = render(<AmbientHost turn={1} />);
    unmount();
    resetTraces();
    act(() => void vi.advanceTimersByTime(200_000));
    expect(fired('waste.farlight')).toBe(false);
  });
});

// ── 变异验证记录(ADR-018 §4.13:守护测试必须证明自己会红)────────────────
// 把 `useRadio` / `WasteAmbient` 的链条改回 `runtime.setTimeout(...)`(= 刀 3 的原写法),
// 本文件的三条 ★ 用例立刻变红;改回 `window.setTimeout` + effect cleanup 后全绿。
// **没有这一步,一条通过的测试无法区分「守住了」与「什么都没守」。**
