# ADR-025 · 叙事历史:让「回看这一局」成为一个能力,PostgreSQL 只是它的存储

- **日期**:2026-09-26
- **状态**:**提议(草稿)** —— 待 Felix 过目;点头之前不写一行实现
- **决策者**:Felix

## 背景

### 名字先说清:这不是「迁 PostgreSQL」

交付物是**「回看这一局」**:玩家(或 Felix)能按回合顺序读回一局里发生过的全部叙事。
PostgreSQL 是承载它的存储手段,不是目标。

⚠️ 名字这样定是有意的:以「迁 PG」为题,这一刀的完成判据会滑成「数据进库了」,
而**一张写满了却没有任何读路径的事件表,对玩家与对求职线都等于没有**。故读路径(只读 API + 最小只读列表)
**包含在本 ADR 的范围内**,不是「以后再说」。

### 今天为什么回看不了(读源,不读描述)

一局的叙事**在结构上就不存在**,不是「没做读接口」:

- `Engine.LOG_KEEP = 4`(`Engine.java:39`);每回合 `apply` / `applyNoOp` 追加一条 `{turn, narrative, playerAction}`
  后,超出 4 条即 `compressLog()`(`Engine.java:421-436`)—— 把旧条目折成 `[T{turn}选{action}]` 串进 `logSummary`,
  **叙事原文被丢弃,不是被归档**。
- `toPersistedState()`(`Engine.java:223` 起,log 写出在 `:240-246`)只写**内存里还剩的那 ≤4 条**;
  ADR-015 的落盘因此也只有 ≤4 条。
- 前端续局只用**最后一条** log 补散文(`gameStore.ts:253` 与 `:342`),`GameState.log`(`schema.ts:68`)在类型上
  是数组,但服务端从来只给它 ≤4 条。

ADR-004 在找语料时已立过同一个字:**落盘存档不是叙事语料库**。本 ADR 是那句话的另一面 —— 要回看,
就需要一个**生来就不截断**的地方。

### 这一刀在求职线上的位置

[求职线层 2](../backlog-career-track.md)(PostgreSQL + Flyway + 乐观锁 + 回合幂等)与
[工程债 §3.3](../backlog-engineering-debt.md)(形态清单,正文以它为准)。本刀是该层的**第一刀,且只是其中一部分**
(见下文「本刀是完整形态的子集」)。**驱动写准**:与 ADR-024 同调 —— 今天没有任何玩家因为回看不了而受伤,
值得做的理由是「一个会话历史怎么落库、事务边界划在哪」本身可讲;**别把它包装成工程必需**。

## 决策

### 1. 交付物 = 回看这一局;读路径在范围内

- **写**:每个完成的回合产出一条 `game_event`(append-only)。
- **读**:一个只读 API(形如 `GET /api/game/{saveId}/history`,路径实现刀定)按回合升序返回事件;
  一个**最小只读前端列表**,**无皮肤**(ADR-017 视觉宪法一字不动,不进任何世界的主题注册表)。
- **消毒边界**(守 CONTEXT §三.9 三视图):`game_event` **只存玩家本来就看过的东西** —— `turn` / `narrative` /
  `playerAction`(与 `log` 条目同形);**读 API 只读 `game_event`,永不读 `game_session.snapshot`**
  (后者是视图 1 全量,含 `isTrue` / `hiddenLogic`)。鉴权形态与 `GET /state` 相同(持 saveId 即可读),不新增也不削弱。

### 2. 内存仍是权威;DB 只替换文件层

`TurnStateMachine`、忙态 CAS、`Engine`、`compressLog` **一概不动**。DB 以 `SessionStore` 的第二个实现出现
(接缝本就在:`SessionStore` 接口 + `FileSessionStore` + `NOOP`),由 profile 二选一装配,**不双写**。

**写入点 = 现有两个 `persist` 调用点,不新增调用点**:

| 调用点 | 何时 | DB 实现做什么 |
|---|---|---|
| `GameSessionManager.create`(`:73`) | init 播种后 | 在一段短事务里 upsert `game_session` 快照;此时 log 为空 → 零条 event |
| `TurnStateMachine.submitAction`(`:91`) | executor 返回后、相位放回前 | **同一段短事务内**:upsert `game_session` 快照 + append 插入本回合 `game_event` |

⚠️ brief 只点了 `:91`;勘察确认 `create` 也调 `persist`。两处共用同一个接口方法,DB 实现自然同时接住,
**不需要新调用点**,但 init 那一次是「只有快照、没有事件」的形态,实现刀须覆盖它。

**「LLM 不许进事务」在本刀天然成立**:`:91` 位于 `executor.execute(...)` 返回**之后**,模型流早已结束;
事务只包「写快照 + 写事件」两条 SQL。**这不是本刀做到了什么,而是写入点选在这里的结构性后果** —— 不许在任何
表述里把它写成「本刀实现了两段式事务边界」(见下一节)。

**事件从哪来(不改 `compressLog` 的前提下拿到完整历史)**:`persist` 时内存 `engine.log()` 恰好含本回合
那一条(`apply`/`applyNoOp` 先 `log.add` 再折叠,新条目必在尾部)。DB 实现**不取「最后一条」,而取
「内存 log 中 `turn` 大于该 saveId 已落库最大 `turn` 的全部条目」**,`(save_id, turn)` 唯一。
理由在代价节「原子性」一条:它让上一次 persist 被跳过或失败的回合,**只要仍在 4 条窗口内**就能在下一次成功
persist 时补上。

### 3. `persist` best-effort 绝不抛 —— 保留(ADR-015 已知代价 5)

DB 故障不杀活回合:事务失败 → 回滚 → 记 ERROR → 返回;局面继续活在内存,相位照常放回。
**与文件实现同一口径,不因为换了存储就变成关键路径。**

⚠️ **「不抛」不等于「不阻塞」,这条要新写死**:`persist` 跑在准入名额之内的池线程上(ADR-022),名额在它返回后
才还。DB 挂起(连接池取不到连接、语句卡住)时,**它不会抛,它会等** —— 等到超时之前名额一直被占着,
是 [ADR-022 已知代价 3](ADR-022-turn-admission-and-rejection-semantics.md) 那一族(名额被长期占用)的**新入口**。
故连接获取超时与语句超时**必须显式配置、且上界短于一个回合的量级**(具体数值实现刀定,依据须引
[ADR-024](ADR-024-stream-segment-deadline.md) 那份读数;**不许吃驱动默认值**)。
顺带:ADR-024 已记 `durMs` 系统性偏小、**不含 `store.persist()`**;换成网络 DB 后这段未计量的部分**变大了**,
那条取证边界的偏差方向不变、幅度变大,实现刀须在那边加一行指针。

### 4. `compressLog` 不许碰

`logSummary` 同时是三样东西的输入,改任何一处都是连锁:

- 喂 prompt(`snapshot()`,`Engine.java:394-399` 一带,视图 2);
- **退出判定**:`EventLoopService.exitAlreadyPressed`(`:410`)在折叠后的旧回合里靠 `logSummary` 子串找 `选X`;
- **golden parity**:`EngineGoldenTest:53` 逐字断言 `logSummary`。

**叙事历史的完整来源是事件表,不是把内存 log 改成不截断。**

### 5. 本地默认仍是文件;DB 走独立 profile;CI 用 Testcontainers 真 PG

- 默认 profile(本地 `mvn test` / 本地跑 / 现线上)**仍是 `FileSessionStore`**,行为逐字节不变。
- DB 实现只在独立 profile(名字实现刀定,下文暂称 `pg`)下装配。
- schema 迁移走 **Flyway**(求职线层 2 所列形态);Flyway 同样只在 `pg` profile 下启用。
- DB 相关测试用 **Testcontainers 起真 PostgreSQL**。

### 6. 本刀表范围 = `game_session` + `game_event`

- `game_session`:`save_id` 主键 + 快照(视图 1 全量 + `currentActions` / `phaseHint`,即今天
  `FileSessionStore` 写的那份文档)+ `turn` + `status` + 时间戳。
  ⚠️ **求职线清单里 `game_session` 带 `version`;本刀可以建这一列,但不读它做任何判定** —— 乐观锁要在
  `turn_request` 那一刀、两段式成立之后才有意义;今天单写者由忙态 CAS 保证(ADR-015 勘察 2)。
  建不建这一列实现刀定;**若建,注释须写明「本刀不读」**,免得后来者以为乐观锁已经在了。
- `game_event`:`(save_id, turn)` 唯一 + `narrative` + `player_action` + 写入时间。
- **`turn_request` / `llm_call` 不在本刀。**

## ⚠️ 本刀是完整形态的子集,不是替代 —— 张力正面回应

求职线层 2 立的是**两段短事务**:

1. 建 `turn_request(PROCESSING)` + 校验 session `version` → 提交;
2. 事务外调 LLM、收流、校验;
3. 再校验 `version` → 写 `game_event` + 更新快照/`version` + 写 `llm_call` + 标 `SUCCEEDED` → 提交。
   失败走另一段短事务标 `FAILED`。

**本刀只落其中第 3 段的一部分**(写 `game_event` + 更新快照),而且:

- **没有第 1 段**:没有 `turn_request`,没有「受理即落库」的那一刻;
- **没有 `version` 校验**:写入不以版本号为条件;
- **没有 `llm_call`、没有 `FAILED` 标记**。

故这是完整形态的**真子集**。两段式的意义在于「受理」与「完成」之间留一道可被重启跨越的持久记录 ——
**那道记录只有 `turn_request` 那一刀才会出现,两段式在那一刀才成立**。

⚠️ **本刀之后,跨重启幂等仍然没做**。ADR-023 的边界原话照引:

> **同进程内有效,进程外无效**:重启后内存表由 `reloadFromStore` 回载,`engine.turn()` 取自盘上那份
> (路径 (a) 下就是 N)——此时客户端的 N 与服务端的 N **相等**,比较放行,回合会被推进。
> ⚠️ **这不是 bug,是本刀边界的诚实位置**:那正是方案 C(跨重启幂等键)存在的理由。

换成 DB 之后这段话**一个字都不用改**:回载仍是从快照回载,快照仍只在 persist 成功时写,游标比对仍只在进程内。
**不许把本刀写成或暗示「事务边界已落地」「幂等做完了」「乐观锁有了」** —— 面试里、README 里、ROADMAP 里都不许。

### ⚠️ 顺带:「内存 N+1 / 盘 N」本刀也不治

[CONTEXT §三.17](../CONTEXT.md) 第五条的触发器补充写着「内存 N+1 / 盘 N 这一偏差本身仍未治,**解冻绑数据库那一刀**」。
本刀是数据库刀,**但它不治这个偏差**,理由是结构性的:偏差来自 **persist 的位置**(SSE 写抛出 →
`TurnStateMachine` 的 catch 接住 → `store.persist` 在那之前一行、根本没跑到),**不来自存储介质**。
换成 DB,这一行照样跑不到。它要等 `turn_request` 那一刀把「受理」与「完成」拆开才有正解。
⚠️ 故 CONTEXT 那句「解冻绑数据库那一刀」**应读作「绑 `turn_request` 那一刀」**;CONTEXT 本刀不动(范围外),
待本 ADR 采纳后由收口那一刀订正措辞,**订正块保留原文**。

本刀对这条偏差**只有一个副作用**,如实记:被跳过的那一回合,只要仍在 `LOG_KEEP` 窗口内,会在下一次成功
persist 时以事件的形式补进历史(见决策 2「事件从哪来」)—— **历史补上了,盘上快照那一刻的偏差照旧**。

## 否决项

- **H2 替代真 PG 做测试**:方言不一致(`ON CONFLICT`、JSONB、序列/identity 行为都不同)→ Flyway 脚本会在一个
  **生产上不存在的库**上变绿;那是 ADR-018 §4.14 一族「量具不在测它以为在测的东西」。
- **DB 作权威、内存降为缓存**:要动忙态 CAS(改成行锁或版本号条件更新)与引擎的构造/回载路径,
  等于同时动 ADR-015 与 ADR-022 两条护城河,超出本刀;且其正解依赖 `turn_request`,顺序反了。
- **改 `compressLog` / 放开 `LOG_KEEP`**:见决策 4;另外放开 `LOG_KEEP` 会让视图 2 随局长线性增长,
  直接变成成本问题(ADR-016)。
- **事件溯源 / 从事件重放状态**:`game_event` 在本刀是**只读历史**,不是状态来源;引擎状态仍由快照回载。
  从事件重建状态属[求职线层 3.1](../backlog-career-track.md)(确定性事件推进引擎),不在本刀。

## 已知代价(不美化)

1. **两条存储路径并存**(文件 / DB),靠 `SessionStore` 这道缝隔开。测试面**翻倍的部分**点名:
   - 持久化边界的守护(ADR-015 附录 A 的 restore 往返 / 续跑一致)须对两个实现**各跑一遍**,
     否则 DB 实现可以在「文件实现绿」的掩护下写错一个字段;
   - 启动回载(`loadAll` 的「载入 / 跳过 / 拒载」三分与汇总计数)在 DB 形态下语义要重述(没有「非存档文件」
     这一类了),两边的汇总读数不可直接比较;
   - persist 失败路径(best-effort 不抛)两边各一条;
   - 不翻倍的:引擎、golden、prompt lockstep、准入、游标比对 —— 它们都在 `SessionStore` 之上,本刀不碰。
2. **回填有限,不是 0 也不是完整**:既有存档(文件或未来切换前的线上档)只含最后 ≤4 条叙事 +
   `logSummary` 里的 `[T{n}选{id}]` 动作字母串 —— 更早的叙事**已经不存在**,任何回填都补不回来。
   **回不回填、回填的档怎么标「不完整历史」,列为待决,不替 Felix 决。**
3. **开场叙事不在历史里**(勘察发现,brief 未提):`openingNarrative` 是 transient init 字段(ADR-007,
   `GameInitService` 在播种前从 world 根剥除),**不进 `GameSession`、不进 `log`**,`persist` 拿不到它。
   故按本 ADR 的写入点,历史**从第 1 回合开始**,没有开场那一段。补它需要 init 路径多传一个值或多一个写入点,
   **列为待决,不替 Felix 决**。
4. **原子性引入新的失败形态,须对照 CONTEXT §三.17(5) 重新陈述**:
   - 文件实现:一次原子 rename,盘上要么是上一回合的整份文档、要么是这一回合的整份文档。
   - DB 实现:快照与事件**同一事务**。事务失败 → 两者都回滚 → 库里停在**上一个完整回合**
     (快照与历史一致)。「盘上永远是最后一个完整回合」**这句判语在 DB 形态下仍然成立**,
     且多了一条性质:**快照与历史不会一新一旧**。
   - **新增的一个面**:文件实现失败只丢「这一回合的续局」;DB 实现失败时,这一回合的**事件**也没写。
     若下一次成功 persist 时该回合仍在内存 log 的 4 条窗口内 → 补上;**连续失败超过窗口 → 历史出现永久空洞**
     (回合号不连续)。读 API 不得伪造空洞里的内容;空洞如何在列表里呈现列为实现刀的验收项。
5. **ADR-022「落盘目录分家」挂账的前提变了**:该挂账的解冻条件是「第三个模块也往这个目录写文件」。
   在 `pg` profile 下 session 迁走,`aiuniverse.session.store-dir` 上**只剩 quota 一个租户**
   (`QuotaService:74` 复用同一个配置项写 `quota-YYYY-MM.json`)——**租户数从 2 变 1,不是变 3**,挂账不解冻,
   但「两个租户共存」这个描述在 `pg` 下不再准确。另:**Fly volume 仍须保留给 quota**(月累计跨重启,ADR-016),
   不能随 session 迁库而撤卷;且那个配置项的名字(`session.store-dir`)在 `pg` 下会误导读者,**本刀不改名**
   (改名要动 ADR-016 口径),记在这里。
6. **托管 PG 的价格:未核**(Felix 核)。本 ADR 不写任何数字。另记:DB 与应用须同区(syd),否则每回合一次
   跨区往返直接叠进名额占用时长(见决策 3)。
7. **「有效档」未定义**:本刀之后 `game_session` 行数会被拿去当「玩了多少局」读,而其中含孤儿档
   (future-experience §2.3 第 2 面:init 无条件落盘、取消也落)。**「有效档」的定义列为待决**;在定义之前,
   任何从 `game_session` 数出来的数字都**不许**进简历或统计(同工程债「简历素材」那条的分母纪律)。
8. **与多存档管理的顺序**:[future-experience §2.3](../future-experience-backlog.md) 与工程债 §3.3 都写了
   「先迁库再补多存档管理,和反过来做,代价差一整轮」。本刀选的是**先迁库**;它不做存档列表、不做 TTL、
   不修单 saveId 槽 —— 只是让以后做多存档管理时,「一局的历史」已经有地方放。

## 待决(列出,不替 Felix 决)

1. 既有存档回不回填;回填的档怎么标「不完整历史」。
2. 开场叙事要不要进历史(见代价 3)。
3. 「有效档」的定义。
4. **文件 profile 下读 API 的行为**:本地默认仍是文件存储,而文件里只有 ≤4 条。选项:(a) 明确返回
   「本环境无历史」一类的结构化错误;(b) 返回内存里那 ≤4 条并标 `incomplete`。倾向 (a) —— (b) 会让本地
   开发时看到一个「看起来能用」的截断历史,那正是「量具不在测它以为在测的东西」;但这是产品口径,由 Felix 定。
5. 线上何时切到 `pg` profile(本 ADR 只让它**可以**切,不决定**何时**切;切换本身是花钱与对外动作,Felix 亲手)。
6. 只读列表放在哪里(结局屏 / 返回后的选择屏 / 游戏屏一个入口),最小形态由实现刀提案、Felix 定。

## 测试面(写进 ADR,本刀不实现)

- **Testcontainers 真 PG**。⚠️ **CI `ubuntu-latest` 上 Docker 可用性:未验证** —— 不写成已知可用,
  也不写成已知不可用;实现刀的第一个 CI 运行即是验证,且须按 ADR-018 §4.14 / 工程债「回显纪律」口径
  **读到 DB 测试真的跑了**(用例数、容器启动日志),不许只看 job 绿。
  若不可用,退路(跳过 / 标记)须回窗口定,**不许静默跳过** —— 静默跳过的 DB 测试是「绿有两种解释」的最坏形态。
- **首个会变红的是 `ServerApplicationTests`**(`@SpringBootTest` 全量起上下文):一旦 JDBC / Flyway 依赖进
  classpath,DataSource 自动配置会在默认 profile 下尝试建连接并失败。**默认 profile 保持它绿的方式**:
  DB 相关的自动配置与 bean **只在 `pg` profile 下生效**(默认 profile 排除 DataSource / Flyway 自动配置,
  DB 版 `SessionStore` 以 `@Profile` 装配);具体排除项的类名随 Spring Boot 4.1 模块化后的包名实现刀确认,
  **本 ADR 不写死类名**。验收:`ServerApplicationTests` 在默认 profile 下**不启动任何容器、不需要 Docker** 照样绿。
- **事务失败 → persist 不抛、回合照常**:注入一个会在事件插入时失败的条件,断言 ① `submitAction` 不抛、
  相位照常放回、sink 照常收完;② 快照与事件**都未**写入(同一事务回滚);③ 下一次成功 persist 时,
  窗口内的漏写回合被补上。三条须**分别**可被变异打红(§4.13)。
- **消毒**:读 API 的响应里**永不出现** `isTrue` / `hiddenLogic`(同 init / state 的独立硬闸形态)。
- **超时**:DB 挂起时 `persist` 在配置的上界内返回(假数据源 / Testcontainers 暂停容器),名额随之归还。

## 实现分刀(每刀独立可验、可回滚)

- **刀 1 · schema + 写路径**:依赖 + `pg` profile + Flyway 迁移(两张表)+ DB 版 `SessionStore`
  (persist / loadAll)+ 超时配置 + 上述写路径测试。**默认 profile 行为逐字节不变**是本刀的回滚保证:
  不切 profile 就等于没发生。验收含 `ServerApplicationTests` 默认 profile 绿 + CI 上 DB 测试真的跑了。
- **刀 2 · 只读 API**:历史读接口 + 消毒硬闸 + 文件 profile 下的行为(按待决 4 的裁定)。只读、无副作用,
  可单独回滚。
- **刀 3 · 最小只读前端列表**:无皮肤;网络 IO 只在 `web/src/api/`(ADR-003 边界 + eslint 硬线);
  空洞回合如实呈现。可单独回滚(删入口即回退)。
- **刀 4 · 线上切换与真机冒烟**:`fly` 侧的 DB 创建、secret、profile 切换**全部 Felix 亲手**
  (runbook §3.1.4 / §3.1.5 / §3.1.6 前置照旧);回填若裁定要做,在这一刀或单独一刀,不混进刀 1。
  **收口时**再判 CONTEXT §三.17「不引数据库」与「解冻绑数据库那一刀」两处措辞如何订正(循「待验通再立字」)。

每刀一律:`Engine` / `compressLog` / `LOG_KEEP` / `TurnStateMachine` / 忙态 CAS / 引擎 golden /
prompt lockstep / `schemaVersion`(保 "0.4")**零动**。

## 重新审视触发条件

- `turn_request` 那一刀开工 → 本 ADR 的写入点须并入两段式第 3 段,`version` 列开始被读;游标比对是否保留须按
  ADR-023 §重新审视重估。
- 出现多实例需求 → 本 ADR「内存为权威」的前提失效(ADR-015 硬约束①)。
- 连续失败导致的历史空洞在线上被观察到 → 重估「取窗口内全部条目」是否够用。

## 交叉引用

- [ADR-015](ADR-015-overseas-deployment-form-factor.md)(持久化边界、已知代价 5 best-effort)
- [ADR-022](ADR-022-turn-admission-and-rejection-semantics.md)(名额占用、落盘目录分家挂账)
- [ADR-023](ADR-023-turn-cursor-idempotency.md)(同进程游标比对的边界)
- [ADR-024](ADR-024-stream-segment-deadline.md)(`durMs` 不含 persist 的取证边界)
- [CONTEXT §三.17](../CONTEXT.md)、[工程债 §3.3](../backlog-engineering-debt.md)、
  [求职线层 2](../backlog-career-track.md)、[future-experience §2.3](../future-experience-backlog.md)
