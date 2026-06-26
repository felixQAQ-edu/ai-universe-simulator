# AI Universe Simulator

> 基于大语言模型的生成式、可交互、无限流文字模拟游戏平台

## 项目简介

打破固定文本边界的文字模拟游戏。玩家作为绝对变量,实时介入一个由 AI 动态编织、逻辑自洽的**涌现式世界(Emergent World)**——不是在读故事,而是在改写故事线。

完整规划见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 核心特性

- **🌟 概念融合 · 混合模式(杀手锏)**:支持世界观杂交(如 修仙 × 规则怪谈、赛博朋克 × 末日生存),诞生传统配置表游戏触不到的混沌体验。
- **通用生成引擎(UG Engine)**:世界生成 → 角色/属性 → 规则矩阵 → 动态事件流 → 多结局收敛,所有模式共用一条管线。
- **结构化驱动**:AI 在叙事之外输出结构化 JSON,驱动血量 / 灵根 / San 值等数值系统,保证可玩性。
- **基础模式**:规则怪谈、人生模拟、修仙、赛博朋克、末日生存(MVP 先做规则怪谈)。

## 当前进度

- **阶段**:Phase 0 · 准备与核心验证(进行中)
- **目标用户**:主要面向中国用户(微信生态为主)
- **平台路线**:H5(响应式网页)先行 → 微信小程序(Taro)→ 可选 App
- **下一步**:定 JSON Schema + 跑通"稳定生成一个好世界、连推 10 回合不崩"

## 技术栈

- **前端**:React + Vite(H5 先行)→ Taro(微信小程序)
- **运行模型**:DeepSeek 为主,provider 可换(备选 通义千问 / Kimi / 智谱 GLM / 豆包)— 见 ADR-001
- **后端**:Spring Boot 运行于 CloudBase 云托管(见 ADR-002)
- **内容安全**:文本审核网关
- **数据**:统一 JSON Schema(世界 / 角色 / 规则 / 状态 / 行动 / 结局)

## 技术决策记录(ADR)

决策随进度建立,完整列表见 [docs/adr/](docs/adr/);首批待决策议题见 ROADMAP 第五节。

- [ADR-001](docs/adr/ADR-001-runtime-model-and-provider-abstraction.md) — 运行模型选 DeepSeek V4-Flash 为主力,provider 走 OpenAI 兼容配置表抽象(依据:[bake-off 实测](bakeoff/out/report.md))
- [ADR-002](docs/adr/ADR-002-backend-form-factor.md) — 后端形态选 Spring Boot 运行于 CloudBase 云托管(应用层自控 + 微信原生集成,薄适配层缓解锁定)
- [ADR-003](docs/adr/ADR-003-frontend-stack-and-taro-boundary.md) — 前端栈选 React+Vite H5,以接口纪律(`api/` 薄适配层 + provider-agnostic 流接口)占住 Taro 迁移边界,Phase 1 不写小程序代码
- [ADR-005](docs/adr/ADR-005-sse-web-stack-mvc-thin-seam.md) — SSE/流式 web 栈选 Spring MVC(SseEmitter)+ 可换 WebFlux 的薄接缝(`TokenStream` 解耦核心与传输)
- [ADR-006](docs/adr/ADR-006-event-loop-streaming-wire-protocol.md) — event-loop 流式线上协议选叙事先行单次调用 + 哨兵 + 结构化尾巴 + 叙事回灌复用(下游校验/引擎零改,守 ADR-005 薄接缝)
- [ADR-007](docs/adr/ADR-007-world-gen-wire-protocol.md) — world-gen 线上协议选胖调用 + 保 json_object 纯 JSON 无哨兵 + 开场叙事 reveal 不流式(可靠性留在最险的那次生成,异于 ADR-006 回合口径)
- [ADR-008](docs/adr/ADR-008-multi-mode-extension-architecture.md) — 多模式扩展架构:引擎/校验对数值语义无知 + per-archetype 轻量元数据,以「加一个模式」的代价结构为设计目标(首个落地模式=末日生存)
- [ADR-009](docs/adr/ADR-009-axis-roles-and-rule-form-flexibility.md) — 数值轴角色(depletion/accumulation)+ 规则形态弹性(isTrue 可选):引擎触底按 axisRole 二分(根治累积轴误判触底 F-012)+ 校验零分派 isTrue 可选(根治非真假守则世界冲突 F-013),golden parity 字节级守 depletion 零回归
- [ADR-010](docs/adr/ADR-010-ending-outcome-polarity-gate.md) — 结局极性 gate:结局加 AI 标注的极性 `outcome`(引擎只读)+ 致命轴 `lethal` 元数据,引擎在致命轴濒零时拒绝成功结局、确定性挑失败结局(根治濒死人物得光明结局 F-014,A 提示词软引导压不住);顺带关闭 F-015(灵力非致命轴),schemaVersion "0.3"→"0.4",golden parity 字节级守零回归

## 文档

- [docs/ROADMAP.md](docs/ROADMAP.md) — 开发计划总览(中央档案)
- [docs/adr/](docs/adr/) — 技术决策记录
