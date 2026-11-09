// 融合入口刀 2d 的选择屏接线(ADR-019 订正 2026-11-09:入口去动画):松手 → 被拖卡沿原有回位过渡归位,
// 成品卡直接追加在已开放卡之后、获得焦点、播报一句;没有揉合层、没有揉合期。另钉新生卡标题停驻的 CSS 衔接算式。
//
// 取证边界(同 hints.test):jsdom 没有布局,卡片位置逐张替死;滚进视野看浏览器实测,这里只钉
// 「卡挂在哪、带什么类、焦点在不在它上、被拖卡回没回位、有没有动画残留」。
import { StrictMode } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import gsap from 'gsap';
import { useGameStore } from '../../state/gameStore';
import { ArchetypeSelect } from './ArchetypeSelect';
import styles from './game.module.css';
import css from './game.module.css?raw';

const ORDER = ['rules_creepy', 'cthulhu', 'cultivation'] as const;
type Id = (typeof ORDER)[number];
const NAMES: Record<Id, string> = {
  rules_creepy: '规则怪谈',
  cthulhu: '克苏鲁',
  cultivation: '修仙',
};
const topOf = (id: string) => ORDER.indexOf(id as Id) * 100;

beforeEach(() => {
  localStorage.clear();
  useGameStore.setState({
    archetypes: [
      ...ORDER.map((a) => ({ archetype: a, displayName: NAMES[a], tagline: null, vibeTag: null, active: true })),
      // 一张未开放卡:成品卡要排在它之前(「已开放卡之后」= 刀 2c-1 之前的位置)。
      { archetype: 'life_sim', displayName: '人生模拟', tagline: null, vibeTag: null, active: false },
    ],
    // 同一宿主(修仙)的两条现役组合:《验讫》与《识海遗蜕》。
    fusions: [
      { host: 'cultivation', foreign: 'cthulhu', key: 'cultivation×cthulhu' },
      { host: 'cultivation', foreign: 'rules_creepy', key: 'cultivation×rules_creepy' },
    ],
    capabilities: { history: false },
    saves: [],
    saveNotice: null,
  });
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const worldCard = (id: Id) => screen.getByRole('button', { name: new RegExp(`^${NAMES[id]}`) });
const yanqi = () => screen.getByRole('button', { name: '验讫(融合世界)' });

function setup(strict = false) {
  vi.useFakeTimers();
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  render(strict ? <StrictMode><ArchetypeSelect /></StrictMode> : <ArchetypeSelect />);
  for (const id of ORDER) {
    const top = topOf(id);
    worldCard(id).getBoundingClientRect = () =>
      ({ left: 16, top, width: 343, height: 92, right: 359, bottom: top + 92, x: 16, y: top, toJSON: () => ({}) }) as DOMRect;
  }
}

/** 把 from 拖到 to 上,停够、松手(提交)。 */
function fuse(from: Id, to: Id) {
  fireEvent.pointerDown(worldCard(from), { pointerId: 1, clientX: 100, clientY: topOf(from) + 40 });
  act(() => void vi.advanceTimersByTime(200));
  const y = topOf(to) + 40;
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
  act(() => void vi.advanceTimersByTime(400));
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
  fireEvent.pointerUp(window, { pointerId: 1 });
}

/** 走完拖拽层的回位过渡(260ms)+ 清理。 */
const settle = () => act(() => void vi.advanceTimersByTime(400));

/** 卡列里的卡名序列(融合卡用 aria-label 里的真名;未开放卡是 div)。 */
function listOrder(): string[] {
  const list = worldCard('cultivation').parentElement!;
  return Array.from(list.children)
    .filter((el) => el.tagName === 'BUTTON' || el.getAttribute('aria-disabled') === 'true')
    .map((el) => el.getAttribute('aria-label')?.replace('(融合世界)', '') ?? el.querySelector('h2')!.textContent!);
}

describe('成品卡追加在已开放卡之后(订正 2026-11-09)', () => {
  it('松手即挂载,排在已开放卡之后、未开放卡之前', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    expect(listOrder()).toEqual(['规则怪谈', '克苏鲁', '修仙', '验讫', '人生模拟']);
  });

  it('揉出两张:按揉出先后排(先揉的在上);同一组合再揉一次不重复、不挪位置', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    settle();
    fuse('rules_creepy', 'cultivation');
    settle();
    expect(listOrder()).toEqual(['规则怪谈', '克苏鲁', '修仙', '验讫', '识海遗蜕', '人生模拟']);
    const card = yanqi();
    fuse('cthulhu', 'cultivation');
    settle();
    expect(listOrder()).toEqual(['规则怪谈', '克苏鲁', '修仙', '验讫', '识海遗蜕', '人生模拟']);
    expect(yanqi()).toBe(card); // 没有重新挂载
  });

  it('没有揉合期:松手后紧接着再拖一次,照常提交', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    settle();
    fuse('rules_creepy', 'cultivation');
    expect(listOrder()).toContain('识海遗蜕');
  });
});

describe('焦点与播报', () => {
  it('成品卡挂上即获得焦点(不等任何动画)', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    expect(document.activeElement).toBe(yanqi());
  });

  it('同一组合再揉一次:已有的那张重新获得焦点', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    settle();
    worldCard('rules_creepy').focus();
    fuse('cthulhu', 'cultivation');
    expect(document.activeElement).toBe(yanqi());
  });

  it('播报区念一句「融合成功。《真名》已加入世界列表。」', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    expect(screen.getByTestId('fusion-live').textContent).toBe('融合成功。《验讫》已加入世界列表。');
  });

  it('StrictMode(dev 构建的挂载方式)下同样只挂一张、获得焦点', () => {
    setup(true);
    fuse('cthulhu', 'cultivation');
    expect(screen.getAllByRole('button', { name: '验讫(融合世界)' })).toHaveLength(1);
    expect(document.activeElement).toBe(yanqi());
  });
});

describe('不演动画', () => {
  it('没有揉合层挂载:不起 GSAP 时间线、没有融合面 / 标志、卡列不标 aria-busy', () => {
    setup();
    const before = gsap.globalTimeline.getChildren(false, true, true).length;
    fuse('cthulhu', 'cultivation');
    expect(gsap.globalTimeline.getChildren(false, true, true).length).toBe(before);
    expect(document.querySelector('[data-fusion-emblem]')).toBeNull();
    expect(worldCard('cultivation').parentElement!.hasAttribute('aria-busy')).toBe(false);
  });

  it('被拖卡沿原有回位过渡(260ms)回到自己的卡位;走完后所有卡都没有内联样式残留', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    const f = worldCard('cthulhu');
    expect(f.style.transition).toContain('260ms'); // 起的是回位过渡,没有停在松手姿态
    expect(f.style.transform).not.toContain('scale(1.03)');
    settle();
    for (const id of ORDER) expect(worldCard(id).getAttribute('style') ?? '', id).toBe('');
    expect(yanqi().getAttribute('style')).toBeNull();
  });

  it('reduced-motion:被拖卡不起 260ms 回位过渡(只剩卡片自带的 .12s),成品卡照样挂上并获得焦点', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} }) as unknown as MediaQueryList,
    );
    setup();
    fuse('cthulhu', 'cultivation');
    expect(worldCard('cthulhu').style.transition).toBe('');
    expect(document.activeElement).toBe(yanqi());
  });

  it('松手后立刻单击任何卡都照常进入世界(不再有「揉合期间屏蔽单击」)', () => {
    const startGame = vi.fn(async () => {});
    useGameStore.setState({ startGame } as never);
    setup();
    fuse('cthulhu', 'cultivation');
    settle(); // 吞掉的只是被拖那张随后的那一次 click(手势层既有规则),与本条无关
    fireEvent.click(worldCard('rules_creepy'));
    expect(startGame).toHaveBeenCalledWith('rules_creepy');
    fireEvent.click(yanqi());
    expect(startGame).toHaveBeenLastCalledWith(['cultivation', 'cthulhu']);
  });

  it('成品卡带「刚揉出」修饰类', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    expect(yanqi().className).toContain(styles.cardFresh);
  });
});

// ── 「刚揉出」的纯 CSS 衔接算式(ADR-019 订正 §4;订正 2026-11-09 起算点 = 挂载那一刻)──────────
// 停驻结束那一刻,轮换必须正落在真名驻留段里,且三层取值与停驻最后一帧相同(衔接点不空帧、不跳)。
describe('刚揉出:停驻 → 轮换的衔接', () => {
  const CYCLE = 5.2;
  /** `.cardFresh { --fresh-text-at: <T>; }` 的 T(秒)。 */
  function startOf(): number {
    const m = css.match(/\.cardFresh \{\s*--fresh-text-at:\s*(\d+(?:\.\d+)?)(ms|s);/);
    expect(m).not.toBeNull();
    return Number(m![1]) / (m![2] === 'ms' ? 1000 : 1);
  }
  /** 把 `calc(var(--fresh-text-at) + 5.2s - 0.936s)` 这类时长在 T(秒)下求值。 */
  function evalTime(expr: string, T: number): number {
    const body = expr.replace(/^calc\((.*)\)$/, '$1').replace('var(--fresh-text-at)', `${T}s`);
    let sum = 0;
    for (const m of body.matchAll(/([+-]?)\s*(\d+(?:\.\d+)?)(ms|s)/g)) {
      const v = Number(m[2]) / (m[3] === 'ms' ? 1000 : 1);
      sum += m[1] === '-' ? -v : v;
    }
    return sum;
  }
  /** `.cardFresh .fusionTitleX { animation: <flicker> 5.2s steps(1, end) <delay> infinite, <hold> <dur> linear; }` */
  function freshRule(layer: 'A' | 'B' | 'C', T: number) {
    const m = css.match(new RegExp(`\\.cardFresh \\.fusionTitle${layer} \\{\\s*animation:([^;]+);`));
    expect(m, layer).not.toBeNull();
    const flicker = m![1].match(/(fusionFlicker\w)\s+5\.2s\s+steps\(1, end\)\s+(calc\([^)]*\)[^)]*\))\s+infinite/);
    const hold = m![1].match(/(fusionTitleHold\w+)\s+(calc\([^)]*\)[^)]*\))\s+linear/);
    expect(flicker, `${layer} flicker`).not.toBeNull();
    expect(hold, `${layer} hold`).not.toBeNull();
    return { flicker: flicker![1], delay: evalTime(flicker![2], T), hold: hold![1], holdDur: evalTime(hold![2], T) };
  }
  /** 关键帧在 pct 处的 opacity(steps(1,end) = 区间取左端值)。 */
  function opacityAt(name: string, pct: number): number {
    const body = css.match(new RegExp(`@keyframes ${name} \\{([\\s\\S]*?)\\n\\}`))![1];
    let val = NaN;
    let at = -1;
    for (const block of body.matchAll(/(from,\s*to|[\d%,\s]+)\s*\{\s*opacity:\s*([\d.]+);/g)) {
      const stops = block[1].includes('from') ? [0] : [...block[1].matchAll(/(\d+(?:\.\d+)?)%/g)].map((x) => +x[1]);
      for (const st of stops) if (st <= pct && st >= at) [at, val] = [st, +block[2]];
    }
    return val;
  }

  it('起算点 = 挂载那一刻(--fresh-text-at = 0);卡上不再写内联起算变量', () => {
    expect(startOf()).toBe(0);
    setup();
    fuse('cthulhu', 'cultivation');
    expect(yanqi().style.getPropertyValue('--fresh-text-at')).toBe('');
  });

  it('停驻时长 = 一个循环 5.2s', () => {
    for (const l of ['A', 'B', 'C'] as const) expect(freshRule(l, startOf()).holdDur).toBeCloseTo(CYCLE, 6);
  });

  it('停驻结束那一刻轮换走到局部 18%(真名驻留段 16%–55% 内);三层取值与停驻相同', () => {
    const expected = { A: 0, B: 0, C: 1 };
    for (const l of ['A', 'B', 'C'] as const) {
      const r = freshRule(l, startOf());
      expect((r.holdDur - r.delay) / CYCLE).toBeCloseTo(0.18, 6);
      expect(r.flicker).toBe(`fusionFlicker${l}`);
      expect(r.hold).toBe(l === 'C' ? 'fusionTitleHoldOn' : 'fusionTitleHoldOff');
      expect(opacityAt(r.flicker, 18)).toBe(expected[l]); // 轮换接手的第一帧
      expect(opacityAt(r.hold, 100)).toBe(expected[l]); // 停驻的最后一帧
    }
  });

  it('reduced-motion 下这一层整个关掉(按同一权重 .cardFresh .fusionTitleX 再关一次)', () => {
    const reduced = css.slice(css.lastIndexOf('.fusionTitle > .fusionTitleC {'));
    expect(reduced).toMatch(/\.cardFresh \.fusionTitleA,\s*\.cardFresh \.fusionTitleB,\s*\.cardFresh \.fusionTitleC \{\s*animation: none;/);
  });

  it('融合卡没有入场动画:fusionSeep 已删,三张融合卡 animation: none', () => {
    expect(css).not.toMatch(/@keyframes fusionSeep/);
    expect(css).toMatch(/\.card\.cardFusion,\s*\.card\.cardFusionRenfang,\s*\.card\.cardFusionYanqi \{\s*animation: none;/);
  });
});
