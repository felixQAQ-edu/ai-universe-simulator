# AI Universe Simulator

[![CI](https://github.com/felixQAQ-edu/ai-universe-simulator/actions/workflows/ci.yml/badge.svg)](https://github.com/felixQAQ-edu/ai-universe-simulator/actions/workflows/ci.yml)

> 基于大语言模型的生成式、可交互、无限流文字模拟游戏平台

## 项目简介

打破固定文本边界的文字模拟游戏。玩家作为绝对变量,实时介入一个由 AI 动态编织、逻辑自洽的**涌现式世界(Emergent World)**——不是在读故事,而是在改写故事线。

**已上公网,可直接玩**:<https://wanjie-ai.fly.dev>

完整规划与逐周进展见 [docs/ROADMAP.md](docs/ROADMAP.md)(中央档案,本文只给概览);
工程纪律与它们各自防住过什么,见 [docs/engineering-practices.md](docs/engineering-practices.md)。

<!-- 占位(求职线 0.4,Felix 亲手):演示 GIF / 架构图 / 回合时序图。CC 不代做。 -->

## 核心特性

- **🌟 概念融合 · 混合模式(杀手锏)**:世界观杂交成**一个自洽世界**(非轮流播/拼接)。已落地 **识海遗蜕**(修仙 × 规则怪谈,ADR-013)与 **缺页的人防工程**(规则怪谈 × 末日生存,ADR-014);登记处 = `ArchetypeRegistry.FUSION_COMBOS`,组合**方向敏感**(反向 = 另一个尚不存在的世界,不是免费翻转)。
- **通用生成引擎(UG Engine)**:世界生成 → 角色/属性 → 规则矩阵 → 动态事件流 → 多结局收敛,所有世界共用一条管线;**加一个世界 = 后端一条元数据 + 前端一行联合类型**(代价实测见 ADR-021 §复用率读数)。
- **结构化驱动**:AI 在叙事之外输出结构化 JSON,驱动数值系统;**引擎对数值语义无知**(ADR-008)——`Engine` 遍历 `attributes` 字典通用结算,不认识任何一根轴的名字,故各世界数值轴可完全不同(见下表)。
- **语义产出方原则**(同一条规矩被反复实例化,ADR-018 §核心立字):结局极性由 AI 标、引擎只读(ADR-010);选项风险提示由 AI 写、引擎不裁决(ADR-011);数值危险等级由服务端派生、前端只渲染(ADR-018)。

## 世界

> **截至 `d0f29fe`。** 本表是快照,**登记处是 `ArchetypeRegistry` 的构造器**
> (`server/src/main/java/com/aiuniverse/server/archetype/ArchetypeRegistry.java`);
> 线上当前目录以 `GET /api/archetypes` 与[选择屏](https://wanjie-ai.fly.dev)为准。

| id | 对外名 | 数值轴(玩家可见名) | 状态 |
|---|---|---|---|
| `rules_creepy` | 规则怪谈 | 体力 / 理智 | 可玩 |
| `apocalypse` | 末日生存 | 体力 / 饥饿 | 可玩 |
| `cthulhu` | 克苏鲁 | 体力 / 理智 / 禁忌知识 | 可玩 |
| `cultivation` | 修仙 | 气血 / 灵力 / 境界 | 可玩 |
| `life_sim` | 人生模拟(《寻常》) | 气力 / 热望 / 路口 / 牵挂 | 可玩;**调优已收手**,挂账见下 |
| `animal_life` | 动物人生 | 身子 / 暖 / 地面 / 近人 | **已登记,当前实现已停止** —— 见下 |
| `cyberpunk` | 赛博朋克 | — | 枚举占位,未开放(选择屏灰显) |

每根轴带 `axisRole` / `lethal` / `perilAtHigh` 等**纯元数据标**,引擎只读其中最少的几个、不解释语义
(ADR-008 / ADR-009 / ADR-010 / ADR-018)。

## 已知缺口(如实,不粉饰)

- **《动物人生》当前实现已停止**(ADR-021 §「第二局与《动物人生》当前实现停止」)。两次真机冒烟均未过:
  第一次是**没人告诉模型该发生什么**,第二次是素材已进 prompt 而**世界从开局就没打算发生那件事**;
  根因定性为**引擎不产生事件**(FINDINGS F-028 / F-030)。**解冻条件逐字写死**:
  「**当「事件推进」这一层有了答案之后再回来**」——不设时间、不设「下一刀」。
  ⚠️ 世界仍可完整游玩,不阻断任何东西;停止的是继续调它。
- **《寻常》调优已收手,挂账未清**:`§4` 退化判据在历次冒烟里从未被验成(前置从未达成)、
  单回合体量偶发失控、热望下降不稳定、回合数超判据上界(ROADMAP v7.3「调优阶段收手」)。
  收手理由是**验证成本由人承担** —— 真机冒烟依赖作者亲自玩完一辈子(FINDINGS F-027)。
- **内容安全网关未做**:`NoopModerationGateway` 至今是放行占位,ADR-004 尚未落笔;
  `LeakDetector` 按其自陈只是**事后遥测**,抓不到改写式泄露。
  正文在[工程债 §1.2](docs/backlog-engineering-debt.md),它是软启动开闸前的最后一环。
- **单实例不是高可用,且这是有约束下的主动选择**:内存 session + 进程内忙态守卫(`compareAndSet`)
  钉死单副本,水平扩展需先把守卫与 session 外置(Redis/DB)。
  理由与代价写在 [ADR-015 §已知代价 4](docs/adr/ADR-015-overseas-deployment-form-factor.md),
  并已预登记重新审视的触发条件(「真实流量需要多副本」)。
  回合并发另有一道**准入闸**(ADR-022),它换来的是「有限名额」而不是「不会被占满」。

## 当前进度

**不在这里第二次叙述** —— 阶段、周度日志、每一刀的取证读数以
[docs/ROADMAP.md](docs/ROADMAP.md) 为准(那是中央档案)。

只记两条长期口径:

- **上线路线 B**:境外托管、不备案、暂不进微信生态,先做软启动验证;境内合规(执照 / ICP 备案 /
  微信支付)整体冻结,待回国或验证通过后解冻(ADR-015)。
- **两份「玩家看不见」的清单**:[工程债](docs/backlog-engineering-debt.md)(故障视角)与
  [求职线](docs/backlog-career-track.md)(可见性视角)—— 大面积重叠但排序判据不同。

## 技术栈

- **前端**:React + Vite(移动优先 H5)+ GSAP;**小程序 / Taro 线随路线 B 冻结**,接口纪律仍占住迁移边界(见 ADR-003 / ADR-017)
- **运行模型**:DeepSeek 为主,provider 可换(OpenAI 兼容配置表抽象)— 见 ADR-001
- **后端**:Spring Boot(编译目标 **Java 21**,以 `server/pom.xml` 的 `<java.version>` 与 `Dockerfile` 为准)— 形态见 ADR-002,已由 ADR-015 修订
- **流式传输**:Spring MVC `SseEmitter` + 可换 WebFlux 的薄接缝(`TokenStream` 解耦核心与传输)— 见 ADR-005
- **部署**:Fly.io(syd)**同源单容器** + 持久卷续局落盘 — 见 ADR-015
- **成本闸门**:全局 ¥ 双顶熔断 + 单 IP/设备日次数软闸 — 见 ADR-016
- **内容安全**:文本审核网关 **待落地**(ADR-004 未启)
- **数据**:统一 JSON Schema(世界 / 角色 / 规则 / 状态 / 行动 / 结局)— 见 [docs/CONTEXT.md](docs/CONTEXT.md)

## 本地运行

**默认走 mock provider,不花钱、不需要任何 API key**(`application.yml` 的 `aiuniverse.llm.active: mock`)。

```bash
# 后端(:8080)
cd server && ./mvnw spring-boot:run

# 前端(:5173,相对路径 /api 由 Vite 代理到 :8080,零 CORS)
cd web && npm install && npm run dev
```

跑测试:

```bash
cd server && ./mvnw test      # 后端
cd web && npm test            # 前端(另有 npm run lint / tsc -b)
```

CI(`.github/workflows/ci.yml`,push `main` + PR 触发)跑的是同样这些:
`./mvnw -B test` / `npm run lint` + `npx tsc -b` + `npm test` + `npm run build` / `npm audit`
(生产依赖 high 以上**阻塞**,全量只报不挡)。
JDK 21 与 Node 22 跟 `Dockerfile` 走 —— **跑的必须是产物构建用的那套,不是开发机上碰巧装着的那套**。

接**真实 DeepSeek**、整局闭环 curl 冒烟、换 provider:见 [server/README.md](server/README.md)。
API key 只进环境变量,**绝不写进 yaml / 代码 / 提交**。

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
- [docs/engineering-practices.md](docs/engineering-practices.md) — 工程纪律的对外面(**只收有真实实例的规矩**:规矩一句 + 一个能指回仓库的实例 + 它当时防住了什么;裁定的真理源仍在各 ADR,本文件只转写与索引)
- [docs/world-catalog-and-experience-modes.md](docs/world-catalog-and-experience-modes.md) — 世界目录与体验模式(浮世 / 深界:分类归属与大厅立字;成本分级另见世界库 backlog)
- [docs/world-ordinary-life-writing-standards.md](docs/world-ordinary-life-writing-standards.md) — 《寻常》写作标准(措辞铁律六条 / 三处留白 / 回收铁律)= 该世界写作标准的**真理源**,逐字注入 `TurnPromptBuilder` 指令槽;**两处不得漂移:先改本文件再同步 prompt**。**per-world**;族级原则见下一条
- [docs/lifetime-family-writing-standards.md](docs/lifetime-family-writing-standards.md) — 一生制**族级**写作标准(不显示岁数 / 重要的事不给专门回合 / 最后一回合是活着的)= 对族内**每个**世界都成立的原则,代码里对应 `archetype/LifetimeFamily.java` 的具名片段,世界层在原位引用(ADR-021 刀 1);**两处不得漂移由 `LifetimeFamilyLockstepTest` 守护**
- [docs/backlog-career-track.md](docs/backlog-career-track.md) — 求职线待做清单(让已有深度可见 / 关闭已承认的生产缺口 / Java 后端能力补强 / Agent 资格;与[工程债清单](docs/backlog-engineering-debt.md)大面积重叠但**排序判据不同**,重叠条目**只放指针不复制正文**)
- [AGENTS.md](AGENTS.md) — agent 常驻约束(工作流纪律 + 前端动效约束)
- [bakeoff/FINDINGS.md](bakeoff/FINDINGS.md) — 验证过程中的实测发现
