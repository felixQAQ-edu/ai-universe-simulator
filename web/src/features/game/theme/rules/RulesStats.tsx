import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import type { AxisView, StatsProps } from '../contract';
import { severityWord } from '../contract';
import type { SkinRuntime } from '../lifecycle';
import { RULES, reducedMotion } from '../motion';
import styles from './rules.module.css';

// 规则怪谈 · 数值形态「监控 OSD 状态栏」(样板间冻结成果移植)。
// 等宽数字 + 分段条 + 状态灯 + 扫描线(扫描线是纯 CSS 的低频槽,见 rules.module.css)。
//
// **主题层只呈现风险等级,不解释轴语义**(ADR-018 §4.2):状态灯与状态词一律来自
// `axis.severity`(服务端派生)。样板间那版按 `value >= 60 ? "正常" : …` 自算阈值 —— 那正是
// 本 ADR 禁止的「消费方发明启发式」,移植时必须换掉,而不是照抄。
// severity 为 null(四种缺省)→ **不显示状态灯与状态词**,数字与档名照常(安全降级,绝不默认危险)。

export function RulesStats({ axes, runtime }: StatsProps) {
  return (
    <div className={styles.osd}>
      <div className={styles.osdHead}>
        <span className={styles.rec}>
          <i />
          REC
        </span>
        <span>SYS.07 · 状态自检</span>
      </div>
      {axes.map((axis) => (
        <OsdRow key={axis.key} axis={axis} runtime={runtime} />
      ))}
    </div>
  );
}

/** 状态灯配色 class:**只认 severity**,不认 key、不认 label、不认数值高低。 */
function severityClass(axis: AxisView): string {
  if (axis.severity === 'danger') return styles.statusBad;
  if (axis.severity === 'caution') return styles.statusWarn;
  if (axis.severity === 'neutral') return styles.statusOk;
  return '';
}

function OsdRow({ axis, runtime }: { axis: AxisView; runtime: SkinRuntime }) {
  const bar = useRef<HTMLDivElement>(null);
  const num = useRef<HTMLSpanElement>(null);
  /** 屏上当前显示到的数(动画起点);首帧 = 真值,故首屏读数即正确。 */
  const shown = useRef(axis.value);
  const status = severityWord(axis.severity);

  useEffect(() => {
    const b = bar.current;
    const n = num.current;
    if (!b || !n) return;
    const from = shown.current;
    const to = axis.value;
    shown.current = to;
    if (from === to) return;

    if (reducedMotion()) {
      n.textContent = pad(to);
      b.style.width = `${clamp(to)}%`;
      return;
    }
    // 数值滚动 = 本屏的用户触发槽。走统一 runtime(§4.4):turn 切换/卸载由同一个 teardown 收走,
    // 不在这里另起一套清理。线性缓动 = 规则怪谈「准时、机械」的时间感(RULES 常量)。
    runtime.add(() => {
      const o = { v: from };
      gsap.to(o, {
        v: to,
        duration: RULES.dur(0.9),
        ease: RULES.ease,
        onUpdate: () => {
          n.textContent = pad(o.v);
          b.style.width = `${clamp(o.v)}%`;
        },
      });
    });
  }, [axis.value, runtime]);

  return (
    <div className={styles.osdRow} aria-label={axis.a11yText}>
      <span className={styles.osdLabel}>{axis.displayName}</span>
      <div className={styles.osdTrack}>
        <div className={styles.osdBar} ref={bar} style={{ width: `${axis.percent}%` }} />
      </div>
      <span className={styles.osdNum} ref={num}>
        {pad(axis.value)}
      </span>
      {axis.bandLabel && <span className={styles.osdBand}>{axis.bandLabel}</span>}
      {status && (
        <span className={`${styles.osdStatus} ${severityClass(axis)}`}>
          <i className={styles.osdLamp} />
          {status}
        </span>
      )}
    </div>
  );
}

const clamp = (n: number) => Math.max(0, Math.min(100, n));
/** 监控读数是等宽两位数(0–100 的 100 自然三位,不截断)。 */
const pad = (n: number) => String(Math.round(n)).padStart(2, '0');
