// 融合入口(ADR-013 决策 4 + ADR-014 组合表泛化)的纯逻辑:误入手势状态机组合表。
// 展示层常量/纯函数,不入后端、无平台 IO(守 ADR-003);组件文件只留组件(react-refresh 约束)。

import type { Archetype } from '../../types/schema';

/**
 * 一条融合手势登记(ADR-014:泛化到「组合表」,为 round 2 留口;不做组合选择 UI):
 * 依次长按 sequence[0] → sequence[1] 的两张卡,该组合的渗漏卡渗出;点卡 → startGame(pair)。
 */
export interface FusionGesture {
  /** 组合键 `host×foreign`(与后端 FUSION_COMBOS / scene.ts 封面键同键)。 */
  key: string;
  /** 有序融合双值(host 在前,ADR-012/013),点渗漏卡 → startGame(pair)。 */
  pair: Archetype[];
  /** 误入手势的长按顺序(两步)。 */
  sequence: readonly [string, string];
}

/**
 * 已登记的融合手势(与后端已登记组合一一对应)。注意「长按规则怪谈」在两台状态机语义不同
 * (识海=第二步 / 本对=第一步)——per-combo 独立状态、各自匹配、互不干扰。
 */
export const FUSION_GESTURES: readonly FusionGesture[] = [
  {
    key: 'cultivation×rules_creepy',
    pair: ['cultivation', 'rules_creepy'],
    sequence: ['cultivation', 'rules_creepy'],
  },
  {
    key: 'rules_creepy×apocalypse',
    pair: ['rules_creepy', 'apocalypse'],
    sequence: ['rules_creepy', 'apocalypse'],
  },
];

/** 误入手势阶段:idle → armed(已长按第一张)→ revealed(再长按第二张,渗漏卡渗出)。 */
export type FusionStage = 'idle' | 'armed' | 'revealed';

/** 全组合的手势状态(key = 组合键;per-combo 独立)。 */
export type FusionStages = Readonly<Record<string, FusionStage>>;

/** 初始态:全组合 idle。零持久化 —— 状态活在选择屏组件里,离开即遗忘。 */
export const INITIAL_FUSION_STAGES: FusionStages = Object.fromEntries(
  FUSION_GESTURES.map((g) => [g.key, 'idle' as FusionStage]),
);

/** 单台状态机推进(纯函数):顺序不对不推进(但已 revealed 后保持);长按无关卡不影响。 */
function advance(stage: FusionStage, sequence: readonly [string, string], archetype: string): FusionStage {
  if (stage === 'revealed') return 'revealed';
  if (archetype === sequence[0]) return 'armed';
  if (archetype === sequence[1] && stage === 'armed') return 'revealed';
  return stage;
}

/**
 * 一次长按推进【全部】状态机(纯函数,per-combo 独立):同一张卡可以同时是 A 组合的第二步
 * 与 B 组合的第一步,各台机各自匹配、互不干扰(交叉序列不误触发)。
 */
export function nextFusionStages(stages: FusionStages, archetype: string): FusionStages {
  const next: Record<string, FusionStage> = {};
  for (const g of FUSION_GESTURES) {
    next[g.key] = advance(stages[g.key] ?? 'idle', g.sequence, archetype);
  }
  return next;
}
