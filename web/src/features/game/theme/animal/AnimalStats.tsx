import type { AxisView, StatsProps } from '../contract';
import { JOINTS, beamPercent } from './beam';
import styles from './animal.module.css';

// 《动物人生》· 数值形态「地板线上的门缝光」(ADR-021 局内皮肤一节 决策 2)。
//
//   一个带细框的面板,没有面板头。每根轴一行:轴名 | 地板线 | 数字 | 档名。
//   地板线上贴一条 4px 的光带,从右端(门)向左伸,长度 = value%,左端是斜边;最右一道短竖线是门缝。
//
// **主题层不认轴**(ADR-018 §4.2):不读 key 判断哪一行是什么;接缝位置按行号取。状态只读 `axis.severity`。
// **单一驱动量**(§4.2.1):数字、档名、光带长度只从 `axis.value` 派生 —— 它是通用层插值中的显示值(已取整),
// 数字滚到几,光带就伸到几。本组件不另起驱动量,也**不给光带写过渡**:伸缩只是 value 逐帧变化的结果(Felix 定)。
//
// ── 光带怎么画 ───────────────────────────────────────────────────────────
// `.beamClip` 是横向裁切框;里面的 `.beam` 宽 100%、左端用固定的 clip-path 切出斜边,位置写
// `translateX((100 − v)%)`:整条向门那头平移,露在框里的正好是从右端起 v% 的一段。
// 随数值变化的属性只有 transform(AGENTS.md Motion Constraints §6)。
//
// ── 入场(一级记忆点「门缝光」)的样子在这里,驱动不在这里 ───────────────────
// 每行有一片纸色遮片 `.veil`。「暗」由 `AnimalAmbient` 在主题根上挂 `closed` class,
// `animal.module.css` 里 `.closed .veil` / `.closed .gap` 响应;摘掉后遮片按 900ms 向左端收拢。
// 本组件不收任何信号。

export function AnimalStats({ axes }: StatsProps) {
  return (
    <div className={styles.floor}>
      {axes.map((axis, i) => (
        <Row key={axis.key} axis={axis} index={i} />
      ))}
    </div>
  );
}

function Row({ axis, index }: { axis: AxisView; index: number }) {
  const state =
    axis.severity === 'danger' ? styles.danger : axis.severity === 'caution' ? styles.caution : '';
  const cls = (base: string) => (state ? `${base} ${state}` : base);
  const v = beamPercent(axis.value);
  return (
    <>
      {/* 三个可见格子对读屏隐藏:整行由地板线那一格的 aria-label 念一遍(含风险词),不念两遍。 */}
      <span className={styles.sName} aria-hidden="true">
        {axis.displayName}
      </span>
      <div
        className={cls(styles.track)}
        role="img"
        aria-label={axis.a11yText}
        data-track=""
        data-severity={axis.severity ?? ''}
      >
        <span className={styles.ground} />
        <span className={styles.joint} style={{ left: JOINTS[index % JOINTS.length] }} />
        <span className={styles.beamClip}>
          <span className={styles.beam} data-beam="" style={{ transform: `translateX(${100 - v}%)` }} />
        </span>
        <span className={styles.veil} data-veil="" />
        <span className={styles.gap} data-gap="" />
      </div>
      <span className={styles.sNum} aria-hidden="true">
        {Math.round(axis.value)}
      </span>
      {/* 小方块在 neutral / null 时不显示但占位保留,档名左缘对齐;本皮肤不显示「正常 / 注意 / 危险」这几个字。 */}
      <span className={cls(styles.sBand)} aria-hidden="true" data-band="">
        <i />
        <span>{axis.bandLabel ?? ''}</span>
      </span>
    </>
  );
}
