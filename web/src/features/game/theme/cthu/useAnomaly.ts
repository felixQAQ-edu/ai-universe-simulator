import { useCallback, useEffect, useRef } from 'react';
import type { RefObject } from 'react';
import type { AxisSeverity } from '../../../../api';
import type { SkinRuntime } from '../lifecycle';
import { reducedMotion } from '../motion';
import { trace } from '../telemetry';
import { createPickState, markPicked, pickAnomaly, pickPunct, tailExtra, type Pick } from './anomalyPick';
import { advancePhase, deferMs, nextDelayMs, resumeMs, type AnomalyPhase } from './anomalySchedule';
import type { Chunk } from './anomalySegment';

// 克苏鲁 · 一级记忆点**「文字异常」的调度与施加**(四层分工的第 ③层)。
//
// ── 动效预算:文字异常与读数损坏**互斥共用一个低频槽**(AGENTS.md §2.1)──────
// 两者由**同一个调度器**分派(85% 文字 / 15% 仪器)、抢**同一把** `runtime.tryLock()`,
// 持锁到余韵结束才放 —— 故「同时运行的效果数」恒为 1。三条防滥用逐条对应:
//   ① 互斥是**代码保证**(同一个 fire() 里二选一 + 同一把锁),不是「概率上很少同时」;
//   ② 共用槽位与互斥关系**显式声明**在本注释与 `cthu.module.css` 头部;
//   ③ 预算表记**一个低频槽**,标注两种表现与互斥机制。
// 孢子暂停**不占槽**:它是持续槽(孢子)自身的调制(把它停 0.4–0.7s),不是第二个效果。
//
// ── §4.11:持锁的异步效果必须有独立于 rAF 的兜底放锁 ─────────────────────
// 刀 3 的远光在这里踩过:只在 GSAP `onComplete` 放锁,页面转后台 rAF 冻结 → timeline
// 永不完成 → 锁被永久占住 → 共槽的电台**从此静默且无报错**。本调度器的一次事件最长可达
// 标点余韵的 12s,故 {@link RELEASE_MS} 给到 16s,且放锁**幂等**(正常收尾与兜底谁先到都行)。
// 本文件不用 GSAP,但兜底照挂 —— 这条不是「因为用了 GSAP 才要」,是**凡持锁就要**。
//
// ── 正文是禁区,所以异常必须「单点、瞬时、可完全撤销」──────────────────────
//   · 单点:一次只动**一片**(110–300ms),不做整段效果、不加滤镜、不改 opacity;
//   · 可撤销:改前存 `data-orig`,改后原样写回 —— DOM 不残留任何多余字符;
//   · **换行守卫**:任何改动若引起正文高度变化,**立即静默取消这一次**
//     —— 宁可不发生,也不接受阅读中的跳行(视觉宪法第一条是唯一一票否决项);
//   · **文本不稳定期一律停表**(`paused` = generating 或开场逐字):那期间 React 会重渲染,
//     命令式改动会被冲掉,更糟的是可能把异常后的文本当成原文记进 `data-orig`。

/** 兜底放锁(ms):覆盖「异常 0.3s + 余韵起始 0.25s + 余韵最长 12s」并留余量。 */
const RELEASE_MS = 16_000;
/** 每次异常里改为「仪器事件」而非「文字事件」的概率。 */
const INSTRUMENT_CHANCE = 0.15;
/** 文字异常与孢子暂停**人为重合**的概率 —— 其余约四分之三无任何环境前兆。 */
const COINCIDE_CHANCE = 0.25;
/** 标点余韵:50% 概率 + 60s 冷却(样板间验证轮口径,不是每次必现)。 */
const RESIDUE_CHANCE = 0.5;
const RESIDUE_COOLDOWN_MS = 60_000;
const RESIDUE_HOLD_MIN_MS = 9_000;
const RESIDUE_HOLD_SPAN_MS = 3_000;

const TRACE = 'cthu.anomaly';
const TRACE_INSTRUMENT = 'cthu.instrument';
const TRACE_SPORE = 'cthu.spore';

/** 孢子暂停的独立调度(均值 80s,截断 [35s, 200s])—— 与异常调度器**互不知晓**。 */
const SPORE_MEAN_MS = 80_000;
const SPORE_MIN_MS = 35_000;
const SPORE_MAX_MS = 200_000;

export interface AnomalyArgs {
  runtime: SkinRuntime;
  /** 主题根:指针漂移量与孢子暂停都写成它上面的自定义属性(不跨组件 query DOM)。 */
  rootRef: RefObject<HTMLElement | null>;
  /** 正文分片容器 —— 调度器**只碰自己组件的 DOM**。 */
  proseRef: RefObject<HTMLElement | null>;
  /** 当前分片(与 DOM 里的 `data-i` 一一对应)。 */
  chunks: Chunk[];
  /** 停表:generating 或开场逐字期。 */
  paused: boolean;
  /** 签名轴当前档的风险等级 —— 调频率**只认它**(§4.2)。 */
  severity: AxisSeverity | null;
  turn: number;
}

/** 手动触发口(仅 `?debug=1` 面板使用;不改任何生产数据,ADR-018 §5 Q7)。 */
export interface AnomalyControls {
  fireText: () => void;
  fireInstrument: () => void;
  fireSpore: () => void;
}

export function useAnomaly({
  runtime,
  rootRef,
  proseRef,
  chunks,
  paused,
  severity,
  turn,
}: AnomalyArgs): AnomalyControls {
  // 跨 turn 存活的观察状态(组件不因换回合而重挂载,故放 ref)。
  const phase = useRef<AnomalyPhase>('baseline');
  const pick = useRef(createPickState());
  const lastScrollAt = useRef(0);
  const lastResidueAt = useRef(0);
  // 最新值放 ref、**在 effect 里写**(渲染期写 ref 不纯):调度链据此取当下的状态,
  // 而链条本身不因这些值变化而重排 —— 重排会打乱「不可预测」这件事本身。
  const pausedRef = useRef(paused);
  const severityRef = useRef(severity);
  const chunksRef = useRef(chunks);
  useEffect(() => {
    pausedRef.current = paused;
    severityRef.current = severity;
    chunksRef.current = chunks;
  }, [paused, severity, chunks]);

  // 换回合:上一回合的片下标已无意义(文本整段换了),清掉目标记忆;
  // **类型记忆保留** —— 「同一类型不连续」应当跨回合继续成立。
  useEffect(() => {
    pick.current.lastIndex = -1;
  }, [turn]);

  const setVar = useCallback(
    (name: string, value: string) => rootRef.current?.style.setProperty(name, value),
    [rootRef],
  );

  /** 孢子暂停:持续槽自身的调制(**假线索**),不占槽、不持锁。 */
  const fireSpore = useCallback(() => {
    if (reducedMotion()) return;
    setVar('--cthu-spore-play', 'paused');
    trace(TRACE_SPORE, { firedAt: Math.round(performance.now()), state: 'hold' }, true);
    runtime.setTimeout(() => {
      setVar('--cthu-spore-play', 'running');
      trace(TRACE_SPORE, { completedAt: Math.round(performance.now()), state: 'done' });
    }, 400 + Math.random() * 300);
  }, [runtime, setVar]);

  /** 读数损坏 / 指针轻颤:写主题根的自定义属性,由数值形态的 CSS 消费。 */
  const fireInstrument = useCallback(() => {
    if (reducedMotion()) return trace(TRACE_INSTRUMENT, { suppressedReason: 'reduced-motion' });
    if (!runtime.tryLock()) {
      return trace(TRACE_INSTRUMENT, { suppressedReason: 'busy(文字异常正在进行)' });
    }
    const slot = Math.floor(Math.random() * 3); // 轴少于三根时这一路无人消费 → 静默降级
    const name = `--cthu-drift-${slot}`;
    const release = once(() => {
      setVar(name, '0px');
      runtime.unlock();
    });
    // §4.11 兜底放锁:与下面的正常收尾**幂等**互备。
    runtime.setTimeout(() => {
      // 只有**真的由兜底放的锁**才记这一笔(否则正常收尾会被迟到的兜底覆盖,读数失真)。
      if (release()) {
        trace(TRACE_INSTRUMENT, { completedAt: Math.round(performance.now()), state: '兜底放锁' });
      }
    }, RELEASE_MS);

    if (Math.random() < 0.5) {
      // 轻颤:四下小幅摆动(样板间的「指针自颤」并入本调度器,不再自循环)。
      trace(TRACE_INSTRUMENT, { firedAt: Math.round(performance.now()), state: `jitter:${slot}` }, true);
      const steps = [-2.2, 2.0, -1.4, 0.9, 0];
      steps.forEach((px, i) => {
        runtime.setTimeout(() => setVar(name, `${px}px`), i * 100);
      });
      runtime.setTimeout(() => {
        if (release()) {
          trace(TRACE_INSTRUMENT, { completedAt: Math.round(performance.now()), state: 'done' });
        }
      }, steps.length * 100 + 200);
    } else {
      // 读数损坏:指针**缓慢**漂离读数 3–4px,停一拍,再缓慢归位 ——
      // 无闪回、无故障字符(样板间验证轮已砍掉「6?」那种形态:故障字符读作「页面坏了」)。
      const px = (3 + Math.random()) * (Math.random() < 0.5 ? -1 : 1);
      trace(TRACE_INSTRUMENT, { firedAt: Math.round(performance.now()), state: `drift:${slot}` }, true);
      // 分步写值(不用 CSS transition,理由见 cthu.module.css 的 .gaugeNeedle)。
      ramp(runtime, setVar, name, 0, px, 800, 0);
      ramp(runtime, setVar, name, px, 0, 1200, 1300);
      runtime.setTimeout(() => {
        if (release()) {
          trace(TRACE_INSTRUMENT, { completedAt: Math.round(performance.now()), state: 'done' });
        }
      }, 2600);
    }
  }, [runtime, setVar]);

  /** 文字异常:单点、瞬时、可完全撤销。 */
  const fireText = useCallback(() => {
    if (reducedMotion()) return trace(TRACE, { suppressedReason: 'reduced-motion' });
    const root = proseRef.current;
    if (!root) return trace(TRACE, { suppressedReason: '正文未挂载' });
    if (!runtime.tryLock()) return trace(TRACE, { suppressedReason: 'busy(上一次未收尾)' });

    const release = once(() => runtime.unlock());
    // §4.11 兜底放锁(覆盖余韵那一段 —— 它是四个世界里最长的尾巴)。
    runtime.setTimeout(() => {
      if (release()) trace(TRACE, { completedAt: Math.round(performance.now()), state: '兜底放锁' });
    }, RELEASE_MS);

    const chosen = pickAnomaly(chunksRef.current, pick.current);
    if (!chosen) {
      release();
      return trace(TRACE, { suppressedReason: '无可用候选(约束全挡住,整次跳过)' }, true);
    }
    const el = root.querySelector<HTMLElement>(`[data-i="${chosen.index}"]`);
    if (!el) {
      release();
      return trace(TRACE, { suppressedReason: '目标片不在 DOM(刚换过文本)' }, true);
    }

    trace(TRACE, { scheduledAt: Math.round(performance.now()), state: `pick:${chosen.mode}` }, true);
    // 约四分之一与孢子暂停重合 —— 另外四分之三**毫无前兆**:
    // 「任何环境变化都不能可靠预测异常」是这个记忆点的关键(样板间验证轮成果)。
    if (Math.random() < COINCIDE_CHANCE) fireSpore();

    const restore = applyAnomaly(el, chosen, () => root.offsetHeight);
    if (!restore) {
      release();
      return trace(TRACE, { suppressedReason: '换行守卫:会引起跳行,静默取消' });
    }
    markPicked(pick.current, chosen);
    trace(TRACE, { firedAt: Math.round(performance.now()), state: `on:${chosen.mode}` });

    runtime.setTimeout(() => {
      restore();
      trace(TRACE, { state: `off:${chosen.mode}` });
      maybeResidue(root, () => {
        if (release()) trace(TRACE, { completedAt: Math.round(performance.now()), state: 'done' });
      });
    }, holdMsFor(chosen.mode));

    /** 标点余韵:0.25s 后一个句末标点悄悄多出一份,保持 9–12s。50% 概率 + 60s 冷却。 */
    function maybeResidue(scope: HTMLElement, done: () => void) {
      const now = Date.now();
      if (Math.random() > RESIDUE_CHANCE || now - lastResidueAt.current < RESIDUE_COOLDOWN_MS) {
        return done();
      }
      const idx = pickPunct(chunksRef.current);
      if (idx === null) return done();
      const punct = scope.querySelector<HTMLElement>(`[data-i="${idx}"]`);
      if (!punct) return done();
      lastResidueAt.current = now;
      runtime.setTimeout(() => {
        const orig = chunksRef.current[idx]?.text ?? punct.textContent ?? '';
        punct.textContent = orig + orig;
        trace(TRACE, { state: 'residue' });
        runtime.setTimeout(() => {
          punct.textContent = orig;
          done();
        }, RESIDUE_HOLD_MIN_MS + Math.random() * RESIDUE_HOLD_SPAN_MS);
      }, 250);
    }
  }, [runtime, proseRef, fireSpore]);

  // ── 异常调度链 ────────────────────────────────────────────────────
  // **依赖里带 `turn`**:`useSkinRuntime` 在换回合时 `teardown()` 会清掉全部受管定时器,
  // 若只依赖 `runtime`(其身份跨 turn 稳定),effect 不会重跑 → 链条被清掉后再没人重排,
  // 记忆点从第二回合起**永久静默且无报错**。这与 §4.11 是同一族失效(锁/链被冻住,
  // 表现只是「那个效果再也不出现了」),故此处显式重排,并有回归测试钉住。
  useEffect(() => {
    if (reducedMotion()) return; // 整个调度不启动(面板与纸条仍在,只是这世界不动)

    const onScroll = () => {
      lastScrollAt.current = Date.now();
    };
    window.addEventListener('scroll', onScroll, { passive: true });

    let timer = 0;
    const schedule = (ms: number) => {
      window.clearTimeout(timer);
      timer = window.setTimeout(fire, ms);
    };

    function fire() {
      // 页面不可见:**不排下一次**,由 visibilitychange 回来时重新起算 —— 绝不累积连发。
      if (document.hidden) return;
      const sinceScroll = Date.now() - lastScrollAt.current;
      // 停表 / 忙碌 / 刚滚动过 → **顺延,不补发**。
      if (pausedRef.current || sinceScroll < 800) {
        schedule(deferMs());
        return;
      }
      if (Math.random() < INSTRUMENT_CHANCE) fireInstrument();
      else fireText();
      phase.current = advancePhase(phase.current);
      schedule(nextDelayMs({ phase: phase.current, severity: severityRef.current, sinceScrollMs: sinceScroll }));
    }

    const onVisible = () => {
      window.clearTimeout(timer);
      if (!document.hidden) schedule(resumeMs());
    };
    document.addEventListener('visibilitychange', onVisible);

    schedule(
      nextDelayMs({
        phase: phase.current,
        severity: severityRef.current,
        sinceScrollMs: Date.now() - lastScrollAt.current,
      }),
    );

    return () => {
      window.clearTimeout(timer);
      window.removeEventListener('scroll', onScroll);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [runtime, turn, fireText, fireInstrument]);

  // 孢子暂停:**独立**调度器,与异常调度器互不知晓(假线索的另一半)。
  useEffect(() => {
    if (reducedMotion()) return;
    let timer = 0;
    const loop = () => {
      const d = Math.min(SPORE_MAX_MS, Math.max(SPORE_MIN_MS, -Math.log(1 - Math.random()) * SPORE_MEAN_MS));
      timer = window.setTimeout(() => {
        if (!document.hidden) fireSpore();
        loop();
      }, d);
    };
    loop();
    return () => window.clearTimeout(timer);
  }, [runtime, turn, fireSpore]);

  // 主题根上的自定义属性由本模块写,故也由本模块负责擦干净。
  useEffect(() => {
    const root = rootRef.current;
    return () => {
      root?.style.removeProperty('--cthu-spore-play');
      for (let i = 0; i < 3; i++) root?.style.removeProperty(`--cthu-drift-${i}`);
    };
  }, [rootRef]);

  return { fireText, fireInstrument, fireSpore };
}

/** 施加一次异常;返回**撤销函数**,换行守卫触发时返回 null(该次静默取消)。 */
export function applyAnomaly(
  el: HTMLElement,
  chosen: Pick,
  measure: () => number,
): (() => void) | null {
  const orig = chosen.chunk.text;
  const before = measure();
  const restore = () => {
    el.textContent = orig;
    el.style.letterSpacing = '';
    el.style.display = '';
    el.style.transform = '';
  };

  switch (chosen.mode) {
    case 'swap':
      el.textContent = chosen.chunk.alt ?? orig;
      break;
    case 'swap2':
      el.textContent = [...orig].reverse().join('');
      break;
    case 'tail':
      el.textContent = orig + tailExtra();
      break;
    case 'squeeze':
      // 只作用于极短片段、总位移 1px 量级 —— 幅度宁可小到一部分人完全无感。
      el.style.letterSpacing = '-0.03em';
      break;
    case 'shift':
      el.style.display = 'inline-block';
      el.style.transform = 'translateY(1px)';
      break;
    default:
      return null;
  }

  // **换行守卫**:任何引起正文高度变化的改动一律当场撤销。
  // 不只给 `tail` 用 —— `shift` 的 `inline-block` 会阻断片内换行、`squeeze` 会把后字拉上来,
  // 三者都可能跳行。守卫按**结果**判(高度变没变),不按类型猜。
  if (measure() !== before) {
    restore();
    return null;
  }
  return restore;
}

/**
 * 把一个 px 自定义属性从 `from` 缓动到 `to`,分步写值。
 *
 * 为什么不用 CSS transition:见 `cthu.module.css` 的 `.gaugeNeedle` ——
 * transform 的值来自未注册自定义属性时,过渡不插值且终值不生效(冒烟二分实测)。
 * 缓动用 sine.inOut 的闭式解,与本世界 `--t-ease` 同一条曲线(§4.3 成对)。
 */
function ramp(
  runtime: SkinRuntime,
  setVar: (name: string, value: string) => void,
  name: string,
  from: number,
  to: number,
  ms: number,
  delayMs: number,
  steps = 12,
): void {
  for (let i = 1; i <= steps; i++) {
    const t = i / steps;
    const eased = 0.5 - Math.cos(Math.PI * t) / 2; // sine.inOut
    const v = from + (to - from) * eased;
    runtime.setTimeout(() => setVar(name, `${v.toFixed(2)}px`), delayMs + (ms * i) / steps);
  }
}

/** 单点时长:110–300ms(`tail` 多一个字,给到上限附近才看得清)。 */
function holdMsFor(mode: string): number {
  if (mode === 'tail') return 240 + Math.random() * 60;
  if (mode === 'squeeze') return 160 + Math.random() * 60;
  return 110 + Math.random() * 80;
}

/**
 * 幂等包一层:正常收尾与兜底放锁谁先到都行,第二次是 no-op(§4.11 第三条推论)。
 *
 * **返回「这一次是不是真的执行了」** —— 调用方据此决定要不要记遥测。
 * 冒烟当场抓到的教训:兜底不管三七二十一都记一笔,于是正常收尾的 `done` 被 16s 后
 * 迟到的兜底覆盖成「兜底放锁」,读数看起来像**每次都走了兜底**。
 * 探针撒谎比没有探针更坏 —— 刀 1 之后我们是靠它二分的。
 */
function once(fn: () => void): () => boolean {
  let done = false;
  return () => {
    if (done) return false;
    done = true;
    fn();
    return true;
  };
}
