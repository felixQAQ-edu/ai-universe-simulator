import { useEffect, useRef, useState } from 'react';
import type { SkinRuntime } from '../lifecycle';
import { reducedMotion } from '../motion';
import { trace } from '../telemetry';
import { createDrawState, drawIndex, markPlayed } from './radioDraw';
import {
  RADIO_HISS,
  RADIO_IDLE,
  RADIO_POOL,
  SIGNAL_BARS,
  SIGNAL_OPACITY,
  degrade,
  holdMs,
  type SignalLevel,
} from './radioPool';

// 末日 · 电台**调度与播出状态机**(四层分工的第 ③层)。
//
// ── 动效预算:电台与远光**共用一个低频槽**(AGENTS.md §2.1)────────────────
// 两者**永不同时运行**,互斥由**代码保证**而非概率:两边都要先抢 `runtime.tryLock()`,
// 电台在整段播出(含 10s 余韵)期间持锁,远光抢不到就整次跳过 —— 反之亦然。
// 预算表因此记作 **1 个低频槽(两种表现:电台 / 远光,互斥机制 = runtime 忙态锁)**。
//
// ── 去节奏化(B 批验证轮)──────────────────────────────────────────────
// 固定心跳会被玩家量化学习成「每 14 秒响一次」,那一刻电台就从世界变成了 UI 计时器。
// 故用 **随机步长的 setTimeout 链**而非 `setInterval`,且有 15% 概率**调谐失败**
// (只有频率滚动、没有内容)—— **沉默也是内容**。
//
// 停表:`paused`(生成中 / 开场逐字未完)期间**不排期**;`reduced-motion` 下整个调度不启动
// (电台面板仍在,只是永远停在无信号 —— 家具还在,只是不响)。

/** 一次播出的呈现状态(呈现层照着渲染,不自己算)。 */
export interface RadioView {
  /** 频率读数(播出时锁定一个值,调谐时滚动)。 */
  freq: string;
  /** 信号格。 */
  bars: string;
  /** 屏上那行字。 */
  line: string;
  /** 该行字的不透明度(信号越弱越淡)。 */
  lineOpacity: number;
  /** 是否处于「收到内容」状态(呈现层据它加强调 class)。 */
  receiving: boolean;
}

const IDLE_VIEW: RadioView = {
  freq: '146.520',
  bars: '▁▁▁▁',
  line: RADIO_IDLE,
  lineOpacity: 0.55,
  receiving: false,
};

/** 调谐爬升的三格(前一拍,~0.7s)。 */
const CLIMB = ['▁▂▁▂', '▂▃▂▃', '▃▄▃▄'];
const TUNE_STEP_MS = 180;
const TUNE_MS = 720;
const FADE_MS = 600;
/** 余韵:播完之后的微弱底噪,10s 后回到无信号。 */
const HISS_MS = 10_000;
/** 调谐失败的滚动时长。 */
const FAIL_MS = 1400;
/** 调度步长:8–20s 随机(**不是**固定心跳)。 */
const GAP_MIN_MS = 8_000;
const GAP_SPAN_MS = 12_000;
/** 每次调度真正开播的概率(其余时间是安静的 —— 电台不该一直在响)。 */
const BURST_CHANCE = 0.25;
/** 调谐失败概率。 */
const FAIL_CHANCE = 0.15;

const TRACE = 'waste.radio';
const rollFreq = () => (146.2 + Math.random() * 0.7).toFixed(3);
const lockFreq = () => (145.1 + Math.random() * 2.8).toFixed(3);

/**
 * 电台调度 + 播出状态机。
 *
 * @param runtime 皮肤 runtime:所有计时器登记于此(换回合 / 卸载统一 teardown),
 *                忙态锁同时充当**与远光的互斥闸**。
 * @param paused  停表(正文不稳定期)
 * @returns 当前呈现状态 + 手动开播(debug 用)
 */
export function useRadio(runtime: SkinRuntime, paused: boolean) {
  const [view, setView] = useState<RadioView>(IDLE_VIEW);
  const draw = useRef(createDrawState());
  const played = useRef<Set<number>>(new Set());
  // 最新的 paused 放 ref:调度链只排一次,不因 paused 变化而重排(重排会打乱随机步长)。
  // **在 effect 里写 ref,不在渲染期写**(渲染期写 ref 不纯,React Compiler lint 会拦)。
  const pausedRef = useRef(paused);
  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  const burstRef = useRef<() => void>(() => {});

  useEffect(() => {
    const idle = () => setView(IDLE_VIEW);

    /** 一次调谐(前一拍):频率滚动 + 信号爬升。返回停止函数。 */
    const tune = () => {
      let n = 0;
      const id = window.setInterval(() => {
        setView((v) => ({ ...v, freq: rollFreq(), bars: CLIMB[Math.min(n, 2)] }));
        n += 1;
      }, TUNE_STEP_MS);
      runtime.onTeardown(() => window.clearInterval(id));
      return () => window.clearInterval(id);
    };

    const burst = () => {
      // reduced-motion:整个效果不发生(面板仍在,停在无信号)。
      if (reducedMotion()) return trace(TRACE, { suppressedReason: 'reduced-motion' });
      // **互斥闸**:远光若正在演,这次电台整次跳过(反之亦然)。不排队、不补播。
      if (!runtime.tryLock()) return trace(TRACE, { suppressedReason: 'busy(远光或上一次未收尾)' });

      const token = runtime.token;
      trace(TRACE, { scheduledAt: Math.round(performance.now()), state: 'tuning' }, true);
      const stopTune = tune();

      // 15% 调谐失败:频率爬了一半又落回,没有内容 —— 沉默也是内容。
      const idx = Math.random() < FAIL_CHANCE ? null : drawIndex(draw.current);
      if (idx === null) {
        runtime.setTimeout(() => {
          stopTune();
          idle();
          runtime.unlock();
          trace(TRACE, { state: 'tune-failed', completedAt: Math.round(performance.now()) });
        }, FAIL_MS + Math.random() * 600);
        return;
      }

      const msg = RADIO_POOL[idx];
      const again = played.current.has(idx);
      played.current.add(idx);
      markPlayed(draw.current, idx);
      // 重播时信号强度也可能掉一档(「上次好像没听全」)
      const sig = (again && Math.random() < 0.5 ? Math.max(0, msg.sig - 1) : msg.sig) as SignalLevel;
      const text = degrade(msg, again);

      runtime.setTimeout(() => {
        if (!runtime.alive(token)) return;
        stopTune();
        setView({
          freq: lockFreq(), // 每次锁定的频率都不同
          bars: SIGNAL_BARS[sig],
          line: text,
          lineOpacity: SIGNAL_OPACITY[sig],
          receiving: true,
        });
        trace(TRACE, { firedAt: Math.round(performance.now()), state: `rx:${msg.layer}` });

        runtime.setTimeout(() => {
          // 余韵:先淡出内容,再挂 10s 底噪,最后回无信号
          setView((v) => ({ ...v, receiving: false, lineOpacity: 0 }));
          runtime.setTimeout(() => {
            setView({ ...IDLE_VIEW, line: RADIO_HISS, bars: '▂▁▂▁', lineOpacity: 0.35 });
            runtime.setTimeout(() => {
              idle();
              runtime.unlock(); // 余韵结束才放锁 —— 余韵期内远光同样不许插进来
              trace(TRACE, { completedAt: Math.round(performance.now()), state: 'done' });
            }, HISS_MS);
          }, FADE_MS);
        }, holdMs(text));
      }, TUNE_MS);
    };

    burstRef.current = burst;

    // 调度链:随机步长,不用 setInterval(固定心跳会被量化学习)。
    //
    // **链条走 `window.setTimeout` + 本 effect 自己的 cleanup,不走 `runtime.setTimeout`**
    // (ADR-018 §4.11 第二种子模式,刀 4 的守护测试发现):`runtime.teardown()` 在**换回合**时
    // 清掉全部受管定时器,而 runtime 身份跨 turn 稳定 → 本 effect 不会重跑 →
    // 链条被清掉后**再没有人重新排期**,电台从第二回合起永久静默且无报错。
    // 一次播出**内部**的定时器仍走 runtime —— 那些**应当**被换回合打断。
    let timer = 0;
    const loop = () => {
      timer = window.setTimeout(
        () => {
          // 停表期 / 页面不可见时不播,但**链条继续**(下一次照常排期)。
          if (!pausedRef.current && !document.hidden && Math.random() < BURST_CHANCE) burst();
          loop();
        },
        GAP_MIN_MS + Math.random() * GAP_SPAN_MS,
      );
    };
    loop();
    return () => window.clearTimeout(timer);
  }, [runtime]);

  return { view, burst: () => burstRef.current() };
}
