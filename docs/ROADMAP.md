# AI Universe Simulator · 开发计划总览

> 本文档是本项目的"中央档案"。每次开新对话时,把它上传或粘贴给 Claude,即可让它立刻理解全局规划、当前进度与已做决策。
> 配套:`docs/adr/`(技术决策记录)。

---

## 一、项目元信息

| 字段 | 内容 |
|------|------|
| 项目类型 | 基于大语言模型的生成式、可交互、无限流文字模拟游戏平台 |
| 当前阶段 | Phase 1 · 单模式 H5 闭环(规则怪谈)进行中 🟨(后端整局闭环 init→回合→结局贯通;前端 H5 整局闭环已落并 **ff 合并 `main`**;**真 key e2e 首次通关验过——里程碑「朋友能用手机从头玩到一个完整结局」技术闭环达成**;措辞「闭环达成,可玩性打磨中」:行保 🟨 到 A 计划(叙事长度约束 A-1 + 规则高亮 A-2)做完。当前焦点:干净 `main` 起 `phase1/polish` 做 A-1/A-2) |
| 目标用户 | 主要面向中国用户(微信生态为主) |
| 平台路线 | H5(响应式网页)先行 → 微信小程序(Taro)→ 可选 App 套壳 |
| 投入节奏 | 业余,每周 10–20 小时 |
| 终极交付物 | 一个能在手机上玩、能分享、能开始收费的生成式文字游戏 |
| 核心杀手锏 | **概念融合 · 混合模式**(世界观杂交,如修仙 × 规则怪谈) |

> 关系说明:本项目是独立的产品仓库,与「AI 后端架构师一年计划」并行;两者的工程纪律(ADR / ROADMAP / 复盘)共用一套方法,但代码与路线各自独立。

---

## 二、完整路线图(按每周 10–20 小时估算)

### Phase 0 · 准备与核心验证 [第 1–3 周]
**核心理念**:先验证最大风险——AI 能不能稳定生成一个好玩、自洽、可连续推进的世界。这步过了,后面都是工程。
- 定 JSON Schema、选运行模型、实测每回合成本与延迟
- 在脚本里跑通"生成一个好世界 + 连推 10 回合不崩"
- 初始化前端工程(React + Vite)、配好 git 与 Claude Code 工作流
- **里程碑**:一个世界能连续推进 10 回合,属性/规则/剧情前后自洽

### Phase 1 · 单模式 H5 闭环(规则怪谈)[第 4–9 周]
**核心理念**:尽快做出第一个能玩、能发给朋友的东西。
- 移动优先 H5 界面 + "玩家决策圈"交互
- 规则怪谈一条龙:输入 → 世界生成 → 抉择推进 → 结局结算
- 游戏状态管理(状态是真理之源,每回合回传)+ 本地存档
- 部署上线(手机浏览器 / 微信内可打开)
- **里程碑**:朋友能用手机从头玩到一个完整结局

### Phase 2 · 多模式 + 打磨 + 分享 + 云存档 [第 10–16 周]
- 加入人生模拟、修仙(复用同一套生成引擎)
- "精彩世界线 / 奇葩结局"一键生成长图分享
- 账号 + 云存档;接入内容安全审核网关
- 成本控制:日志摘要压缩、分层模型、限流
- **里程碑**:三模式可玩、可分享、可登录存档,单回合成本可控

### Phase 3 · 混合模式 + 变现 + 软启动 [第 17–22 周]
- 杀手锏:生成管线前加一层"世界融合"meta-prompt(先彩蛋验证,再开放自由勾选 2–3 模式)
- 变现:每日免费次数 + 会员(更强模型 / 无限次 / 混合模式解锁),接微信支付
- 引导、异常处理、限流、数据埋点
- 软启动:放给一小撮真实用户
- **里程碑**:可对外发布、能开始收钱、能看到真实使用数据

### Phase 4 · 微信小程序化 + 增长 [第 23 周起 · 第 5–6 个月]
- 用 Taro 把 H5 逻辑移植为微信小程序(React 复用,改动最小)
- 走完小程序类目与审核;确认变现合规
- "世界线广场"内容发现;持续优化性能、留存、付费
- **里程碑**:小程序上线,关键指标进入可优化循环

> 节奏预期:第一个能玩的版本约第 2 个月底;完整版(多模式 + 混合 + 支付 + 小程序)约第 5–6 个月。业余开发最大的敌人是范围膨胀——每个阶段宁可砍功能,也要按时拿出"能给人看的产出"。

---

## 三、技术选型与架构(倾向方案,正式决策走 ADR)

- **前端**:React + Vite,先做响应式 H5 → 后期 Taro 编译微信小程序
- **运行模型**:倾向 DeepSeek 为主(便宜、结构化输出强、OpenAI 兼容),架构做成 **provider 可换**;备选 通义千问 / Kimi / 智谱 GLM / 豆包 → 见 ADR-001
- **后端形态**:**未定**,两条路差异大 → 见 ADR-002
- **内容安全**:文本审核网关(必须,从架构第一天纳入)
- **状态管理**:游戏状态为真理之源,每回合回传模型;老日志做摘要压缩
- **统一数据结构**:一套 JSON Schema(世界 / 角色属性 / 规则 / 当前状态 / 可选行动 / 结局)

> 注:写代码用 Claude / Claude Code(build-time);给玩家实时生成用国产模型(run-time)。这是两件事。

---

## 四、进度追踪表

> 每周末花 10 分钟更新。状态符号:⬜ 未开始 / 🟨 进行中 / ✅ 已完成 / ⚠️ 阻塞

| 阶段 | 周 | 核心任务 | 状态 | 完成日期 | 主要产出 / 链接 | 备注 |
|------|----|---------|------|---------|----------------|------|
| Phase 0 | W1–3 | 核心验证:Schema + 稳定生成 + 连推 10 回合 | ✅ | 2026-06-18 | [ADR-001](adr/ADR-001-runtime-model-and-provider-abstraction.md) · [ADR-002](adr/ADR-002-backend-form-factor.md) · [bakeoff/](../bakeoff/) · CONTEXT v0.2 · [web/](../web/) | provider bake-off ✅(连推 10 回合自洽里程碑达成);ADR-001/002 已采纳;前端工程(React + Vite + TS,scaffold)初始化完成,schema v0.2 已落 TS 类型 → **Phase 0 整体收口** |
| Phase 1 | W4–9 | 单模式 H5 闭环(规则怪谈) | 🟨 | | [server/](../server/) · [web/](../web/) · [ADR-003](adr/ADR-003-frontend-stack-and-taro-boundary.md) · [ADR-005](adr/ADR-005-sse-web-stack-mvc-thin-seam.md) · [ADR-006](adr/ADR-006-event-loop-streaming-wire-protocol.md) · [event-loop 规格](phase1-event-loop-contract-and-state-machine.md) · [ADR-007](adr/ADR-007-world-gen-wire-protocol.md) · [world-gen 设计稿](phase1-world-gen-and-initialization.md) · CONTEXT v0.5 | 开工:Spring Boot 4.1(编译目标 Java 21/Maven)后端骨架落 `server/`,provider 抽象(`LlmClient`/`LlmProperties` 配置表 + mock 实现)+ 审核网关 no-op 接缝 + SSE 冒烟端点本地跑通(逐字流式,印证 ADR-002 承重假设本地侧);ADR-005(SSE web 栈:MVC + 薄接缝)已采纳。**已接真实 DeepSeek**(`OpenAiCompatLlmClient`,OpenAI 兼容流式,真实 token 经现有 `TokenStream` 接缝流到 SSE、web 层一行未动,15 测全绿;手动集成冒烟待 key 就位)。**event-loop 契约/状态机设计定稿**(规格入库 + ADR-006 流式线上协议采纳 + CONTEXT v0.3:数值权威/三视图消毒)——设计检查点,**业务代码未写**。**event-loop 第一批(数据面内核)已落**:bake-off 已验证逻辑移植为 Java(`server/.../engine/`:`Engine.apply` 数值结算/log 折叠/§5 兜底、`GameSchemas` 校验、`LeakDetector` 遥测、`LooseJson`、`toClientState` 消毒投影),**golden parity** 锁死(Java 逐字段 == Python 三路径 ×10 回合)+ 加固(结局三子分支 + 校验器 accept-parity),`mvn test` 93 绿(见 F-007:Boot 4.1 锁 Jackson 3 → 手写校验器)。**event-loop 第二批(控制面)已落——业务代码首次成型**:`server/.../eventloop/` 起 `SentinelSplitter`(哨兵切分,跨 chunk)+ `TurnReinfuser`(叙事回灌)+ `TurnStateMachine`/守卫(合法性 + 忙态 `ConcurrentHashMap`+`compareAndSet`)+ `EventLoopService`(`TurnExecutor`:驱动流、按序发 SSE narrative→delta→ending、一次修复开回 json_object 回灌同一 N、保守 no-op 降级、兜底结局接线、出网过 `toClientState` 消毒)+ web 接缝(`SseTurnEventSink`/`GameController` `POST /api/game/{id}/turn`);`prompts/event-loop.md` v0.2(prose+哨兵+尾巴去 narrative)。**transform parity** 把 golden 护城河延伸到切分+回灌(新线上格式经切分回灌后逐字段 == 旧单 JSON 端状态),`mvn test` 117 绿。**event-loop 第三批(world-gen + INITIALIZING,上游补全)已落——整局闭环后端贯通**:`server/.../worldgen/` 起 `WorldGenService`(胖调用 + json_object + `LooseJson` + `validateWorld` + 一次修复 + 救不回整局 ERROR)+ `GameInitService`(播种编排:提取 transient `openingNarrative`、解析初始动作含 FALLBACK、玩家可见文本过 moderation 接缝、剥 `availableActions`/`openingNarrative` 后播种、出网过 `toClientState` 消毒)+ `WorldGenPromptBuilder`/`WorldGenException`/`InitResponse`;web 接缝起真实 **`POST /api/game/init`**(plain POST 无 SSE,ERROR→502)、**退役 dev-init 桩**;`prompts/world-gen.md` 生产化(json_object + `openingNarrative` + 初始 actions + id 类型约定 + 泄露硬化)。**world-gen parity** 复用 8 golden world raw(raw→`validateWorld`→播种,断言 init 投影无 `isTrue`/`hiddenLogic` + 会话 AWAITING_ACTION + 数值/FALLBACK 动作播种正确)+ 修复路径 + ERROR 路径(不进半残 PLAYING)+ init 消毒独立硬闸 + moderation 接缝被调用 + 生产形态(world 带动作/openingNarrative)。决策 [ADR-007](adr/ADR-007-world-gen-wire-protocol.md)(world-gen 保 json_object 纯 JSON 无哨兵、开场叙事 reveal 不流式——可靠性优先,异于 ADR-006 回合口径)+ CONTEXT v0.4(§三.10/11/12)。`mvn test` **139 绿**(+18),commit @ `phase1/world-gen`。**偏差如实记**:8 golden world raw 早于本批,无 `availableActions`/`openingNarrative` → 播种走 FALLBACK(沿用 bake-off),`validateWorld` 保持零改(两字段在校验层仍可选,守 8 golden accept-parity)。**未碰 CloudBase 部署;真 key 整局通关冒烟(含递延 ending + §5 触底兜底端到端)待 `DEEPSEEK_API_KEY` 就位**。**前端 H5 整局闭环已落**(ADR-003 采纳):`web/src/api/`(provider-agnostic 契约 `GameApi`/`TurnStream` + `h5GameApi` fetch+流式 SSE 实现,接 ADR-006/007 wire)+ `web/src/state/gameStore.ts`(Zustand:消毒 client world + 流式增量,可注入 mock 的 `createGameStore`)+ `web/src/features/game/`(散文区逐字/开场 client-side reveal + 决策圈只选 id + hp/san&规则面板 + 整局流程容器:start→loading→reveal→回合→结局/ERROR 重生成);**边界硬线** eslint `no-restricted-globals` 禁逻辑/状态/展示层引用 `fetch`/`EventSource`/`wx.*`(`api/` 与测试豁免)。**14 测绿**:api 层(mock fetch + 合成 SSE 字节流跨 chunk 切分 + 四类事件分发 + init 解析 + HTTP/网络失败归一)、状态层(合成事件序列推进,含 ending/error/守卫分支);lint/build 全绿。**偏差如实记**:回合端点是 `POST` 带 body,浏览器原生 `EventSource` 只能 GET 无 body、接不了 → H5 改用 `fetch`+`ReadableStream` 解析 SSE(ADR-003 边界不受影响,逻辑层依旧只见 `TurnStream`)。决策 [ADR-003](adr/ADR-003-frontend-stack-and-taro-boundary.md)(接口纪律占 Taro 边界、不写小程序代码)+ CONTEXT v0.5(§三.13 前端边界)。commit `4eceec3`(ADR 落档)/ `78bd5d6`(实现)@ `phase1/frontend-h5`。**手动 e2e 首次通关已验过(真 key,Felix 以玩家视角玩通一局规则怪谈)→ 里程碑「朋友能用手机从头玩到一个完整结局」技术闭环达成;前端批 ff 合并 `main`(`4eceec3`/`78bd5d6`/`0b1ebeb`/`02f2faf`),旧分支 `phase1/frontend-h5`/`phase1/event-loop-core` 已删。** e2e 暴露的全是**可玩性打磨**(非闭环能否跑,见 backlog):A 计划只做两条——**A-1 叙事长度约束**(提示词层,所有世界共用)+ **A-2 规则高亮**(前端把已有 `discoveredRules` 数据点亮),其余(配图/难度/多世界)入 Phase 2/3 待办。**行保 🟨 到 A 计划做完**(措辞「闭环达成,可玩性打磨中」) |
| Phase 2 | W10–16 | 多模式 + 分享 + 云存档 + 成本控制 | ⬜ | | | |
| Phase 3 | W17–22 | 混合模式 + 变现 + 软启动 | ⬜ | | | |
| Phase 4 | W23+ | 微信小程序化 + 增长 | ⬜ | | | 第 5–6 月 |

### 周度日志(最近三周,循环覆盖)

**Week 1(2026-06-15 ~ 2026-06-21)**
- **完成(补)**:前端工程初始化(Phase 0 末项)——`npm create vite` 起 React + Vite + TypeScript 工程,落在仓库 `web/` 子目录(为 ADR-002 的 Spring Boot 后端留清晰边界);移动优先基底(viewport / safe-area / 深浅色)+ Phase 1 目录占位(`features/` `state/` `api/`,均未实现);把 CONTEXT §二 统一 JSON Schema v0.2 落成 TS 类型(`src/types/schema.ts`,严守 rules[].id 整数 / endings[].id 字符串 / attributes 必填),并用一份硬编码示例 state 渲染占位页验证「工程能跑、类型能用」(build/lint/dev 全绿)。**scaffold only,未接 LLM、未实现规则怪谈流程。Phase 0 整体收口。**
- **完成**:provider bake-off 跑通——`bakeoff/` 脚本(统一 OpenAI 兼容客户端 + provider 配置表 + 状态机引擎 + 场景组 A/B + 报告 + 盲评生成器),实跑 DeepSeek V4-Flash。工程指标全部达标(JSON 修复后/首次 100%、TTFT~1s、回合~5.8s、单回合~¥0.002、0 泄露、0 错误、连推 10 回合三路径自洽);人工盲评(ADR-001 §6)综合 ~4.4 通过。**Phase 0 核心验证里程碑(连推 10 回合自洽)达成。**
- **决策**:ADR-001(运行模型与 provider 抽象选型)已采纳——DeepSeek V4-Flash 为 event-loop 主力,provider 走 OpenAI 兼容配置表抽象;「改配置即可换 provider」假设实测成立。
- **卡点**:bake-off 暴露 5 条 schema/质量问题(`bakeoff/FINDINGS.md` F-001~F-005),F-001~F-004 已 unblock(收敛进 CONTEXT v0.2);F-005(单一种子致沉浸感套路化,沉浸感 3.25)挂 Phase 1 world-gen 提示词待办,不影响主力决策。
- **决策(补)**:ADR-002(后端形态)已采纳——选方案 C「Spring Boot 运行于 CloudBase 云托管」:应用层经验(SSE/LLM 代理/计费)照搬 + 微信原生集成 + 免小程序域名白名单,以薄适配层缓解平台锁定(沿用 ADR-001 抽象哲学)。
- **完成(Phase 1 开工)**:按 ADR-002 起 **Spring Boot 4.1(编译目标 Java 21 / Maven)后端骨架**,落仓库 `server/`(与 `web/` 平级)。包结构落 ADR-001 provider 抽象:`llm/`(平台无关核心)`LlmClient` + `TokenStream`(最小流式 sink)+ `LlmProperties` 配置表(对应 bakeoff `providers.py`,key 只存环境变量名)+ `ThinkingAdapter`(占位)+ `MockLlmClient`(逐字 echo);`moderation/` 审核网关 no-op 接缝(ADR-004 未定)；`web/StreamController` 薄 SSE 适配(唯一碰 `SseEmitter`);`platform/` CloudBase/微信薄适配层占位。冒烟:`mvn test` 上下文绿、`POST /api/dev/echo-stream` 本地逐字流式返回、空 prompt → 400。**skeleton only · mock 实现,未接真实 DeepSeek、未实现规则怪谈业务/状态机、未碰 CloudBase 部署/ICP 备案。**
- **决策(补)**:ADR-005(SSE/流式 web 栈)已采纳——选 **Spring MVC(SseEmitter)+ 可换 WebFlux 的薄接缝**:核心只把 token 吐给最小 `TokenStream` sink,web 层薄适配桥到 SSE,日后换 WebFlux 只动 web 层;骨架阶段优先出闭环、复用命令式 bake-off client 心智,响应式留作日后独立深啃。本地 SSE 通路跑通,印证 ADR-002 承重假设(云托管 SSE)的本地侧。
- **完成(工具链修缮)**:Spring Boot parent 升 **3.5.3 → 4.1.0**(编译目标保持 Java 21)。起因:初版踩了两个坑——Initializr 返回的版本号带 `.RELEASE` 后缀(Boot 2.x 后已废,Central 无此物),且当时拿 `search.maven.org` solr 索引交叉核对而它滞后(只到 3.5.3),误选了 3.5.3。复核 Central 权威 `maven-metadata.xml` 确认 3.5.15 / 4.1.0 均在;Central 本身不滞后,无需换源。Boot 4 支持到 JDK 26 → 本机默认 JDK 直接跑,**去掉 `JAVA_HOME=21` workaround**(README 同步)。冒烟照旧:`mvn test` 绿、SSE 逐字流式、空 prompt → 400。ADR 不改。
- **完成(接真实 DeepSeek)**:在 `LlmClient` 下新增 OpenAI 兼容实现 `OpenAiCompatLlmClient`(JDK `HttpClient`,`stream=true`),真实 token 经现有 `TokenStream` 接缝流到 SSE、**web 层一行未动**(印证 ADR-005 接缝设计成立);**零新增依赖**(JDK `HttpClient` + 随 starter-web 来的 Jackson)。新增 `OpenAiStreamDecoder`(纯 SSE 解析,移植自 bakeoff `client.py` 的 chunk 消费循环)、`LlmException`(缺 key/网络失败/非 200/流中断统一干净降级,不泄露 key 给前端)、`LlmClientConfig`(按 `aiuniverse.llm.active` 选实现,mock 作默认/回退、未知 active fail-fast);`ThinkingAdapter` 从占位 throw 改为移植 bakeoff `_thinking_extra_body`(deepseek/dashscope/bigmodel 分派,ADR-001 §5.2 单点)。**走完整 TDD 红绿**:用一段录制的 DeepSeek SSE 样本做单测(解析→token 序列)+ 配置绑定选择 + 错误映射 + 缺 key 降级,共 15 测全绿;单测不打真实 API(确定性、零成本),手动集成冒烟(挂真 key curl 端点看逐字流)留待 `DEEPSEEK_API_KEY` 就位后跑。**安全核查**:git 历史确认 key 明文从未提交(`.env` 已 gitignore、只 `.env.example` 占位),无需重置。**仍是接缝/通路工作,未碰规则怪谈业务 / event-loop / 状态机。**
- **卡点(已 unblock)**:Spring Boot 4 用 **Jackson 3**(包名 `com.fasterxml.jackson` → `tools.jackson`,异常改为 unchecked)+ 自动配置类包路径变动 → import 迁移、测试改直接提供 `ObjectMapper` bean 后全绿。
- **完成(event-loop 契约/状态机设计定稿)**:把 bake-off 已验证逻辑(`scenarios.py` 的 `Engine` + `client.py` + `schema.py`)形式化为生产实现蓝本——[event-loop 契约 + 状态机规格](phase1-event-loop-contract-and-state-machine.md)入库 `docs/`(整局/单回合状态机、线上契约、数值结算、可靠性/降级、log 压缩、消毒边界,逐项标「已验证 / 🆕 新设计」)。**决策**:ADR-006(event-loop 流式线上协议)已采纳——选**叙事先行单次调用 + 哨兵 `<<<DELTA>>>` + 结构化尾巴 + 叙事回灌**:服务端回灌叙事使 `validate_turn`/`detect_leak`/`Engine.apply` 原样复用(下游零改),贴 ~¥0.002 成本、守 ADR-005 薄接缝;两次调用(方案 B)留作埋点示警后的可切换后路。**约定回写**:CONTEXT 升 **v0.3**——§三.8 数值权威(AI 提议绝对值、引擎三道闸门落账)+ §三.9 state 三视图与消毒边界(`detect_leak` 流式路径降为遥测);**JSON `schemaVersion` 仍 "0.2"(字段未变),仅文档版本号升**。**设计检查点,非阶段完成——event-loop 业务代码尚未起。**
- **完成(event-loop 内核移植 · 数据面)**:按内核先行切分起 Phase 1 event-loop 第一批,落 `server/.../engine/`。移植 bake-off 已验证逻辑为 Java(parity 锁死):`Engine.apply()`(§5 序列 1–10:绝对值/跳变>40 标记不拒绝/clamp/triggered+discovered/log 折叠 LOG_KEEP=4 抽取式/结局判定/§5 兜底结局 id 补丁)、`GameSchemas`(WORLD/TURN_SCHEMA + §10 结局回合 actions 放宽)、`LeakDetector`(字段名 + hiddenLogic 逐字≥8 子串,§1c 遥测语义)、`LooseJson`(剥围栏/取首 {…}/降级)。新建 `Engine.toClientState()` 消毒投影(递归剥 isTrue/hiddenLogic/isCorrect/groundTruth,出网必经)。**golden parity**:`bakeoff/replay_golden.py` 重放 30 event-loop + 8 world-gen 录制 raw 经 Python `Engine` 导出端状态夹具,Java 移植逐字段 == Python 三路径(B1/B2/B3 × T1–T10);三路径均干净跑满 10 回合(ongoing/未触底)→ §5 兜底/结局结算/跳变标记不被 golden 覆盖,各配专门单测。**加固两条(commit `4e66056`)**:(1) 结局结算三子分支单测(golden 盲区)——AI 合法 ending(未触底)、触底无 condition 匹配→约定 fallback(首条)、AI 幽灵 ending.id→不接受;顺带把 `apply()` 结局接受 gate 于 id 存在性(规格 §4.4,golden 无 ending 故 golden-safe)。(2) 校验器 accept-parity——`gen_validator_parity.py` 离线把 38 条真实 raw 过 Python `schema.py` 导出 verdict 夹具,Java `GameSchemas` 逐条比对(钉「不误杀 Python 接受的输入」;reject-parity 已由破坏用例覆盖)。`mvn test` **93 绿**(原 50 + 5 结局 + 38 accept-parity),commit `d8cf95f`/`4e66056` @ `phase1/event-loop-core`。**0b 改判**:Boot 4.1 = Jackson 3,networknt(Jackson 2)会造双 Jackson 世界 + 顶撞单一 ObjectNode 决策 → 手写校验器,零新依赖(见 F-007)。**纯数据面,未起状态机/SSE/EventLoopService/守卫/降级/prompts。**
- **完成(event-loop 控制面 · 业务代码首次成型)**:按交接清单由纯到脏起 Phase 1 event-loop 第二批,落 `server/.../eventloop/`(前两批为数据面/接缝,此批是 event-loop 业务代码首次成型)。**①`SentinelSplitter`(纯,最高风险先 TDD)**:吃 token → 吐叙事增量 / 缓冲哨兵 `<<<DELTA>>>` 后尾巴,压住末尾「哨兵长-1」字符做跨 chunk 边界扫描,只切第一个、无哨兵→tail 空交降级。**②`TurnReinfuser`(纯)**:尾巴 → `LooseJson` → `ObjectNode` → 回灌 `narrative` → 现成 `validateTurn`/`Engine.apply` 一行不改复用(§4.4)。**③`TurnStateMachine`+守卫**:合法性(非法 actionId→`event:error`、executor 零调用、停 AWAITING)、忙态(每 saveId 相位活在 `GameSessionManager` 内存 `ConcurrentHashMap`,入 GENERATING 走 `AtomicReference.compareAndSet` 非读后写,两线程同回合恰一个过)、ongoing→AWAITING / ended→ENDED。**④`EventLoopService`(`TurnExecutor` 实现)**:组 ①②③+引擎,驱动流(叙事经切分逐字发 `narrative`)、按序发 SSE(narrative→单 delta→可选 ending)、一次修复(开回 `json_object`、**回灌同一个 canonical 叙事 N**,不让修复改写已流出叙事)、保守 no-op 降级(turn++/不脏写/响亮告警/已流叙事当氛围)、兜底结局 id 接线、**所有出网过 `toClientState` 消毒**(delta/ending 无 `isTrue`/`hiddenLogic`,discovered 规则只带 content)。**⑤接缝 + prompts**:`TurnEventSink`+`web/SseTurnEventSink`+`GameController`(`POST /api/game/{id}/turn` + dev init,薄适配守 ADR-005);`ChatRequest` 增 `jsonObject`、`OpenAiCompatLlmClient` 修复发开回 `response_format:json_object`;`Engine.applyNoOp` 降级推进;`prompts/event-loop.md` v0.2(prose+哨兵+尾巴去 narrative,运行时同义副本在 `TurnPromptBuilder`)。**测试 +24**:**transform parity**(核心一招,零 API 成本)把 golden 护城河延伸到切分+回灌——离线把 30 条 golden parsed 重写成新线上格式,逐字符过 ①+② 后断言回灌 `ObjectNode` 逐字段 == 原 parsed 且 `Engine.apply` 端状态 == 同一 golden fixture;另手搓切分器 8 / 状态机 5(含 `compareAndSet` 并发)/ service 6(SSE 时序 + 消毒断言 + 一次修复 + no-op + 叙事非法 + 兜底结局接线)/ prompt 2 / json_object 1。`mvn test` **117 绿**(原 93 + 24),commit `f4b2fb6` @ `phase1/event-loop-fsm`。**规格回写**:§9 新增「回灌后才校验,绝不校验裸 tail」、§3 补忙态并发原语注、§10 划掉第 2 条(结局回合 actions 已放宽,第一批落地)。**未起 world-gen/INITIALIZING(故会话暂经 dev init 起)、未碰 CloudBase 部署。**
- **完成(event-loop 第三批 · world-gen + INITIALIZING,整局闭环上游)**:按设计稿 §8 由纯到脏起 Phase 1 第三批,落 `server/.../worldgen/`,补全 init→回合→结局整局闭环的上游(后端贯通)。**①`WorldGenService`(胖调用)**:开 `response_format: json_object`(ADR-007,异于回合丢 json_object)累积流式 token 成纯 JSON → `LooseJson` → `validateWorld`(第一批零改复用)→ 不通过**一次修复**(同样 json_object,零切换成本)→ 仍败抛 `WorldGenException` = **整局 ERROR**(无前态可守,干净重来,不进半残 PLAYING——异于回合保守 no-op)。**②`GameInitService`(播种编排)**:提取 transient `openingNarrative`(过 moderation 接缝)并从 world 根剥除(不入持久化 state、不夹带进每回合 context/投影,故 `schemaVersion` 仍 "0.2")、解析初始决策圈(world 给合法 2–4 个则用,否则 **FALLBACK** 沿用 bake-off)、玩家可见文本过审核接缝、剥 `availableActions` 后播种会话(INITIALIZING→PLAYING / turn FSM `AWAITING_ACTION`)、出网 `world` 走 `toClientState()` 消毒投影。**③web 接缝 + prompts**:真实 **`POST /api/game/init`**(plain POST 无 SSE,守 ADR-005「只在真需要流的地方用 SSE」;ERROR→502 `{error:{code,message}}`)、**退役 dev-init 桩**(`/api/dev/game/{id}/init`,会话此后只经真实 init 播种)、`GameSessionManager.create` 改收显式初始动作;`prompts/world-gen.md` 生产化(json_object + `openingNarrative` + 初始 actions + id 类型约定 F-001 + 泄露硬化),运行时同义副本 `WorldGenPromptBuilder`。**测试 +18**:**world-gen parity**(复用 8 golden world raw,零 API)——raw→`validateWorld`→播种,断言 init 消毒投影无 `isTrue`/`hiddenLogic`、会话在 `AWAITING_ACTION`、数值由 raw 播种、FALLBACK 动作正确;另修复路径 / ERROR 路径(不进 PLAYING、`activeCount`=0)/ init 消毒独立硬闸 / moderation 接缝被调用 / 生产形态(world 带动作+openingNarrative,验 transient 不入 state)/ prompt 格式钉位。`mvn test` **139 绿**(原 121 + 18),commit @ `phase1/world-gen`。**决策**:[ADR-007](adr/ADR-007-world-gen-wire-protocol.md) 已采纳(world-gen 保 json_object 纯 JSON 无哨兵 + reveal 不流式,理由:world-gen JSON 首次失败是头号失败模式、真 key 冒烟只证回合侧不外推 → 把可靠性留在最险的那次生成;开场 vibe 由前端 reveal 动画补)。**约定回写**:CONTEXT v0.4(§三.10 两套线上口径别混 / §三.11 world-gen 失败=整局 ERROR / §三.12 开场叙事 reveal 不流式,JSON `schemaVersion` 仍 "0.2");event-loop 规格 §2「开场叙事可流式」一条标注覆盖(reveal 不流式,见 ADR-007)。**偏差如实记**:8 golden world raw 早于本批、无 `availableActions`/`openingNarrative` → 设计稿 §8 的「断言播种正确初始 actions」按 FALLBACK 路径验,`validateWorld`/`WORLD_SCHEMA` 保持**零改**(两字段在校验层仍可选,守 8 golden accept-parity——故未把它们设为 required)。
- **下周计划**:整局集成冒烟(`DEEPSEEK_API_KEY` 就位后 curl 走真实 `init`→`/turn` 玩到结局,**首次真实验递延 ending + §5 触底兜底端到端**,见 `server/README.md`「整局闭环冒烟」);前端接 SSE 渲染散文/数值/决策圈 + init loading→世界浮现(client-side reveal);Phase 1 排期纳入 ICP 备案 + CloudBase 部署接缝。

**Week 2(2026-06-22 ~ 2026-06-28)**
- **决策**:ADR-003(前端栈与 Taro 边界)已采纳——React+Vite H5 先行,**用接口纪律(非实现)占住小程序迁移边界**:逻辑/状态/类型/展示层平台无关(Taro 直接复用、禁引用平台 IO),网络/流 IO 全收进 `web/src/api/` 薄适配层(镜像后端 `LlmClient`/`TokenStream` 接缝哲学,ADR-001/005);状态 Zustand、样式原生 CSS/CSS Modules(Taro 迁移税最低);Phase 1 **不写任何小程序代码**,只保证边界接缝在对的位置(WS 预案记录不实现)。落档单独 commit(`4eceec3`),与实现分开。
- **完成(前端 H5 整局闭环)**:按 ADR-003 由内到外落 `web/` 前端整局闭环(init→回合→结局)。**①`api/` 适配层**(平台 IO 唯一集中地):`contract.ts` 暴露 provider-agnostic 契约(`GameApi` + `TurnStream{onNarrative/onDelta/onEnding/onError/onClose}` + `GameApiError`);`sse.ts` 用 `fetch`+`ReadableStream` 流式解析 SSE(跨 chunk 帧边界扫描);`h5GameApi.ts` 接已实测定型的 wire(init plain POST ADR-007 / 回合 SSE 命名事件 ADR-006),映射四类语义事件。**②状态层** `state/gameStore.ts`(Zustand):消毒 client world + 流式增量(narrative 累加 / delta 落数值&规则面板 / ending 出画面 / 可恢复 error 回 awaiting+提示);`createGameStore(api)` 工厂可注入 mock,测试零网络。**③回合循环 UI** `features/game/`:散文区(开场 client-side 逐字 reveal `useTypewriter`,回合实时流直渲)+ 决策圈(只选 id,不开自由文本)+ hp/san 数值面板 + 规则面板(已验证高亮,**绝不渲染 `isTrue`/`hiddenLogic`**)+ 整局流程容器(start→loading→reveal→回合→结局 / world-gen ERROR 出「重新生成」)。**④边界硬线**:`eslint.config.js` `no-restricted-globals` 禁逻辑/状态/展示层引用 `fetch`/`EventSource`/`WebSocket`/`wx.*`(`api/` 与测试豁免);自查 `state/`/`features/` 零平台 IO。**测试 14 绿**:api 层(mock fetch + 合成 SSE 字节流跨 chunk 切分 + 四类事件分发 + init 解析 + 502/网络/404 失败归一)、状态层(合成事件序列推进,含 ending/error/守卫分支);lint/build 全绿。工程:zustand + vitest/jsdom/testing-library,vite dev proxy `/api`→`:8080`,退役脚手架占位(`sampleWorld`/`App.css`/`.gitkeep`)。commit `78bd5d6` @ `phase1/frontend-h5`。
- **偏差如实记**:brief 假设「H5 实现用原生 `EventSource`」,但回合端点是 `POST /api/game/{saveId}/turn` 带 JSON body,而浏览器 `EventSource` 只能 GET、不能带 body —— 接不了这个 wire。改用 `fetch`+流式读 body 解析 SSE(POST-SSE 标准做法)。**ADR-003 边界不受影响**:逻辑层依旧只见 `TurnStream`、永不见传输细节,反而更纯(连 `EventSource` 都不用)。已在 `api/sse.ts` 顶注与 ADR/CONTEXT 记明。
- **约定回写**:CONTEXT v0.5(§三.13 前端跨端边界:平台无关层禁平台 IO + `api/` 薄适配层 + provider-agnostic `TurnStream` 流接口 + eslint 硬线);JSON `schemaVersion` 仍 "0.2"(字段未变)。ADR 索引加 ADR-003、移出待决策(随 ADR 落档 commit)。
- **完成(真 key e2e 首次通关 + 前端批合并 main)**:`DEEPSEEK_API_KEY` 就位,Felix 以**玩家视角**真 key 从 init 玩通一局规则怪谈到结局——前后端整局打通的首次真实验,**Phase 1 里程碑「朋友能用手机从头玩到一个完整结局」技术闭环达成**(本窗只架环境 + init 探针,回合流式手感由 Felix 本人在浏览器判,非脚本观测)。验过后解封合并:`mvn test` 139 绿 + 前端 `npm run test`(14)/`lint`/`build` 全绿 → 前端批 **ff 合并 `main`** 并 push origin(`4eceec3`/`78bd5d6`/`0b1ebeb`/`02f2faf`);删旧分支 `phase1/frontend-h5` + `phase1/event-loop-core`(后者提交早已全含 `main`)。**ADR-003 §3 预案主次序校正**(随上轮 commit `02f2faf`):H5 实测确认回合走 `fetch`+`POST` body+流式读 chunk(非原生 `EventSource`)→ Phase 4 小程序 `wx.request`+`enableChunked` 分块回调(与 H5 同构、复用最多)升**主预案**、WebSocket 降**备选**;CONTEXT v0.6 同步(纯文档,`schemaVersion` 仍 "0.2")。
- **决策(可玩性 backlog + A 计划限范围)**:e2e 暴露反馈按 Phase 分类入档(防范围膨胀,见 `docs/phase1-playability-backlog-and-polish.md`)——**现在做**仅两条:**A-1 叙事长度约束**(提示词层零代码,所有世界共用的 event-loop 体验)+ **A-2 规则高亮**(前端把后端已产的 `delta.discoveredRules` 点亮,非新功能);**推迟**:配图管线(②,Phase 2 后,涉图像生成 + 成本/延迟 + 图过审)、难度系统(③b,Phase 2/3)、多世界 archetype 选择(④,Phase 2 主线,UG Engine 已留好)。
- **下周计划**:从干净 `main` 起 `phase1/polish` 做 A-1/A-2 两批(互不依赖、各自单独 commit),做完架 e2e 复玩环境由 Felix 本人验体感(A-1 读起来累不累 / A-2 触发规则时面板高亮明不明显);**不替 push/合并**,等 e2e 复玩定后再 ff;视觉打磨(`frontend-design` skill 定方向);Phase 1 排期纳入 ICP 备案 + CloudBase 部署接缝。

---

## 五、技术决策记录(ADR)

> 命名:`docs/adr/ADR-NNN-决策主题.md`。用 `/adr-author` 生成。

### 首批待决策议题

- **ADR-004 · 内容安全方案**:审核 API 选型 + 兜底过滤策略

### 已完成 ADR 索引

| ADR | 主题 | 状态 | 日期 |
|-----|------|------|------|
| [ADR-001](adr/ADR-001-runtime-model-and-provider-abstraction.md) | 运行模型选 DeepSeek V4-Flash 为主力,provider 走 OpenAI 兼容配置表抽象 | 已采纳 | 2026-06-17 |
| [ADR-003](adr/ADR-003-frontend-stack-and-taro-boundary.md) | 前端栈选 React+Vite H5,以接口纪律(`api/` 薄适配层 + provider-agnostic 流接口)占住 Taro 迁移边界,Phase 1 不写小程序代码 | 已采纳 | 2026-06-23 |
| [ADR-002](adr/ADR-002-backend-form-factor.md) | 后端形态选 Spring Boot 运行于 CloudBase 云托管(应用层自控 + 微信原生集成,薄适配层缓解锁定) | 已采纳 | 2026-06-18 |
| [ADR-005](adr/ADR-005-sse-web-stack-mvc-thin-seam.md) | SSE/流式 web 栈选 Spring MVC(SseEmitter)+ 可换 WebFlux 的薄接缝(`TokenStream` 解耦核心与传输) | 已采纳 | 2026-06-18 |
| [ADR-006](adr/ADR-006-event-loop-streaming-wire-protocol.md) | event-loop 流式线上协议选叙事先行单次调用 + 哨兵 + 结构化尾巴 + 叙事回灌复用(下游校验/引擎零改,守 ADR-005 薄接缝) | 已采纳 | 2026-06-19 |
| [ADR-007](adr/ADR-007-world-gen-wire-protocol.md) | world-gen 线上协议选胖调用 + 保 json_object 纯 JSON 无哨兵 + 开场叙事 reveal 不流式(可靠性留在最险的那次生成,异于 ADR-006 回合口径) | 已采纳 | 2026-06-21 |

---

## 六、中国市场雷区清单(尽早确认 · 需核实最新政策)

- **小程序账号与主体**:个人 vs 企业(支付、部分类目需企业)
- **游戏类目与"版号"**:游戏类目通常要资质,带虚拟货币付费历史上要版号,个人极难拿到 → 规避:H5 起步、变现先用**会员订阅**而非游戏内代币
- **内容安全**:生成内容必须过审,接审核 API + 兜底关键词过滤
- **备案**:规模化后可能涉及算法 / 大模型备案;走公网域名还有 ICP 备案
- 政策会变,上线前以官方最新文档为准;重大合规决策咨询专业人士

---

## 七、关键设计要点(从企划提炼)

- **通用生成引擎(UG Engine)**:世界生成 → 角色/属性 → 规则矩阵 → 动态事件流 → 多结局收敛
- **基础模式**:规则怪谈、人生模拟、修仙、赛博朋克、末日生存(MVP 先做规则怪谈)
- **旗舰模式**:混合模拟器(概念融合)——最大差异点,也是最难自洽,先彩蛋验证
- **提示词工程是核心资产**:`prompts/` 文件夹版本化管理
- **商业模式**:免费额度 + 会员;分享长图带水印做增长

---

## 八、Claude 协作使用说明

> 给 Claude 看的协作指引。新对话开始时读到这里能立刻进入状态。

1. 默认我已在某个 Phase 的具体任务上,先看「四、进度追踪表」了解当前状态再回答
2. 涉及技术选型时,**主动建议写一条 ADR** 并用 `/adr-author` 填充
3. 不重复解释路线图本身,除非我明确要求修订
4. 给出长篇技术建议后,主动问是否需要更新本文档对应章节
5. 开放式探讨结束前,提示哪些内容值得入档(ADR / 进度日志 / 设计要点)

**当前对话焦点**:_每次开新窗口在这里写一句本次想聊什么_

---

## 九、版本历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| v0.1 | 2026-06-15 | 初版:项目元信息 + Phase 0–4 路线图 + 技术选型倾向 + 进度表骨架 + 首批 ADR 议题 + 中国雷区清单 + 设计要点 + 协作说明 |
| v0.2 | 2026-06-18 | Phase 0 进度更新(provider bake-off 完成 + 盲评通过);追加 Week 1 周度日志;ADR-001 落档移入已完成索引、从待决策议题移除 |
| v0.3 | 2026-06-18 | ADR-002(后端形态,采纳方案 C:Spring Boot @ CloudBase 云托管)落档:移入已完成 ADR 索引、从待决策议题移除;Week 1 日志补 ADR-002 决策与下周计划 |
| v0.4 | 2026-06-18 | 前端工程初始化(React + Vite + TS,scaffold,落 `web/`)完成,schema v0.2 落 TS 类型 → Phase 0 整体收口:进度表 Phase 0 行标 ✅、当前阶段更新、Week 1 日志补「完成(补)」一条 |
| v0.5 | 2026-06-18 | Phase 1 开工:后端 Spring Boot 骨架(`server/`,mock 实现)+ SSE 通路本地跑通 + ADR-005(SSE web 栈:MVC + 薄接缝)落档 → 进度表 Phase 1 行转 🟨、当前阶段更新、Week 1 日志补两条、ADR-005 进已完成索引(003/004 仍留候选) |
| v0.6 | 2026-06-19 | 工具链修缮:Spring Boot 3.5.3 → 4.1.0(编译目标仍 Java 21),去掉 `JAVA_HOME=21` workaround → 同步进度表/Week 1 日志的版本号、补「完成(工具链修缮)」一条;ADR 不改 |
| v0.7 | 2026-06-19 | Phase 1 推进:接真实 DeepSeek(`OpenAiCompatLlmClient`,OpenAI 兼容流式,token 经 `TokenStream` 接缝流到 SSE、web 层未动,TDD 15 测全绿)→ 进度表 Phase 1 备注与「当前阶段」更新、Week 1 日志补「完成(接真实 DeepSeek)」+ Jackson 3 卡点 + 刷新下周计划;ADR 不改 |
| v0.8 | 2026-06-19 | Phase 1 设计检查点(非阶段完成,行仍 🟨):event-loop 契约/状态机规格入库 `docs/` + ADR-006(流式线上协议:叙事先行单次调用 + 哨兵 + 结构化尾巴 + 叙事回灌)落档进已完成索引 + CONTEXT v0.3(§三.8 数值权威 / §三.9 三视图消毒,JSON schemaVersion 仍 "0.2")→ 进度表 Phase 1 产出/链接与备注更新、Week 1 日志补「完成(设计定稿)」一条 + 刷新下周计划 |
| v0.9 | 2026-06-20 | Phase 1 event-loop 第一批(数据面内核移植,非阶段完成,行仍 🟨):`Engine`/`GameSchemas`/`LeakDetector`/`LooseJson`/`toClientState` 落 `server/.../engine/`,golden parity 锁死(Java 逐字段 == Python),`mvn test` 50 绿 → 进度表 Phase 1 备注与「当前阶段」更新、Week 1 日志补「完成(event-loop 内核移植·数据面)」一条;FINDINGS 加 F-007(Boot 4.1 锁 Jackson 3 → 否决 networknt、手写校验器);CONTEXT 不动、无新 ADR |
| v1.0 | 2026-06-21 | Phase 1 event-loop 第二批(控制面,业务代码首次成型,非阶段完成,行仍 🟨):`SentinelSplitter`/`TurnReinfuser`/`TurnStateMachine`+守卫/`EventLoopService`/web 接缝落 `server/.../eventloop/`,transform parity 把 golden 护城河延伸到切分+回灌,`mvn test` 117 绿(+24);`prompts/event-loop.md` v0.2(prose+哨兵+尾巴去 narrative)→ 进度表 Phase 1 备注与「当前阶段」更新、Week 1 日志补「完成(event-loop 控制面)」一条 + 刷新下周计划;event-loop 规格回写(§9 回灌后才校验 / §3 忙态并发原语 / §10 划掉第 2 条);CONTEXT 不动(无字段语义变更)、无新 ADR |
| v1.1 | 2026-06-21 | Phase 1 event-loop 第三批(world-gen + INITIALIZING 上游,整局闭环后端贯通,非阶段完成,行仍 🟨):`WorldGenService`/`GameInitService`/`WorldGenPromptBuilder`/`WorldGenException`/`InitResponse` 落 `server/.../worldgen/`,真实 `POST /api/game/init`(退役 dev-init),`prompts/world-gen.md` 生产化,world-gen parity 复用 8 golden,`mvn test` 139 绿(+18)→ 进度表 Phase 1「当前阶段」/产出链接/备注更新、Week 1 日志补「完成(event-loop 第三批·world-gen)」一条 + 刷新下周计划、ADR 索引加 ADR-007;[ADR-007](adr/ADR-007-world-gen-wire-protocol.md) 采纳(world-gen 保 json_object 纯 JSON 无哨兵 + reveal 不流式)+ CONTEXT v0.4(§三.10/11/12);world-gen 设计稿入库 `docs/`、event-loop 规格 §2 标注覆盖 |
| v1.3 | 2026-06-23 | Phase 1 真 key e2e 首次通关 + 前端批 ff 合并 `main`(里程碑技术闭环达成,但措辞「闭环达成,可玩性打磨中」、行仍 🟨 到 A 计划做完):Felix 玩家视角真 key 玩通一局规则怪谈 → 里程碑「朋友能用手机从头玩到一个完整结局」达成;前端批 ff 合并 `main` 并 push(`4eceec3`/`78bd5d6`/`0b1ebeb`/`02f2faf`)、删 `phase1/frontend-h5`+`phase1/event-loop-core`;ADR-003 §3 预案主次序校正(`wx.request enableChunked` 升主 / WS 降备,随 commit `02f2faf`)+ CONTEXT v0.6(纯文档,`schemaVersion` 仍 "0.2")→ 进度表 Phase 1「当前阶段」/备注更新、Week 2 日志补「完成(e2e 通关 + 合并 main)」+「决策(可玩性 backlog + A 计划限范围)」两条 + 刷新下周计划;可玩性 backlog 入档(`docs/phase1-playability-backlog-and-polish.md`),A 计划只做 A-1 叙事长度约束 + A-2 规则高亮,其余(配图/难度/多世界)挂 Phase 2/3 |
| v1.2 | 2026-06-23 | Phase 1 前端 H5 整局闭环落地(非阶段完成,行仍 🟨):[ADR-003](adr/ADR-003-frontend-stack-and-taro-boundary.md) 采纳(前端栈 + 接口纪律占 Taro 边界、Phase 1 不写小程序)+ `web/` 整局实现(`api/` 适配层 contract/sse/h5GameApi + Zustand `gameStore` + `features/game/` 回合循环 UI + 整局流程)+ eslint 边界硬线 + 14 测绿(api 层 mock fetch/合成 SSE + 状态层合成事件序列)→ 进度表 Phase 1「当前阶段」/产出链接(加 web/ · ADR-003 · CONTEXT v0.5)/备注更新、新增 Week 2 日志(ADR-003 决策 + 前端完成 + EventSource 偏差 + 下周计划)、ADR 索引加 ADR-003、移出待决策;CONTEXT v0.5(§三.13 前端边界,`schemaVersion` 仍 "0.2");偏差如实记:回合端点 POST 带 body,原生 EventSource 接不了 → 改 fetch+流式 SSE(边界不受影响) |
