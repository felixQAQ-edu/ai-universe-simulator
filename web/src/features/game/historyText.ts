import type { HistoryGapEntry } from '../../api';

// 历史页文案(ADR-025 刀 3 口径 E/F,Felix 定稿,逐字)。纯函数,可单测。

/** 条目小标题:turn ≥ 1 与 SceneBanner 同格式「第 N 回合」;turn 0 =「开场」。 */
export function turnHeading(turn: number): string {
  return turn === 0 ? '开场' : `第 ${turn} 回合`;
}

function turnSpan(from: number, to: number): string {
  // 多回合用 en dash「–」。
  return from === to ? `第 ${from} 回合` : `第 ${from}–${to} 回合`;
}

/**
 * 缺口 → 一行或两行文案。**只标回合号,不伪造任何内容。**
 * write_failed 从 0 开始且跨多个回合 → 拆两行:「开场没有留下记录」+「第 1–N 回合没有留下记录」。
 */
export function gapLines(gap: HistoryGapEntry): string[] {
  if (gap.reason === 'before_recording') return ['更早的回合没有留下记录'];
  const { fromTurn: from, toTurn: to } = gap;
  if (from === 0) {
    if (to === 0) return ['开场没有留下记录'];
    return ['开场没有留下记录', `${turnSpan(1, to)}没有留下记录`];
  }
  return [`${turnSpan(from, to)}没有留下记录`];
}
