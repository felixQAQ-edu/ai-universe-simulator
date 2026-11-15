import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AttributeAxisMeta, AxisBand, ClientAction } from '../../../../api';
import { StatsPanel } from '../../StatsPanel';
import { SkinContext, type AxisView, type StatsTimeline } from '../contract';
import { SkinRuntime } from '../lifecycle';
import { resolveWorldTheme } from '../registry';
import { SKINS } from '../skins';
import { XunActions } from './XunActions';
import {
  BAND_MS,
  BAR_GAP_MS,
  BAR_STAGGER_MS,
  COUNT_FADE_MS,
  COUNT_MS,
  EASE_OUT_CUBIC,
  ENTER_MS,
  GHOST_FADE_MS,
  GHOST_HOLD_MS,
  KEPT_DELAY_MS,
  KEPT_MS,
  SHORT_TICK,
  TICK_MS,
} from './timing';
import { XunStats } from './XunStats';
import { XUN_SONG_CHARS } from './subset';
import { justMarked, tickOpacity, ticksFor } from './ticks';
import styles from './xun.module.css';

// 《寻常》局内皮肤(ADR-020「《寻常》局内皮肤 + 记忆点『留下的那一年』」一节)的测试。口径同 animalSkin / qiriSkin:
//   ① 登记与共享语义;② 形态冒烟;③ 动效的时序与收尾(用一个假的 element.animate 记下每次调用)。守行为,不守像素。

const skin = SKINS.life_sim!;

// 档表照 ArchetypeRegistry 现行派生(下发形态:显式区间 + severity):只有气力(致命消耗轴)会进 caution / danger。
const VIGOR: AxisBand[] = [
  { min: 0, max: 20, label: '衰弱', severity: 'danger' },
  { min: 21, max: 50, label: '耗损', severity: 'caution' },
  { min: 51, max: 100, label: '康健', severity: 'neutral' },
];
const LONGING: AxisBand[] = [
  { min: 0, max: 20, label: '熄了', severity: 'neutral' },
  { min: 21, max: 50, label: '转淡', severity: 'neutral' },
  { min: 51, max: 100, label: '炽热', severity: 'neutral' },
];
const CROSSROADS: AxisBand[] = [
  { min: 0, max: 20, label: '只剩一条', severity: 'neutral' },
  { min: 21, max: 50, label: '路已收窄', severity: 'neutral' },
  { min: 51, max: 100, label: '岔口尚多', severity: 'neutral' },
];
const TIES: AxisBand[] = [
  { min: 0, max: 30, label: '轻身', severity: 'neutral' },
  { min: 31, max: 60, label: '有所系', severity: 'neutral' },
  { min: 61, max: 100, label: '深系', severity: 'neutral' },
];
const AXES: AttributeAxisMeta[] = [
  { key: 'vigor', displayName: '气力', bands: VIGOR },
  { key: 'longing', displayName: '热望', bands: LONGING },
  { key: 'crossroads', displayName: '路口', bands: CROSSROADS },
  { key: 'ties', displayName: '牵挂', bands: TIES },
];
const VALUES = { vigor: 46, longing: 72, crossroads: 35, ties: 33 };

/** 手搭一根 AxisView(直接渲染 XunStats 时用;不经通用层,数值就是这一帧的显示值)。 */
function view(value: number, label: string | null, severity: AxisView['severity'] = 'neutral'): AxisView {
  return {
    key: 'vigor',
    displayName: '气力',
    value,
    percent: value,
    bandLabel: label,
    severity,
    a11yText: `气力 · ${value}`,
    signature: false,
  };
}

// ── 假的 element.animate:记下元素、关键帧、参数;cancel / finish 触发对应回调 ───────────────
interface FakeAnim {
  el: Element;
  keyframes: Keyframe[];
  options: KeyframeAnimationOptions;
  playState: AnimationPlayState;
  onfinish: (() => void) | null;
  oncancel: (() => void) | null;
  cancel: () => void;
  finish: () => void;
}
let anims: FakeAnim[] = [];
function stubAnimate() {
  anims = [];
  Object.defineProperty(Element.prototype, 'animate', {
    configurable: true,
    writable: true,
    value(this: Element, keyframes: Keyframe[], options: KeyframeAnimationOptions) {
      const a: FakeAnim = {
        el: this,
        keyframes,
        options,
        playState: 'running',
        onfinish: null,
        oncancel: null,
        cancel() {
          if (a.playState === 'idle') return;
          a.playState = 'idle';
          a.oncancel?.();
        },
        finish() {
          a.playState = 'finished';
          a.onfinish?.();
        },
      };
      anims.push(a);
      return a;
    },
  });
}
function unstubAnimate() {
  delete (Element.prototype as { animate?: unknown }).animate;
}

const ticksOf = (c: HTMLElement) => [...c.querySelectorAll<HTMLElement>('[data-tick]')];
const rowsOf = (c: HTMLElement) => [...c.querySelectorAll<HTMLElement>('[data-word]')];
const bandText = (c: HTMLElement) => c.querySelector<HTMLElement>('[data-band-text]')!.textContent;

function Panel({ timeline, values = VALUES, runtime }: { timeline: StatsTimeline; values?: Record<string, number>; runtime: SkinRuntime }) {
  return (
    <SkinContext.Provider value={{ skin, runtime }}>
      <StatsPanel axes={AXES} values={values} timeline={timeline} />
    </SkinContext.Provider>
  );
}

afterEach(() => {
  unstubAnimate();
  vi.restoreAllMocks();
});

describe('登记', () => {
  it('life_sim 登记了皮肤:不占开场、不挂签名轴、要回合数、顶图只有静态滤镜', () => {
    expect(resolveWorldTheme('life_sim').skin).toBe('life_sim');
    expect(skin.id).toBe('life_sim');
    expect(skin.hasIntro).toBe(false);
    expect(skin.signatureAxisKey).toBeUndefined();
    expect(skin.statsTimeline).toBe(true);
    expect(skin.valueRoll).toEqual({ durationMs: 700, ease: 'power2.out' });
    expect(skin.bannerImgClass).toBe(styles.bannerImg);
    expect(skin.pausedClass).toBe('');
    expect(skin.backPressDurMs).toBeUndefined();
    expect(skin.Interlude).toBeUndefined();
    expect(skin.Prose).toBeUndefined();
  });

  it('其余七套皮肤都没有声明 statsTimeline', () => {
    const declared = Object.values(SKINS).filter((s) => s!.statsTimeline).map((s) => s!.id);
    expect(declared).toEqual(['life_sim']);
  });
});

describe('刻痕(ticks.ts)', () => {
  it('不透明度:最近三道实的,往前每道 −0.07,地板 0.22', () => {
    expect([0, 1, 2].map(tickOpacity)).toEqual([1, 1, 1]);
    expect(tickOpacity(3)).toBe(0.93);
    expect(tickOpacity(4)).toBe(0.86);
    expect(tickOpacity(13)).toBe(0.23);
    expect(tickOpacity(14)).toBe(0.22);
    expect(tickOpacity(200)).toBe(0.22);
  });

  it('第 i 道 = 第 i + 1 回合;标记过的那道 kept 且恒为 1;turn 0 一道都没有', () => {
    expect(ticksFor({ turn: 0, markedTurns: [] })).toEqual([]);
    const t = ticksFor({ turn: 6, markedTurns: [1, 4] });
    expect(t.map((x) => x.turn)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(t.map((x) => x.kept)).toEqual([true, false, false, true, false, false]);
    expect(t[0].opacity).toBe(1); // 最早的一道,但标记过 → 不变淡
    expect(t[1].opacity).toBe(0.86); // age 4
    expect(t[5].opacity).toBe(1);
  });

  it('justMarked:回合往前走了,且清单多出了刚落地的那一回合;没有上一次一律 false', () => {
    expect(justMarked(null, { turn: 5, markedTurns: [5] })).toBe(false);
    expect(justMarked({ turn: 4, markedTurns: [] }, { turn: 5, markedTurns: [5] })).toBe(true);
    expect(justMarked({ turn: 4, markedTurns: [2] }, { turn: 5, markedTurns: [2] })).toBe(false);
    expect(justMarked({ turn: 5, markedTurns: [] }, { turn: 5, markedTurns: [5] })).toBe(false);
    // 409 后经 /state 补上来的那一回合(审定 11):回合也往前走了 → 照播。
    expect(justMarked({ turn: 4, markedTurns: [2] }, { turn: 5, markedTurns: [2, 5] })).toBe(true);
  });
});

describe('形态', () => {
  it('四行(按 axes 顺序,整行一个 aria-label)+ 回合数那么多道刻痕,标记过的那几道是长的', () => {
    const { container } = render(<Panel timeline={{ turn: 7, markedTurns: [3, 6] }} runtime={new SkinRuntime()} />);
    const rows = rowsOf(container);
    expect(rows.map((r) => r.getAttribute('aria-label'))).toEqual([
      '气力 · 46 · 耗损 · 注意',
      '热望 · 72 · 炽热 · 正常',
      '路口 · 35 · 路已收窄 · 正常',
      '牵挂 · 33 · 有所系 · 正常',
    ]);
    expect(rows.every((r) => r.getAttribute('role') === 'img')).toBe(true);
    const ticks = ticksOf(container);
    expect(ticks).toHaveLength(7);
    expect(ticks.map((t) => t.dataset.kept)).toEqual(['false', 'false', 'true', 'false', 'false', 'true', 'false']);
    expect(ticks[2].className).toContain(styles.kept);
    expect(ticks[0].style.opacity).toBe('0.72'); // age 6 → 1 − 4 × 0.07
    expect(ticks[0].style.getPropertyValue('--i')).toBe('0');
    expect(ticks[6].style.getPropertyValue('--i')).toBe('6');
    expect(container.querySelector<HTMLElement>(`.${styles.years}`)!.style.getPropertyValue('--n')).toBe('7');
    expect(container.querySelector(`.${styles.years}`)!.getAttribute('aria-hidden')).toBe('true');
  });

  it('数字、档名、实线长度来自通用层的显示值;实线写 transform,不写 width', () => {
    const { container } = render(<Panel timeline={{ turn: 0, markedTurns: [] }} runtime={new SkinRuntime()} />);
    const first = rowsOf(container)[0];
    expect(first.querySelector(`.${styles.wNum}`)!.textContent).toBe('46');
    expect(bandText(first)).toBe('耗损');
    const fill = first.querySelector<HTMLElement>('[data-fill]')!;
    expect(fill.style.transform).toBe('scaleX(0.46)');
    expect(fill.style.width).toBe('');
    expect(ticksOf(container)).toHaveLength(0);
  });

  it('severity 只读 axis.severity:danger / caution 各挂自己的 class,neutral 与 null 都不挂', () => {
    // 减弱动效:通用层不插值,换值同帧落位(否则第二次渲染时数字还在滚)。
    vi.spyOn(window, 'matchMedia').mockImplementation((q: string) => ({ matches: true, media: q }) as MediaQueryList);
    const { container, rerender } = render(<Panel timeline={{ turn: 1, markedTurns: [] }} values={{ ...VALUES, vigor: 12 }} runtime={new SkinRuntime()} />);
    const [vigor, longing] = rowsOf(container);
    expect(vigor.className).toContain(styles.danger);
    expect(longing.className).not.toContain(styles.danger);
    expect(longing.className).not.toContain(styles.caution);
    rerender(<Panel timeline={{ turn: 1, markedTurns: [] }} values={{ ...VALUES, vigor: 46 }} runtime={new SkinRuntime()} />);
    expect(rowsOf(container)[0].className).toContain(styles.caution);
    // 没有档表 → severity null → 什么都不挂。
    render(
      <XunStats axes={[view(50, null, null)]} runtime={new SkinRuntime()} rootRef={{ current: null }} />,
    );
    const lone = rowsOf(document.body).at(-1)!;
    expect(lone.className).toBe(styles.word);
  });

  it('没有 timeline(不经通用层直接渲染)→ 不画刻痕、不报错', () => {
    const { container } = render(
      <XunStats axes={[view(50, '康健')]} runtime={new SkinRuntime()} rootRef={{ current: null }} />,
    );
    expect(ticksOf(container)).toHaveLength(0);
    expect(rowsOf(container)).toHaveLength(1);
  });

  it('jsdom 没有 element.animate:换档直接换字,什么都不动画', () => {
    const rt = new SkinRuntime();
    const { container, rerender } = render(<XunStats axes={[view(60, '康健')]} runtime={rt} rootRef={{ current: null }} />);
    rerender(<XunStats axes={[view(45, '耗损', 'caution')]} runtime={rt} rootRef={{ current: null }} />);
    expect(bandText(container)).toBe('耗损');
  });
});

describe('动效(假 element.animate)', () => {
  beforeEach(() => stubAnimate());

  it('入场:刻痕逐道 28ms 起、每道 240ms;刻痕出完 120ms 后四根条依次画,错开 90ms、各 1100ms;已标记的不播长出来', () => {
    const { container } = render(<Panel timeline={{ turn: 5, markedTurns: [2] }} runtime={new SkinRuntime()} />);
    const ticks = ticksOf(container);
    const tickAnims = anims.filter((a) => ticks.includes(a.el as HTMLElement));
    expect(tickAnims.map((a) => a.options.delay)).toEqual([0, 28, 56, 84, 112]);
    expect(tickAnims.every((a) => a.options.duration === COUNT_FADE_MS && a.options.fill === 'backwards')).toBe(true);
    expect(tickAnims.every((a) => 'opacity' in a.keyframes[0] && !('transform' in a.keyframes[0]))).toBe(true);
    expect(tickAnims[0].keyframes).toEqual([{ opacity: 0 }, { opacity: 0.86 }]); // 第 1 回合,age 4
    expect(tickAnims[1].keyframes).toEqual([{ opacity: 0 }, { opacity: 1 }]); // 第 2 回合标记过 → 恒为 1
    const fills = anims.filter((a) => (a.el as HTMLElement).dataset.fill !== undefined);
    expect(fills.map((a) => a.options.delay)).toEqual([0, 1, 2, 3].map((k) => 5 * COUNT_MS + BAR_GAP_MS + k * BAR_STAGGER_MS));
    expect(fills.every((a) => a.options.duration === ENTER_MS && a.options.easing === EASE_OUT_CUBIC)).toBe(true);
    expect(fills[0].keyframes).toEqual([{ transform: 'scaleX(0)' }, { transform: 'scaleX(0.46)' }]);
    expect(anims).toHaveLength(5 + 4); // 没有别的(没有记忆点、没有残痕)
  });

  it('新开局:0 道刻痕,四根条在 120ms 起画', () => {
    render(<Panel timeline={{ turn: 0, markedTurns: [] }} runtime={new SkinRuntime()} />);
    expect(anims.map((a) => a.options.delay)).toEqual([120, 210, 300, 390]);
  });

  it('回合 +1、没被标记:新刻痕 900ms 淡入,不长', () => {
    const rt = new SkinRuntime();
    const { container, rerender } = render(<Panel timeline={{ turn: 3, markedTurns: [] }} runtime={rt} />);
    anims = [];
    rerender(<Panel timeline={{ turn: 4, markedTurns: [] }} runtime={rt} />);
    const last = ticksOf(container).at(-1)!;
    expect(anims).toHaveLength(1);
    expect(anims[0].el).toBe(last);
    expect(anims[0].keyframes).toEqual([{ opacity: 0 }, { opacity: 1 }]);
    expect(anims[0].options.duration).toBe(TICK_MS);
  });

  it('回合 +1、刚被标记(留下的那一年):新刻痕淡入,540ms 后用 600ms 长出来', () => {
    const rt = new SkinRuntime();
    const { container, rerender } = render(<Panel timeline={{ turn: 3, markedTurns: [] }} runtime={rt} />);
    anims = [];
    rerender(<Panel timeline={{ turn: 4, markedTurns: [4] }} runtime={rt} />);
    const last = ticksOf(container).at(-1)!;
    expect(last.dataset.kept).toBe('true');
    const grow = anims.find((a) => 'transform' in a.keyframes[0])!;
    expect(grow.el).toBe(last);
    expect(grow.keyframes).toEqual([{ transform: SHORT_TICK }, { transform: 'scaleY(1)' }]);
    expect(grow.options).toMatchObject({ delay: KEPT_DELAY_MS, duration: KEPT_MS, fill: 'backwards' });
    expect(KEPT_DELAY_MS).toBe(540);
  });

  it('首次装载就带着清单(续局):已标记的直接是长的,不播长出来', () => {
    render(<Panel timeline={{ turn: 9, markedTurns: [9] }} runtime={new SkinRuntime()} />);
    expect(anims.some((a) => 'transform' in a.keyframes[0] && String(a.keyframes[0].transform).startsWith('scaleY'))).toBe(false);
  });

  it('入场途中来了回合落地:runtime teardown 取消在途入场(直接到终态),再判记忆点', () => {
    const rt = new SkinRuntime();
    const { rerender } = render(<Panel timeline={{ turn: 3, markedTurns: [] }} runtime={rt} />);
    const entry = [...anims];
    act(() => rt.teardown()); // 通用层换回合时做的事(useSkinRuntime)
    expect(entry.every((a) => a.playState === 'idle')).toBe(true);
    anims = [];
    rerender(<Panel timeline={{ turn: 4, markedTurns: [4] }} runtime={rt} />);
    expect(anims.some((a) => a.options.delay === KEPT_DELAY_MS)).toBe(true);
  });

  it('残痕:下降那一帧起一段 0 → from 的线,停 300ms 再 1400ms 淡;继续下降不重起;回涨即收掉', () => {
    const rt = new SkinRuntime();
    const R = (v: number) => <XunStats axes={[view(v, '康健')]} runtime={rt} rootRef={{ current: null }} />;
    const { container, rerender } = render(R(60));
    anims = [];
    rerender(R(59));
    expect(anims).toHaveLength(1);
    const g = anims[0];
    expect((g.el as HTMLElement).dataset.ghost).toBe('');
    expect(g.options.duration).toBe(GHOST_HOLD_MS + GHOST_FADE_MS);
    expect(g.keyframes[0]).toEqual({ opacity: 1, transform: 'scaleX(0.6)' });
    expect(g.keyframes[1]).toMatchObject({ opacity: 1, offset: 300 / 1700, easing: 'ease-in' });
    expect(g.keyframes[2]).toEqual({ opacity: 0, transform: 'scaleX(0.6)' });
    rerender(R(52));
    expect(anims).toHaveLength(1);
    rerender(R(55));
    expect(g.playState).toBe('idle');
    expect(container.querySelector('[data-ghost]')).not.toBeNull(); // 元素常在,静态不可见
  });

  it('档名换字:350ms 淡出期间仍是旧字 → 换成最新的字再 350ms 淡回;淡出途中又跨一档,换字时取最新', () => {
    const rt = new SkinRuntime();
    const R = (v: number, l: string) => <XunStats axes={[view(v, l)]} runtime={rt} rootRef={{ current: null }} />;
    const { container, rerender } = render(R(60, '康健'));
    anims = [];
    rerender(R(50, '耗损'));
    const out = anims.find((a) => a.el === container.querySelector('[data-band-text]'))!;
    expect(out.keyframes).toEqual([{ opacity: 1 }, { opacity: 0 }]);
    expect(out.options).toMatchObject({ duration: BAND_MS / 2, fill: 'forwards', easing: 'ease-in-out' });
    expect(bandText(container)).toBe('康健');
    rerender(R(19, '衰弱'));
    expect(bandText(container)).toBe('康健');
    expect(anims.filter((x) => x.el === out.el)).toHaveLength(1); // 淡出途中不另起
    act(() => out.finish());
    expect(bandText(container)).toBe('衰弱');
    const back = anims.at(-1)!;
    expect(back.keyframes).toEqual([{ opacity: 0 }, { opacity: 1 }]);
    expect(out.playState).toBe('idle'); // forwards 残留已收掉
  });

  it('档名淡出途中被 teardown 取消:直接落到最新的字', () => {
    const rt = new SkinRuntime();
    const R = (v: number, l: string) => <XunStats axes={[view(v, l)]} runtime={rt} rootRef={{ current: null }} />;
    const { container, rerender } = render(R(60, '康健'));
    rerender(R(50, '耗损'));
    act(() => rt.teardown());
    expect(bandText(container)).toBe('耗损');
  });

  it('减弱动效:一个动画都不起,换档直接换字', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation((q: string) => ({ matches: true, media: q }) as MediaQueryList);
    const rt = new SkinRuntime();
    const { container, rerender } = render(<Panel timeline={{ turn: 4, markedTurns: [] }} runtime={rt} />);
    rerender(<Panel timeline={{ turn: 5, markedTurns: [5] }} values={{ ...VALUES, vigor: 12 }} runtime={rt} />);
    expect(anims).toHaveLength(0);
    expect(bandText(rowsOf(container)[0])).toBe('衰弱');
  });

  it('卸载:在途动画全部取消', () => {
    const rt = new SkinRuntime();
    const { unmount } = render(<Panel timeline={{ turn: 6, markedTurns: [] }} runtime={rt} />);
    const live = [...anims];
    unmount();
    rt.teardown(); // 通用层卸载时同样 teardown
    expect(live.every((a) => a.playState === 'idle')).toBe(true);
  });
});

describe('决策圈', () => {
  const ACTIONS: ClientAction[] = [
    { id: 'A', text: '去送站', hint: '他会记得' },
    { id: 'B', text: '留在食堂', hint: '' },
  ];

  it('几行字:编号 + 正文 + hint;没有 hint 不渲染该节点;只回传 id', () => {
    const onChoose = vi.fn();
    const { container } = render(<XunActions actions={ACTIONS} disabled={false} onChoose={onChoose} />);
    const opts = container.querySelectorAll(`.${styles.opt}`);
    expect(opts).toHaveLength(2);
    expect(opts[0].querySelector(`.${styles.optHint}`)!.textContent).toBe('他会记得');
    expect(opts[1].querySelector(`.${styles.optHint}`)).toBeNull();
    fireEvent.click(screen.getByText('留在食堂'));
    expect(onChoose).toHaveBeenCalledWith('B');
  });

  it('生成中全部禁用', () => {
    render(<XunActions actions={ACTIONS} disabled onChoose={() => {}} />);
    expect(screen.getAllByRole('button').every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
  });
});

describe('宋体子集(决策 6)', () => {
  // 手抄自 ArchetypeRegistry life_sim:fixedWorldTitle「寻常」+ 四根轴 withBands 的十二个 label。
  // 服务端改了文案这里不会红(已知代价 1)—— 改文案的人须同步重裁子集。
  const STRINGS = ['寻常', '康健', '耗损', '衰弱', '炽热', '转淡', '熄了', '岔口尚多', '路已收窄', '只剩一条', '轻身', '有所系', '深系'];

  it('世界名与全部档名的每个字都在子集里;子集没有多余的字', () => {
    const need = new Set(STRINGS.join(''));
    for (const ch of need) expect(XUN_SONG_CHARS, ch).toContain(ch);
    expect(new Set(XUN_SONG_CHARS)).toEqual(need);
    expect([...XUN_SONG_CHARS]).toHaveLength(32);
  });

  it('测试里的档名与本文件的档表一致(防手抄漂移)', () => {
    const labels = [VIGOR, LONGING, CROSSROADS, TIES].flat().map((b) => b.label);
    expect(new Set(labels)).toEqual(new Set(STRINGS.slice(1)));
  });
});

describe('调试', () => {
  it('非 debug 时不渲染重播按钮', () => {
    render(<Panel timeline={{ turn: 2, markedTurns: [] }} runtime={new SkinRuntime()} />);
    expect(screen.queryByText('重播:入场')).toBeNull();
    expect(screen.queryByText('重播:留下的那一年')).toBeNull();
  });
});
