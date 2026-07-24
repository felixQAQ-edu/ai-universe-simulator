import type { WorldSkin } from './contract';
import type { SkinId } from './registry';

// 皮肤表(ADR-018 §4.5 feature gate 的另一半):skinId → 一套形态组件 + class 令牌。
//
// **本刀的基建段先立空表**:四个世界此刻全部未登记 → 全部走旧实现,UI 逐像素不变。
// 下一刀(规则怪谈试验田)往这里加第一条。
//
// 表刻意是 Partial:即使 registry 登记了某个 skin 而这里漏了组件,也只是**降级回旧实现**
// 而不是崩 —— 与「四种缺省一律安全降级」同一方向。
export const SKINS: Partial<Record<SkinId, WorldSkin>> = {};
