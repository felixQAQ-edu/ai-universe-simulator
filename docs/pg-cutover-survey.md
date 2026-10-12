# 切库勘察底稿 · 线上从文件存储切到 PostgreSQL

> **性质**:事实底稿,不是方案,不是 ADR。只写事实与问题,不写推荐、不排序。
> 截至 `main@357b5a3`(2026-10-12;线上同为 `357b5a3`,见 ROADMAP 部署记录)。出处写 `文件:行号`,行号以写成时为准、会漂。
> 推断标「**推测**」;没能核实的标「**未证实**」。路径前缀 `server/…/` = `server/src/main/java/com/aiuniverse/server/`。
> 形态照 [层 3.2 勘察底稿](tool-calling-survey.md)。本次只读,未动代码 / 配置,未碰线上。

---

## ⚠️ 置顶

1. **ADR-025 刀 4 挂账的理由今天仍成立**:月费比较(托管库起价 vs 月预算)那一条没有变化;刀 4 解冻前须先做的
   三项代码(快照导入工具 / 启动时 DB 不可达行为核实 / runbook 切库一节)**一项都还没做**(§一 问题 3)。
2. **今天的代码不能「只换 profile」就切过去**:`pg` 下启动回载只读库(`JdbcSessionStore.loadAll`),
   文件卷上的存档不会被看见;而把文件存档写进库、来源标 `import` 的那段代码**不存在** ——
   全 `server/src/main/java` 里 `'import'` 只在读侧出现(`server/…/persistence/HistoryPages.java:21`),
   没有任何写 `source='import'` 的路径(§三 问题 8)。
3. **pg 下仍有两个租户留在卷上**:配额月账(`QuotaService` 无 `@Profile`,`server/…/quota/QuotaService.java:48`)
   与回合轨迹(`TraceSinkConfig` 两个 profile 一样装,`server/…/persistence/TraceSinkConfig.java:18`)。
   **切库不等于撤卷**(ADR-025 已知代价 5 已写)。
4. **第 4 项 Fly 官方文档未能直接打开**:本会话出口对 `fly.io` 返回 `403`(代理拒绝,2026-10-12 实测
   `curl` → `CONNECT tunnel failed, response 403`;`WebFetch` → `ENOTFOUND fly.io`)。§二 问题 4 里的数字
   **只来自 fly.io 域内搜索结果的摘要**,原页正文本会话未读到 —— 一律标「**未证实(检索摘要读数)**」,开通前须由 Felix 打开原页核对。

---

## 一、`pg` profile 今天已经有什么

### 问题 1 · 哪些存储在 `pg` 下有实现

| 存储 | 默认 profile | `pg` profile | 表 / 迁移 |
|---|---|---|---|
| 存档(会话快照) | `FileSessionStore`(`@Profile("!pg")`,`persistence/FileSessionStore.java:61`) | `JdbcSessionStore`(`@Profile("pg")`,`persistence/JdbcSessionStore.java:50`) | `game_session`,`V1__narrative_history.sql` |
| 叙事历史(读) | `UnavailableHistoryReader`(恒 501,`persistence/UnavailableHistoryReader.java:11`) | `JdbcNarrativeHistoryReader`(`persistence/JdbcNarrativeHistoryReader.java:45`) | `game_event`,V1 |
| 叙事历史(写) | 无 | `JdbcSessionStore.persist` 同一事务补齐事件(`JdbcSessionStore.java:84-118`) | `game_event`,V1 |
| 回合受理记录 | `TurnLedger.NOOP`(`persistence/TurnLedgerConfig.java:15`) | `JdbcTurnLedger`(`persistence/JdbcTurnLedger.java:33`) | `turn_request`,`V2__turn_request.sql` |
| 配额月账 | `quota-YYYY-MM.json` 文件 | **同左,仍走文件**(`QuotaService` 无 profile;`quota/QuotaService.java:74,194`) | — |
| 回合轨迹 | `FileTraceSink`(`/data/traces/<saveId>.trace.jsonl`) | **同左,仍走文件**(ADR-031 已决 W-1:不做仅 pg 落点) | — |
| `llm_call` | 无 | **无**(ADR-026 已决 G:不做,直到 ADR-025 刀 4 解冻) | — |

- Flyway 版本:V1(`game_session` + `game_event`)、V2(`turn_request`)。两张迁移只在 `pg` 下执行
  (默认 profile 排除 `DataSourceAutoConfiguration` / `FlywayAutoConfiguration`,`application.yml` `spring.autoconfigure.exclude`;
  `application-pg.yml` 把排除清空、`spring.flyway.enabled: true`)。
- 快照列是 PG `json` 不是 `JSONB`(V1 注释:JSONB 重排键序会破坏 restore 往返逐字节)。
- 快照内容 = `SessionDocument.encode`,**与文件实现同一份文档**(`persistence/SessionDocument.java:19-24,38-48`)。

### 问题 2 · 测试覆盖与已知行为差异

**Testcontainers 用例**(`@Test` / `@ParameterizedTest` 注解计数,均 `@ActiveProfiles("pg")`,`server/src/test/java/…/persistence/`):

| 类 | 注解数 | 覆盖 |
|---|---|---|
| `PgContainerSmokeTest` | 1 | 容器起来、`select 1` |
| `JdbcSessionStoreTest` | 12 | 装配 + 迁移、turn 0、往返逐字节、降级叙事、失败回滚、补齐、`source` 不被覆写、坏行拒载 |
| `JdbcSessionStoreTimeoutTest` | 2 | DB 挂死时 persist 有界返回 |
| `JdbcNarrativeHistoryReaderTest` | 16 | 历史读、两种缺口(`native` / `import` 行由测试直接插入)、分页、消毒、REPEATABLE READ、`capabilities.history` |
| `JdbcNarrativeHistoryReaderTimeoutTest` | 2 | 读历史有界返回 |
| `JdbcTurnLedgerTest` | 11 | 受理 / 落地 / 降级 / 失败 / 启动收口 `INTERRUPTED` / 唯一索引 |

- CI 上无 Docker 时**不跳过而是失败**(`DockerGate`,`server/src/test/…/persistence/DockerGate.java`)。
- **未覆盖**(据测试方法名逐个读过,未发现):① 启动时 DB 不可达;② 文件存档 → 库的导入(代码不存在);
  ③ 库 → 文件的回退导出(代码不存在);④ PgBouncer 后面跑 Flyway / JDBC。

**已知行为差异(默认 vs `pg`)**:

1. 回载汇总口径不同:文件实现「载入 / 跳过非存档 / 拒载」三项,pg 实现只有「载入 / 拒载」(`JdbcSessionStore.java:160-182`;ADR-025 已知代价 1)。
2. 开场叙事:pg 下作 `game_event` turn 0 入库;文件实现不存(`SessionDocument.java:26-27`)。
3. `GET /api/game/{saveId}/history`:默认恒 501;pg 下可读。`GET /api/archetypes` 的 `capabilities.history` 随之 false / true
   (`web/ArchetypeController.java:32`)。
4. `/actuator/health`:pg 下关掉 db 健康指示器(`application-pg.yml` `management.health.db.enabled: false`;ADR-025 已知代价 10)
   —— DB 挂了 health 仍报 UP。
5. persist 一致性:文件 = tmp + `ATOMIC_MOVE`;pg = 快照与事件同一短事务(`JdbcSessionStore.java:23-35`)。
6. 受理记录:默认 `NOOP`;pg 下有 `turn_request` 行与启动收口(`JdbcSessionStore.java:138-152`)。
7. 超时:pg 下 `connection-timeout 1000` / `socketTimeout 2` / `cancelSignalTimeout 1` / `connectTimeout 1` / 事务 2 s,
   依据写在 `application-pg.yml` 注释里(DB 挂死时 persist 实测阻塞 ≈ 3 s)。

### 问题 3 · ADR-025 刀 4 当初挂账的原文理由,今天是否成立

原文(ADR-025「实现分刀 · 刀 4」加注,2026-09-27):

> 本 ADR 的目标是求职可讲,刀 1–3 已把功能与测试做完整……而托管库的月费**高于整个模型月预算**
> (API 月预算心理线 ¥200、ADR-016 月闸 ¥175;A 档起价 $38/月),对一件作品不划算。

逐条对照今天:

| 理由 / 前置 | 今天 | 依据 |
|---|---|---|
| 托管库月费高于模型月预算 | **仍成立**(检索摘要读数:Basic 仍为 $38/月,未证实) | §二 问题 4 |
| 解冻条件 = Felix 决定上线「回看上局」**并**选定 A / B | **未触发**(无此裁定记录) | ADR-025 刀 4 加注 |
| 前置代码 1:快照导入工具 | **未做**(无写 `import` 的代码) | 置顶 2 |
| 前置代码 2:启动时 DB 不可达行为核实 | **未做**(无对应测试) | 问题 2 未覆盖 ① |
| 前置代码 3:runbook 切库一节 | **未做**(runbook 无切库章节;§七 只讲轨迹) | `docs/phase3-fly-deploy-runbook.md` |

另:线上本月模型累计支出的最近读数是 2026-09-28 的 ¥4.05(ROADMAP ADR-027 收口)—— 实际支出远低于 ¥175 月闸;
月费比较若改用「实际支出」而不是「预算」做分母,差距更大。这是事实陈述,不是结论。

---

## 二、线上要切需要什么

### 问题 4 · 数据库从哪来

> 查阅日期 2026-10-12。fly.io 原页本会话打不开(见置顶 4),下列数字与描述**全部来自 fly.io 域内搜索结果摘要,未证实**。

| 形态 | 摘要读数 | 官方链接 |
|---|---|---|
| A · Fly Managed Postgres(MPG) | 计划 Basic $38/月(shared-2x、1 GB RAM)起;存储另计 $0.28/GB·月;高可用、备份、连接池(PgBouncer)含在内;自动备份保留 10 天,`fly mpg restore` 时间点恢复**建一个新集群**;区域列表含 Sydney;同区流量免费 | https://fly.io/docs/mpg/ 、https://fly.io/mpg/ 、https://fly.io/docs/about/pricing/ |
| A · 接入方式 | `fly mpg attach <clusterID> -a <app>` 给 app 写一个名为 `DATABASE_URL` 的 secret 并触发一次部署;连接串分 pooled(`pgbouncer.` 主机)与 direct(`direct.` 主机);**迁移(advisory lock)须走 direct URL** | https://fly.io/docs/mpg/create-and-connect/ 、https://fly.io/docs/mpg/client-configuration/ |
| B · 自管 Fly Postgres(`fly postgres create`) | 文档页标题即「This Is Not Managed Postgres」:Fly 不提供支持;备份只有卷的每日快照(保留 5 天),异地备份、监控、升级、故障恢复由用户自理 | https://fly.io/docs/postgres/getting-started/what-you-should-know/ 、https://fly.io/docs/postgres/managing/backup-and-restore/ |
| B · 价格 | **未证实**(摘要未给数字;取决于所开机器与卷的规格,另计) | https://fly.io/docs/about/pricing/ |

- 与当前单机 syd 的关系:app 在 `primary_region = "syd"`(`fly.toml`)。ADR-025 已知代价 6 要求 DB 同区(否则每回合一次跨区往返叠进名额占用)。
  MPG 区域列表含 Sydney(摘要读数,**未证实**);ADR-025 刀 4 加注里引的社区帖称「悉尼集群备份存新加坡」—— **未证实**,非官方文档。
- Fly 2026-10-01 起机器内存涨价(ADR-025 刀 4 加注引 https://fly.io/pricing-update/),对 B 的影响**未证实**。

### 问题 5 · 连接配置、密钥、连接池、启动时 Flyway

- **连接配置**:`application-pg.yml` 只写超时,**不写任何地址**;注释说明连接串走 Spring 标准项
  `spring.datasource.url / username / password`,可由 `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` /
  `SPRING_DATASOURCE_PASSWORD` 环境变量注入。profile 由 `SPRING_PROFILES_ACTIVE=pg` 打开。
- **与 MPG attach 的落差**:attach 写的是 `DATABASE_URL`(摘要读数);代码里**没有任何地方读 `DATABASE_URL`**
  (全仓 `server/src/main` 检索无命中)。Spring Boot 不会自动把 `DATABASE_URL` 映射到 `spring.datasource.url` —— **推测**
  (Spring 默认行为;本会话未对本项目实测)。`postgres://…` 形式的 URL 能否直接作 JDBC URL 用 —— **推测不能**
  (pgjdbc 要求 `jdbc:postgresql://` 前缀),未证实。
- **fly secrets 名**:今天线上 secret 只有 runbook 里的 `DEEPSEEK_API_KEY` / `AIUNIVERSE_LLM_ACTIVE` 一类;
  DB 相关 secret **一个都没有**。切库需要的名字取决于问题 6 的开放问题(用 `SPRING_DATASOURCE_*` 还是代码适配 `DATABASE_URL`)。
- **连接池**:HikariCP(`spring-boot-starter-jdbc` 自带)。`application-pg.yml` **没有设 `maximum-pool-size` / `minimum-idle`**,
  故取 Hikari 默认 —— 默认值为最大 10、最小空闲 = 最大(Hikari 文档默认,**本会话未对本项目实测**)。
  MPG 前面有 PgBouncer(摘要读数);PgBouncer 池模式与 JDBC / Flyway 的兼容**未证实**,摘要只说迁移应走 direct URL。
- **启动时 Flyway**:`spring.flyway.enabled: true`,Flyway 自动配置在应用启动时跑 `migrate`。
  DB 不可达时:**推测启动失败、服务起不来**(Flyway 拿不到连接即抛,Spring 上下文中止),而不是走
  `JdbcSessionStore.loadAll` 那条「读失败 → 以空档启动」分支(`JdbcSessionStore.java:167-171`)。
  这一推测与 ADR-025 刀 4 待办原话一致,**仍未测试**。后果推测:单机 + 健康检查(`fly.toml` `/actuator/health`,
  `grace_period = "45s"`)→ 机器不健康 / 重启循环,整站不可用 —— **未证实**。
- **迁移连接**:Flyway 默认用同一个 DataSource;若 `spring.datasource.url` 指向 PgBouncer,与摘要「迁移须走 direct」相冲突 —— 是否真冲突**未证实**。

### 问题 6 · 512 MB 单机下引入 JDBC 连接池的内存影响

能核实的只有这些:
- 机器 `shared-cpu-1x` / `512mb`(`fly.toml` `[[vm]]`);启动命令 `java -jar app.jar`,**无 `-Xmx` / `MaxRAMPercentage`**(`Dockerfile` `ENTRYPOINT`)。
- runbook 记「512MB 下 JVM 默认堆 ~128MB,理论够用」(`docs/phase3-fly-deploy-runbook.md:344-345`)—— 那是估算,非实测。
- 连接池默认 10 条物理连接(见问题 5)且不在 yml 里覆盖。
- JDBC / Flyway / Hikari / pgjdbc 的 jar **今天已经在镜像里**(`server/pom.xml:54-71`,依赖不分 profile);默认 profile 下只是不初始化。
- 每条连接的 JVM 侧内存、池对 RSS 的增量:**未证实**(仓库内无任何读数;从未在 512 MB 机器上跑过 pg profile)。

---

## 三、数据迁移

### 问题 7 · 线上卷 `/data` 上现有什么

最近一次有记录的读数(启动回载日志,不是 `ls`):
- 2026-09-28 部署 `54bcb0e`:**载入 145 档 / 跳过 3 个非存档文件 / 0 档拒载**(ROADMAP、ADR-027 实际效果)。
  跳过的 3 个推测 = `quota-2026-07/08/09.json`,未证实;10 月起推测再多一份 `quota-2026-10.json`。
- 2026-10-12 部署 `357b5a3` 的启动回载读数:**未入档**(本会话看不到线上日志)。
- `/data/traces/`:ADR-031 刀 3 上线(2026-10-08 `681721a`)后开始写;文件数与总量**没有任何入档读数**。
- ⚠️ 档数是**文件数**,不是「有效档」(工程债「简历素材」与 ADR-025 已决 3 的分母纪律)。

Felix 可执行的只读命令(不改任何东西):

```sh
fly ssh console -C 'sh -c "ls /data/*.json | grep -v /quota- | wc -l"'   # 存档文件数
fly ssh console -C 'sh -c "ls -la /data/quota-*.json"'                   # 配额月账
fly ssh console -C 'du -sh /data /data/traces'                           # 总量与轨迹量
fly ssh console -C 'sh -c "ls /data/traces | wc -l"'                     # 轨迹文件数
fly logs | grep 'session-store\] 启动回载'                                  # 最近一次启动回载汇总(需当场挂着)
```

### 问题 8 · 存档 → 数据库的映射

- **快照一一对应**:`game_session.snapshot` 存的就是 `SessionDocument.encode` 的整份文档,与 `<saveId>.json` 是**同一个函数产的同一份 JSON**
  (`SessionDocument.java:19-24`);`turn` / `status` 两列是从 Engine 读出的冗余列(`JdbcSessionStore.java:94-95`)。
  文件里有、pg 侧没有落点的字段:**无**(整份文档原样进 `json` 列)。
- **pg 侧多出、文件里没有的**:`source`(`native` / `import`)、`created_at` / `updated_at`、整张 `game_event`、整张 `turn_request`。
  - `game_event`:文件存档里只有 `state.log` 末 ≤ `LOG_KEEP = 4` 条(`engine/Engine.java:39`),更早的叙事已被 `compressLog` 丢弃;
    开场叙事不在文件里。导入档的历史由「补齐规则」在**第一次 persist 时**自动补进 ≤4 条(ADR-025 采纳时补记),
    更早的是「记录开始之前」(ADR-025 已决 1)。
  - `turn_request`:文件里无对应;导入档开局没有受理行。
- **旧局(无局面键)**:`SessionDocument.decode` 对有无局面键都处理(`SessionDocument.java:76-77`,`BoxSceneState.restoreFor`);
  快照原样进库后回载走同一个 `decode` —— **推测可原样迁移**,无专门测试。
- **导入代码不存在**(置顶 2)。ADR-025 代价 9 已写死导入须走与文件回载同一套判据(文档形状三分 + `Engine.restore` 全量校验)、
  对账读数(N 份 → N 行)、时序(最后一次文件写入之后)。
- **配额月账**:不进库(ADR-025 测试面「quota 月账不被导入」),留卷上。**轨迹**:不进库(ADR-031 W-1),留卷上。

### 问题 9 · 迁移期间的玩家影响

- **停机窗口**:单机单副本(`fly.toml`、`--ha=false`),切 profile = 一次部署 = 进程重启;重启期间不可用。窗口时长**未证实**(无读数;
  冷启动参照 health `grace_period = "45s"`)。导入若须在「最后一次文件写入之后、新进程首次写库之前」进行,
  窗口里还要加上导入本身的时长 —— 导入工具不存在,时长**未证实**。
- **进行中的回合**:部署杀进程 → 在途回合按 ADR-015「崩溃回滚一回合是特性」处理(盘上停在最后一个完整回合)。
  切库那一次,「盘上」= 文件;pg 下 `turn_request` 此时还没有行。
- **续局**:若不导入,切换后所有未完的局续局拿 404,前端**静默清掉指针**,无报错(ADR-025 已知代价 9)。
  客户端游标(ADR-023 / ADR-027)比对的是内存回合号,导入后与文件一致则不受影响 —— **推测**。
- **旧页签**:切换后 `capabilities.history` 变 true,前端选择屏开始探测 `/history`(2026-10-09 能力位)。

---

## 四、回滚与安全网

### 问题 10 · 切过去之后回退到文件存储

- 两个实现**互斥装配、不双写**(`FileSessionStore.java:61` 注释:「pg profile 下由 JdbcSessionStore 接管,不双写」)。
- 回退 = 去掉 `pg` profile 再部署 → `FileSessionStore` 读卷上的 `*.json`。卷上那些文件**停在切库那一刻**:
  切库之后在 pg 里推进的回合、新开的局,文件里都没有。
- **库 → 文件的导出代码不存在**(全仓无)。
- 今天的代码**能在两种存储间切换启动**(profile 二选一,且同一份文档格式);**不能在切换时带走对方那一侧切换后产生的数据** —— 由上两条推出,无测试。
- 若保留卷上的旧文件不删,回退时至少能回到「切库那一刻」的状态 —— **推测**;切库之后新开的局会丢。

### 问题 11 · 备份

| | 今天(文件) | 切库后(A · MPG) | 切库后(B · 自管) |
|---|---|---|---|
| 谁备份 | Fly 卷每日快照(默认保留 5 天,可设 1–60 天;**检索摘要读数,未证实**) | Fly(摘要:自动备份、保留 10 天,**未证实**) | 用户自理;卷每日快照(摘要,**未证实**) |
| 怎么恢复 | 从快照新建卷(摘要,**未证实**);仓库 runbook **无恢复步骤** | `fly mpg restore` 新建集群(摘要,**未证实**) | 用户自理 |
| 卷 `wanjie_data` 的快照保留设置 | runbook 的建卷命令未带 `--snapshot-retention`(`phase3-fly-deploy-runbook.md:36`);现值**未证实** | — | — |

- 今天「卷上就是全部数据」:存档 + 月账 + 轨迹在一个卷上。切库后数据分布到 **库(存档 / 历史 / 受理)+ 卷(月账 / 轨迹)** 两处,
  两边的备份时点不同步 —— 由形态推出;恢复时两边对不上的后果**未证实**(月账与存档本就无引用关系,推测影响小)。
- 摘要:Fly 文档称数据频繁变化时每日快照不宜作唯一备份(https://fly.io/docs/volumes/snapshots/ ,**未证实**)。

---

## 五、切库之后会解锁什么(只列,指向原文)

- **回看上局对玩家可用**:ADR-025 刀 2/刀 3 已合并,`capabilities.history` 在 pg 下为 true → 选择屏出「回看上局」([ADR-025](adr/ADR-025-narrative-history.md) 刀 4 解冻条件)。
- **回合受理记录在线上生效**:`turn_request` 的 `INTERRUPTED` 收口等([ADR-026](adr/ADR-026-turn-acceptance-record.md) 决策 1–4)。
- **`llm_call` 解冻**:ADR-026 已决 G「不做,直到 ADR-025 刀 4 解冻」;顺带是「成本闸门少算被中止的调用」唯一会变可见的地方(ADR-026 已知代价 7)。
- **轨迹落点可重估**:ADR-031 不做仅 pg 落点的理由是「线上无库」(ADR-031 §「线上问题复盘只有文件落点能兑现」/ W-1);切库后该前提变化,**ADR-031 未写切库后的重新审视条目**(未证实其是否需要)。
- **ADR-022「落盘目录分家」挂账**:pg 下卷上租户 = 月账 + 轨迹(子目录),ADR-025 已知代价 5 记为「租户数从 2 变 1」;ADR-031 之后轨迹另占子目录。
- **CONTEXT §三.17**「不引数据库」措辞随切库那一刀收口(ADR-025 刀 4 加注)。
- **求职线对外口径**:「不许说线上用了 PG」那条随之失效([求职线层 2](backlog-career-track.md))。

---

## 六、开放问题(需校勘或 Felix 裁定)

- **P-1** 解冻本身:是否决定上线「回看上局」(ADR-025 刀 4 解冻条件)。今天的月费比较是否需要用实际支出(¥4.05/月量级,2026-09-28)重算。
- **P-2** A(MPG)还是 B(自管)。A 的价格、Sydney 可开、备份存放地,须 Felix 打开官方原页核实(本会话打不开)。
- **P-3** 连接串注入方式:直接配 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 三个 secret,还是改代码读 MPG attach 写的 `DATABASE_URL`(须格式转换)。
- **P-4** 迁移走 direct URL、业务走 pooled URL 是否必要(Flyway advisory lock 与 PgBouncer 模式);若必要,Flyway 需要单独的连接配置(`spring.flyway.url` 等),今天没有。
- **P-5** 启动时 DB 不可达的期望行为:起不来(今天推测的行为)还是降级为「无存档启动」;与健康检查 / 重启循环一起定(ADR-025 刀 4 待办 2)。
- **P-6** Hikari 池大小是否显式设(今天吃默认 10);512 MB 下是否先测一次 pg profile 的 RSS。
- **P-7** 快照导入工具的形态:启动时一次性导入、独立命令、还是 `fly ssh` 内执行;对账读数写到哪里。
- **P-8** 回退策略:接受「回退丢切库后数据」,还是要导出工具 / 双写(双写与 ADR-025「不双写」冲突,须另立 ADR)。
- **P-9** 卷 `wanjie_data` 的快照保留天数现值(未核),以及切库后谁负责两处备份的对齐。
- **P-10** `capabilities.history` 一旦为 true,`/history` 对所有续局玩家开放 —— 历史页的范围(只对本人上局)是否需要再看一遍(今天单 saveId 指针,未见问题,**未证实**)。
