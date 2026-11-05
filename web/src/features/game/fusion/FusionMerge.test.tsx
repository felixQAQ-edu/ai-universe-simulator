import { StrictMode } from 'react';
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import gsap from 'gsap';
import { CROSSFADE, SETTLE_MS, SETTLE_REDUCED_MS, snapshotList, totalMs } from './crossfade';
import { FusionMerge } from './FusionMerge';

// 三段交叉淡化 + 落位的钉子(ADR-019 订正 §6 / §7;ADR-018 §4.8「测试守行为不守像素」)。
// 观感由真机 / 录屏回答;这里钉结构与契约:三段的先后、成品卡叠在宿主卡位上、落位只走 transform、
// reduced-motion 不移动、演完一定放行、卸载后真卡上不留任何内联样式。
//
// 取证边界:jsdom 没有布局,offsetTop 逐个替死;时间线由测试直接 seek(不靠 rAF)。

const GAP = 100; // 卡位间距(卡高 + 缝)
/** 手势层交过来的松手姿态(与 setup 里摆在被拖卡上的内联值一致)。 */
const POSE = { transform: 'translate3d(6px, 40px, 0) scale(1.03)', zIndex: '30' };

function stubTop(el: HTMLElement, top: () => number) {
  Object.defineProperty(el, 'offsetTop', { configurable: true, get: top });
  stubRect(el);
}

/** layoutBox 读 getBoundingClientRect(jsdom 恒 0):按替死的 offsetTop / offsetHeight 给。 */
function stubRect(el: HTMLElement) {
  el.getBoundingClientRect = () => {
    const top = el.offsetTop;
    const height = el.offsetHeight;
    return { top, height, bottom: top + height, left: 0, right: 343, width: 343, x: 0, y: top, toJSON: () => ({}) } as DOMRect;
  };
}

function card(name: string): HTMLElement {
  const el = document.createElement('button');
  el.dataset.name = name;
  el.innerHTML = `<div>${name}</div><p>tagline</p>`;
  return el;
}

/**
 * 卡列:按 order 排的卡,offsetTop = 下标 × GAP(随 DOM 顺序实时算)。
 * 先量 before,再把成品卡插到宿主紧下方 —— 与 ArchetypeSelect 的「先插入、再演」同一顺序。
 */
function setup(order: string[], hostName: string, foreignName: string) {
  const list = document.createElement('div');
  document.body.appendChild(list);
  const els = Object.fromEntries(order.map((n) => [n, card(n)]));
  for (const n of order) {
    list.appendChild(els[n]);
    stubTop(els[n], () => Array.from(list.children).indexOf(els[n]) * GAP);
  }
  const host = els[hostName];
  const foreign = els[foreignName];
  // 松手姿态(手势层留下的,见 useFusionDrag 的 CommitCards)。
  foreign.style.transform = 'translate3d(6px, 40px, 0) scale(1.03)';
  foreign.style.zIndex = '30';
  const before = snapshotList(list);
  const fused = card('fused');
  fused.dataset.fusionKey = 'h×f';
  host.after(fused);
  stubTop(fused, () => Array.from(list.children).indexOf(fused) * GAP);
  return { list, els, host, foreign, fused, before };
}

/** 刚挂上的那条时间线(暂停住,由测试 seek)。 */
function lastTimeline(): gsap.core.Timeline {
  const all = gsap.globalTimeline.getChildren(false, false, true);
  const tl = all[all.length - 1] as gsap.core.Timeline;
  tl.pause();
  return tl;
}

const yOf = (el: HTMLElement) => Number(gsap.getProperty(el, 'y'));
const opOf = (el: HTMLElement) => Number(getComputedStyle(el).opacity || '1');

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('时长', () => {
  it('三段交叉淡化 0–160 / 120–380 / 320–460,三段错开(ADR-019 订正 §7)', () => {
    expect(CROSSFADE).toEqual({ out: [0, 160], fade: [120, 380], text: [320, 460] });
    expect(CROSSFADE.fade[0]).toBeLessThan(CROSSFADE.out[1]);
    expect(CROSSFADE.text[0]).toBeGreaterThan(CROSSFADE.fade[0]);
  });

  it('落位 ≤ 300ms(简报上限);reduced-motion 比原速短', () => {
    expect(SETTLE_MS).toBeLessThanOrEqual(300);
    expect(totalMs(false)).toBe(460 + SETTLE_MS);
    expect(totalMs(true)).toBe(460 + SETTLE_REDUCED_MS);
  });
});

describe('FusionMerge', () => {
  it('首帧之前:成品卡叠在宿主卡位上、透明、文字为 0;插入挤下去的卡原地抵消', () => {
    const { list, els, host, foreign, fused, before } = setup(['f', 'h', 'below'], 'h', 'f');
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    lastTimeline();
    expect(yOf(fused)).toBe(host.offsetTop - fused.offsetTop); // -100:正好在宿主卡位
    expect(fused.style.opacity).toBe('0');
    for (const t of Array.from(fused.children)) expect((t as HTMLElement).style.opacity).toBe('0');
    expect(yOf(els.below)).toBe(-GAP); // 被挤下去一格 → 反向抵消一格
    expect(fused.style.pointerEvents).toBe('none');
    expect(host.style.pointerEvents).toBe('none');
  });

  it('三段先后:被拖卡先退净 → 成品卡底色盖实、宿主才隐去 → 文字最后到', () => {
    const { list, host, foreign, fused, before } = setup(['f', 'h', 'below'], 'h', 'f');
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    const tl = lastTimeline();
    const text = fused.children[0] as HTMLElement;

    tl.seek(0.1);
    expect(opOf(foreign)).toBeGreaterThan(0);
    expect(foreign.style.transform).toContain('6px'); // 退场在松手姿态上
    expect(opOf(fused)).toBe(0);

    tl.seek(0.2);
    expect(opOf(foreign)).toBe(0);
    expect(foreign.style.zIndex).toBe(''); // 退净后松手姿态清掉
    expect(opOf(fused)).toBeGreaterThan(0);
    expect(opOf(fused)).toBeLessThan(1);
    expect(opOf(host)).toBe(1); // 还没盖实,宿主还在
    expect(opOf(text)).toBe(0);

    tl.seek(0.39);
    expect(opOf(fused)).toBe(1);
    expect(opOf(host)).toBe(0); // 盖实了才隐去:任何时刻只有一道外边界
    expect(opOf(text)).toBeGreaterThan(0);
    expect(yOf(fused)).toBe(-GAP); // 揭晓全程在宿主卡位里

    tl.seek(0.46);
    expect(opOf(text)).toBe(1);
  });

  it('落位(原速):成品卡下移一格到最终位置,挤下去的卡一起归 0;宿主与被拖卡各在原位淡回', () => {
    const { list, els, host, foreign, fused, before } = setup(['f', 'h', 'below'], 'h', 'f');
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    const tl = lastTimeline();
    tl.seek(0.46 + 0.1);
    expect(yOf(fused)).toBeGreaterThan(-GAP);
    expect(yOf(fused)).toBeLessThan(0);
    expect(yOf(els.below)).toBeLessThan(0);
    expect(opOf(host)).toBeGreaterThan(0);
    expect(opOf(host)).toBeLessThan(1);
    expect(yOf(host)).toBe(0); // 宿主不动
    tl.seek(totalMs(false) / 1000);
    expect(yOf(fused)).toBe(0);
    expect(opOf(host)).toBe(1);
    expect(opOf(foreign)).toBe(1);
  });

  it('被拖卡在宿主下面:它也被挤下去一格,在自己(新)的卡位上淡回', () => {
    const { list, host, foreign, before } = setup(['h', 'f'], 'h', 'f');
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    const tl = lastTimeline();
    // 插入把它的布局位置挤下去一格:松手姿态也要抵消这一格,t=0 不许往下跳。
    expect(foreign.style.transform).toBe(`translateY(${-GAP}px) translate3d(6px, 40px, 0) scale(1.03)`);
    tl.seek(0.2);
    expect(yOf(foreign)).toBe(-GAP); // 松手姿态换成卡位抵消(此时看不见)
    tl.seek(totalMs(false) / 1000);
    expect(yOf(foreign)).toBe(0);
    expect(opOf(foreign)).toBe(1);
  });

  it('reduced-motion:同一套三段,落位**不移动** —— 文字到齐那一刻直接就位', () => {
    const { list, els, host, foreign, fused, before } = setup(['f', 'h', 'below'], 'h', 'f');
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced onDone={vi.fn()} />);
    const tl = lastTimeline();
    tl.seek(0.3);
    expect(yOf(fused)).toBe(-GAP);
    expect(opOf(fused)).toBeGreaterThan(0);
    tl.seek(0.461);
    expect(yOf(fused)).toBe(0);
    expect(yOf(els.below)).toBe(0);
    expect(tl.duration()).toBeCloseTo(totalMs(true) / 1000, 3);
  });

  it('只动 transform / opacity:时间线上没有任何布局属性', () => {
    const { list, host, foreign, before } = setup(['h', 'f', 'below'], 'h', 'f');
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    const tl = lastTimeline();
    // GSAP 自己的配置键(不是被插值的样式),其余一律只许 opacity / y(transform)/ clearProps。
    const config = new Set(['parent', 'duration', 'ease', 'immediateRender', 'data', 'overwrite', 'delay', 'lazy', 'repeat']);
    const animated = new Set<string>();
    for (const tw of tl.getChildren(true, true, false) as gsap.core.Tween[]) {
      for (const k of Object.keys(tw.vars)) if (!config.has(k)) animated.add(k);
    }
    expect([...animated].sort()).toEqual(['clearProps', 'opacity', 'y']);
  });

  it('演完放行;卸载后真卡上不留任何内联样式(不 revert 回松手姿态)', () => {
    const onDone = vi.fn();
    const { list, els, host, foreign, fused, before } = setup(['f', 'h', 'below'], 'h', 'f');
    const r = render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={onDone} />);
    const tl = lastTimeline();
    tl.seek(tl.duration(), false);
    expect(onDone).toHaveBeenCalled();
    r.unmount();
    for (const el of [fused, host, foreign, els.below]) {
      expect(el.getAttribute('style') ?? '').toBe('');
    }
    for (const t of Array.from(fused.children)) expect((t as HTMLElement).getAttribute('style') ?? '').toBe('');
  });

  it('**一定会放行**:页面在后台(rAF 冻结)时靠独立于 rAF 的兜底', () => {
    vi.useFakeTimers();
    const onDone = vi.fn();
    const { list, host, foreign, before } = setup(['f', 'h'], 'h', 'f');
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={onDone} />);
    lastTimeline();
    vi.advanceTimersByTime(totalMs(false) + 700);
    expect(onDone).toHaveBeenCalled();
  });

  it('中途卸载(例如揉合时单击别的卡进了世界):撤掉兜底计时、清掉内联样式', () => {
    vi.useFakeTimers();
    const onDone = vi.fn();
    const { list, host, foreign, fused, before } = setup(['f', 'h'], 'h', 'f');
    const r = render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={onDone} />);
    lastTimeline().seek(0.2);
    r.unmount();
    vi.advanceTimersByTime(totalMs(false) + 2000);
    expect(onDone).not.toHaveBeenCalled();
    expect(foreign.getAttribute('style') ?? '').toBe('');
    expect(fused.getAttribute('style') ?? '').toBe('');
  });

  it('找不到成品卡(理论上不会):不演、直接放行,被拖卡的松手姿态照样清掉', () => {
    const onDone = vi.fn();
    const { list, host, foreign, before } = setup(['f', 'h'], 'h', 'f');
    render(<FusionMerge list={list} fusedKey="nope" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={onDone} />);
    expect(onDone).toHaveBeenCalled();
    expect(foreign.style.transform).toBe('');
    expect(foreign.style.zIndex).toBe('');
  });
});

// ── 宿主比成品卡高(375 宽:规则怪谈钩子折两行 115px,人防 93px;Felix 选定方案 A)─────────────
describe('宿主比成品卡高:揉合期间钉成宿主高度,落位时用 clip-path 连续收回', () => {
  const GAP_PX = 13;
  const H: Record<string, number> = { f: 93, h: 115, below: 93, fused: 93 };
  /** 布局随高度走:offsetTop = 前面各卡高度之和 + 缝;成品卡的高度 = max(自然高度, min-height)。 */
  function setupTall(order: string[]) {
    const list = document.createElement('div');
    document.body.appendChild(list);
    const els: Record<string, HTMLElement> = {};
    const heightOf = (el: HTMLElement) => Math.max(H[el.dataset.name!], parseFloat(el.style.minHeight) || 0);
    const topOf = (el: HTMLElement) => {
      let t = 0;
      for (const c of Array.from(list.children) as HTMLElement[]) {
        if (c === el) return t;
        t += heightOf(c) + GAP_PX;
      }
      return t;
    };
    const mk = (n: string) => {
      const el = card(n);
      Object.defineProperty(el, 'offsetTop', { configurable: true, get: () => topOf(el) });
      Object.defineProperty(el, 'offsetHeight', { configurable: true, get: () => heightOf(el) });
      stubRect(el);
      return el;
    };
    for (const n of order) list.appendChild((els[n] = mk(n)));
    els.f.style.transform = 'translate3d(6px, 40px, 0) scale(1.03)';
    const before = snapshotList(list);
    const fused = mk('fused');
    fused.dataset.fusionKey = 'h×f';
    els.h.after(fused);
    return { list, els, host: els.h, foreign: els.f, fused, before };
  }
  /** 屏上位置 = 布局位置 + transform y(jsdom 没有渲染,按这个算「看起来在哪」)。 */
  const screenTop = (el: HTMLElement) => el.offsetTop + yOf(el);
  const extra = H.h - H.fused;

  it('在宿主卡位里:成品卡钉成宿主高度,与宿主卡同顶同高(外边界重合),下面的卡原地不动', () => {
    const { list, els, host, foreign, fused, before } = setupTall(['h', 'below', 'f']);
    const belowBefore = before.get(els.below)!;
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    const tl = lastTimeline();
    expect(fused.style.minHeight).toBe(`${H.h}px`);
    expect(fused.offsetHeight).toBe(host.offsetHeight);
    expect(screenTop(fused)).toBe(host.offsetTop);
    expect(screenTop(els.below)).toBe(belowBefore);
    tl.seek(0.4);
    expect(screenTop(fused)).toBe(host.offsetTop);
    expect(fused.style.minHeight).toBe(`${H.h}px`);
  });

  it('落位:可见底边由 clip-path 从宿主高度连续收到自然高度;下面的卡同步多走这段差', () => {
    const { list, host, foreign, before } = setupTall(['h', 'below', 'f']);
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    const tl = lastTimeline();
    const clip = (tl.getChildren(true, true, false) as gsap.core.Tween[]).find((t) => 'clipPath' in t.vars);
    expect(clip).toBeDefined();
    expect(clip!.startTime()).toBeCloseTo(CROSSFADE.text[1] / 1000, 6);
    expect(clip!.duration()).toBeCloseTo(SETTLE_MS / 1000, 6);
    expect(String(clip!.vars.clipPath)).toContain(`${extra}px`); // 收掉的正好是高度差
    expect(String((clip!.vars as { startAt?: { clipPath?: string } }).startAt?.clipPath)).toMatch(/^inset\(0px 0px 0px 0px/);
  });

  it('撤钉那一帧不跳:撤之前与撤之后,每张卡的屏上位置逐像素相同,成品卡回到自然高度', () => {
    const { list, els, host, foreign, fused, before } = setupTall(['h', 'below', 'f']);
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    const tl = lastTimeline();
    const end = totalMs(false) / 1000;
    tl.seek(end - 0.0001);
    const pre = [fused, els.below, foreign].map(screenTop);
    expect(fused.style.minHeight).toBe(`${H.h}px`);
    expect(yOf(els.below)).toBeCloseTo(-extra, 0);
    tl.seek(end, false); // 回调要触发
    expect(fused.style.minHeight).toBe('');
    expect(fused.offsetHeight).toBe(H.fused);
    const post = [fused, els.below, foreign].map(screenTop);
    post.forEach((v, i) => expect(v).toBeCloseTo(pre[i], 0));
    expect(yOf(els.below)).toBe(0);
  });

  it('只多动了一个 clip-path(布局属性仍一个没动)', () => {
    const { list, host, foreign, before } = setupTall(['h', 'below', 'f']);
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    const tl = lastTimeline();
    const config = new Set(['parent', 'duration', 'ease', 'immediateRender', 'data', 'overwrite', 'delay', 'lazy', 'repeat', 'startAt', 'runBackwards', 'callbackScope', 'onComplete', 'onReverseComplete', 'onCompleteParams', 'onReverseCompleteParams']);
    const animated = new Set<string>();
    for (const tw of tl.getChildren(true, true, false) as gsap.core.Tween[]) {
      for (const k of Object.keys(tw.vars)) if (!config.has(k)) animated.add(k);
    }
    expect([...animated].sort()).toEqual(['clearProps', 'clipPath', 'opacity', 'y']);
  });

  it('reduced-motion:文字到齐那一刻连同高度差一起直接就位(不移动、不裁切)', () => {
    const { list, els, host, foreign, fused, before } = setupTall(['h', 'below', 'f']);
    render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced onDone={vi.fn()} />);
    const tl = lastTimeline();
    expect((tl.getChildren(true, true, false) as gsap.core.Tween[]).some((t) => 'clipPath' in t.vars)).toBe(false);
    tl.seek(0.3);
    expect(fused.style.minHeight).toBe(`${H.h}px`);
    tl.seek(0.461, false);
    expect(fused.style.minHeight).toBe('');
    expect(screenTop(fused)).toBe(fused.offsetTop);
    expect(yOf(els.below)).toBe(0);
  });

  it('卸载后不留钉高与裁切', () => {
    const { list, host, foreign, fused, before } = setupTall(['h', 'below', 'f']);
    const r = render(<FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />);
    lastTimeline().seek(0.6);
    r.unmount();
    expect(fused.getAttribute('style') ?? '').toBe('');
  });
});

// ── React StrictMode:effect 会 mount → cleanup → mount(刀 2c-1 返工)──────────────────────────
// 第一版的松手姿态只活在内联样式里;cleanup 清掉它,重跑时被拖卡已回到自己的卡位,于是「在原位淡出」。
// 真浏览器(dev 服务器)里松手后 15ms 内跳回原位,就是这一条。
describe('StrictMode 下 effect 清理后重跑', () => {
  it('被拖卡仍按松手姿态退场(不跳回自己的卡位)', () => {
    const { list, host, foreign, before } = setup(['f', 'h', 'below'], 'h', 'f');
    render(
      <StrictMode>
        <FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />
      </StrictMode>,
    );
    const tl = lastTimeline();
    expect(foreign.style.transform).toBe(POSE.transform);
    expect(foreign.style.zIndex).toBe('30');
    tl.seek(0.1);
    expect(foreign.style.transform).toBe(POSE.transform);
    expect(Number(foreign.style.opacity)).toBeGreaterThan(0);
    expect(Number(foreign.style.opacity)).toBeLessThan(1);
  });

  it('被拖卡在宿主下面:重跑后松手姿态仍只抵消一格(不叠两次)', () => {
    const { list, host, foreign, before } = setup(['h', 'f'], 'h', 'f');
    render(
      <StrictMode>
        <FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />
      </StrictMode>,
    );
    lastTimeline();
    expect(foreign.style.transform).toBe(`translateY(${-GAP}px) ${POSE.transform}`);
  });

  it('成品卡仍叠在宿主卡位上、透明(重跑没有把 FLIP 抵消弄丢)', () => {
    const { list, host, foreign, fused, before } = setup(['f', 'h', 'below'], 'h', 'f');
    render(
      <StrictMode>
        <FusionMerge list={list} fusedKey="h×f" host={host} foreign={foreign} pose={POSE} before={before} emblem={null} reduced={false} onDone={vi.fn()} />
      </StrictMode>,
    );
    lastTimeline();
    expect(yOf(fused)).toBe(host.offsetTop - fused.offsetTop);
    expect(fused.style.opacity).toBe('0');
  });
});

