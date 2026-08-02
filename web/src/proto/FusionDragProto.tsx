import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import {
  DEFAULT_TUNABLES,
  autoScrollVelocity,
  baseOf,
  classifyMove,
  comboAllowed,
  commitLine,
  findTarget,
  isArmed,
  overlapRatio,
  protoWorlds,
  rectCenter,
  type Candidate,
  type DragTunables,
  type Rect,
} from './fusionDrag';
import { cardTheme } from '../features/game/theme/registry';
import game from '../features/game/game.module.css';
import styles from './proto.module.css';

// 线 B1 · 融合入口手势可行性原型(**仅 `?proto=fusion` 可达,不进生产路径**)。
//
// 只验手势,不做视觉:抓起 = 抬起 + 阴影;磁吸 = 边框高亮 + 轻微位移;
// 提交 = 打印一行文本(不生成世界、不调 init、零网络调用)。
//
// 三个实现要点(都是移动端拖拽的老坑,写在这里免得下一个人重踩):
//  ① `touch-action: pan-y`(见 proto.module.css):纵向滚动仍走浏览器原生(带惯性),
//     横向留给我们判定 —— 若图省事写 `none`,等于把玩家的默认手势整个没收。
//  ② 抓起之后靠 **非 passive 的 touchmove + preventDefault** 挡住页面滚动:
//     `touch-action` 在触摸序列开始时就定死了,中途改它无效。监听器在 pointerdown
//     时挂上、手势结束即摘,不常驻(常驻会让每次滚动都过一遍主线程)。
//  ③ 读数用**直接写 DOM**(卡片 transform、HUD 文本)而不是 setState:
//     每帧 setState 会让整页重渲染,那会污染我们正要测的滚动手感
//     —— 观测工具不该扰动被观测对象(ADR-018 §4.14 同族)。

interface DragSession {
  pointerId: number;
  id: string;
  el: HTMLElement;
  /** 原位矩形(页面坐标)。 */
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
  grabbedAt: number;
  maxMove: number;
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
  /** 已判为滚动:这一次触摸就此作废 —— 既不再计数,也不许中途再改判成拖拽。 */
  abandoned: boolean;
}

interface Counters {
  grab: number;
  scrollWon: number;
  commit: number;
  cancel: number;
  falseGrab: number;
  reject: number;
}

const ZERO_COUNTERS: Counters = { grab: 0, scrollWon: 0, commit: 0, cancel: 0, falseGrab: 0, reject: 0 };

/** 抓起后 <320ms 且几乎没动就松手 —— 大概率是「本想滚动/点击却被抓起」。 */
const FALSE_GRAB_MS = 320;
const FALSE_GRAB_PX = 16;

const RETURN_MS = 260;

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
}

export function FusionDragProto() {
  const [tunables, setTunables] = useState<DragTunables>(DEFAULT_TUNABLES);
  const [tunerOpen, setTunerOpen] = useState(false);
  /** 加长列表:六张卡在手机上只滚得动一点点,验不了「目标卡在屏幕外」那条(见 protoWorlds)。 */
  const [longList, setLongList] = useState(false);
  const [counters, setCounters] = useState<Counters>(ZERO_COUNTERS);
  const [log, setLog] = useState<readonly string[]>([]);
  const [result, setResult] = useState<string | null>(null);
  /** 仅在离散变化时 setState:抓起哪张、命中哪张、是否可提交。逐帧数据一律走 HUD 直写。 */
  const [view, setView] = useState<{
    draggingId: string | null;
    targetId: string | null;
    valid: boolean;
    armed: boolean;
    ghost: { top: number; left: number; width: number; height: number } | null;
  }>({ draggingId: null, targetId: null, valid: false, armed: false, ghost: null });
  /** view 的 ref 镜像:逐帧比较只读它,免得 applyFrame 因 view 变化而重建 / 读到过期闭包。 */
  const viewRef = useRef(view);
  useEffect(() => {
    viewRef.current = view;
  }, [view]);

  // ref 镜像一律在 effect 里更新(渲染期写 ref 会被 StrictMode 双调用坑到,ADR-018 §4.9 同族)。
  // 时序安全:effect 在提交后立刻跑,而手势只可能发生在提交之后。
  const tunablesRef = useRef(tunables);
  useEffect(() => {
    tunablesRef.current = tunables;
  }, [tunables]);

  const listRef = useRef<HTMLDivElement | null>(null);
  const hudRef = useRef<HTMLDivElement | null>(null);
  const cardEls = useRef(new Map<string, HTMLElement>());
  const press = useRef<PressSession | null>(null);
  const drag = useRef<DragSession | null>(null);
  const t0 = useRef(0);

  const pushLog = useCallback((line: string) => {
    const at = ((Date.now() - t0.current) / 1000).toFixed(1);
    setLog((l) => [`${at}s ${line}`, ...l].slice(0, 6));
  }, []);

  const writeHud = useCallback((text: string) => {
    if (hudRef.current) hudRef.current.textContent = text;
  }, []);

  // ── 全局监听器(手势期间才挂;摘干净)──────────────────────────────
  const listeners = useRef<(() => void)[]>([]);
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
    for (const [, el] of cardEls.current) {
      if (drag.current && el === drag.current.el) continue;
      el.style.transform = '';
    }
  }, []);

  /** 逐帧:算位移 → 写 transform → 判目标 → 磁吸/排斥 → 写 HUD。 */
  const applyFrame = useCallback(() => {
    const d = drag.current;
    if (!d) return;
    const T = tunablesRef.current;
    const pageX = d.lastClientX + window.scrollX;
    const pageY = d.lastClientY + window.scrollY;
    const dx = pageX - d.startPageX;
    const dy = pageY - d.startPageY;
    d.maxMove = Math.max(d.maxMove, Math.hypot(dx, dy));
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
    const valid = tid ? comboAllowed(d.id, tid) : false;
    const armed = valid && isArmed(ov, dwell, T);

    // 磁吸(合法:目标轻微倾向拖动卡)/ 排斥(非法:轻微推开)。最朴素的位移,不做材质变化。
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
    if (armed !== d.armed) {
      d.armed = armed;
      if (armed) pushLog(`可提交 ← ${tid}(重叠 ${(ov * 100) | 0}% / 停留 ${dwell | 0}ms)`);
    }
    d.valid = valid;

    const vel = autoScrollVelocity(d.lastClientY, window.innerHeight, T);
    writeHud(
      `拖拽中 ${d.id}  dx${dx.toFixed(0)} dy${dy.toFixed(0)}\n` +
        `目标 ${tid ?? '—'} ${tid ? (valid ? (armed ? '· 可提交' : '· 合法') : '· 无效组合') : ''}  重叠 ${(ov * 100) | 0}%  停留 ${dwell | 0}ms\n` +
        `自动滚动 ${vel.toFixed(0)}px/s  scrollY ${window.scrollY | 0}`,
    );
  }, [pushLog, resetTargetStyles, writeHud]);

  const stopDrag = useCallback(
    (commit: boolean) => {
      const d = drag.current;
      if (!d) return;
      if (d.raf != null) cancelAnimationFrame(d.raf);
      drag.current = null;

      const held = performance.now() - d.grabbedAt;
      if (commit && d.armed && d.valid && d.targetId) {
        const line = commitLine(d.id, d.targetId);
        setResult(line);
        pushLog(`提交 ${line}`);
        setCounters((c) => ({ ...c, commit: c.commit + 1 }));
      } else {
        const rejected = commit && !!d.targetId && !d.valid;
        pushLog(rejected ? `无效组合,回弹(${d.id} → ${d.targetId})` : '取消,归位');
        setCounters((c) => ({
          ...c,
          cancel: c.cancel + 1,
          reject: c.reject + (rejected ? 1 : 0),
          falseGrab: c.falseGrab + (held < FALSE_GRAB_MS && d.maxMove < FALSE_GRAB_PX ? 1 : 0),
        }));
      }

      const el = d.el;
      const dur = prefersReducedMotion() ? 0 : RETURN_MS;
      el.style.transition = dur ? `transform ${dur}ms cubic-bezier(.2,.7,.3,1)` : '';
      el.style.transform = 'translate3d(0,0,0) scale(1)';
      window.setTimeout(() => {
        el.style.transition = '';
        el.style.transform = '';
        el.style.zIndex = '';
      }, dur + 20);

      resetTargetStyles();
      setView({ draggingId: null, targetId: null, valid: false, armed: false, ghost: null });
      writeHud('待命 · 上下滑=滚动,长按或横拖=抓起卡片');
    },
    [pushLog, resetTargetStyles, writeHud],
  );

  const beginDrag = useCallback(() => {
    const p = press.current;
    if (!p || drag.current) return;
    clearPressTimer();

    const r = p.el.getBoundingClientRect();
    const sx = window.scrollX;
    const sy = window.scrollY;
    const origRect: Rect = { left: r.left + sx, top: r.top + sy, width: r.width, height: r.height };

    const candidates: Candidate[] = [];
    for (const [id, el] of cardEls.current) {
      if (id === p.id) continue;
      const cr = el.getBoundingClientRect();
      candidates.push({
        id,
        rect: { left: cr.left + sx, top: cr.top + sy, width: cr.width, height: cr.height },
      });
    }

    const listRect = listRef.current?.getBoundingClientRect();
    const ghost = listRect
      ? { top: r.top - listRect.top, left: r.left - listRect.left, width: r.width, height: r.height }
      : null;

    drag.current = {
      pointerId: p.pointerId,
      id: p.id,
      el: p.el,
      origRect,
      startPageX: p.lastClientX + sx,
      startPageY: p.lastClientY + sy,
      lastClientX: p.lastClientX,
      lastClientY: p.lastClientY,
      candidates,
      targetId: null,
      dwellStart: null,
      armed: false,
      valid: false,
      grabbedAt: performance.now(),
      maxMove: 0,
      raf: null,
      lastFrameT: performance.now(),
    };

    p.el.style.transition = '';
    p.el.style.zIndex = '30';
    try {
      p.el.setPointerCapture(p.pointerId);
    } catch {
      /* 捕获失败不致命:我们本来就在 window 上收 move/up */
    }
    navigator.vibrate?.(8);

    setCounters((c) => ({ ...c, grab: c.grab + 1 }));
    pushLog(`抓起 ${p.id}`);
    setView({ draggingId: p.id, targetId: null, valid: false, armed: false, ghost });

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
  }, [applyFrame, pushLog]);

  const endPress = useCallback(() => {
    clearPressTimer();
    press.current = null;
    detachAll();
  }, [detachAll]);

  const onPointerDown = useCallback(
    (id: string, e: ReactPointerEvent<HTMLElement>) => {
      if (drag.current || press.current) return;
      if (e.button !== 0 && e.pointerType === 'mouse') return;
      const el = e.currentTarget as HTMLElement;
      const T = tunablesRef.current;

      press.current = {
        pointerId: e.pointerId,
        id,
        el,
        startClientX: e.clientX,
        startClientY: e.clientY,
        lastClientX: e.clientX,
        lastClientY: e.clientY,
        timer: window.setTimeout(() => beginDrag(), T.longPressMs),
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
          p.abandoned = true; // 一次触摸只判一次,且判完不许再改判成拖拽
          setCounters((c) => ({ ...c, scrollWon: c.scrollWon + 1 }));
          writeHud('判为滚动 · 长按取消');
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
          pushLog('pointercancel(浏览器接管手势)');
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
    [applyFrame, beginDrag, endPress, pushLog, stopDrag, writeHud],
  );

  useEffect(() => {
    t0.current = Date.now();
    writeHud('待命 · 上下滑=滚动,长按或横拖=抓起卡片');
    return () => {
      if (drag.current?.raf != null) cancelAnimationFrame(drag.current.raf);
      clearPressTimer();
      detachAll();
      drag.current = null;
      press.current = null;
    };
  }, [detachAll, writeHud]);

  const setTunable = (k: keyof DragTunables, delta: number, min: number, max: number) =>
    setTunables((t) => ({ ...t, [k]: Math.min(max, Math.max(min, +(t[k] + delta).toFixed(2))) }));

  return (
    <main className={styles.protoScreen}>
      <header className={styles.protoHeader}>
        <h1>融合入口 · 拖拽手势原型</h1>
        <p className={styles.protoNote}>
          把一张世界卡拖到另一张上:被拖者 = foreign,承接者 = host。
          本页只验手势 —— 提交后只打印一行文本,不生成世界、不调后端。
        </p>
      </header>

      <div className={styles.tuner}>
        <button type="button" className={styles.tunerToggle} onClick={() => setTunerOpen((o) => !o)}>
          {tunerOpen ? '▾' : '▸'} 阈值调参(长按 {tunables.longPressMs}ms · 重叠{' '}
          {(tunables.overlapRatio * 100) | 0}% · 停留 {tunables.dwellMs}ms)
        </button>
        {tunerOpen && (
          <div className={styles.tunerBody}>
            <TunerRow
              label="长按抓起 (ms)"
              value={tunables.longPressMs}
              onStep={(s) => setTunable('longPressMs', s * 20, 80, 500)}
            />
            <TunerRow
              label="长按抖动容差 (px)"
              value={tunables.moveTolerancePx}
              onStep={(s) => setTunable('moveTolerancePx', s * 2, 2, 24)}
            />
            <TunerRow
              label="横拖直接抓起 (px)"
              value={tunables.dirLockPx}
              onStep={(s) => setTunable('dirLockPx', s * 2, 4, 40)}
            />
            <TunerRow
              label="提交重叠比 (%)"
              value={(tunables.overlapRatio * 100) | 0}
              onStep={(s) => setTunable('overlapRatio', s * 0.05, 0.3, 0.95)}
            />
            <TunerRow
              label="磁吸停留 (ms)"
              value={tunables.dwellMs}
              onStep={(s) => setTunable('dwellMs', s * 50, 100, 1000)}
            />
            <TunerRow
              label="边缘带宽 (px)"
              value={tunables.edgePx}
              onStep={(s) => setTunable('edgePx', s * 10, 30, 200)}
            />
            <TunerRow
              label="滚动速度 (px/s)"
              value={tunables.maxScrollPxPerSec}
              onStep={(s) => setTunable('maxScrollPxPerSec', s * 100, 200, 2400)}
            />
            <div className={styles.tunerRow}>
              <span className={styles.tunerLabel}>列表长度</span>
              <button type="button" className={styles.tunerBtn} onClick={() => setLongList((v) => !v)}>
                {longList ? '12 张' : '6 张'}
              </button>
            </div>
            <div className={styles.tunerRow}>
              <span className={styles.tunerLabel}>计数</span>
              <button
                type="button"
                className={styles.tunerBtn}
                onClick={() => {
                  setCounters(ZERO_COUNTERS);
                  setLog([]);
                  setResult(null);
                  t0.current = Date.now();
                }}
              >
                清零
              </button>
            </div>
          </div>
        )}
      </div>

      {result && <div className={styles.result}>{result}</div>}

      <div className={styles.list} ref={listRef}>
        {view.ghost && (
          <div
            className={styles.ghost}
            style={{
              top: view.ghost.top,
              left: view.ghost.left,
              width: view.ghost.width,
              height: view.ghost.height,
            }}
          />
        )}
        {protoWorlds(longList).map((w) => {
          const isDragging = view.draggingId === w.id;
          const isTarget = view.targetId === w.id;
          const cls = [
            game.card,
            cardTheme(baseOf(w.id)).cardClass, // 副本卡沿用本体的氛围登记
            w.active ? styles.dragCard : game.cardLocked,
            isDragging ? styles.lifted : '',
            isTarget ? (view.valid ? (view.armed ? styles.targetArmed : styles.targetOk) : styles.targetBad) : '',
          ]
            .filter(Boolean)
            .join(' ');
          return (
            <div
              key={w.id}
              className={cls}
              data-proto-card={w.id}
              ref={(el) => {
                if (el) cardEls.current.set(w.id, el);
                else cardEls.current.delete(w.id);
              }}
              onPointerDown={w.active ? (e) => onPointerDown(w.id, e) : undefined}
              onContextMenu={(e) => e.preventDefault()}
            >
              <div className={game.cardTop}>
                <h2 className={game.cardTitle}>{w.displayName}</h2>
                {w.active ? (
                  <span className={game.cardTag}>{w.vibeTag}</span>
                ) : (
                  <span className={game.cardSoon}>敬请期待</span>
                )}
              </div>
              {w.tagline && <p className={game.cardTagline}>{w.tagline}</p>}
            </div>
          );
        })}
      </div>

      <div className={styles.hud}>
        <div ref={hudRef} />
        <div className={styles.hudLog}>
          {`抓起 ${counters.grab} · 滚动胜出 ${counters.scrollWon} · 误抓起 ${counters.falseGrab} · 提交 ${counters.commit} · 取消 ${counters.cancel} · 无效 ${counters.reject}`}
          {log.map((l) => `\n${l}`)}
        </div>
      </div>
    </main>
  );
}

function TunerRow({
  label,
  value,
  onStep,
}: {
  label: string;
  value: number;
  onStep: (sign: 1 | -1) => void;
}) {
  return (
    <div className={styles.tunerRow}>
      <span className={styles.tunerLabel}>{label}</span>
      <span className={styles.tunerValue}>{value}</span>
      <button type="button" className={styles.tunerBtn} onClick={() => onStep(-1)} aria-label={`${label} 减`}>
        −
      </button>
      <button type="button" className={styles.tunerBtn} onClick={() => onStep(1)} aria-label={`${label} 加`}>
        +
      </button>
    </div>
  );
}
