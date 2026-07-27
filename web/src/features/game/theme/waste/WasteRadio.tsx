import type { InterludeProps } from '../contract';
import { isDebug } from '../debug';
import { useRadio } from './useRadio';
import styles from './waste.module.css';

// 末日 · 间奏槽「手摇电台」(四层分工的第 ④层:**呈现**)。
//
// 位置是语义的一部分(ADR-018 §3 刀 3):它渲染在**正文之后、决策圈之前** ——
// **读完情境 → 电台响一下 → 做决定**,那一下响是决策前的最后一个扰动。
//
// 本文件只管**长什么样**:内容池在 `radioPool.ts`、抽取约束在 `radioDraw.ts`、
// 调度与播出状态机在 `useRadio.ts`。**加一条电台词不用碰这里,改调频节奏也不用碰这里。**
//
// 淡入淡出走 **CSS transition** 而非 GSAP(AGENTS.md §7 减债习惯:能用 CSS 的优先 CSS;
// GSAP 留给远光那种多段时间线编排)。

export function WasteRadio({ runtime, paused }: InterludeProps) {
  const { view, burst } = useRadio(runtime, paused);

  return (
    <div className={styles.radio}>
      <div className={styles.radioHead}>
        <i className={styles.radioLed} aria-hidden="true" />
        <span className={styles.radioFreq}>{view.freq}</span>
        <span className={styles.radioUnit}>MHz</span>
        <span className={styles.radioSig} aria-hidden="true">
          {view.bars}
        </span>
      </div>
      {/*
        读屏:电台是**环境**不是内容,整段设 aria-hidden —— 它不参与叙事语义,
        且逐字变化的频率读数对读屏是噪音(正文与决策圈才是内容)。
      */}
      <p
        className={[styles.radioLine, view.receiving ? styles.radioRx : null]
          .filter(Boolean)
          .join(' ')}
        style={{ opacity: view.lineOpacity }}
        aria-hidden="true"
      >
        {view.line}
      </p>
      {isDebug() && (
        <button type="button" className={styles.debugBtn} onClick={burst}>
          触发一次电波
        </button>
      )}
    </div>
  );
}
