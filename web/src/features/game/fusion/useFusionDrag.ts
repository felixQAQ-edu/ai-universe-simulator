import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import {
  DEFAULT_TUNABLES,
  autoScrollVelocity,
  classifyMove,
  findTarget,
  isArmed,
  overlapRatio,
  rectCenter,
  type Candidate,
  type DragTunables,
  type Rect,
} from './drag';

// 融合入口拖拽手势的**接线层**(判据全在 drag.ts,这里只管指针事件与 DOM)。
// 线 B1 原型的实现整体搬入,去掉原型专有的读数条 / 调参面板 / 加长列表。
//
// 三个移动端要点(ADR-018 §4.20,B1 真机验过,别重踩):
//  ① `touch-action: pan-y`(见 fusion.module.css):纵向滚动仍走浏览器原生(带惯性),
//     横向留给我们判定 —— 写 `none` 等于没收玩家的默认手势。
//  ② 抓起之后靠 **非 passive 的 touchmove + preventDefault** 挡住页面滚动:
//     `touch-action` 在触摸序列开始时就定死了,中途改它无效。监听器 pointerdown 挂上、
//     手势结束即摘(常驻会让每次普通滚动都过一遍主线程)。
//  ③ 逐帧量**直接写 DOM**(卡片 transform)而不是 setState:每帧重渲染会污染滚动手感。
//     只有离散变化(抓起哪张 / 命中哪张 / 是否可提交)才 setState。

interface DragSession {
  pointerId: number;
  id: string;
  el: HTMLElement;
  origRect: Rect;
  startPageX: number;
  startPageY: number;
  lastClientX: number;
  lastClientY: number;
  candidates: Candidate[];
  targetId: string | null;
  dwellStart: number | null;
  armed: boolean;
  valid: boolean;
  raf: number | null;
  lastFrameT: number;
}

interface PressSession {
  pointerId: number;
  id: string;
  el: HTMLElement;
  startClientX: number;
  startClientY: number;
  lastClientX: number;
  lastClientY: number;
  timer: number | null;
  /** 已判为滚动:这一次触摸就此作废 —— 既不再判定,也不许中途改判成拖拽。 */
  abandoned: boolean;
}

/** 归位过渡时长(ms);reduced-motion 下为 0(瞬时归位)。 */
const RETURN_MS = 260;

/** 清掉磁吸/排斥留下的位移(拖动卡自己除外 —— 它的 transform 由逐帧写)。 */
function clearTargetTransforms(cards: Map<string, HTMLElement>, except: HTMLElement | null): void {
  for (const [, el] of cards) {
    if (el === except) continue;
    el.style.transform = '';
  }
}

export interface FusionDragView {
  /** 正被拖起的卡 id(null = 无拖拽)。 */
  draggingId: string | null;
  /** 当前命中的目标卡 id。 */
  targetId: string | null;
  /** 该组合是否合法(非法 → 排斥回弹,不吸附)。 */
  valid: boolean;
  /** 是否已进入可提交态(松手即提交)。 */
  armed: boolean;
}

const IDLE_VIEW: FusionDragView = { draggingId: null, targetId: null, valid: false, armed: false };

export interface UseFusionDragOptions {
  /** 这一对能不能揉(host = 被拖到的那张,foreign = 被拖者)。 */
  canFuse: (host: string, foreign: string) => boolean;
  /** 松手且可提交时回调(host 在前 —— 有序双值直接喂 init)。 */
  onCommit: (host: string, foreign: string) => void;
  /**
   * 松手在**非法目标**上时回调(排斥回弹由手势层做,这里只是给一句提示的机会)。
   * 刻意在**松手**时报而不是悬停时报:悬停即报会让玩家路过一张卡就被念一句。
   */
  onReject?: (host: string, foreign: string) => void;
  /** false = 手势整体停摆(揉合动画进行中:同时只允许一组融合动画运行)。 */
  enabled?: boolean;
  /** reduced-motion:归位不做过渡。 */
  reduced?: boolean;
  tunables?: DragTunables;
}

export interface FusionDragApi {
  view: FusionDragView;
  /** 卡片 ref 回调:登记 / 注销该卡的 DOM 节点。 */
  registerCard: (id: string) => (el: HTMLElement | null) => void;
  /** 挂在卡片上的 pointerdown。 */
  onPointerDown: (id: string, e: ReactPointerEvent<HTMLElement>) => void;
  /** 抓起后必须吞掉随后的 click(否则松手即进世界)。 */
  shouldSwallowClick: (id: string) => boolean;
}

export function useFusionDrag({
  canFuse,
  onCommit,
  onReject,
  enabled = true,
  reduced = false,
  tunables = DEFAULT_TUNABLES,
}: UseFusionDragOptions): FusionDragApi {
  const [view, setView] = useState<FusionDragView>(IDLE_VIEW);
  const viewRef = useRef(view);
  useEffect(() => {
    viewRef.current = view;
  }, [view]);

  // ref 镜像一律在 effect 里更新(渲染期写 ref 会被 StrictMode 双调用坑到,ADR-018 §4.9 同族)。
  const canFuseRef = useRef(canFuse);
  const onCommitRef = useRef(onCommit);
  const onRejectRef = useRef(onReject);
  const enabledRef = useRef(enabled);
  const reducedRef = useRef(reduced);
  const tunablesRef = useRef(tunables);
  useEffect(() => {
    canFuseRef.current = canFuse;
    onCommitRef.current = onCommit;
    onRejectRef.current = onReject;
    enabledRef.current = enabled;
    reducedRef.current = reduced;
    tunablesRef.current = tunables;
  }, [canFuse, onCommit, onReject, enabled, reduced, tunables]);

  const cardEls = useRef(new Map<string, HTMLElement>());
  const press = useRef<PressSession | null>(null);
  const drag = useRef<DragSession | null>(null);
  /** 抓起过的卡:松手后那一次 click 必须吞掉。 */
  const swallowClick = useRef<string | null>(null);
  const listeners = useRef<(() => void)[]>([]);

  const registerCard = useCallback(
    (id: string) => (el: HTMLElement | null) => {
      if (el) cardEls.current.set(id, el);
      else cardEls.current.delete(id);
    },
    [],
  );

  const detachAll = useCallback(() => {
    for (const off of listeners.current) off();
    listeners.current = [];
  }, []);

  const clearPressTimer = () => {
    const p = press.current;
    if (p?.timer != null) {
      window.clearTimeout(p.timer);
      p.timer = null;
    }
  };

  const resetTargetStyles = useCallback(() => {
    clearTargetTransforms(cardEls.current, drag.current?.el ?? null);
  }, []);

  /** 逐帧:算位移 → 写 transform → 判目标 → 磁吸/排斥。 */
  const applyFrame = useCallback(() => {
    const d = drag.current;
    if (!d) return;
    const T = tunablesRef.current;
    const dx = d.lastClientX + window.scrollX - d.startPageX;
    const dy = d.lastClientY + window.scrollY - d.startPageY;
    d.el.style.transform = `translate3d(${dx.toFixed(1)}px, ${dy.toFixed(1)}px, 0) scale(1.03)`;

    const cur: Rect = {
      left: d.origRect.left + dx,
      top: d.origRect.top + dy,
      width: d.origRect.width,
      height: d.origRect.height,
    };
    const tid = findTarget(cur, d.candidates);
    const now = performance.now();
    if (tid !== d.targetId) {
      d.targetId = tid;
      d.dwellStart = tid ? now : null;
      resetTargetStyles();
    }
    const dwell = d.dwellStart == null ? 0 : now - d.dwellStart;
    const targetRect = tid ? d.candidates.find((c) => c.id === tid)?.rect : undefined;
    const ov = targetRect ? overlapRatio(cur, targetRect) : 0;
    // 方向语义:被拖到的那张是 host,手上这张是 foreign(ADR-019)。
    const valid = tid ? canFuseRef.current(tid, d.id) : false;
    const armed = valid && isArmed(ov, dwell, T);

    // 磁吸(合法:目标轻微倾向拖动卡)/ 排斥(非法:轻微推开)——最朴素的位移,不做材质变化。
    if (tid && targetRect) {
      const el = cardEls.current.get(tid);
      if (el) {
        const a = rectCenter(cur);
        const b = rectCenter(targetRect);
        const vx = a.x - b.x;
        const vy = a.y - b.y;
        const len = Math.hypot(vx, vy) || 1;
        const k = valid ? 5 : -5;
        const rot = valid ? (vx / len) * 1.2 : 0;
        el.style.transform = `translate3d(${((vx / len) * k).toFixed(1)}px, ${((vy / len) * k).toFixed(1)}px, 0) rotate(${rot.toFixed(2)}deg)`;
      }
    }

    const v0 = viewRef.current;
    if (tid !== v0.targetId || armed !== v0.armed || valid !== v0.valid) {
      setView((v) => ({ ...v, targetId: tid, valid, armed }));
    }
    d.armed = armed;
    d.valid = valid;
  }, [resetTargetStyles]);

  const stopDrag = useCallback(
    (commit: boolean) => {
      const d = drag.current;
      if (!d) return;
      if (d.raf != null) cancelAnimationFrame(d.raf);
      drag.current = null;

      const el = d.el;
      const dur = reducedRef.current ? 0 : RETURN_MS;
      el.style.transition = dur ? `transform ${dur}ms cubic-bezier(.2,.7,.3,1)` : '';
      el.style.transform = 'translate3d(0,0,0) scale(1)';
      window.setTimeout(() => {
        el.style.transition = '';
        el.style.transform = '';
        el.style.zIndex = '';
      }, dur + 20);

      resetTargetStyles();
      setView(IDLE_VIEW);

      // 提交放在归位之后:揉合动画会自己接管两张卡的呈现。
      if (commit && d.targetId) {
        if (d.armed && d.valid) onCommitRef.current(d.targetId, d.id);
        else if (!d.valid) onRejectRef.current?.(d.targetId, d.id);
      }
    },
    [resetTargetStyles],
  );

  const beginDrag = useCallback(() => {
    const p = press.current;
    if (!p || drag.current || !enabledRef.current) return;
    clearPressTimer();

    const r = p.el.getBoundingClientRect();
    const sx = window.scrollX;
    const sy = window.scrollY;

    const candidates: Candidate[] = [];
    for (const [id, el] of cardEls.current) {
      if (id === p.id) continue;
      const cr = el.getBoundingClientRect();
      candidates.push({
        id,
        rect: { left: cr.left + sx, top: cr.top + sy, width: cr.width, height: cr.height },
      });
    }

    drag.current = {
      pointerId: p.pointerId,
      id: p.id,
      el: p.el,
      origRect: { left: r.left + sx, top: r.top + sy, width: r.width, height: r.height },
      startPageX: p.lastClientX + sx,
      startPageY: p.lastClientY + sy,
      lastClientX: p.lastClientX,
      lastClientY: p.lastClientY,
      candidates,
      targetId: null,
      dwellStart: null,
      armed: false,
      valid: false,
      raf: null,
      lastFrameT: performance.now(),
    };

    p.el.style.transition = '';
    p.el.style.zIndex = '30';
    swallowClick.current = p.id;
    try {
      p.el.setPointerCapture(p.pointerId);
    } catch {
      /* 捕获失败不致命:move/up 本来就收在 window 上 */
    }
    navigator.vibrate?.(8);
    setView({ draggingId: p.id, targetId: null, valid: false, armed: false });

    // 边缘自动滚动:独立 rAF 循环(手指不动也要能持续滚)。
    const loop = () => {
      const d = drag.current;
      if (!d) return;
      const now = performance.now();
      const dt = Math.min(0.05, (now - d.lastFrameT) / 1000);
      d.lastFrameT = now;
      const v = autoScrollVelocity(d.lastClientY, window.innerHeight, tunablesRef.current);
      if (v !== 0) {
        window.scrollBy(0, v * dt);
        applyFrame();
      }
      d.raf = requestAnimationFrame(loop);
    };
    drag.current.raf = requestAnimationFrame(loop);
    applyFrame();
  }, [applyFrame]);

  const endPress = useCallback(() => {
    clearPressTimer();
    press.current = null;
    detachAll();
  }, [detachAll]);

  const onPointerDown = useCallback(
    (id: string, e: ReactPointerEvent<HTMLElement>) => {
      if (!enabledRef.current) return;
      if (drag.current || press.current) return;
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      const el = e.currentTarget as HTMLElement;

      press.current = {
        pointerId: e.pointerId,
        id,
        el,
        startClientX: e.clientX,
        startClientY: e.clientY,
        lastClientX: e.clientX,
        lastClientY: e.clientY,
        timer: window.setTimeout(() => beginDrag(), tunablesRef.current.longPressMs),
        abandoned: false,
      };

      const onMove = (ev: PointerEvent) => {
        const p = press.current;
        const d = drag.current;
        if (d) {
          if (ev.pointerId !== d.pointerId) return;
          d.lastClientX = ev.clientX;
          d.lastClientY = ev.clientY;
          applyFrame();
          return;
        }
        if (!p || ev.pointerId !== p.pointerId || p.abandoned) return;
        p.lastClientX = ev.clientX;
        p.lastClientY = ev.clientY;
        const verdict = classifyMove(
          ev.clientX - p.startClientX,
          ev.clientY - p.startClientY,
          tunablesRef.current,
        );
        if (verdict === 'drag') beginDrag();
        else if (verdict === 'scroll') {
          clearPressTimer();
          p.abandoned = true; // 一次触摸只判一次,判完不许再改判成拖拽
        }
      };
      const onUp = (ev: PointerEvent) => {
        if (drag.current) {
          if (ev.pointerId !== drag.current.pointerId) return;
          stopDrag(true);
        }
        endPress();
      };
      const onCancel = (ev: PointerEvent) => {
        if (drag.current) {
          if (ev.pointerId !== drag.current.pointerId) return;
          stopDrag(false);
        }
        endPress();
      };
      // 抓起后靠它挡住页面滚动(touch-action 中途改无效);手势结束即摘。
      const onTouchMove = (ev: TouchEvent) => {
        if (drag.current) ev.preventDefault();
      };

      window.addEventListener('pointermove', onMove);
      window.addEventListener('pointerup', onUp);
      window.addEventListener('pointercancel', onCancel);
      document.addEventListener('touchmove', onTouchMove, { passive: false });
      listeners.current = [
        () => window.removeEventListener('pointermove', onMove),
        () => window.removeEventListener('pointerup', onUp),
        () => window.removeEventListener('pointercancel', onCancel),
        () => document.removeEventListener('touchmove', onTouchMove),
      ];
    },
    [applyFrame, beginDrag, endPress, stopDrag],
  );

  const shouldSwallowClick = useCallback((id: string) => {
    if (swallowClick.current !== id) return false;
    swallowClick.current = null;
    return true;
  }, []);

  // unmount 清理:摘监听、停 rAF、清计时器(留一条在途 = 下一屏被幽灵手势打扰)。
  useEffect(
    () => () => {
      if (drag.current?.raf != null) cancelAnimationFrame(drag.current.raf);
      drag.current = null;
      clearPressTimer();
      press.current = null;
      detachAll();
    },
    [detachAll],
  );

  return { view, registerCard, onPointerDown, shouldSwallowClick };
}
