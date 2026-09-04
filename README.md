# AI Universe Simulator

> 基于大语言模型的生成式、可交互、无限流文字模拟游戏平台

## 项目简介

打破固定文本边界的文字模拟游戏。玩家作为绝对变量,实时介入一个由 AI 动态编织、逻辑自洽的**涌现式世界(Emergent World)**——不是在读故事,而是在改写故事线。

**已上公网,可直接玩**:<https://wanjie-ai.fly.dev>

完整规划与逐周进展见 [docs/ROADMAP.md](docs/ROADMAP.md)(中央档案,本文只给概览)。

## 核心特性

- **🌟 概念融合 · 混合模式(杀手锏)**:世界观杂交成**一个自洽世界**(非轮流播/拼接)。已落地两组——**识海遗蜕**(修仙 × 规则怪谈)、**缺页的人防工程**(规则怪谈 × 末日生存)。
- **通用生成引擎(UG Engine)**:世界生成 → 角色/属性 → 规则矩阵 → 动态事件流 → 多结局收敛,所有世界共用一条管线;**加一个世界 ≈ 加一条元数据 + 一个提示词注入块**。
- **结构化驱动**:AI 在叙事之外输出结构化 JSON,驱动数值系统;**引擎对数值语义无知**(ADR-008),故各世界数值轴可完全不同——气血/灵力/境界、体力/理智、体力/饥饿、禁忌知识……
- **已实现世界(4 基础 + 2 融合)**:规则怪谈 / 末日生存 / 克苏鲁 / 修仙;人生模拟与赛博朋克为枚举占位,尚未开放。

## 当前进度

- **阶段**:Phase 3(混合模式 + 上线)收尾;**当前主线 = 前端视觉移植**(ADR-018 四刀:刀 0 severity 契约 ✅ / 刀 1 共享基建 + 规则怪谈 ✅ / 刀 2 修仙 ✅ / 刀 3 末日、刀 4 克苏鲁待起)。
- **上线路线**:走**路线 B —— 境外托管、不备案、暂不进微信生态**,先做软启动验证;境内合规(执照 / ICP 备案 / 微信支付)整体冻结,待回国或验证通过后解冻(见 ADR-015)。
- **待收口**:内容安全网关(ADR-004)—— 软启动开闸前的最后一环。

## 技术栈

- **前端**:React + Vite(移动优先 H5)+ GSAP;**小程序 / Taro 线随路线 B 冻结**,接口纪律仍占住迁移边界(见 ADR-003 / ADR-017)
- **运行模型**:DeepSeek 为主,provider 可换(OpenAI 兼容配置表抽象)— 见 ADR-001
- **后端**:Spring Boot(Java 21)— 见 ADR-005
- **部署**:Fly.io(syd)**同源单容器** + 持久卷续局落盘 — 见 ADR-015
- **成本闸门**:全局 ¥ 双顶熔断 + 单 IP/设备日次数软闸 — 见 ADR-016
- **内容安全**:文本审核网关 **待落地**(ADR-004 未启)
- **数据**:统一 JSON Schema(世界 / 角色 / 规则 / 状态 / 行动 / 结局)— 见 [docs/CONTEXT.md](docs/CONTEXT.md)

## 技术决策记录(ADR)

决策正文在 [docs/adr/](docs/adr/)。**带「状态 / 日期」两列的长文索引**在
[ROADMAP §五 · 已完成 ADR 索引](docs/ROADMAP.md#五技术决策记录adr) —— 那是长文的**唯一**一份,
本节只做一行一条的目录。待决策议题(编号 **004** 的空缺 = 内容安全,尚未落笔)同见该节。

> **下面每条的一句话取自该 ADR 文件自己的一级标题**(`# ADR-NNN · <主题>`,`adr-author` 的约定格式),
> **不是在这里手写的第二份摘要** —— 改标题即改这里,摘要有真理源。

- [ADR-001](docs/adr/ADR-001-runtime-model-and-provider-abstraction.md) — 运行模型选 DeepSeek V4-Flash 为主力,provider 走 OpenAI 兼容配置表抽象(依据:[bake-off 实测](bakeoff/out/report.md))
- [ADR-002](docs/adr/ADR-002-backend-form-factor.md) — 后端形态选 Spring Boot 运行于 CloudBase 云托管(应用层自控 + 微信原生集成)
- [ADR-003](docs/adr/ADR-003-frontend-stack-and-taro-boundary.md) — 前端栈选型与 Taro 跨端边界——React+Vite H5 先行,以接口纪律占住小程序迁移边界
- [ADR-005](docs/adr/ADR-005-sse-web-stack-mvc-thin-seam.md) — SSE/流式 web 栈选 Spring MVC(SseEmitter)+ 可换 WebFlux 的薄接缝
- [ADR-006](docs/adr/ADR-006-event-loop-streaming-wire-protocol.md) — event-loop 流式线上协议:叙事先行单次调用 + 哨兵 + 结构化尾巴 + 叙事回灌复用
- [ADR-007](docs/adr/ADR-007-world-gen-wire-protocol.md) — world-gen 线上协议:胖调用 + json_object 纯 JSON + 开场叙事 reveal 不流式(可靠性优先,异于 ADR-006)
- [ADR-008](docs/adr/ADR-008-multi-mode-extension-architecture.md) — 多模式扩展架构:引擎/校验对数值语义无知 + per-archetype 轻量元数据,以「加一个模式」的代价结构为设计目标
- [ADR-009](docs/adr/ADR-009-axis-roles-and-rule-form-flexibility.md) — 数值轴角色(depletion/accumulation)+ 规则形态弹性(isTrue 可选)——根治累积轴误判触底与非真假守则世界的骨架冲突
- [ADR-010](docs/adr/ADR-010-ending-outcome-polarity-gate.md) — 结局极性 gate——以 AI 标注的结局极性 + 引擎致命轴把关,根治濒死人物得成功结局(F-014)
- [ADR-011](docs/adr/ADR-011-action-hint-narrative-metadata.md) — 选项风险提示为叙事元数据(引擎不裁决)——#1 选择反馈定性版
- [ADR-012](docs/adr/ADR-012-hybrid-axis-merge-strategy.md) — 混合模式轴合并策略(host 优先 + 语义换皮,引擎不动)
- [ADR-013](docs/adr/ADR-013-hybrid-fusion-protocol.md) — 混合模式融合协议(内联融合 + init 双值,引擎不动)
- [ADR-014](docs/adr/ADR-014-fusion-skeleton-parameterization-and-second-combo.md) — 融合骨架参数化 + 第二组合「守则即补给」(rules_creepy × apocalypse)
- [ADR-015](docs/adr/ADR-015-overseas-deployment-form-factor.md) — 境外部署形态:同源单容器 + Spring static + 最小续局落盘(修订 ADR-002)
- [ADR-016](docs/adr/ADR-016-cost-gate.md) — 成本闸门:全局 ¥ 双顶硬熔断 + 单 IP/设备次数软闸(软启动开闸前置硬门槛)
- [ADR-017](docs/adr/ADR-017-frontend-visual-charter-and-animation-libraries.md) — 前端视觉宪法与动画库许可名单——放开动画库限制,立「界面是世界的一部分」为视觉基准(修订 ADR-003)
- [ADR-018](docs/adr/ADR-018-base-world-visual-migration-and-severity-contract.md) — 基础世界视觉移植:severity 语义契约 + 单一主题注册表 + 四刀切分
- [ADR-019](docs/adr/ADR-019-fusion-visual-entry-and-composability.md) — 融合视觉:入口互噬、局内单主语,与「四套材质覆盖六对组合」的可组合机制
- [ADR-020](docs/adr/ADR-020-lifetime-world-family-and-ordinary-life.md) — 一生制世界族与首个实例《寻常》:时间尺度扩到 per-archetype、归零不死走 `resource`、软自律以可数判据兑现
- [ADR-021](docs/adr/ADR-021-lifetime-family-layer-and-animal-life.md) — 一生制族层抽取(补上「族」这一层)与第二个实例《动物人生》
- [ADR-022](docs/adr/ADR-022-turn-admission-and-rejection-semantics.md) — 回合线程池准入与拒绝语义:让「此刻太挤」成为一个有名字的拒绝

## 文档

- [docs/ROADMAP.md](docs/ROADMAP.md) — 开发计划总览(中央档案:阶段、周度日志、ADR 索引)
- [docs/CONTEXT.md](docs/CONTEXT.md) — 术语 / 统一 JSON Schema / 跨模块约定(约定的真理之源)
- [docs/adr/](docs/adr/) — 技术决策记录
- [docs/world-catalog-and-experience-modes.md](docs/world-catalog-and-experience-modes.md) — 世界目录与体验模式(浮世 / 深界:分类归属与大厅立字;成本分级另见世界库 backlog)
- [docs/world-ordinary-life-writing-standards.md](docs/world-ordinary-life-writing-standards.md) — 《寻常》写作标准(措辞铁律六条 / 三处留白 / 回收铁律)= 该世界写作标准的**真理源**,逐字注入 `TurnPromptBuilder` 指令槽;**两处不得漂移:先改本文件再同步 prompt**。**per-world**;族级原则见下一条
- [docs/lifetime-family-writing-standards.md](docs/lifetime-family-writing-standards.md) — 一生制**族级**写作标准(不显示岁数 / 重要的事不给专门回合 / 最后一回合是活着的)= 对族内**每个**世界都成立的原则,代码里对应 `archetype/LifetimeFamily.java` 的具名片段,世界层在原位引用(ADR-021 刀 1);**两处不得漂移由 `LifetimeFamilyLockstepTest` 守护**
- [docs/backlog-career-track.md](docs/backlog-career-track.md) — 求职线待做清单(让已有深度可见 / 关闭已承认的生产缺口 / Java 后端能力补强 / Agent 资格;与[工程债清单](docs/backlog-engineering-debt.md)大面积重叠但**排序判据不同**,重叠条目**只放指针不复制正文**)
- [AGENTS.md](AGENTS.md) — agent 常驻约束(工作流纪律 + 前端动效约束)
- [bakeoff/FINDINGS.md](bakeoff/FINDINGS.md) — 验证过程中的实测发现
