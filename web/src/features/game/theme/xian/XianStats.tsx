import type { AxisView, StatsProps } from '../contract';
import { severityWord } from '../contract';
import styles from './xian.module.css';

// 修仙 · 数值形态「灵脉」竖向光带(样板间冻结成果移植)。
//
// **与规则怪谈 OSD 是两种形态**,不是换个颜色:那边横向分段、等宽读数、状态灯(仪表盘);
// 这边竖着走的光带、自下而上蓄起(脉)。同一份 `AxisView` 喂两种形态,这正是刀 1 立的
// 通用层/主题层分工:通用层给「一根轴的全部可呈现信息」,主题层只决定**形态与视觉状态**。
//
// **主题层不得重新解释轴语义**(ADR-018 §4.2,本刀是这条边界的第二次实例化):
// 光带配色只认 `axis.severity`(服务端派生)—— 样板间那版写的是 `value < 35 ? "is-low"`,
// 移植时**必须换掉而不是照抄**:前端手里没有做那个判断所需的信息(修仙的境界越高越好、
// 克苏鲁的禁忌知识越高越糟,同为 accumulation 结论相反)。
// severity 为 null(四种缺省)→ 不进危险态、不显风险字样,数字与档名照常。
//
// **数值滚动不在本文件**:`axis.value` 已是通用层插值后的当前显示值(`useAnimatedValues`),
// 档名与 severity 由同一个值派生 —— 数字滚过阈值那一帧三者同帧翻转(§4.2.1)。
// 跨档那一回合的「先鸣钟、后变数」也在通用层施加(`valueRoll.ceremonyDelayMs`),不在这里。

export function XianStats({ axes }: StatsProps) {
  return (
    <div className={styles.lingmai}>
      {axes.map((axis) => (
        <MaiColumn key={axis.key} axis={axis} />
      ))}
    </div>
  );
}

/** 光带视觉状态:**只认 severity**,不认 key、不认 label、不认数值高低。 */
function severityClass(axis: AxisView): string {
  if (axis.severity === 'danger') return styles.danger;
  if (axis.severity === 'caution') return styles.caution;
  return '';
}

function MaiColumn({ axis }: { axis: AxisView }) {
  // 风险字样只在 caution / danger 露面:常态不加字是**呈现选择**(这片天该是静的),
  // 不是语义判断 —— 完整语义仍由通用层写进 a11yText 交给读屏。
  const risk = axis.severity === 'neutral' ? null : severityWord(axis.severity);
  // 空段一律滤掉:class 属性里不留多余空格(同刀 1 对拍口径)。
  const columnClass = [styles.mai, severityClass(axis)].filter(Boolean).join(' ');
  return (
    <div className={columnClass} aria-label={axis.a11yText}>
      <span className={styles.maiName}>{axis.displayName}</span>
      <div className={styles.maiTrack}>
        <div className={styles.maiFill} style={{ height: `${axis.percent}%` }} />
      </div>
      <span className={styles.maiNum}>{axis.value}</span>
      {axis.bandLabel && <span className={styles.maiBand}>{axis.bandLabel}</span>}
      {risk && <span className={styles.maiRisk}>{risk}</span>}
    </div>
  );
}
