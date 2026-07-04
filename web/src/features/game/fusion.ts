// 融合入口(ADR-013 决策 4)的纯逻辑:误入手势状态机 + 融合双值常量。
// 展示层常量/纯函数,不入后端、无平台 IO(守 ADR-003);组件文件只留组件(react-refresh 约束)。

import type { Archetype } from '../../types/schema';

/** 融合双值(有序,host=修仙在前,ADR-012/013)。点渗漏卡 → startGame(FUSION_PAIR)。 */
export const FUSION_PAIR: Archetype[] = ['cultivation', 'rules_creepy'];

/** 误入手势阶段:idle → armed(已长按修仙)→ revealed(再长按规则怪谈,融合卡渗出)。 */
export type FusionStage = 'idle' | 'armed' | 'revealed';

/**
 * 误入手势状态机(纯函数):依次长按 修仙 → 规则怪谈 才渗出;
 * 顺序不对不推进(但已 revealed 后保持);长按其它卡不影响。
 * 零持久化 —— 状态活在选择屏组件里,离开即遗忘。
 */
export function nextFusionStage(stage: FusionStage, archetype: string): FusionStage {
  if (stage === 'revealed') return 'revealed';
  if (archetype === 'cultivation') return 'armed';
  if (archetype === 'rules_creepy' && stage === 'armed') return 'revealed';
  return stage;
}
