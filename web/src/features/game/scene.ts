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
 * 融合世界(ADR-013,mode=hybrid)的专属封面:key = `host×foreign`(有序,host 在前)。
 * round 1 只一组(修仙×规则怪谈=识海遗蜕);加融合组合 = 放一张图 + 加一条映射。
 */
const FUSION_SCENES: Readonly<Record<string, string>> = {
  'cultivation×rules_creepy': '/scenes/fusion-shihai.webp',
};

/**
 * 据 world.archetypes 解析氛围底图路径(#8;ADR-013 放开单键假设)。
 * - 单体(单 key / 单元素数组)→ 已配图世界 `/scenes/<archetype>.webp`;
 * - 融合(数组 ≥2,host 在前)→ 融合专属封面(识海遗蜕);未登记组合回落 host([0])的图——
 *   绝不盲取 `[0]` 当单体键错认融合世界。
 * 未配图 → null,顶部优雅降级为纯氛围色、不显图、布局不塌。
 */
export function sceneImageUrl(archetypes: string | readonly string[] | undefined): string | null {
  if (!archetypes) return null;
  const list = typeof archetypes === 'string' ? [archetypes] : archetypes;
  if (list.length === 0) return null;
  if (list.length >= 2) {
    const fusion = FUSION_SCENES[`${list[0]}×${list[1]}`];
    if (fusion) return fusion;
    // 未登记融合组合:回落 host 的单体图(仍优雅降级)。
  }
  const host = list[0];
  return SCENE_ARCHETYPES.has(host) ? `/scenes/${host}.webp` : null;
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
