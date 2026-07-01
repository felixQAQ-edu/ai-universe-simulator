// #8 顶部氛围图(静态第 1 档)的纯函数:选图路径解析 + 危险度中英映射。
// 展示层常量,不入后端、无平台 IO(守 ADR-003)。测试直接打这两个函数。

import type { Archetype, DangerLevel } from '../../types/schema';

// 每世界一张人工审过的静态底图,放 web/public/scenes/<archetype>.webp;
// Vite 构建挂根路径 /scenes/<archetype>.webp。加新世界 = 放一张同名图即可,后端零改。
const SCENE_ARCHETYPES: ReadonlySet<string> = new Set<Archetype>([
  'rules_creepy',
  'apocalypse',
  'cthulhu',
  'cultivation',
]);

/**
 * 据 world.archetypes[0] 解析氛围底图路径(#8)。已配图世界 → `/scenes/<archetype>.webp`;
 * 未配图(未来新世界暂缺图)→ null,顶部带优雅降级为纯氛围色、不显图、布局不塌。
 */
export function sceneImageUrl(archetype: string | undefined): string | null {
  if (archetype && SCENE_ARCHETYPES.has(archetype)) {
    return `/scenes/${archetype}.webp`;
  }
  return null;
}

/** 危险度英文枚举 → 中文短词(顶部状态栏展示,纯前端常量表)。 */
const DANGER_LABEL: Record<DangerLevel, string> = {
  low: '低',
  medium: '中',
  high: '高',
  extreme: '极危',
};

/** 危险度中文标签;未知值回落原字符串(不崩、不吞信息)。 */
export function dangerLabel(level: string): string {
  return DANGER_LABEL[level as DangerLevel] ?? level;
}
