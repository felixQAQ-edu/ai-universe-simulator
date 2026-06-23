# web/src 目录结构

Phase 1 单模式 H5 闭环(规则怪谈)。跨端边界纪律见 [ADR-003](../../docs/adr/ADR-003-frontend-stack-and-taro-boundary.md):
**逻辑/状态/类型/展示层平台无关**(Taro 直接复用,禁 import `fetch`/`EventSource`/`wx.*`),
**平台 IO 全收进 `api/` 薄适配层**(迁移时只换它)。该硬线由 `eslint.config.js` 的
`no-restricted-globals` 规则守住(`api/` 与测试豁免)。

| 目录 | 现状 | 职责 | 平台无关? |
|------|------|------|-----------|
| `types/` | `schema.ts` | 统一 JSON Schema(CONTEXT §二 v0.2)的 TS 类型,真理之源在 `docs/CONTEXT.md` | ✅ |
| `api/` | `contract.ts`/`sse.ts`/`h5GameApi.ts` | **平台适配层**:对上暴露 provider-agnostic 契约(`GameApi`/`TurnStream`),对下 H5 用 `fetch`+SSE 实现(ADR-006/007 wire);Phase 4 换 WS 只新增一个实现 | ❌(唯一允许碰平台 IO) |
| `state/` | `gameStore.ts` | 整局状态(Zustand):消毒 client world + 流式增量(narrative 累加 / delta 落面板 / ending 出画面 / error 处置);只依赖 `api/` 契约 | ✅ |
| `features/game/` | 回合循环 UI | 散文区(逐字)+ 决策圈 + 数值/规则面板 + 开场 reveal + 整局流程容器 | ✅ |
| `features/share/` | 占位 | 精彩世界线 / 奇葩结局长图分享(Phase 2) | — |

> 注:`api/` 实现用 `fetch`+流式读 body 解析 SSE,**不是浏览器原生 `EventSource`** —— 回合端点是
> `POST` 带 body,`EventSource` 只能 GET 无 body,接不了(见 ADR-003 与 `api/sse.ts` 顶注)。
> 运行模型 = DeepSeek(给玩家生成);写码 = Claude Code。两者别混(见 CLAUDE.md)。
