import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { SkinRuntime } from '../lifecycle';
import { reducedMotion } from '../motion';
import { trace } from '../telemetry';
import type { StampSpec } from './registry';
import styles from './seep.module.css';

// 验讫印(ADR-043 决策 4;Felix 补裁 7 + 审定 4 / 5)—— 宿主一级记忆点(修仙钟鸣)的**第二拍**,不另立记忆点。
//
// **在不在 = 宿主签名轴当前档序号 > 0**,纯推导:跌回初境就不在;首次装载 / 续局 / 开局就在小成 → 直接在、不播。
// **播压印**:签名轴向上跨档(通用层的 `tick` 加一;首次装载不加、跨多档只加一)且新档 > 0 →
// 印先藏起(pending),跨档成立后 `afterCrossingMs`(1650 = 钟鸣 +550ms 鸣、再 1100ms)播样张关键帧,播完常显。
// 每次向上跨档都重压(审定 5)。reduced-motion 下不藏、不播,档 > 0 即在(钟此时本就不鸣)。
//
// 计时走 `runtime.setTimeout`(世代号核对:换回合 / 卸载自动作废);被打断 → 印直接常显、不补播(同钟鸣「不补播」)。
// 回调写 state 前一律查 `runtime.alive(token)` 与本次 effect 是否已作废(F-041)。
// **不持锁**:压印与钟鸣是同一个用户触发事件,钟鸣持锁;压印另抢会在 +1650ms 被钟鸣的锁拒掉。

const TRACE = 'seep.stamp';
const now = () => Math.round(performance.now());

type Phase = 'idle' | 'pending' | 'press';

export function SeepStamp({
  stamp,
  tick,
  index,
  runtime,
}: {
  stamp: StampSpec;
  /** 宿主签名轴向上跨档的累计次数(通用层 `useSignalCrossing` 的同一次判定)。 */
  tick: number;
  /** 宿主签名轴当前档序号;null = 无档表 → 不显示。 */
  index: number | null;
  runtime: SkinRuntime;
}) {
  // 据 props 变化调整 state(同 useSignalCrossing / useAnimatedValues):跨档那一次提交里就藏起,不晚一拍闪一下。
  const [seen, setSeen] = useState<{ tick: number; phase: Phase }>({ tick, phase: 'idle' });
  let state = seen;
  if (seen.tick !== tick) {
    const press = index !== null && index > 0 && !reducedMotion();
    state = { tick, phase: press ? 'pending' : 'idle' };
    setSeen(state);
  }
  const { phase } = state;

  // pending → 到点压下;被 teardown 打断 → 直接常显(不补播)。
  useEffect(() => {
    if (phase !== 'pending') return;
    const at = tick;
    const token = runtime.token;
    let dropped = false;
    trace(TRACE, { scheduledAt: now(), state: 'pending', suppressedReason: undefined }, true);
    runtime.setTimeout(() => {
      if (dropped || !runtime.alive(token)) return;
      setSeen((s) => (s.tick === at && s.phase === 'pending' ? { tick: at, phase: 'press' } : s));
    }, stamp.afterCrossingMs);
    runtime.onTeardown(() => {
      setSeen((s) => (s.tick === at && s.phase === 'pending' ? { tick: at, phase: 'idle' } : s));
      trace(TRACE, { suppressedReason: '被打断(换回合 / 卸载),直接常显', state: 'interrupted' });
    });
    return () => {
      dropped = true;
    };
  }, [phase, tick, runtime, stamp.afterCrossingMs]);

  const ref = useRef<HTMLDivElement | null>(null);
  useLayoutEffect(() => {
    if (phase !== 'press') return;
    const el = ref.current;
    trace(TRACE, { firedAt: now(), state: 'press' });
    if (!el || typeof el.animate !== 'function') return;
    const a = el.animate(
      [
        { opacity: 0, transform: `rotate(-9deg) scale(${stamp.fromScale})` },
        { opacity: 0.85, transform: 'rotate(-9deg) scale(0.96)', offset: 0.85 },
        { opacity: 0.85, transform: 'rotate(-9deg) scale(1)' },
      ],
      { duration: stamp.pressMs, easing: 'cubic-bezier(0.55, 0, 1, 0.45)' },
    );
    const token = runtime.token;
    a.onfinish = () => {
      if (runtime.alive(token)) trace(TRACE, { completedAt: now(), state: 'done' });
    };
    runtime.onTeardown(() => a.cancel());
    return () => a.cancel();
  }, [phase, tick, runtime, stamp.fromScale, stamp.pressMs]);

  if (index === null || index <= 0 || phase === 'pending') return null;
  return (
    <div
      ref={ref}
      className={styles.stamp}
      data-seep="stamp"
      style={{
        left: `calc(${stamp.center.x} - var(--stamp-size) / 2)`,
        top: `calc(${stamp.center.y} - var(--stamp-size) / 2)`,
      }}
    >
      {stamp.text}
    </div>
  );
}
