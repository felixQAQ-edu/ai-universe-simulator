import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxisSeverity } from '../../../../api';
import { SkinRuntime } from '../lifecycle';
import { FIRST_MIN_MS, bleedWindow, hanOffsets, nextBleedDelayMs } from './bleedSchedule';
import { SEEPS } from './registry';
import { SeepProse } from './SeepProse';

// 字洇成墨(ADR-043 刀 5)。

const seep = SEEPS.yanqi!;
const bleed = seep.bleed!;

describe('调度(审定 6)', () => {
  it('neutral / 缺省不调度;caution 75s [30, 150];danger 40s [20, 90]', () => {
    expect(bleedWindow(bleed, 'neutral')).toBeNull();
    expect(bleedWindow(bleed, null)).toBeNull();
    expect(bleedWindow(bleed, 'caution')).toEqual({ meanMs: 75_000, minMs: 30_000, maxMs: 150_000 });
    expect(bleedWindow(bleed, 'danger')).toEqual({ meanMs: 40_000, minMs: 20_000, maxMs: 90_000 });
  });

  it('指数分布逆变换、截断到区间;首次至少 20s', () => {
    const c = bleedWindow(bleed, 'caution')!;
    expect(nextBleedDelayMs(c, false, () => 0)).toBe(30_000);
    expect(nextBleedDelayMs(c, false, () => 0.999999)).toBe(150_000);
    expect(nextBleedDelayMs(c, false, () => 0.5)).toBeCloseTo(-Math.log(0.5) * 75_000);
    const d = bleedWindow(bleed, 'danger')!;
    expect(nextBleedDelayMs(d, true, () => 0)).toBe(FIRST_MIN_MS);
    expect(FIRST_MIN_MS).toBe(20_000);
  });

  it('只挑汉字:标点、数字、拉丁都不算', () => {
    expect(hanOffsets('印,A1文。').map((h) => h.offset)).toEqual([0, 4]);
  });
});

// ── 组件 ─────────────────────────────────────────────────────────────
const TEXT = '台心那方青石,印文如祝。';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'performance'] });
  vi.spyOn(Math, 'random').mockReturnValue(0); // 调度取下限、选字取第一个可选的
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function mount(severity: AxisSeverity | null, paused = false, runtime = new SkinRuntime()) {
  const ui = (sev: AxisSeverity | null, p: boolean, text = TEXT) => (
    <SeepProse
      text={text}
      caret={false}
      seep={{ ...seep, stain: undefined }}
      signals={{ stainLevel: null, bleedSeverity: sev }}
      paused={p}
      runtime={runtime}
    />
  );
  const r = render(ui(severity, paused));
  const span = () => r.container.querySelector('[data-seep-bleed]') as HTMLElement | null;
  const frames = (n: number) =>
    act(() => {
      for (let i = 0; i < n; i++) vi.advanceTimersToNextFrame();
    });
  /** 逐 250ms 步进,每一步都要求没有在途的字洇(只看终点会落在两次之间,抓不到)。 */
  const neverWithin = (ms: number) => {
    for (let t = 0; t < ms; t += 250) {
      act(() => vi.advanceTimersByTime(250));
      expect(span(), `t+${t + 250}ms`).toBeNull();
    }
  };
  return { ...r, runtime, span, frames, neverWithin, set: (sev: AxisSeverity | null, p: boolean, text?: string) => r.rerender(ui(sev, p, text)) };
}

describe('字洇 · 时序与载体', () => {
  it('danger:首次 20s;plain → on(两帧后)→ +1000 off → +550 plain → +450 复原;字与文本始终不变', () => {
    const m = mount('danger');
    act(() => vi.advanceTimersByTime(19_999));
    expect(m.span()).toBeNull();
    act(() => vi.advanceTimersByTime(1));
    expect(m.span()?.dataset.seepBleed).toBe('pre');
    expect(m.span()?.textContent).toBe('台');
    expect(m.container.textContent).toBe(TEXT);
    m.frames(2);
    expect(m.span()?.dataset.seepBleed).toBe('on');
    act(() => vi.advanceTimersByTime(999));
    expect(m.span()?.dataset.seepBleed).toBe('on');
    act(() => vi.advanceTimersByTime(1));
    expect(m.span()?.dataset.seepBleed).toBe('off');
    act(() => vi.advanceTimersByTime(550));
    expect(m.span()?.dataset.seepBleed).toBe('plain');
    expect(m.container.textContent).toBe(TEXT);
    act(() => vi.advanceTimersByTime(450));
    expect(m.span()).toBeNull();
    // 复原成一段文本(一个文本节点)
    expect([...m.container.firstElementChild!.childNodes].map((n) => n.nodeType)).toEqual([Node.TEXT_NODE]);
  });

  it('不加 aria、不改字:只是一层 span', () => {
    const m = mount('danger');
    act(() => vi.advanceTimersByTime(20_000));
    const s = m.span()!;
    expect([...s.attributes].map((a) => a.name).sort()).toEqual(['class', 'data-seep-bleed', 'style']);
  });

  it('一屏最多一个:在途时不起第二次;下一次不选同一字位', () => {
    const m = mount('danger');
    act(() => vi.advanceTimersByTime(20_000));
    expect(m.container.querySelectorAll('[data-seep-bleed]')).toHaveLength(1);
    m.frames(2);
    act(() => vi.advanceTimersByTime(2_000)); // 走完这一次
    expect(m.span()).toBeNull();
    act(() => vi.advanceTimersByTime(20_000)); // danger 下限 20s
    expect(m.span()?.textContent).toBe('心'); // 不连续两次「台」
  });

  it('caution 下限 30s;neutral 永不', () => {
    const c = mount('caution');
    act(() => vi.advanceTimersByTime(29_999));
    expect(c.span()).toBeNull();
    act(() => vi.advanceTimersByTime(1));
    expect(c.span()).not.toBeNull();
    c.unmount();
    const n = mount('neutral');
    n.neverWithin(300_000);
  });

  it('paused(生成中 / 逐字未完)期间不调度;在途时一变 paused 立即撤销', () => {
    const m = mount('danger', true);
    m.neverWithin(120_000);
    m.set('danger', false);
    act(() => vi.advanceTimersByTime(20_000));
    expect(m.span()).not.toBeNull();
    m.set('danger', true);
    expect(m.span()).toBeNull();
    m.neverWithin(120_000);
  });

  it('换回合(runtime teardown)→ 在途作废,不留半截', () => {
    const m = mount('danger');
    act(() => vi.advanceTimersByTime(20_000));
    m.frames(2);
    act(() => m.runtime.teardown());
    expect(m.span()).toBeNull();
    m.neverWithin(5_000);
  });

  it('reduced-motion:不出现(审定 7)', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    const m = mount('danger');
    m.neverWithin(300_000);
  });
});
