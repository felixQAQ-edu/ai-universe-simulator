import { StrictMode, act } from 'react';
import { render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ClientWorld } from '../../../../api';
import type { Archetype } from '../../../../types/schema';
import { useGameStore } from '../../../../state/gameStore';
import { GameScreen } from '../../GameScreen';
import { SkinRuntime } from '../lifecycle';
import { SEEPS } from './registry';
import { SeepStamp } from './SeepStamp';

// 验讫印(ADR-043 刀 4)。jsdom 没有 Web Animations:给 Element.prototype.animate 打桩,记下每次调用与 cancel。

const stamp = SEEPS.yanqi!.stamp!;

interface FakeAnim {
  keyframes: Keyframe[];
  options: KeyframeAnimationOptions;
  cancelled: boolean;
  cancel: () => void;
  onfinish: null | (() => void);
}
let anims: FakeAnim[] = [];

beforeEach(() => {
  anims = [];
  vi.useFakeTimers();
  (Element.prototype as unknown as { animate: unknown }).animate = function (
    keyframes: Keyframe[],
    options: KeyframeAnimationOptions,
  ) {
    const a: FakeAnim = {
      keyframes,
      options,
      cancelled: false,
      onfinish: null,
      cancel() {
        a.cancelled = true;
      },
    };
    anims.push(a);
    return a;
  };
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  delete (Element.prototype as unknown as { animate?: unknown }).animate;
});

function harness(runtime = new SkinRuntime()) {
  const ui = (tick: number, index: number | null) => (
    <div data-testid="anchor">
      <SeepStamp stamp={stamp} tick={tick} index={index} runtime={runtime} />
    </div>
  );
  const r = render(ui(0, 0));
  return {
    runtime,
    set: (tick: number, index: number | null) => r.rerender(ui(tick, index)),
    shown: () => r.container.querySelector('[data-seep="stamp"]'),
  };
}

describe('验讫印 · 在不在跟着当前档走(补裁 7)', () => {
  it('初境不在;首次装载就在小成 → 直接在、不播', () => {
    const h = harness();
    expect(h.shown()).toBeNull();
    const r2 = render(<SeepStamp stamp={stamp} tick={0} index={1} runtime={new SkinRuntime()} />);
    expect(r2.container.querySelector('[data-seep="stamp"]')?.textContent).toBe('验讫');
    expect(anims).toHaveLength(0);
  });

  it('跌回初境 → 不在(不播任何东西)', () => {
    const h = harness();
    h.set(0, 2);
    expect(h.shown()).not.toBeNull();
    h.set(0, 0);
    expect(h.shown()).toBeNull();
    expect(anims).toHaveLength(0);
  });

  it('无档表(null)→ 不在', () => {
    const h = harness();
    h.set(0, null);
    expect(h.shown()).toBeNull();
  });

  it('位置挂在境界印框上的 (105%, 87.1%),用边长变量的一半做偏移(边长随断点变,印心不变)', () => {
    const h = harness();
    h.set(0, 1);
    const el = h.shown() as HTMLElement;
    expect(el.style.left).toBe('calc(105% - var(--stamp-size) / 2)');
    expect(el.style.top).toBe('calc(87.1% - var(--stamp-size) / 2)');
  });
});

describe('验讫印 · 向上跨档压印(审定 4 / 5)', () => {
  it('跨档那一次提交里就藏起;+1650ms 才落下,样张关键帧 260ms', () => {
    const h = harness();
    h.set(1, 1);
    expect(h.shown()).toBeNull();
    act(() => vi.advanceTimersByTime(1649));
    expect(h.shown()).toBeNull();
    act(() => vi.advanceTimersByTime(1));
    expect(h.shown()).not.toBeNull();
    expect(anims).toHaveLength(1);
    expect(anims[0].keyframes).toEqual([
      { opacity: 0, transform: 'rotate(-9deg) scale(1.35)' },
      { opacity: 0.85, transform: 'rotate(-9deg) scale(0.96)', offset: 0.85 },
      { opacity: 0.85, transform: 'rotate(-9deg) scale(1)' },
    ]);
    expect(anims[0].options).toEqual({ duration: 260, easing: 'cubic-bezier(0.55, 0, 1, 0.45)' });
  });

  it('每次向上跨档都重压:小成 → 高深,印先藏、再落下', () => {
    const h = harness();
    h.set(1, 1);
    act(() => vi.advanceTimersByTime(1650));
    h.set(2, 2);
    expect(h.shown()).toBeNull();
    act(() => vi.advanceTimersByTime(1650));
    expect(h.shown()).not.toBeNull();
    expect(anims).toHaveLength(2);
  });

  it('同档重渲染不重播', () => {
    const h = harness();
    h.set(1, 1);
    act(() => vi.advanceTimersByTime(1650));
    h.set(1, 1);
    h.set(1, 1);
    expect(anims).toHaveLength(1);
  });

  it('跨档进来的是初境(理论上不会发生)→ 不藏不播', () => {
    const h = harness();
    h.set(1, 0);
    act(() => vi.advanceTimersByTime(3000));
    expect(h.shown()).toBeNull();
    expect(anims).toHaveLength(0);
  });

  it('pending 期间被打断(换回合 / 卸载)→ 直接常显,不补播;过期计时不再压', () => {
    const h = harness();
    h.set(1, 1);
    act(() => h.runtime.teardown());
    expect(h.shown()).not.toBeNull();
    act(() => vi.advanceTimersByTime(5000));
    expect(anims).toHaveLength(0);
  });

  it('压到一半被打断 → 动画取消,印停在 CSS 终态(常显)', () => {
    const h = harness();
    h.set(1, 1);
    act(() => vi.advanceTimersByTime(1650));
    act(() => h.runtime.teardown());
    expect(anims[0].cancelled).toBe(true);
    expect(h.shown()).not.toBeNull();
  });

  it('reduced-motion:跨档不藏、不播,档 > 0 即在', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    const h = harness();
    h.set(1, 1);
    expect(h.shown()).not.toBeNull();
    act(() => vi.advanceTimersByTime(5000));
    expect(anims).toHaveLength(0);
  });

  it('StrictMode:一次跨档只有一段在播的压印', () => {
    const runtime = new SkinRuntime();
    const ui = (tick: number, index: number) => (
      <StrictMode>
        <SeepStamp stamp={stamp} tick={tick} index={index} runtime={runtime} />
      </StrictMode>
    );
    const r = render(ui(0, 0));
    r.rerender(ui(1, 1));
    act(() => vi.advanceTimersByTime(1650));
    expect(anims.filter((a) => !a.cancelled)).toHaveLength(1);
    expect(r.container.querySelector('[data-seep="stamp"]')).not.toBeNull();
  });
});

// ── 接线 ────────────────────────────────────────────────────────────
const REALM_BANDS = [
  { min: 0, max: 30, label: '初境', severity: 'neutral' as const },
  { min: 31, max: 60, label: '小成', severity: 'neutral' as const },
  { min: 61, max: 100, label: '高深', severity: 'neutral' as const },
];
function enter(archetypes: Archetype[], realm: number) {
  const world: ClientWorld = {
    schemaVersion: '0.4',
    mode: archetypes.length > 1 ? 'hybrid' : 'single',
    archetypes,
    world: { title: '验讫', background: '', dangerLevel: 'high', tone: '' },
    character: { attributes: {}, traits: [], inventory: [] },
    rules: [],
    state: { turn: 3, status: 'ongoing', timeline: '', logSummary: '', log: [] },
    endings: [],
  };
  useGameStore.setState({
    status: 'awaiting',
    world,
    saveId: 's-1',
    turn: 3,
    narrative: '钟声过后。',
    availableActions: [{ id: 'A', text: '入定', hint: '' }],
    attributeAxes: [
      { key: 'hp', displayName: '气血' },
      { key: 'realm', displayName: '境界', bands: REALM_BANDS },
    ],
    attributeValues: { hp: 80, realm },
  });
}

describe('接线 · 印只挂在《验讫》的境界印上', () => {
  afterEach(() => {
    useGameStore.getState().reset();
  });

  it('《验讫》:境界由初境跨进小成 → 印挂在 .sealAnchor 里,+1650ms 落下', () => {
    enter(['cultivation', 'cthulhu'], 20);
    const { container } = render(<GameScreen />);
    expect(container.querySelector('[class*="sealAnchor"]')).not.toBeNull();
    expect(container.querySelector('[data-seep="stamp"]')).toBeNull();
    act(() => useGameStore.setState({ turn: 4, attributeValues: { hp: 80, realm: 40 } }));
    expect(container.querySelector('[data-seep="stamp"]')).toBeNull();
    act(() => vi.advanceTimersByTime(1650));
    const el = container.querySelector('[class*="sealAnchor"] > [data-seep="stamp"]');
    expect(el?.textContent).toBe('验讫');
  });

  it.each([[['cultivation'] as Archetype[]], [['cultivation', 'rules_creepy'] as Archetype[]]])(
    '%j:同样跨档,没有挂点、没有印',
    (archetypes) => {
      enter(archetypes, 20);
      const { container } = render(<GameScreen />);
      act(() => useGameStore.setState({ turn: 4, attributeValues: { hp: 80, realm: 40 } }));
      act(() => vi.advanceTimersByTime(3000));
      expect(container.querySelector('[class*="sealHost"]')).toBeNull();
      expect(container.querySelector('[data-seep]')).toBeNull();
    },
  );
});
