// 存档区的纯函数(ADR-042 决策 4);无平台 IO(守 ADR-003)。
import type { SaveSummary } from '../../../state/savePointers';

/**
 * 大厅「继续」取哪一条:按本设备上次游玩降序的第一条「没结局、能找回」的。
 * ⚠️「能找回」只是「上次知道时能找回」—— availability 只在续局 / 回合拿到确认 404 时才改成 unavailable,
 * 没有主动探测(ADR-042 勘察 c)。没有候选 → null。
 */
export function pickContinue(saves: readonly SaveSummary[]): SaveSummary | null {
  return saves.find((s) => !s.ended && s.availability === 'available') ?? null;
}
