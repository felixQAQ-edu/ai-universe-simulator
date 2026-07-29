import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ProseProps, SkinBundle, WorldSkin } from './contract';
import { SkinRuntime } from './lifecycle';
import { useAnimatedValues } from './useAnimatedValues';

// 刀 4 的两处**基建扩点**(按 ADR-018 口径:基建缺口回头改刀 1 的产物,不在主题层打补丁)。
//
//   ① 可选**正文形态槽** `WorldSkin.Prose`  —— 文字异常要作用在正文的字上,而作用点需要分片 DOM;
//      通用 Prose 里按主题分支 = 世界判定复制进通用层(§4.1 失效),让氛围层去 query 正文 DOM
//      = 捅穿组件边界(§4.2)。故照刀 3 `Interlude` 的先例开一个**可选**槽。
//   ② `ValueRoll` 的时长/迟到允许给**函数**,通用层每次起 tween 时求值 —— 否则「时间不可信」
//      这条时间感在数值滚动上会**静默失效**(§4.12 搬元素要连关系一起搬)。
//
// 两处都必须证明:**不用新能力的世界行为逐字不变**。

const skinOf = (over: Partial<WorldSkin>): WorldSkin => ({ valueRoll: { durationMs: 500, ease: 'none' }, ...over }) as WorldSkin;

describe('正文形态槽(可选)', () => {
  it('不配槽 → 通用 Prose(不配的世界正文 DOM 不受影响,是构造保证)', () => {
    const skin = skinOf({});
    expect(skin.Prose).toBeUndefined();
  });

  it('配了槽 → 由主题层的形态组件渲染正文,并拿到全部所需上下文', () => {
    const seen: ProseProps[] = [];
    function Form(props: ProseProps) {
      seen.push(props);
      return <div data-testid="form">{props.text}</div>;
    }
    const props: ProseProps = {
      text: '雾比昨夜更沉。',
      caret: false,
      runtime: new SkinRuntime(),
      rootRef: { current: null },
      paused: false,
      generating: false,
      turn: 3,
      signatureSeverity: 'caution',
    };
    render(<Form {...props} />);
    expect(screen.getByTestId('form')).toHaveTextContent('雾比昨夜更沉。');
    // 契约面:停表信号、回合、runtime、签名轴 severity 一个都不能少 ——
    // 少任何一个,形态层就会被迫自己去猜(读 store / 读别人的 DOM / 读轴 key)。
    expect(seen[0].paused).toBe(false);
    expect(seen[0].turn).toBe(3);
    expect(seen[0].signatureSeverity).toBe('caution');
    expect(seen[0].runtime).toBeInstanceOf(SkinRuntime);
  });
});

describe('ValueRoll 时长允许函数(每次起 tween 求值)', () => {
  const bundleOf = (skin: WorldSkin): SkinBundle => ({ skin, runtime: new SkinRuntime() });

  function rollProbe(skin: WorldSkin) {
    function Probe({ values }: { values: Record<string, number> }) {
      useAnimatedValues(values, bundleOf(skin));
      return null;
    }
    const { rerender } = render(<Probe values={{ hp: 10 }} />);
    return (values: Record<string, number>) => act(() => rerender(<Probe values={values} />));
  }

  it('函数值:每一次数值变化都重新求值(不是模块加载时算一次)', () => {
    const dur = vi.fn(() => 400);
    const push = rollProbe(skinOf({ valueRoll: { durationMs: dur, ease: 'none' } }));
    push({ hp: 40 });
    const afterFirst = dur.mock.calls.length;
    expect(afterFirst).toBeGreaterThan(0);
    push({ hp: 70 });
    // ★ 这一条就是「时间不可信」在数值滚动上没被冻掉的证据:第二次滚动**又问了一次**。
    expect(dur.mock.calls.length).toBeGreaterThan(afterFirst);
  });

  it('数字值:照旧直接用,不额外求值 —— 刀 1/2/3 三世界行为逐字不变', () => {
    const push = rollProbe(skinOf({ valueRoll: { durationMs: 500, ease: 'none' } }));
    expect(() => push({ hp: 40 })).not.toThrow();
  });

  it('startDelayMs 缺省 = 0(不给的皮肤不产生任何迟到)', () => {
    const skin = skinOf({ valueRoll: { durationMs: 500, ease: 'none' } });
    expect(skin.valueRoll.startDelayMs).toBeUndefined();
  });
});
