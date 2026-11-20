// 三区大厅的纯函数(ADR-041 决策 1 / 2):世界在哪一区、哪些融合组合算「已发现」、进门落在哪一区。
// 纯数据、无平台 IO(守 ADR-003);存取在 state/lobbyPrefs.ts,这里只算。

import type { ExperienceMode, FusionCombo } from '../../api';
import type { SaveSummary } from '../../state/savePointers';
import type { LobbyZone } from '../../state/lobbyPrefs';
import { fusionKey } from './fusion/drag';

/** 单体世界的分区 → 大厅区 id。渗界不是单体世界的分区(立字七:只放融合世界)。 */
export function zoneOfWorld(mode: ExperienceMode): LobbyZone {
  return mode === 'fushi' ? 'fushi' : 'shen';
}

/**
 * 已发现的融合组合键(ADR-041 决策 2):浏览器记录 ∪ 存档推导,再与后端 `fusions` 投影、前端卡文案取交集。
 *
 * - 存档推导:存档指针 `archetypes` 长度为 2 即融合局(host 在前的有序对,ADR-034)。
 * - 取交集:后端撤掉一个组合、或前端还没配卡文案时,渗界里不出现点了会 400 或渲染不出的卡。
 * - 顺序:浏览器记录的先后在前(= 发现先后),存档推导出、浏览器里没有的接在后面(按存档列表顺序,最近的在前)。
 */
export function discoveredFusions(input: {
  stored: readonly string[];
  saves: readonly SaveSummary[];
  fusions: readonly FusionCombo[];
  hasCard: (key: string) => boolean;
}): string[] {
  const legal = new Set(input.fusions.map((f) => f.key));
  const out: string[] = [];
  const push = (key: string) => {
    if (!out.includes(key) && legal.has(key) && input.hasCard(key)) out.push(key);
  };
  input.stored.forEach(push);
  for (const s of input.saves) {
    if (s.archetypes.length === 2) push(fusionKey(s.archetypes[0], s.archetypes[1]));
  }
  return out;
}

/** 还有没被发现的组合吗(渗界底句只在这时显示,审定 6)。 */
export function hasUndiscovered(
  fusions: readonly FusionCombo[],
  discovered: readonly string[],
  hasCard: (key: string) => boolean,
): boolean {
  return fusions.some((f) => hasCard(f.key) && !discovered.includes(f.key));
}

/**
 * 进门落在哪一区(决策 2 / 审定 4):有记录且合法 → 用它;记的是渗界但已发现为空 → 浮世;
 * 没有记录 → 浮世(含老玩家与系统深色玩家)。
 */
export function initialZone(stored: LobbyZone | null, discoveredCount: number): LobbyZone {
  if (stored === 'rong' && discoveredCount === 0) return 'fushi';
  return stored ?? 'fushi';
}
