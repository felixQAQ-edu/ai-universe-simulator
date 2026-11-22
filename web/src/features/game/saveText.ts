// 「我的存档」条目文案(ADR-034;纯函数,无平台 IO)。
import type { SaveSummary } from '../../state/savePointers';

/** 世界名为空(迁移来的旧指针)时的占位(ADR-034 已知代价 4,原文照旧;大厅继续条与存档页共用)。 */
export const UNTITLED_SAVE = '上一局(续上后显示世界名)';

/** 世界名与小标题之间的分隔(ADR-044 待审 3;与 `describeSave` 同一个「 · 」)。 */
export const SUBTITLE_SEPARATOR = ' · ';

/**
 * 这一局有没有可显示的小标题(ADR-044)。世界名为空(迁移来的旧指针)时一律不显示 —— 那时只显示占位文案。
 * 返回小标题本身或 null;四个显示点都经这里判,不各自判。
 */
export function shownSubtitle(s: Pick<SaveSummary, 'title' | 'subtitle'>): string | null {
  return s.title && s.subtitle ? s.subtitle : null;
}

/** 一局的完整名字(撤销条与移除按钮的可访问名,书名号里那一段):「派单 · 小标题」;没有小标题 = 世界名本身。 */
export function saveName(s: Pick<SaveSummary, 'title' | 'subtitle'>): string {
  const sub = shownSubtitle(s);
  return sub ? `${s.title}${SUBTITLE_SEPARATOR}${sub}` : s.title;
}

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
 * 撤销条那一句(ADR-042 决策 3 + 审定 1 / 2):恰好一次单删 → 《世界名》(空名 → 「这一局」;有小标题 → 《世界名 · 小标题》,ADR-044);
 * 恰好一次「全部移除」→ N 局已结局的存档;其余并批 → N 局存档。
 */
export function removalMessage(batch: {
  entries: readonly SaveSummary[];
  kind: 'one' | 'endedAll' | 'mixed';
}): string {
  const n = batch.entries.length;
  if (batch.kind === 'one') {
    const first = batch.entries[0];
    return first?.title ? `已从这台设备移除《${saveName(first)}》` : '已从这台设备移除这一局';
  }
  if (batch.kind === 'endedAll') return `已从这台设备移除 ${n} 局已结局的存档`;
  return `已从这台设备移除 ${n} 局存档`;
}
