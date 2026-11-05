// 融合入口刀 2c-1 的选择屏接线(ADR-019 订正):成品卡落位(选 A,插在宿主紧下方)、
// 「刚揉出」修饰类、聚焦时机、reduced-motion 分支,以及新生卡标题停驻的 CSS 衔接算式。
//
// 取证边界(同 hints.test):jsdom 没有布局,卡片位置逐张替死;动画观感看录屏,这里只钉
// 「卡插在哪、带什么类、什么时候聚焦、reduced 下走没走不移动的那条路」。
import { StrictMode } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import gsap from 'gsap';
import { useGameStore } from '../../state/gameStore';
import { ArchetypeSelect } from './ArchetypeSelect';
import { totalMs } from './fusion/crossfade';
import { EMBLEM_BEATS, EMBLEM_TOTAL_MS } from './fusion/plan';
import styles from './game.module.css';
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
function fuse(from: (typeof ORDER)[number], to: (typeof ORDER)[number]) {
  fireEvent.pointerDown(worldCard(from), { pointerId: 1, clientX: 100, clientY: topOf(from) + 40 });
  act(() => void vi.advanceTimersByTime(200));
  const y = topOf(to) + 40;
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
  act(() => void vi.advanceTimersByTime(400));
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
  fireEvent.pointerUp(window, { pointerId: 1 });
}

/** 让揉合放行(jsdom 里 rAF 不推时间线,走的是兜底计时 —— 与后台标签页同一条路)。按最长的标志路径等。 */
const finish = () => act(() => void vi.advanceTimersByTime(EMBLEM_TOTAL_MS + 700));

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

describe('StrictMode(dev 构建的真实挂载方式)', () => {
  it('松手后被拖卡仍停在松手姿态上退场,不跳回自己的卡位', () => {
    setup(true);
    fuse('cthulhu', 'cultivation');
    const f = worldCard('cthulhu');
    expect(f.style.transform).not.toBe('');
    expect(f.style.zIndex).toBe('30');
  });
});

describe('新生卡', () => {
  it('带「刚揉出」修饰类', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    const card = screen.getByRole('button', { name: '验讫(融合世界)' });
    expect(card.className).toContain(styles.cardFresh);
  });

  it('聚焦在落位之后,不在插入那一刻(中途聚焦会按最终位置滚动,而画面上它还叠在宿主卡位上)', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    const card = screen.getByRole('button', { name: '验讫(融合世界)' });
    expect(document.activeElement).not.toBe(card);
    finish();
    expect(document.activeElement).toBe(card);
  });

  it('演完之后真卡上不留揉合写的内联样式(宿主、被拖卡、其余卡;成品卡只留停驻起算变量)', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    finish();
    for (const el of [worldCard('cultivation'), worldCard('cthulhu'), worldCard('rules_creepy')]) {
      expect(el.getAttribute('style') ?? '').toBe('');
    }
    const card = screen.getByRole('button', { name: '验讫(融合世界)' });
    expect(card.getAttribute('style')).toBe(`--fresh-text-at: ${EMBLEM_BEATS.text[0]}ms;`);
    expect(card.hasAttribute('data-emblem-reveal')).toBe(false);
    expect(document.querySelector('[data-fusion-emblem]')).toBeNull();
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

  it('reduced-motion 下有登记的组合也不出标志', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} }) as unknown as MediaQueryList,
    );
    setup();
    fuse('cthulhu', 'cultivation');
    expect(document.querySelector('[data-fusion-emblem]')).toBeNull();
  });
});

describe('有登记 / 没登记两条分支(ADR-019 订正 §2)', () => {
  function lastTimeline(): gsap.core.Timeline {
    const all = gsap.globalTimeline.getChildren(false, false, true);
    return all[all.length - 1] as gsap.core.Timeline;
  }

  it('有登记(《验讫》):走标志路径 —— 融合面挂进卡列,时间线 4.5 秒(落位在 4500 收住)', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    const overlay = document.querySelector('[data-fusion-emblem]');
    expect(overlay).not.toBeNull();
    expect(overlay!.parentElement).toBe(worldCard('cultivation').parentElement);
    expect(lastTimeline().duration()).toBeCloseTo(EMBLEM_TOTAL_MS / 1000, 3);
  });

  it('没登记(《识海遗蜕》):三段交叉淡化 —— 不出融合面,时间线 = 460 + 280', () => {
    setup();
    fuse('rules_creepy', 'cultivation');
    expect(document.querySelector('[data-fusion-emblem]')).toBeNull();
    expect(lastTimeline().duration()).toBeCloseTo(totalMs(false) / 1000, 3);
  });
});

describe('揉合期间屏蔽单击(Felix 2026-11-05 裁定)', () => {
  function stubStart() {
    const startGame = vi.fn(async () => {});
    useGameStore.setState({ startGame } as never);
    return startGame;
  }

  it('揉合进行中:单击其他卡、单击参与的两张卡,都不进入世界;卡列标 aria-busy', () => {
    const startGame = stubStart();
    setup();
    fuse('cthulhu', 'cultivation');
    const list = worldCard('cultivation').parentElement!;
    expect(list.getAttribute('aria-busy')).toBe('true');
    fireEvent.click(worldCard('rules_creepy'));
    fireEvent.click(worldCard('cultivation'));
    fireEvent.click(worldCard('cthulhu'));
    expect(startGame).not.toHaveBeenCalled();
  });

  it('揉合进行中:已有的融合卡(含刚揉出的那张)单击也不进入世界', () => {
    const startGame = stubStart();
    setup();
    fuse('rules_creepy', 'cultivation');
    finish();
    fuse('cthulhu', 'cultivation');
    fireEvent.click(screen.getByRole('button', { name: '识海遗蜕(融合世界)' }));
    fireEvent.click(screen.getByRole('button', { name: '验讫(融合世界)' }));
    expect(startGame).not.toHaveBeenCalled();
  });

  // user-event 与假时钟同用会挂住(它内部靠真 setTimeout 排键序):拖拽 / 揉合在假时钟下起好之后切回真时钟再按键。
  it('键盘回车也不进入世界(按钮上回车 = 一次 click,同一道闸)', async () => {
    const startGame = stubStart();
    setup();
    fuse('cthulhu', 'cultivation');
    vi.useRealTimers();
    const user = userEvent.setup();
    worldCard('rules_creepy').focus();
    await user.keyboard('{Enter}');
    expect(startGame).not.toHaveBeenCalled();
  });

  it('对照:没有揉合时,同一个回车照常进入世界(证明回车确实走到了这道闸)', async () => {
    const startGame = stubStart();
    render(<ArchetypeSelect />);
    const user = userEvent.setup();
    worldCard('rules_creepy').focus();
    await user.keyboard('{Enter}');
    expect(startGame).toHaveBeenCalledWith('rules_creepy');
  });

  it('演完(兜底放行)立即恢复:aria-busy 撤掉,单击照常进入世界;外观不变(class 与揉合前相同)', () => {
    const startGame = stubStart();
    setup();
    const before = worldCard('rules_creepy').className;
    fuse('cthulhu', 'cultivation');
    expect(worldCard('rules_creepy').className).toBe(before);
    finish();
    const list = worldCard('cultivation').parentElement!;
    expect(list.hasAttribute('aria-busy')).toBe(false);
    fireEvent.click(worldCard('rules_creepy'));
    expect(startGame).toHaveBeenCalledWith('rules_creepy');
  });
});

// ── 「刚揉出」的纯 CSS 衔接算式(ADR-019 订正 §4;刀 2c-2 改为从文字出现起算)──────────
// 停驻结束那一刻,轮换必须正落在真名驻留段里,且三层取值与停驻最后一帧相同(衔接点不空帧、不跳)。
// 起算点 T = 卡上的 --fresh-text-at(揉合路径给出的「文字开始浮现」时刻);两条路径都要成立。
describe('刚揉出:停驻 → 轮换的衔接', () => {
  const CYCLE = 5.2;
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
  /** 两条揉合路径的文字起点(秒)+ 不演(0)。 */
  const STARTS = { 标志: EMBLEM_BEATS.text[0] / 1000, 交叉淡化: 0.32, 不演: 0 };

  it('停驻时长 = 文字浮现的起点 + 一个循环 5.2s(停满一个循环从文字出现算),两条路径都成立', () => {
    for (const T of Object.values(STARTS)) {
      for (const l of ['A', 'B', 'C'] as const) expect(freshRule(l, T).holdDur).toBeCloseTo(T + CYCLE, 6);
    }
  });

  it('停驻结束那一刻轮换走到局部 18%(真名驻留段 16%–55% 内);三层取值与停驻相同 —— 与起点无关', () => {
    const expected = { A: 0, B: 0, C: 1 };
    for (const T of Object.values(STARTS)) {
      for (const l of ['A', 'B', 'C'] as const) {
        const r = freshRule(l, T);
        const local = r.holdDur - r.delay;
        expect(local / CYCLE).toBeCloseTo(0.18, 6);
        expect(r.flicker).toBe(`fusionFlicker${l}`);
        expect(r.hold).toBe(l === 'C' ? 'fusionTitleHoldOn' : 'fusionTitleHoldOff');
        expect(opacityAt(r.flicker, 18)).toBe(expected[l]); // 轮换接手的第一帧
        expect(opacityAt(r.hold, 100)).toBe(expected[l]); // 停驻的最后一帧
      }
    }
  });

  it('起算点由揉合路径写在卡上:标志路径 3150ms / 交叉淡化 320ms', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    finish();
    fuse('rules_creepy', 'cultivation');
    const yanqi = screen.getByRole('button', { name: '验讫(融合世界)' });
    const shihai = screen.getByRole('button', { name: '识海遗蜕(融合世界)' });
    expect(yanqi.style.getPropertyValue('--fresh-text-at')).toBe(`${EMBLEM_BEATS.text[0]}ms`);
    expect(shihai.style.getPropertyValue('--fresh-text-at')).toBe('320ms');
  });

  it('reduced-motion 下《验讫》走交叉淡化,起算点也随之是 320ms', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} }) as unknown as MediaQueryList,
    );
    setup();
    fuse('cthulhu', 'cultivation');
    expect(screen.getByRole('button', { name: '验讫(融合世界)' }).style.getPropertyValue('--fresh-text-at')).toBe('320ms');
  });

  it('同一组合再揉一次:卡不重新挂载,起算点不改(停驻早已走完,改了会把它重新唤醒)', () => {
    setup();
    fuse('cthulhu', 'cultivation');
    finish();
    const card = screen.getByRole('button', { name: '验讫(融合世界)' });
    fuse('cthulhu', 'cultivation');
    expect(screen.getByRole('button', { name: '验讫(融合世界)' })).toBe(card);
    expect(card.style.getPropertyValue('--fresh-text-at')).toBe(`${EMBLEM_BEATS.text[0]}ms`);
  });

  it('reduced-motion 下这一层整个关掉(按同一权重 .cardFresh .fusionTitleX 再关一次)', () => {
    const reduced = css.slice(css.lastIndexOf('.fusionTitle > .fusionTitleC {'));
    expect(reduced).toMatch(/\.cardFresh \.fusionTitleA,\s*\.cardFresh \.fusionTitleB,\s*\.cardFresh \.fusionTitleC \{\s*animation: none;/);
  });

  it('fusionSeep 已删(ADR-019 订正 §8):不再有融合卡的入场动画', () => {
    expect(css).not.toMatch(/@keyframes fusionSeep/);
    expect(css).toMatch(/\.card\.cardFusion,\s*\.card\.cardFusionRenfang,\s*\.card\.cardFusionYanqi \{\s*animation: none;/);
  });
});
