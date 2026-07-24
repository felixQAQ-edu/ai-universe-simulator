// #8 顶部氛围图的纯函数:危险度中英映射。
// 展示层常量,不入后端、无平台 IO(守 ADR-003)。测试直接打这个函数。
//
// **迁出说明(ADR-018 刀 1)**:原 `sceneImageUrl`(单体 + 融合封面两张分派表)已收编进
// 单一主题注册表 `theme/registry.ts` —— 世界判定只发生一次(§4.1),封面只是那次判定的一个字段。
// 本文件只留与世界无关的枚举映射。

import type { DangerLevel } from '../../types/schema';

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
