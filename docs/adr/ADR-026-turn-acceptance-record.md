# ADR-026 · 回合受理记录:把「接下了」与「落地了」拆成两次提交(`pg` profile)

- **日期**:2026-09-28
- **状态**:**已采纳**(2026-09-28,Felix 定)。原「待决」各条(C、D、E、E′、F、G)改写为「已决」,每条带理由,见下。
  ⚠️ 草稿期原状态为「提议,未采纳」—— 决策正文(1–7)在草稿里写成的样子**未改写**,凡被已决改变的地方以加注标出。
- **实现进度**(活状态格):未起。**开工前置:[ADR-027](ADR-027-delivery-failure-keeps-turn-and-bidirectional-cursor.md) 的实现合并之后**(见实现分刀)。
- **决策者**:Felix

## 名字先说清

不叫「加 `turn_request` 表」—— 表是手段。叫「回合受理记录」是因为本 ADR 真正新增的东西只有一样:
**服务端决定跑一个回合的那一刻,在库里留下一行**,并在回合落地时与快照**同一事务**把它关掉。
⚠️ 刻意**不**叫「回合幂等」:下文背景第 4 条与决策 3 会写清,在单实例下这一刀**不新拦下任何一次重复提交**
—— 那个名字会让读者以为幂等是这一刀做成的。

## 背景

### 这一刀在求职线上的位置

[求职线层 2](../backlog-career-track.md) 立的两段式原文:① 建 `turn_request(PROCESSING)` + 校验 `version` → 提交;
② 事务外调 LLM;③ 再校验 `version` → 写 `game_event` + 快照/`version` + `llm_call` + 标 `SUCCEEDED` → 提交;失败另一段短事务标 `FAILED`。
[ADR-025](ADR-025-narrative-history.md) 只落了 ③ 的一部分,并自认「真子集」。本 ADR 补 ① 与 ③ 的其余部分,
**同时**逐条核对那份原文里哪些零件在今天的系统里真的有东西可拦 —— 答案见决策 3 / 5 / 6。

**驱动写准(同 ADR-024)**:今天线上没有切库(ADR-025 刀 4 挂账,方案 D),本 ADR **只在 `pg` profile 下生效**,
故**对线上玩家的收益是零**。值得做的理由是「为什么不能在远程模型调用期间持有事务」「中断的回合怎么在重启后被认出来」
这两件事可讲、可测;以及它是层 3(确定性事件推进 → Tool Calling + Trace)依赖链上的一环。
**一个动机被写大的 ADR,在它唯一的消费场景(面试)里是负资产。**

### 待核六条(简报里的说法,逐条对源码判定)

| # | 说法 | 判定 | 出处 / 订正 |
|---|---|---|---|
| 1 | 客户端发回合没有请求 id,只有 turn 游标 + actionId | **成立** | `web/src/api/h5GameApi.ts` `openTurnStream` body = `{ turn, actionId }`;唯一额外头是 `X-Device-Id`(`deviceId.ts`,软闸键,不是请求 id);`GameController.TurnRequest(int turn, String actionId)` |
| 2 | 「跨重启」问题 = 回合在内存完成而 persist 没跑到(或进程死在 persist 前)→ 重启后盘上 N,客户端带 N 重试被放行、再生成一次(重掷) | **部分成立** | 机制对(ADR-023 已知代价 5 原话)。两处订正:① **SSE 断在叙事流中途时,回合根本没「在内存完成」** —— `SseTurnEventSink.send` 抛的 `IllegalStateException` 从 `onToken` 回调穿出 `OpenAiStreamDecoder`(它只接 `IOException`)与 `OpenAiCompatLlmClient`,也穿出 `EventLoopService.execute`(它只接 `LlmException`),`engine.apply` **没跑**,内存与盘都是 N;此时重试不是「重掷」,是「重试一个玩家没见过结尾的回合」。② **漏了一个方向:客户端超前**。`settle()` 先 `sink.delta` 后才轮到 `store.persist`;delta 已送达而 persist 失败 / 进程死在其后 → 重启后服务端 N、客户端 N+1。控制器只拒 `req.turn < engine.turn()`(`aheadTurnIsNotTreatedAsStale` 用例钉住「超前放行」),而选项 id 跨回合稳定为 A–D → **用 N+1 那组选项的字母,去 N 那组选项的文字里重跑一次**。ADR-023 正文没有讨论超前方向 |
| 3 | 「内存 N+1 / 盘 N」来自 SSE 写失败时 catch 跳过 persist,和存储介质无关 | **部分成立** | ① SSE 那条路径只在**写 `delta` / `ending` 时**失败才产生 N+1 / N(`settle` 里 `engine.apply` 在 `sink.delta` 之前;`degrade` 里 `applyNoOp` 在 `sink.delta` 之前);叙事流中途失败见第 2 条 ①,不产生偏差。② **persist 自己失败也产生同一偏差**(`FileSessionStore` / `JdbcSessionStore` 都 best-effort 吞异常)—— 这一条**和介质有关**:`pg` 下 DB 断连会让每个回合都 persist 失败,偏差按回合累积(且 ADR-025 代价 10:health 看不见)。「与介质无关」只对 SSE 那半成立 |
| 4 | 单实例 + 忙态 CAS 已保证每 saveId 一个写者;单实例下 version 乐观锁拦不到真实冲突 | **成立** | 写者只有两处:`GameSessionManager.create`(新 saveId,无竞争)与 `TurnStateMachine.submitAction` 临界区尾部(CAS 之内,javadoc「忙态守卫保证每 saveId 单写者」);单实例 = ADR-015 硬约束①,`fly.toml` 挂卷 `wanjie_data`(卷只挂一台,部署不重叠)。唯一可想见的第二写者是 ADR-025 刀 4 的导入工具,而它按设计只在切换窗口内跑 |
| 5 | 没有任何地方持久化每次模型调用的耗时 / token / 费用,只在日志里 | **部分成立** | **逐次**确实只在日志(`EventLoopService.logUsage`、`durMs` 两处 log)。但**月累计 ¥**是持久化的:`QuotaService.persistMonth` 写 `quota-YYYY-MM.json`(ADR-016)。另撞见一条(本 ADR 不修,报):**被中止的调用不入账** —— 流中断(`LlmException` → `degrade`)与 SSE 断(`IllegalStateException` 穿出)两条路都走不到 `logUsage` → `quota.record`,已消耗的 token 不进 ¥ 账 |
| 6 | `pg` profile 从未在线上启用 | **成立** | `fly.toml` `[env]` 无 `SPRING_PROFILES_ACTIVE`;ADR-025 实现进度格记 2026-09-28 部署 `27a7b14` 启动回载走 `FileSessionStore`、无任何 pg / Flyway 日志、历史接口 501 |

### 由此得出的一句话

在「单实例、内存为权威」下,重复提交已经由两道**进程内**闸挡住:同回合并发 → CAS(`busy`);落后游标 → ADR-023(`turn_stale`)。
跨重启时,只要「回合落地」与「快照写入」在同一事务(本 ADR 决策 2),重启后的快照就是真相,游标比对照样成立。
**剩下没人管的只有两件事**:① 一个被接下、却没落地的回合,重启后**不留任何痕迹**(ADR-015 代价 1 的回滚是静默的);
② 客户端超前(第 2 条 ②)。本 ADR 解决 ①;② 的正解在默认 profile 的控制器里,列为待决。
⚠️ 已决 E′:② 由 [ADR-027](ADR-027-delivery-failure-keeps-turn-and-bidirectional-cursor.md) 治。

## 决策

> 下列 1–7 为草稿期原文(「提议」),采纳时未改写;被「已决」改变之处以 ⚠️ 加注标出。

### 1. 受理(第一段短事务)—— 在 CAS 成功之后、调模型之前

新增接缝 `TurnLedger`(名字待实现刀定),两个实现:`NOOP`(默认 profile,方法体为空)与 `pg` 实现。
`TurnStateMachine.submitAction` 在 **CAS 成功之后、`executor.execute` 之前**调 `ledger.accept(session, actionId)`:
一段短事务 `INSERT turn_request(save_id, base_turn, target_turn, action_id, status='PROCESSING', accepted_at)`,
返回的行 id 挂在 `GameSession` 上(同 ADR-025 `openingNarrative` 的形态:不进 Engine、不进快照)。

- **为什么在 CAS 之后**:CAS 之前写会出现「行已受理、却被 busy 拒掉」的孤行;CAS 之后,这一局此刻只有本线程在写。
- **为什么不在配额守卫之前**:配额拒绝相位零触碰(ADR-016),受理记录也不该有。
- **模型流式调用不在任何事务里**(硬约束):第一段事务在调模型之前提交。

### 2. 落地(第三段)并入 ADR-025 那一次 persist 事务

`JdbcSessionStore.persist` 在同一事务内追加:若会话挂着受理行、且 `engine.turn() == target_turn`,
`UPDATE turn_request SET status=?, finished_at=now()`(`SUCCEEDED`;降级回合为 `DEGRADED`,见下)。
**「落地」的定义 = 快照写到 `target_turn` 的那一次提交**。于是:库里 `SUCCEEDED` ⇒ 快照 ≥ `target_turn`,两者**不可能一新一旧**。
⚠️ 只有单向:反过来不成立 —— 一行 `FAILED` / `PROCESSING` 的回合,可能在内存里其实落了地、随**后续**某次成功 persist 被快照覆盖(「内存 N+1 / 盘 N」那条路径)。表记的是「**那一次**有没有确认落地」,不是「这一回合最终存不存在」。

降级(`degrade` → `applyNoOp`,turn 照样 +1)算落地,标 `DEGRADED` 与 `SUCCEEDED` 区分 ——
它是 ADR-024 立字 7 的「第四条降级路径」的持久读数,而今天降级只在 WARN 日志里。

### 3. 幂等键 = `(save_id, base_turn)`,不引入客户端请求 id

部分唯一索引:`UNIQUE (save_id, base_turn) WHERE status IN ('PROCESSING','SUCCEEDED','DEGRADED')`。
**它在单实例下不新拦下任何一次提交**(待核第 4 条):同一 `base_turn` 的第二个 PROCESSING 已被 CAS 挡在进程内;
`base_turn` 已落地则快照已前进,游标比对先拒。**它是把进程内两道闸的不变式在库里再陈述一遍** ——
在单实例下是**防御性断言**(实现有 bug 时会以唯一键冲突现形),在多实例下才是真正的闸。
测试须用人为构造的双写证明它会拦(见测试面),并在注释里写死「生产路径上今天没有东西会触发它」。

否决客户端请求 id,理由见否决项。

### 4. 中断:重启时收口 `PROCESSING`

`pg` 实现在启动回载时(`loadAll` 之后)把所有 `PROCESSING` 行一次性标 `INTERRUPTED`。
依据:落地与快照同事务(决策 2),故**重启后仍是 `PROCESSING` 的行,其回合在受理那次没有被确认落地**。
⚠️ 措辞刻意是「没被确认」而不是「必然丢了」:`PROCESSING` 在进程内也会残留(persist 被吞掉的失败、或 `ledger.failed` 自己失败,都是 best-effort),
而那一回合可能已随后续快照落地。`INTERRUPTED` 的准确含义 = 「受理之后,本进程没来得及写下结论」。
`INTERRUPTED`(重启时才发现)与 `FAILED`(进程内看见了异常)**分开记**:前者是「我们当时不知道」,后者是「我们当时知道」。

进程内异常路径(`TurnStateMachine` 的 `catch (RuntimeException)`)调 `ledger.failed(session)`:另一段短事务标 `FAILED`,best-effort 不抛。
⚠️ 注意这条 catch 同时接住「叙事流中途 SSE 断」(回合未落地,标 FAILED 正确)与「delta 写失败」(回合已在内存落地、只是没 persist)。
后者标 FAILED **是准确的** —— 库里的判据是「快照有没有写到 target」,而它没写到;内存里那一回合在下一次 persist 前崩溃就真的没了。

玩家侧:**默认不变**(续局回到盘上那一回合,ADR-015 代价 1)。是否告诉玩家「上一回合被中断」列为待决 D。
⚠️ **已决 D(2026-09-28):静默**,见下。

### 5. `version` 乐观锁:不建

V1 迁移注释写「乐观锁要在 turn_request 那一刀、两段式成立之后才有意义」—— **本 ADR 订正这句**:
两段式成立**并不**让乐观锁有东西可拦;有东西可拦的前提是**第二个写者**(多实例,或进程外工具与在线进程同时写),
而今天两者都不存在(待核第 4 条)。建一个每次都校验通过的列,效果是让读者以为并发被它保护着 ——
**实际保护它的是 CAS**。故不建;求职线层 2 那条「乐观锁」改为指向本条的理由(见对外口径)。
若 Felix 选择为了可讲而建,见待决 F 的写法约束。
⚠️ **已决 F(2026-09-28):不建**,见下。

### 6. `llm_call`:不进本 ADR 的必做范围

逐次模型调用记录与 `turn_request` 是两件事:前者是可观测性,后者是回合生命周期。混在一刀里,
可观测性那一半会借「两段式」的名义进来,而它**只在 `pg` 下成立、线上未切库时不解决任何线上问题** ——
工程债「日志是瞬时的」「上游硬失败不可见」两条挂账的病灶都在**线上**,本 ADR 在线上是零。
放在最后一刀、可单独砍掉,还是另起 ADR,列为待决 G。
⚠️ **已决 G(2026-09-28):不做,直到 ADR-025 刀 4 解冻**,见下。

### 7. `TurnStateMachine` 要动(点名)

ADR-025 的写入点「零动 CAS 临界区」在本 ADR 不再成立。动三处,均在临界区内:
① CAS 成功后一行 `ledger.accept(...)`;② `catch (RuntimeException)` 里一行 `ledger.failed(...)`;
③ 构造器多注入一个 `TurnLedger`(带 `NOOP` 的重载,既有测试调用点零改,同 `QuotaGate.NOOP` 的接缝形态)。
**CAS 本身、相位转换、`store.persist` 的位置一字不动。** 默认 profile 下 `NOOP` 两个方法体为空 → 行为逐字节不变。

⚠️ **代价**:第一段事务是一次 DB 往返,落在**准入名额之内、调模型之前** —— 名额占用时长 + 1 次往返;
DB 挂起时由 ADR-025 决策 3 的超时约束(事务 2 s / 取消请求 1 s,实测挂死 ≈3 s)。

## 否决项

- **客户端生成的请求 id**:它能区分「同一次点击的重试」与「同一 base_turn 上的新点击」。但只有在服务端**存下结果并重放**时,
  这个区分才有用处,而重放已被 ADR-023 否决(方案 A);不重放时,两者得到的处理完全一样。
  代价是改 `api/` 层契约与 `gameStore`(ADR-003 边界内,但不是零)。**解冻条件**:出现重放需求,或多实例。
- **把「受理」放在 `GameController`(容器线程)**:那会在准入之前写库,被准入拒掉的请求留下孤行;也把 DB 往返放进零名额路径。
- **受理行在第三段才插入(一段事务搞定)**:那就回到 ADR-025 的子集 —— 重启后没有任何东西能说明「曾经接下过一个回合」,本 ADR 就没有存在的理由。
- **重启时逐行比对快照再判中断**:比对能把「其实后来落了地」的残留行改判,但那需要给每行一个「确切落在哪个快照里」的定义,
  而内存偏差路径下没有这个定义(决策 2 的单向性)。收益是一个更漂亮的计数,代价是一段每次启动都跑的推断 —— 不值;记的是事实(没确认),不是推断。
- **本刀顺带治「内存 N+1 / 盘 N」**:见决策外的挂账与待决 E —— 正解会改默认 profile 的回合路径,违反本 ADR 硬约束。

## 已知代价(不美化)

1. **对线上零收益**(`pg` 未启用)。本地 / CI 有受理记录;线上没有。
2. **单实例下幂等键与唯一索引不拦任何真实提交**(决策 3)—— 它们是断言,不是闸。
3. **回合路径多一次 DB 往返**,在名额之内;DB 挂起时单回合最坏多 ≈3 s(ADR-025 实测)。
4. **`TurnStateMachine` 首次为持久化再开接缝**:临界区里又多两处外部调用。ADR-022 立字 5 禁的是「必须永不失败的写」——
   这两处是 best-effort,失败只丢记录不影响回合,**不构成**那类写;但它们必须保持 best-effort,任何人把它们改成「失败即拒绝」都会撞上立字 5 与待决 C。
5. **受理失败时的记录空洞**:已决 C 采用「照跑」(草稿原文:若采用待决 C 的「照跑」),第一段事务失败的回合没有受理行;落地时无行可关 → 历史里有该回合的事件、没有受理记录。读这张表数「接下了多少回合」时须知道它是**下界**。
6. **客户端超前**(待核第 2 条 ②)**本 ADR 不治**,且 `pg` 下更可能出现(DB 断连 → persist 连败 → 重启回滚多回合,而 health 看不见)。
   ⚠️ 已决 E′:由 [ADR-027](ADR-027-delivery-failure-keeps-turn-and-bidirectional-cursor.md) 治,且先于本 ADR 的实现落地。
7. **被中止的调用不入 ¥ 账**(待核第 5 条附带)是既有缺口,本 ADR 不修;`llm_call` 若做,是唯一会把它变可见的地方(仍只在 `pg`)。
   ⚠️ 已决 G 之后 `llm_call` 不做;该缺口另立工程债挂账「成本闸门少算被中止的调用」(2026-09-28)。
8. **`INTERRUPTED` 收口依赖启动**:进程不重启,就不会有 `INTERRUPTED`;进程内卡死(ADR-024 挂账那种彻底静默)留下的 `PROCESSING` 行会一直是 `PROCESSING`,直到重启。

## 挂账(本 ADR 不做,记着)

### 「内存 N+1 / 盘 N」—— 本刀不治,且订正一句旧口径

[CONTEXT §三.17](../CONTEXT.md) 写「解冻绑数据库那一刀」,ADR-025 订正为「应读作绑 `turn_request` 那一刀」。
**本 ADR 就是 `turn_request` 那一刀,而它也不治**:偏差来自 `persist` 在异常路径上没跑,两段式不改变这一点。
正解有两个形状,都改**默认 profile** 的回合路径(违反本 ADR 硬约束):
(a) 让 `SseTurnEventSink` 在 `delta` / `ending` 写失败时不抛、只标记,使 `execute` 正常返回、`persist` 照跑
(叙事流中途仍抛 —— 那是省 token 的中止);(b) 在 catch 里区分「已落地未送达」与「未落地」后补一次 persist。
⚠️ 两者都不需要数据库。**故 ADR-025 那句订正本身也不准确**:解冻条件应读作「**允许改默认 profile 回合路径的那一刀**」。
CONTEXT 与 ADR-025 本刀不动(范围外),由本 ADR 采纳后的收口刀订正,订正块保留原文。

⚠️ **已决 E(2026-09-28)**:修,但**不在本 ADR** —— 另立 [ADR-027](ADR-027-delivery-failure-keeps-turn-and-bidirectional-cursor.md),先于本 ADR 的实现落地。
CONTEXT §三.17 的订正随 ADR-027 实现收口那一刀做(订正块保留原文),本 ADR 采纳这一刀不动 CONTEXT。

## 已决(2026-09-28,Felix 定)

> 草稿期本节标题为「待决(列选项,不替 Felix 定)」,各条选项与代价的原文见 commit `3862e3f`;
> 此处只写裁定与理由。

- **C · 受理写库失败时 → (a) 照跑。** 与 ADR-025 决策 3 同口径(DB 是增强不是闸门)。
  选 (b) 等于 DB 故障时全站不能推进回合,而 health 看不见(ADR-025 代价 10),还会让 `ledger.accept` 变成 ADR-022 立字 5 禁的「必须永不失败的写」。
  代价照记:受理记录是下界(已知代价 5)。
- **D · 重启后玩家侧 → (a) 静默。** `INTERRUPTED` 只在 `pg` 下有数据,而线上未切库(ADR-025 刀 4 挂账);
  为它改 `/state` 契约与前端,线上收益为零。
- **E · 「内存 N+1 / 盘 N」→ 修,但不在本 ADR。** 另立 [ADR-027](ADR-027-delivery-failure-keeps-turn-and-bidirectional-cursor.md),
  **先于本 ADR 的实现落地**。理由:正解改的是默认 profile 的回合路径,且不需要数据库 —— 与本 ADR「只在 `pg`、默认 profile 逐字节不变」的硬约束正交,混进来会让两件事无法各自回滚。
- **E′ · 客户端超前 → 修,并入 ADR-027。** 与 E 同一条因果链(落盘没成 → 重启回滚 → 客户端超前),同一个 ADR 讲清。
- **F · 乐观锁 → (a) 不建。** 单实例下它拦不到任何冲突,单写者由 CAS 保证(待核第 4 条);多实例时才建。对外口径补一句(见下)。
- **G · `llm_call` → (c) 不做,直到 ADR-025 刀 4 解冻(线上切库)。** 它的价值在线上可观测性,线上没库之前为零。
  它不解冻「日志是瞬时的」「上游硬失败不可见」两条挂账(病灶在线上)这一句原判不变。
  被中止的调用不入 ¥ 账那条缺口不再挂在它身上,另立工程债挂账。

## 测试面(写进 ADR,本刀不实现)

- 默认 profile:`NOOP` 装配;既有回合测试一字不改全绿;源码级断言 `TurnStateMachine` 的 CAS / `persist` / 相位转换行未变(以 diff 为准,只新增两行调用)。
- `pg`(Testcontainers,同 ADR-025):① 正常回合 → 一行 `SUCCEEDED` 且 `target_turn` = 快照 turn;② 降级 → `DEGRADED`;
  ③ executor 抛 → `FAILED`,快照未前进;④ 受理后、落地前模拟进程终止(不调 persist,新建 store 回载)→ 启动收口为 `INTERRUPTED`;
  ⑤ 落地事务失败(注入)→ 行仍 `PROCESSING`、快照未前进 —— 两者一致;⑥ 唯一索引:人为插入同 `(save_id, base_turn)` 的第二个 PROCESSING → 唯一键冲突(证明断言会响),注释写明生产路径不触发;
  ⑦ 受理事务失败(已决 C = a)→ 回合照跑、落地时无行可关不报错。
- 变异:摘掉决策 2 的条件更新 → ① 红;摘掉启动收口 → ④ 红;把 `accept` 挪到 CAS 之前 → 需有一条「busy 拒绝不留受理行」的用例红。
- 往返守护:快照字节不因本 ADR 改变(ADR-025 代价 1 的守护照跑)。

## 实现分刀(每刀独立可验、可回滚;均只在 `pg` 下生效)

⚠️ **开工前置(2026-09-28 已决)**:在 [ADR-027](ADR-027-delivery-failure-keeps-turn-and-bidirectional-cursor.md) 的实现合并之后开工。
采纳后只保留刀 1、刀 2;刀 3 / 刀 4 按已决 G / D·E·E′ 划掉(加注,原文保留)。

1. **V2 迁移 + `TurnLedger` 接缝 + `NOOP` 装配**:表与部分唯一索引;`TurnStateMachine` 两行调用 + 构造重载。默认 profile 逐字节不变。
2. **`pg` 实现**:受理 / 失败两段短事务;`JdbcSessionStore.persist` 事务内的落地更新;启动收口。
3. ~~**(待决 G 选 a 时)`llm_call`**。~~ ⚠️ 划掉:已决 G = (c),不做,直到 ADR-025 刀 4 解冻。
4. ~~**(待决 D / E / E′ 若选改动项)各自单独一刀**,不与 1–3 混。~~ ⚠️ 划掉:D 已决静默(无改动);E / E′ 移到 ADR-027。

不部署、不切库(ADR-025 刀 4 挂账不变)。

## 重新审视触发条件

- 出现多实例需求 → 决策 3 的唯一索引从断言变成真正的闸,已决 F(不建乐观锁)重估;请求 id 否决项重估。
- ADR-025 刀 4 解冻(线上切库)→ 代价 1 失效;已决 D、G 需重新定(G 的解冻条件就是这一条)。
- 出现重放需求 → 客户端请求 id 否决项重估。
- ~~线上观察到客户端超前 → 待决 E′ 升级。~~ ⚠️ 已决 E′ 已移交 ADR-027,本条随之失效。

## 对外口径(同 ADR-025 格式)

**可以说**(采纳并实现之后):

> 设计了回合受理的两段短事务:受理在调模型之前单独提交,落地与快照同一事务提交,模型流式调用不在任何事务里;
> 重启时把未落地的受理记录收口为「中断」,使静默回滚第一次留下痕迹。

> 评估过乐观锁:单实例下它拦不到任何冲突,单写者由 CAS 保证;多实例时才建。(已决 F,2026-09-28)

**不许说**:「做了幂等」「乐观锁防并发」「线上有回合受理记录」。
单实例下重复提交由进程内 CAS 与游标比对挡住,受理表的唯一索引只是把同一不变式在库里再写一遍;`pg` 线上未启用。

**必须能答**:
- 为什么不能在远程模型调用期间持有事务(连接占用、锁持有、回滚成本、断流后状态);
- 为什么不建乐观锁(没有第二个写者,建了每次都通过,读者会误以为是它在保护并发);
- 为什么不用客户端请求 id(不重放,就区分不出任何处理差异);
- 这张表在单实例下到底拦下了什么(什么也没拦下,它记下了什么)。

## 交叉引用

- [ADR-015](ADR-015-overseas-deployment-form-factor.md)(已知代价 1 崩溃回滚、4 单副本、5 best-effort)
- [ADR-016](ADR-016-cost-gate.md)(配额守卫相位零触碰;¥ 账)
- [ADR-022](ADR-022-turn-admission-and-rejection-semantics.md)(立字 5「必须永不失败的写」;名额占用)
- [ADR-023](ADR-023-turn-cursor-idempotency.md)(同进程内有效;方案 C;超前方向未讨论)
- [ADR-024](ADR-024-stream-segment-deadline.md)(第四条降级路径;彻底静默挂账)
- [ADR-025](ADR-025-narrative-history.md)(子集一节、决策 3 超时、代价 9 / 10、刀 4 挂账)
- [CONTEXT §三.17](../CONTEXT.md)、[求职线层 2](../backlog-career-track.md)、[工程债 §3.3 与两条挂账](../backlog-engineering-debt.md)
