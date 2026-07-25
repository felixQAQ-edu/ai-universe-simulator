import { useEffect, useRef, useState } from 'react';
import type { ActionsProps } from '../contract';
import styles from './xian.module.css';

// 修仙 · 决策圈形态「玉简签」(样板间冻结成果移植)。
// 对角切角(clip-path)+ 金色签头 + 宽度刻意不齐(避免表单感)+ 按压缓出发光。
// 反馈是**缓出**、有余韵(`--t-dur`/`--t-ease` = 0.55s + 缓出长尾),与规则怪谈的阶跃相反。
//
// 语义与通用 DecisionCircle 完全一致:只回传 id、hint 是叙事元数据(ADR-011);
// 主题层只换形态、不改交互语义。整张签(含内边距)都是按钮本体,不是只有文字区可点。

/** 墨色确认停留时长(钟鸣时间轴 t=0 的那一下)。 */
const ACK_MS = 480;

export function XianActions({ actions, disabled, onChoose }: ActionsProps) {
  // 「输入已接收」的即时确认。生产与样板间的结构差异:这里按下之后要等好几秒世界才回话,
  // 所以这一下确认比样板间更要紧 —— 没有它,>800ms 的停顿会被当成点击没生效。
  const [ack, setAck] = useState<string | null>(null);
  const timer = useRef<number | null>(null);

  // 卸载即清定时器(AGENTS.md §5:所有定时器必须清)。本组件不属皮肤 runtime 的编排,
  // 是纯 React 局部状态,故就地清理。
  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
    },
    [],
  );

  const choose = (id: string) => {
    setAck(id);
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setAck(null), ACK_MS);
    onChoose(id);
  };

  return (
    <div className={styles.slips}>
      {actions.map((a) => (
        <button
          key={a.id}
          type="button"
          // 空段一律滤掉:class 属性里不留多余空格(同刀 1 对拍口径)。
          className={[styles.slip, ack === a.id ? styles.slipAck : null].filter(Boolean).join(' ')}
          disabled={disabled}
          onClick={() => choose(a.id)}
        >
          <span className={styles.slipText}>{a.text}</span>
          {/* 定性风险提示(ADR-011):缺失时不渲染、不占位。 */}
          {a.hint && <span className={styles.slipHint}>{a.hint}</span>}
        </button>
      ))}
    </div>
  );
}
