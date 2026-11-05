import { useEffect, useLayoutEffect, useRef } from 'react';
import gsap from 'gsap';
import { CROSSFADE, SETTLE_MS, SETTLE_REDUCED_MS, totalMs, type ListSnapshot } from './crossfade';

// 融合入口的揉合动作(ADR-019 订正 §2 / §6 / §7;刀 2c-1 = 共用骨架 + 降级段)。
//
// **三对组合一律走三段交叉淡化**,全程在**宿主卡位那一个盒子**里完成:
//   被拖卡退场(0–160ms)→ 成品卡底色盖过宿主卡(120–380ms)→ 文字(320–460ms)。
// 三段错开:斜压着的被拖卡错开的边先退,不和卡位叠成两道;两套文字不同屏。
// reduced-motion 下走同一段(§7)。标志登记(有登记 / 无登记的分支)留给刀 2c-2。
//
// 揭晓完成后**落位**(Felix 2026-11-05 裁定选 A,见 placement.ts):成品卡往下移一个卡位到最终位置,
// 宿主卡在原位淡回,被拖卡在自己原位淡回。成品卡在揉合开始那一刻就已插进卡列(最终位置),
// 插入挤下去的卡和成品卡自己都先用 transform 反向抵消(FLIP):画面上什么都没动,
// 成品卡正好叠在宿主卡位上;落位那一段再把 transform 统统收回 0。reduced-motion 下不移动,直接就位。
//
// **只动 transform / opacity**:布局只在插入那一刻变一次(在首帧绘制之前,useLayoutEffect),
// 动画期间不读写任何布局属性。
//
// 动效预算(Motion Constraints §3):入口动作占**用户触发槽**,同屏只有一组揉合在跑(手势锁不变)。
// 它**替代**旧的五拍碎裂(五拍、世界核、四套材质与物理已整体退役,ADR-019 订正 §6)。
//
// 生命周期:真卡上的内联样式由这里写、也由这里清 —— 卸载时(演完、兜底放行、或中途进入世界)
// 一律 kill 时间线并清掉内联样式,**不 revert**:revert 会把被拖卡恢复成松手姿态。

export interface FusionMergeProps {
  /** 卡列(`.cardList`):成品卡已插在宿主紧下方。 */
  list: HTMLElement;
  /** 成品卡的组合键(卡上 `data-fusion-key`)。 */
  fusedKey: string;
  host: HTMLElement;
  /** 被拖卡:仍停在松手姿态(见 useFusionDrag 的 CommitCards)。 */
  foreign: HTMLElement;
  /** 插入成品卡之前量的卡列位置。 */
  before: ListSnapshot;
  reduced: boolean;
  /** 演完(或兜底放行)回调:调用方据此归还手势、聚焦成品卡。幂等由调用方保证。 */
  onDone: () => void;
}

/** 兜底放行的余量(rAF 冻结时 timeline 可能永不完成,ADR-018 §4.11 子模式 A)。 */
const FALLBACK_SLACK_MS = 600;

const s = (ms: number) => ms / 1000;
const span = ([a, b]: readonly [number, number]) => ({ at: s(a), dur: s(b - a) });

/** 动画期间写过的内联样式;结束时一律清掉,卡回到纯布局位置。 */
const CLEAR = 'transform,opacity,zIndex,pointerEvents,transition';

export function FusionMerge({ list, fusedKey, host, foreign, before, reduced, onDone }: FusionMergeProps) {
  const doneRef = useRef(onDone);
  useEffect(() => {
    doneRef.current = onDone;
  }, [onDone]);

  useLayoutEffect(() => {
    const card = Array.from(list.children).find(
      (el): el is HTMLElement => el instanceof HTMLElement && el.dataset.fusionKey === fusedKey,
    );
    if (!card) {
      // 量不到成品卡(理论上不会):不演,直接放行 —— 被拖卡的松手姿态照样要清。
      gsap.set(foreign, { clearProps: CLEAR });
      doneRef.current();
      return;
    }
    const texts = Array.from(card.children);

    // FLIP:插入挤下去的卡(含被拖卡,如果它在宿主下面)先反向抵消,画面上原地不动。
    const shifted: [HTMLElement, number][] = [];
    for (const el of Array.from(list.children)) {
      if (!(el instanceof HTMLElement) || el === card) continue;
      const was = before.get(el);
      if (was === undefined) continue;
      const d = was - el.offsetTop;
      if (d !== 0) shifted.push([el, d]);
    }
    const foreignShift = shifted.find(([el]) => el === foreign)?.[1] ?? 0;
    const others = shifted.filter(([el]) => el !== foreign);
    const lift = host.offsetTop - card.offsetTop; // 成品卡叠到宿主卡位上
    const touched = [card, host, foreign, ...others.map(([el]) => el)];

    // 被拖卡在宿主下面时,插入把它的布局位置也挤下去一格:松手姿态(相对布局位置)得一并抵消,
    // 否则 t=0 它会往下跳一格(480 宽录屏里抓到的,人防:末日生存拖到规则怪谈上)。
    if (foreignShift) foreign.style.setProperty('transform', `translateY(${foreignShift}px) ${foreign.style.transform}`);

    const tl = gsap.timeline({ onComplete: () => doneRef.current() });
    // 卡片自带 `transition: transform .12s`(悬停微浮):逐帧写 transform 时它会让每一帧都拖尾。
    gsap.set(touched, { transition: 'none' });
    gsap.set(card, { y: lift, opacity: 0, pointerEvents: 'none' });
    gsap.set(texts, { opacity: 0 });
    for (const [el, d] of others) gsap.set(el, { y: d });
    gsap.set(host, { pointerEvents: 'none' });

    // ① 被拖卡在松手姿态上退场。退净后清掉松手姿态,换成它自己卡位的 FLIP 抵消(看不见)。
    const out = span(CROSSFADE.out);
    tl.to(foreign, { opacity: 0, duration: out.dur, ease: 'sine.out' }, out.at);
    tl.set(foreign, { clearProps: 'transform,zIndex' }, out.at + out.dur);
    if (foreignShift) tl.set(foreign, { y: foreignShift }, out.at + out.dur);

    // ② 成品卡底色盖过宿主卡;盖实了宿主卡才隐去(同一个盒子,任何时刻只有一道外边界)。
    const fade = span(CROSSFADE.fade);
    tl.to(card, { opacity: 1, duration: fade.dur, ease: 'sine.inOut' }, fade.at);
    tl.set(host, { opacity: 0 }, fade.at + fade.dur);

    // ③ 文字。
    const text = span(CROSSFADE.text);
    tl.to(texts, { opacity: 1, duration: text.dur, ease: 'sine.out' }, text.at);

    // ④ 落位:成品卡下移一个卡位(挤下去的卡一起归 0),宿主与被拖卡各在原位淡回。
    const at = s(CROSSFADE.text[1]);
    const movers = [card, ...others.map(([el]) => el), ...(foreignShift ? [foreign] : [])];
    if (reduced) {
      tl.set(movers, { y: 0 }, at);
      tl.to([host, foreign], { opacity: 1, duration: s(SETTLE_REDUCED_MS), ease: 'sine.out' }, at);
    } else {
      tl.to(movers, { y: 0, duration: s(SETTLE_MS), ease: 'power2.out' }, at);
      tl.to([host, foreign], { opacity: 1, duration: s(SETTLE_MS), ease: 'sine.inOut' }, at);
    }

    const fallback = window.setTimeout(() => doneRef.current(), totalMs(reduced) + FALLBACK_SLACK_MS);
    return () => {
      window.clearTimeout(fallback);
      tl.kill();
      gsap.set(touched, { clearProps: CLEAR });
      gsap.set(texts, { clearProps: 'opacity' });
    };
  }, [list, fusedKey, host, foreign, before, reduced]);

  return null;
}
