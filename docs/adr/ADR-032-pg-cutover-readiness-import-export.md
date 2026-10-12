# ADR-032 · 切库准备:文件存档与数据库之间的导入 / 回退导出、连接配置、启动语义与演练(不执行线上切换)

- **日期**:2026-10-12
- **状态**:**提议**(草案,待校勘与 Felix 过目;实现未起)
- **决策者**:Felix
- **前提**:`main@c666853`。依据 [切库勘察底稿](../pg-cutover-survey.md)(截至 `357b5a3`,下称「底稿」)、
  [ADR-025](ADR-025-narrative-history.md)(叙事历史,刀 4 挂账)、[ADR-026](ADR-026-turn-acceptance-record.md)、
  [ADR-031](ADR-031-turn-execution-trace-and-offline-replay.md)(W-1 / W-11)、[ADR-016](ADR-016-cost-gate.md)(月账)。
- 路径前缀 `…/` = `server/src/main/java/com/aiuniverse/server/`。行号以 `c666853` 为准,会漂。
  推断标「**推测**」,没核实的标「**未证实**」。
- **本 ADR 不写代码**;只定导入 / 导出 / 配置 / 启动语义 / 演练的形状与测试面,实现另起刀。

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

> 与底稿的对照:底稿 §二 问题 4 的 Fly 数字标「未证实(检索摘要读数)」,上面的价格已由校勘另行核实,二者一致($38 / $0.28);
> 底稿与已定**无冲突**。

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
| JSON 解析不了 | 疑似坏档 | **拒载**,逐份列出(文件名 + 原因) |
| 无 `world` 对象节点(`SessionDocument.isSaveDocument`,`SessionDocument.java:57-59`) | 非存档(月账等) | **跳过**,计数,不报错 |
| 是存档,`SessionDocument.decode` 抛(`SessionDocument.java:65-80`) | 拒载 | **拒载**,逐份列出 |
| decode 成功 | 合法 | 入库 |

- **旧局(无局面键)**:decode 对有无局面键都处理(`SessionDocument.java:77`),按上表走「合法」分支,**原始文本入库,不补旧局标记**
  (标记由切库后的第一次 persist 写入,与文件 profile 下的行为一致)。
- **拒载是否让整批失败 → 待决 D-2**(见 §八)。提议:**不让整批失败**,理由 = 今天文件 profile 下这些档同样载不进来
  (`FileSessionStore.java:183-186`),导入不改变玩家可见状态;但拒载数 > 0 时工具以非零退出码结束,强迫人看清单。

### 1.3 原子性、校验与幂等

- **一批一个事务**:全部合法存档在同一事务内插入;读回比对也在提交前完成。任一档比对不一致 → **整批回滚**,
  报告哪一档、第一个不同字节的偏移。底稿 §三 问题 7 的最近读数是 145 档(2026-09-28),单事务体量**推测**无压力(未实测)。
- **读回比对(核心校验)**:`SELECT snapshot::text … WHERE save_id = ?` 与源文件 UTF-8 字节逐字节相等;
  另比 `turn` / `status` 两列与 decode 读数相等。理由见置顶。
- **对账读数(ADR-025 代价 9 原话要求)**:报告必须给出 `候选 N / 非存档 M / 拒载 K(逐份) / 入库 N−M−K / 库中 source='import' 行数`,
  并断言后两者相等。不许只输出「导入成功」。
- **幂等性(重复导入同一目录)**:按 `save_id` 逐档判定 ——

  | 库中已有该 `save_id` | 快照文本 | 行为 |
  |---|---|---|
  | 无 | — | 插入 |
  | 有,`source='import'` | 与源文件逐字节相同 | **无操作**,计入「已存在且一致」 |
  | 有,`source='import'` | 不同 | **整批失败**(源目录在两次导入之间变了,说明导入不在「最后一次文件写入之后」) |
  | 有,`source='native'` | 任意 | **整批失败**(库里已有本 profile 下推进过的局,导入不得覆盖) |

  即:**导入从不覆盖库里已有的行**。重复跑同一份目录的结果与跑一次相同。

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
- **入口(提议,待实现刀定)**:同一个 jar 内的独立 `main` 类(如 `…/persistence/migrate/StoreMigrationTool`),两个子命令
  `import <dir>` / `export <dir>`;以 Spring 非 web 上下文运行(`spring.main.web-application-type=none`),
  **只装配 DataSource + Flyway + `ArchetypeRegistry` + `ObjectMapper`**,不装配 `GameSessionManager`
  (否则会触发 `@PostConstruct reloadFromStore()` 与启动收口,`…/eventloop/GameSessionManager.java:50-51`、
  `JdbcSessionStore.java:144-152`)。
  - 本地 / CI:`mvn -q exec:java` 或测试内直接调用;线上(若将来解冻):`fly ssh console` 内用 Spring Boot loader
    以 `-Dloader.main=…` 指定入口 —— **未证实**(镜像今天 `ENTRYPOINT ["java","-jar","app.jar"]`,`Dockerfile:37`;
    换主类的具体写法未在本项目实测),**线上运行不在本 ADR 范围**。
- **profile**:以 `pg` profile 运行(需要 `application-pg.yml` 的超时配置与 Flyway)。工具**先跑 Flyway migrate**
  再操作,保证在空库上也能用。⚠️ 工具里的超时:`application-pg.yml` 的 2 s 事务超时是为「persist 在准入名额内」定的
  (`application-pg.yml:11-17,42-47`),整批导入在一个事务里会超过它 → 工具**须用自己的事务超时**(数值待实现刀按演练读数定),
  不得改动线上 persist 的超时。
- **路径规则**:
  - 输入 / 输出路径一律经 `toAbsolutePath().normalize()`,不得位于 static web 根之下(复用 `FileSessionStore.assertOutsideWebRoot`)。
  - **处理真实存档时,输入输出须在仓库之外**(复用 `TraceFixtureConverter.requireOutsideRepo` 同一判定,ADR-031 刀 5a);
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
  未设置且 `DATABASE_URL` 存在 → 解析为 jdbc url + username + password;两者都没有 → 见 §五(按启动语义处理)。
- 启动打一行 INFO 说明**用的是哪个来源、主机与库名**,**不打用户名以外的任何凭据**(形状照 `[turn-admission] 准入容量 N=8`
  的「开机说清现值」)。
- **Flyway 的连接**:底稿 P-4 —— MPG 的迁移须走 direct URL、业务走 pooled URL(摘要读数,未证实)。本 ADR 只预留
  `spring.flyway.url` 可单独设置这一口,**是否需要两条 URL 列为待决 D-4**,不在 D 阶段定。

### 4.3 URI 解析测试(只列)

`postgres://` 与 `postgresql://` 两种 scheme;无端口(默认 5432);用户名 / 密码含百分号编码字符(`@`、`:`、`/`);
无密码;query 参数原样带过(如 `sslmode=require`);IPv6 主机;缺库名 / 非法 URI → 启动失败且**错误信息不含密码**;
两个来源同时存在时 `SPRING_DATASOURCE_URL` 胜出且 `DATABASE_URL` 的密码不被读取。

---

## 五、数据库不可用时的启动语义(**待决 D-1**,不替 Felix / 校勘选)

**事实先行**:今天的行为是**推测**「起不来」(Flyway 在启动时 migrate,拿不到连接即抛,上下文中止),**未测**(底稿 §二 问题 5;
ADR-025 刀 4 待办原话)。本 ADR 的第一刀先把这条推测变成测试读数,再在候选中选。

| 候选 | 行为 | 好处 | 代价 |
|---|---|---|---|
| **A · fail-fast(拒启)** | DB 不可达 → 启动失败、进程退出 | 不会在「看不见存档」的状态下对外服务;行为简单 | 单机单副本(`--ha=false`)下整站不可用;平台健康检查(`grace_period = "45s"`)→ 重启循环(**推测**,未证实) |
| **B · 降级启动** | 跳过 Flyway / 以空档启动(`JdbcSessionStore.loadAll` 读失败分支,`JdbcSessionStore.java:167-171`),persist 逐次失败记 ERROR | 站点仍可打开,新局能玩(只活在内存) | **续局全部 404 → 前端静默清掉存档指针**(ADR-025 代价 9 同一机制)—— DB 恢复后指针已经没了,**数据还在库里,玩家却再也回不去**;与 ADR-025 决策 3「DB 故障不许杀活回合」的出发点相同、结果相反;且 Flyway 未跑时 schema 状态未知 |
| **C · 有界等待后 fail-fast** | 启动时以有限次数 / 有限时长重试连接,仍不可达则退出 | 吸收 DB 晚于应用就绪的短暂窗口(部署时常见) | 等待时长要落在平台健康检查宽限期之内;超出后同 A |

- 三个候选都须配一条**启动日志现值**(用的是哪个连接来源、是否连上、Flyway 当前版本)与一条测试。
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
3. `import <副本目录>` → 读对账报告:候选 / 非存档 / 拒载(逐份)/ 入库 / 库中 `import` 行数,后两者必须相等;退出码。
4. 再跑一次同一 `import` → 报告「已存在且一致」= 上次入库数、新增 0(幂等)。
5. 以 `pg` profile 启动应用连这个库 → 启动日志「载入 N 档,0 档拒载」与第 3 步入库数相等;选一局续局走一回合 →
   该局 `game_event` 出现补齐的 ≤4 条 + 新回合;`/history` 起点为「此前的回合未被记录」。
6. 停应用,`export <新的空目录>` → 对账报告;对**第 5 步未动过的档**逐个 `cmp` 源副本与导出结果,必须全部相等;
   第 5 步走过一回合的那一局预期不等(它推进了),单独记。
7. 以默认 profile、落盘目录指向导出目录启动 → 启动日志「载入 N 档」;续局刚才那一局,回合号 = 第 5 步之后的回合号(回退不丢切库后进度的实证)。
8. 销毁容器与临时目录;报告(不含存档内容)入 ROADMAP 记录。

---

## 七、非目标

- **线上切换**;在 Fly 上创建任何数据库或 secret;改 `fly.toml`。
- **双写**(与 ADR-025「不双写」冲突,须另立 ADR,底稿 P-8)。
- **多实例 / 高可用**。
- **改变默认 profile**(仍为文件存储)。
- 导入 / 导出叙事历史与受理记录之外的东西(月账、轨迹,见 §1.4);导出 `game_event` / `turn_request` 到任何文件格式。
- 修改 ADR-025 刀 4 原文、ADR-016、ADR-031 的任何立字;`schemaVersion`(保 "0.4")与引擎 / 校验 / golden / prompt。

---

## 八、待决

- **D-1 · DB 不可用时的启动语义**:A fail-fast / B 降级 / C 有界等待后 fail-fast(§五)。先由第一刀给出今天行为的实测读数。
- **D-2 · 导入遇拒载档是否整批失败**:提议不失败但非零退出(§1.2);另一选择是「有拒载即整批失败,除非显式允许」。
- **D-3 · 工具的线上运行方式**:同 jar 换主类在 `fly ssh` 内执行是否可行(§三,未证实);D 阶段只要求本地 / CI 可跑。
- **D-4 · Flyway 是否需要单独的 direct 连接**(§4.2,底稿 P-4);D 阶段只预留配置口。
- **D-5 · Hikari 池大小是否显式设**(底稿 P-6,今天吃默认 10;512 MB 单机下 pg profile 的 RSS 从未测过)—— 不阻塞 D 阶段,演练时顺带取一次读数即可决。

---

## 测试面(实现时加,本 ADR 只列;每条做变异校验,须能单独失败)

1. **`json` 列原样保存**:含多余空白、特定键序、非 ASCII 字符的快照入库后 `snapshot::text` 逐字节相等。
2. **导入读回比对**:置顶两个陷阱(`phaseHint=SETTLING`、旧局无局面键)入库后逐字节相等;变异「入库前先 decode→encode」→ 本条红。
3. **三分判据共用**:非存档跳过、坏 JSON 拒载、decode 拒载;变异「导入另写一份 `world` 判据」→ 源码级断言红(判据唯一一份在 `SessionDocument`)。
4. **对账读数**:N / M / K / 入库 / `import` 行数五个数字;任一对不上即非零退出。
5. **幂等四格**(§1.3 表):尤其「已有 `native` 行 → 整批失败、库中零变化」。
6. **往返**:文件 → pg → 文件,被导入档逐字节相等;回退后以默认 profile 回载并续局一回合。
7. **整批原子**:第 k 档比对失败 → 库中 `import` 行数为 0。
8. **月账与轨迹不进库**:导入后 `game_session` 无 `quota-*` 行;`traces/` 下文件不被读取。
9. **导出安全**:非空目标目录拒绝;非法 `save_id` 拒写;载不动的行照样导出并列出。
10. **路径闸门**:真实模式下输入 / 输出在仓库内或 web 根下 → 拒绝运行。
11. **`DATABASE_URL` 解析**:§4.3 全部情形;错误信息不含密码(断言日志 / 异常文本)。
12. **来源优先级**:两者并存时 `SPRING_DATASOURCE_URL` 胜出。
13. **启动语义**:DB 不可达时的实际行为(第一刀记录读数;D-1 定后按所选候选改写断言)。
14. **默认 profile 不受影响**:既有 `DefaultProfileNoDatabaseTest` 等照绿;默认 profile 下工具类不被装配。

## 实施分刀(建议)

- **刀 1 · 启动语义读数 + 连接配置**:先写测试把「DB 不可达时今天怎样」变成读数(测试面 13 的事实部分);
  `DATABASE_URL` 解析与优先级(测试面 11–12);D-1 定了再补启动语义实现。
- **刀 2 · 导入工具**:§一 + §三(测试面 1–5、7、8、10)。
- **刀 3 · 导出工具 + 往返**:§二(测试面 6、9)。
- **刀 4 · 演练 + runbook 草稿落地**:§六;Felix 本机跑一次真实存档,只贴报告;ROADMAP 记录;**同刀在 ADR-025 刀 4 下追加一行注记指向本 ADR**(已定)。
- 每刀:`schemaVersion` 保 "0.4"、默认 profile 行为逐字节不变、不碰线上。

## 已知代价

1. **导入档没有完整历史**:≤4 条由补齐规则补上,之前的回合永远没有;导入后不再走的局没有事件(ADR-025 已决 1 既有后果)。
2. **回退丢历史与受理记录**:§2.2;只保住续局。
3. **库 + 卷两处存储**:切库后月账与轨迹仍在卷上,备份时点不同步(底稿 §四 问题 11)。
4. **演练不等于线上**:Testcontainers 不覆盖 PgBouncer、跨区延迟、512 MB 内存与平台健康检查;这些只在真正解冻时才能验。
5. **工具的事务超时与线上 persist 不同**:工具是离线批处理,超时另设;两者不得共用一个配置项。

## 重新审视的触发条件

- 三条重新切库条件同时满足(已定)→ 回到 ADR-025 刀 4 加注的解冻流程,本 ADR 的工具即其前置代码。
- 存档文档格式变化(`SessionDocument.encode` 增删顶层键)→ 重跑往返测试;若影响原文本入库口径,重审置顶结论。
- 出现第二个需要进库的租户(月账或轨迹)→ 重审 §1.4。
- D-1 选 B 之后若出现「库恢复但玩家指针已被清」的实例 → 重审。

## 跟其他文档的交叉引用

- [切库勘察底稿](../pg-cutover-survey.md):事实来源(§一–§四,P-1–P-10)。
- [ADR-025](ADR-025-narrative-history.md):刀 4 挂账、已决 1(导入不补历史)、代价 5 / 9 / 10;实施时在其刀 4 下追加指向本 ADR 的注记。
- [ADR-026](ADR-026-turn-acceptance-record.md):`turn_request` 不导入、不导出。
- [ADR-031](ADR-031-turn-execution-trace-and-offline-replay.md):W-1(轨迹不进库)、W-11(真实数据不进仓库 / 聊天)、刀 5a 的仓库外路径闸门。
- [ADR-016](ADR-016-cost-gate.md):月账留卷上。
- [求职线清单](../backlog-career-track.md):简历口径按「已定」第 4 条。
