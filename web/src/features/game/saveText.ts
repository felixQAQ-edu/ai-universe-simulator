// 「我的存档」条目文案(ADR-034;纯函数,无平台 IO)。
import type { SaveSummary } from '../../state/savePointers';

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

