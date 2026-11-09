import { StrictMode } from 'react';
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import gsap from 'gsap';
import { snapshotList, totalMs } from './crossfade';
import type { FusionEmblem } from './emblem';
import { FusionMerge } from './FusionMerge';
import { DIM_OPACITY, EMBLEM_BEATS, EMBLEM_TOTAL_MS, HANDOFF_DY } from './plan';
import mergeSrc from './FusionMerge.tsx?raw';
import overlaySrc from './EmblemOverlay.tsx?raw';
import emblemSrc from './emblem.ts?raw';
import planSrc from './plan.ts?raw';
import emblemCss from './emblem.module.css?raw';

// 标志路径的钉子(ADR-019 订正 §2 / §3;刀 2c-2)。观感由录屏回答;这里钉结构与契约:
// 有 / 没登记走哪条路、通用代码不认识组合、各拍的先后、只动 transform / opacity / mask / 变量、演完全清。
//
// 用一份**测试自造**的标志(两段笔画、一团焦褐,挂在一个与任何真实组合都无关的 key 上):
// 通用代码若偷偷按组合分支,换一份数据就会露馅。
// 取证边界:jsdom 没有布局,位置逐个替死;时间线由测试直接 seek。

const GAP = 100;
const H = 93;
/** 手势层交出的松手姿态是**屏上 computed transform**(矩阵):rotate(3deg) + 平移 (6, 40)。 */
const POSE = { transform: 'matrix(0.99863, 0.052336, -0.052336, 0.99863, 6, 40)', zIndex: '30' };

const TEST_EMBLEM: FusionEmblem = {
  surface: {
    angle: 150,
    stops: [
      ['#ffffff', 0],
      ['#888888', 50],
      ['#000000', 100],
    ],
    seam: [30, 45, 55, 70],
    seamTexture: 'none',
    sideTexture: 'none',
  },
  mark: {
    size: 40,
    rotate: 3,
    ink: { color: 'rgb(0, 0, 0)', core: 0.7, halo: 0.1 },
    scorch: { color: 'rgb(90, 50, 20)', alpha: 0.5 },
    strokes: [
      { from: [10, 10], to: [90, 10], out: [0, -1], knots: [[0, 3], [1, 1]], band: { side: 'top', angle: 90 }, lead: 0.2, burn: [0, 0.5], ease: 'none' },
      { from: [90, 10], to: [90, 90], out: [1, 0], knots: [[0, 1], [1, 1]], band: { side: 'right', angle: 180 }, lead: 0, burn: [0.5, 1], ease: 'none' },
    ],
    blots: [{ center: [10, 10], radius: [20, 20], window: [0.2, 0.4], ease: 'none' }],
    accent: { from: [20, 15], to: [40, 15], color: 'rgb(0, 200, 0)', width: 2, alpha: 0.5, stops: [[0, 0], [1, 1]] },
  },
  reveal: { angle: 330, kink: 10, lag: 5, soft: 8, from: 20, to: 120 },
};

function stub(el: HTMLElement, list: HTMLElement, h = H) {
  Object.defineProperty(el, 'offsetTop', { configurable: true, get: () => Array.from(list.children).indexOf(el) * GAP });
  Object.defineProperty(el, 'offsetHeight', { configurable: true, get: () => h });
  el.getBoundingClientRect = () => {
    const top = el.offsetTop;
    return { top, height: h, bottom: top + h, left: 0, right: 343, width: 343, x: 0, y: top, toJSON: () => ({}) } as DOMRect;
  };
}

function card(name: string): HTMLElement {
  const el = document.createElement('button');
  el.dataset.name = name;
  el.innerHTML = `<div>${name}</div><p>tagline</p>`;
  return el;
}

/** 卡列 [f, h, other] + 成品卡插在宿主紧下方;另挂一条 aria-hidden 的提示(不该被压暗)。 */
function setup(foreignH = H) {
  const list = document.createElement('div');
  list.getBoundingClientRect = () => ({ top: 0, left: 0, width: 343, height: 400, right: 343, bottom: 400, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
  document.body.appendChild(list);
  const els = Object.fromEntries(['f', 'h', 'other'].map((n) => [n, card(n)]));
  for (const n of ['f', 'h', 'other']) {
    list.appendChild(els[n]);
    stub(els[n], list, n === 'f' ? foreignH : H);
  }
  const hint = document.createElement('p');
  hint.setAttribute('aria-hidden', 'true');
  list.appendChild(hint);
  const host = els.h;
  const foreign = els.f;
  foreign.style.transform = POSE.transform;
  foreign.style.zIndex = POSE.zIndex;
  const before = snapshotList(list);
  const fused = card('fused');
  fused.dataset.fusionKey = 'x×y';
  host.after(fused);
  stub(fused, list);
  return { list, host, foreign, fused, other: els.other, hint, before };
}

function mount(emblem: FusionEmblem | null, reduced = false, strict = false, foreignH = H) {
  const ctx = setup(foreignH);
  const onDone = vi.fn();
  const ui = (
    <FusionMerge list={ctx.list} fusedKey="x×y" host={ctx.host} foreign={ctx.foreign} pose={POSE} before={ctx.before} emblem={emblem} reduced={reduced} onDone={onDone} />
  );
  const r = render(strict ? <StrictMode>{ui}</StrictMode> : ui);
  const all = gsap.globalTimeline.getChildren(false, false, true);
  const tl = all[all.length - 1] as gsap.core.Timeline;
  tl.pause();
  const overlay = ctx.list.querySelector<HTMLElement>('[data-fusion-emblem]');
  return { ...ctx, onDone, tl, overlay, unmount: r.unmount };
}

const ms = (t: number) => t / 1000;
const v = (el: HTMLElement, name: string) => Number.parseFloat(el.style.getPropertyValue(name));
const op = (el: Element) => Number((el as HTMLElement).style.opacity || '1');

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('有登记 / 没登记两条分支', () => {
  it('有登记:融合面挂进卡列(宿主卡位那一个盒子上),时间线约 4.5 秒', () => {
    const { overlay, tl, host, list } = mount(TEST_EMBLEM);
    expect(overlay).not.toBeNull();
    expect(overlay!.parentElement).toBe(list);
    expect(overlay!.style.top).toBe(`${host.offsetTop}px`);
    expect(overlay!.style.height).toBe(`${H}px`);
    expect(tl.duration()).toBeCloseTo(EMBLEM_TOTAL_MS / 1000, 3);
    expect(EMBLEM_TOTAL_MS).toBe(4500);
  });

  it('没登记:不挂融合面,三段交叉淡化(460 + 280)', () => {
    const { overlay, tl } = mount(null);
    expect(overlay).toBeNull();
    expect(tl.duration()).toBeCloseTo(totalMs(false) / 1000, 3);
  });

  it('有登记但 reduced-motion:不出标志,走交叉淡化(460 + 160)', () => {
    const { overlay, tl } = mount(TEST_EMBLEM, true);
    expect(overlay).toBeNull();
    expect(tl.duration()).toBeCloseTo(totalMs(true) / 1000, 3);
  });

  it('其余卡只在标志路径上变暗;交叉淡化路径不变暗', () => {
    const a = mount(null);
    a.tl.seek(ms(300));
    expect(op(a.other)).toBe(1);
    document.body.innerHTML = '';
    const b = mount(TEST_EMBLEM);
    b.tl.seek(ms(EMBLEM_BEATS.dim[1]));
    expect(op(b.other)).toBeCloseTo(DIM_OPACITY, 3);
    expect(b.hint.style.opacity).toBe(''); // aria-hidden 的提示不算「卡」
  });
});

describe('通用代码不读组合 key', () => {
  const SOURCES = { 'FusionMerge.tsx': mergeSrc, 'EmblemOverlay.tsx': overlaySrc, 'emblem.ts': emblemSrc, 'plan.ts': planSrc, 'emblem.module.css': emblemCss };
  const KEYS = ['cultivation', 'cthulhu', 'rules_creepy', 'apocalypse', '验讫', '识海', '人防', 'yanqi', 'Yanqi'];

  it.each(Object.keys(SOURCES))('%s 里没有任何世界 id / 组合名', (name) => {
    const src = SOURCES[name as keyof typeof SOURCES];
    for (const k of KEYS) expect(src.includes(k), `${name} 含 ${k}`).toBe(false);
  });

  it('换一份自造数据、挂在任意 key 上,照样走完整条标志路径', () => {
    const { tl, overlay, fused } = mount(TEST_EMBLEM);
    tl.seek(tl.duration());
    expect(overlay!.style.opacity).toBe('0');
    expect(fused.style.getPropertyValue('--em-full')).toBe('1');
  });
});

describe('标志路径各拍', () => {
  it('交接:被拖卡从松手姿态摆正、靠拢到宿主卡位之下半张卡', () => {
    const { tl, foreign, host } = mount(TEST_EMBLEM);
    tl.seek(ms(EMBLEM_BEATS.settle[1]));
    expect(Number(gsap.getProperty(foreign, 'rotation'))).toBeCloseTo(0, 3);
    expect(Number(gsap.getProperty(foreign, 'x'))).toBeCloseTo(0, 3);
    // 被拖卡 f 在第 0 格,宿主在第 1 格:靠拢到宿主卡位之下半张卡。
    expect(Number(gsap.getProperty(foreign, 'y'))).toBeCloseTo(host.offsetTop - foreign.offsetTop + HANDOFF_DY * H, 3);
  });

  it('标志:两张原卡文字先退 → 融合面成形 → 被拖卡收净时宿主才隐去;两张原卡的文字随后复原(看不见时)', () => {
    const { tl, host, foreign, overlay } = mount(TEST_EMBLEM);
    tl.seek(ms(EMBLEM_BEATS.cardText[1]));
    for (const t of [...host.children, ...foreign.children]) expect(op(t)).toBe(0);
    expect(op(host)).toBe(1);
    tl.seek(ms(EMBLEM_BEATS.merge[1]));
    expect(op(overlay!)).toBe(1);
    expect(op(host)).toBe(1); // 还没收净
    tl.seek(ms(EMBLEM_BEATS.tuck[1]) + 0.001);
    expect(op(host)).toBe(0);
    expect(op(foreign)).toBe(0);
    for (const t of [...host.children, ...foreign.children]) expect(op(t)).toBe(1);
  });

  it('烧出顺序按登记:重角段先烧出 lead,烧边段里笔画 0 先于笔画 1;焦褐团在自己的窗里洇开', () => {
    const { tl, overlay } = mount(TEST_EMBLEM);
    const [e0, e1] = EMBLEM_BEATS.edges;
    tl.seek(ms(EMBLEM_BEATS.corner[1]) - 0.001);
    expect(v(overlay!, '--em-s0')).toBeGreaterThan(0.15);
    expect(v(overlay!, '--em-s1')).toBe(0);
    tl.seek(ms(e0 + (e1 - e0) * 0.25));
    expect(v(overlay!, '--em-s0')).toBeGreaterThan(0.2);
    expect(v(overlay!, '--em-s0')).toBeLessThan(1);
    expect(v(overlay!, '--em-s1')).toBe(0);
    expect(v(overlay!, '--em-b0')).toBeGreaterThan(0);
    tl.seek(ms(e1));
    expect(v(overlay!, '--em-s0')).toBe(1);
    expect(v(overlay!, '--em-s1')).toBe(1);
    tl.seek(ms(EMBLEM_BEATS.accent[0]));
    expect(v(overlay!, '--em-accent')).toBe(0);
    tl.seek(ms(EMBLEM_BEATS.accent[1]));
    expect(v(overlay!, '--em-accent')).toBe(1);
  });

  it('揭晓:边界从登记的起点推到终点(在真实成品卡上);点缀先退、标志随后退;文字到 3150 才开始浮现', () => {
    const { tl, overlay, fused } = mount(TEST_EMBLEM);
    const [r0, r1] = EMBLEM_BEATS.reveal;
    tl.seek(ms(r0));
    expect(fused.style.getPropertyValue('--em-front')).toBe('20%');
    expect(op(fused)).toBe(1);
    tl.seek(ms(EMBLEM_BEATS.text[0]) - 0.001);
    for (const t of fused.children) expect(op(t)).toBe(0);
    expect(v(overlay!, '--em-accent')).toBe(0);
    tl.seek(ms(r1));
    expect(fused.style.getPropertyValue('--em-front')).toBe('120%');
    expect(fused.style.getPropertyValue('--em-full')).toBe('1');
    expect(v(overlay!, '--em-ink')).toBe(0);
    expect(op(overlay!)).toBe(0);
    tl.seek(ms(EMBLEM_BEATS.text[1]));
    for (const t of fused.children) expect(op(t)).toBe(1);
  });

  it('光带门控:揭晓期间为 0,3700–4220 淡入', () => {
    const { tl, fused } = mount(TEST_EMBLEM);
    expect(fused.hasAttribute('data-emblem-reveal')).toBe(true);
    tl.seek(ms(EMBLEM_BEATS.streak[0]));
    expect(v(fused, '--em-streak')).toBe(0);
    tl.seek(ms(EMBLEM_BEATS.streak[1]));
    expect(v(fused, '--em-streak')).toBe(1);
  });

  it('落位接在揭晓之后(4220–4500),与其余卡恢复一同收住;宿主与被拖卡在原位淡回', () => {
    const { tl, fused, host, foreign, other } = mount(TEST_EMBLEM);
    tl.seek(ms(EMBLEM_BEATS.land[0]));
    expect(Number(gsap.getProperty(fused, 'y'))).toBe(host.offsetTop - fused.offsetTop);
    tl.seek(ms(EMBLEM_BEATS.land[1]));
    expect(Number(gsap.getProperty(fused, 'y'))).toBe(0);
    expect(op(host)).toBe(1);
    expect(op(foreign)).toBe(1);
    expect(op(other)).toBe(1);
  });

  it('只动 transform / opacity / mask(经变量)/ clip-path:时间线上没有任何布局属性', () => {
    const { tl } = mount(TEST_EMBLEM);
    const LAYOUT = /^(width|height|top|left|right|bottom|margin|padding|minHeight|maxHeight|minWidth|maxWidth|inset|fontSize|lineHeight|border)/i;
    const props = new Set<string>();
    for (const t of tl.getChildren(true, true, false) as gsap.core.Tween[]) for (const k of Object.keys(t.vars)) props.add(k);
    for (const k of props) expect(LAYOUT.test(k), `时间线动了布局属性 ${k}`).toBe(false);
    // 而且标志路径确实在推 mask 变量与 transform / opacity
    expect(props.has('--em-front')).toBe(true);
    expect(props.has('opacity')).toBe(true);
    expect(props.has('y')).toBe(true);
  });
});

// 收进卡位时露出的深色块(刀 2c-2 返工,校勘 375 宽实测:被拖卡 115 高、宿主 93 高)。
// 被拖卡露在宿主卡位外的那一截在停顿里连同字退净;遮罩两条硬边钉在卡位上 / 下沿(换算到被拖卡自身坐标)。
describe('卡位外修剪', () => {
  // 遮罩由时间线的 onUpdate 逐帧写:seek 第二参给 false,否则 GSAP 默认不触发回调。
  /** 解析被拖卡上的修剪遮罩:卡位上 / 下沿在被拖卡自身坐标里的位置,与卡位外那两段的不透明度。 */
  function trimOf(el: HTMLElement) {
    const m = el.style.getPropertyValue('mask-image');
    // jsdom 会把 `to bottom` 省掉、把 #000 规范成 rgb(0, 0, 0):两种写法都认。
    const K = '(?:#000|rgb\\(0, 0, 0\\))';
    const r = m.match(new RegExp(`rgba\\(0, 0, 0, ([\\d.]+)\\) (-?[\\d.]+)px, ${K} (-?[\\d.]+)px, ${K} (-?[\\d.]+)px, rgba\\(0, 0, 0, ([\\d.]+)\\) (-?[\\d.]+)px`));
    if (!r) return null;
    return { out: Number(r[1]), a: Number(r[2]), b: Number(r[4]) };
  }
  /** 卡位下沿在被拖卡屏上坐标里应在的位置(按 y / scale 换算,transform-origin 在中心)。 */
  function expectedB(foreign: HTMLElement, host: HTMLElement, fH: number) {
    const y = Number(gsap.getProperty(foreign, 'y'));
    const k = Number(gsap.getProperty(foreign, 'scale')) || 1;
    const top = foreign.offsetTop + y + (fH * (1 - k)) / 2;
    return (host.offsetTop + H - top) / k;
  }

  it.each([
    ['比宿主高(115 vs 93)', 115],
    ['与宿主等高', H],
    ['比宿主矮(80 vs 93)', 80],
  ])('%s:停顿结束时卡位外那一截已退净,卡位内不动;收进卡位全程卡位外为 0', (_, fH) => {
    const { tl, foreign, host } = mount(TEST_EMBLEM, false, false, fH);
    tl.seek(ms(EMBLEM_BEATS.trim[0]) + 0.01, false);
    const start = trimOf(foreign)!;
    expect(start.out).toBeGreaterThan(0.9); // 刚开始退
    tl.seek(ms(EMBLEM_BEATS.trim[1]), false);
    const end = trimOf(foreign)!;
    expect(end.out).toBe(0);
    // 硬边正落在卡位上 / 下沿:下沿在被拖卡之内(它伸出了卡位),上沿在它上方(它的顶在卡位里)
    expect(end.b).toBeCloseTo(expectedB(foreign, host, fH), 1);
    expect(end.b).toBeLessThan(fH);
    expect(end.b - end.a).toBeCloseTo(H / Number(gsap.getProperty(foreign, 'scale')), 1);
    // 收进卡位段:被拖卡往上收,卡位下沿在它坐标里随之下移;卡位外始终是 0
    for (const t of [EMBLEM_BEATS.tuck[0], 900, 1050, EMBLEM_BEATS.tuck[1] - 5]) {
      tl.seek(ms(t), false);
      const m = trimOf(foreign)!;
      expect(m.out, `${t}ms`).toBe(0);
      expect(m.b, `${t}ms`).toBeCloseTo(expectedB(foreign, host, fH), 1);
    }
  });

  it('收进卡位那一刻(被拖卡已淡净)撤掉遮罩;交接段(摆正之前)不挂遮罩', () => {
    const { tl, foreign } = mount(TEST_EMBLEM, false, false, 115);
    tl.seek(ms(EMBLEM_BEATS.settle[1]) - 0.05, false);
    expect(foreign.style.getPropertyValue('mask-image')).toBe('');
    tl.seek(ms(EMBLEM_BEATS.tuck[1]) + 0.01, false);
    expect(foreign.style.getPropertyValue('mask-image')).toBe('');
  });

  it('修剪在收进卡位、文字退、融合面盖上来之前收完', () => {
    expect(EMBLEM_BEATS.trim[1]).toBeLessThan(EMBLEM_BEATS.tuck[0]);
    expect(EMBLEM_BEATS.trim[1]).toBeLessThan(EMBLEM_BEATS.cardText[0]);
    expect(EMBLEM_BEATS.trim[1]).toBeLessThan(EMBLEM_BEATS.merge[0]);
    expect(EMBLEM_BEATS.trim[0]).toBeGreaterThanOrEqual(EMBLEM_BEATS.settle[1]); // 摆正之后才开始(不在旋转中裁)
  });
});

describe('收尾', () => {
  it('演完放行;卸载后真卡上不留任何内联样式与标记,融合面随之移除', () => {
    const { tl, onDone, unmount, fused, host, foreign, other, list } = mount(TEST_EMBLEM);
    tl.progress(1);
    expect(onDone).toHaveBeenCalledTimes(1);
    unmount();
    for (const el of [fused, host, foreign, other]) expect(el.getAttribute('style') ?? '', el.dataset.name).toBe('');
    for (const el of [...host.children, ...foreign.children]) expect(el.getAttribute('style') ?? '').toBe('');
    expect(fused.hasAttribute('data-emblem-reveal')).toBe(false);
    expect(list.querySelector('[data-fusion-emblem]')).toBeNull();
  });

  it('兜底放行按标志路径的总时长算(rAF 冻结时也不卡死手势锁)', () => {
    vi.useFakeTimers();
    const { onDone } = mount(TEST_EMBLEM);
    vi.advanceTimersByTime(EMBLEM_TOTAL_MS);
    expect(onDone).not.toHaveBeenCalled();
    vi.advanceTimersByTime(700);
    expect(onDone).toHaveBeenCalled();
  });

  it('StrictMode(effect 清理后重跑):融合面只有一份,被拖卡仍从松手姿态起步', () => {
    const { list, foreign } = mount(TEST_EMBLEM, false, true);
    expect(list.querySelectorAll('[data-fusion-emblem]').length).toBe(1);
    expect(foreign.style.zIndex).toBe('30');
    expect(Number(gsap.getProperty(foreign, 'rotation'))).toBeCloseTo(3, 1);
  });
});
