import type { AxisView, StatsProps } from '../contract';
import styles from './paidan.module.css';

// 《派单》· 数值形态「义眼读数」(ADR-036 Slice G-2)。
//
// 一轴一格:名字 / 等宽数字 / **档名在数字下方**(Felix 2026-11-11 裁定补充 1:375 宽下档名放在数字旁边
// 会被挤成一字一行,旧实现《派单》样张里的「透 / 支」)/ 一条细进度条。
//
// **主题层只呈现风险等级,不解释轴语义**(ADR-018 §4.2):进度条与危险态数字的颜色一律来自
// `axis.severity`,不读 key、不读 label、不按数值高低自判;severity 为 null → 常态色(绝不默认危险)。
// 数值滚动由通用层插值(`axis.value` 已是当前显示值),本组件不自己滚。
export function PaidanStats({ axes }: StatsProps) {
  return (
    <div className={styles.readout}>
      {axes.map((axis) => (
        <Cell key={axis.key} axis={axis} />
      ))}
    </div>
  );
}

function fillClass(axis: AxisView): string {
  if (axis.severity === 'danger') return `${styles.fill} ${styles.fillDanger}`;
  if (axis.severity === 'caution') return `${styles.fill} ${styles.fillCaution}`;
  return styles.fill;
}

function Cell({ axis }: { axis: AxisView }) {
  const danger = axis.severity === 'danger';
  return (
    <div className={styles.cell} aria-label={axis.a11yText}>
      <span className={styles.cellKey}>{axis.displayName}</span>
      <span className={danger ? `${styles.cellNum} ${styles.numDanger}` : styles.cellNum}>
        {Math.round(axis.value)}
      </span>
      {/* 无档表时也占一行高度,四格底边对齐。 */}
      <span className={styles.cellBand}>{axis.bandLabel ?? ''}</span>
      <div className={styles.track}>
        <div className={fillClass(axis)} style={{ width: `${axis.percent}%` }} />
      </div>
    </div>
  );
}
