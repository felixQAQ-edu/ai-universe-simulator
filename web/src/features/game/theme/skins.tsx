import type { WorldSkin } from './contract';
import type { SkinId } from './registry';
import { RulesActions } from './rules/RulesActions';
import { RulesAmbient } from './rules/RulesAmbient';
import { RulesStats } from './rules/RulesStats';
import rules from './rules/rules.module.css';

// 皮肤表(ADR-018 §4.5 feature gate 的另一半):skinId → 一套形态组件 + class 令牌。
//
// **刀 1 只有规则怪谈**(试验田:先用一个世界证明这套共享基建能活在生产里,
// 而不是四个世界一起上——那样等于没有试验田)。刀 2–4 各自把自己那条加进来。
//
// 回滚路径:`registry.ts` 里把某世界的 `skin` 置回 null,该世界立刻回到旧实现,
// **不必回滚任何共享基建**;表是 Partial —— 两张表万一对不上也只是降级,不会崩。
export const SKINS: Partial<Record<SkinId, WorldSkin>> = {
  rules_creepy: {
    id: 'rules_creepy',
    screenClass: rules.screen,
    pausedClass: rules.paused,
    bannerImgClass: rules.cctvBreathe,
    Ambient: RulesAmbient,
    Stats: RulesStats,
    Actions: RulesActions,
  },
};
