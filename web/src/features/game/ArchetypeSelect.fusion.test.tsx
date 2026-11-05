// 融合入口刀 2c-1 的选择屏接线(ADR-019 订正):成品卡落位(选 A,插在宿主紧下方)、
// 「刚揉出」修饰类、聚焦时机、reduced-motion 分支,以及新生卡标题停驻的 CSS 衔接算式。
//
// 取证边界(同 hints.test):jsdom 没有布局,卡片位置逐张替死;动画观感看录屏,这里只钉
// 「卡插在哪、带什么类、什么时候聚焦、reduced 下走没走不移动的那条路」。
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import gsap from 'gsap';
import { useGameStore } from '../../state/gameStore';
import { ArchetypeSelect } from './ArchetypeSelect';
import { totalMs } from './fusion/crossfade';
import css from './game.module.css?raw';

const ORDER = ['rules_creepy', 'cthulhu', 'cultivation'] as const;
const NAMES: Record<(typeof ORDER)[number], string> = {
  rules_creepy: '规则怪谈',
  cthulhu: '克苏鲁',
  cultivation: '修仙',
};
const topOf = (id: string) => ORDER.indexOf(id as (typeof ORDER)[number]) * 100;

beforeEach(() => {
  localStorage.clear();
  useGameStore.setState({
    archetypes: ORDER.map((a) => ({ archetype: a, displayName: NAMES[a], tagline: null, vibeTag: null, active: true })),
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

const worldCard = (id: (typeof ORDER)[number]) => screen.getByRole('button', { name: new RegExp(`^${NAMES[id]}`) });

function setup() {
  vi.useFakeTimers();
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  render(<ArchetypeSelect />);
  for (const id of ORDER) {
    const top = topOf(id);
    worldCard(id).getBoundingClientRect = () =>
      ({ left: 16, top, width: 343, height: 92, right: 359, bottom: top + 92, x: 16, y: top, toJSON: () => ({}) }) as DOMRect;
  }
}

/** 把 from 拖到 to 上,停够、松手(提交)。 */
function fuse(from: (typeof ORDER)[number], to: (typeof ORDER)[number]) {
  fireEvent.pointerDown(worldCard(from), { pointerId: 1, clientX: 100, clientY: topOf(from) + 40 });
  act(() => void vi.advanceTimersByTime(200));
  const y = topOf(to) + 40;
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
  act(() => void vi.advanceTimersByTime(400));
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
  fireEvent.pointerUp(window, { pointerId: 1 });
}

/** 让揉合放行(jsdom 里 rAF 不推时间线,走的是兜底计时 —— 与后台标签页同一条路)。 */
const finish = () => act(() => void vi.advanceTimersByTime(totalMs(false) + 700));

/** 卡列里的卡名序列(融合卡用 aria-label 里的真名)。 */
function listOrder(): string[] {
  const list = worldCard('cultivation').parentElement!;
  return Array.from(list.children)
    .filter((el) => el.tagName === 'BUTTON')
    .map((el) => el.getAttribute('aria-label')?.replace('(融合世界)', '') ?? el.querySelector('h2')!.textContent!);
}

describe('成品卡落位(选 A)', () => {
  it('揉合一开始成品卡就插在宿主紧下方(最终位置),不追加在卡列末尾', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    expect(listOrder()).toEqual(['规则怪谈', '克苏鲁', '修仙', '验讫']);
    finish();
    expect(listOrder()).toEqual(['规则怪谈', '克苏鲁', '修仙', '验讫']);
  });

  it('同一宿主揉两次:新的那张紧贴宿主,旧的往下', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    finish();
    fuse('rules_creepy', 'cultivation');
    finish();
    expect(listOrder()).toEqual(['规则怪谈', '克苏鲁', '修仙', '识海遗蜕', '验讫']);
  });

  it('揉合期间手势停摆(手势锁 merging === null 不变):第二次拖拽不提交', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    fuse('rules_creepy', 'cultivation'); // 揉合还没演完
    expect(listOrder()).toEqual(['规则怪谈', '克苏鲁', '修仙', '验讫']);
  });
});

describe('新生卡', () => {
  it('聚焦在落位之后,不在插入那一刻(中途聚焦会按最终位置滚动,而画面上它还叠在宿主卡位上)', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    const card = screen.getByRole('button', { name: '验讫(融合世界)' });
    expect(document.activeElement).not.toBe(card);
    finish();
    expect(document.activeElement).toBe(card);
  });

  it('演完之后真卡上不留内联样式(宿主、被拖卡、成品卡)', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    finish();
    for (const el of [worldCard('cultivation'), worldCard('cthulhu'), screen.getByRole('button', { name: '验讫(融合世界)' })]) {
      expect(el.getAttribute('style') ?? '').toBe('');
    }
  });
});

describe('reduced-motion 分支', () => {
  function lastTimeline(): gsap.core.Timeline {
    const all = gsap.globalTimeline.getChildren(false, false, true);
    return all[all.length - 1] as gsap.core.Timeline;
  }

  it('走同一套三段交叉淡化,落位不移动(时间线时长 = 460 + 160,而不是 460 + 280)', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} }) as unknown as MediaQueryList,
    );
    setup();
    fuse('cthulhu', 'cultivation');
    expect(lastTimeline().duration()).toBeCloseTo(totalMs(true) / 1000, 3);
  });

  it('原速分支:时间线时长 = 460 + 280', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    expect(lastTimeline().duration()).toBeCloseTo(totalMs(false) / 1000, 3);
  });
});

describe('入场', () => {
  it('fusionSeep 已删(ADR-019 订正 §8):不再有融合卡的入场动画', () => {
    expect(css).not.toMatch(/@keyframes fusionSeep/);
    expect(css).toMatch(/\.card\.cardFusion,\s*\.card\.cardFusionRenfang,\s*\.card\.cardFusionYanqi \{\s*animation: none;/);
  });
});
