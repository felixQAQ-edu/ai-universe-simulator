# web/src 目录结构

Phase 0 脚手架。当前只落了类型与一个占位页;带 `.gitkeep` 的目录是为 Phase 1 预留的位置,**现在均未实现**。

| 目录 | 现状 | 用途(Phase 1+) |
|------|------|------------------|
| `types/` | ✅ `schema.ts` | 统一 JSON Schema(CONTEXT §二 v0.2)的 TS 类型,真理之源在 `docs/CONTEXT.md` |
| `data/` | ✅ `sampleWorld.ts` | 硬编码示例 state,仅供脚手架验证;接入运行模型后删除 |
| `features/game/` | 占位 | 规则怪谈游戏界面 + 决策圈交互(回合推进、状态渲染) |
| `features/share/` | 占位 | 精彩世界线 / 奇葩结局长图分享(Phase 2) |
| `state/` | 占位 | 游戏状态管理(state 是真理之源,每回合回传;本地存档) |
| `api/` | 占位 | 运行模型(DeepSeek,ADR-001)调用与 provider 适配层 |

> 约束:`api/` 的真实 LLM 调用、`features/` 的真实流程都属于 Phase 1,脚手架阶段不碰。
> 运行模型 = DeepSeek(给玩家生成);写码 = Claude Code。两者别混(见 CLAUDE.md)。
