# 层 3.2 勘察底稿 · Tool Calling + Trace + 回放

> **性质**:事实底稿,不是方案,不是 ADR。只写事实与问题,不写推荐、不排序。
> 截至 `main@52b339b`(2026-10-06)。出处写 `文件:行号`,行号以写成时为准、会漂。
> 推断标「**推测**」;没能核实的标「**未证实**」。路径前缀 `server/…/` = `server/src/main/java/com/aiuniverse/server/`。
> 形态照 [层 3.1 勘察底稿](event-advancement-survey.md)。

---

## ⚠️ 置顶:backlog 3.2 的前提只部分成立

[求职线 3.2](backlog-career-track.md)(`backlog-career-track.md:422-430`)的前提是:建在 3.1 之上以后,
被调用的是「一个真实、可事务化、可回放的领域系统」。勘察结果(依据见 §一 问题 3、§三、§四):

1. **今天已经是「模型提出、引擎裁决」的形态** —— 只是通道是「叙事 + 哨兵 + 结构化尾巴」,不是 tool_calls。
   模型提议数值绝对值 / 规则 id / 结局 id / 选项,`Engine.apply` 校验、钳制、gate、落账(§一)。
   **把同一组字段改走 tool_calls,不新增任何裁决能力**(推测,依据:裁决全在 `Engine.apply`,与载体无关)。
2. **玩家输入是闭集**(ADR-004 §背景订正:「玩家无法把任意字节送进模型」;守卫 1 `GameSession.hasAction`
   精确相等,`server/…/eventloop/GameSession.java:122`)。**所以「这回合要结算什么」在调模型之前就已知**,
   能确定性算的东西都可以在调模型之前算好、作为事实塞进 prompt —— 纸箱局面(ADR-028)今天就是这么做的,
   **一次模型调用、零工具往返**(§三 问题 7)。
3. **读操作已经全量下发**:每回合 prompt 带 `engine.contextJson()` 整份视图 2(`TurnPromptBuilder.java:563-565`),
   模型没有「需要查一下才知道」的状态(在当前上下文规模下;层 4 RAG 的解冻条件正是规模超了,`backlog-career-track.md:438`)。
4. **3.1 本身没有「答案」**:3.1 底稿只列了问题(`event-advancement-survey.md:64-79`),纵向样本只锁一个局面
   (ADR-028),「确定性事件推进引擎」作为通用层**不存在**。backlog 的依赖链(`backlog-career-track.md:401-404`)
   第二环没有完成。

因此 **Tool Calling 在本项目里没有「今天做不到、做了就能做到」的游戏功能收益**(推测,依据上面四条)。
它可能带来的东西在问题 3 里逐条列出;其中真实存在的是 **执行轨迹(trace)** 与 **供应商侧的参数结构约束(strict)** 两项,
而这两项**都不必须经由 tool calling 才能拿到**。这一条请 Felix 裁定(见末尾「开放问题」O-1)。

另:「Agent 工具调用」今天在工程债「不要写」清单里(`backlog-engineering-debt.md:835`,「不存在(= 第 3 层那一刀要建的东西)」)。

---

## 一、模型今天怎么改变游戏状态

### 问题 1 · 输出形态与落成状态的字段

- **形态 = 叙事散文 + 一行哨兵 `<<<DELTA>>>` + 结构化尾巴 JSON**(ADR-006;骨架 `TurnPromptBuilder.java:114-131`,
  哨兵常量 `SentinelSplitter.java:24`)。主调用**不开** `response_format: json_object`(`ChatRequest.java:4-6`,
  `EventLoopService.java:198` 传 `false`);修复发开(`:266` 传 `true`)。
- 叙事在哨兵前**逐字流给玩家**(`EventLoopService.java:192-195` → `sink.narrative`),尾巴在服务端缓冲
  (`splitter.tail()`,`:210`),回灌叙事后成一个 `parsed` 节点(`TurnReinfuser.reinfuse`,`:239`)。
- **会落成状态变化的字段**(`Engine.apply`,`server/…/engine/Engine.java:267-342`):

  | 字段 | 引擎怎么处理 | 行 |
  |---|---|---|
  | `stateUpdate.<各轴>` | **绝对新值**;缺省 = 当前;跳变 >40 记 issue 不拒;clamp 0–100 | `:272-281` |
  | `stateUpdate.timeline` | 有则整句替换,缺省保留;不评价 | `:283-285` |
  | `triggeredRuleIds` | 并入 `triggered` 集合;**不校验 id 是否存在** | `:287-289` |
  | `discoveredRuleIds` | 标 `rules[].discovered`;id 不存在则**静默无效果**(`markRuleDiscovered` `:439-445`) | `:290-292` |
  | `narrative`(回灌) | 进 `log`;过泄露遥测 | `:294-303` |
  | `ending` | id 须存在于 `endings[]`;致命轴 ≤10 时拒 `success` 改判失败;未给结局但致命轴触底则兜底 | `:311-340` |
  | `availableActions` | 不进 Engine;`EventLoopService` 写进 `session.currentActions`(下回合守卫 1 的闭集) | `EventLoopService.java:298-302,491-498` |

- 事件循环侧另有两处**服务端改写模型产出**:一生制出口 `X` 在校验之后追加(`appendLifeExitAction`,`:515-533`);
  收束段致命轴下限钳制(`clampClosingVigorFloor`,`:581-617`,改写 `stateUpdate` 后记 `Engine.recordIssue`)。
- 纸箱局面回合:选项槽位必须恰好 A/B/C(`slotErrors`,`:394-411`),缺槽按数据表模板补(`sceneActions`,`:417-442`)。

### 问题 2 · 落地前的校验与被拒时的处理

按发生顺序(拒绝链原文 `server/…/web/GameController.java:280-296`):

1. **调模型之前**(容器线程 / 池线程,零 LLM):404 存档 / 409 `turn_stale`(ADR-023/027,`:339-350`)/
   **守卫 1 合法性**(`session.hasAction`,`:353`)/ 准入 503(ADR-022)/ 守卫 0 配额(`TurnStateMachine.java:89-93`)/
   守卫 2 忙态 CAS(`:95-98`)。
2. **模型输出之后**:
   - 叙事空 / 无哨兵 / 尾巴空 → **直接降级**(`EventLoopService.java:213-217`)。
   - 尾巴解析或 `GameSchemas.validateTurn` 不过(`GameSchemas.java:96-143`:narrative 非空、stateUpdate 各轴 0–100、
     规则 id 整数数组、availableActions 2–4 个 / 结局回合可 0、ending 对象或 null)或局面槽位违约
     → **一次修复**(`repairOnce`,`:250-274`):回喂错误清单 + 失败尾巴,开 json_object,回灌**同一**叙事。
   - 修复仍不过 / 修复调用失败 / 主调用流中断(含 ADR-024 20 s 段时限自掐)→ **保守 no-op**
     (`degrade`,`:323-351`;`Engine.applyNoOp` `Engine.java:352-362`:turn++、写 log、不动数值与结局)。
3. **落账时**(`Engine.apply`):clamp、跳变 issue、结局 id 存在性、极性 gate、触底兜底 —— 这些**不拒绝回合**,
   是就地改判或记录。

### 问题 3 · 结论性问题:今天是不是「模型提出、引擎裁决」?Tool Calling 额外带来什么?

**是,部分是。** 依据:

- 引擎是数值与结局的**权威**(CONTEXT §三.8「数值由引擎落账,AI 只提议」),但它对**数值本身**的裁决只有
  范围 / clamp / 跳变记录 / 结局 gate —— **「这一步该掉多少血」是模型定的**,引擎只核边界
  (`Engine.java:276`:`nv = upd.get(key)`)。
- 规则触发与发现的**判定**也是模型做的(`hiddenLogic` 进视图 2 让模型裁决,CONTEXT §三.9);引擎只记录 id。
- 纸箱局面是唯一一处「**引擎裁决,模型只措辞**」:结算由 `BoxScene.settle`(`server/…/eventloop/BoxScene.java:206-213`)
  在**调模型之前**按 (阶段, g, 玩家所选槽位对应的意图) 算出,结果以「引擎给定的局面事实(只措辞,不改事实)」注入
  prompt(`BoxSceneTurn.promptBlock`,`BoxSceneTurn.java:319-350`)。

**Tool Calling 相对现有结构化输出「额外」能带来的**(逐条,都标出是否必须经由 tool calling):

| # | 可能的收益 | 今天的对照 | 必须用 tool calling 吗 |
|---|---|---|---|
| A | **供应商侧参数结构约束**:DeepSeek 文档称 strict 模式下按 function JSON schema 输出(见问题 5,**未证实**) | 今天靠 `validateTurn` + 一次修复;修复率无系统统计(仅 2026-09-17 那 16 回合里 2 次修复 ≈12.5%,ROADMAP v10.5,n=16) | **否** —— 推测:同等约束对整个尾巴也成立(把尾巴做成一个 strict function 的参数)。但这样就是「把结构化输出换了个信封」 |
| B | **执行轨迹(trace)作为自然产物**:每次工具调用有 name / arguments / result,可逐条记录 | 今天一个回合**不记录 prompt、原始尾巴、parsed、修复错误、usage 与回合的对应**(§四 问题 8) | **否** —— 把 `parsed` / 修复错误 / usage 落库同样得到轨迹;tool calling 只是让轨迹「有天然的条目边界」 |
| C | **模型在生成中途「问引擎要一个它不知道的结果」**(掷骰、确定性结算、查不在上下文里的状态) | 玩家输入是闭集 → 结算可前置(纸箱就是这样);所有状态已全量下发 | **只有一种情形必须**:要结算的东西**取决于模型在本回合里才做出的选择**。今天找不到这种情形(推测,见下段) |
| D | **把「写」拆成多个有前置条件的命令**(如 `discover_rule(id)` 在 id 不存在时被拒并告诉模型) | 今天 id 不存在被**静默忽略**(`Engine.java:439-445`),模型不知道;`issues` 不进 `snapshot()`,模型也看不见自己被钳制 / 被 gate(`EventLoopService.java:612`,F-020/F-024) | **否** —— 校验错误回喂已经存在(修复发);缺的是**把拒绝告诉模型**这件事本身,与载体无关 |
| E | 面试关键词 / 简历条目 | 「不要写」清单现列「Agent 工具调用」 | — |

**Tool Calling 带不来的**:

- 不能让「该发生的事发生」(F-028)。那是 3.1 的问题:事件由谁产生。纸箱证明了**前置确定性结算**可以做到这件事,
  而它没用 tool calling。
- 不能减少一次回合里的模型调用次数,只会增加(问题 10)。
- 不能让数值裁决更「确定」,除非同时把「这一步该变多少」从模型手里拿走 —— 那是 3.1 的设计决策,不是 tool calling 的。

**「C 必须用 tool calling」那一种情形是否存在**(推测,需裁定):

- 非纸箱世界里,选项由模型生成(`availableActions` 的 text 是自由文本),**引擎不知道「A」在语义上是什么动作**。
  若要确定性结算,需要有人把动作映射到一个引擎能结算的意图 —— 但这个映射可以**在模型生成选项时**作为结构化字段
  一并产出(像纸箱的「槽位 → 意图」映射,`BoxSceneState.slots`),下回合在调模型前结算。
  这仍然不需要模型中途调用工具。
- 唯一需要中途调用的形态是:**叙事写到一半,结果才由引擎决定,然后叙事继续** —— 这与「叙事先行逐字流」(ADR-006)
  正面冲突(问题 10)。

**问题 3 的结论(照实)**:3.2 若以「让游戏多一种能力」为理由,**前提不成立**;若以「回合执行轨迹可记录、可审计、
可不调模型回放」为理由,**价值真实存在,但不依赖 tool calling**。是否仍以 tool calling 为载体去做,是一个
**求职表述与工程收益之间的取舍**,本底稿不替 Felix 选。

---

## 二、LLM 客户端的能力边界

### 问题 4 · `OpenAiCompatLlmClient` / `ChatRequest` 现在支持什么、缺什么

**现在有**:

- `ChatRequest(String prompt, boolean jsonObject)`(`server/…/llm/ChatRequest.java:8`)—— **一条 prompt 字符串**。
- `buildBody`(`server/…/llm/OpenAiCompatLlmClient.java:106-131`):`model`、`stream: true`(恒定,`:109`)、
  `temperature 0.7`、`stream_options.include_usage`、可选 `response_format: json_object`(`:113-115`)、
  **`messages` 里只有一条 `role:user`**(`:117-120`)、`ThinkingAdapter` 平铺额外字段(`:123-124`;
  DeepSeek 发 `thinking.type=disabled`,`ThinkingAdapter.java:266-268`)。
- 解码 `OpenAiStreamDecoder.decode`(`OpenAiStreamDecoder.java:34`):
  **只取 `choices[0].delta.content`**(`extractContent` `:66`,取值处 `:86`)+ usage 块回调。
- `TokenStream` 只有 `onToken(String)` 与 `onUsage`(`TokenStream.java`);接缝刻意「不在本接口上堆回调」。
- 超时:`REQUEST_TIMEOUT 60s` 只 bound `send()`(ROADMAP v10.5 订正);流式段由 `StreamSegmentDeadline` 每 token 检查
  (`StreamSegmentDeadline.java`,回合侧 20 s,`EventLoopService.java:84`)。

**做 tool calling 缺的**(逐项,均为现测「不存在」):

| 缺口 | 今天的状态 |
|---|---|
| 请求侧 `tools` 定义 / `tool_choice` | `buildBody` 不写这两个字段;`ChatRequest` 没有承载它们的位置 |
| 多轮 `messages`(assistant 消息带 `tool_calls`、`role:tool` 消息带 `tool_call_id`) | 只能发**单条 user 消息**;`ChatRequest` 是字符串 |
| 响应侧解析 `delta.tool_calls` | 解码器只看 `delta.content`,`tool_calls` 增量会被**静默丢弃** |
| 流式 tool_calls 增量拼接(按 index 累积 `function.arguments` 片段,首块带 id/name) | 不存在 |
| `finish_reason`(区分 `stop` 与 `tool_calls`) | 解码器不读 `finish_reason` |
| 把工具调用事件交给调用方的接口 | `TokenStream` 只有文本与 usage 两个回调 |
| thinking 模式下回传 `reasoning_content`(若启用 thinking + tools,见问题 5) | 今天 thinking 关闭,`reasoning_content` 不解析 |
| 录制样本 | `server/src/test/resources/deepseek-sse-sample.txt` 只有 content 块,无 tool_calls 块 |

### 问题 5 · deepseek-v4-flash 对 function calling、尤其「流式 + tool_calls」的支持

**⚠️ 本节全部标「未证实」。** 原因:本会话出口代理拒绝访问 `api-docs.deepseek.com`(WebFetch 返回
`EGRESS_BLOCKED`,`curl` 返回 `CONNECT tunnel failed, response 403`,查阅日期 **2026-10-06**)。
下面只是**搜索引擎对官方页面的摘要**,不是对页面原文的阅读,**不得当作已核实事实引用**:

| 官方页面(链接) | 搜索摘要称(未证实) |
|---|---|
| [Function Calling](https://api-docs.deepseek.com/guides/function_calling) / [Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/) | 支持 Function Calling,兼容 OpenAI API;流式时「每个 tool call 的第一个块带 id、type、function,后续块只带 function arguments」;strict 模式(Beta)需 `base_url=https://api.deepseek.com/beta`、每个 function `strict: true`、所有属性 required 且 `additionalProperties: false`,thinking / non-thinking 均支持 |
| [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/) | thinking 模式支持 tool calls;**带 `tools` 参数的请求须把此前所有轮的 `reasoning_content` 回传,否则 400**;thinking 模式下不支持 required / named `tool_choice`;thinking 模式下 `temperature` 等参数无效 |
| [API 首页](https://api-docs.deepseek.com/) / [Change Log](https://api-docs.deepseek.com/updates/) | 「`deepseek-v4-flash` 等旧模型名仍被接受,但已退役,请求由 **DeepSeek-V4.1-Flash** 提供服务」 |

**与本项目相关、需要核实的三点**(未证实,需要能访问官方文档的人亲自读一遍):

1. 本项目 `thinking.type=disabled`(ThinkingAdapter,F-006 实测于 2026-06-19)。non-thinking 模式下流式 tool_calls 的行为
   是否与上表一致 —— **未证实**。
2. **若「deepseek-v4-flash 已退役、由 V4.1-Flash 服务」属实**,则 F-006 的实测对象、ADR-016 的计价配置
   (`application.yml` `providers.deepseek-v4-flash.price`)与 ADR-024 的耗时读数(n=16 / n=14)**测的是哪个模型**
   都需要重新确认 —— **未证实**,且这条与 3.2 无关,单独列入开放问题 O-5。
3. 「叙事 content + tool_calls」能否在**同一次**流式响应里先后出现(先吐叙事、再吐 tool_calls)—— **未证实**,
   搜索摘要没有提到。这一条直接决定问题 10 的形态。

---

## 三、领域里有哪些东西天然像「工具」

### 问题 6 · 引擎现有的读 / 写操作及其前置条件、不变量

**读**(全部纯读,今天全部经视图 2 一次性下发给模型):

| 操作 | 定义处 | 前置条件 / 不变量 |
|---|---|---|
| 当前回合 / 状态 | `Engine.turn()` `status()` `Engine.java:654-660` | — |
| 各轴当前值 | `attributes()` `:668`;`attribute(key)` `:663` | key 集合由 world 声明,引擎对语义无知(ADR-008) |
| 当前档位 | `AttributeAxis.resolveBand`;注入 `currentBandBlock`(`TurnPromptBuilder.java:564`) | bands 良构由构造器校验(CONTEXT §三.14) |
| 规则 / 结局池 | `world()` `:725`;视图 2 `contextJson()` `:365` | `isTrue`/`hiddenLogic` 只在视图 1/2(CONTEXT §三.9) |
| 近 4 回合 log + 摘要 | `log()` `:720`、`logSummary()` `:685`;`LOG_KEEP=4` `:39` | 更早的叙事**被丢弃**,只剩 `[T{n}选{id}]`(`compressLog` `:421-437`) |
| 人生阶段(一生制) | `LifeStageTables.of(arch).stageAt(turn)` | 只有一生制世界有表(`LifetimeFamily.isLifetime`) |
| 纸箱局面编排 | `BoxSceneTurn.plan`(`BoxSceneTurn.java:105-199`) | 纯函数,不改状态;旧局 / 非局面世界 → null |
| 当前合法动作 | `GameSession.hasAction` `GameSession.java:122` | 闭集 = 上回合下发的 `currentActions` |
| issues | `issues()` `Engine.java:693` | **不进 `snapshot()`**,模型看不见 |

**写**:

| 操作 | 定义处 | 前置条件 / 不变量 |
|---|---|---|
| 落账一回合 | `Engine.apply(parsed, actionId)` `:267-342` | 调用前须过 `validateTurn`;turn++ 恒为首句(ADR-027 判据依赖它);数值 clamp;结局 id 须存在;极性 gate;触底兜底 |
| 降级落账 | `Engine.applyNoOp` `:352-362` | turn++、只写 log |
| 记录异常 | `Engine.recordIssue` `:716` | javadoc 立字「不是通用日志后门」,只记引擎侧数值异常 |
| 纸箱局面提交 | `BoxSceneTurn.commit` `BoxSceneTurn.java:246-261` | 紧跟 apply / applyNoOp、在任何 sink 写之前(ADR-028 §已决 A 第 7 条) |
| 下发选项 | `GameSession.setCurrentActions` | 局面回合恰好 A/B/C;一生制追加 `X` |
| 落盘 | `SessionStore.persist` | best-effort 不抛(CONTEXT §三.17 (4));回合落地 ⇒ 一定尝试(ADR-027) |

**不存在的写操作**(今天没有任何入口,推测依据:上表外未见调用方):
「对某一轴加减一个增量」「标记一个事件发生」「推进局面到下一拍」作为**单独可调用的操作** —— 今天它们要么是
`apply` 内部一步(数值以绝对值整体提交),要么是纸箱的 `plan`/`commit` 内部一步。

### 问题 7 · 纸箱局面:哪些确定、哪些交给模型;是否适合作为工具调用的载体

**确定性(引擎算,纯函数,有测试)**:阶段与拍(`BoxScene.present` `BoxScene.java:193-201`)、结算
(`settle` `:206-213`:阶段 + 行动前 g + 意图 → 结算路径或新 g 与反馈事实)、记忆事实(`:216-219`)、
处境转移(`transition` `:235-240`,唯一一条 EMPTY_HOME --LEAVE_HOME--> OUTSIDE)、意图池轮换(`:245` 起)、
习惯句(`:289`)、逐字句窗口(`VerbatimWindows.judge`,ADR-029)、槽位模板与意图映射(`BoxSceneTurn.plan`)。
**结算发生在下一回合调模型之前**,输入是玩家**上一回合**选的槽位(`BoxSceneTurn.java:113, 125-139`)。

**交给模型**:把这些事实写成正文(「只措辞,不改事实」,`BoxSceneTurn.java:323`)、三个槽位的措辞与 hint、
`stateUpdate` 各轴数值(局面层不接管数值)、是否提议结局。

**是否适合作载体 —— 事实两面**:

- 它是项目里**唯一一个「引擎裁决」已经成形**的地方,前置条件、不变量、数据表、测试(`BoxSceneTest` 等)齐全,
  符合「建在 3.1 之上」的字面要求。
- 但它**今天不需要任何工具往返**:输入(玩家槽位)在调模型前已知,结算前置、一次调用完成。
  把 `settle` 改成模型调用的工具,等于**把一个今天确定性前置的步骤改成由模型决定何时调用** —— 推测会引入
  「模型不调 / 调错参数 / 调两次」三种今天不存在的失败方式。
- 它**不是通用的**(只锁《动物人生》一个局面,ADR-028 标题与 §范围),且《动物人生》当前对旧存档按旧玩法继续、
  F-031~F-034 修复真机未验(ROADMAP v15.0)。

---

## 四、Trace 与回放

### 问题 8 · 一个回合今天留下哪些记录

**文件存储(默认 profile,线上)** —— `<saveId>.json`,内容 = `Engine.toPersistedState()`(`Engine.java:223-257`)
+ `currentActions` / `phaseHint` / `boxScene`(`SessionDocument.java:41-45`):world(含视图 1 全量)、
各轴当前值、`state.turn/status/timeline`、**最近 4 条 log(叙事 + 玩家动作 id)**、`logSummary`(`[T{n}选{id}]` 串)、
`triggered`、`issues`。**每次覆盖写**,不是追加。

**pg profile(线上未启用)** —— `V1__narrative_history.sql`:`game_session(snapshot json, turn, status, source)`、
`game_event(save_id, turn, narrative, player_action, written_at)`(**全量叙事历史**,ADR-025);
`V2__turn_request.sql`:`turn_request(base_turn, target_turn, action_id, status ∈ PROCESSING/SUCCEEDED/DEGRADED/FAILED/INTERRUPTED,
accepted_at, finished_at)`(ADR-026)。

**日志(stdout,瞬时,工程债「日志是瞬时的」挂账)**:
`[event-loop] save=… usage 主调用/修复 …`(`EventLoopService.java:317`)、
`… 触发一次结构化修复(校验错误 N 条)`(`:271`,只记条数不记内容)、
`… T{n} durMs action 落账 attrs ending`(`:295-297`)、降级 WARN(`:202,214,340`)、
钳制 WARN(`:615`)、泄露 WARN(`:288`);world-gen 成功 / 失败 / 修复 / usage(`WorldGenService.java:107,114,140,176`)。

**任何地方都没有记录的**(现测):

- 发给模型的 **prompt 全文**(单体回合 prompt 几千到一万多字节,ROADMAP v6.4 等处读数)。
- 模型的**原始尾巴 JSON** / 回灌后的 `parsed` / 修复 prompt 与修复错误清单内容 / 修复产出。
- 每次调用的**模型名与版本**(响应里有 `model` 字段,解码器不读)。
- usage 与回合的持久对应(只在日志里)。
- 被引擎改写或拒绝的提议原值(gate 与钳制会进 `issues`;跳变只记 >40 的;`discoveredRuleIds` 里不存在的 id 无痕)。
- world-gen 的原始产出与所选种子(种子随机,`WorldGenPromptBuilder.java:733,742` `ThreadLocalRandom`)。

### 问题 9 · 「回放」今天能做到什么程度

**今天就在做的「不调模型的回放」**:`EngineGoldenTest` —— 把 bake-off **录制的每回合模型产出**
(`event-loop-golden.json` 的 `paths.{B1,B2,B3}.turns`)逐回合喂给 `Engine`,断言终态逐字段等于 Python 引擎
(`server/src/test/java/…/engine/EngineGoldenTest.java:16-21`)。录制来源 `bakeoff/out/calls.jsonl`
**被 gitignore**(`.gitignore:18`),夹具由脚本生成、勿手改。**这正是「记下模型产出 → 确定性重放引擎」的形态**,
只是只存在于测试里、只覆盖 bake-off 时代的规则怪谈三条路径。

**确定性的环节**:`Engine.apply` / `applyNoOp`(golden parity 守)、`GameSchemas` 校验、`SentinelSplitter` /
`TurnReinfuser`(TransformParity 守)、`BoxScene` / `BoxSceneTurn` / `VerbatimWindows`(纯函数,数据表)、
`LifeStageTables`、`TurnPromptBuilder` 的渲染(给定 engine 状态与参数,逐字节确定;prompt parity 靠临时 dump
与 `adr028-legacy-turn-prompts` 金样本)、时钟可注入(`EventLoopService` 的 `Clock`;测试用 `ScriptedClock`,
`StreamSegmentDeadlineTest.java:61` 等三处)。

**不确定的环节**:模型输出(temperature 0.7,`OpenAiCompatLlmClient.java:38`)、world-gen 种子选择
(`ThreadLocalRandom`)、墙钟(段时限是否触发取决于真实耗时)、上游可用性。

**不重新调模型的回放,至少需要先记下**(推测,按「从同一初始状态重放到同一终态」推出):

1. 初始世界(world-gen 落地后的 world + 种子;今天 world 在存档里,但会被后续 `markRuleDiscovered`/`markEndingReached`
   原地改写 —— 初始形态不单独保存)。
2. 每回合:玩家 `actionId`(已有)、**回灌后的 `parsed`**(或「原始尾巴 + 叙事」)、是否走修复及修复产出、
   是否降级及降级时已流出的叙事、ADR-028 局面状态(已在 `boxScene` 里,但只存最新)。
3. 服务端改写的输入:出口追加与钳制依赖引擎状态,可重算;**时钟**(若要重放段时限是否触发,需记录每 token 时刻或至少结果)。
4. 代码版本(引擎行为随版本变;`/actuator/info` 有 commit SHA,存档里没有)。

若只要「回看」(ADR-025 已实现的只读历史)而不要「重放引擎」,pg 下 `game_event` 已经够;
「回放」与「回看」是两件事,今天的 ADR-025 只做了后者。

---

## 五、加入工具调用会撞上的约束

### 问题 10 · 「模型 → 工具 → 模型」多次往返在回合链路上分别撞上什么

- **ADR-006 叙事先行逐字流**:主调用叙事在哨兵前逐字下发(`EventLoopService.java:192-195`)。
  若工具调用发生在**叙事之前**,玩家要等一次额外往返才看到第一个字;若发生在**叙事之后**(像今天的尾巴),
  工具调用只是换了载体;若发生在**叙事中途**,已流出的叙事无法撤回(回灌纪律「绝不让修复改写已流出叙事」,`:248`)。
  「content 与 tool_calls 能否在同一次流里先后出现」**未证实**(问题 5 第 3 点)。
- **ADR-024 每段 20 s**:「段」= 一次 `streamChat`(`StreamSegmentDeadline.guard` 每段一个,`EventLoopService.java:117-121`)。
  javadoc 写死「**最坏 2 × 20 = 40 秒,不随发数漂(修复发若变成两发以上,这个上界当场失效,回 ADR-024 重算)**」
  (`:109-110`)。多次往返 = 多段 → **该上界当场失效**。另:守卫只在 token 到达时检查,**工具执行期间不在任何守卫里**。
- **准入名额占用**(ADR-022):名额跟 worker 走,一个回合的全部往返都在名额内(`TurnAdmission`,容量 N=8,
  `application.yml` `max-concurrent: 8`)。回合 `durMs` 实测 p50 3421 / max 6327 ms(n=16,2026-09-17)、
  p50 3092 / max 6074(n=14,2026-09-21)(ROADMAP v10.5 / v10.7)。每多一次往返,占用至少再加一次「首 token 等待」——
  **今天没有 TTFT 读数**(工程债挂账 3:链路上无此时间锚),故增量**无法估算**。
- **ADR-026 / 027 幂等与游标**:二者以「回合」为单位(`turn_request.base_turn → target_turn = base_turn+1`,
  `V2__turn_request.sql`;游标比 `engine.turn()`)。只要多次往返仍在**一次 `execute` 内**、最后才 `apply`,
  turn++ 仍只发生一次,**游标与受理记录不受影响**(推测)。若某个工具**在回合中途写引擎状态**,则
  「回合已落地 ⇔ `engine.turn()` 变了」这条判据(`TurnStateMachine.java:110-112`)**不再覆盖**中途写入
  —— 中途写了状态而 turn 没变的失败,会走「未落地」分支、放回 AWAITING、**不写盘**,内存与盘分叉。
- **降级语义**:今天降级 = no-op,不脏写(`Engine.applyNoOp`)。若工具已在中途写了状态再降级,「不脏写」不再成立。
- **三视图消毒**:工具结果若含 `isTrue`/`hiddenLogic`,它们进的是喂模型的视图 2(允许);但若工具结果被转发给前端
  (例如作为 trace 展示),须过 `toClientState` 那一层 —— 今天任何出网路径都必须过它(`Engine.java:369-373`)。
- **前端契约**:`TurnStream` 只有 narrative / delta / ending / error 四类(CONTEXT §三.13)。工具往返若要对玩家可见,
  需要新事件类型;若不可见,玩家只看到更长的等待。
- **prompt lockstep / golden**:多轮 messages 会改变 prompt 结构;`adr028-legacy-turn-prompts` 金样本与各 lockstep 测试
  守的是**单条 prompt 文本**。

### 问题 11 · 成本闸门:多次调用的回合怎么记账;额度按回合还是按调用

- **¥ 真闸按调用记**:每次 `streamChat` 结束后 `logUsage → quota.record(usage)`(`EventLoopService.java:315-320`),
  `QuotaService.record` 按 usage 三段计价累加日 / 月(`QuotaService.java:139-148`)。多次调用 = 多次入账,形态上兼容。
- **软闸次数按回合记**:`checkTurn` 在回合入口计一次(`TurnStateMachine.java:89`;`QuotaService.java:98-122`,
  日 300 回合 / 键)。一个回合内多少次调用都只算一次。
- **已知漏账**:被中止的调用不入 ¥ 账(流中断走 `catch`、不经 `logUsage`;工程债挂账「成本闸门少算被中止的调用」
  `backlog-engineering-debt.md:642`)。多次往返增加「中途中止」的机会,漏账面随之扩大(推测)。
- **单回合成本**:ADR-016 用 ≈¥0.0035/回合推阈值;多次往返时每次都重发整份上下文(今天 prompt 是单条、无多轮),
  缓存命中率决定增量(ADR-016 实测命中约 52%)—— 增量**未测**。
- ⚠️ 402 余额耗尽不产生 usage,闸门看不见(工程债挂账「上游硬失败不可见」),与本条正交但同一片代码。

### 问题 12 · 线上文件存储 + 单实例:trace 落在哪里有现实可行性

- **线上没有数据库**(ADR-025 刀 4 挂账「方案 D:暂不切库」;README「PostgreSQL 只在 `pg` profile 下生效,线上未切库」)。
- **文件存储**是每 saveId 一个 JSON、**整份覆盖写**(`FileSessionStore`,原子写),无追加语义;
  存档同目录还住着月账 `quota-YYYY-MM.json`(ADR-016),`loadAll` 按文档形状分流(ADR-022 刀 2 前置)。
  把 trace 塞进存档 = 存档随回合数线性增长、每回合整份重写(推测:今天存档只保留 4 条 log 正是成本控制)。
  另开文件 = 落盘目录第三个租户 —— ADR-022 §挂账「分目录」的解冻条件逐字是「**第三个模块也往这个目录写文件时**」。
- **日志**是 stdout,Fly 无 drain(工程债挂账「日志是瞬时的」);v10.5 那批 16 个样本「不在任何地方存着」。
- **pg profile**:ADR-025/026 已有表与 Testcontainers CI(`PgContainerSmokeTest` 等);trace 表在 pg 下有自然落点,
  但**线上不跑**。ADR-026 已决 G「`llm_call` 不做直到 ADR-025 刀 4 解冻」—— 按调用记录的表**已被裁定挂账**。
- 单实例(ADR-015)意味着不存在多写者争用 trace(推测),这一点对任何落点都一样。

---

## 六、候选落点(只列,不选、不排序、不推荐)

### 候选 α · 纸箱局面的结算

- **涉及**:`BoxScene.settle` / `BoxSceneTurn.plan` / `commit`、`EventLoopService.scenePlan` / `settle` / `degrade`、
  `TurnPromptBuilder` 的 `sceneBlock`、`OpenAiCompatLlmClient` / `OpenAiStreamDecoder` / `ChatRequest` / `TokenStream`。
- **需新增**:问题 4 表中全部客户端缺口;工具定义(如「结算本回合选择」);工具结果回填与第二次调用;多段时限重算。
- **与问题 3 的关系**:这是唯一「引擎裁决已成形」的地方;但今天它**前置结算、一次调用**,改成工具调用是把确定性步骤交给模型
  决定何时触发 —— 问题 3 的 C 行「必须用 tool calling 的情形」在这里**不成立**(输入在调模型前已知)。

### 候选 β · 把「结构化尾巴」整体改为一个 strict function 的参数(只换载体,不加往返)

- **涉及**:`TurnPromptBuilder` 骨架尾巴段(`:114-131`)与修复 prompt、`SentinelSplitter` / `TurnReinfuser`、
  `GameSchemas.validateTurn`、`OpenAiCompatLlmClient` / 解码器、prompt lockstep 与 `.md` 资产、`adr028-legacy-turn-prompts` 金样本。
- **需新增**:`tools` 请求字段、`delta.tool_calls` 增量解析、strict 端点(`/beta`,**未证实**)、叙事与 tool_calls
  同流先后出现的确认(**未证实**)。
- **与问题 3 的关系**:对应 A 行(供应商侧结构约束)。**不增加任何游戏能力**;可能改变修复率(今天无系统读数,n=16 时约 12.5%)。
  与 ADR-006「叙事先行 + 哨兵」是同一位置的替换,ADR-006 / CONTEXT §三.10 两套口径会受影响。

### 候选 γ · 回合执行轨迹落库 + 不调模型回放(不引入 tool calling)

- **涉及**:`EventLoopService.execute` / `repairOnce` / `settle` / `degrade`(记录 prompt 摘要或哈希、原始尾巴、`parsed`、
  修复错误、降级原因、usage、模型名)、pg 的 `JdbcSessionStore` 事务(ADR-025 决策:同一短事务)、
  `EngineGoldenTest` 的重放形态(推广为「读轨迹 → 重放 Engine → 比对快照」)。
- **需新增**:轨迹表或字段(注意 ADR-026 已决 G 挂账 `llm_call`)、记录初始世界、重放入口(测试或只读工具)、
  文件 profile 下的落点决策(问题 12)。
- **与问题 3 的关系**:对应 B 行(轨迹)——问题 3 结论里「价值真实存在」的那一半,**完全不需要 tool calling**。
  若之后仍要 tool calling,轨迹的条目边界可以从「回合」细化到「调用」。

---

## 开放问题(需要 Felix 或校勘裁定)

- **O-1(最重要)**:问题 3 的结论是「Tool Calling 在本项目没有今天做不到的游戏功能收益;轨迹与结构约束的价值真实存在但不依赖它」。
  3.2 是否仍以 Tool Calling 为载体?若是,理由写成「求职表述」还是有一个具体的工程收益?这条决定 ADR 的标题与范围。
- **O-2**:backlog 依赖链(`backlog-career-track.md:401-404`)第二环「确定性事件推进引擎」今天只有一个局面的纵向样本。
  3.2 是否接受「建在纸箱一个局面之上」作为满足依赖,还是要等 3.1 有通用答案?
- **O-3**:「不要写」清单现列「Agent 工具调用」(`backlog-engineering-debt.md:835`)。若 3.2 只做候选 γ,这一行是否维持?
- **O-4**:ADR-024 javadoc 写死「修复发若变成两发以上,这个上界当场失效,回 ADR-024 重算」(`EventLoopService.java:109-110`)。
  任何多次往返方案都会触发它 —— 是否接受先重开 ADR-024?
- **O-5(与 3.2 无关,但本次勘察撞见)**:搜索摘要称 `deepseek-v4-flash` 已退役、由 DeepSeek-V4.1-Flash 服务(**未证实**,
  官方页面在本会话被出口代理拒绝)。若属实,F-006(thinking disabled 实测)、ADR-016 计价、ADR-024 耗时读数的对象都要重核。
  需要能访问 `api-docs.deepseek.com` 的人读一遍原文。
- **O-6**:问题 5 所有官方文档条目均为「未证实」。是否在写 ADR 之前由 Felix 本地打开那三页核对,或在本环境放行该域名?
- **O-7**:「回看」(ADR-025,只读叙事)与「回放」(重放引擎)今天被同一个词覆盖(backlog「Trace + 回放」)。
  3.2 的「回放」指哪一个?若指后者,需要记录的东西(问题 9 列表)比 ADR-025 多出 `parsed` / 修复 / 初始世界 / 代码版本。
- **O-8**:trace 落点(问题 12):线上无库;文件存储整份覆盖写;另开文件触发 ADR-022「第三个租户」解冻条件;
  pg 下 `llm_call` 已被 ADR-026 已决 G 挂账。哪一个前提可以动?
- **O-9(观察,非 3.2 必需)**:`discoveredRuleIds` / `triggeredRuleIds` 里不存在的 id 今天被静默接受或忽略(`Engine.java:287-292,439-445`),
  `issues` 不进 `snapshot()`(模型看不见自己被拒 / 被钳 / 被 gate)。这是问题 3 表 D 行的事实基础;是否单独记账?
