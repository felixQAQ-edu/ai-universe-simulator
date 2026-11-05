import { useEffect, useLayoutEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import gsap from 'gsap';
import { CROSSFADE, SETTLE_MS, SETTLE_REDUCED_MS, layoutBox, type ListSnapshot } from './crossfade';
import { blotVar, revealMask, strokeVar, type FusionEmblem } from './emblem';
import { EmblemOverlay } from './EmblemOverlay';
import { DIM_OPACITY, EMBLEM_BEATS, HANDOFF_DY, HOLD_SCALE, mergePlan } from './plan';

// 融合入口的揉合动作(ADR-019 订正 §2 / §3 / §6 / §7;刀 2c-1 骨架 + 降级段,刀 2c-2 标志路径)。
//
// **时间线只有一套**(见 plan.ts),全程在**宿主卡位那一个盒子**里完成:
// - **有标志登记**(数据由调用方从主题注册表取来):交接 → 停顿 → 标志 → 揭晓 → 落位(约 4.5 秒)。
//   交接:被拖卡从松手姿态摆正、靠拢,其余卡淡到 22%;停顿:极轻的下压;
//   标志:两张原卡文字先退,融合面盖上来、被拖卡收进卡位,标志按登记的笔画顺序烧出来,点缀最后透出;
//   揭晓:一条斜向边界推过**真实的成品卡**(成品卡上的 CSS mask),标志与点缀退去,文字浮现,光带淡入;
//   落位与其余卡恢复一同在 4500ms 收住。
// - **没登记**(或 reduced-motion,订正 §7):三段交叉淡化 —— 被拖卡退场(0–160ms)→ 成品卡底色盖过宿主卡
//   (120–380ms)→ 文字(320–460ms)→ 落位。
// 本组件**不认识任何组合 key**:走哪条路只看传进来的 `emblem` 是不是 null。
//
// 落位(Felix 2026-11-05 裁定选 A,见 placement.ts):成品卡在揉合开始那一刻就已插进卡列(最终位置),
// 插入挤下去的卡和成品卡自己都先用 transform 反向抵消(FLIP):画面上什么都没动,成品卡正好叠在宿主卡位上;
// 落位那一段再把 transform 统统收回 0,宿主卡在原位淡回,被拖卡在自己原位淡回。reduced-motion 下不移动,直接就位。
//
// **只动 transform / opacity / mask / clip-path / CSS 自定义属性**:布局只在插入那一刻变一次(首帧绘制之前,
// useLayoutEffect),动画期间不读写任何布局属性。标志「烧出来」用 HTML 外层盒子上的 CSS mask(只重绘),
// 不用 SVG <mask>(dev 稿实测每帧一次布局,f056b75)。
//
// **clip-path 例外:宿主比成品卡高时**(Felix 2026-11-05 选定方案 A)。宿主卡钩子折两行时
// (375 宽实测:宿主 115px vs 成品卡 93px),成品卡在宿主卡位里时把布局 min-height 钉成宿主高度,
// 外边界与宿主卡逐像素重合;落位那一步用 `clip-path: inset(… round 圆角)` 把可见底边从宿主高度
// 连续收到自然高度,下面的卡同步多走这段差;收完那一帧撤钉高、撤裁切、下面的卡 y 归 0,三者互相抵消。
// 已知 1px 残差:收的过程中可见底边是裁出来的,没有卡片自己那条 1px 底边线,撤裁切那一帧它才出现。
//
// 动效预算(Motion Constraints §3):入口动作占**用户触发槽**,同屏只有一组揉合在跑(手势锁不变)。
// 标志路径**替代**旧的五拍碎裂;无登记的组合由五拍碎裂**降级为**交叉淡化(订正 §6)。
// 其余卡变暗(22%)只在标志路径上、只在揉合期间,演完恢复(订正 §1)。
//
// 生命周期:真卡上的内联样式由这里写、也由这里清 —— 卸载时(演完、兜底放行、或中途卸载)
// 一律 kill 时间线并清掉内联样式,**不 revert**:revert 会把被拖卡恢复成松手姿态。

export interface FusionMergeProps {
  /** 卡列(`.cardList`):成品卡已插在宿主紧下方。 */
  list: HTMLElement;
  /** 成品卡的组合键(卡上 `data-fusion-key`;只用来在卡列里找到它,不据它分支)。 */
  fusedKey: string;
  host: HTMLElement;
  /** 被拖卡:仍停在松手姿态(见 useFusionDrag 的 CommitCards)。 */
  foreign: HTMLElement;
  /** 被拖卡松手那一刻的屏上姿态;每次 effect 开始都按它重摆(StrictMode 下 effect 会清理后重跑)。 */
  pose: { transform: string; zIndex: string };
  /** 插入成品卡之前量的卡列位置。 */
  before: ListSnapshot;
  /** 该组合登记的标志;null = 未登记 → 交叉淡化。 */
  emblem: FusionEmblem | null;
  reduced: boolean;
  /** 演完(或兜底放行)回调:调用方据此归还手势、聚焦成品卡。幂等由调用方保证。 */
  onDone: () => void;
}

/** 兜底放行的余量(rAF 冻结时 timeline 可能永不完成,ADR-018 §4.11 子模式 A)。 */
const FALLBACK_SLACK_MS = 600;

const s = (ms: number) => ms / 1000;
const span = ([a, b]: readonly [number, number]) => ({ at: s(a), dur: s(b - a) });

/** 动画期间写过的内联样式;结束时一律清掉,卡回到纯布局位置。 */
const CLEAR = 'transform,opacity,zIndex,pointerEvents,transition,minHeight,clipPath';
/** 标志路径额外写在成品卡上的(揭晓遮罩、光带门控)。 */
const CARD_EMBLEM_PROPS = ['mask-image', '-webkit-mask-image', '--em-front', '--em-full', '--em-streak'];

export function FusionMerge({ list, fusedKey, host, foreign, pose, before, emblem, reduced, onDone }: FusionMergeProps) {
  const doneRef = useRef(onDone);
  useEffect(() => {
    doneRef.current = onDone;
  }, [onDone]);
  const overlayRef = useRef<HTMLDivElement>(null);
  const showEmblem = mergePlan(emblem, reduced).kind === 'emblem';

  useLayoutEffect(() => {
    // 先按松手姿态重摆被拖卡:第一次运行时它本来就在这儿(写一遍同值,无变化);
    // 若这是清理之后的重跑(React StrictMode 的 mount → unmount → mount),清理已把它清回原位,这里摆回来。
    foreign.style.setProperty('transition', 'none');
    foreign.style.setProperty('transform', pose.transform);
    foreign.style.setProperty('z-index', pose.zIndex);

    const plan = mergePlan(emblem, reduced);
    const card = Array.from(list.children).find(
      (el): el is HTMLElement => el instanceof HTMLElement && el.dataset.fusionKey === fusedKey,
    );
    const overlay = overlayRef.current;
    if (!card || (plan.kind === 'emblem' && !overlay)) {
      // 量不到成品卡(理论上不会):不演,直接放行 —— 被拖卡的松手姿态照样要清。
      gsap.set(foreign, { clearProps: CLEAR });
      doneRef.current();
      return;
    }
    const texts = Array.from(card.children);

    // 宿主比成品卡高 → 在宿主卡位里时钉成宿主高度(见文件头;在量 FLIP 之前钉,下面的卡按钉后的布局抵消)。
    // 一律用亚像素布局外框(见 layoutBox):取整的 offsetTop / offsetHeight 会让外边界错开零点几像素。
    const hostBox = layoutBox(host);
    const cardBox = layoutBox(card);
    const raw = hostBox.height - cardBox.height;
    const extra = raw > 0.01 ? raw : 0;
    if (extra > 0) card.style.setProperty('min-height', `${hostBox.height}px`);

    // FLIP:插入挤下去的卡(含被拖卡,如果它在宿主下面)先反向抵消,画面上原地不动。
    const shifted: [HTMLElement, number][] = [];
    for (const el of Array.from(list.children)) {
      if (!(el instanceof HTMLElement) || el === card) continue;
      const was = before.get(el);
      if (was === undefined) continue;
      const d = was - layoutBox(el).top;
      if (Math.abs(d) > 0.01) shifted.push([el, d]);
    }
    const foreignShift = shifted.find(([el]) => el === foreign)?.[1] ?? 0;
    const others = shifted.filter(([el]) => el !== foreign);
    const lift = hostBox.top - cardBox.top; // 成品卡叠到宿主卡位上(钉高不改它自己的 top)
    const touched = [card, host, foreign, ...others.map(([el]) => el)];

    // 被拖卡在宿主下面时,插入把它的布局位置也挤下去一格:松手姿态(相对布局位置)得一并抵消,
    // 否则 t=0 它会往下跳一格(480 宽录屏里抓到的:被拖卡排在宿主下面的那一对)。
    if (foreignShift) foreign.style.setProperty('transform', `translateY(${foreignShift}px) ${foreign.style.transform}`);

    const tl = gsap.timeline({ onComplete: () => doneRef.current() });
    // 卡片自带 `transition: transform .12s`(悬停微浮):逐帧写 transform 时它会让每一帧都拖尾。
    gsap.set(touched, { transition: 'none' });
    gsap.set(card, { y: lift, opacity: 0, pointerEvents: 'none' });
    gsap.set(texts, { opacity: 0 });
    for (const [el, d] of others) gsap.set(el, { y: d });
    gsap.set(host, { pointerEvents: 'none' });

    /** 标志路径另外动过的元素(结束时清)。 */
    let dimmed: HTMLElement[] = [];
    let pairTexts: Element[] = [];
    let landAt: number;

    if (plan.kind === 'emblem' && emblem && overlay) {
      const e = emblem;
      const B = EMBLEM_BEATS;
      const z = Number.parseInt(pose.zIndex, 10) || 30;
      const H = hostBox.height;
      // 被拖卡到宿主卡位的距离(按它插入后的布局位置量,松手姿态已含插入造成的那一格)。
      const toHost = hostBox.top - layoutBox(foreign).top;
      pairTexts = [...Array.from(host.children), ...Array.from(foreign.children)];

      // 融合面:叠在宿主卡位那一个盒子上,带成品卡同一套卡边(外轮廓逐像素重合)。层级高于被拖卡,成品卡再高一层。
      const listRect = list.getBoundingClientRect();
      const cs = getComputedStyle(card);
      const surface = overlay.firstElementChild as HTMLElement;
      Object.assign(overlay.style, {
        top: `${hostBox.top - (listRect.top + window.scrollY) - list.clientTop}px`,
        left: `${hostBox.left - (listRect.left + window.scrollX) - list.clientLeft}px`,
        width: `${hostBox.width}px`,
        height: `${Math.max(hostBox.height, cardBox.height)}px`,
        borderRadius: cs.borderRadius,
        zIndex: String(z + 1),
      });
      for (const side of ['Top', 'Right', 'Bottom', 'Left'] as const) {
        surface.style.setProperty(`border-${side.toLowerCase()}`, `${cs.getPropertyValue(`border-${side.toLowerCase()}-width`)} ${cs.getPropertyValue(`border-${side.toLowerCase()}-style`)} ${cs.getPropertyValue(`border-${side.toLowerCase()}-color`)}`);
      }
      surface.style.borderRadius = cs.borderRadius;

      // 成品卡:压在融合面上,一道斜向边界遮罩;光带(卡自带的 ::after)先门控为 0。
      const mask = revealMask(e.reveal);
      card.style.setProperty('z-index', String(z + 2));
      card.style.setProperty('--em-front', `${e.reveal.from}%`);
      card.style.setProperty('--em-full', '0');
      card.style.setProperty('--em-streak', '0');
      card.style.setProperty('mask-image', mask);
      card.style.setProperty('-webkit-mask-image', mask);
      card.dataset.emblemReveal = '';

      // 标志初值:笔画、焦褐团、点缀都未出现,整枚可见。
      const init: Record<string, number> = { '--em-accent': 0, '--em-ink': 1 };
      e.mark.strokes.forEach((_, i) => (init[strokeVar(i)] = 0));
      e.mark.blots.forEach((_, i) => (init[blotVar(i)] = 0));
      gsap.set(overlay, { opacity: 0, ...init });

      // 其余卡:卡列里除这一组之外的卡(提示条、融合面这类 aria-hidden 的不算),淡到 22%,演完回到原值。
      dimmed = Array.from(list.children).filter(
        (el): el is HTMLElement =>
          el instanceof HTMLElement && el !== card && el !== host && el !== foreign && el.getAttribute('aria-hidden') !== 'true',
      );
      const restoreTo = dimmed.map((el) => getComputedStyle(el).opacity);

      // 交接:从松手姿态摆正、靠拢(GSAP 从当前内联 transform 解析出起点);其余卡淡下。
      gsap.set(foreign, { transform: foreign.style.transform });
      const st = span(B.settle);
      tl.to(foreign, { x: 0, y: toHost + HANDOFF_DY * H, rotation: 0, scale: 1, duration: st.dur, ease: 'power2.out' }, st.at);
      const dm = span(B.dim);
      tl.to(dimmed, { opacity: DIM_OPACITY, duration: dm.dur, ease: 'power1.out' }, dm.at);

      // 停顿:极轻的下压,停住但不冻住。
      const hd = span(B.hold);
      tl.to(foreign, { scale: HOLD_SCALE, duration: hd.dur, ease: 'sine.inOut' }, hd.at);

      // 标志:两张原卡文字先退 → 融合面(连同压在上面的成品卡底部那一窄条)淡入 → 被拖卡收进卡位、后段淡出。
      const ct = span(B.cardText);
      tl.to(pairTexts, { opacity: 0, duration: ct.dur, ease: 'sine.in' }, ct.at);
      const mg = span(B.merge);
      tl.to([overlay, card], { opacity: 1, duration: mg.dur, ease: 'sine.inOut' }, mg.at);
      const tk = span(B.tuck);
      tl.to(foreign, { y: toHost, scale: 1, duration: tk.dur, ease: 'sine.in' }, tk.at);
      tl.to(foreign, { opacity: 0, duration: tk.dur * 0.45, ease: 'sine.in' }, tk.at + tk.dur * 0.55);
      // 收净:宿主原卡隐去;两张原卡在看不见时回到自己的卡位、文字复原(落位时原位淡回)。
      const tuckEnd = tk.at + tk.dur;
      tl.set(host, { opacity: 0 }, tuckEnd);
      tl.set(foreign, { clearProps: 'transform,zIndex' }, tuckEnd);
      if (foreignShift) tl.set(foreign, { y: foreignShift }, tuckEnd);
      tl.set(pairTexts, { opacity: 1 }, tuckEnd);

      // 重角段:登记了 lead 的笔画先烧出一截,'corner' 焦褐团随之冒出(先快后慢)。
      const co = span(B.corner);
      e.mark.strokes.forEach((sk, i) => {
        if (sk.lead > 0) tl.to(overlay, { [strokeVar(i)]: sk.lead, duration: co.dur, ease: 'sine.out' }, co.at);
      });
      e.mark.blots.forEach((b, i) => {
        if (b.window === 'corner') tl.to(overlay, { [blotVar(i)]: 1, duration: co.dur, ease: b.ease }, co.at);
      });

      // 烧边段:各笔画按登记的时间窗从 lead 烧到 1;其余焦褐团按各自的窗洇开。
      const ed = span(B.edges);
      e.mark.strokes.forEach((sk, i) => {
        tl.to(overlay, { [strokeVar(i)]: 1, duration: ed.dur * (sk.burn[1] - sk.burn[0]), ease: sk.ease }, ed.at + ed.dur * sk.burn[0]);
      });
      e.mark.blots.forEach((b, i) => {
        if (b.window !== 'corner') {
          tl.to(overlay, { [blotVar(i)]: 1, duration: ed.dur * (b.window[1] - b.window[0]), ease: b.ease }, ed.at + ed.dur * b.window[0]);
        }
      });

      // 点缀最后透出。
      const ac = span(B.accent);
      tl.to(overlay, { '--em-accent': 1, duration: ac.dur, ease: 'sine.inOut' }, ac.at);

      // 揭晓:边界从标志下沿之下推过整张(先慢后快再收);点缀先退,标志随边界经过退去;
      // 止点整片补满(只当一道保险,不提前淡),融合面隐去。
      const rv = span(B.reveal);
      tl.to(overlay, { '--em-accent': 0, duration: rv.dur * 0.15, ease: 'sine.out' }, rv.at);
      tl.to(card, { '--em-front': `${e.reveal.to}%`, duration: rv.dur, ease: 'power1.inOut' }, rv.at);
      tl.to(overlay, { '--em-ink': 0, duration: rv.dur * 0.65, ease: 'sine.inOut' }, rv.at + rv.dur * 0.15);
      tl.set(card, { '--em-full': 1 }, rv.at + rv.dur);
      tl.set(overlay, { opacity: 0 }, rv.at + rv.dur);

      // 文字:卡面基本成形后浮现;光带随后。
      const tx = span(B.text);
      tl.to(texts, { opacity: 1, duration: tx.dur, ease: 'sine.out' }, tx.at);
      const sk = span(B.streak);
      tl.to(card, { '--em-streak': 1, duration: sk.dur, ease: 'sine.inOut' }, sk.at);

      // 其余卡恢复。
      const rs = span(B.restore);
      dimmed.forEach((el, i) => tl.to(el, { opacity: Number(restoreTo[i]), duration: rs.dur, ease: 'sine.inOut' }, rs.at));

      landAt = s(B.land[0]);
    } else {
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

      landAt = s(CROSSFADE.text[1]);
    }

    // ④ 落位:成品卡下移一个卡位(挤下去的卡一起归 0),宿主与被拖卡各在原位淡回。两条路径共用。
    const at = landAt;
    const below = [...others.map(([el]) => el), ...(foreignShift ? [foreign] : [])];
    const movers = [card, ...below];
    /** 撤钉高:成品卡回到自然高度,下面的卡在布局里上移 extra —— 调用方在同一帧把它们的 y 补上。 */
    const unpin = () => gsap.set(card, { clearProps: 'minHeight,clipPath' });
    if (reduced) {
      // 不移动:文字到齐那一刻直接就位(高度差也在这一刻一起归位,本就是瞬时就位)。
      if (extra > 0) tl.call(unpin, [], at);
      tl.set(movers, { y: 0 }, at);
      tl.to([host, foreign], { opacity: 1, duration: s(SETTLE_REDUCED_MS), ease: 'sine.out' }, at);
    } else {
      const settle = s(SETTLE_MS);
      tl.to(card, { y: 0, duration: settle, ease: 'power2.out' }, at);
      // 下面的卡收到「钉高时的布局」里的 -extra,正是撤钉后的自然位置。
      tl.to(below, { y: extra > 0 ? -extra : 0, duration: settle, ease: 'power2.out' }, at);
      if (extra > 0) {
        const r = getComputedStyle(card).borderBottomLeftRadius || '0px';
        tl.fromTo(
          card,
          { clipPath: `inset(0px 0px 0px 0px round ${r})` },
          { clipPath: `inset(0px 0px ${extra}px 0px round ${r})`, duration: settle, ease: 'power2.out', immediateRender: false },
          at,
        );
        tl.call(
          () => {
            unpin();
            gsap.set(below, { y: 0 });
          },
          [],
          at + settle,
        );
      }
      tl.to([host, foreign], { opacity: 1, duration: settle, ease: 'sine.inOut' }, at);
    }

    const fallback = window.setTimeout(() => doneRef.current(), plan.totalMs + FALLBACK_SLACK_MS);
    return () => {
      window.clearTimeout(fallback);
      tl.kill();
      gsap.set(touched, { clearProps: CLEAR });
      gsap.set(texts, { clearProps: 'opacity' });
      if (dimmed.length) gsap.set(dimmed, { clearProps: 'opacity' });
      if (pairTexts.length) gsap.set(pairTexts, { clearProps: 'opacity' });
      for (const p of CARD_EMBLEM_PROPS) card.style.removeProperty(p);
      delete card.dataset.emblemReveal;
    };
  }, [list, fusedKey, host, foreign, pose, before, emblem, reduced]);

  return showEmblem && emblem ? createPortal(<EmblemOverlay ref={overlayRef} emblem={emblem} />, list) : null;
}
