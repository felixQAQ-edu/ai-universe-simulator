import type { AxisView, StatsProps } from '../contract';
import { severityWord } from '../contract';
import styles from './cthu.module.css';

// 克苏鲁 · 数值形态「潮汐刻度」(样板间冻结成果移植)。
//
// 四种形态并列看:规则怪谈 = 横向分段 OSD(监控)/ 修仙 = 竖向光带 + 玉印(脉)/
// 末日 = 圆表盘指针(机械)/ 克苏鲁 = **横向刻度尺上的一根游标**(潮汐水位)。
// 同一份 `AxisView` 喂四种形态。
//
// ── §4.2 的第五次实例化,而且这次差点又被样板间带偏 ─────────────────────
// 样板间的刻度左侧画着一条**固定红区**(`width: 35%`),等于把「**低 = 危险**」
// 这个位置假设画进了视觉里 —— 对禁忌知识(累积轴,**高位**才危)是错的:
// 「禁忌知识 12」会被画成危险,「禁忌知识 88」反而干净。
// 这与刀 3 表盘那条 `value < 35 ? "is-low"` 是**同一个坑的另一种画法**
// (一个写在 JS 里、一个画在 CSS 里),移植时必须换掉而不是照抄:
// **固定红区整条删除**,风险色只跟 `axis.severity`(服务端派生)走。
//
// **游标位置不是语义**:`percent` → `left%` 是纯几何映射,与危不危险无关,故可在主题层算。
// **指针漂移不是本文件的事**:读数损坏由异常调度器写主题根的 `--cthu-drift-N`,
// 本层的 CSS 消费它 —— 数值形态自己不认识「异常」这回事。
//
// **数值滚动不在本文件**:`axis.value` 已是通用层插值后的当前显示值(`useAnimatedValues`),
// 游标、数字、档名、风险字样全由同一个值派生 —— 同帧翻转(§4.2.1)。

export function CthuStats({ axes }: StatsProps) {
  return (
    <div className={styles.gauges}>
      {axes.map((axis) => (
        <Gauge key={axis.key} axis={axis} />
      ))}
    </div>
  );
}

/** 风险态:**只认 severity**,不认 key、不认 label、不认数值高低、不认位置。 */
function severityClass(axis: AxisView): string {
  if (axis.severity === 'danger') return styles.danger;
  if (axis.severity === 'caution') return styles.caution;
  return '';
}

function Gauge({ axis }: { axis: AxisView }) {
  // 风险字样只在 caution / danger 露面;常态不加字(完整语义仍在 a11yText 里交给读屏)。
  const risk = axis.severity === 'neutral' ? null : severityWord(axis.severity);
  const cls = [styles.gauge, severityClass(axis)].filter(Boolean).join(' ');
  return (
    <div className={cls} aria-label={axis.a11yText}>
      <span className={styles.gaugeLabel}>{axis.displayName}</span>
      <div className={styles.gaugeScale} aria-hidden="true">
        <div className={styles.gaugeNeedle} style={{ left: `${axis.percent}%` }} />
      </div>
      <span className={styles.gaugeNum}>{axis.value}</span>
      {axis.bandLabel && <span className={styles.gaugeBand}>{axis.bandLabel}</span>}
      {risk && <span className={styles.gaugeRisk}>{risk}</span>}
    </div>
  );
}
