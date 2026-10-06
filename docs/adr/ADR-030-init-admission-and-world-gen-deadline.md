# ADR-030 · init 独立准入 + world-gen 流式段时限:开局不占容器线程,world-gen 不再无上限

- **日期**:2026-10-06
- **状态**:提议(草案,待校勘审读;**未采纳、未实现**)
- **决策者**:Felix

## 背景

本 ADR 合并两件**性质不同**的事,分两节写,不许读成同一个理由:

### 一、init 独立准入 —— 提前做,不是事后修

[ADR-015](ADR-015-overseas-deployment-form-factor.md) 已知代价 2 与重新审视条件逐字预登记了出口:
「**并发起局把 Tomcat 线程池吃紧**(init 阻塞排队可感)→ init 移独立执行器」。

⚠️ **这个触发条件尚未发生。** 当前线上玩家 = 1 人,从未观察到并发开局,更没有观察到 Tomcat
线程被吃紧。本刀是**提前做**,理由只有两条,都不是「已经出事」:

1. **与 [ADR-022](ADR-022-turn-admission-and-rejection-semantics.md) 回合侧准入对齐。** ADR-022 裁定 1
   自己逐字限定:「只负责不死」**必须限定到 turn 路径**,`POST /api/game/init` 仍阻塞占 Tomcat 线程、
   「本刀完全没管它」。今天全站只有一条路径有并发上限,另一条没有。
2. **隔离上游卡死对 Tomcat 线程的占用。** init 今天在容器请求线程上同步调 world-gen;上游若卡住,
   被卡住的是**容器线程**(全站共用,默认 200),而不是一个归属清楚、有上限的池。

**不得写成「已遇到并发问题」。** 本节没有任何线上读数支撑「需要」,只有「对齐」与「隔离」。

### 二、world-gen 流式时限 —— 现存缺陷的修复,与并发量无关

`WorldGenService.call` 今天**没有任何响应体时长上界**:

- `OpenAiCompatLlmClient.REQUEST_TIMEOUT = 60s` 挂在 `HttpRequest.timeout` 上,而响应体走
  `BodyHandlers.ofInputStream()` —— **响应头一到它就返回了**,此后 `decoder.decode(reader, sink)`
  在 `readLine()` 上阻塞多久没有任何一层管(这正是 ADR-024 背景里那个洞,回合侧已修、world-gen 侧没修);
- 回合侧的 `streamDeadlineGuard` 是 `EventLoopService` 的 **private** 方法,world-gen 路径不经过它。

即使只有 1 名玩家,这个缺陷也在:上游流得极慢时,一次 init 可以无限期挂着。**它单独成立,
不依赖第一节的任何论证**;即使第一节被否决,这一节也应当做。

## 依据(勘察,只读)

### 勘察 1 · 回合侧 guard 的语义,以及几个数字的出处

- **`streamDeadlineGuard` 是「总时长」,不是「两个 chunk 之间的空闲间隔」。** 源码
  (`EventLoopService`):守卫创建时读一次 `segmentStartedAtMs = clock.millis()`,之后**每个 token 到达**
  时算 `clock.millis() - segmentStartedAtMs`,`> STREAM_DEADLINE_MS` 即 `throw new LlmException`。
  起点在 `new UsageCapture(streamDeadlineGuard(...))` 构造时,即 `llm.streamChat` 调用**之前**,
  故一段的计时**包含**建连与等响应头。
- **ADR-024 的 20 s 指的就是这个总时长**,每段(主调用 / 修复发)各自 20 s,最坏 40 s(ADR-024 立字 4)。
- ⚠️ **语义上的边界(会原样继承)**:检查**只在 token 到达时发生**。上游**一个 token 都不再来**
  (彻底静默)时守卫不触发 —— 这是 ADR-024 §挂账「彻底静默仍无时间上界」,本 ADR 复用同一语义,
  **同一个缺口原样带过来**,不在本刀修(见已知代价 3)。
  校勘 brief 测试项里写的「流式停滞超时」,本 ADR 按此语义解释为「**停滞之后又来了一个 token,而那时已过线**」;
  「停滞且再无 token」不在本刀保护范围内。**这条解释若不被接受,请停下改裁定,不要在实现时自行加看门线程**。
- **复用判断:语义可以复用,代码需要抽出。** guard 是 `EventLoopService` 的 private 方法、阈值是
  private 常量。实现时把「起点 + 每 token 比较 + 抛 `LlmException`」抽到 `llm` 包一个小的公共构件
  (阈值与时钟由调用方传入),回合侧行为逐字不变(既有 ADR-024 测试与 `ScriptedClock` 精确脚本全部照绿);
  world-gen 传自己的阈值。不构成「guard 语义无法复用」。
- **world-gen「首局约 120 s」的出处**:[ADR-015](ADR-015-overseas-deployment-form-factor.md)
  实际效果第 4 条 / [runbook](../phase3-fly-deploy-runbook.md) 冒烟 ⑨:**2026-07-20 部署冒烟时的单次观察**,
  首局 ~120 s、次局 ~15 s(本地直连基线 ~10 s),当时即标注「首局 ~120s 为单次观察、不立 FINDINGS」、
  判为冷因素。它是**端到端体感数字**(从点击到出图),**不是**分段测量,也分不出其中有没有修复发。
  另一个数字「真实世界生成约 12.6 秒」出自工程债「上游硬失败」那条(2026-09-21),同样是单次口径、出处未记采样方法。

### 勘察 2 · 时限取值的数据

- **代码里没有 world-gen 的耗时锚点。** `WorldGenService` 只打一行 `[world-gen] usage …`(token 数),
  不打耗时;层 1 第 4 条那对 `durMs` 锚点只在回合路径(`settle()` / `degrade()`)。
- **仓库里能找到的 world-gen 耗时读数共 3 个**:~120 s(冷,单次)、~15 s(单次)、~12.6 s(单次);
  [基线语料](ADR-004-baseline-corpus.md) 的 4 发 init 是 HAR 抽取的**内容**,未带耗时。
  **样本量 = 3,全部是端到端口径、全部是单次观察,没有分布、没有 chunk 间隔。**
- **提议值:每段 180 s(主调用与修复发各自独立,最坏 360 s)。** ⚠️ **无实测依据,上线后按日志复核。**
  推理只有一条:唯一已知的最大值是 ~120 s,而 ADR-024 立字「**一个会误掐正常请求的超时,比没有超时更糟**」;
  120 s 那次是不是单段、有没有修复发都不知道,故按「它就是单段」取 1.5 倍余量。
  它要挡的是「上游卡了几分钟」,不是「比平时慢」。
- **复核前提**:今天没有 world-gen 耗时日志,「按日志复核」就无从做起 —— 见待决 1(是否在本刀加一行 world-gen `durMs`)。

### 勘察 3 · 前端 `initGame` / `startGame` 的超时与中止

- `web/src/api/h5GameApi.ts` 的 `initGame` 是一个裸 `fetch`:**没有超时、没有 `AbortSignal`**
  (全 `web/src` 里 `AbortController` 只出现在回合流 `sse.ts`)。
- 「取消」(线 C,`LoadingScreen`)是 store 侧的**世代守卫**:结果回来时丢弃,**服务端不知道、照常跑完**,
  照常扣额度、照常建 session(那就是 future-experience-backlog §2.3 的孤儿档来源之一)。
- **两者关系**:前端今天**完全依赖服务端给出上界**。本 ADR 之后,玩家等待 init 的上界 =
  服务端 `DeferredResult` 超时(见决策 1);前端不另设超时(设了会与服务端两个数打架;且前端早于服务端
  放弃时,服务端照样跑完 → 又一个孤儿档)。前端零改动(见待决 3 关于 503 标题文案)。
- ⚠️ 未实测:浏览器 / Fly 反代对 ~6 分钟纯 POST 是否掐断。仅有的旁证是 ADR-015 冒烟「mock init 阻塞数分钟未被反代掐断」。

### 勘察 4 · 容量取值

- 机器:`fly.toml` `shared-cpu-1x` / `512mb`,单副本。
- world-gen 一次在途的占用:一个池线程(栈约 1 MB、在堆外,同 ADR-022 裁定 1 的论证)+ 一个累积缓冲
  (world JSON 量级几 KB 到几十 KB 字符)+ 解析后的 JSON 树。**CPU 几乎全在等 I/O**;
  内存与 CPU 都不是约束。⚠️ 堆的具体默认值同 ADR-022 一样**未实测**,本节只用「栈在堆外 + 每次占用为 KB 级」这两条不依赖堆大小的事实。
- 真正的约束是**上游并发与成本突发**:init 与回合合计的同时在途上游流数 = turn N + init M。
- **提议默认 `aiuniverse.init.max-concurrent = 4`**,理由:正常情况一名玩家同一时刻至多 1 个在途 init;
  4 覆盖「一小撮朋友同时开局」;turn 8 + init 4 = 上游同时最多 12 条流。**无实测依据**(并发开局从未观察到),
  env `AIUNIVERSE_INIT_MAX_CONCURRENT` 可覆盖,真被证伪的信号见重新审视条件。

### 勘察顺带撞见、影响实现的一处事实

- **Servlet 异步默认超时 30 s。** `application.yml` 没有配 `spring.mvc.async.request-timeout`,
  Tomcat connector 的 `asyncTimeout` 默认 30000 ms。回合侧 `SseEmitter(120_000L)` 是**显式**给的;
  init 若改异步而**不显式给超时**,超过 30 s 的 world-gen(含 ~120 s 那次)会被容器判超时 —— 那是本刀自己造出的回归。
  故决策 1 要求**每个 `DeferredResult` 显式带超时**,不动全局配置。

## 候选方案(第一节:init 怎么异步)

| | A1 · `DeferredResult` | A2 · `CompletableFuture` |
|---|---|---|
| 超时 | 每个实例构造时显式给,局部生效 | 走全局 `spring.mvc.async.request-timeout`(要改它就影响全站异步) |
| 超时时的响应 | `onTimeout` 里设定本端点自己的 JSON 错误体 | 需另配 `AsyncTaskTimeout` 处理,全局 |
| 与现有代码 | 同 `SseEmitter`(同属 Spring 的 deferred 族),回合侧已在用这一套 | 新引入一种形态 |
| 立即拒绝 | `setResult(503)` 后返回,不开池线程 | `completedFuture(503)`,等价 |

**选 A1 `DeferredResult`。** 决定性理由是第一行:超时必须**显式、局部**(见勘察「异步默认 30 s」),
A2 要么吃 30 s 默认值(回归),要么改全局配置(扩散到所有异步端点)。

方案 B(幂等键 / 孤儿清理 / 断线检测)不在本刀,见「非目标」。

## 最终决策

### 决策 1 · `GameController.init` 改 `DeferredResult`,独立准入在最前

`init` 返回 `DeferredResult<ResponseEntity<?>>`,容器线程上只做三件事,**按此顺序**:

1. 读请求头定 `ClientKey`(同回合侧:头在容器线程读定,不跨线程摸 request);
2. `new DeferredResult<>(INIT_RESULT_TIMEOUT_MS)`,注册 `onTimeout` → 502 `world_gen_failed`;
3. `initAdmission.submit(…, work)`;返回 false → 立即 `setResult(503 server_at_capacity)` 并返回。

`work`(池线程上)里才做:`quota.checkInit` → `initService.init` → `setResult`。即:

> **准入在额度之前,与回合侧一致。** 被准入拒绝的请求**不扣额度**(`checkInit` 允许时会计数,
> 而它根本没被调用)、**零次 world-gen 调用**、不建 session。

- 其余错误映射**逐字沿用现状**:额度拒绝 429 `quota_exceeded`、非法组合 400 `invalid_archetype`、
  world-gen 失败 502 `world_gen_failed`;503 一律 `contentType(APPLICATION_JSON)`(ADR-022 闸 C 那个 406 风险同样适用)。
- **`INIT_RESULT_TIMEOUT_MS` 必须大于 world-gen 受保护路径的最坏耗时**,否则会出现「客户端已收到失败、
  服务端随后成功建了 session、额度也扣了」。取 `2 × 段时限 + 30 s 余量` = **390 s**(段时限按决策 2 的 180 s)。
  这个超时只在**彻底静默**时才可能被触发(其余情形都由决策 2 先收尾)。
- ⚠️ `DeferredResult` 超时**不中断 worker**(同 `SseEmitter` 120 s 到期不中断回合 worker,ADR-022 已知代价 3 同形);
  名额要等 worker 自己结束才还。
- 503 文案归服务端发(只有服务端知道「此刻开局的人太多」,ADR-022 闸 A 立字 11),
  建议「此刻同时开局的人太多,请过几秒再试」;不带 `Retry-After`(理由同 ADR-022 裁定 2)。

### 决策 2 · `InitAdmission`:形状照 `TurnAdmission`

- **占线程前拒绝**:`Semaphore.tryAcquire()` 在容器线程上做,失败即返回 false,不碰池;
- **`finally` 还槽**:归还在包装层的 `finally`,调用方拿不到名额;
- **`catch (Throwable)`**:`executor.execute` 本身抛(含 `OutOfMemoryError: unable to create native thread`)时
  在容器线程还槽再抛 —— ADR-022 审阅抓到的那条「慢性失血」在这里同样成立;
- **池归本对象所有**,`@PreDestroy` 只关自建的那个;`GameController` 手边不持有任何线程池;
- 容量 `aiuniverse.init.max-concurrent`(新 `InitProperties`,`@DefaultValue("4")`),env 可覆盖;
  **生产构造器启动打一行** `[init-admission] 准入容量 N=…`(理由同层 1 第 6 条:变松没有读数,而变松才危险);
- 拒绝 WARN 落在本类内(闸 B:拒绝那一瞬的在途数只有本类读得到);日志**不打客户端 IP**(ADR-022 立字),
  可打 archetypes。

### 决策 3 · 两条准入分开,不合并(回答 ADR-022「重新审视条件」中「两处准入是否该合并成一道」)

**结论:分开。** 两个信号量、两个池、两个配置项、两条启动日志。理由:

1. **占用时长差两个数量级**:回合 p50 约 3 s(ADR-022 / 024 两批真机读数),init 10 s–2 分钟。
   合并成一个池,几个慢开局就能把所有名额占住,**正在玩的人一回合都点不动** —— 合并把一条路径的慢
   传染给另一条,正是第一节要隔离的东西。
2. **拒绝时玩家的处境不同**:回合被拒,局还在、过几秒重点即可;开局被拒,还没有局。分开才能各自给文案、各自调容量。
3. **代价可控**:两份几十行的相似代码。**本刀不重构 `TurnAdmission`**(动它就要重跑 ADR-022 的整套守护),
   抽公共基类留作「第三条准入出现时」再做(门槛可数:两条可以抄,三条才值得抽)。

上游同时在途流数的总上界因此是 turn N + init M(默认 12),写进已知代价 4。

### 决策 4 · world-gen 流式段时限

- `WorldGenService.call` 接入与回合侧**同语义**的段时限(总时长、每 token 检查、过线抛 `LlmException`),
  **主调用与修复调用各自一段**,每段 `aiuniverse.world-gen.segment-deadline-ms`(默认 180 000,env 可覆盖;
  可配置是为了冒烟能压低到几秒确定性触发,同 ADR-022 N 的理由);
- 插入位置同 ADR-024 立字 3:`new UsageCapture(guard(buf::append))`,守卫在 `UsageCapture` 里层
  (外层要自己转发 `onUsage`、忘了就静默丢 usage);
- 过线 → `LlmException` 落进 `call` 既有的 `catch (LlmException)` → `WorldGenException` → **502 `world_gen_failed`,不建 session**
  (`GameInitService.init` 里 `worldGen.generate()` 在 `sessions.create()` 之前,源码顺序已保证);
- 时钟注入(同 `EventLoopService` / `QuotaService` 的 `Clock` 接缝),缺省 `Clock.systemUTC()`,既有构造调用点零改;
- **prompt 一字不动**:时限只包在 token 回调上,不碰 `WorldGenPromptBuilder`。

## 非目标(各附理由)

- **幂等键 `X-Init-Request-Id`(B2)**:重复开局已受每 key 每日 10 次额度约束,最坏是同一人多扣几次自己的额度;
  进程内映射重启即丢,要跨重启就得落盘或上库 —— 复杂度与收益不成比例。
- **孤儿存档清理(B3)**:与「开局即落盘、崩溃不丢局」(ADR-015)冲突;孤儿只占磁盘,已记
  [future-experience-backlog §2.3](../future-experience-backlog.md)。
- **客户端断线检测 / 取消生成**:服务端今天不可中断 world-gen(`Thread.interrupt()` 打不断 socket 读,ADR-024 方案 A 否决理由同);
  前端取消仍只是丢弃结果。

## 已知代价

1. **被准入拒绝的玩家要手动重试。** 不排队(ADR-022 立字 2 同理:队列容量为零)。
2. **超时不中断 worker**:`DeferredResult` 到期后,worker 若仍在跑,名额继续占着,且它之后若成功会建一个
   客户端永远收不到的 session(孤儿档,额度已扣)。只在「彻底静默 ≥ 390 s」时发生。
3. **彻底静默仍无上界**:同 ADR-024 §挂账,本刀复用其语义,同一缺口带到 world-gen;由决策 1 的 390 s 在**响应侧**兜住,
   **worker 侧不兜**。
4. **上游并发总上界变成 12(8 + 4)**,比今天的「回合 8 + init 无上限」紧,但两个数之和没有单一读数,
   诊断时要分别看两条启动日志。
5. **修复调用超时的计费**:主调用成功已记账(`quota.record`);修复调用被掐时 usage 块尚未到达,
   **这一段不入 ¥ 账**(即工程债「成本闸门少算被中止的调用」的又一实例);同时 `checkInit` 已计数一次 ——
   玩家为一次失败的开局花掉一次日额度。与今天任何 world-gen 失败的计费行为相同,本刀不改。
6. **段时限 180 s 无实测依据**,且一旦误掐的是正常的冷启动首局,玩家看到的是 502 —— 比慢更糟。
   回看纪律照 ADR-024:**出现一次误掐就调大;「从没掐到过」不构成调小的理由**。
7. **非法 archetype 今天就会扣一次 init 额度**(`checkInit` 在校验之前);本刀把顺序改为「准入 → 额度 → init」,
   这一点**原样保留**,不顺手修。

## 待决(交校勘裁定,本草案不替你选)

1. **是否在本刀加一行 world-gen `durMs`**(两个终点:成功 / 失败,同层 1 第 4 条形状)。不加则勘察 2 的
   「上线后按日志复核」无数据可复核;加则超出 brief 的字面范围(一行 INFO,零行为)。
2. **段时限默认值**:180 s(本草案提议)或其他。
3. **前端 503 标题**:`GameScreen` 的错误屏标题只区分 `quota_exceeded`,`server_at_capacity` 会显示
   「世界生成失败」+ 服务端文案「此刻同时开局的人太多…」,标题与正文不一致。改它是一行前端改动,超出 brief「做」的范围。
4. **容量默认值**:4(本草案提议)或其他。

## 重新审视的触发条件

- 线上出现一次 `[world-gen]` 段超时且判定为正常请求(误掐)→ 段时限调大,回本 ADR;
- 出现真人被 `[init-admission]` 拒绝 → M 的取值被证伪,重估;
- 修复调用变成两发以上 → 「最坏 2 × 段时限」与 390 s 当场失效,重算;
- 出现第三条准入路径 → 抽公共构件(决策 3 第 3 条);
- 服务端获得中断上游流的能力 → 已知代价 2/3 可缓解,同时可重评「取消生成」;
- 多副本部署 → per-instance 准入失效,与 ADR-022 / ADR-016 同批重估。

## 实施步骤(建议分刀)

1. 抽出段时限构件到 `llm` 包,回合侧改用它(行为逐字不变,ADR-024 全部测试照绿)+ world-gen 接入(决策 4);
2. `InitProperties` + `InitAdmission` + `GameController.init` 改 `DeferredResult`(决策 1–3);
3. 冒烟:压 `AIUNIVERSE_INIT_MAX_CONCURRENT=1` 两个浏览器同时开局 → 第二个 503;压段时限到几秒 → 502 且 `/data` 无新档。

## 测试面(实现时加,本 ADR 只列;每条做变异校验,须能**单独**失败)

准入:
- 拒绝发生在容器线程(被拒请求的线程名不是池线程;同 ADR-022 刀 3 的判据);
- 工作抛 `RuntimeException` 与 `Error` 两种情形都还槽(变异:`catch (Throwable)` 改回 `RuntimeException` → 只有 Error 那条红);
- `execute` 本身抛时在容器线程还槽;
- 被拒请求:world-gen 调用计数 = 0、`checkInit` 调用计数 = 0(用同步计数,不用异步线程里的读数 —— ADR-022 竞态绿的教训);
- 503 响应 content type 为 JSON(MockMvc 实拍,不直接调方法);
- 控制器源码不持有线程池(源码级断言,只扫 `GameController.java`、无排除项);
- 生产构造器启动日志打出解析后的 N(用非默认值,防字面量假绿);
- `DeferredResult` 超时显式给定且 > 2 × 段时限(防回落到容器 30 s 默认)。

时限:
- 假时钟下,主调用某 token 到达时已过线 → 502 `world_gen_failed`,`sessions` 无新增;
- 恰好等于上界放过(闭合方向);
- 主调用成功 + 修复调用超时 → 502、无 session;主调用的 `quota.record` 被调用一次,修复段未记账;
- 修复段有自己的预算(主调用用掉大半时间后,修复段不被提前掐);
- 回合侧既有 ADR-024 用例全部照绿(抽构件不改回合行为);
- 现有 world-gen prompt(16 处中 world-gen 侧 8 份)字节级不变(dump 前后对拍,先跑「同一构建两次全等」自检)。

## 跟其他文档的交叉引用

- [ADR-015](ADR-015-overseas-deployment-form-factor.md) 已知代价 2 与重新审视条件:本 ADR 是其预登记出口,**在触发条件发生之前做**;
- [ADR-022](ADR-022-turn-admission-and-rejection-semantics.md):形状来源;其重新审视条件「两处准入是否该合并成一道」由决策 3 回答;
- [ADR-024](ADR-024-stream-segment-deadline.md):段时限语义与插入位置的来源,§挂账「彻底静默」同一缺口;
- [工程债](../backlog-engineering-debt.md)「成本闸门少算被中止的调用」:已知代价 5;
- [future-experience-backlog §2.3](../future-experience-backlog.md):孤儿存档,非目标 B3。
