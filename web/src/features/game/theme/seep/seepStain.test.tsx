import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ClientWorld } from '../../../../api';
import type { Archetype } from '../../../../types/schema';
import { useGameStore } from '../../../../state/gameStore';
import { GameScreen } from '../../GameScreen';
import { SEEPS } from './registry';
import { SeepStain } from './SeepStain';
import { cornerStates, measureHoles } from './stainGeometry';

// 墨洇(ADR-043 刀 3)。jsdom 不排版:几何靠打桩(层框 + 文本 Range 的行框),
// 这里钉的是**接线与规则**(哪档哪几个角、挖空从哪里来、首帧不过渡、只在《验讫》出现);
// 逐像素「墨不压字」的实测在真浏览器里做(ADR-043 实施记录)。

const stain = SEEPS.yanqi!.stain!;

describe('cornerStates · 哪档哪几个角', () => {
  const on = (level: number, surface: 'stats' | 'prose') =>
    cornerStates(stain, level, surface)
      .filter((c) => c.on)
      .map((c) => `${c.corner} ${c.w}×${c.h}`);

  it('未觉:只有数值区左下;正文一角不显', () => {
    expect(on(0, 'stats')).toEqual(['bl 64×56']);
    expect(on(0, 'prose')).toEqual([]);
  });
  it('见格:+ 数值区右下 + 正文左上', () => {
    expect(on(1, 'stats')).toEqual(['bl 64×56', 'br 52×46']);
    expect(on(1, 'prose')).toEqual(['tl 56×48']);
  });
  it('知渔:+ 正文右下,且全部变大', () => {
    expect(on(2, 'stats')).toEqual(['bl 96×84', 'br 76×66']);
    expect(on(2, 'prose')).toEqual(['tl 72×62', 'br 84×70']);
  });
  it('不显的角留在 DOM 里、透明,尺寸取最近登记它的那一档(淡入时从对的尺寸开始)', () => {
    const p0 = cornerStates(stain, 0, 'prose');
    expect(p0.map((c) => [c.corner, c.w, c.h, c.on])).toEqual([
      ['tl', 56, 48, false],
      ['br', 84, 70, false],
    ]);
    // 知渔跌回见格:正文右下仍在 DOM 里(淡出用),尺寸保持知渔那档
    const p1 = cornerStates(stain, 1, 'prose');
    expect(p1.find((c) => c.corner === 'br')).toMatchObject({ w: 84, h: 70, on: false });
  });
  it('超出最后一档按最后一档', () => {
    expect(cornerStates(stain, 7, 'prose').every((c) => c.on)).toBe(true);
  });
});

// ── 几何打桩 ──────────────────────────────────────────────────────────
const LAYER = { left: 10, top: 20, right: 310, bottom: 120, width: 300, height: 100 };
let lineRects: DOMRect[] = [];
const rect = (left: number, top: number, w: number, h: number) =>
  ({ left, top, right: left + w, bottom: top + h, width: w, height: h, x: left, y: top }) as DOMRect;

beforeEach(() => {
  lineRects = [];
  vi.spyOn(SVGElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: SVGElement) {
    return (this.getAttribute('data-seep') === 'stain' ? rect(LAYER.left, LAYER.top, LAYER.width, LAYER.height) : rect(0, 0, 0, 0));
  });
  (Range.prototype as unknown as { getClientRects: () => DOMRect[] }).getClientRects = () => lineRects;
});
afterEach(() => {
  vi.restoreAllMocks();
  delete (Range.prototype as unknown as { getClientRects?: unknown }).getClientRects;
});

describe('measureHoles · 挖空来自承载框里的文字行框', () => {
  it('行框换算到层坐标、外扩 pad、向外取整;空白文本与渗入层自己的节点不算', () => {
    const host = document.createElement('div');
    host.innerHTML = '<span>气血</span>  <i data-seep="stamp">验讫</i>';
    const layer = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    layer.setAttribute('data-seep', 'stain');
    host.appendChild(layer);
    lineRects = [rect(30.4, 50.6, 20, 10)];
    const g = measureHoles(layer, 2)!;
    expect(g.w).toBe(300);
    expect(g.h).toBe(100);
    // 只有「气血」一个文本节点被量到(空白跳过、[data-seep] 跳过)→ 一块
    expect(g.holes).toEqual([{ x: 18, y: 28, w: 25, h: 15 }]);
  });

  it('层没有尺寸(未排版 / 隐藏)→ null', () => {
    const host = document.createElement('div');
    const layer = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    host.appendChild(layer); // 无 data-seep → 打桩返回 0 尺寸
    expect(measureHoles(layer, 2)).toBeNull();
  });
});

describe('SeepStain · 渲染', () => {
  function mount(level: number | null) {
    lineRects = [rect(40, 30, 50, 12)];
    return render(
      <div>
        <span>窥天</span>
        <SeepStain stain={stain} level={level} surface="stats" />
      </div>,
    );
  }

  it('无档(null)→ 什么都不画', () => {
    const { container } = mount(null);
    expect(container.querySelector('[data-seep]')).toBeNull();
  });

  it('挂载即带着挖空画出来;mask 里一块白底 + 每个行框一块黑', () => {
    const { container } = mount(1);
    const layer = container.querySelector('[data-seep="stain"]')!;
    expect(layer.getAttribute('aria-hidden')).toBe('true');
    const rects = [...layer.querySelectorAll('mask rect')];
    expect(rects.map((r) => r.getAttribute('fill'))).toEqual(['#fff', '#000']);
    expect(rects[1].getAttribute('x')).toBe('27'); // 40 - 10 - 3(外扩 3px,裁定 5)
    expect(rects[1].getAttribute('width')).toBe('56'); // 50 + 3 + 3
    expect([rects[1].getAttribute('rx'), rects[1].getAttribute('ry')]).toEqual(['3', '3']); // 圆角 3px
    expect(rects[0].getAttribute('rx')).toBeNull(); // 白底不圆角
    // 墨渍挂在 mask 下
    const g = layer.querySelector('g[mask]')!;
    expect(g.getAttribute('mask')).toMatch(/^url\(#seep-mask-/);
    expect([...g.querySelectorAll('[data-corner]')].map((n) => `${n.getAttribute('data-corner')}:${n.getAttribute('data-on')}`)).toEqual([
      'stats-bl:1',
      'stats-br:1',
    ]);
  });

  it('首帧不过渡(still),两帧后放开;档变时不再回到 still', async () => {
    vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame'] });
    try {
      const { container, rerender } = mount(0);
      const layer = () => container.querySelector('[data-seep="stain"]')!;
      expect(layer().getAttribute('class')).toMatch(/still/);
      await act(async () => {
        vi.advanceTimersToNextFrame();
        vi.advanceTimersToNextFrame();
      });
      expect(layer().getAttribute('class')).not.toMatch(/still/);
      rerender(
        <div>
          <span>窥天</span>
          <SeepStain stain={stain} level={2} surface="stats" />
        </div>,
      );
      expect(layer().getAttribute('class')).not.toMatch(/still/);
      expect(layer().getAttribute('style')).toContain('--fo: 0.21');
    } finally {
      vi.useRealTimers();
    }
  });

  it('角的位置在外层 translate(按层尺寸贴角),尺寸在内层 scale(会过渡)', () => {
    const { container } = mount(1);
    const br = container.querySelector('[data-corner="stats-br"]') as SVGGElement;
    expect(br.parentElement!.getAttribute('transform')).toBe('translate(300 100)');
    expect(br.style.transform).toBe('scale(-0.52, 0.46) translate(0px, -100px)');
  });
});

// ── 接线:只在《验讫》出现 ────────────────────────────────────────────
const KNOW_BANDS = [
  { min: 0, max: 30, label: '未觉', severity: 'neutral' as const },
  { min: 31, max: 60, label: '见格', severity: 'neutral' as const },
  { min: 61, max: 100, label: '知渔', severity: 'neutral' as const },
];
function enter(archetypes: Archetype[]) {
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
    narrative: '钟声过后,满门都在为你高兴。',
    availableActions: [{ id: 'A', text: '入定', hint: '' }],
    attributeAxes: [
      { key: 'hp', displayName: '气血' },
      { key: 'realm', displayName: '境界' },
      { key: 'knowledge', displayName: '窥天', bands: KNOW_BANDS },
    ],
    attributeValues: { hp: 80, realm: 20, knowledge: 41 },
  });
}

describe('接线 · 只在登记了的组合出现', () => {
  afterEach(() => {
    useGameStore.getState().reset();
  });

  it('《验讫》:数值区与正文各一层墨渍,数值区外包一层定位框', () => {
    enter(['cultivation', 'cthulhu']);
    const { container } = render(<GameScreen />);
    expect(container.querySelectorAll('[data-seep="stain"]')).toHaveLength(2);
    const statsLayer = container.querySelector('[class*="statsHost"] > [data-seep="stain"]');
    expect(statsLayer).not.toBeNull();
    expect(container.querySelector('[class*="prose"] > [data-seep="stain"]')).not.toBeNull();
  });

  it.each([
    [['cultivation'] as Archetype[]],
    [['cultivation', 'rules_creepy'] as Archetype[]],
    [['rules_creepy', 'apocalypse'] as Archetype[]],
    [['cthulhu'] as Archetype[]],
  ])('%j:没有墨渍层、没有定位框', (archetypes) => {
    enter(archetypes);
    const { container } = render(<GameScreen />);
    expect(container.querySelector('[data-seep]')).toBeNull();
    expect(container.querySelector('[class*="statsHost"]')).toBeNull();
  });
});
