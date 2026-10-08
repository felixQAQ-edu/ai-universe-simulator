# ADR-031 · 回合执行轨迹 + 不调模型回放:记下每回合模型交来的东西,离线重放结算

- **日期**:2026-10-06
- **状态**:**已采纳(2026-10-07)**;刀 1(采集 + `TraceSink` 接缝,`NOOP`)已合并(`main@fb2209d`);刀 2(编解码 + 测试侧档 1 回放,含钳制冲突 (b))已合并(`main@7d46367`);刀 3(文件落点 + 上限 + runbook,含目录不可用降级)已合并(`main@681721a`;部署状态本条未核);刀 5a(回归夹具转换工具 + 夹具回放测试,不含任何真实轨迹)已实现,待校勘 / 未合并、未部署;刀 5b(首个真实夹具)未起(见文末「实现进度」)。原「待决」已改为「已决」(Felix 裁定),原文保留在已决之下
- **决策者**:Felix
- **前提**:`main@f89fea6`。依据 [层 3.2 勘察底稿](../tool-calling-survey.md) 候选 γ、O-7、O-8,
  [求职线 3.2 状态更新](../backlog-career-track.md)(裁定为 γ,不引入 tool calling)。
- 路径前缀 `…/` = `server/src/main/java/com/aiuniverse/server/`。行号以 `f89fea6` 为准,会漂。
  推断标「**推测**」,没核实的标「**未证实**」。

---

## 置顶:γ 的前提勘察结论 —— 成立,但有两条限定

**没有发现 γ 的价值或可行性前提不成立。** 两条限定写在这里,因为它们决定了后面的选项:

1. **「线上问题复盘」这条用途只有文件落点能兑现。** 线上没有数据库(ADR-025 刀 4 挂账,方案 D),
   而 ADR-026 已决 G 对 `llm_call` 的裁定理由逐字是「它的价值在线上可观测性,线上没库之前为零」
   (`ADR-026-turn-acceptance-record.md:190`)。**同一条理由原样适用于「只在 pg 下落库的轨迹」**:
   它能做回归测试素材(本地 / CI 的 pg),做不了线上复盘。
2. **回放只在「同一代码版本」下有「应当一致」的含义。** 跨版本重放出现差异是预期行为(那正是回归测试要报的东西),
   不是轨迹坏了。故每条轨迹必须带代码版本(`build.commit`,`server/pom.xml:122`)。

---

## 背景

一个回合今天留下的持久记录只有覆盖写的存档(`<saveId>.json`,`SessionDocument.encode`,
`…/persistence/SessionDocument.java:38-48`)与 pg 下的叙事历史 / 受理记录(ADR-025 / ADR-026,线上未启用)。
**模型这一回合实际交来了什么、被怎样改写后落账,任何地方都没有持久记录**(底稿问题 8)。
日志里有一部分(usage、durMs、落账后数值、修复「条数」),但日志是瞬时的(工程债挂账「日志是瞬时的」)。

今天唯一「不调模型的回放」是 `EngineGoldenTest`(`server/src/test/java/…/engine/EngineGoldenTest.java:23`):
喂 bake-off 录制的 `{actionId, parsed}`(`server/src/test/resources/golden/event-loop-golden.json`,
`paths.*.turns[*]` 的键实测就是这两个)给 `Engine`,断言终态。**γ 就是把这个形态从「三条 bake-off 路径」
推广到「线上任何一个回合」。**

### 命名(底稿 O-7)

| 词 | 含义 | 宿主 |
|----|------|------|
| **回看** | 只读展示已发生的叙事 | ADR-025(`/history`,pg 下) |
| **回放** | 从记录下的「回合前状态 + 模型产出」**重新执行服务端结算**,不重新生成叙事 | **本 ADR** |
| **轨迹** | 回放所需的那份记录 | **本 ADR** |

本 ADR 里「回放」只指第二行。不在本 ADR 范围内把两者合并成一个功能。

---

## 一、记什么

### 1.1 回合链路上,哪些东西决定了落账后状态(勘察)

`EventLoopService.execute`(`…/eventloop/EventLoopService.java:160-233`)一个回合的顺序:

1. 局面编排 `scenePlan`(`:173`,纯函数,输入 = 存档里的 `boxScene` + `actionId` + 回合号)、渲染 prompt(`:185-186`)。
2. 主调用流式 → `SentinelSplitter` 切出叙事与尾巴(`:189-206`);流中断走 `degrade`(`:198-203`)。
3. 叙事空 / 无哨兵 / 无尾巴 → `degrade`(`:214-218`)。
4. `TurnReinfuser.reinfuse` + `GameSchemas.validateTurn`(`:236-244`);局面槽位检查(`:222-227`)。
5. 失败 → `repairOnce`(`:250-273`):修复错误清单在 `:254-261` 算出,**只把条数写进日志**(`:271`);
   修复产出回灌同一叙事(`:272`)。
6. 仍失败 → `degrade`(`:231`);成功 → `settle`(`:277-311`)。
7. `settle`:**`clampClosingVigorFloor` 先就地改写 `parsed`**(`:280`,`:581-617`)→ `engine.apply(parsed, actionId)`(`:281`)
   → `BoxSceneTurn.commit`(`:285`)→ 选项:局面回合 `sceneActions`(`:299`),否则 `updateActionsFromParsed`
   (`:301`,内部 `appendLifeExitAction`,`:491-498`、`:515-533`)。
8. `degrade`:`engine.applyNoOp(已流出叙事 [+ 离开叙事], actionId)`(`:332`)→ `BoxSceneTurn.commit`(`:335`)
   → `markDegraded`(`:337`)→ 局面回合用模板槽位(`:343-346`)。
9. 回到 `TurnStateMachine.submitAction`:`store.persist(session)`(`…/eventloop/TurnStateMachine.java:107`)。

**`Engine.apply`(`…/engine/Engine.java:267-339`)对给定的「回合前引擎状态 + `parsed` + `actionId`」是确定的**(推测,依据:
方法体内无随机数、无时钟读取;轴集合只做 `contains`,`:495`、`:504`;数值遍历用 `LinkedHashMap`,`:75`;
golden parity 守它与 Python 引擎逐字段一致)。

### 1.2 字段清单(回放必需 / 诊断用 / 可省)

「回放」的两档定义见 §三。下表「必需」指**档 1**(重放落账)所需;标「档 2」的是**档 2**(重放整个 SETTLING 阶段)额外所需。

| 字段 | 分类 | 理由(出处) |
|------|------|------|
| `schema`(轨迹格式版本)、`saveId`、`turnBefore`、`recordedAt` | 必需 | 定位与格式演进 |
| `commit`(代码版本) | 必需 | 回放「应当一致」只在同版本成立(置顶 2);`/actuator/info` 的 `build.commit` 来源 `server/pom.xml:122` |
| **回合前状态** `pre` = `SessionDocument.encode(session)` 在 `execute` 开头的副本 | 必需 | 含视图 1 全量 + `currentActions` + `boxScene`;`Engine.restore` 往返逐字节(ADR-015 附录 A)。可改为引用,见待决 W-2 |
| `actionId` | 必需 | `apply` 的第二参数 |
| `path` = `settled` / `degraded` | 必需 | 两条终止路径落账函数不同(`apply` vs `applyNoOp`) |
| **`parsed`(改写后、apply 前)** | 必需(settled) | 即 `clampClosingVigorFloor` 之后的节点 —— 档 1 只重放 `apply`,服务端改写的结果已在其中 |
| `degradeReason` + `streamedNarrative` | 必需(degraded) | `applyNoOp` 的入参就是已流出叙事(`:332`);三种降级原因今天只在 WARN 里(`:200`、`:216`、`:231` 经修复失败) |
| `rawNarrative` + `rawTail` | 档 2 必需;档 1 可省 | 档 2 要从原始文本重跑回灌与校验;成功回合里叙事已在 `parsed.narrative` 中,尾巴≈`parsed` 去叙事 |
| `repair` = `{errors[], rawTail}` | 档 2 必需;诊断用 | 修复错误**内容**今天无处可查(日志只有条数,`:271`) |
| `parsedBeforeRewrite` 或「改写差异」 | 诊断用 | 只在钳制触发时与 `parsed` 不同;钳制本身已进 `issues`(`:612-613`)并随存档落盘 |
| `promptSha256` | 诊断用 | 同版本下 prompt 可由 `pre` + `actionId` 重新渲染(`TurnPromptBuilder` 给定输入逐字节确定,底稿问题 9),哈希用来核对「重渲染的就是当时那份」 |
| prompt 全文 | **可省** | 同上,可重渲染;体积最大(见 1.3) |
| `post` 摘要 = 落账后 `SessionDocument.encode` 去掉 `phaseHint` 的 sha256 + 各轴数值 | 必需 | 回放的比对目标。`phaseHint` 必须排除:它记的是 persist 那一刻的相位,正常回合为 `SETTLING`、主调用流中断降级为 `GENERATING`(相位在 `:207` 才置 `SETTLING`),**与落账无关** |
| `usage`(含缓存命中)、`model`、`reasoningChars` | 诊断用 | 今天在 usage INFO 里(`:317`) |
| `durMs` | 诊断用 | 今天在 per-turn INFO / 降级 WARN 里(`:295-297`、`:340-341`) |
| `leak` 命中 | 诊断用 | 由 `apply` 确定地重算出来,今天在 WARN 里(`:287-289`) |

> **订正(2026-10-07,刀 2 冲突已裁定 (b);上文原文保留)**:`parsed` 改为取在服务端改写**之前**(校验 / 修复通过之后、
> `clampClosingVigorFloor` 之前)。改写由共用落账入口 `EventLoopService.landSettled` 在 `apply` 之前做,
> 线上与回放走同一个入口完成「改写 → `apply` → 局面 → 选项」,钳制写进 `issues` 的那一条因此能在回放中重现。

### 1.3 单回合体积估算(算式)

| 量 | 取值与出处 | 估算 |
|----|------|------|
| 开局存档 | 真实线上一档 **5917 B**(`ADR-015-overseas-deployment-form-factor.md:170`) | — |
| 一条 log 叙事 | 基线语料 16 发 3059 字(`ADR-004-baseline-corpus.md`)→ 191 字/回合 × 3 B(UTF-8)≈ **575 B** | — |
| `pre` | 5917 + 4 条 log × (575 + ~40 键) + `currentActions` ~500 + `logSummary` ~10 B×T | **≈ 8.9 KB**(T=30);**范围 6–9.5 KB** |
| `parsed` | 叙事 575 + 选项(61 条 2014 字 / 16 回合 ≈ 126 字 × 3 B ≈ 380)+ JSON 键与 `stateUpdate`/`timeline` ~400 | **≈ 1.4 KB**(golden 里 bake-off 时代一条实测 970 B) |
| `rawTail` | ≈ `parsed` − 叙事 | ≈ 0.8 KB |
| `repair` | 错误 ~5 条 × 60 B + 修复尾巴 0.8 KB ≈ 1.1 KB × 触发率 **12.5%**(n=16,ROADMAP v10.5) | 期望 ≈ **0.14 KB** |
| 元数据 + `post` 摘要 | 各键 + 两个 sha256(64 B)+ 数值 | ≈ **0.4 KB** |
| prompt 全文 | 回合 SYSTEM 段 4016 B(rules_creepy)– 13233 B(life_sim)(ROADMAP v6.4 / v7.7 dump)+ contextJson ≈ `pre` 的世界部分 6–8 KB;金样本(玩具世界)实测 14679–15089 B(`golden/adr028-legacy-turn-prompts/*`) | **≈ 10–22 KB** |

合计(每回合):

- **档 1 自含**(`pre` 全量 + `parsed` + 元数据):8.9 + 1.4 + 0.4 ≈ **10.7 KB**
- **档 1 + 档 2**(再加 `rawTail` + 期望 `repair`):10.7 + 0.8 + 0.14 ≈ **11.6 KB**
- **链式**(`pre` 只在第 1 条存全量,其后只存引用):1.4 + 0.8 + 0.14 + 0.4 ≈ **2.8 KB**
- 任一形态再存 prompt 全文:**+10–22 KB**

折算到量:一局 50 回合 ≈ 0.58 MB(自含)/ 0.14 MB(链式)/ 1.1–1.7 MB(带 prompt)。
按 ADR-016 重算注记的月闸上限 ≈ 1.9–2.1 万回合(`ADR-016-cost-gate.md:34-35`):自含 ≈ **230 MB/月**,链式 ≈ 60 MB/月,
带 prompt ≈ 430–690 MB/月。按今天的实际量(本月 ¥4.05 ÷ ¥0.0083 ≈ 490 回合,ROADMAP v13.2 读数)自含 ≈ **5.6 MB/月**。
Fly 卷 1 GB(`docs/phase3-fly-deploy-runbook.md:36`)。

### 1.4 world-gen 记不记(问题 2)

- 开局世界 **已在存档里**,但会被后续 `markRuleDiscovered` / `markEndingReached` 原地改写(底稿问题 9 第 1 条)。
  **自含形态下,第 1 回合的 `pre` 就是开局世界的完整快照** —— 回放不需要另记 world-gen。
- world-gen 的修复错误**已在日志里**(`…/worldgen/WorldGenService.java:140` 逐条打出 `errors`);场景种子是随机挑的
  (`…/worldgen/WorldGenPromptBuilder.java:733`、`:742` `ThreadLocalRandom`),没有记录。
- **world-gen 失败时没有 saveId**(`GameInitService.java:67` 先生成、`:85` 才建会话),失败那一发的轨迹**没有一个可以挂靠的存档**,
  要记就得另有落点 —— 这一点对 §二 每个落点都成立。见待决 W-4。

---

## 二、存在哪里(底稿 O-8)

### 2.1 写入时机与失败语义(对所有落点相同)

- **采集**:`EventLoopService` 在已经持有这些值的位置往一个本回合收集器里放(`execute` 开头取 `pre`;`settle` 在 `apply` 前取 `parsed`;
  `repairOnce` 取错误与修复尾巴;`degrade` 取原因与已流出叙事)。流式期间只追加到内存里已有的 `StringBuilder`(`:189`、`:265`),
  **不做任何 I/O**。
- **写出**:在 `TurnStateMachine.submitAction` 里、`store.persist(session)` **之后**(`:107` 之后、`:108` 设相位之前),以及
  「已落地未送达」分支的补写盘之后(`:120`)。此处:流已结束;不持有任何锁(并发控制是相位 CAS,`:95`,不是互斥锁);
  pg 下 persist 的事务已提交(轨迹若进 pg,用**另一段**短事务,见待决 W-5)。
- **失败语义**:写轨迹失败**只记一条 WARN**,catch 范围与 `FileSessionStore.persist` 的 best-effort 同口径;**不改相位、不抛、不影响回合结果、
  不回滚**。轨迹是旁路记录,不是关键路径(同 CONTEXT §三.17 (4) 对 persist 的定性)。
- **未落地分支**(`:122-128`):回合没有落账,没有 `post`。记不记一条「未落地」轨迹 → 待决 W-6。
- **时延**:写出发生在 worker 线程上、准入名额之内(ADR-022「名额跟 worker 走」),会让名额多占一次写操作;同 `persist` 的位置,
  `durMs` 锚点不包含它(那个锚点在 `execute` 内结束)。

### 2.2 三个落点 + 一个参照

| | A · 写进存档本身 | B · 每局一个追加写轨迹文件 | C · 只在 pg profile 下落库 | 参照 · 只打日志 |
|---|---|---|---|---|
| **形态** | `SessionDocument` 加一个 `trace` 数组 | `<saveId>.trace.jsonl`,一回合一行 | 新表(`game_turn_trace`)或扩 `turn_request` | 一回合一行 INFO |
| **单机文件存储下可行性** | 可写,但存档随回合线性增长 | 可行 | **默认 profile 不存在** | 可行 |
| **Fly 卷 1 GB** | 同 B 的体积,但见下「覆盖写」 | 今天量 ≈ 5.6 MB/月(自含);月闸上限 ≈ 230 MB/月 → 约 4 个月写满,**必须有清理** | 不占卷 | 不占卷 |
| **整份覆盖写的代价** | **每回合重写整份**:写入量随回合数平方增长(一局 50 回合自含 ≈ Σ 10.7 KB×k ≈ 13.6 MB);且 **`loadAll` 启动时把所有存档读进内存**(`FileSessionStore.java:115-123`),今天 145 档(ROADMAP v12.4)× 0.58 MB ≈ 84 MB,对 512 MB 机器上默认堆约 128 MB(runbook `:345`)是实际风险;`GameSession` 还得在内存里常驻整份轨迹才能重编码 | 追加写,写入量线性;启动不读(`loadAll` 只收 `.json` 结尾,`:123`;`.trace.jsonl` 不匹配) | 与 persist 同库,不覆盖 | 不适用 |
| **ADR-022「第三个租户」** | 不触发(仍是存档文件) | **触发**:存档、月账之后第三个往 `store-dir` 写文件的模块,`ADR-022-…:670` 逐字解冻条件。放子目录是否算「往这个目录写」→ 待决 W-3 | 不触发 | 不触发 |
| **与 ADR-025 叙事历史** | 无关;但文件 profile 下存档里会第一次出现全量叙事 | 文件 profile 下轨迹里含全量叙事(`parsed.narrative`)—— 与 ADR-025「文件 profile 下 `/history` 返回 501」并存,**不得被当成回看数据源**(非目标 §四) | `game_event` 已有叙事;轨迹可只存 `parsed` 去叙事 + 引用 `(save_id, turn)`,或冗余存 | 无关 |
| **与 ADR-026** | 无关 | 无关 | 已决 G 的理由原样适用(置顶 1);形态上可挂在 `turn_request` 行上 | 无关 |
| **崩溃一致性** | 与快照原子一致(同一次原子写) | 与快照**不原子**:写在 persist 之后,崩溃在两者之间 → 存档有该回合、轨迹没有(少一条,可接受);末行可能半截 → 读取方跳过不能解析的末行。每条自含,丢一条不影响其他条的回放 | 若与 persist 同事务则原子;若另一段事务则同 B(待决 W-5) | 无保证 |
| **保留期与清理** | 跟存档一起,今天无 TTL(future-experience §2.3) | 须新定:每文件上限 / 总量上限 / 局结束后保留 N 天(待决 W-7)。今天存档本身也无 TTL | SQL 可清理;无卷压力 | 随日志消失(Fly 无 drain) |
| **兑现「线上复盘」** | 能 | 能 | **不能**(线上无库) | 不能(瞬时) |
| **兑现「转回归用例」** | 能 | 能(一行即一个用例) | 能(本地 / CI) | 不能 |

### 2.3 一句话结论(问题 3)

- **A · 写进存档**:原子一致是唯一优点,但每回合整份重写与启动全量载入让体积问题落在内存和写放大上,**不可行**(推测,依据上表两条算式)。
- **B · 每局追加写文件**:**唯一能在线上兑现复盘的落点**,代价是触发 ADR-022 第三个租户解冻、并且必须同时定清理规则。
- **C · 只在 pg 落库**:实现最干净、一致性最好,但**线上零价值**,与 ADR-026 已决 G 的挂账理由相同;只能服务回归测试。

---

## 三、回放是什么(底稿 O-7)

### 3.1 可测试的定义

**档 1 · 落账回放**:给定一条轨迹 `r`,在 `r.commit` 对应的代码上,

1. `session0 = SessionDocument.decode(r.saveId, r.pre, …)`(经 registry 重派生轴集,与回载同一路径);
2. `path=settled`:`session0.engine().apply(r.parsed, r.actionId)`,再按局面编排 `BoxSceneTurn.commit`、按 `settle` 同一规则更新选项;
   `path=degraded`:`applyNoOp(r.streamedNarrative [+ 离开叙事], r.actionId)` + 同 `degrade` 的后续;
3. 断言 `sha256(encode(session0) − phaseHint) == r.post.sha256`。

> **订正(2026-10-07,刀 2 冲突已裁定 (b);上文原文保留)**:第 2 步 settled 改为经 `landSettled`(先 `clampClosingVigorFloor`
> 再 `apply`,再局面与选项)——轨迹里的 `r.parsed` 是改写前的节点,改写在回放中重做。degraded 经 `landDegraded`。

**不重新调模型、不重新生成叙事。** 不经过 SSE、准入、配额、游标。

**档 2 · 结算回放**:从 `r.rawNarrative` + `r.rawTail`(及 `r.repair.rawTail`)出发,重跑「切分 → 回灌 → 校验 → 槽位检查 →
(修复产出回灌)→ `clampClosingVigorFloor` → 落账」,断言得到的 `parsed` 等于 `r.parsed`、终态同档 1。
档 2 能把**校验失败、修复失败**的真实回合变成用例 —— 这类回合在档 1 里只是一个 `applyNoOp`,没有可测的东西。
切分的分块无关性由 `TransformParityTest` 守(推测:以整串一次喂入切分器与逐 token 喂入结果相同)。

### 3.2 今天会破坏确定性的因素

| 因素 | 出处 | 档 1 | 档 2 |
|------|------|------|------|
| 模型输出(temperature 0.7) | `OpenAiCompatLlmClient.java:38` | 不重新生成,用记录的 `parsed` | 用记录的原始文本 |
| 随机数 | 回合路径无;仅 world-gen 种子 `WorldGenPromptBuilder.java:733,742` | 无影响(world-gen 不在回放内) | 同左 |
| 时钟 | `EventLoopService` 注入 `Clock`(`:67`),只用于 `durMs` 与段时限(`:117-121`) | 不进状态;无影响 | 段时限是否触发**不重放**:以记录的 `path`/`degradeReason` 为准 |
| `clampClosingVigorFloor` 改写 `parsed` | `:280`、`:581-617` | 已含在记录的 `parsed` 里 | 重算;依赖 registry 与 `LifeStageTable`(同版本下确定) |
| `appendLifeExitAction` 改选项 | `:515-533`,读 `engine.log()`/`logSummary`(`:547-556`) | 只影响 `currentActions`,由回合前状态确定地重算 | 同左 |
| 纸箱局面编排与结算 | `scenePlan` `:375`、`BoxSceneTurn.plan` `BoxSceneTurn.java:105`、`commit` `:285`/`:335` | 纯函数,输入在 `pre.boxScene` 里;重算 | 同左;另需重跑 `slotErrors`(`:394`) |
| 泄露检测 | `Engine.apply` 内 `LeakDetector.detect` | 确定;结果只进日志,不比对 | 同左 |
| `phaseHint` | `SessionDocument.java:42` | 不比对(见 1.2) | 同左 |
| 代码版本 | — | 同版本应一致;跨版本差异是**报告**而非失败,由回放工具标出 | 同左 |
| 外部依赖:registry 数据 | `SessionDocument.decode` 重派生轴集 `:66-70` | 属代码版本的一部分 | 同左 |

### 3.3 用途(写死两条)

1. **线上问题复盘**:拿到一个出问题的 `saveId` + 回合号,取那条轨迹,本地档 1 / 档 2 重放,看结算每一步的输入输出。
2. **真实失败回合转回归用例**:把一条轨迹放进测试资源,作为一条 JUnit 用例的夹具(形态同 `EngineGoldenTest`)。

**不做**通用评测平台、批量统计、模型对比、prompt A/B。

---

## 四、边界

### 4.1 隐私与体积

- **玩家输入是闭集**(ADR-004 §背景订正;守卫 1 精确相等),prompt 与 `pre` 里没有玩家自由文本、没有 IP / deviceId
  (这两个只在配额键里,`QuotaGate.ClientKey`)、没有 API key。
- **`pre` 是视图 1 全量,含 `isTrue` / `hiddenLogic`**;prompt 是视图 2,同样含 `hiddenLogic`。轨迹的保密级别**等同存档**:
  只在服务端,**不得经任何出网路径下发**(CONTEXT §三.9、§三.17 (1);今天任何出网都必须过 `toClientState`,`Engine.java:374`)。
  文件落点须与存档同样在 web 根之外(ADR-015 启动断言同口径)。
- **日志里已有的诊断字段**(usage / durMs / 落账数值 / 泄露命中)默认不在轨迹里重复存 —— 但日志是瞬时的,
  不存就意味着复盘时可能拿不到。见待决 W-8。
- 体积大头是 `pre`(若存全量)与 prompt 全文(若存);两者都有可省的形态(引用 / 哈希)。

### 4.2 非目标

- **tool calling**(3.2 裁定为 γ;「Agent 工具调用」继续在「不要写」清单,`backlog-engineering-debt.md:867`)。
- **Agent 编排**、多次模型往返。
- **指标平台**、仪表盘、批量统计(统计脚本仍按其自身解冻条件冻结)。
- **前端回放界面**;轨迹不下发前端、不作为 `/history` 的数据源。
- **重放时重新调用模型**、叙事重生成、模型对比评测。
- world-gen 失败的轨迹(除非 W-4 选择记)。

---

## 候选方案对比(汇总)

| 维度 | A 存档内 | B 追加文件 | C 仅 pg |
|---|---|---|---|
| 线上复盘 | 能 | 能 | 不能 |
| 回归用例 | 能 | 能 | 能 |
| 内存 / 启动 | 差(全量载入) | 不受影响 | 不受影响 |
| 写放大 | 平方 | 线性 | 线性 |
| 一致性 | 与快照原子 | 可能少末条 / 半行 | 可原子 |
| 新增运维 | 无 | 清理规则 + 第三租户分家 | 无(线上不跑) |
| 与既有裁定冲突 | 无 | 触发 ADR-022 解冻条件 | 与 ADR-026 已决 G 同理由 |

---

## 已决(2026-10-07,Felix 裁定)

> 本节取代原「待决」一节;原文逐字保留在本节末尾「原待决(2026-10-06,保留)」之下。
> 正文 §一–§四 里写着「见待决 W-n」或「若选 B」的地方,以本节为准。

- **W-1 · 落点 = B(每局一个追加写轨迹文件),不做 C。** 不做 C 的理由同 ADR-026 已决 G
  (`ADR-026-turn-acceptance-record.md:190`:价值在线上可观测性,线上没库之前为零)。
  **测试用内存 `TraceSink`**(不起数据库、不写文件),同 `SessionStore` / `TurnLedger` 的接缝形态。
- **W-3 · 独立配置目录 `aiuniverse.trace.dir`,线上 `/data/traces/`。**
  解释:ADR-022 §挂账「落盘目录分家」的解冻条件(`ADR-022-…:670`「第三个模块也往这个目录写文件时」)
  防的是**共用扫描面** —— 第三个租户的文件会进入 `loadAll` 的候选集、要靠文档形状判据分流。
  **`loadAll` 不递归时,子目录里的文件根本不进候选集,不构成第三个租户。**
  - **前提已核实(`main@f89fea6`)**:`loadAll` 用 `Files.list(dir)`
    (`server/src/main/java/com/aiuniverse/server/persistence/FileSessionStore.java:122`)——
    JDK 语义只列出该目录的直接条目,**不递归**;子目录 `traces/` 本身作为一个条目出现,
    但被 `endsWith(".json")` 过滤掉(`:123`)。全 `server/src/main/java` 内 `Files.walk` / `Files.find` /
    `newDirectoryStream` **零命中**,`Files.list` 只此一处。
  - **实施中以测试钉住**(测试面 9):在存档目录下建 `traces/` 子目录并放入 `.json` / `.jsonl` 文件,
    `loadAll` 的载入与「跳过非存档」计数都不变;变异「`Files.list` 改 `Files.walk`」须使该用例变红。
    将来若有人把 `loadAll` 改成递归,本解释当场失效,须回本 ADR 与 ADR-022。
  - **ADR-022 原文不改。** 本条只记录这个解读与它的前提。
  - 轨迹目录与存档目录同受「必须在 web 根之外」的启动断言(ADR-015,CONTEXT §三.17 (4) 同口径)。
- **W-2 · `pre` 存全量**(自含,≈10.7 KB/回合;每条独立可回放,一条即一个用例)。
- **W-7 · 不做自动删除。** 单文件上限 **5 MB**、目录总量上限 **200 MB**;到限**停写 + WARN 一次**
  (每文件 / 总量各一次,不刷屏),**回合不受影响**。两个上限 env 可覆盖,**启动打一行现值**
  (同 ADR-022 层 1 第 6 条「启动打准入容量 N」的形状)。清理走 runbook,手动 `fly ssh`。
  折算(按 §1.3 自含 ≈10.8 KB/回合):单文件 5 MB ≈ 460 回合;总量 200 MB ≈ 1.85 万回合 ——
  约等于月闸上限一个月的量,远大于今天的实际量(≈5.6 MB/月)。
- **W-8 · 诊断字段存入轨迹**:usage(含缓存命中)/ `durMs` / `model` / `reasoningChars` / 降级原因 / 修复错误清单。
  理由:日志是瞬时的(工程债挂账),「日志里已有的不重复存」在这里让位。
- **W-9 · prompt 只存 sha256 + `commit`。** 并加测试:**同版本下由 `pre` + `actionId` 重新渲染 prompt,sha256 与记录一致**
  —— 把 §1.2 里「可重渲染」这条推测变为已证(测试面 11)。
- **W-4 · world-gen 不记。**
- **W-6 · 未落地回合不记**(`TurnStateMachine` 未落地分支);**降级回合照记**(它已落地,`applyNoOp`)。
- **W-5 · 不适用**(不做 C)。
- **W-10 · 只做测试侧回放工具,不加服务端端点。** 线上取轨迹走 `fly ssh`,实施时写进 runbook。
- **W-11 · 原始轨迹与回归夹具(2026-10-08,Felix 裁定)。**
  - **线上取回的原始轨迹文件:一律不进仓库、不进聊天、不交给云端会话。**
  - **由原始文件转换出的回归夹具:只来自 Felix 为夹具专门开的、本人玩的局;只保留回放必需字段;替换能关联线上存档的标识;
    确认无密钥 / 请求头 / IP / 非本人输入;字段清单与扫描报告经人工核对后才提交。若转换后无法明确划出安全字段,则不公开提交。**
  - 机器能保证的那一半由 `TraceFixtureTool` 承担(白名单 / saveId 替换 / 逐行档 1 自检 / 敏感形态扫描 / 输入输出须在仓库之外);
    「是不是本人专门开的局」「actionId 是不是本人选的」只能由人核对,工具只把核对所需的清单列进报告。字段清单与勘察见下「刀 5a 勘察」。

### 原待决(2026-10-06,保留)


- **W-1 · 落点**:A / B / C 选哪个,或 B + C 都做(两个 profile 各一个实现)。
- **W-2 · `pre` 全量还是引用**:自含(≈10.7 KB/回合,每条独立可回放)vs 链式(≈2.8 KB/回合,回放第 N 回合要从第一条起连续重放,
  任何一条缺失或代码版本变化就断链)。用途 2「一条即一个用例」倾向自含,但这是取舍不是结论。
- **W-3 · B 的目录**:与存档同目录(`<saveId>.trace.jsonl`)还是子目录;子目录算不算「第三个模块往这个目录写文件」,
  决定要不要先做 ADR-022 §挂账的分家那一刀(代价 1–3 见 `ADR-022-…:661-668`)。
- **W-4 · world-gen**:不记 / 只在成功时作为第 0 条记种子与修复错误 / 失败时也记(失败没有 saveId,需要另一个落点)。
- **W-5 · C 的事务**:轨迹与 persist 同一事务(原子,但轨迹写失败会不会拖累 persist 要单独论证)还是另一段短事务。
- **W-6 · 未落地回合**:记一条只有 `pre` + 失败原因的轨迹,还是不记。
- **W-7 · 保留期与清理**:每文件上限 / 总量上限 / 局结束后保留天数;与存档无 TTL(future-experience §2.3)是否同批处理。
- **W-8 · 已在日志里的诊断字段**:按「日志里已有的不重复存」不存,还是因日志瞬时而存。两条原则在这里冲突。
- **W-9 · prompt**:只存 sha256(可重渲染)还是存全文(+10–22 KB/回合,但不依赖「同版本可重渲染」这条推测)。
- **W-10 · 回放入口形态**:只做测试侧工具(读轨迹文件 → 断言),还是另有一个服务端只读入口。后者要新端点,与 echo-stream 那次教训同类风险。

---

## 测试面(实现时加,本 ADR 只列;每条做变异校验,须能单独失败)

1. **轨迹写失败不影响回合**:注入抛异常的轨迹写入器,回合照常落账、相位照常、存档照常写。
2. **档 1 往返**:正常回合、降级回合(三种原因各一)、纸箱局面回合(含离开回合)、一生制钳制触发回合、结局回合 —— 各录一条轨迹,
   回放后 `post` 摘要相等。
3. **`phaseHint` 不进摘要**:主调用流中断降级(`GENERATING`)与正常回合(`SETTLING`)的摘要计算不读它。
4. **`parsed` 取在改写之后**:钳制触发的回合,记录的 `stateUpdate` 是钳制后的值;变异「在钳制之前取」→ 档 1 回放失败。
   > **订正(2026-10-07,已裁定 (b);上行原文保留)**:改为「轨迹记录的是改写前的 `parsed`;回放经 `landSettled` 重做钳制,
   > 钳制回合的 `post` 摘要与线上一致(含那条 issue)」;变异「记录点挪回钳制之后」→ 重钳不产生 issue → 摘要不等 → 变红。
5. **档 2**:校验失败 → 修复成功的真实回合,从原始文本重跑得到相同 `parsed`;修复仍失败的回合重跑得到「降级」。
6. **跨版本**:改动 `Engine.apply` 的一处行为后,回放工具报告差异而不是静默通过。
7. **采集不在流式期间做 I/O**:流式回调里不调用写入器(源码级或桩计数)。
8. **消毒**:轨迹不出现在任何出网响应里(源码级断言:控制器层不引用轨迹类型)。
9. **`loadAll` 不读轨迹目录**(W-3 前提的守护):存档目录下建 `traces/` 子目录并放 `.json` / `.jsonl` 文件,`loadAll` 的载入数与
   「跳过非存档」计数都不变;变异「`Files.list` → `Files.walk`」须使本条变红。另:半截末行不影响前面各行的读取。
10. **上限**(W-7):单文件到 5 MB、总量到 200 MB 时停写,各只 WARN 一次,回合照常落账与写盘;env 覆盖生效;启动打一行现值。
11. **prompt 可重渲染**(W-9):同版本下由记录的 `pre` + `actionId` 重新渲染 prompt,sha256 与记录一致(正常回合、纸箱局面回合、一生制回合各一)。
12. **未落地回合不写轨迹,降级回合写**(W-6)。

---

## 实施分刀

1. **刀 1 · 采集 + 接缝(`NOOP`)**:本回合收集器 + `TraceSink` 接口 + `NOOP` 实现 + 测试用内存实现
   (照 `SessionStore` / `TurnLedger` 的接缝形态);写出点放在 `TurnStateMachine` 两处 persist 之后;
   诊断字段按 W-8 收齐;测试面 1、3、4、7、8、12。默认 profile 行为逐字节不变。
2. **刀 2 · 测试侧档 1 回放**:读一条轨迹 → 档 1 回放 → 比对摘要;用刀 1 在测试里产出的轨迹做往返(测试面 2、6、11)。
3. **刀 3 · 文件落点 + 上限 + runbook**:`aiuniverse.trace.dir`(线上 `/data/traces/`)、追加写 `<saveId>.trace.jsonl`、
   web 根之外断言、单文件 / 总量上限与启动现值行;runbook 补「取轨迹」「手动清理」两节(`fly ssh`);测试面 9、10。
4. ~~**刀 4 · 档 2**~~ **挂账**。解冻条件:**线上出现第一条真实的校验失败或修复失败回合**(那时才有值得重跑的原始文本)。
   届时补记 `rawNarrative` / `rawTail` / 修复尾巴,测试面 5。
5. **刀 5 · 首个真实用例**:从线上取一条真实轨迹(Felix 亲手 `fly ssh` 取文件),转成回归测试,作为本 ADR 的实际效果读数。
   > **订正(2026-10-08,上句原文保留)**:按 W-11 拆为两刀 —— **5a** 转换工具 + 夹具回放测试(本仓库内,不接触任何真实轨迹);
   > **5b** Felix 在本机对专门开的局运行工具、人工核对报告后提交第一份夹具。原始文件不经云端会话。

每刀:引擎 / 校验 / golden / prompt lockstep / `schemaVersion`(保 "0.4")零动。

### 实现进度

- **刀 1(2026-10-07,待校勘 / 未合并)**:`TurnTrace` 记录类型 + `TraceSink` 接缝(默认装配 `NOOP`)+ 会话级本回合收集器
  `TurnTraceCollector`;写出点 = `TurnStateMachine` 两处 persist 之后,未落地分支只丢弃收集器;写出 catch `Throwable` + WARN。
  测试面 1、3、4、7、8、12 已加,**另提前加了测试面 11**(prompt 重渲染核对,本节原排在刀 2):`promptSha256` 刀 1 就开始记,
  不在同一刀证实「可重渲染」,这个哈希一落地就是一条没人核对的推测。为此把主调用 prompt 的渲染抽成
  `EventLoopService.renderTurnPrompt`(`execute` 也只经它渲染)。`post` 按 §1.2 存 sha256 **与**各轴落账值。
  默认 profile 下 prompt 与存档 encode 字节与 `51980ca` 逐字节一致(临时对拍 8 组 16 份,工具未入库)。

- **刀 2(2026-10-07,待校勘 / 未合并)**:`TurnTraceCodec`(main,刀 3 同用):一条轨迹 ⇄ 一行 JSON,键序固定、
  可空字段显式写 `null`,解码严格(未知 `schema` / 缺键 / 不认识的键 / 类型不对一律抛)。生产重构:`settle` / `degrade`
  里「拿到 `parsed`(或已流出叙事)之后的落账部分」抽成 `EventLoopService.landSettled` / `landDegraded`(不依赖模型与 sink;
  日志与 `durMs` 终点经钩子留在原位置),`scenePlan` 改包内可见;对拍 11 个场景的存档 encode / prompt / 日志与抽取前逐字节一致。
  测试侧 `TraceReplayer`(`src/test`,不进生产装配):`decode(pre)` → `scenePlan` → 同一份落账方法 → 比 `post` 摘要与各轴;
  结果一致 / 不一致 / 跨版本(差异照列、不算失败)。测试面 2、6 已加(测试面 4 按裁定改写);测试面 11 刀 1 已加。
  - ✅ **已裁定 (b)(校勘,2026-10-07),补刀已实现**:记录点挪到钳制之前;`clampClosingVigorFloor` 挪进 `landSettled`、
    `apply` 之前;测试面 4 按上方订正改写;`TraceScenarios.lifetimeClamp` 纳入测试面 2 场景集。生产行为逐字节不变
    (11 个场景的存档 encode / prompt / sink 事件 / 日志与 `fb2209d` 对拍全等)。**`schema` 不升版(仍 1)**:刀 1 的写出端
    是 `NOOP`,线上还没有任何一条轨迹落地,不存在按旧语义(改写后的 `parsed`)写出的轨迹需要区分。下列为裁定前的原记录:
  - ⚠️ **与 §三 档 1 定义冲突,裁定前:一生制钳制触发的回合回放不一致。** `clampClosingVigorFloor` 在 `apply` **之前**
    往引擎 `issues` 记一条「收束下限钳制 5->15」;`pre` 取在它之前,`parsed` 已是钳制后的值 → 回放 `apply(parsed)` 重现不出
    那条 issue(原始值 5 不在轨迹里),快照摘要对不上(实测:`pre.issues=[]`,线上终态多出该条)。§1.2 把
    `parsedBeforeRewrite 或「改写差异」` 列为「诊断用」、注「钳制本身已进 issues」—— 进的是**本回合的 post**,不是 `pre`。
    该类回合暂不进测试面 2 的场景集(`TraceScenarios.lifetimeClamp` 留着、类注释写明)。可选方向(未选):
    (a) 轨迹补记「改写差异」(钳制写入的 issue),落账入口在 `apply` 前补记它 —— 字段从「诊断用」升「必需」,`schema` 是否升版另议;
    (b) 记 `parsedBeforeRewrite`,落账入口改为从改写前重跑钳制 —— 测试面 4 的变异(「在钳制之前取」)将不再使回放失败,需改写该条;
    (c) 摘要不比 `issues` —— 削弱比对,不推荐。

- **刀 3(2026-10-07,待校勘 / 未合并,未部署)**:`FileTraceSink`(替换默认装配的 `NOOP`;两个 profile 一样装,W-1)。
  - **目录**:`aiuniverse.trace.dir`,默认 `${aiuniverse.session.store-dir}/traces`(`application.yml` 与 `@Value` 缺省一致)——
    本地(`server/` 下启动)解析为 `server/data/traces`;线上 `fly.toml` 的 `AIUNIVERSE_SESSION_STORE_DIR=/data` → `/data/traces`
    (以该 env 起一次 Spring 上下文实测)。同受 web 根之外启动断言(与存档共用一份判定,`FileSessionStore.assertOutsideWebRoot`)。
  - **写法**:每局 `<saveId>.trace.jsonl`,每个已落地回合一行(`TurnTraceCodec` + 换行),每次打开 → 追加 → 关闭;saveId 非 UUID 形拒写
    (与存档同一条正则)。写失败抛,由 `TurnStateMachine` 吞并 WARN(刀 1 口径)。
  - **上限(W-7)**:`max-file-bytes` 默认 5 MB、`max-total-bytes` 默认 200 MB;到限停写,单文件按局、总量全局各只 WARN 一次;
    总量 = 启动时目录下 `*.trace.jsonl`(不递归)之和 + 运行中累加,**手动删文件不回落,需重启**(runbook 写明)。
    启动打一行 `[trace] 目录 = … enabled=… 单文件上限=… 总量上限=… 当前总量=…`。`aiuniverse.trace.enabled=false` → `NOOP`、不建目录。
  - **读取(W-10)**:测试侧 `TraceFileReader`,不以换行结尾的末段视为半行跳过,中间行坏照旧抛。
  - 测试面 9(`loadAll` 不读 `traces/`,变异 `Files.list`→`Files.walk` 单独变红;半截末行)、10(两种上限、各 WARN 一次、回合照常落账与写盘);
    另有启动现值行、开关、默认目录解析、端到端(文件行数 = 落地回合数、逐行回放一致)。变异 11 条各自变红。
    默认行为对拍:11 个场景的存档 encode / prompt / sink 事件,`main@7d46367`、本刀 `NOOP`、本刀接文件落点三者逐字节一致;
    日志只多出启动那一行 `[trace]`。runbook §七「回合轨迹」。
  - **补(2026-10-08,待校勘 / 未合并)· 目录不可用时降级,不阻止启动**:目录不可创建 / 不可统计 → 抛 `TraceDirUnavailableException`,
    `TraceSinkConfig` 只接住这一类型,打一条 ERROR `[trace] 目录不可用,本次进程不写轨迹:…` 并装配 `NOOP`,服务照常启动;
    web 根断言失败仍是 `IllegalStateException`、照旧拒启(两类按异常类型区分,不看消息)。目录不可用时看启动 ERROR 行即可发现。

- **刀 5a(2026-10-08,待校勘 / 未合并,未部署)· 回归夹具转换工具 + 夹具回放测试(W-11;不含任何真实轨迹)**。勘察结论:
  - **档 1 回放读取的字段**(以 `TraceReplayer.replay` 为准):`saveId`(只传给 `SessionDocument.decode`)、`commit`(只用于判一致 / 跨版本)、
    `actionId`、`path`、`pre`、`parsed`(settled)、`streamedNarrative`(degraded)、`post.sha256`、`post.attributes`。
    不读:`schema`(由编解码校验)/ `turnBefore` / `recordedAt` / `degradeReason` / `promptSha256` / `usage`(含 `model` / `reasoningChars`)/ `durMs` / `repairErrors`。
  - **saveId 不进比对**:`SessionDocument.encode` = `toPersistedState()` + `currentActions` + `phaseHint` + `boxScene`,不含 saveId,
    故 `pre` 与 `post` 摘要的计算输入里都没有它;`decode` 把它交给 `GameSession` 与 `BoxSceneState.restoreFor`,后者只用于一条 WARN;
    main 代码里 eventloop / engine 对 `session.saveId()` 的其余使用全是日志。⇒ **替换 saveId 不破坏比对**,夹具里的 `post` 是线上记录的原值、
    不重算(测试 `originalSaveIdDoesNotRemainAndReplayStillMatchesRecordedPost` 钉住)。
  - **其余线上标识**:`recordedAt`(可与 `fly logs` 对时)、`usage` / `model` / `reasoningChars` / `durMs`、`promptSha256`、`repairErrors`
    (含模型原文片段)—— 均非回放所需,**丢弃**;`commit` 是公开仓库的 SHA,保留(来源与分类)。轨迹类型本身没有 IP / 请求头 / deviceId 字段;
    玩家输入只有 `actionId`(服务端闭集,ADR-004 §背景)。⚠️ 已知且接受:`pre` 里的世界文本与线上 `/data/<saveId>.json` 相同,
    能读到线上卷的人仍可凭内容对上那一局 —— 那个人只有 Felix。
  - **替换方案**:saveId → `fixture-<name>`,`name` 由运行工具的人给(`[a-z0-9-]`),**不由原 saveId 派生**(派生值可被拿来反查确认)。
  - **「一致」的判定(本刀定义,⚠️ 待 Felix 确认)**:夹具回放按**差异清单为空**判,不按 `Outcome`。夹具记的是线上那次的 `commit`,
    CI 与本地构建的 commit 不同(本地多为 `unknown`),回放器必然判「跨版本」;而 §置顶 2「跨版本差异不算失败」对**回归夹具**正好反了 ——
    夹具存在的意义就是新代码在旧回合上回放出差异时变红。有意的落账行为变更导致夹具变红 → 重新生成或撤下该夹具,不放宽测试。
    `TraceReplayer` 本身的三态语义不改。
  - **实现(全部在 `src/test`,不进生产装配)**:`TraceFixture`(白名单格式,严格解码)/ `FixtureScanner`(sk- / api-key 字段名 /
    Authorization / Bearer / IPv4 / IPv6 / 邮箱 / 原 saveId 残留;扫将要写出的全文,任一命中即拒绝)/ `TraceFixtureConverter`
    (输入与输出须在仓库之外;逐行档 1 自检,任一行差异不空即拒绝;拒绝时删掉同名旧输出,报告照写)/ `TraceFixtureTool`(命令行入口,
    `exec:java`,命令见 runbook §七)/ `TraceFixtureReplayTest`(扫 `src/test/resources/trace-fixtures/*.jsonl`;目录随 README 入库、
    不存在即失败;为空则通过并打一行提示)。
  - **测试**:`TraceFixtureConverterTest` 26 条(端到端演示 7 个场景:原始 → 转换 → 报告 → 夹具回放无差异;白名单键序独立写死;
    非白名单字段里的密钥 / IP 被丢弃而非拷贝;原 saveId 不残留且 post 为记录值;扫描器植入样本报警 / 普通内容不报;
    白名单字段内的命中、回放不一致、多个 saveId、输入或输出在仓库内 → 拒绝)。报告不回显输入文件名(线上文件名即原 saveId)。变异 9 条各自变红:报告回显输入文件名 / 非白名单字段加进编码 /
    白名单常量与编码一并扩 / 不替换 saveId / 去掉 sk- 形态 / 跳过自检 / 去掉仓库路径闸门 / 落账行为改动(本地放一份演示夹具、
    `Engine` clamp 减 1 → `TraceFixtureReplayTest` 红;演示夹具未入库)/ 夹具目录缺失。

---

## 已知代价

1. **轨迹的保密级别等于存档**,多一份含 `hiddenLogic` 的文件需要同样的边界保护。
2. **B 让卷第一次有一个随流量线性增长、且无人消费时也一直增长的写入者**;不定清理就是在等卷写满。
3. **回放只证明「同版本下结算可复现」**,不证明叙事质量、不证明模型行为;它不是评测。
4. 写出点在名额之内,每回合多一次文件写(量级同 persist)。

## 重新审视的触发条件

- `FileSessionStore.loadAll` 改为递归扫描(或任何模块开始递归扫描存档目录)→ W-3 的「子目录不构成第三个租户」解读失效,回本 ADR 与 ADR-022 §挂账。
- 轨迹上限被触发(WARN 出现)→ 重估 W-7 的上限值与是否需要自动清理。
- 线上切库(ADR-025 刀 4 解冻)→ C 获得线上价值,重估 W-1;同时 ADR-026 已决 G 解冻。
- 回合内出现多次模型调用(任何形式的往返)→ 轨迹条目从「回合」细化到「调用」。
- 卷用量或启动载入时间出现可观察的增长 → 重估保留期。

## 跟其他文档的交叉引用

- [层 3.2 勘察底稿](../tool-calling-survey.md) 候选 γ、O-7、O-8、问题 8 / 9 / 12。
- ADR-015(持久化边界、restore 往返)/ ADR-016(重算注记)/ ADR-022(§挂账「落盘目录分家」)/ ADR-025(回看)/
  ADR-026(已决 G)/ ADR-027(已落地必写盘的写出点)/ ADR-028(纸箱局面)。
- README ADR 列表与 ROADMAP §五 索引已于采纳时(2026-10-07)补入。
