# AI Universe Simulator · 开发计划总览

> 本文档是本项目的"中央档案"。每次开新对话时,把它上传或粘贴给 Claude,即可让它立刻理解全局规划、当前进度与已做决策。
> 配套:`docs/adr/`(技术决策记录)。

---

## 一、项目元信息

| 字段 | 内容 |
|------|------|
| 项目类型 | 基于大语言模型的生成式、可交互、无限流文字模拟游戏平台 |
| 当前阶段 | Phase 1 · 单模式 H5 闭环(规则怪谈)进行中 🟨(后端骨架已搭、真实 DeepSeek 已接入 SSE 通路) |
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
| Phase 1 | W4–9 | 单模式 H5 闭环(规则怪谈) | 🟨 | | [server/](../server/) · [ADR-005](adr/ADR-005-sse-web-stack-mvc-thin-seam.md) | 开工:Spring Boot 4.1(编译目标 Java 21/Maven)后端骨架落 `server/`,provider 抽象(`LlmClient`/`LlmProperties` 配置表 + mock 实现)+ 审核网关 no-op 接缝 + SSE 冒烟端点本地跑通(逐字流式,印证 ADR-002 承重假设本地侧);ADR-005(SSE web 栈:MVC + 薄接缝)已采纳。**已接真实 DeepSeek**(`OpenAiCompatLlmClient`,OpenAI 兼容流式,真实 token 经现有 `TokenStream` 接缝流到 SSE、web 层一行未动,15 测全绿;手动集成冒烟待 key 就位)。**未实现规则怪谈业务 / event-loop 契约 / 状态机,未碰 CloudBase 部署** |
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
- **下周计划**:手动集成冒烟(`DEEPSEEK_API_KEY` 就位后 curl 看真实逐字流);着手规则怪谈业务(world-gen 提示词 + event-loop 契约 / 状态机);Phase 1 排期纳入 ICP 备案。

---

## 五、技术决策记录(ADR)

> 命名:`docs/adr/ADR-NNN-决策主题.md`。用 `/adr-author` 生成。

### 首批待决策议题

- **ADR-003 · 前端栈与跨端路线**:React + Vite H5 先行 → Taro 移植小程序的取舍与边界
- **ADR-004 · 内容安全方案**:审核 API 选型 + 兜底过滤策略

### 已完成 ADR 索引

| ADR | 主题 | 状态 | 日期 |
|-----|------|------|------|
| [ADR-001](adr/ADR-001-runtime-model-and-provider-abstraction.md) | 运行模型选 DeepSeek V4-Flash 为主力,provider 走 OpenAI 兼容配置表抽象 | 已采纳 | 2026-06-17 |
| [ADR-002](adr/ADR-002-backend-form-factor.md) | 后端形态选 Spring Boot 运行于 CloudBase 云托管(应用层自控 + 微信原生集成,薄适配层缓解锁定) | 已采纳 | 2026-06-18 |
| [ADR-005](adr/ADR-005-sse-web-stack-mvc-thin-seam.md) | SSE/流式 web 栈选 Spring MVC(SseEmitter)+ 可换 WebFlux 的薄接缝(`TokenStream` 解耦核心与传输) | 已采纳 | 2026-06-18 |

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
