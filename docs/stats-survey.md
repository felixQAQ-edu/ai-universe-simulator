# 统计勘察底稿 · 「有效档」口径与简历指标可算性(E-1)

> 2026-10-14 · 只读勘察(代码与 schema),**只写事实与问题,不写方案,不给推荐**。
> 截至 `main@7c5ab03`。原始存档 / 轨迹没有进入本次勘察(W-11);下文所有「字段有 / 没有」都来自读代码,
> 不是读线上数据。行号随代码漂移,**以方法名为准**。
>
> 背景挂账:[工程债「简历素材」](backlog-engineering-debt.md)的解冻条件「`/data` 有效档 ≥ 50 局,且单局平均回合数 > 5」;
> ROADMAP v5.8 已裁定统计主要消费 `/data` 卷、不碰 `fly logs`。

---

## 1. 存档 JSON 里可用于统计的字段

存档文档 = `SessionDocument.encode`(`server/.../persistence/SessionDocument.java`)
= `Engine.toPersistedState()`(`engine/Engine.java:223`)+ session 层两个键 + 《动物人生》局面键。
文件存储下每局一个 `<saveId>.json`,由 `FileSessionStore.persist` 原子写入(init 一次 + 每个落地回合一次)。

| 字段(文档内路径) | 来源 | 统计上能说明什么 |
|---|---|---|
| `schemaVersion` | `toPersistedState` 取自 `world.schemaVersion` | 世界产出时的 schema 版本(0.2/0.3/0.4),不是代码版本 |
| `world.mode` / `world.archetypes` | world-gen 产出原样落盘(`toPersistedState` 深拷贝 world,只删 `state`) | 单体 / 融合;组合与 host 顺序。融合判定可用 `archetypes` 长度 |
| `world.world.title` 等世界文案 | 同上 | 叙事正文类,**输出纪律禁止出现在报告里**(见 §5) |
| `world.character.attributes` | `toPersistedState` 写回**落账当前值** | 末值,不是历史轨迹 |
| `world.rules[].discovered` | `Engine.markRuleDiscovered` | 本局发现了几条规则 |
| `world.endings[]`(`id` / `outcome` / `reached`) | world-gen 产出 + `Engine.markEndingReached` | 结束局达成了哪条、极性 `success/failure/neutral`(`outcome` 自 schema 0.4 起可选,老档可能没有) |
| `state.turn` | `Engine.apply` / `applyNoOp` 每回合 `turn += 1` | 已落地回合数(**降级回合也 +1**) |
| `state.status` | `ongoing` / `ended` | 是否收束 |
| `state.timeline` | 模型维护的一句话 | 叙事类,禁止出现在报告里 |
| `state.log[]`(`turn` / `narrative` / `playerAction`) | `Engine.apply` 步骤 8、`applyNoOp` | **只留最近 `LOG_KEEP = 4` 条**(`Engine.java:39`) |
| `state.logSummary` | `Engine.compressLog`(`:421`) | 超出 4 条的回合折成 `[T{turn}选{action}]` —— **完整的动作 id 序列可复原,叙事正文不可复原** |
| `triggered[]` | `Engine.apply` 步骤 5 | 触发过的规则 id |
| `issues[]` | `Engine.apply`(跳变 `:278`、结局极性 gate `:322`)+ `Engine.recordIssue`(`:716`,一生制收束下限钳制) | 每条以 `T{n}` 开头,可数 gate 介入次数 / 钳制次数 |
| `currentActions` / `phaseHint` | `SessionDocument.encode` | `phaseHint` 是 persist 那一刻的相位,注释自陈「仅取证用」 |
| `boxScene` | `SessionDocument.encode`,只在《动物人生》 | 新局 / 旧局标记、结算结果、处境、意图历史(ADR-028) |

**结局及其 outcome**:不在顶层,只能从 `world.endings[]` 里找 `reached == true` 的那一条读 `outcome`。
引擎兜底结局(`Engine.forceBottomOutEnding`,`:546`)与 AI 提议结局在文档里**落成同一形态**(都是 `reached:true`),
文档本身不区分;gate 改判会在 `issues` 里留一行。

**时间戳:没有。** 存档 JSON 里没有创建时间、没有最后回合时间、没有任何日期字段
(全 `server/src/main` 里写 `Instant` 的只有 `TurnTraceCollector`,那是轨迹不是存档;
`QuotaService` 的 `LocalDate` 只用于账本日界线)。由此做不到的事:

- 按日期 / 周 / 月切分局数与回合数;
- 区分 F-036 模型边界(2026-09-10)之前与之后的局 —— 存档里也**没有 model 字段**;
- 区分路线 B 上线前后、某次部署前后(存档里**没有 commit 字段**);
- 算「最后活跃时间」以区分「放弃的局」与「还在玩的局」。

文件系统的 mtime 不在 JSON 内。`FileSessionStore.persist` 每次原子替换文件,线上卷上的 mtime 理论上等于最后一次 persist;
经 `fly sftp` / ADR-032 导出等方式拷贝后 mtime 是否保留,**本次未核**。

---

## 2. 「有效档」的候选口径(只列,不选)

已有的一条定义:**ADR-025 已决 3(2026-09-26 Felix 定)**——「至少有一条 `turn ≥ 1` 的 `game_event`」。
它定义在 **pg 的 `game_event` 表**上;线上走文件存储,`/data` 里没有 `game_event`。文件存档上的对应物是什么,**未定义**。

| 候选口径 | 需要的字段 | 现有字段能否判定 |
|---|---|---|
| `state.turn ≥ 1` | `state.turn` | **能**。注意:降级回合也让 turn +1,故 turn ≥ 1 不等于「至少有一个正常落账回合」 |
| `state.turn ≥ N`(N 待定) | `state.turn` | **能**,N 的取值无数据依据 |
| 排除 init 后从未走过回合的孤儿档([future-experience §2.3](future-experience-backlog.md) 第 2 面) | `state.turn == 0` | **能识别「turn == 0」**,但分不清三种来源:玩家取消等待(孤儿)/ 开了局一步没走 / 第一回合在途时被拍下 |
| ended 与 ongoing 分开 | `state.status` | **能** |
| 「至少一个正常落账回合」 | 区分 settled / degraded | **/data 不能**:`applyNoOp` 与 `apply` 写出的 log 条目同形(`{turn, narrative, playerAction}`);降级回合的 narrative 可能为空串,但空串不是降级的充分条件;只有 `/data/traces` 的 `path` 字段能分 |
| 区分 Felix / 冒烟局与真实玩家局 | 设备 id / IP / 时间 | **不能**。`deviceId` 与客户端 IP 只经 `GameController` → `QuotaGate` 用于计数,不进会话、不进存档;存档无时间戳,也不能按「冒烟日期」排除 |
| 区分导入档与原生档 | 来源标记 | 文件存储下**无此概念**(来源标记 `source` 只在 pg 的 `game_session` 表) |

另:同一个玩家跨 init 开的多局之间没有任何关联键(单 saveId 槽,future-experience §2.3 第 1 面),
「局」与「玩家」不可对应。

---

## 3. 指标逐项

图例:✅ 可算 / ⚠️ 部分可算或有偏 / ❌ 算不出。

| 指标 | 数据源 | 能否从 `/data` 存档算 | 可算样本范围 | 已知偏差 |
|---|---|---|---|---|
| world-gen 首轮有效率 | 只有日志:`WorldGenService.generate` 的 `[world-gen] … 成功/失败 durMs repaired=` INFO 与「首次产出未过校验」WARN | ❌ /data 算不出。世界 JSON 里没有 repaired 标记;ADR-031 W-4 world-gen 不记轨迹 | 无 | 失败的 init **不产生存档**(`GameInitService.init` 里 `worldGen.generate()` 在 `sessions.create()` 之前),存档集本身就只含成功者 |
| world-gen 修复后成功率 | 同上 | ❌ | 无 | 同上 |
| 平均 token(主调用 / 修复) | `/data/traces` 的 `usage[]`(`TurnTrace.CallUsage`:`call` = `主调用`/`修复`,`LlmUsage` 含 prompt/completion/total/cacheHit/cacheMiss) | ❌ 存档里无 usage;✅ 轨迹可算 | 只覆盖有轨迹的局(2026-10-08 `681721a` 起) | 主调用流中断时 `logUsage` 不被调用(`EventLoopService` 在 catch 里直接 `degrade`),该回合轨迹没有主调用 usage;world-gen 的 token 不在轨迹里 |
| 单回合成本 | 轨迹 `usage[]` × `application.yml` 的 price 三段(`QuotaService.costCny` 同一公式,包私有) | ❌ 存档;✅ 轨迹 | 同上 | 价格是配置现值,不是调用发生时的价格;被中止的调用不入账([工程债「成本闸门少算被中止的调用」](backlog-engineering-debt.md)) |
| 单局成本 | 回合部分:轨迹;world-gen 部分:只在日志 `[world-gen] usage` | ❌ 存档;⚠️ 轨迹只能给出**回合部分之和** | 有轨迹的局 | world-gen 那一发(单局里最贵的一次)不在 /data 任何地方;日志 usage 行不带 saveId |
| no-op 降级率 | 轨迹 `path == degraded` + `degradeReason`(三值:`stream_interrupted` / `no_structured_tail` / `repair_failed`) | ❌ 存档(同形,见 §2);✅ 轨迹 | 有轨迹的局 | 无 |
| 修复率(回合侧) | 轨迹 `repairErrors != null` / `usage[]` 里出现 `修复` 一条 | ❌ 存档;✅ 轨迹 | 有轨迹的局 | 无 |
| 最长连续回合数 | 存档 `state.turn` 最大值 | ✅ | 全部存档 | turn 含降级回合;「连续」若指「一次会话不中断」则无法判定(无时间戳);跨续局累计 |
| 单局平均回合数 | 存档 `state.turn` | ✅ | 全部存档 | 分母取决于 §2 的有效档口径;turn 含降级回合 |
| 回合耗时 `durMs` | 轨迹 `durMs`(`TurnTraceCollector.finished`,与日志同一读数) | ❌ 存档;✅ 轨迹 | 有轨迹的局 | 不含 `store.persist()`,系统性偏小(ADR-024 / 求职线层 1 #2 已记);未读到终点为 -1 |
| 按 archetype 分布 | 存档 `world.archetypes` | ✅ | 全部存档 | 融合局 host 在前,统计时归哪一类未定 |
| 结局分布(outcome) | 存档 `world.endings[reached].outcome` | ⚠️ | `status == ended` 的局 | 老档可能无 `outcome`;兜底结局与 AI 结局不可区分(gate 改判可从 `issues` 看出) |

### 3.1 world-gen 结果在 /data 里的痕迹

**没有。** 首过 / 修复 / 失败三种结果在 `/data` 里都不留痕:成功(含修复后成功)只留下一份与首过无异的存档;
失败不产生存档。唯一的痕迹在日志(瞬时,ROADMAP v5.8 裁定不碰)。**/data 算不出。**

### 3.2 `/data/traces` 可直接用的字段

`TurnTrace`(`persistence/TurnTrace.java`,schema 1;编解码 `TurnTraceCodec`):

- `recordedAt`:ISO-8601 回合结束时刻 —— **轨迹是 /data 里唯一带时间戳的数据**;
- `commit`:代码版本(`build.commit`);
- `path` + `degradeReason`:降级率与原因;
- `usage[]`:每次模型调用一条,含 `model` 与 `reasoningChars`;
- `durMs`;
- `repairErrors`:修复是否触发;
- `pre` / `post`:回合前全量快照 / 落账后摘要。

样本范围:只覆盖 2026-10-08(`681721a`)起开的局,且只记**已落地**回合(W-6:未落地不记);
单文件 5 MB / 总量 200 MB 到限停写(刀 3),到限后的回合不在轨迹里。
⚠️ `pre` 是视图 1 全量(含 `isTrue` / `hiddenLogic`)、`streamedNarrative` 是正文 —— 统计只需要上面列的几个字段。

### 3.3 `quota-YYYY-MM.json`

`QuotaService.persistMonth`:只有 `{month, spentCny}` 两个字段。

- 能支撑:当月模型支出总额(按 price 配置计,北京日界线);跨月逐月对比。
- 不能支撑:单局 / 单回合成本、world-gen 与回合的拆分、按 archetype 拆分、日粒度(日累计只在内存,deploy 清零)。
- 被中止的调用不入账,故偏低。

### 3.4 F-036 模型版本边界(2026-09-10)

- **存档**:没有 model 字段,也没有时间戳 → 无法把任何一局归到边界前或后。
- **轨迹**:`usage[].model` 有(`UsageCapture.model()`,`fab3eda` 起;轨迹 `681721a` 起,晚于边界)→ 轨迹全部是边界之后的数据。
- **日志 usage 行**:`model=` 自 `fab3eda`(2026-10-07)起才有;在此之前的行没有 model。
- 由此:边界之前的数据,在 /data 里只剩 `quota-2026-07/08/09.json` 的月总额,且 2026-09 那个月跨边界、不可拆。

---

## 4. 可复用的现成部件

| 部件 | 位置 | 用途 |
|---|---|---|
| `SessionDocument.decode` / `isSaveDocument` | `src/main/.../persistence/SessionDocument.java` | 判定是否存档 + 拒载不半载的还原(经 `Engine.restore`) |
| `FileSessionStore` 的扫描方式 | `FileSessionStore.loadAll`(`Files.list`,只收 `.json`,不递归 → 不进 `traces/`) | 存档目录的遍历口径 |
| `TraceFileReader.read` | `src/test/.../persistence/TraceFileReader.java` | 逐行解码轨迹,半截末行跳过并报出 |
| `TurnTraceCodec` | `src/main/.../persistence/TurnTraceCodec.java` | 一行 JSON ⇄ `TurnTrace`,严格解码 |
| `RepoPathGuard.requireOutsideRepo` / `findRepoRoot` | `src/main/.../persistence/RepoPathGuard.java`(ADR-032 刀 1 移入 main) | 输入输出必须在仓库外 |
| `MigrationPathGate.strict()` | `src/main/.../persistence/MigrationPathGate.java` | 仓库 + web 根两道路径闸门 |
| `TraceRecheckTool` / `TraceFixtureTool` 的入口形态 | `src/test/.../eventloop/` | `--in/--out` 参数、报告只写文件不打印、不回显输入文件名、`<saveId>` 替换 |
| `SyntheticSaves` | `src/test/.../persistence/SyntheticSaves.java` | 合成存档夹具(W-11),可作统计工具的测试输入 |
| `QuotaService.costCny` | `src/main/.../quota/QuotaService.java`(包私有) | price 三段计价公式 |

统计工具可能的放置方式(不选):

| 方式 | 代价 |
|---|---|
| 测试侧 `exec:java` 工具(同 `TraceRecheckTool`,`-Dexec.classpathScope=test`) | 能直接用 `SessionDocument` / `TraceFileReader` / `SyntheticSaves`;要 Maven + JDK 环境;不进生产 jar |
| 生产 jar 里的独立 main(同 `StoreMigrationTool`) | 可在容器内跑;进生产装配面,要处理 Spring 装配边界;改动面更大 |
| `scripts/` 下独立脚本(sh / python) | 不依赖 JVM;**要重新实现存档 / 轨迹解析**,与 `SessionDocument` 形成第二份判据(「同一判断落两处」);路径闸门要重写 |
| 不做工具,一次性手算 | 无代码;不可复现,且最容易违反 §5 的输出纪律 |

---

## 5. 输出纪律与可复用的守卫

两条约束:报告里不得出现 saveId、文件名、叙事正文;小样本指标必须标出分母(ROADMAP v5.8)。

现有守卫:

- **路径**:`RepoPathGuard.requireOutsideRepo` / `MigrationPathGate.strict()` —— 输入输出都在仓库外。
- **saveId**:`TraceRecheck` 写报告时把 `t.saveId()` 替换为 `<saveId>`;`TraceFixtureConverter` 把 saveId 换成固定值,`FixtureScanner` 扫描原 saveId 残留。
- **文件名**:`TraceRecheckTool` / `TraceFixtureTool` 不回显输入文件名;`StoreMigrationTool` 报告不含存档标识(ADR-032 刀 2)。
- **引擎字段名**:`TraceRecheck` 的 `MASK` 遮蔽 `isTrue` / `hiddenLogic`。
- **敏感形态扫描**:`FixtureScanner`(`src/test/.../eventloop/`,`TraceFixtureConverter` 调用)扫 sk- / Authorization / Bearer / IPv4 / IPv6 / 邮箱 / 原 saveId。

**没有现成守卫的**:「报告里不含叙事正文」(现有工具里 `TraceRecheck` 是故意输出正文的,方向相反);
「每个百分比都带分母」—— 全仓没有这类格式守卫。

---

## 6. 开放问题

1. 文件存档上的「有效档」口径是什么?是否沿用 ADR-025 已决 3 在 pg 上的定义的对应物(`state.turn ≥ 1`)?
2. 降级回合算不算「走过的回合」?(/data 分不出,只有轨迹能分)
3. 「单局平均回合数 > 5」的分母是有效档还是全部档?ended 与 ongoing 是否合算?
4. Felix 自己的局与冒烟局要不要排除?排除的话,在没有任何时间与设备字段的前提下用什么排除?
5. 融合局在按 archetype 分布里算 host、算组合、还是两边各算一次?
6. 轨迹覆盖面(2026-10-08 起)与存档覆盖面(全部)不同,两组指标并列时如何标注各自分母?
7. world-gen 首轮有效率 / 修复后成功率在不碰日志的前提下是否就此放弃?
8. 2026-09-10 之前的数据是否要单独标注或剔除?存档无法切分时如何处理?
9. 文件 mtime 能否作为时间依据?经拷贝后是否保留?
10. 统计工具读的是线上卷的拷贝,拷贝本身的流程是否沿用 ADR-032 演练第 1 步的只读拷贝?
11. 「最长连续回合数」的「连续」指什么:单局最大 `turn`,还是一次会话内不中断?
12. 价格按调用当时还是按现值计?(price 配置无历史版本)
