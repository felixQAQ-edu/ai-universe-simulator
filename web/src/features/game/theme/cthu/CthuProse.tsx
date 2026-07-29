import { useMemo, useRef } from 'react';
import type { ProseProps } from '../contract';
import { isDebug } from '../debug';
import { segmentProse } from './anomalySegment';
import { useAnomaly } from './useAnomaly';
import game from '../../game.module.css';
import styles from './cthu.module.css';

// 克苏鲁 · **正文形态**:分片壳 + 一级记忆点「文字异常」的宿主。
//
// 为什么正文需要一个形态槽,见 `contract.ts` 的 {@link ProseProps} 头注释
// (一句话:异常必须作用在字上 → 需要分片 DOM → 通用 Prose 里分支会废掉 §4.1,
// 让氛围层去 query 正文 DOM 会捅穿组件边界)。**调度器住在这里 = 它只碰自己组件的 DOM。**
//
// ── 版式与可访问性:分片必须是「无损」的 ─────────────────────────────────
//   · 容器复用通用 `.prose`(同一份排版 token),只多挂一个主题 class;
//   · 分片是一维 `<span>` 流,**不插字符、不加空白、不加 aria** —— 相邻内联元素的
//     文本拼接与原文逐字相等,故复制 / 选择 / 读屏语义全部连续(分片器侧有用例钉住);
//   · 换行仍是文本里的 `\n`(`.prose` 本就 `white-space: pre-wrap`)。
//
// ── 分片必须确定性 ────────────────────────────────────────────────────
// `useMemo` 只按 `text` 记忆,而 `segmentProse` 对同一文本恒返回同一份结果 ——
// 否则每次渲染节点重建,正在进行的异常会被冲掉、原文备份也会丢。
// 容器再按 `turn` 打 key:换回合时旧节点连同旧引用一起作废、整棵重建。

export function CthuProse({
  text,
  caret,
  runtime,
  rootRef,
  paused,
  signatureSeverity,
  turn,
}: ProseProps) {
  const proseRef = useRef<HTMLDivElement | null>(null);
  const chunks = useMemo(() => segmentProse(text), [text]);

  const controls = useAnomaly({
    runtime,
    rootRef,
    proseRef,
    chunks,
    paused,
    severity: signatureSeverity,
    turn,
  });

  return (
    <>
      <div className={`${game.prose} ${styles.prose}`} ref={proseRef} key={turn}>
        {chunks.map((c, i) => (
          // `data-i` 是调度器找目标的唯一凭据(与 chunks 下标一一对应)。
          // **平时不带任何视觉标记** —— 异常发生前后与普通文字必须完全不可区分。
          <span key={i} data-i={i} className={styles.frag}>
            {c.text}
          </span>
        ))}
        {caret && <span className={game.caret}>▍</span>}
      </div>
      {isDebug() && (
        <div className={styles.debugRow}>
          <button type="button" className={styles.debugBtn} onClick={controls.fireText}>
            触发:文字异常
          </button>
          <button type="button" className={styles.debugBtn} onClick={controls.fireInstrument}>
            触发:读数损坏
          </button>
          <button type="button" className={styles.debugBtn} onClick={controls.fireSpore}>
            触发:孢子暂停(假线索)
          </button>
        </div>
      )}
    </>
  );
}
