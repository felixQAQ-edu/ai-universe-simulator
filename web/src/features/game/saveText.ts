// 「我的存档」条目文案(ADR-034;纯函数,无平台 IO)。
import type { SaveSummary } from '../../state/savePointers';

/** 世界名为空(迁移来的旧指针)时的占位(ADR-034 已知代价 4,原文照旧;大厅继续条与存档页共用)。 */
export const UNTITLED_SAVE = '上一局(续上后显示世界名)';

/** 条目副标题:世界(单体 / 融合)· 进度 · 本设备上次游玩时间(客户端本地时钟,不是服务端时间)。 */
export function describeSave(s: SaveSummary, nameOf: (archetype: string) => string): string {
  const kind = s.archetypes.length > 0 ? s.archetypes.map(nameOf).join(' × ') : '';
  const progress = s.ended ? '已结局' : `第 ${s.turn} 回合`;
  return [kind, progress, `本设备上次游玩 ${formatLocalTime(s.lastPlayedAt)}`].filter(Boolean).join(' · ');
}

export function formatLocalTime(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}月${d.getDate()}日 ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}


/**
 * 撤销条那一句(ADR-042 决策 3 + 审定 1 / 2):恰好一次单删 → 《世界名》(空名 → 「这一局」);
 * 恰好一次「全部移除」→ N 局已结局的存档;其余并批 → N 局存档。
 */
export function removalMessage(batch: {
  entries: readonly SaveSummary[];
  kind: 'one' | 'endedAll' | 'mixed';
}): string {
  const n = batch.entries.length;
  if (batch.kind === 'one') {
    const title = batch.entries[0]?.title;
    return title ? `已从这台设备移除《${title}》` : '已从这台设备移除这一局';
  }
  if (batch.kind === 'endedAll') return `已从这台设备移除 ${n} 局已结局的存档`;
  return `已从这台设备移除 ${n} 局存档`;
}
