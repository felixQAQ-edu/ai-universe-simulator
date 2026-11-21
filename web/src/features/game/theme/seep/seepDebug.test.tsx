import { act, fireEvent, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AttributeAxisMeta, AxisSeverity } from '../../../../api';
import { resetDebugCache } from '../debug';
import { SkinRuntime } from '../lifecycle';
import { SEEPS, type SeepSignals } from './registry';
import { useSeepDebug, type SeepDebug } from './SeepDebug';
import { SeepProse } from './SeepProse';

// 渗入层调试控件(ADR-043 挂账项,Felix 2026-11-21):仅 ?debug=1 出现;只改这一层的展示输入。

const seep = SEEPS.yanqi!;
const band = (min: number, max: number, label: string) => ({ min, max, label });
const AXES: AttributeAxisMeta[] = [
  { key: 'realm', displayName: '境界', bands: [band(0, 30, '初境'), band(31, 60, '小成'), band(61, 100, '高深')] },
  { key: 'knowledge', displayName: '窥天', bands: [band(0, 30, '未觉'), band(31, 60, '见格'), band(61, 100, '知渔')] },
];
const REAL: SeepSignals = { stainLevel: 1, bleedSeverity: 'caution' };

function setUrl(url: string) {
  window.history.replaceState({}, '', url);
  resetDebugCache();
}
afterEach(() => {
  setUrl('/');
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function harness(signals: SeepSignals = REAL) {
  const out: { current: SeepDebug | null } = { current: null };
  function Host() {
    const d = useSeepDebug(seep, AXES, signals);
    out.current = d;
    return <>{d.controls}</>;
  }
  return { ...render(<Host />), out };
}

describe('useSeepDebug · 只在 ?debug=1 下出现', () => {
  it('普通路径:没有按钮,信号原样透传(同一个对象),两个计数恒 0', () => {
    setUrl('/');
    const h = harness();
    expect(h.container.innerHTML).toBe('');
    expect(h.out.current!.signals).toBe(REAL);
    expect([h.out.current!.stampNonce, h.out.current!.bleedNonce]).toEqual([0, 0]);
  });

  it('?debug=1:三个按钮;未登记渗入的局即便 debug 也没有', () => {
    setUrl('/?debug=1');
    const h = harness();
    expect([...h.container.querySelectorAll('button')].map((b) => b.textContent)).toEqual([
      '窥天切档:见格 → 知渔',
      '重压印',
      '触发字洇',
    ]);
    // 按钮行带 data-seep:墨洇量行框时不把它当文字
    expect(h.container.querySelector('[data-seep="debug"]')).not.toBeNull();

    function Bare() {
      return <>{useSeepDebug(null, AXES, REAL).controls}</>;
    }
    expect(render(<Bare />).container.innerHTML).toBe('');
  });

  it('窥天切档:见格 → 知渔 → 未觉 → 见格 循环;只换墨洇的档,字洇的 severity 不动', () => {
    setUrl('/?debug=1');
    const h = harness();
    const btn = () => h.container.querySelector('button')!;
    fireEvent.click(btn());
    expect(h.out.current!.signals).toEqual({ stainLevel: 2, bleedSeverity: 'caution' });
    expect(btn().textContent).toBe('窥天切档:知渔 → 未觉');
    fireEvent.click(btn());
    expect(h.out.current!.signals.stainLevel).toBe(0);
    fireEvent.click(btn());
    expect(h.out.current!.signals.stainLevel).toBe(1);
  });

  it('重压印 / 触发字洇:各自加一,互不影响', () => {
    setUrl('/?debug=1');
    const h = harness();
    const [, stamp, bleed] = [...h.container.querySelectorAll('button')];
    fireEvent.click(stamp);
    fireEvent.click(stamp);
    fireEvent.click(bleed);
    expect([h.out.current!.stampNonce, h.out.current!.bleedNonce]).toEqual([2, 1]);
  });
});

// ── 字洇的手动触发口 ─────────────────────────────────────────────────
const TEXT = '台心那方青石,印文如祝。';

describe('SeepProse · debugFire', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'performance'] });
    vi.spyOn(Math, 'random').mockReturnValue(0);
  });

  function mount(severity: AxisSeverity | null, paused = false) {
    const runtime = new SkinRuntime();
    const ui = (fire: number, p = paused) => (
      <SeepProse
        text={TEXT}
        caret={false}
        seep={{ ...seep, stain: undefined }}
        signals={{ stainLevel: null, bleedSeverity: severity }}
        paused={p}
        runtime={runtime}
        debugFire={fire}
      />
    );
    const r = render(ui(0));
    const span = () => r.container.querySelector('[data-seep-bleed]') as HTMLElement | null;
    return { ...r, span, fire: (n: number, p?: boolean) => r.rerender(ui(n, p)) };
  }

  it('清明澄澈(本不调度)也立刻演一次;演完不再排下一次', () => {
    const m = mount('neutral');
    act(() => vi.advanceTimersByTime(300_000));
    expect(m.span()).toBeNull();
    m.fire(1);
    expect(m.span()?.dataset.seepBleed).toBe('pre');
    act(() => {
      vi.advanceTimersToNextFrame();
      vi.advanceTimersToNextFrame();
    });
    expect(m.span()?.dataset.seepBleed).toBe('on');
    act(() => vi.advanceTimersByTime(1000 + 550 + 450));
    expect(m.span()).toBeNull();
    expect(m.container.textContent).toBe(TEXT);
    for (let t = 0; t < 300_000; t += 250) {
      act(() => vi.advanceTimersByTime(250));
      expect(m.span(), `t+${t + 250}ms`).toBeNull();
    }
  });

  it('首次装载的计数不算触发;逐字期间(paused)按了也不演', () => {
    const m = mount('neutral', true);
    act(() => vi.advanceTimersByTime(1000));
    expect(m.span()).toBeNull();
    m.fire(1, true);
    act(() => vi.advanceTimersByTime(5000));
    expect(m.span()).toBeNull();
  });

  it('reduced-motion 下按了也不演', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    const m = mount('danger');
    m.fire(1);
    act(() => vi.advanceTimersByTime(5000));
    expect(m.span()).toBeNull();
  });
});
