import type { AxisView, StatsProps } from '../contract';
import { severityWord } from '../contract';
import styles from './waste.module.css';

// 末日 · 数值形态「磨损机械仪表」(样板间冻结成果移植)。
//
// 三种形态并列看:规则怪谈 = 横向分段 OSD(监控)/ 修仙 = 竖向光带 + 玉印(脉)/
// 末日 = **带指针的圆表盘**(机械)。同一份 `AxisView` 喂三种形态。
//
// **主题层不得重新解释轴语义**(ADR-018 §4.2,本刀第四次实例化):
// 表盘的告警状态只认 `axis.severity`(服务端派生)—— 样板间那版写的是
// `value < 35 ? "is-low"`,移植时**必须换掉而不是照抄**:末日的「饥饿」轴数值越高越糟,
// 按 `< 35` 判会把「饥饿 20 = 还不饿」误报成告警。
//
// **指针角度不是语义**:`percent` → 角度是纯几何映射(0–100 映到 -110°–110°),
// 与「危不危险」无关,故可以在主题层算。
//
// **数值滚动不在本文件**:`axis.value` 已是通用层插值后的当前显示值(`useAnimatedValues`),
// 指针、数字、档名、告警灯全部由同一个值派生 —— 同帧翻转(§4.2.1)。
// 样板间那版自己跑了一条 GSAP 补间来滚数字与指针,移植时一并去掉。

/** 表盘指针角度:0–100 → -110°–110°(纯几何,不含语义)。 */
const needleAngle = (percent: number) => -110 + (percent / 100) * 220;

export function WasteStats({ axes }: StatsProps) {
  return (
    <div className={styles.dials}>
      {axes.map((axis) => (
        <Dial key={axis.key} axis={axis} />
      ))}
    </div>
  );
}

/** 告警状态:**只认 severity**,不认 key、不认 label、不认数值高低。 */
function severityClass(axis: AxisView): string {
  if (axis.severity === 'danger') return styles.danger;
  if (axis.severity === 'caution') return styles.caution;
  return '';
}

function Dial({ axis }: { axis: AxisView }) {
  // 风险字样只在 caution / danger 露面;常态不加字(完整语义仍在 a11yText 里交给读屏)。
  const risk = axis.severity === 'neutral' ? null : severityWord(axis.severity);
  const cls = [styles.dial, severityClass(axis)].filter(Boolean).join(' ');
  return (
    <div className={cls} aria-label={axis.a11yText}>
      <div className={styles.dialFace} aria-hidden="true">
        <div className={styles.dialArc} />
        <div
          className={styles.dialNeedle}
          style={{ transform: `rotate(${needleAngle(axis.percent)}deg)` }}
        />
        <div className={styles.dialCap} />
        <div className={styles.dialScratch} />
      </div>
      <span className={styles.dialLabel}>{axis.displayName}</span>
      <span className={styles.dialNum}>{axis.value}</span>
      {axis.bandLabel && <span className={styles.dialBand}>{axis.bandLabel}</span>}
      {risk && <span className={styles.dialRisk}>{risk}</span>}
    </div>
  );
}
