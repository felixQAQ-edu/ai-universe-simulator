# ADR-032 · 切库准备:文件存档与数据库之间的导入 / 回退导出、连接配置、启动语义与演练(不执行线上切换)

- **日期**:2026-10-12
- **状态**:**Accepted(安全网范围)**,2026-10-12 定稿(草案 `cb4f70e`)。刀 1 已合并(`main@e17a71c`,未部署);刀 2 已合并(`main@e6d134b`,未部署);刀 3 runbook 与比对脚本已合并(`main@9899c5e`,[pg-cutover-drill.md](../pg-cutover-drill.md)),**Felix 真实演练于 2026-10-14 通过**(构建 `9899c5e`,来源为线上卷只读拷贝)。
- **总基调**:**生产存储默认仍为文件存储;本 ADR 不启动线上切库。** 本 ADR 只交付五件事:
  文件 → DB 导入、DB → 文件导出(回退)、`pg` profile 连接配置、数据库不可用时的启动语义与测试、测试环境演练。
- **决策者**:Felix
- **前提**:`main@c666853`。依据 [切库勘察底稿](../pg-cutover-survey.md)(截至 `357b5a3`,下称「底稿」)、
  [ADR-025](ADR-025-narrative-history.md)(叙事历史,刀 4 挂账)、[ADR-026](ADR-026-turn-acceptance-record.md)、
  [ADR-031](ADR-031-turn-execution-trace-and-offline-replay.md)(W-1 / W-11)、[ADR-016](ADR-016-cost-gate.md)(月账)。
- 路径前缀 `…/` = `server/src/main/java/com/aiuniverse/server/`。行号以 `c666853` 为准,会漂。
  推断标「**推测**」,没核实的标「**未证实**」。
- **本 ADR 不写代码**;只定导入 / 导出 / 配置 / 启动语义 / 演练的形状与测试面,实现另起刀(见「实施计划」)。
- **定稿说明**:草案 §八 的五项待决已全部处置 —— D-1、D-2、D-5 移入「已决」;D-3、D-4 移入「切库前检查清单」,
  标「未验证」,**不是本 ADR 的交付**。正文各节原先写「待决」处已改为指向「已决」,草案原句不另留(定稿前未合并、未被引用)。

---

## 置顶:勘察中改变了一处建议的结论

brief 建议的校验方式是「导入后由 pg 读回再 **encode**,与源文件逐字节比对」。**按源码,再 encode 不会逐字节相等**,
原因有两个,都与数据无关:

1. **`phaseHint`**:`SessionDocument.encode` 写的是 persist 那一刻的相位(`…/persistence/SessionDocument.java:42`),
   正常回合落盘时是 `SETTLING`、主调用流中断降级时是 `GENERATING`(`…/persistence/TurnTrace.java:72-73`;
   persist 在相位放回之前,`…/eventloop/TurnStateMachine.java:117-119`)。而 `decode` 把相位按 `status` 重置为
   `AWAITING_ACTION` / `ENDED`(`SessionDocument.java:78`)。故 decode → encode 之后 `phaseHint` 必然改变。
2. **旧局标记**:没有局面键的《动物人生》旧局,decode 后会补上旧局标记,下次 encode 写出 `{"legacy": true}`
   (`SessionDocument.java:43-46,77`;CONTEXT §三.17 ADR-028 补充段)—— 源文件里没有这个键。

因此本 ADR 的校验口径改为:**导入写入源文件的原始文本(经 decode 校验合法之后),读回 `snapshot::text` 与源文件逐字节比对**;
decode 只当合法性闸门用,**不参与产生入库字节**。依据:PG `json` 列按输入文本原样存储(V1 迁移注释选 `json` 不选 `JSONB`
的理由正是「保键序、往返逐字节」,`server/src/main/resources/db/migration/V1__narrative_history.sql:8-11`);
读侧已在用 `snapshot::text`(`…/persistence/JdbcSessionStore.java:166`)。⚠️ 「`json` 列连空白也原样保留」是 PG 文档行为,
**本项目未对导入路径实测**,列为测试面第 1 条。

这不是与「已定」冲突(已定里没有校验方式),是对 brief 一条建议的订正,故不停下、在此写明。

---

## 背景

切库勘察底稿结论:今天 `pg` profile 下存档 / 叙事历史 / 受理记录三项已有实现与 Testcontainers 覆盖(底稿 §一 问题 1–2),
但**不能「只换 profile」就切过去** —— 文件存档 → 库的导入代码不存在、库 → 文件的回退导出不存在、
启动时 DB 不可达的行为未测、代码不读平台注入的 `DATABASE_URL`(底稿 置顶 2、§二 问题 5、§四 问题 10)。
ADR-025 刀 4 挂账时列的三项前置代码(快照导入工具 / 启动时 DB 不可达行为核实 / runbook 切库一节)一项都还没做(底稿 §一 问题 3)。

### 已定(2026-10-12,Felix;本节不得改动)

- **不执行线上切库;线上默认仍为文件存储。** 理由:Fly Managed Postgres 最低 Basic **$38/月** + 存储 **$0.28/GB·月**
  (校勘 2026-10-12 核实 https://docs.fly.io/postgres.md),高于整个模型预算;自管库官方不支持
  (https://fly.io/docs/postgres/getting-started/what-you-should-know/),备份 / 升级 / 恢复成持续责任。
- **D 阶段只做切换准备**:文件存档 → 数据库导入、数据库 → 文件导出(回退)、`pg` profile 连接配置、
  数据库不可用时的启动行为与测试;在测试环境演练导入与回退。
- **重新切库的条件(三条同时满足)**:真实使用量或查询需求使文件存储不够用;能承担持续费用或运维责任;导入与回退演练通过。
- **简历口径**:只写「PostgreSQL + Flyway 在 pg profile 下实现与验证(含导入 / 回退演练)」,不写已完成线上迁移。
- **ADR-025 刀 4 原文不改**;在 ADR-025 下追加一行注记指向 ADR-032(**实施时做**,本刀只在这里写明)。

> **定稿补记(不改上面「已定」原文)**:「ADR-025 下追加一行注记」拆成两步 —— 定稿这一刀先在 ADR-025 加**一行指针**
> (「导入 / 导出见 ADR-032」);**带读数的注记在刀 2 实施时加**(导入 / 导出工具落地之后)。

> 与底稿的对照:底稿 §二 问题 4 的 Fly 数字标「未证实(检索摘要读数)」,上面的价格已由校勘另行核实,二者一致($38 / $0.28);
> 底稿与已定**无冲突**。

---

## 已决(2026-10-12 定稿)

### D-1 · 数据库不可用 → **拒绝启动**(草案候选 A)

- **决定**:`pg` profile 下,启动时数据库不可达(连接失败或 Flyway migrate 失败)→ 进程以**非零退出码**退出,
  日志有一行写明原因(哪个连接来源、哪一步失败;不含密码)。**不降级、不回退到文件存储。**
- **理由**:`pg` profile 下数据库是**唯一真相源**。降级启动(草案候选 B)会让站点看起来正常,而所有续局都回 404
  —— 前端对续局失败一律静默清掉存档指针(ADR-025 代价 9 同一机制),DB 恢复后数据还在库里,玩家却再也回不去。
  **把续局误报成 404 比拒启更危险。** 候选 C(有界等待后拒启)不采纳:它只是 A 加一段等待,等待时长要和平台健康检查
  宽限期一起调,今天没有任何读数支撑一个具体值;需要时可在 A 之上再加,不改变 A 的语义。
- **「不回退到文件存储」在结构上已有一半保证**:`FileSessionStore` 标 `@Profile("!pg")`(`…/persistence/FileSessionStore.java:61`),
  `pg` 下根本不会被装配。另一半(不读写 `/data` 下的存档文件)由测试与演练证明,见下。
- ⚠️ **必须写明:Fly 的重启不是无限重试。** 机器的 on-fail 重启策略有重试上限;数据库持续不可用时,
  拒启 → 重启 → 再拒启,重试用完后**机器可能最终停下**。**这是预期行为,不是故障。**
  今天 `fly.toml` **没有显式写 restart policy**,实际取值由平台给出;**本 ADR 不凭默认值推断次数**,
  演练时读出实际值并记录(见下)。
- **演练验收**(写进 runbook 草案,§六):
  - (a) **核对演练机器的实际重启策略**:用 `fly machine status`(或机器配置中 restart policy 的实际值)读出并记进演练记录,
    不凭文档默认值推断。⚠️ 若该次演练只在本地 / Testcontainers 上做,本地进程没有平台重启策略,
    (a) 记为「**未在 Fly 上验证**」,并留在「切库前检查清单」里,不得写成已验证。
    ⤷ 2026-10-14:已只读读到线上 restart policy = `on-failure`、`max_retries` = `10`;拒启 + 有限重试后停机的行为
    与 D-1 的设计一致,但仍记为「**未在 Fly 上验证**」。
  - (b) **验证服务拒启**:进程非零退出,日志有明确原因行。
  - (c) **验证没有回退到文件存储**:启动日志不得出现 `FileSessionStore` 装配(`[session-store] 落盘目录` 一类行),
    不得读写 `/data`(或演练指定的存档目录)下的存档文件。

### D-2 · 导入的「部分成功」语义

- **顺序**:先**扫描**整个输入目录,列出**不可读档**(JSON 解析失败或 `SessionDocument.decode` 拒载),再**导入可读档**。
- **报告必须分别给出三个计数**:
  - `imported`:本次新插入并读回一致的档;
  - `skipped`:未写入的可读文件,含 **非存档**(月账等,无 `world` 节点)、**库中已存在的原生行**(`source='native'`)、
    **库中已存在且与源逐字节相同的导入行**(`source='import'`,即重复导入);
  - `integrity_failed`:读回不一致的档。
  - **不可读档单独列名单**:只列文件名 / saveId 与原因类别,**不输出存档内容**。
- **退出码**:存在不可读档 → **非零退出**,但**可读档照常导入**(不是整批失败)。
- **只有「读回不一致」触发整批回滚**:`snapshot::text` 与源文件字节不等(或 `turn` / `status` 列与 decode 读数不等)→
  本批全部写入回滚,`integrity_failed` 报出哪一档、第一个不同字节的偏移,退出码非零。
- **事务边界**(刀 2 的验收要求,ADR 定形状,实现时写进代码注释与测试):
  - **扫描阶段不开事务**、不写库(只读源目录,产出不可读名单与候选清单)。
  - **导入阶段 = 一个事务**:对候选中每一档「查已有行 → 插入 → 读回比对」全部在同一事务内;**全部比对通过才提交**。
  - 回滚覆盖本批的**全部** `game_session` 插入;导入不写 `game_event` / `turn_request`(§1.1),因此事务内没有别的写入。
- **故障测试**(刀 2 验收要求):人为注入一次读回不一致(测试侧替换读回比对所用的读取,让它返回篡改过的字节;
  ⚠️ 不能用「另开一条连接改写该行」—— 插入尚未提交,独立连接看不见它。具体接缝由刀 2 定),断言:进程非零退出、`integrity_failed = 1`,且**库内无任何本批写入残留**
  (`source='import'` 行数 = 导入前的值)。
- ⚠️ **明文:任何非零退出都不得被当成可切库的成功演练。** 演练「通过」的**唯一**条件:
  **退出码 0,且三项计数与源目录清点一致**(源目录直接下 `*.json` 文件数 = `imported + skipped`,`integrity_failed = 0`,
  不可读名单为空,「已存在且不同」名单为空;`imported` 等于库中本批新增的 `source='import'` 行数)。
- **已存在且不同的导入行(Felix 2026-10-12 确认)**:库中已有**导入行**(`source='import'`)而快照与源文件**不同**
  (两次导入之间源目录变了)→ **不覆盖**、计入 `skipped`、在报告中逐份列出「已存在且不同」,并且**非零退出**;
  **不整批回滚**,其余可读档照常导入;该次运行**不算通过的演练**(与下一条「任何非零退出都不算通过」一致)。
  草案原把它与「已有原生行」一起定为整批失败,与 D-2「只有读回不一致才整批回滚」冲突,故改。
- **退出码(刀 2 定,Felix 2026-10-13)**:

  | 退出码 | 含义 |
  |---|---|
  | 0 | 通过。导入只有满足上面「通过」定义才给 0;导出 = 全部写出且没有载不动的行 |
  | 1 | 工具或配置错误(参数、路径闸门、不是 `pg` profile、连不上库 / Flyway 失败、导出目标非空等),**库里什么都没写**;导入在这些情形下文件侧也什么都没写。⚠️ **导出例外**:写到一半发生 IO 错误同样返回 1(`SessionExporter.run` 的写出 catch),此时目标目录里**可能已有部分 `.json`(与一个残留的 `.json.tmp`)**。**处置:导出返回 1 时,目标目录须清空后重跑** —— 不得拿一个返回 1 的导出目录去回退或比对 |
  | 2 | 数据层面需要人看:导入有不可读档,或有「库与源文件不一致」的已有行(可读档已照常导入);导出有拒写或载不动的行 |
  | 3 | 完整性失败:读回不一致(或提交前的对账不平),**整批已回滚** |

  同时出现多种情况时取最大值;报告把每一类都列出来。
  - **已有原生行(Felix 2026-10-14 确认,原为刀 2 记的待确认判读)**:已有**原生**行(`source='native'`)而快照与源文件**不同** →
    列入「库与源文件不一致(不覆盖)」、**退出码 2**(与「已存在且不同的导入行」同一格,按「库与源文件不一致」不分来源处理);
    已有原生行且字节**相同** → 只列出、**退出码不变**。§1.3 表里原生行那一格只写了「不覆盖、计入 skipped、逐份列出」,没写退出码,故在此写明。
  - 对账两项(`N = imported + skipped + 不可读`、`导入后 − 导入前 = imported`)放在**提交之前、同一事务里**断言,
    对不上即抛、整批回滚、给 3 —— 这样「对账不平」也落在「什么都没留下」的那一侧,不会出现「已提交但对不上」的状态。

### D-5 · 连接池

- Hikari `maximum-pool-size` **显式设为 5**(今天吃默认 10,底稿 P-6),**可由环境变量覆盖**
  (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`,Spring 标准松绑定;env 名在刀 1 实测确认)。
- **启动时打一行日志,含最终取值**(形状照 `[turn-admission] 准入容量 N=8`:打解析后的值,不打配置文件里的字面量)。
- 512 MB 机器上的内存读数:演练时取一次,标「**演练观测值**」,注明**单次、非压测**;**不得据此宣称容量已充分验证。**
  ⤷ 2026-10-14 Felix 演练:Mac 上的观测值 214320 KB,只作演练观测值(非 512 MB 机器)。

### 共用重构:`requireOutsideRepo` 移到 `src/main`

- `TraceFixtureConverter.requireOutsideRepo`(连同它依赖的 `findRepoRoot`)今天在 `src/test`
  (`server/src/test/java/com/aiuniverse/server/eventloop/TraceFixtureConverter.java:142`),
  `TraceRecheck` 也在用。导出工具在 `src/main`,要用同一道闸门 → **移到 `src/main`**,供导出工具与夹具转换共用,
  行为不变。**刀 1 的一项;本刀不动代码。**

---

## 切库前检查清单(**每项均未验证**;不是本 ADR 的交付)

| 项 | 内容 | 状态 |
|---|---|---|
| D-3 · 工具的线上运行方式 | 同 jar 换主类在 `fly ssh console` 内执行是否可行(镜像 `ENTRYPOINT ["java","-jar","app.jar"]`,`Dockerfile:37`)。刀 2 只在本地实测了 `PropertiesLauncher` + `-Dloader.main` 写法(§三),容器内未跑 | **未验证** |
| D-4 · Flyway 是否需要单独的 direct 连接 | MPG 迁移走 direct URL、业务走 pooled URL(底稿 P-4,摘要读数);本 ADR 只预留 `spring.flyway.url` 可单独设置这一口 | **未验证** |
| D-1 (a) · 平台重启策略 | 若 D-1 演练只在本地做,Fly 机器上的实际重启策略与重试上限仍未读 | **未验证**(演练在 Fly 上读到后改) |
| D-5 · 生产内存 | 512 MB 单机下 `pg` profile 的 RSS(演练观测值只是单次读数) | **未验证** |

---

## 一、导入:文件存档 → `pg`

### 1.1 输入与输出

- **输入**:一个存档目录(与 `FileSessionStore` 的落盘目录同形:`<saveId>.json` 平铺,可能混有 `quota-YYYY-MM.json`
  与 `traces/` 子目录,`…/persistence/FileSessionStore.java:42-48,122-124`)。只看目录**直接**下的 `*.json`,
  不递归(与 `loadAll` 同口径;`traces/` 因此天然不在候选里)。
- **输出**:只写 **`game_session`** 一张表,每份合法存档一行:
  `save_id` = 文件名去 `.json`(与 `loadOne` 同取法,`FileSessionStore.java:167-168`)、
  `snapshot` = **源文件原始文本**、`turn` / `status` = decode 出的 Engine 读数、`source = 'import'`。
- **不写 `game_event`**:沿用 ADR-025 已决 1 —— 导入快照,历史由补齐规则在该局下一次 persist 时补进 ≤4 条
  (`JdbcSessionStore.java:31-34,101-107`),起点标「此前的回合未被记录」。⚠️ 由此推出:**导入后不再走任何回合的局
  (含已结束的局)永远没有事件**,读 API 只返回一个「记录开始之前」的缺口;这是 ADR-025 已决 1 的既有后果,不是新代价。
- **不写 `turn_request`**:文件里没有对应物(底稿 §三 问题 8);导入档开局没有受理行。

### 1.2 判据(与文件回载同一套,不另写)

逐文件三分,**判据唯一一份在 `SessionDocument`**(ADR-025 代价 9 要求导入走与文件回载同一套判据):

| 情形 | 判定 | 导入行为 |
|---|---|---|
| JSON 解析不了 | 疑似坏档 | **不可读**,列入不可读名单(文件名 + 原因类别) |
| 无 `world` 对象节点(`SessionDocument.isSaveDocument`,`SessionDocument.java:57-59`) | 非存档(月账等) | **跳过**,计入 `skipped`,不报错 |
| 是存档,`SessionDocument.decode` 抛(`SessionDocument.java:65-80`) | 不可读 | **不可读**,列入不可读名单 |
| decode 成功 | 合法 | 入库 |

- **旧局(无局面键)**:decode 对有无局面键都处理(`SessionDocument.java:77`),按上表走「合法」分支,**原始文本入库,不补旧局标记**
  (标记由切库后的第一次 persist 写入,与文件 profile 下的行为一致)。
- **不可读档的处置见「已决 · D-2」**:不让整批失败(今天文件 profile 下这些档同样载不进来,`FileSessionStore.java:183-186`,
  导入不改变玩家可见状态),但有不可读档即非零退出,强迫人看清单。

### 1.3 原子性、校验与幂等

- **一批一个事务**(事务边界见「已决 · D-2」):全部可读存档在同一事务内插入;读回比对也在提交前完成。任一档比对不一致 → **整批回滚**,
  报告哪一档、第一个不同字节的偏移。底稿 §三 问题 7 的最近读数是 145 档(2026-09-28),单事务体量**推测**无压力(未实测)。
- **读回比对(核心校验)**:`SELECT snapshot::text … WHERE save_id = ?` 与源文件 UTF-8 字节逐字节相等;
  另比 `turn` / `status` 两列与 decode 读数相等。理由见置顶。
- **对账读数(ADR-025 代价 9 原话要求)**:报告给出 `候选 N`、三个计数 `imported / skipped / integrity_failed`、
  不可读名单(逐份),以及库中 `source='import'` 行数的导入前 / 导入后值;断言 `N = imported + skipped + 不可读数`、
  `导入后 − 导入前 = imported`。不许只输出「导入成功」。通过定义见「已决 · D-2」。
- **幂等性(重复导入同一目录)**:按 `save_id` 逐档判定 ——

  | 库中已有该 `save_id` | 快照文本 | 行为 |
  |---|---|---|
  | 无 | — | 插入,读回比对,计入 `imported` |
  | 有,`source='import'` | 与源文件逐字节相同 | **无操作**,计入 `skipped`(重复导入) |
  | 有,`source='import'` | 不同 | **不覆盖**,计入 `skipped` 并逐份列「已存在且不同」,**非零退出**(不整批回滚,其余可读档照常导入;Felix 2026-10-12 确认,「已决 · D-2」) |
  | 有,`source='native'` | 任意 | **不覆盖**,计入 `skipped` 并逐份列出(库里已有本 profile 下推进过的局) |

  即:**导入从不覆盖库里已有的行,尤其绝不覆盖原生行**。重复跑同一份目录的结果与跑一次相同。
  (草案把后两格定为「整批失败」;D-2 定为「只有读回不一致才整批回滚」,故改为跳过并列出。)

### 1.4 配额月账与轨迹:都留在卷上,不迁

| 租户 | 去向 | 理由 |
|---|---|---|
| 配额月账 `quota-YYYY-MM.json` | **留卷上,不迁** | `QuotaService` 不分 profile,pg 下仍读写文件(`…/quota/QuotaService.java:74,194`);ADR-025 测试面已写「quota 月账不被导入」;导入它进库没有任何读者 |
| 回合轨迹 `traces/*.trace.jsonl` | **留卷上,不迁** | ADR-031 已决 W-1 不做仅 pg 落点,两个 profile 都写文件(`…/persistence/TraceSinkConfig.java:18`);导入只扫直接子文件,`traces/` 天然不进候选 |

后果(ADR-025 已知代价 5 已写):**切库不等于撤卷**,切库后数据分在库与卷两处(底稿 §四 问题 11)。

---

## 二、导出(回退):`pg` → 文件

### 2.1 形状

- **输入**:`game_session` 全表。**输出**:一个**空的**目标目录,每行写 `<save_id>.json`,内容 = `snapshot::text` 原样。
  - 写法照 `FileSessionStore.persist`:先写 `.json.tmp` 再 `ATOMIC_MOVE`(`FileSessionStore.java:98-105`)。
  - 目标目录非空 → **拒绝运行**(不与月账、旧存档混写;回退时由人把导出结果与卷上月账合在一起,步骤进 runbook)。
  - `save_id` 不匹配 `SAFE_SAVE_ID`(`FileSessionStore.java:67`)→ 该行拒写并列出(防目录逃逸,与 persist 同口径)。
- **导出前对每行做一次 decode**:载不动的行**照样导出**(保留尸检,与 `loadAll` 拒载留行的口径一致),但在报告里逐行列出,
  因为它们回退到文件 profile 后也会被拒载。
- 对账读数:`库中行数 N / 写出 N−R / 拒写 R(逐行)`。

### 2.2 回退会丢什么(写清,不粉饰)

文件格式只有快照。导出**带不走**:`game_event`(叙事历史,含 turn 0 开场叙事)、`turn_request`(受理记录)、
`source` / `created_at` / `updated_at`。回退之后 `/history` 回到 501(`UnavailableHistoryReader`),历史本就不可见;
续局只依赖快照,**不受影响 —— 推测**(依据:文件 profile 下续局只读快照,`SessionDocument.decode`),演练须实证(测试面第 6 条)。

### 2.3 往返:核心测试

**文件 → pg → 文件,逐字节相等**:对同一组源文件,导入(§一)后导出(§二)到空目录,
对每个**被导入的**存档 `cmp` 源与导出结果。比较集合 = 导入入库的那些档;非存档与拒载档按设计不往返,
其数量由两侧对账读数分别给出、必须对得上。

---

## 三、工具形态

- **离线命令,不加任何 HTTP 端点**(echo-stream 教训:一个能写库的端点就是一扇要记得关的门,ADR-005 退役记录)。
- **入口(刀 2 实现)**:`com.aiuniverse.server.persistence.StoreMigrationTool`(同一个 jar 内的独立 `main` 类;
  落在 `persistence` 包而不是提议里的 `persistence/migrate` 子包 —— 为了直接复用包内可见的 `FileSessionStore.SAFE_SAVE_ID`
  与 `assertOutsideWebRoot`,不另开公共入口、不复制正则)。子命令 `import <dir>` / `export <dir>`,可选 `--report=<file>`;
  其余 `--x=y` 原样交给 Spring。上下文由工具自己的 `ToolConfig` 起:`@ImportAutoConfiguration` 只引 DataSource / Flyway / Jackson,
  外加一个 `ArchetypeRegistry` bean,**不用 `@SpringBootApplication`、不扫包**,非 web(`WebApplicationType.NONE`)。
  装配面由 `StoreMigrationToolContextTest` 守:无 `GameSessionManager`、无任何 `SessionStore`、无 web 服务器
  (变异「改回扫全包」→ 该用例红)。工具类都不是组件,默认 profile 的应用上下文不装配它们(`DefaultProfileHasNoMigrationToolTest`)。
  - 调用(本地,合成数据,2026-10-13 实跑可用):
    `SPRING_PROFILES_ACTIVE=pg DATABASE_URL=… java -cp app.jar -Dloader.main=com.aiuniverse.server.persistence.StoreMigrationTool org.springframework.boot.loader.launch.PropertiesLauncher import <dir> --report=<file>`。
    ⚠️ 不能把解开的 `BOOT-INF/classes` 当 classpath 直接跑:`META-INF/spring.factories` 在 jar 根,不在那里,
    `DATABASE_URL` 的解析(`PgConnectionEnvironmentPostProcessor`)会静默不生效(本地踩到过,报「url 未指定」)。
  - 线上(`fly ssh console` 内)同一写法是否可行:**未验证**,仍在检查清单 D-3。
  - ⚠️ **导入 / 导出时应用必须停着**:工具不防并发写者(应用的 persist 会与导入交错,对账读数也会失真)。刀 3 的 runbook 照这一条写。
- **profile**:以 `pg` profile 运行(需要 `application-pg.yml` 的超时配置与 Flyway)。工具**先跑 Flyway migrate**
  再操作,保证在空库上也能用。⚠️ 工具里的超时:`application-pg.yml` 的 2 s 事务超时是为「persist 在准入名额内」定的
  (`application-pg.yml:11-17,42-47`),整批导入在一个事务里会超过它 → 工具**须用自己的事务超时**(刀 2:配置项 `aiuniverse.store-migration.tx-timeout-seconds`,缺省 300 s —— 一批约 145 档、每档三条毫秒级语句,上界只为「DB 挂起时不无限等」;单条语句仍受 `socketTimeout=2` 约束;演练读数出来后可再定),
  不得改动线上 persist 的超时。
- **路径规则**:
  - 输入 / 输出路径一律经 `toAbsolutePath().normalize()`,不得位于 static web 根之下(复用 `FileSessionStore.assertOutsideWebRoot`)。
  - **处理真实存档时,输入输出须在仓库之外**(复用 `requireOutsideRepo` 同一判定,ADR-031 刀 5a;刀 1 把它从 `src/test` 移到 `src/main`,见「已决」);
    合成夹具除外(见 §六)。
  - 报告**不回显绝对输入路径与 saveId 以外的存档内容**(照 W-11 精神:报告可贴进聊天,存档内容不可)。

---

## 四、连接配置

### 4.1 两种来源

| 来源 | 形态 | 今天 |
|---|---|---|
| Spring 标准项 | `SPRING_DATASOURCE_URL`(`jdbc:postgresql://…`)+ `_USERNAME` + `_PASSWORD` | 已支持(`application-pg.yml:3-4` 注释) |
| 平台注入 | `DATABASE_URL`(`postgres://user:pass@host:port/db?…`,MPG attach 写入) | **不支持**,全 `server/src/main` 无读取点(底稿 §二 问题 5) |

### 4.2 提议

- `pg` profile 下**同时接受两者**,由一个只在 `pg` profile 生效的小组件在 DataSource 创建前解析(形态待实现刀定,
  `EnvironmentPostProcessor` 或自定义 `DataSourceProperties` 均可)。
- **优先级**:`spring.datasource.url` 已设置 → **完全不读 `DATABASE_URL`**(不按字段混用两个来源,避免 url 来自一处、密码来自另一处);
  未设置且 `DATABASE_URL` 存在 → 解析为 jdbc url + username + password;两者都没有 → 按 D-1 拒启(§五)。
- 启动打一行 INFO 说明**用的是哪个来源、主机与库名**,**不打用户名以外的任何凭据**(形状照 `[turn-admission] 准入容量 N=8`
  的「开机说清现值」)。
- **Flyway 的连接**:底稿 P-4 —— MPG 的迁移须走 direct URL、业务走 pooled URL(摘要读数,未证实)。本 ADR 只预留
  `spring.flyway.url` 可单独设置这一口,**是否需要两条 URL 列入「切库前检查清单」D-4,未验证**,不在本 ADR 定。
- **连接池**:Hikari `maximum-pool-size` 显式 5、env 可覆盖、启动日志打最终值(「已决 · D-5」)。

### 4.3 URI 解析测试(只列)

`postgres://` 与 `postgresql://` 两种 scheme;无端口(默认 5432);用户名 / 密码含百分号编码字符(`@`、`:`、`/`);
无密码;query 参数原样带过(如 `sslmode=require`);IPv6 主机;缺库名 / 非法 URI → 启动失败且**错误信息不含密码**;
两个来源同时存在时 `SPRING_DATASOURCE_URL` 胜出且 `DATABASE_URL` 的密码不被读取。

---

## 五、数据库不可用时的启动语义(**已决 D-1 = 拒启**,见「已决」)

**事实先行**:今天的行为是**推测**「起不来」(Flyway 在启动时 migrate,拿不到连接即抛,上下文中止),**未测**(底稿 §二 问题 5;
ADR-025 刀 4 待办原话)。刀 1 用测试把它**钉住为 D-1 = 拒启**:今天若确实已经拒启,测试只是把推测变成守护;
若不是,刀 1 改实现使其拒启。下表是定稿前的候选,**留档**,不再是待选项。

| 候选 | 行为 | 好处 | 代价 |
|---|---|---|---|
| **A · fail-fast(拒启)** | DB 不可达 → 启动失败、进程退出 | 不会在「看不见存档」的状态下对外服务;行为简单 | 单机单副本(`--ha=false`)下整站不可用;平台健康检查(`grace_period = "45s"`)→ 重启循环(**推测**,未证实) |
| **B · 降级启动** | 跳过 Flyway / 以空档启动(`JdbcSessionStore.loadAll` 读失败分支,`JdbcSessionStore.java:167-171`),persist 逐次失败记 ERROR | 站点仍可打开,新局能玩(只活在内存) | **续局全部 404 → 前端静默清掉存档指针**(ADR-025 代价 9 同一机制)—— DB 恢复后指针已经没了,**数据还在库里,玩家却再也回不去**;与 ADR-025 决策 3「DB 故障不许杀活回合」的出发点相同、结果相反;且 Flyway 未跑时 schema 状态未知 |
| **C · 有界等待后 fail-fast** | 启动时以有限次数 / 有限时长重试连接,仍不可达则退出 | 吸收 DB 晚于应用就绪的短暂窗口(部署时常见) | 等待时长要落在平台健康检查宽限期之内;超出后同 A |

- 选定的 A 须配一条**启动日志现值**(用的是哪个连接来源、是否连上、Flyway 当前版本)与测试(测试面 13)。
- **A 的已知代价**(已接受):`pg` profile 下 DB 持续不可用 = 整站不可用,且平台重试用完后机器可能停下(「已决 · D-1」)。
- 已知事实之一须一并考虑:`pg` 下 db 健康指示器是关的(`application-pg.yml:34-40`,ADR-025 已知代价 10)——
  **启动之后** DB 挂掉 health 仍报 UP;本节只管**启动时**,运行中的 DB 故障语义沿用 ADR-025 决策 3,不在本 ADR 改。
- 与「不执行线上切库」的关系:D-1 只影响 `pg` profile;默认 profile 不连库(`DefaultProfileNoDatabaseTest` 已守),线上不受影响。

---

## 六、演练

### 6.1 环境与数据

- **环境**:本地与 CI 的 Testcontainers PG(镜像与既有 pg 用例同一版本,`PgContainerSmokeTest`;CI 无 Docker 时**失败不跳过**,
  `DockerGate`)。
- **合成存档(入库,CI 跑)**:由测试代码现场生成 —— 覆盖四个基础世界、一组融合、《寻常》、《动物人生》新局与**旧局(无局面键)**、
  已结束的局、`phaseHint` 为 `SETTLING` / `GENERATING` 的档(置顶那两个陷阱)、一个月账文件、一个 `traces/` 子目录、
  一个截断的坏 JSON、一个 decode 必拒的档(未知 archetype)。
- **Felix 本机的本地存档(不入库)**:本机 `server/data/` 下已有的存档,**只在 Felix 本机运行**;原始文件**不进仓库、不进聊天、
  不交给云端会话**,只贴工具输出的对账报告(照 ADR-031 W-11 同一精神)。⚠️ 本机存档未必覆盖上面的全部情形,
  它补的是「真实数据形态」,合成存档补的是「情形覆盖」,两者不互替。

### 6.2 runbook 步骤草稿(实施时落进 runbook,本 ADR 只列)

1. 起一个 Testcontainers / 本地 Docker PG,得到连接串。
2. **复制**本地存档目录到仓库之外的临时目录(工具只读源目录,但演练只对副本操作)。
3. `import <副本目录>` → 读对账报告:候选 N、`imported / skipped / integrity_failed`、不可读名单、库中 `import` 行数前后值;退出码。
   通过 = 退出码 0 且 `N = imported + skipped`、`integrity_failed = 0`、不可读名单为空(「已决 · D-2」)。
4. 再跑一次同一 `import` → `imported = 0`、`skipped` 比上次多出上次的 `imported`(幂等)。
5. 以 `pg` profile 启动应用连这个库 → 启动日志「载入 N 档,0 档拒载」与第 3 步入库数相等;选一局续局走一回合 →
   该局 `game_event` 出现补齐的 ≤4 条 + 新回合;`/history` 起点为「此前的回合未被记录」。
6. 停应用,`export <新的空目录>` → 对账报告;对**第 5 步未动过的档**逐个 `cmp` 源副本与导出结果,必须全部相等;
   第 5 步走过一回合的那一局预期不等(它推进了),单独记。
7. 以默认 profile、落盘目录指向导出目录启动 → 启动日志「载入 N 档」;续局刚才那一局,回合号 = 第 5 步之后的回合号(回退不丢切库后进度的实证)。
8. **D-1 拒启演练**:以 `pg` profile 启动、连接指向一个**不可达**的库 →
   (b) 进程非零退出、日志有原因行(不含密码);(c) 启动日志无 `FileSessionStore` 装配行、存档目录下文件的 mtime 与个数不变;
   (a) 若在 Fly 机器上做:用 `fly machine status` 读出实际 restart policy 与重试上限并记录;只在本地做则 (a) 记「未在 Fly 上验证」。
9. **D-5 观测**:第 5 步运行中取一次进程 RSS 与启动日志里的池大小,记为「演练观测值(单次,非压测)」。
10. 销毁容器与临时目录;报告(不含存档内容)入 ROADMAP 记录。**演练通过**的判定照「已决 · D-2」:
    第 3 步退出码 0 且三项计数与源目录清点一致;任何非零退出都不算通过。

---

## 七、非目标

- **线上切换**;在 Fly 上创建任何数据库或 secret;改 `fly.toml`。
- **双写**(与 ADR-025「不双写」冲突,须另立 ADR,底稿 P-8)。
- **多实例 / 高可用**。
- **改变默认 profile**(仍为文件存储)。
- 导入 / 导出叙事历史与受理记录之外的东西(月账、轨迹,见 §1.4);导出 `game_event` / `turn_request` 到任何文件格式。
- 修改 ADR-025 刀 4 原文、ADR-016、ADR-031 的任何立字;`schemaVersion`(保 "0.4")与引擎 / 校验 / golden / prompt。

---

## 八、待决 → 已处置(2026-10-12 定稿)

- **D-1** → 已决:拒启(「已决 · D-1」)。
- **D-2** → 已决:部分成功语义 + 只有读回不一致才整批回滚(「已决 · D-2」);已存在且不同的导入行 → 非零退出、不整批回滚(Felix 2026-10-12 确认)。
- **D-3、D-4** → 移入「切库前检查清单」,未验证,不是本 ADR 的交付。
- **D-5** → 已决:池 5、env 可覆盖、启动日志(「已决 · D-5」)。

---

## 挂账:线上切库

**三条同时满足才考虑**(与「已定」第 3 条同义,在此列为挂账的解冻条件):

1. 真实使用量或查询需求使文件存储不够用;
2. Felix 能承担持续费用或运维责任(参考:Fly Managed Postgres Basic **$38/月起** + 存储 $0.28/GB·月,校勘 2026-10-12 核实);
3. 导入与回退演练均通过(按「已决 · D-2」的通过定义:退出码 0 且三项计数与源目录清点一致)。

解冻时回到 ADR-025 刀 4 的流程;「切库前检查清单」各项须先由「未验证」变为有读数。

---

## 对外口径

- 简历 / 对外表述**只用**:「PostgreSQL + Flyway 在 pg profile 下实现和验证(含导入 / 回退演练)」。
- ⚠️ **明文禁止**表述为「已完成生产迁移」「线上用了 PostgreSQL」「生产已切库」一类。
- 「含导入 / 回退演练」**只在刀 3 演练按「已决 · D-2」通过之后**才可以说;在那之前只能说「在 pg profile 下实现与测试」。
- ⤷ 2026-10-14:前提已满足(Felix 真实演练按「已决 · D-2」通过),自 2026-10-14 起可以写「PostgreSQL + Flyway 在 pg profile 下实现和验证(含导入 / 回退演练)」。
  仍然不许写:已完成生产迁移 / 线上用了 PostgreSQL / 生产已切库。

---

## 测试面(实现时加,本 ADR 只列;每条做变异校验,须能单独失败)

1. **`json` 列原样保存**:含多余空白、特定键序、非 ASCII 字符的快照入库后 `snapshot::text` 逐字节相等。
2. **导入读回比对**:置顶两个陷阱(`phaseHint=SETTLING`、旧局无局面键)入库后逐字节相等;变异「入库前先 decode→encode」→ 本条红。
3. **三分判据共用**:非存档跳过、坏 JSON 拒载、decode 拒载;变异「导入另写一份 `world` 判据」→ 源码级断言红(判据唯一一份在 `SessionDocument`)。
4. **对账读数**:`imported / skipped / integrity_failed` 三个计数 + 不可读名单 + `import` 行数前后值;任一对不上即非零退出;
   有不可读档时非零退出**且**可读档已入库。
5. **幂等四格**(§1.3 表):尤其「已有 `native` 行 → 不覆盖、计入 `skipped`、该行字节不变」;
   「已存在且不同」→ 非零退出、其余可读档已入库、该行字节不变。
6. **往返**:文件 → pg → 文件,被导入档逐字节相等;回退后以默认 profile 回载并续局一回合。
7. **整批原子(D-2 故障测试)**:注入第 k 档读回不一致 → 非零退出、`integrity_failed = 1`、库中本批写入零残留
   (`import` 行数 = 导入前值);变异「每档单独提交」→ 本条红。
8. **月账与轨迹不进库**:导入后 `game_session` 无 `quota-*` 行;`traces/` 下文件不被读取。
9. **导出安全**:非空目标目录拒绝;非法 `save_id` 拒写;载不动的行照样导出并列出。
10. **路径闸门**:真实模式下输入 / 输出在仓库内或 web 根下 → 拒绝运行。
11. **`DATABASE_URL` 解析**:§4.3 全部情形;错误信息不含密码(断言日志 / 异常文本)。
12. **来源优先级**:两者并存时 `SPRING_DATASOURCE_URL` 胜出。
13. **启动语义(D-1)**:DB 不可达 → 上下文启动失败(非零退出)、原因日志不含密码、`FileSessionStore` 未被装配、
    存档目录未被读写;变异「捕获连接失败后继续启动」→ 本条红。
14. **连接池(D-5)**:未设 env 时池大小 = 5,设 env 时取 env 值;启动日志打的是最终值(用非默认值测,防「打字面量」假绿)。
15. **`requireOutsideRepo` 迁移**:移到 `src/main` 后 `TraceFixtureConverter` / `TraceRecheck` 既有用例照绿,导出工具调用同一方法。
16. **默认 profile 不受影响**:既有 `DefaultProfileNoDatabaseTest` 等照绿;默认 profile 下工具类不被装配。

## 实施计划

- **刀 1 · 连接配置 + 启动语义 + 连接池 + 共用重构**:`DATABASE_URL` / `SPRING_DATASOURCE_*` 优先级(测试面 11–12);
  启动语义测试钉住 D-1 = 拒启(测试面 13);Hikari 池 5 + 启动日志(测试面 14);`requireOutsideRepo` 移到 `src/main`(测试面 15);默认 profile 不受影响(测试面 16)。
- **刀 2 · 导入 / 导出离线工具 + 往返**:§一、§二、§三(测试面 1–10);**D-2 的事务边界说明写进代码注释,故障测试为验收项**(测试面 7);
  **同刀在 ADR-025 刀 4 下加带读数的注记**(定稿这一刀只加了一行指针)。
  ⤷ 刀 2 已实现(2026-10-13,分支待合并):测试面 1–10、16 落在 `StoreMigrationToolTest` / `StoreMigrationToolGateTest` /
  `StoreMigrationToolContextTest` / `DefaultProfileHasNoMigrationToolTest`;退出码见「已决 · D-2」;入口见 §三。
- **刀 3 · runbook 演练**:§六 步骤落进 runbook,含 D-1 的重启策略核对与「未回退文件存储」验证;**Felix 本地演练记录**
  (只贴报告,不贴存档);ROADMAP 记录。
  ⤷ 2026-10-14:runbook = [`docs/pg-cutover-drill.md`](../pg-cutover-drill.md)(runbook §八 指针)+ `scripts/pg-drill-compare.sh`;
  CC 用合成存档按文档跑了第 1–8 步并全部按判定通过(记录在该文件末尾;mock 降级回合;第 9 步无 fly 权限未做)。
  ⚠️ **这不是「导入 / 回退演练通过」**:「对外口径」那句仍要等 Felix 真实演练按「已决 · D-2」通过;D-1 (a) 仍「未在 Fly 上验证」。
  ⤷ 2026-10-14:Felix 真实演练通过,读数见 [pg-cutover-drill.md](../pg-cutover-drill.md) 的「Felix 真实演练记录」。
- 每刀:`schemaVersion` 保 "0.4"、默认 profile 行为逐字节不变、不碰线上、不改 `fly.toml`。

## 已知代价

1. **导入档没有完整历史**:≤4 条由补齐规则补上,之前的回合永远没有;导入后不再走的局没有事件(ADR-025 已决 1 既有后果)。
2. **回退丢历史与受理记录**:§2.2;只保住续局。
3. **库 + 卷两处存储**:切库后月账与轨迹仍在卷上,备份时点不同步(底稿 §四 问题 11)。
4. **演练不等于线上**:Testcontainers 不覆盖 PgBouncer、跨区延迟、512 MB 内存与平台健康检查;这些只在真正解冻时才能验。
5. **工具的事务超时与线上 persist 不同**:工具是离线批处理,超时另设;两者不得共用一个配置项。
6. **D-1 拒启的代价**:`pg` profile 下 DB 持续不可用 = 整站不可用;平台重试用完后机器可能停下(预期行为)。今天线上是文件 profile,不受影响。

## 重新审视的触发条件

- 三条重新切库条件同时满足(已定)→ 回到 ADR-025 刀 4 加注的解冻流程,本 ADR 的工具即其前置代码。
- 存档文档格式变化(`SessionDocument.encode` 增删顶层键)→ 重跑往返测试;若影响原文本入库口径,重审置顶结论。
- 出现第二个需要进库的租户(月账或轨迹)→ 重审 §1.4。
- D-1 拒启在演练或将来线上出现「DB 短暂晚于应用就绪即拒启」的实例 → 重审是否在 A 之上加有界等待(草案候选 C)。

## 跟其他文档的交叉引用

- [切库勘察底稿](../pg-cutover-survey.md):事实来源(§一–§四,P-1–P-10)。
- [ADR-025](ADR-025-narrative-history.md):刀 4 挂账、已决 1(导入不补历史)、代价 5 / 9 / 10;定稿时已加一行指针,刀 2 加带读数的注记。
- [ADR-026](ADR-026-turn-acceptance-record.md):`turn_request` 不导入、不导出。
- [ADR-031](ADR-031-turn-execution-trace-and-offline-replay.md):W-1(轨迹不进库)、W-11(真实数据不进仓库 / 聊天)、刀 5a 的仓库外路径闸门。
- [ADR-016](ADR-016-cost-gate.md):月账留卷上。
- [求职线清单](../backlog-career-track.md):简历口径按「已定」第 4 条与「对外口径」。
