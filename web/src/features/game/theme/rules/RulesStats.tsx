import type { AxisView, StatsProps } from '../contract';
import { severityWord } from '../contract';
import styles from './rules.module.css';

// 规则怪谈 · 数值形态「监控 OSD 状态栏」(样板间冻结成果移植)。
// 等宽数字 + 分段条 + 状态灯 + 扫描线(扫描线是纯 CSS 的低频槽,见 rules.module.css)。
//
// **主题层只呈现风险等级,不解释轴语义**(ADR-018 §4.2):状态灯与状态词一律来自
// `axis.severity`(服务端派生)。样板间那版按 `value >= 60 ? "正常" : …` 自算阈值 —— 那正是
// 本 ADR 禁止的「消费方发明启发式」,移植时必须换掉,而不是照抄。
// severity 为 null(四种缺省)→ **不显示状态灯与状态词**,数字与档名照常(安全降级,绝不默认危险)。
//
// **数值滚动不在本文件**:`axis.value` 已经是通用层插值后的当前显示值(`useAnimatedValues`),
// 档名与 severity 也由同一个值派生 —— 数字滚过阈值那一帧,三者同帧翻转。
// 主题层若自己再滚一遍数字,就会出现「数字还在滚、状态已经翻」的老毛病(ADR-018 §4.2)。

export function RulesStats({ axes }: StatsProps) {
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
        <OsdRow key={axis.key} axis={axis} />
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

function OsdRow({ axis }: { axis: AxisView }) {
  const status = severityWord(axis.severity);
  return (
    <div className={styles.osdRow} aria-label={axis.a11yText}>
      <span className={styles.osdLabel}>{axis.displayName}</span>
      <div className={styles.osdTrack}>
        <div className={styles.osdBar} style={{ width: `${axis.percent}%` }} />
      </div>
      <span className={styles.osdNum}>{pad(axis.value)}</span>
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

/** 监控读数是等宽两位数(0–100 的 100 自然三位,不截断)。 */
const pad = (n: number) => String(Math.round(n)).padStart(2, '0');
