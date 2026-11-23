# 记忆账本勘察底稿 · B 线第 2 步

> **性质**:事实底稿,不是方案,不是 ADR。只写事实与问题,不写推荐、不排序。
> 截至 `main@2579d62`(2026-11-23)。出处写 `文件:行号`,行号以写成时为准、会漂。
> 推断标「**推测**」;没能核实的标「**未证实**」。路径前缀 `server/…/` = `server/src/main/java/com/aiuniverse/server/`。
> 形态照 [层 3.2 勘察底稿](tool-calling-survey.md)。上游测量:[ADR-045](adr/ADR-045-long-game-selfplay-measurement.md)、
> [长局记忆勘察底稿](long-memory-survey.md)(下称「上一份底稿」)。
>
> **数据**:本机基线套件 `~/aiuniverse-selfplay/20261123-a/`(只读;7 局 571 回合)。所有计数都附命令,原始输出与脚本原文在**附录**。
> 临时产物在仓库外的会话临时目录(下称 `$SP`),**不入库**;基线目录一个字节没写。没有调用真模型。

---

## ⚠️ 置顶:与勘察简报的出入(按简报要求停下来报告,未自行调和)

| # | 简报原话 | 源码 / 基线实际 | 出处 |
|---|---|---|---|
| 1 | 「`Engine.fromPersisted` 对 state 字段逐个硬校验」 | **没有 `fromPersisted` 这个方法**。持久化回载入口是 `Engine.restore`;state 五个字段的硬校验在 `:160-182`(`:169-173` 是其中 timeline / logSummary 两条) | `server/…/engine/Engine.java:142-209` |
| 2 | 「`FileSessionStore.isSaveDocument` 有同判据的副本」 | 判据**只有一份**,在 `SessionDocument.isSaveDocument`,`FileSessionStore:197-198` 只是转调;它**只判 `world` 是不是对象**,不看 state 任何字段。`Engine.java:152` 注释里说的「同判据的提前副本」指的是 **world 闸门**,不是 state 校验 | `server/…/persistence/SessionDocument.java:57-59`;`FileSessionStore.java:197-198` |
| 3 | §三(b)「引擎目前没有写 world 内部字段的先例」 | **有先例**:`markRuleDiscovered` 写 `world.rules[].discovered`,`markEndingReached` 写 `world.endings[].reached`,写的都是引擎手里那份可变 world(`world()` 的 javadoc 就说它可变);deepCopy 只发生在 `snapshot` / `toPersistedState` 出口 | `Engine.java:439-456`、`:733`;`:223-256`、`:381-403` |
| 4 | §二「ADR-007『无预调用』的原文」 | ADR-007 原文**没有「预调用」这三个字**。「无预调用」出现在 CONTEXT §一 术语表与 ADR-013 / 014 / 035,全部说的是 world-gen 融合。ADR-007 自述范围:「本 ADR 仅约束单模式单次胖调用」 | `docs/adr/ADR-007-world-gen-wire-protocol.md:105`;`ADR-035-…:92` |
| 5 | 校勘已核 1「每回合最多折 1 条…(:422–434)」 | 内容属实;`compressLog` 整个方法在 `:421-437` | `Engine.java:421-437` |
| 6 | (隐含)上一份底稿的计数可在加账本后「原样重跑」 | 上一份底稿附录说「定位脚本输出,别名与范围见各局『别名处理』」,但**定位脚本与别名表都不在仓库、也不在基线目录**;底稿正文里也没有叫「别名处理」的小节(`grep -c 别名处理` = 1,即那句指针本身)。跨窗提及类计数**今天无法从仓库机械复算** | `docs/long-memory-survey.md:429`;§五.3 |
| 7 | §四 Q-2「中途获得、从没进过 inventory」一类 | 21 次**全部**属于「开局就在 inventory」,另一类为 0 | §四 Q-2 |

另有一条不是出入、但影响 Q-3 读法的事实:xun-2 的 T0 世界**自身前后不一** —— `background` 写棉袄是父亲改小的,
同一份世界的 `inventory` 写「母亲改小的旧棉袄」(附录 A.7 `invT0.py`、A.8 `q3.py` 输出)。

---

## 一、起点与现状地图

### 1.0 起点:校勘已核(2579d62,照录,不重核;出入见置顶)

1. `Engine.LOG_KEEP = 4`(`Engine.java:39`);超出的回合每回合最多折 1 条 `[T{turn}选{action}]` 进 `logSummary`(`:421-437`)。
2. `Engine.apply` 不碰 `character.inventory`(`:267-342`)。`stateUpdate` 里的非数值键会被校验器放过(`GameSchemas.java:288`,
   注释原文「非数值键(traits/inventory 数组、timeline 等)不当数值轴,容忍(parity)」),引擎也不读,等于悄悄丢掉。
3. 回合 prompt 里的「世界段」= `contextJson()` 把整个 world 序列化成一块 JSON(`snapshot`,`Engine.java:381-403`),
   在 `TurnPromptBuilder.java:1000` 拼入;`character.inventory` 原样在里面。
4. `stateUpdate.timeline` 可选,不写就沿用上回合(`Engine.java:283-285`);《寻常》收束段指令把 timeline 当作跨回合唯一记忆(`TurnPromptBuilder.java:730-732`)。
5. 持久化回载对 state 字段逐个硬校验,缺字段就抛异常 —— 方法名与副本的出入见置顶 1、2。
6. 前端只在 `web/src/types/schema.ts:47` 有 inventory 类型声明;非测试组件(含 `theme/`)里**复核未见使用**(`grep -rn inventory web/src` 除 `schema.ts` 与 `*.test.*` 外无命中)。

### 1.1 回合 prompt 组装顺序

入口 `TurnPromptBuilder.buildTurnPrompt`(`:978-1007`)。**整份 prompt 作为一条 user 消息发出**(`OpenAiCompatLlmClient.java:117-120`),
虽然代码里第一段变量叫 `system`。下表按在 prompt 里实际出现的顺序:

| # | 段 | 来源 | 单体 | 融合 |
|---|---|---|---|---|
| 1 | 骨架开头:模式名 | `SKELETON`(`:330-382`)槽 %1 | archetype 显示名 | 融合语境名 |
| 2 | 【叙事清洁度】禁名单 + 各轴中文意象 | 骨架 + `IMAGERY`(如 `:290` 气力「爬到三楼要停一下」) | 本世界轴 | `fusedAxes`(含换皮轴) |
| 3 | 【叙事长度】【叙事须贴合当前状态档】【绝境的写法】【现实边界 · 硬约束】 | 骨架固定文 | 同 | 同 |
| 4 | 【输出格式】哨兵 + 尾巴字段清单 | 骨架;`stateUpdate` 规格 %6、轴行为提示 %7 | 同 | 同 |
| 5 | 融合附加指令 | 槽 %8 = `FUSION_TURN_DIRECTIVE`(`:393-408`)+ `FUSION_TURN_COPY`(`:805-880`) | 空 | 有 |
| 6 | 单体世界指令 | 槽 %9 = `archetypeTurnDirective`(`:459-502`)← `TURN_DIRECTIVES`(`:639-736`:cyberpunk / time_travel / animal_life / life_sim) | 登记世界有,其余空 | 空 |
| 7 | 「世界设定与当前状态(state 是真理之源):」+ **`engine.contextJson()`** | `TurnPromptBuilder.java:999-1000` ← `Engine.snapshot`(`Engine.java:381-403`) | 有 | 有 |
| 8 | 当前档块 | `currentBandBlock`(`:1146-1163`) | 本世界轴 | 融合轴 |
| 9 | 局面片段 | `sceneBlock`(只有 animal_life) | 仅动物人生 | 空 |
| 10 | 融合时段 | `fusionTurnClock`(只有《验讫》cultivation×cthulhu,`yanqiTurnClock` `:894-908`) | 空 | 仅《验讫》 |
| 11 | 单体时段 | `singleTurnClock`(cyberpunk、time_travel) | 登记世界 | 空 |
| 12 | 单体状态行 | `singleTurnStatus`(cyberpunk【腿况】【记忆点】;time_travel【记忆点】) | 登记世界 | 空 |
| 13 | 推进句 | 「请推进第 N 回合。玩家本回合选择的行动:」+ `id · text`(`:982`;选项文字取 `currentActions`,`EventLoopService.actionTextOf` `:903-913`) | 有 | 有 |

- contextJson 的内容(`snapshot`):world 深拷贝(attributes 换成引擎当前值)+ `state{turn,status,timeline,logSummary,log 近 4 条}`。
  world 里含 `character.inventory`、`rules`(含 `isTrue` / `hiddenLogic`,视图 2)、`endings`。
- 《寻常》(life_sim)没有 `singleTurnClock` 条目,它的人生时钟在 %9 指令槽里(`TURN_DIRECTIVES` life_sim 段)。
- **修复提示不带世界段**:修复 prompt = `REPAIR_SYSTEM` + 错误清单 + 失败的尾巴(`:922-926`、`:1047-1051`)。

**contextJson 在 prompt 中的字数占比**(Java `String.length()`;方法:用生产同一个 `EventLoopService.renderTurnPrompt` 对 571 条轨迹的 `pre` + `actionId`
重渲染,与轨迹里的 `promptSha256` 比对,**571/571 一致**,再在 prompt 里定位 `contextJson()` 原文。命令与原始输出见附录 A.1–A.3):

| 局 | n | prompt 字数 均值 / 最大 | contextJson 字数 均值 / 最大 | 占比 均值 / 最小 / 最大 | contextJson 之后的字数 均值 / 最大 |
|---|---:|---|---|---|---|
| gui-1 | 31 | 5320 / 5545 | 3012 / 3234 | 0.565 / 0.486 / 0.583 | 156 / 171 |
| xiu-1 | 60 | 5870 / 6143 | 2989 / 3250 | 0.508 / 0.410 / 0.529 | 224 / 238 |
| xiu-2 | 60 | 6213 / 6453 | 3331 / 3567 | 0.535 / 0.447 / 0.553 | 225 / 239 |
| rong-1 | 60 | 7356 / 7785 | 3538 / 3946 | 0.480 / 0.393 / 0.507 | 257 / 278 |
| xiu-long | 120 | 6045 / 6587 | 3164 / 3705 | 0.522 / 0.403 / 0.562 | 224 / 239 |
| xun-1 | 120 | 9850 / 10354 | 4070 / 4575 | 0.413 / 0.318 / 0.442 | 263 / 271 |
| xun-2 | 120 | 9729 / 10258 | 3951 / 4474 | 0.406 / 0.318 / 0.436 | 261 / 271 |
| **全部** | 571 | **7717 / 10354** | **3550 / 4575** | **0.472 / 0.318 / 0.583** | 240 / 278 |

contextJson 内部拆分(Python 按同一快照重新编码,字数为近似值,附录 A.3):world 部分均值 2296、state 部分 1254(其中 log 816、logSummary 均值 342 / 最大 926)、
**inventory 均值 29 字**。

### 1.2 回合输出

**TURN_SCHEMA**(实现是 `GameSchemas.validateTurn`,`:96-142`;不检查未知键):

| 字段 | 约束 |
|---|---|
| `narrative` | 必填字符串(由服务端把流出的叙事回灌进来,`TurnReinfuser`) |
| `stateUpdate` | 对象;每个数值键 0–100;`timeline` 可选字符串;非数值键放过(`:276`、`:288`) |
| `triggeredRuleIds` / `discoveredRuleIds` | 可选整数数组 |
| `availableActions` | 2–4 条 `{id, text 必填, hint 可选}`;已达结局时 0 条 |
| `ending` | 对象或 null:`{reached: bool, id: string}`(另有可选 `outcome`) |

**线上形态**(ADR-006):叙事散文 → 单独一行哨兵 `<<<DELTA>>>` → 结构化尾巴 JSON。叙事在哨兵前逐字流给玩家(`SentinelSplitter` → `sink.narrative`),
尾巴在服务端缓冲,不下发原文;主调用**不开** `json_object`,修复调用开。前端在 `onDelta` 时才更新状态与选项(`web/src/state/gameStore.ts:469`)。

**管线**(`EventLoopService.execute`,`:161-224`):

1. `beginTrace` → 局面计划 → `renderPrompt` → `trace.prompt`(记 sha)。
2. 流式调用经 `SentinelSplitter`;流断 → 降级。
3. `logUsage`(`:375-381`,内含 `quota.record`)。
4. 结算(SETTLING):无叙事 / 无哨兵 / 尾巴为空 → 降级 `no_structured_tail`。
5. `reinfuseAndValidate`(`:299-307`)+ `slotErrors`;不过 → `repairOnce`(`:313-339`,json_object,一次)→ 仍不过 → 降级 `repair_failed`。
6. `settle`(`:342-373`):**先** `trace.settled(parsed)`(记服务端改写之前的 parsed)→ `landSettled` → `sink.delta` / `sink.ending`。
7. 降级路径 `landDegraded`(`:447-475`):`engine.applyNoOp` + `markDegraded`(`:468`)。

**`landSettled` 内的先后**(`:418-443`):

1. `clampClosingVigorFloor`(`:719`;《寻常》收束 / 末段气力下限)
2. `checkPaidanEnding`(《派单》结局核对)
3. `guardQiriAction` → `checkQiriEnding`(《七日开张》)
4. 读落账前的值(profile / cover / ties)
5. **`engine.apply`**
6. `markPaidanProfileCrossing`、`markQiriCoverDrop`、`XunTiesMarker.mark`(落账后比前后值,记 issue 标记)
7. `BoxSceneTurn.commit`(动物人生局面)
8. `afterLanding`(durMs、INFO 日志)
9. 写 `session.currentActions`(《寻常》追加出口 X,`appendLifeExitAction` `:633`)

**persist 在 `landSettled` 之外**:`TurnStateMachine.submitAction`(`:96-143`)依次是 `quota.checkTurn` → CAS 进 GENERATING(失败 = busy)→
`ledger.accept` → `executor.execute`(上面整条)→ **`store.persist`** → `writeTrace` → 置相位;回合已落地后若抛 RuntimeException,catch 里补一次 persist(ADR-027)。
`GameController:369-370` 在 `submitAction` 返回后才 `emitter.complete()`。

### 1.3 inventory 的全部读点

| 读点 | 位置 | 怎么用 |
|---|---|---|
| world-gen 提示词(单体 / 融合) | `WorldGenPromptBuilder.java:76`、`:164`「inventory:1-3 件中文」;`prompts/world-gen.md:105`、`:447` | 让模型产出开局物品 |
| 《验讫》world-gen | `WorldGenPromptBuilder.java:892` | 限定 inventory 只用第一级词汇(揭示阶梯,CONTEXT §三.16 (10)) |
| `validateWorld` | `GameSchemas.java:33-92` | **不校验 inventory** |
| `validateTurn` | `GameSchemas.java:276`、`:288` | stateUpdate 里若出现 inventory 这类非数值键,放过 |
| 回合 prompt | `contextJson` ← `snapshot` | 原样进世界段(视图 2) |
| 客户端 | `toClientState` | 原样下发(视图 3),前端不渲染(起点 6) |
| `LeakDetector` | — | **不读**:只查 `LEAK_TOKENS` 与 `hiddenLogic` 子串 |
| 测试侧 | `SelfPlayDraftTool.java:280`(成稿);`DataStats`(扫 inventory / timeline / logSummary) | 成稿与统计 |
| 引擎 | `Engine.apply` | **不读不写**(起点 2) |

per-archetype 与融合 combo 文案里提到物品的地方(**没有一处指向 inventory 字段**,都是叙事层的恢复手段):

- 修仙 `mana` 的 behaviorHint 提「服食丹药」(`ArchetypeRegistry.java:611`)。
- 识海遗蜕 world-gen 槽:「服食随身丹药可稳道心但存量有限」(`:631`);回合侧 `recoveryMeans`「参悟心法 / 调息 / 丹药等」。
- 人防工程:「配给日 / 搜刮 / 以页换粮」。《验讫》:「服余泽丹…随身带着」。
- 结局 condition:T0 基线 7 局里**没有一条**写到 inventory 物品。规则里:rong-1 #4 点名「定神丹」,xiu-long #5 泛说丹药。

### 1.4 timeline 与 logSummary 的读点

| 读点 | 位置 | timeline | logSummary |
|---|---|---|---|
| 引擎落账 | `Engine.apply :283-285`;`compressLog :421-437` | 有则整句替换 | 每回合追加 `[T{n}选{X}]` |
| 回载校验 | `Engine.restore :170`、`:173` | 必须是字符串 | 必须是字符串 |
| 回合 prompt | contextJson 的 `state` 部分 | 有 | 有 |
| 骨架 | `TurnPromptBuilder.java:332`、`:367` | 尾巴字段清单里列出 | — |
| 服务端读 | `EventLoopService.exitAlreadyPressed :685-693` | **不读** | 以 logSummary 里的「选X」为《寻常》出口硬信号 |
| md 镜像 | `prompts/event-loop.md:105`、`:109`、`:151`、`:237` | 有 | 有 |
| 前端 | `web/src/types` | 只有类型;`StatsPanel` 的 `timeline` prop 是 `{turn, markedTurns}`,不是这个字段 | 只有类型 |
| 测试侧 | `DataStats`;`EngineGoldenTest` 比对二者 | | |

**把 timeline 当状态载体的指令**(全量):

| 世界 | 指令 | 位置 |
|---|---|---|
| 《寻常》life_sim | 收束段:「必须在 stateUpdate 的 timeline 里写明…已进入收束段(第 k 回合)」「timeline 是你跨回合唯一记得住这件事的地方」 | `TurnPromptBuilder.java:726-732` |
| 《验讫》cultivation×cthulhu | timeline 必须以「第 N 日 · 昼/夜」开头 | combo 槽 (5)(`:854-860`) |
| 《派单》cyberpunk、《七日开张》time_travel、《验讫》 | 时钟行:「叙事与 timeline 都按这个时段写」 | `singleTurnClock` / `yanqiTurnClock` |

基线 7 局里:修仙单体(xiu-1 / xiu-2 / xiu-long)与规则怪谈(gui-1)、识海遗蜕(rong-1)**没有时钟、也没有 timeline 指令**;
《寻常》两局的出口 X 被 selfplay 排除(`EXCLUDED_ACTION_ID="X"`),**收束段的 timeline 载体指令在基线里一次也没被触发**。

### 1.5 持久化

- **默认 profile(文件)**:每个 saveId 一个 JSON。文档 = `Engine.toPersistedState`(七字段)+ `currentActions` + `phaseHint` + 可选 `boxScene`(`SessionDocument.encode`)。
  回载:`FileSessionStore:176` 先 `isSaveDocument`(只看 world)分流,`:182` `SessionDocument.decode` → `Engine.restore` 全量校验,拒载不半载。
- **pg profile**:Flyway `V1__narrative_history.sql` 建 `game_session.snapshot json NOT NULL`(**整份文档一列**,`json` 不是 `jsonb`)与 `game_event`(逐回合叙事);
  `V2__turn_request.sql` 是回合受理记录。`JdbcSessionStore:178` 走同一个 `SessionDocument.decode`。
- **加一个文档内的键要不要迁移**:snapshot 列是整块 JSON,**推测不需要** Flyway 迁移(依据:列类型 `json`,无 schema;未在 pg 上实测)。若要拆表或按键查询则另说。

### 1.6 加一段 prompt / 加一个 state 字段会碰到的守护

| 守护 | 加一段 prompt | 加一个 state 字段 | 理由 |
|---|---|---|---|
| `golden/turn-prompts/`(`PaidanTurnTest` ①,9 个世界 / 组合 × 10 回合,最小存档各轴 50,逐字节) | **会红** | **会红**(若进 contextJson) | 逐字节比 prompt;字段进 `snapshot` 就进 prompt |
| `PaidanPromptSnapshotTest`(《派单》回合快照) | **会红** | **会红**(同上) | 同上 |
| `AnimalLifeLegacyGoldenTest` | **会红** | **会红**(同上) | 旧局 prompt 与 `583abc9` 逐字节比,只剔除公共边界块 |
| 其他按世界的逐字节 prompt 测试(《七日》《验讫》等) | **未证实** | **未证实** | 未逐个穷举 |
| `TurnTraceTest` | 不会红 | 不会红 | 同一 build 内重渲染 = sha |
| `TraceRecheck`(手动工具,非测试) | 不会红,但对**旧轨迹全部报 sha 不符** | 同左(若进 prompt) | 它用当前代码重渲染旧 `pre` |
| `TraceFixtureReplayTest`(档 1 回放,`trace-fixtures/first-real.jsonl` 6 行) | 不会红 | **会红**,若 `SessionDocument.encode` 对旧局也写出新键 | 回放不渲染 prompt;比的是 `post` 的 sha(encode 结果) |
| `EngineGoldenTest` | 不会红 | 不会红,除非改了它比对的字段 | 比 turn / status / 各轴 / timeline / triggered / logSummary / issue 数 / discovered / endings |
| `TransformParityTest` | 不会红 | **未证实** | 看新字段是否进 transform 路径 |
| `ValidatorParityTest`(38 条录制原文) | 不会红 | **会红**,若新字段在 TURN_SCHEMA 里是必填 | 旧原文没有这个键;可选则不红 |
| `EngineRestoreTest`(往返) | 不会红 | **未证实** | 取决于 restore 是否读回新字段 |
| `ClientProjectionParityTest`(init / state / delta 逐字节,除 time_travel) | 不会红 | **会红**,若字段进视图 3 | 视图 3 逐字节;剥掉则不红 |
| ADR-023 / 027 游标 | 不会红 | 不会红(推测) | 游标只看 `state.turn` |
| Lockstep(`TurnFusionLockstep`、`TurnPromptBandInjection`、`CommonBoundary`、`ActionHint`、`CultivationCthulhuTurn`、`QiriTurn`、`PaidanTurn`、`ContentSafety`、`FusionMetaPromptLockstep`、`LifetimeFamilyLockstep`) | 只在新段落进了它们锁定的那几段时会红 | 不涉及 | **没有一个**覆盖骨架的尾巴字段清单:往清单里加字段,lockstep 抓不到 md 与运行时副本不一致 |
| `SelfPlayDraftTool` 两道扫描 | 不涉及 | **未证实** | 隐藏字段名扫描是否要加新键 |

---

## 二、设计前提甲:抽取放在回合输出里,还是另起一次调用(只列碰到的面,不选)

### 2.1 放进回合输出

要改的位置:

- `GameSchemas.validateTurn`(新字段的约束);注意 `stateUpdate` 内的非数值键今天会被放过(`:288`),放在 `stateUpdate` 里与放在顶层,校验器表现不同。
- `TurnPromptBuilder.SKELETON` 的尾巴字段清单(`:367` 一带)与 `prompts/event-loop.md` 对应段 —— 两份同步**没有 lockstep 守护**(§1.6)。
- 修复提示 `REPAIR_SYSTEM`(`:922-926` / `:1047-1051`):修复 prompt 不带世界段,修复时模型看不到当前账本。
- `Engine.apply` 或 `landSettled` 里新的落账步骤;`trace.settled(parsed)` 记的是改写前的 parsed(档 1 回放要能重现)。

线上位置与延迟:

- 尾巴在哨兵之后,玩家看到的叙事在哨兵之前逐字流出。**推测**:尾巴变长不影响叙事首字,但会推迟 `delta`(选项与数值)到达的时刻。
  依据:`durMs ≈ 764 + 8.62 × completionTokens`(n=527 条无修复的回合,r=0.37,附录 A.5);基线**没有首字时间的记录**,durMs 不含 persist。
- 修复回合:修复调用重出整条尾巴,新字段随之重出;修复 42 次全部是「结构化尾巴非合法 JSON」(下表)。
- 降级回合:2 次都是 `no_structured_tail`,**模型只出了叙事、没有尾巴**,没有结构化部分可抽(下表)。

### 2.2 另起一次调用

- **挂点**:`settle` 在 `landSettled` 之后、`sink.delta` 之前或之后;或 `TurnStateMachine` 在 `persist` 之后、置相位之前;或置相位之后异步。
- **串行**:同一 saveId 的回合靠相位 CAS(AWAITING_ACTION → GENERATING,失败 = busy)与 ADR-023/027 游标串行。
  - 挂在置相位**之前**:下一回合请求会撞 busy;SSE 的 `emitter.complete()` 也会被推迟(`GameController:369-370`),但前端在 `onDelta` 时已经更新。
  - 挂在置相位**之后**(异步):下一回合的 prompt 可能在账本更新前渲染;两条路径同时写同一个 `Engine`,**`Engine` 是否线程安全未证实**。
- **计费**:`quota.checkTurn` 按回合检查,`quota.record(usage)` 按调用记账;轨迹的 `usage` 是按调用的列表(基线里名字只有「主调用」「修复」)。
- **回放**:档 1 回放重放的是记下的 parsed;另起调用的产出若不进轨迹,回放重现不了(推测)。
- **ADR-007 的范围**:原文「本 ADR 仅约束单模式单次胖调用」(`ADR-007:105`),约束对象是 world-gen;「无预调用」原文在 ADR-013 / 014 / 035,说的都是 world-gen 融合(如 `ADR-035:92`「守 ADR-007:world-gen 单次胖调用、`json_object`、无哨兵、无预调用」)。
  **没有一处原文覆盖回合后的调用**。回合侧相关的是 ADR-006 方案 B「两次调用(叙事纯文本流 + 结构化 json_object 非流式)」,
  原文「成本 ~1.3–1.5×」、被「暂排」并留作切换条件(`ADR-006:44`、`:52`、`:97`、`:101`、`:103`)。

### 2.3 成本与时延底数(附录 A.4、A.5、A.6)

主调用 token,每回合一条:

| 局 | n | prompt 均值 / 中位 / 最大 | completion 均值 / 中位 / 最大 | 缓存命中占 prompt |
|---|---:|---|---|---:|
| gui-1 | 31 | 2959 / 3046 / 3122 | 324 / 329 / 380 | 0.476 |
| xiu-1 | 60 | 3391 / 3418 / 3570 | 351 / 350 / 408 | 0.486 |
| xiu-2 | 60 | 3683 / 3750 / 3877 | 355 / 354 / 430 | 0.461 |
| rong-1 | 60 | 4382 / 4404 / 4740 | 403 / 399 / 672 | 0.488 |
| xiu-long | 120 | 3549 / 3552 / 3958 | 348 / 350 / 423 | 0.469 |
| xun-1 | 120 | 5655 / 5660 / 6040 | 352 / 355 / 422 | 0.307 |
| xun-2 | 120 | 5576 / 5597 / 5965 | 336 / 331 / 422 | 0.551 |
| **全部** | 571 | **4471 / 4335 / 6040** | **352 / 350 / 672** | 0.450 |

修复调用 42 次:prompt 394 / 386 / 623,completion 253 / 252 / 289。

回合 durMs(均值 / 中位 / p90 / 最大):全部 3917 / 3601 / 5487 / 8327;分局见附录 A.4。

修复与降级:

| 项 | 数 | 分布 / 原因 |
|---|---:|---|
| 修复回合 | 42 / 571 | gui-1 3、xiu-1 5、xiu-2 9、rong-1 11、xiu-long 14、xun 两局 0;**42 条错误信息全部是「结构化尾巴非合法 JSON」**,42 次修复后全部落账 |
| 修复原因按字段 | — | **未证实**:轨迹只记错误信息,不记失败的尾巴原文,拆不到字段 |
| 降级回合 | 2 / 571 | 都是 `no_structured_tail`:xiu-long T25(completion 102 token)、xun-1 T5(158 token),叙事完整收尾、没有哨兵与尾巴 |

单价(`server/src/main/resources/application.yml:116-119`,元 / 百万 token):输入未命中 2.0、命中 0.04、输出 8.0。
按此算基线每回合成本(含修复,不含 world-gen):均值 **0.8016 分**、中位 0.7661 分、最大 1.3255 分;571 回合合计 **¥4.5772**(附录 A.6)。

**另起一次调用的成本**(**推测**,只给公式与代入值):

```
单次成本(元) = [2.0 × (I − H) + 0.04 × H + 8.0 × O] / 1,000,000
  I = 输入 token,H = 其中缓存命中,O = 输出 token
```

- 代入 ①(输入 = 整份回合上下文,按基线均值 I=4471、命中率 0.450;输出按修复调用均值 O=253):
  `[2.0×2459 + 0.04×2012 + 8.0×253] / 1e6 ≈ 0.00702 元 ≈ 0.70 分`,约为基线每回合均值 0.80 分的 0.88 倍。
- 代入 ②(同上但全部未命中):`[2.0×4471 + 8.0×253] / 1e6 ≈ 0.01097 元 ≈ 1.10 分`。
- 时延按上面的回归式代入 O=253:`764 + 8.62×253 ≈ 2945 ms`;r=0.37,解释力弱,只作量级参考。

---

## 三、设计前提乙:账本是否同时承担 inventory 更新(只列波及面,不选)

前提事实:基线 7 局的 inventory **从 T0 到终局存档一字未变**(附录 A.7 `invT0.py`,7/7「相同」)。

### (a) 账本物品条目在 prompt 里取代 inventory,inventory 退化成开局种子

| 读点 | 波及 |
|---|---|
| `contextJson` / `snapshot` | 剔不剔 `character.inventory`:剔则世界段少约 29 字(§1.1),且所有逐字节 prompt 快照会红;不剔则 prompt 里同时有「开局种子」与「账本」两份物品文字 |
| world-gen 提示词 | 可不动(仍产种子);是否要求物品带数量,看账本设计 |
| 《验讫》 | 账本文字每回合回传模型,受揭示阶梯约束(CONTEXT §三.16 (10));world-gen 对 inventory 的第一级词汇限定(`:892`)对账本是否同样成立,要另说 |
| `toClientState` | 视图 3 仍下发 inventory(前端不显示);账本是否下发另说 |
| 测试侧 | `SelfPlayDraftTool:280`、`DataStats` 读的仍是 inventory |
| 旧存档 / T0 | inventory 在,账本键不在 → 回载时要么按空账本起,要么按 inventory 播种(§五.2) |
| CONTEXT §二 | 不触及字段职责 |

### (b) 回合输出增加 inventory 更新,改写 `world.character.inventory`

| 读点 | 波及 |
|---|---|
| TURN_SCHEMA | 新字段;放在 `stateUpdate` 里,今天的校验器会把它当非数值键放过(`:288`) |
| `Engine.apply` | 新写点。先例:`markRuleDiscovered` / `markEndingReached` 已写 world 内部(置顶 3) |
| `contextJson` / `toClientState` | 无需改,自然带出更新后的值;但逐字节快照只在新回合会变 |
| `ClientProjectionParityTest` | delta 是否带 world 片段 **未证实**;若带,会红 |
| world-gen / combo 文案 | 物品原文是「半瓶 X」「半块 X」这类模糊量词(附录 A.7);改写需要模型写出新量词,是否要求开局写数量另说 |
| 《验讫》 | 模型每回合改写的 inventory 文字同样受揭示阶梯约束 |
| 旧存档 / T0 | 字段已在,**无需迁移**;旧局从下一回合起开始被改写 |
| CONTEXT §二 字段职责 | 今天写的是「AI 生成:world / character 初值」「引擎维护:state…」;(b) 让 character 在开局后继续被改写,与这句字面不一致(CONTEXT 本刀不动) |

---

## 四、上一份底稿的开放问题 Q-2 ~ Q-6(只答事实)

### Q-2 · 21 次物品矛盾拆两类;其余 25 次分类

**开局就在 inventory 的:21 次;中途获得、从没进过 inventory 的:0 次。**(T0 inventory 见附录 A.7;回合号取上一份底稿 §三、§五)

| 局 | 物品 | T0 inventory 原文 | 矛盾回合 | 次数 |
|---|---|---|---|---:|
| xiu-1 | 聚气丹 | 「半瓶聚气丹」 | T12、T15、T30、T35 | 4 |
| xiu-2 | 灵石 | 「半块下品灵石」 | T29、T37 | 2 |
| rong-1 | 定神丹 | 「半瓶定神丹」 | T24、T39、T48、T59 | 4 |
| xiu-long | 回气散 | 「半瓶回气散」 | T15、T21、T28、T35、T44、T50、T69、T89、T101、T107、T115 | 11 |

另:21 个回合全部是「所选行动文本含该物品名」的回合(附录 A.9 `invproxy.py`:四个物品名在所选行动文本中出现的回合集合覆盖了上表全部 21 个回合号)。

其余 25 次,按「人物 / 承诺 / 事件 / 时间 / 其他」(分类口径是本稿的人工判读,依据上一份底稿 §三 各局条目;带「推测」的沿用上一份底稿的标注):

| 类 | 次 | 条目 |
|---|---:|---|
| 人物 | 6 | xiu-2 陆师兄 T13;xun-1 小满 T13(推测)、老伴 T36(推测)、老邻居 T61(推测)、儿子 T110(推测);xun-2 她 = 妻 T119 |
| 承诺 | 0 | — |
| 事件 | 4 | xiu-2 柴房 T58;xun-1 老槐树 T112、五斗柜 T57(推测);xun-2 挪炕 T91 |
| 时间 | 4 | xiu-long T30、T88、T94(推测);xiu-2 大比 T60(推测) |
| 其他(地点 / 设定细节 / 物件位置) | 11 | xiu-1 洞 T24、残卷 T51(推测);xiu-2 柴房 T39;xun-1 楼层 T34 / T35 / T45 / T64、钥匙 T31(推测);xun-2 棉袄 T37、菜畦 T73(推测)、老树 T113(推测) |
| **合计** | **25** | |

上一份底稿 §五 的五类(物品 21 / 地点时间 7 / 设定细节 5 / 人物 5 / 场景物其他 8)与本表口径不同:本表把「小满」从场景物移进人物,把「柴房 T58」「挪炕」「老槐树」「五斗柜」归为事件。**这只是重排,不改任何一条的判定**。

### Q-3 · 「正确答案在 prompt 里仍写错」

方法:取当回合重渲染的 prompt 全文(sha 已与轨迹一致),逐个找正确答案与错误说法的出现位置(附录 A.8 `q3.py`)。

| 局 · 回合 | 正确答案 | 在 prompt 哪一段 | 离末尾(字) | 出现次数 | 同时在 prompt 里的其他说法 |
|---|---|---|---:|---:|---|
| xun-1 T34 | 「二楼西户」 | contextJson.world.background | 3808 | 1 | 「三楼」1 次:骨架意象「爬到三楼要停一下」(@354) |
| xun-1 T35 | 同上 | 同上 | 3861 | 1 | 「三楼」2 次:骨架意象 + 窗口 log[T34](离末尾 459) |
| xun-1 T45 | 同上 | 同上 | 3907 | 1 | 「三楼」1 次:骨架意象 |
| xun-1 T64 | 同上 | 同上 | 4059 | 1 | 「三楼」1 次:骨架意象 |
| xun-2 T37 | 「父亲」把棉袄「改小了给你」 | contextJson.world.background | 4075(「父亲」)/ 4050(「改小」) | 「父亲」1、「改小」2 | **inventory 自己写「母亲改小的旧棉袄」**(3719);timeline「母亲的旧棉袄」(1555);log[T33]「母亲那件旧棉袄」(1154);「棉袄」共 7 次 |
| xiu-1 T15 | 「最后一粒」(T12 已服) | 窗口 log[T12] | 802 | 1 | inventory「半瓶聚气丹」(2701);所选行动「摸出聚气丹再服一粒」(14) |

- **已查范围**:上表 6 个回合,加另外 14 个矛盾回合的候选关键词(附录 A.0 里 `q3.py` 的第二次运行:xiu-1 T12 / T30 / T35、xiu-2 T29 / T39 / T58、
  rong-1 T24 / T39 / T48 / T59、xiu-long T101、xun-1 T112、xun-2 T91 / T119)。这 14 个回合的正确答案都不在当回合 prompt 里;
  xiu-1 T12「最后一粒」与 xun-2 T91「挪」命中的是推进句里的所选行动,xiu-1 T35「最后」命中的是 log[T31],xiu-2 T58「烧」命中的是骨架 —— 都不是正确答案。
  46 次里其余回合**没有逐个查**,「这类只有上表 6 个」**未证实**。
- 「三楼」在每一份《寻常》回合 prompt 的骨架意象里都有一次(`TurnPromptBuilder.java:290`)。它与楼层错误是否有关,**未证实**。
- xun-2 的「正确答案」本身有两说:T0 的 background 与 inventory 互相矛盾(置顶末段)。上一份底稿把 background 当正确答案;按 inventory,T37 不算错。

### Q-4 · 「埋专名 + 点名追问」探针能不能做

| 问 | 事实 |
|---|---|
| 回合 API 收不收自由文本 | **不收**。`TurnRequest(int turn, String actionId)`(`GameController.java:443`);`:361` `hasAction` 不在本回合闭集里 → 400 `illegal_action` |
| selfplay 能不能在指定回合注入指定行动 | **今天不能**。每回合 `rng.randrange(len(候选))` 抽一次,候选 = 本回合选项去掉 X;没有覆盖参数。加参数也只能在模型给出的 id 里选,不能注入文本 |
| `--resume-t0` 后前若干回合能不能按脚本走 | 只有第 1 回合的选项文字来自 T0 的 `currentActions`,改拷进新 store 的 T0 副本里的 `currentActions[].text`,就能让第 1 回合的「所选行动」带上指定文字(推进句取 `currentActions` 文字,`actionTextOf :903-913`)。第 2 回合起的选项由模型生成,无法预先编排 |
| 要做到「T_a 埋、T_b 追问」要改哪里 | 二选一(只列不选):改 `src/main`(放开自由文本或加测试用的行动覆盖入口);或在 T_a、T_b 各自做一次「停服 → 改存档 `currentActions` → 回载」(推测可行,未试) |

### Q-5 · 时间类矛盾与当回合 timeline

上一份底稿的时间类:xiu-long 3 条(T30、T88、T94,其中 1 条推测)、xiu-2 大比 1 条(推测);另 xun-2 的季节问题在 timeline 上。逐条对照(附录 A.10 `q5timeline.py`):

| 局 · 回合 | 进 prompt 的上回合 timeline | 本回合写的 timeline |
|---|---|---|
| xiu-long T29 → T76 | T29 起写「第一夜将尽…」,一直沿用到 T76(T76 写「第一夜熬到天亮」) | — |
| xiu-long T30 | 「第一夜将尽,黑影妖兽被你拼死刺毙…」 | 「第一夜将尽,你伏在兽尸余温中…」 |
| xiu-long T88 | 「…药园残灯下只剩你一人撑立。」(无时间词) | 「你护住残灯坐守至夜半…」 |
| xiu-long T93 / T94 | 「天光渐亮…」/「…七日守夜将成。」 | 「…七日守夜将成。」(两回合同句) |
| xiu-2 T60 | 「你养稳了那一缕灵力,柴房外寂静无声…」(无「大比」) | 「你伏低将养…」 |
| xun-2 T116 | 「开春修完树的枝子收进了柴垛…」 | 「**入冬前**咳得重了…」(T117 又写「老树发了芽」) |

- 这 5 条所在的世界(修仙单体、《寻常》)**都没有时钟,也没有 timeline 载体指令在生效**(§1.4)。基线里有时钟或载体指令的世界(《派单》《七日》《验讫》)一局都没有。
  所以「矛盾是否集中在把 timeline 当载体的世界」这个问题,**本基线答不了**。

### Q-6 · 《寻常》家人首次出现

叙事里家人称谓的首现回合(附录 A.11 `q6first.py`、A.12 `q6ctx.py`;「她」这个代词无法机械检索):

| 局 | 人 | 首现 | 原文(节选) |
|---|---|---|---|
| xun-1 | 老伴 | T36 | 「那盆花是你老伴生前养的」 |
| xun-1 | 儿子(自己的) | T110 | 「电视是儿子前年换的」;T38、T46 的「儿子」是别家的 |
| xun-2 | 哥 | T89 | 「你哥托人捎话,说开春要回来一趟」;同一回合已有「有一日她进来」 |
| xun-2 | 她 = 妻 | 上一份底稿记 T107 / T119 | 「她」首现**未证实**(T89 已在) |

当时数值(附录 A.13 `q6vigor.py`):

| 局 · 回合 | 人生阶段 | 落账前 气力 / 念想 / 岔路 / 牵挂 | 模型提议气力 | 落账 |
|---|---|---|---:|---|
| xun-1 T36 | 末段之前 | 22 / 14 / 9 / 88 | 18 | 18,未钳 |
| xun-1 T110 | 末段 | 15 / 1 / 1 / 98 | 13 | 钳到 15 |
| xun-2 T89 | 末段 | 15 / 0 / 0 / 99 | 10 | 钳到 15 |
| xun-2 T107 / T119 | 末段 | 15 / 0 / 0 / 99 | 11 / 14 | 钳到 15 |

钳制总量:xun-2 T44 起 77 回合里,模型 77 回合都提议气力 <15,落账后 77 回合都是 15,issue 里「收束下限钳制」76 条;
xun-1 T44 起 77 回合里提议 <15 的 76 回合(首个 T45),落账 =15 的 77 回合,钳制 issue 75 条。

当回合注入的 life_sim 专属文本里与家人 / 收束有关的原文(`TurnPromptBuilder.java` life_sim 段;「家」的命中全部落在「玩家」二字里,没有直接提到家人的句子):

- (6)「一旦进入【末段】(第 44 回合起)或【收束段】(见第 8 条),【气力不得低于 15】——老死不是生命力耗到零…」
- (3) 念想在【熄了】档:「四个选项【一个都不得】引入」新的人 —— **约束的是选项,不是叙事**。两局家人首现时念想都在熄了档。
- (8) 收束段「不再展开新的人」—— 只在按了 X 之后生效,基线没按过。
- 人生时钟在 T88、T110 仍写「全局预期在第 45-55 回合走到寿终」。
- 牵挂 ties 在【深系】档:「你的很多事,已经不只是你自己的事」;ties 意象:「碗一直摆四副」。

---

## 五、前后对比方法与 T0 兼容

### 5.1 `--resume-t0` 实际流程(README 对照代码)

1. 人工把原套件每槽位保留局的 T0 拷成新 store 下的 `<saveId>.json`(`bakeoff/selfplay/README.md:167-182` 的内联脚本)。
2. 起 server:`FileSessionStore` 启动回载 → `:176` `isSaveDocument`(只看 world)→ `:182` `SessionDocument.decode` → **`Engine.restore`**(全量校验)。
3. `selfplay.py --resume-t0 OLD`:`_run_slot_resume`(`:591-632`)对每槽位先 `GET /state`,要求 turn 0,再 `_play`;选项种子沿用原套件 `suiteSeed` 与保留局 attempt。

T0 存档的键(附录 A.7):顶层 `schemaVersion, world, state, triggered, issues, currentActions, phaseHint`(**没有 `boxScene`**,基线里没有动物人生);
`state` 内 `turn, status, timeline, log, logSummary`。

### 5.2 新增账本字段时「缺了按空账本起」

- **`SessionDocument.isSaveDocument`**:只判 world(`:57-59`),**不用改**;它不看 state,也不看顶层其他键。
- **`Engine.restore`**:若新字段放在 `state` 内,今天的五条硬校验是逐键写死的(`:160-182`),**不会因为多一个未知键而拒载**,
  但也不会读它 —— 需要新增一段「缺省 = 空账本」的读取;若照现有五条的写法写成硬校验,T0 与所有旧存档都会被拒载。
  若放在顶层(像 `boxScene` 那样由 `SessionDocument` 处理),`SessionDocument.decode` 是落点,先例是 `boxScene` 的「键不在 = 旧局」(CONTEXT §三.17 ADR-028 补充)。
- **其他也走这条加载路径的地方**(都经 `SessionDocument.decode` → `Engine.restore`):`FileSessionStore:176/182`、`JdbcSessionStore:178`、
  `SessionExporter:92`、`SessionImporter:208/218`;测试侧 `TraceReplayer`、`TraceRecheck`(都解码轨迹的 `pre`,而旧轨迹的 `pre` 也没有新键)、`DataStats`、`SelfPlayDraftTool`。
- 写出侧:`SessionDocument.encode` 一旦写出新键,`TraceFixtureReplayTest` 的 post sha 会变(§1.6)。

### 5.3 哪些能脚本重算,哪些要人工

| 类 | 能否机械重算 | 命令(本稿附录) |
|---|---|---|
| token、修复、降级、durMs、成本 | 能 | A.4 `traceStats.py`、A.5 `durreg.py`、A.6 `cost.py`、A.14 `degraded.py` |
| prompt 重渲染与 contextJson 占比 | 能(需当前代码能重渲染新轨迹;加了 prompt 段后,**旧轨迹**的 sha 会对不上,只能重渲染新套件) | A.1 `PromptDump.java`、A.2 `ctxshare.py`、A.3 `ctxparts.py` |
| 物品名在叙事 / 所选行动里出现的回合 | 能 | A.9 `invproxy.py` |
| 某事实在 prompt 的位置与次数 | 能(关键词要人给) | A.8 `q3.py` |
| timeline 对照、气力钳制、家人称谓首现 | 能(关键词要人给) | A.10、A.13、A.11–A.12 |
| inventory T0 与终局是否相同 | 能 | A.7 `invT0.py` |
| 上一份底稿的「跨窗提及 / 回访 / 通道」计数 | **今天不能**:定位脚本与别名表不在仓库(置顶 6) | — |
| ② 矛盾的判定、类型归属、「推测」标注 | **必须人工**:要读上下文判断是不是同一实体、是不是错 | — |
| 「正确答案」是什么 | **必须人工**(xun-2 棉袄就有两说) | — |

---

## 六、开放问题(只列,不作答)

- **O-1**:上一份底稿的定位脚本与别名表要不要补进仓库?不补,加账本前后的跨窗计数没法用同一把尺子量(置顶 6)。
- **O-2**:xun-2 的 T0 世界自相矛盾(background 父亲改小 / inventory 母亲改小)。world-gen 产出的世界内部不一致,算不算记忆账本要管的事?
- **O-3**:21 次物品矛盾全部出在「所选行动点名了这件物品」的回合。随机玩家反复点名同一件 inventory 物品,这个形状在真人玩家那里是否也成立?
- **O-4**:基线里把 timeline 当载体的世界一局都没有,《寻常》收束段也没触发过。Q-5「矛盾是否集中在载体世界」要不要先补一套含《派单》《七日》《验讫》的基线?
- **O-5**:「三楼」出现在每一份《寻常》prompt 的骨架意象里。骨架意象与世界设定冲突时,要不要当作单独一类?
- **O-6**:另起调用若挂在置相位之后异步执行,`Engine` 的并发写是否安全,需要专门核实。
- **O-7**:修复 42 次的失败尾巴原文没进轨迹,拆不到字段。要不要在评估「尾巴变长后修复率」之前先让轨迹记下它?
- **O-8**:基线没有首字时间。评估「尾巴变长对体验的影响」时,看首字还是看 delta 到达?
- **O-9**:《寻常》末段新冒出来的家人(老伴 T36 在末段之前)与念想在【熄了】档同时出现。【熄了】档只约束选项、不约束叙事,这是不是 Q-6 的一部分?

---

## 附录 · 命令、脚本原文与原始输出

> 所有脚本在 `$SP`(仓库外的会话临时目录)里运行,只读基线 `~/aiuniverse-selfplay/20261123-a/`;脚本与输出原样附在下面,供复算。
> `$SP` 的绝对路径因会话而异,复算时换成自己的临时目录即可。附录 A.0 是汇总输出文件 `all.out` 的全文(按运行顺序,每段以 `$ 命令` 开头;正文里「附录 A.n」指脚本原文,它的输出在 A.0 里按脚本名找),A.1 起是各脚本原文。

### A.0 运行步骤与汇总输出 `all.out`(原样)

准备(在 `server/` 下;离线编译,不起 server、不调模型):

```bash
mvn -q -o -DskipTests compile
mvn -q -o dependency:build-classpath -Dmdep.outputFile=$SP/cp.txt
cd $SP/promptdump && CP=$(cat $SP/cp.txt):<仓库>/server/target/classes && javac -d out -cp "$CP" src/com/aiuniverse/server/eventloop/PromptDump.java
B=$HOME/aiuniverse-selfplay/20261123-a/traces
java -cp "$CP:out" com.aiuniverse.server.eventloop.PromptDump $SP/prompts \
  gui-1 $B/fc240a2a-f0f5-4f1e-a7cb-fd52d77df79a.trace.jsonl xiu-1 $B/aaf1ce9c-048c-452d-a603-d196fbe3737e.trace.jsonl \
  xiu-2 $B/aa7d8487-d6e3-40d5-8ccd-6eecd27a5b85.trace.jsonl rong-1 $B/f0519f1a-35b9-4521-bd51-be1012463847.trace.jsonl \
  xiu-long $B/b29dd808-3e7a-4628-8141-1e74775051bd.trace.jsonl xun-1 $B/dd496b7a-69eb-4038-b5c7-018d6f6eea22.trace.jsonl \
  xun-2 $B/c8922b07-ebf6-41ff-92e4-fa50387fa6c2.trace.jsonl > $SP/promptdump.tsv 2> $SP/promptdump.err
```

`promptdump.err` 为空(0 字节);`promptdump.tsv` 572 行(表头 + 571)。其余命令都在 `$SP` 下运行,`all.out` 里每段以 `$ 命令` 开头:

```text
$ awk -F"	" 'NR>1{n++; if($4=="true")ok++; if($7<0)miss++} END{print "rows",n,"shaOk",ok,"ctxNotFound",miss+0}' promptdump.tsv
rows 571 shaOk 571 ctxNotFound 0

$ python3 ctxshare.py promptdump.tsv
slot	n	prompt_mean	prompt_max	ctx_mean	ctx_max	share_mean	share_min	share_max	after_ctx_mean	after_ctx_max
gui-1	31	5320	5545	3012	3234	0.565	0.486	0.583	156	171
xiu-1	60	5870	6143	2989	3250	0.508	0.410	0.529	224	238
xiu-2	60	6213	6453	3331	3567	0.535	0.447	0.553	225	239
rong-1	60	7356	7785	3538	3946	0.480	0.393	0.507	257	278
xiu-long	120	6045	6587	3164	3705	0.522	0.403	0.562	224	239
xun-1	120	9850	10354	4070	4575	0.413	0.318	0.442	263	271
xun-2	120	9729	10258	3951	4474	0.406	0.318	0.436	261	271
ALL	571	7717	10354	3550	4575	0.472	0.318	0.583	240	278

$ python3 ctxparts.py prompts promptdump.tsv
slot	ctx_mean	world部分_mean	state部分_mean	log_mean	logSummary_mean/max	inventory_mean
gui-1	3012	2106	906	728	83/198	36
xiu-1	2989	1919	1070	777	197/430	33
xiu-2	3331	2249	1082	786	197/430	25
rong-1	3538	2388	1150	859	197/430	25
xiu-long	3164	1861	1303	768	437/926	26
xun-1	4070	2623	1447	916	437/926	31
xun-2	3951	2621	1330	801	437/926	28
ALL	3550	2296	1254	816	342/926	29

$ python3 traceStats.py
== 主调用 token(每回合一条)  mean	median	max
slot	n	prompt(mean/med/max)			completion(mean/med/max)			cacheHit/prompt
gui-1	31	2959	3046	3122	324	329	380	0.476
xiu-1	60	3391	3418	3570	351	350	408	0.486
xiu-2	60	3683	3750	3877	355	354	430	0.461
rong-1	60	4382	4404	4740	403	399	672	0.488
xiu-long	120	3549	3552	3958	348	350	423	0.469
xun-1	120	5655	5660	6040	352	355	422	0.307
xun-2	120	5576	5597	5965	336	331	422	0.551
ALL	571	4471	4335	6040	352	350	672	0.450

== 修复调用 token
calls 42 names Counter({'主调用': 571, '修复': 42})
prompt 394	386	623 completion 253	252	289

== durMs(每回合)mean	median	p90	max
gui-1	31	3512	3404	4363	6388
xiu-1	60	3802	3531	5035	5944
xiu-2	60	3992	3581	5673	7079
rong-1	60	4492	4058	6490	7130
xiu-long	120	3953	3527	5620	8327
xun-1	120	3848	3658	5004	6880
xun-2	120	3789	3522	5113	6927
ALL	571	3917	3601	5487	8327

== 修复(repairErrors 非空的回合)与降级
repairTurns 42 {'gui-1': 3, 'xiu-1': 5, 'xiu-2': 9, 'rong-1': 11, 'xiu-long': 14}
repair outcome {('settled', None): 42}
  42	结构化尾巴非合法
raw messages:
  42	结构化尾巴非合法 JSON

== degraded
xiu-long T25 no_structured_tail repairErrors= None streamedNarrativeChars= 120
xun-1 T5 no_structured_tail repairErrors= None streamedNarrativeChars= 212

$ python3 cost.py
gui-1 31 mean=0.5996分 max=0.9203分 局合计=0.1859元
rong-1 60 mean=0.8325分 max=1.3255分 局合计=0.4995元
xiu-1 60 mean=0.6580分 max=0.9809分 局合计=0.3948元
xiu-2 60 mean=0.7307分 max=1.1017分 局合计=0.4384元
xiu-long 120 mean=0.6948分 max=1.0687分 局合计=0.8338元
xun-1 120 mean=1.0724分 max=1.1847分 局合计=1.2869元
xun-2 120 mean=0.7816分 max=0.8957分 局合计=0.9379元
ALL 571 mean=0.8016分 median=0.7661分 max=1.3255分 合计=4.5772元

$ python3 invproxy.py
xiu-2	「灵石」	叙事含:7 [5, 6, 13, 18, 29, 30, 37]
		所选行动文本含:3 [5, 29, 37]
xiu-2	「半块下品灵石」	叙事含:3 [5, 29, 37]
		所选行动文本含:3 [5, 29, 37]
xiu-1	「聚气丹」	叙事含:7 [3, 6, 12, 15, 24, 30, 35]
		所选行动文本含:7 [3, 12, 15, 24, 30, 35, 36]
xiu-1	「半瓶聚气丹」	叙事含:4 [3, 15, 24, 35]
		所选行动文本含:1 [24]
xiu-long	「回气散」	叙事含:13 [9, 15, 21, 28, 35, 44, 50, 51, 69, 89, 101, 107, 115]
		所选行动文本含:12 [9, 15, 21, 28, 35, 44, 50, 69, 89, 101, 107, 115]
xiu-long	「半瓶回气散」	叙事含:12 [9, 15, 21, 28, 35, 44, 50, 69, 89, 101, 107, 115]
		所选行动文本含:9 [9, 15, 21, 35, 69, 89, 101, 107, 115]
rong-1	「定神丹」	叙事含:7 [3, 24, 26, 39, 48, 50, 59]
		所选行动文本含:7 [3, 24, 26, 39, 48, 50, 59]
rong-1	「半瓶定神丹」	叙事含:3 [24, 48, 59]
		所选行动文本含:4 [3, 39, 48, 59]

$ python3 q3.py prompts xun-1_T34::二楼西户 xun-1_T35::二楼西户 xun-1_T45::二楼西户 xun-1_T64::二楼西户 xun-1_T34::三楼 xun-1_T35::三楼 xun-1_T45::三楼 xun-1_T64::三楼 xun-2_T37::改小 xun-2_T37::棉袄 xun-2_T37::母亲的旧棉袄 xun-2_T37::母亲那件旧棉袄 xun-2_T37::父亲 xiu-1_T15::最后一粒 xiu-1_T15::聚气丹
xun-1_T34	「二楼西户」	次数=1	prompt总长=9606
    @5798	离末尾=3808	段=contextJson.background
xun-1_T35	「二楼西户」	次数=1	prompt总长=9659
    @5798	离末尾=3861	段=contextJson.background
xun-1_T45	「二楼西户」	次数=1	prompt总长=9700
    @5793	离末尾=3907	段=contextJson.background
xun-1_T64	「二楼西户」	次数=1	prompt总长=9852
    @5793	离末尾=4059	段=contextJson.background
xun-1_T34	「三楼」	次数=1	prompt总长=9606
    @354	离末尾=9252	段=骨架/指令段
xun-1_T35	「三楼」	次数=2	prompt总长=9659
    @354	离末尾=9305	段=骨架/指令段
    @9200	离末尾=459	段=contextJson.state.log[T34]
xun-1_T45	「三楼」	次数=1	prompt总长=9700
    @354	离末尾=9346	段=骨架/指令段
xun-1_T64	「三楼」	次数=1	prompt总长=9852
    @354	离末尾=9498	段=骨架/指令段
xun-2_T37	「改小」	次数=2	prompt总长=9735
    @5685	离末尾=4050	段=contextJson.background
    @6016	离末尾=3719	段=contextJson.inventory
xun-2_T37	「棉袄」	次数=7	prompt总长=9735
    @5683	离末尾=4052	段=contextJson.background
    @6020	离末尾=3715	段=contextJson.inventory
    @8184	离末尾=1551	段=contextJson.timeline
    @8586	离末尾=1149	段=contextJson.state.log[T33]
    @9218	离末尾=517	段=contextJson.state.log[T36]
    @9329	离末尾=406	段=contextJson.state.log[T36]
    @9717	离末尾=18	段=尾部(档位/时钟/状态行/推进句+所选行动)
xun-2_T37	「母亲的旧棉袄」	次数=1	prompt总长=9735
    @8180	离末尾=1555	段=contextJson.timeline
xun-2_T37	「母亲那件旧棉袄」	次数=1	prompt总长=9735
    @8581	离末尾=1154	段=contextJson.state.log[T33]
xun-2_T37	「父亲」	次数=1	prompt总长=9735
    @5660	离末尾=4075	段=contextJson.background
xiu-1_T15	「最后一粒」	次数=1	prompt总长=5785
    @4983	离末尾=802	段=contextJson.state.log[T12]
xiu-1_T15	「聚气丹」	次数=3	prompt总长=5785
    @3084	离末尾=2701	段=contextJson.inventory
    @4987	离末尾=798	段=contextJson.state.log[T12]
    @5771	离末尾=14	段=尾部(档位/时钟/状态行/推进句+所选行动)

$ python3 q5timeline.py
xiu-2 T22 上回合timeline(进prompt): '大比将启，你借参须汤劲勉强压住伤势，正被逼着往演武台去。' | 本回合写: '大比首轮已开，你藏在演武台侧后偷看旁人应试，暗中记下起手诀窍。'
xiu-2 T59 上回合timeline(进prompt): '你借残丹余力勉强聚回一缕灵力，门外脚步声停而复去，柴房静得反常。' | 本回合写: '你养稳了那一缕灵力，柴房外寂静无声，静得反常。'
xiu-2 T60 上回合timeline(进prompt): '你养稳了那一缕灵力，柴房外寂静无声，静得反常。' | 本回合写: '你伏低将养，气力稍回，墙根下却传来不明的窸窣声。'
xiu-long T29 上回合timeline(进prompt): '第一日夜，你倾尽回气散强撑起最后一线灵力，妖兽已看清你的方位，正缓缓逼近。' | 本回合写: '第一夜将尽，黑影妖兽被你拼死刺毙，你重伤压在兽尸之下，气若游丝。'
xiu-long T30 上回合timeline(进prompt): '第一夜将尽，黑影妖兽被你拼死刺毙，你重伤压在兽尸之下，气若游丝。' | 本回合写: '第一夜将尽，你伏在兽尸余温中吊住一口气，灵力仅回一线，园中灯火未熄。'
xiu-long T31 上回合timeline(进prompt): '第一夜将尽，你伏在兽尸余温中吊住一口气，灵力仅回一线，园中灯火未熄。' | 本回合写: '第一夜将尽，你以守园令牌勉强传讯，灵力又耗一线，援手未明，园中灯火未熄。'
xiu-long T76 上回合timeline(进prompt): '第一夜将尽，你伏在灯下省力守火，浓影在光外徘徊，天边微现松动。' | 本回合写: '第一夜熬到天亮，浓影随晨雾散去，你伏在将熄的火堆旁，气若游丝却仍守住了灯火。'
xiu-long T77 上回合timeline(进prompt): '第一夜熬到天亮，浓影随晨雾散去，你伏在将熄的火堆旁，气若游丝却仍守住了灯火。' | 本回合写: '第一夜熬到天亮，你借晨露勉强引气入体，气海稍润，山道上却有脚步声渐近。'
xiu-long T79 上回合timeline(进prompt): '天亮后你伏进药丛藏身，一名同样持守园令牌的不明来客停在园门外，打量着被火烧过的地面。' | 本回合写: '天亮后你伏在药丛中不动，持守园令牌的不明来客推门探看，脚步迟疑未入。'
xiu-long T86 上回合timeline(进prompt): '你横剑拦门强撑不退，雾中来客一时驻足观望，未进也未退。' | 本回合写: '你举牌横剑不退，来人碍于宗门规矩暂退半步，却仍在雾中观望不去。'
xiu-long T87 上回合timeline(进prompt): '你举牌横剑不退，来人碍于宗门规矩暂退半步，却仍在雾中观望不去。' | 本回合写: '你以半寸剑锋逼退雾中来人，他们暂退下山，药园残灯下只剩你一人撑立。'
xiu-long T88 上回合timeline(进prompt): '你以半寸剑锋逼退雾中来人，他们暂退下山，药园残灯下只剩你一人撑立。' | 本回合写: '你护住残灯坐守至夜半，雾退声息，然气血将尽，只凭一线灯火与半口气撑着。'
xiu-long T89 上回合timeline(进prompt): '你护住残灯坐守至夜半，雾退声息，然气血将尽，只凭一线灯火与半口气撑着。' | 本回合写: '你服下最后一份回气散，勉强压住伤势，护着残灯守到夜半，雾退声息，却再无余药可依。'
xiu-long T90 上回合timeline(进prompt): '你服下最后一份回气散，勉强压住伤势，护着残灯守到夜半，雾退声息，却再无余药可依。' | 本回合写: '你借灵草月华勉强回补了一丝灵力，丹田稍润，护灯守到天将破晓。'
xiu-long T93 上回合timeline(进prompt): '天光渐亮，你清点灵草，损毁不过两三成，园尚完好。' | 本回合写: '天光大亮，执事验园，园虽微损而人尚存，七日守夜将成。'
xiu-long T94 上回合timeline(进prompt): '天光大亮，执事验园，园虽微损而人尚存，七日守夜将成。' | 本回合写: '天光大亮，执事验园，园虽微损而人尚存，七日守夜将成。'
xiu-long T95 上回合timeline(进prompt): '天光大亮，执事验园，园虽微损而人尚存，七日守夜将成。' | 本回合写: '天光大亮，你自陈守园不力，执事押你往执事堂听候长老发落。'
xiu-long T97 上回合timeline(进prompt): '你随执事抵达执事堂外，静候长老发落。' | 本回合写: '执事堂内长老免了废修之罚,改判补种灵草再守三夜,你领命退出堂外。'
xun-2 T115 上回合timeline(进prompt): '开春后她修了老树，他在墙根坐着看，腿已站不稳，只等今年发不发新芽' | 本回合写: '开春修完树的枝子收进了柴垛，他坐在院里看了一天，夜里咳了几回，只等今年发不发新芽'
xun-2 T116 上回合timeline(进prompt): '开春修完树的枝子收进了柴垛，他坐在院里看了一天，夜里咳了几回，只等今年发不发新芽' | 本回合写: '入冬前咳得重了，她守着熬药，把铁盒里最后半勺糖也刮给了他'
xun-2 T117 上回合timeline(进prompt): '入冬前咳得重了，她守着熬药，把铁盒里最后半勺糖也刮给了他' | 本回合写: '药喝到第三日咳轻了些，人却更没力气，窗纸糊上，老树发了芽'

$ python3 q6vigor.py
xun-2 T86 pre {'vigor': 15, 'longing': 0, 'crossroads': 0, 'ties': 99} post {'vigor': 15.0, 'longing': 0.0, 'crossroads': 0.0, 'ties': 99.0} 提议vigor 11
xun-2 T87 pre {'vigor': 15, 'longing': 0, 'crossroads': 0, 'ties': 99} post {'vigor': 15.0, 'longing': 0.0, 'crossroads': 0.0, 'ties': 99.0} 提议vigor 11
xun-2 T88 pre {'vigor': 15, 'longing': 0, 'crossroads': 0, 'ties': 99} post {'vigor': 15.0, 'longing': 0.0, 'crossroads': 0.0, 'ties': 99.0} 提议vigor 11
xun-2 T89 pre {'vigor': 15, 'longing': 0, 'crossroads': 0, 'ties': 99} post {'vigor': 15.0, 'longing': 0.0, 'crossroads': 0.0, 'ties': 99.0} 提议vigor 10
xun-2 T90 pre {'vigor': 15, 'longing': 0, 'crossroads': 0, 'ties': 99} post {'vigor': 15.0, 'longing': 0.0, 'crossroads': 0.0, 'ties': 99.0} 提议vigor 12
xun-2 T106 pre {'vigor': 15, 'longing': 0, 'crossroads': 0, 'ties': 99} post {'vigor': 15.0, 'longing': 0.0, 'crossroads': 0.0, 'ties': 99.0} 提议vigor 12
xun-2 T107 pre {'vigor': 15, 'longing': 0, 'crossroads': 0, 'ties': 99} post {'vigor': 15.0, 'longing': 0.0, 'crossroads': 0.0, 'ties': 99.0} 提议vigor 11
xun-2 T118 pre {'vigor': 15, 'longing': 0, 'crossroads': 0, 'ties': 99} post {'vigor': 15.0, 'longing': 0.0, 'crossroads': 0.0, 'ties': 99.0} 提议vigor 11
xun-2 T119 pre {'vigor': 15, 'longing': 0, 'crossroads': 0, 'ties': 99} post {'vigor': 15.0, 'longing': 0.0, 'crossroads': 0.0, 'ties': 99.0} 提议vigor 14
xun-2 T44+回合 77 提议气力<15 77 首 [44] 末 [120] 落账后气力=15 77 | 末回合 pre.issues 里收束下限钳制 76 首 ['T44 vigor 收束下限钳制 11->15'] 末 ['T119 vigor 收束下限钳制 14->15']
xun-1 T35 pre {'vigor': 7, 'longing': 6, 'crossroads': 3, 'ties': 92} post {'vigor': 22.0, 'longing': 14.0, 'crossroads': 9.0, 'ties': 88.0} 提议vigor 22
xun-1 T36 pre {'vigor': 22, 'longing': 14, 'crossroads': 9, 'ties': 88} post {'vigor': 18.0, 'longing': 13.0, 'crossroads': 8.0, 'ties': 88.0} 提议vigor 18
xun-1 T37 pre {'vigor': 18, 'longing': 13, 'crossroads': 8, 'ties': 88} post {'vigor': 15.0, 'longing': 11.0, 'crossroads': 7.0, 'ties': 89.0} 提议vigor 15
xun-1 T109 pre {'vigor': 15, 'longing': 1, 'crossroads': 1, 'ties': 98} post {'vigor': 15.0, 'longing': 1.0, 'crossroads': 1.0, 'ties': 98.0} 提议vigor 13
xun-1 T110 pre {'vigor': 15, 'longing': 1, 'crossroads': 1, 'ties': 98} post {'vigor': 15.0, 'longing': 1.0, 'crossroads': 1.0, 'ties': 98.0} 提议vigor 13
xun-1 T111 pre {'vigor': 15, 'longing': 1, 'crossroads': 1, 'ties': 98} post {'vigor': 15.0, 'longing': 1.0, 'crossroads': 1.0, 'ties': 98.0} 提议vigor 12
xun-1 T44+回合 77 提议气力<15 76 首 [45] 末 [120] 落账后气力=15 77 | 末回合 pre.issues 里收束下限钳制 75 首 ['T45 vigor 收束下限钳制 14->15'] 末 ['T119 vigor 收束下限钳制 13->15']

$ python3 durreg.py
n=527 durMs ≈ 764 + 8.62*completionTokens  r=0.37

$ python3 degraded.py
xiu-long T25 no_structured_tail completionTokens= 102 叙事末 40 字: '住，四爪一蹬扑将上来。你侧身让过爪锋，剑脊顺着它的来势滑下，只求卸力，不求伤敌。'
xun-1 T5 no_structured_tail completionTokens= 158 叙事末 40 字: '放笔。厂里的大喇叭又响了一个冬天，开春的时候，卫东他爸调去了别的车间，车不开了。'

$ python3 q3.py prompts xiu-1_T12::最后一粒 xiu-1_T30::最后 xiu-1_T35::最后 xiu-2_T29::伙房 xiu-2_T39::演武 xiu-2_T58::烧 rong-1_T24::两粒 rong-1_T39::最后一粒 rong-1_T48::最后 rong-1_T59::最后 xiu-long_T101::余药 xiu-long_T101::最后 xun-1_T112::锯 xun-2_T91::挪 xun-2_T119::进门
xiu-1_T12	「最后一粒」	次数=1	prompt总长=5725
    @5709	离末尾=16	段=尾部(档位/时钟/状态行/推进句+所选行动)
xiu-1_T30	「最后」	次数=0	prompt总长=5909
xiu-1_T35	「最后」	次数=1	prompt总长=5958
    @4931	离末尾=1027	段=contextJson.state.log[T31]
xiu-2_T29	「伙房」	次数=0	prompt总长=6281
xiu-2_T39	「演武」	次数=0	prompt总长=6393
xiu-2_T58	「烧」	次数=1	prompt总长=6433
    @939	离末尾=5494	段=骨架/指令段
rong-1_T24	「两粒」	次数=0	prompt总长=7352
rong-1_T39	「最后一粒」	次数=0	prompt总长=7409
rong-1_T48	「最后」	次数=0	prompt总长=7525
rong-1_T59	「最后」	次数=0	prompt总长=7700
xiu-long_T101	「余药」	次数=0	prompt总长=6450
xiu-long_T101	「最后」	次数=0	prompt总长=6450
xun-1_T112	「锯」	次数=0	prompt总长=10329
xun-2_T91	「挪」	次数=1	prompt总长=9939
    @9933	离末尾=6	段=尾部(档位/时钟/状态行/推进句+所选行动)
xun-2_T119	「进门」	次数=0	prompt总长=10258
$ python3 invT0.py
xiu-1 T0 inventory = ["半瓶聚气丹", "一柄缺口青锋剑", "师门残卷《枯云吐纳诀》"] | 终局存档 turn 60 相同 | T0 顶层键 ['currentActions', 'issues', 'phaseHint', 'schemaVersion', 'state', 'triggered', 'world'] | T0 state 键 ['log', 'logSummary', 'status', 'timeline', 'turn']
xiu-2 T0 inventory = ["半块下品灵石", "旧铜镜", "残破心法帛书"] | 终局存档 turn 60 相同 | T0 顶层键 ['currentActions', 'issues', 'phaseHint', 'schemaVersion', 'state', 'triggered', 'world'] | T0 state 键 ['log', 'logSummary', 'status', 'timeline', 'turn']
xiu-long T0 inventory = ["宗门制式青锋剑", "守园令牌", "半瓶回气散"] | 终局存档 turn 120 相同 | T0 顶层键 ['currentActions', 'issues', 'phaseHint', 'schemaVersion', 'state', 'triggered', 'world'] | T0 state 键 ['log', 'logSummary', 'status', 'timeline', 'turn']
gui-1 T0 inventory = ["塑封《夜班须知》", "店长留下的旧钥匙串", "半杯凉掉的速溶咖啡"] | 终局存档 turn 31 相同 | T0 顶层键 ['currentActions', 'issues', 'phaseHint', 'schemaVersion', 'state', 'triggered', 'world'] | T0 state 键 ['log', 'logSummary', 'status', 'timeline', 'turn']
rong-1 T0 inventory = ["残破玉简", "半瓶定神丹", "一缕残魂信物"] | 终局存档 turn 60 相同 | T0 顶层键 ['currentActions', 'issues', 'phaseHint', 'schemaVersion', 'state', 'triggered', 'world'] | T0 state 键 ['log', 'logSummary', 'status', 'timeline', 'turn']
xun-1 T0 inventory = ["一张家属楼出入证", "母亲给的五分钱", "一辆小三轮车"] | 终局存档 turn 120 相同 | T0 顶层键 ['currentActions', 'issues', 'phaseHint', 'schemaVersion', 'state', 'triggered', 'world'] | T0 state 键 ['log', 'logSummary', 'status', 'timeline', 'turn']
xun-2 T0 inventory = ["母亲改小的旧棉袄", "半块干馍", "放羊用的短鞭"] | 终局存档 turn 120 相同 | T0 顶层键 ['currentActions', 'issues', 'phaseHint', 'schemaVersion', 'state', 'triggered', 'world'] | T0 state 键 ['log', 'logSummary', 'status', 'timeline', 'turn']
$ python3 q6first.py
xun-2 「老伴」 叙事含回合数 0 首 []
xun-2 「媳妇」 叙事含回合数 1 首 [81]
xun-2 「妻」 叙事含回合数 0 首 []
xun-2 「哥」 叙事含回合数 1 首 [89]
xun-2 「儿子」 叙事含回合数 0 首 []
xun-2 「闺女」 叙事含回合数 0 首 []
xun-2 「孙」 叙事含回合数 0 首 []
xun-2 background 含: []
xun-1 「老伴」 叙事含回合数 3 首 [36, 46, 64]
xun-1 「儿子」 叙事含回合数 10 首 [38, 46, 64, 110, 114, 116]
xun-1 「闺女」 叙事含回合数 0 首 []
xun-1 「女儿」 叙事含回合数 0 首 []
xun-1 「孙」 叙事含回合数 1 首 [40]
xun-1 background 含: []
$ python3 q6ctx.py xun-1:36:老伴 xun-1:38:儿子 xun-1:40:孙 xun-1:46:儿子 xun-1:110:儿子 xun-2:81:媳妇 xun-2:89:哥
xun-2 T81 「媳妇」 …得慢，也过得匀。他每天把被垛垫高些，看院里的人出来进去：谁家媳妇端着盆去井台，谁家老汉扛着锄往地里走，走到墙根就咳两声。…
xun-2 T89 「哥」 …想起来动一动。有一日她进来，站在炕边看了他一会儿，忽然说，你哥托人捎话，说开春要回来一趟。他嗯了一声，不知道她听没听见。…
xun-1 T36 「老伴」 …那盆花是你老伴生前养的，叶子早黄了大半，土面裂着细纹。你端着脸盆去接水…
xun-1 T38 「儿子」 …，可原先那几片一直绿着，没黄。楼下的马扎被人收走了，说是那家儿子接老两口去城里住，走前把院里零碎都清了。你在窗边坐着，看…
xun-1 T40 「孙」 …底下，几个老人在晒太阳，你过去在小马扎上坐下，听他们说起谁家孙子考上学、谁家又搬走了。你插了两句话，声音不大，说完自己先…
xun-1 T46 「儿子」 …脚。你问了句今年白菜多少钱一斤，她絮絮说了半天，说今年贵，说儿子单位分的菜还没到，说她老伴的腿又不行了。你听着，偶尔点一…
xun-1 T110 「儿子」 …电视是儿子前年换的，薄薄一块，你到现在还不太会使。按了半天才亮起来…
```

### A.1 `PromptDump.java`

```java
package com.aiuniverse.server.eventloop;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.persistence.SessionDocument;
import com.aiuniverse.server.persistence.TurnTrace;
import com.aiuniverse.server.persistence.TurnTraceCodec;

import tools.jackson.databind.ObjectMapper;

/**
 * 勘察用(仓库外,不入库):逐行读 .trace.jsonl,按 pre + actionId 走生产同一个 renderTurnPrompt 重渲染回合 prompt,
 * 核对 promptSha256;输出 TSV:slot turn sha_ok prompt_chars ctx_chars ctx_start ctx_end ...;并把 prompt 全文落到 outDir。
 * 不调模型(LlmClient 为空实现,本程序从不调用 execute)。
 */
public class PromptDump {
	public static void main(String[] args) throws Exception {
		Path outDir = Path.of(args[0]);
		ObjectMapper mapper = new ObjectMapper();
		ArchetypeRegistry registry = new ArchetypeRegistry();
		EventLoopService svc = new EventLoopService((req, sink) -> { throw new IllegalStateException("no model"); },
				new TurnPromptBuilder(registry), mapper);
		System.out.println("slot\tturn\tpath\tshaOk\tpromptChars\tctxChars\tctxStart\tctxEnd\tbeforeCtx\tafterCtx");
		for (int i = 1; i < args.length; i += 2) {
			String slot = args[i];
			List<String> lines = Files.readAllLines(Path.of(args[i + 1]), StandardCharsets.UTF_8);
			for (String line : lines) {
				if (line.isBlank()) continue;
				TurnTrace t = TurnTraceCodec.decode(line, mapper);
				GameSession s = SessionDocument.decode(t.saveId(), t.pre(), mapper, registry);
				String ctx = s.engine().contextJson();
				String prompt = svc.renderTurnPrompt(s, t.actionId());
				boolean ok = TurnTrace.sha256Hex(prompt).equals(t.promptSha256());
				int start = prompt.indexOf(ctx);
				int end = start < 0 ? -1 : start + ctx.length();
				int turn = t.turnBefore() + 1;
				Files.writeString(outDir.resolve(slot + "_T" + turn + ".txt"), prompt, StandardCharsets.UTF_8);
				System.out.println(slot + "\t" + turn + "\t" + t.path() + "\t" + ok + "\t" + prompt.length() + "\t"
						+ ctx.length() + "\t" + start + "\t" + end + "\t" + start + "\t" + (prompt.length() - end));
			}
		}
	}
}
```

### A.2 `ctxshare.py`

```python
import csv,sys,statistics as st
rows=list(csv.DictReader(open(sys.argv[1]),delimiter='\t'))
by={}
for r in rows: by.setdefault(r['slot'],[]).append(r)
print("slot\tn\tprompt_mean\tprompt_max\tctx_mean\tctx_max\tshare_mean\tshare_min\tshare_max\tafter_ctx_mean\tafter_ctx_max")
allsh=[]
for s,rs in list(by.items())+[('ALL',rows)]:
    p=[int(r['promptChars']) for r in rs]; c=[int(r['ctxChars']) for r in rs]; a=[int(r['afterCtx']) for r in rs]
    sh=[ci/pi for ci,pi in zip(c,p)]
    print(f"{s}\t{len(rs)}\t{st.mean(p):.0f}\t{max(p)}\t{st.mean(c):.0f}\t{max(c)}\t{st.mean(sh):.3f}\t{min(sh):.3f}\t{max(sh):.3f}\t{st.mean(a):.0f}\t{max(a)}")
```

### A.3 `ctxparts.py`

```python
# 勘察用:把每回合 contextJson 拆成 world 部分 / state.log / state.logSummary / inventory,算字数(Java String 长度)。只读。
import csv,sys,os,json,statistics as st
P=sys.argv[1]; rows=list(csv.DictReader(open(sys.argv[2]),delimiter='\t'))
out={}
for r in rows:
    p=open(os.path.join(P,f"{r['slot']}_T{r['turn']}.txt"),encoding='utf-8').read()
    c=p[int(r['ctxStart']):int(r['ctxEnd'])]
    j=json.loads(c); s=j['state']
    enc=lambda o: json.dumps(o,ensure_ascii=False,separators=(',',':'))
    log=len(enc(s['log'])); summ=len(s['logSummary']); inv=len(enc(j['character'].get('inventory',[])))
    statepart=len(enc(s)); worldpart=len(c)-statepart
    out.setdefault(r['slot'],[]).append((len(c),worldpart,statepart,log,summ,inv))
print("slot\tctx_mean\tworld部分_mean\tstate部分_mean\tlog_mean\tlogSummary_mean/max\tinventory_mean")
for s,v in list(out.items())+[('ALL',[x for vv in out.values() for x in vv])]:
    m=lambda i: st.mean(x[i] for x in v)
    print(f"{s}\t{m(0):.0f}\t{m(1):.0f}\t{m(2):.0f}\t{m(3):.0f}\t{m(4):.0f}/{max(x[4] for x in v)}\t{m(5):.0f}")
```

### A.4 `traceStats.py`

```python
# 勘察用(仓库外):从基线轨迹算每回合 token / 时延 / 修复 / 降级。只读。
import json,sys,statistics as st,collections,glob,os,re
B=os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
slots={a['saveId']:s['slot'] for s in json.load(open(B+'/summary.json'))['slots'] for a in s['attempts']}
order=['gui-1','xiu-1','xiu-2','rong-1','xiu-long','xun-1','xun-2']
recs={s:[] for s in order}
for f in glob.glob(B+'/traces/*.trace.jsonl'):
    for l in open(f):
        if l.strip(): r=json.loads(l); recs[slots[r['saveId']]].append(r)
def q(v):
    v=sorted(v); return f"{st.mean(v):.0f}\t{st.median(v):.0f}\t{max(v)}"
print("== 主调用 token(每回合一条)  mean\tmedian\tmax")
print("slot\tn\tprompt(mean/med/max)\t\t\tcompletion(mean/med/max)\t\t\tcacheHit/prompt")
allp=[];allc=[];allh=[];
for s in order+['ALL']:
    rs=[r for k in (order if s=='ALL' else [s]) for r in recs[k]]
    mains=[c['usage'] for r in rs for c in (r['usage'] or []) if c['call']=='主调用']
    p=[u['promptTokens'] for u in mains]; c=[u['completionTokens'] for u in mains]; h=sum(u['cacheHitTokens'] for u in mains)/sum(p)
    print(f"{s}\t{len(mains)}\t{q(p)}\t{q(c)}\t{h:.3f}")
print("\n== 修复调用 token")
reps=[(slots[r['saveId']],r['turnBefore']+1,c['usage']) for k in order for r in recs[k] for c in (r['usage'] or []) if c['call']!='主调用']
print("calls",len(reps), "names",collections.Counter(c['call'] for k in order for r in recs[k] for c in (r['usage'] or [])))
if reps:
    print("prompt",q([u['promptTokens'] for _,_,u in reps]),"completion",q([u['completionTokens'] for _,_,u in reps]))
print("\n== durMs(每回合)mean\tmedian\tp90\tmax")
for s in order+['ALL']:
    rs=[r for k in (order if s=='ALL' else [s]) for r in recs[k]]
    d=sorted(r['durMs'] for r in rs); p90=d[int(0.9*(len(d)-1))]
    print(f"{s}\t{len(d)}\t{st.mean(d):.0f}\t{st.median(d):.0f}\t{p90}\t{max(d)}")
print("\n== 修复(repairErrors 非空的回合)与降级")
nrep=0; fieldc=collections.Counter(); perslot=collections.Counter(); outc=collections.Counter()
for k in order:
    for r in recs[k]:
        if r['repairErrors']:
            nrep+=1; perslot[k]+=1; outc[(r['path'],r['degradeReason'])]+=1
            for e in r['repairErrors']:
                fieldc[re.sub(r'/\d+','/<i>',e.split(':')[0].split(' ')[0])]+=1
print("repairTurns",nrep,dict(perslot)); print("repair outcome",dict(outc))
for f,n in fieldc.most_common(): print(f"  {n}\t{f}")
print("raw messages:")
msg=collections.Counter(re.sub(r'/\d+','/<i>',e) for k in order for r in recs[k] for e in (r['repairErrors'] or []))
for m,n in msg.most_common(): print(f"  {n}\t{m}")
print("\n== degraded")
for k in order:
    for r in recs[k]:
        if r['path']=='degraded': print(k,'T%d'%(r['turnBefore']+1),r['degradeReason'],'repairErrors=',r['repairErrors'],'streamedNarrativeChars=',len(r['streamedNarrative'] or ''))
```

### A.5 `durreg.py`

```python
# 勘察用:未修复的 settled 回合 durMs 对 completionTokens 的线性回归(只作量级参考)。只读。
import json,glob,os,statistics as st
B=os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
xs=[];ys=[]
for f in glob.glob(B+'/traces/*.trace.jsonl'):
    for l in open(f):
        r=json.loads(l)
        if r['path']=='settled' and not r['repairErrors']:
            xs.append(r['usage'][0]['usage']['completionTokens']); ys.append(r['durMs'])
mx=st.mean(xs); my=st.mean(ys)
b=sum((x-mx)*(y-my) for x,y in zip(xs,ys))/sum((x-mx)**2 for x in xs); a=my-b*mx
r=sum((x-mx)*(y-my) for x,y in zip(xs,ys))/(sum((x-mx)**2 for x in xs)*sum((y-my)**2 for y in ys))**.5
print(f"n={len(xs)} durMs ≈ {a:.0f} + {b:.2f}*completionTokens  r={r:.2f}")
```

### A.6 `cost.py`

```python
# 勘察用:按 application.yml 现值(CNY/1M:未命中 2.0、命中 0.04、输出 8.0)算基线每回合成本。只读。
import json,glob,os,statistics as st
B=os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
slots={a['saveId']:s['slot'] for s in json.load(open(B+'/summary.json'))['slots'] for a in s['attempts']}
MISS,HIT,OUT=2.0,0.04,8.0
def cost(u): return (u['cacheMissTokens']*MISS+u['cacheHitTokens']*HIT+u['completionTokens']*OUT)/1e6
per={}
for f in glob.glob(B+'/traces/*.trace.jsonl'):
    for l in open(f):
        r=json.loads(l); s=slots[r['saveId']]
        c=sum(cost(x['usage']) for x in r['usage']); per.setdefault(s,[]).append(c)
        chk=[x['usage'] for x in r['usage'] if x['usage']['cacheHitTokens']+x['usage']['cacheMissTokens']!=x['usage']['promptTokens']]
        assert not chk, chk
tot=[c for v in per.values() for c in v]
for s,v in sorted(per.items()): print(s,len(v),f"mean={st.mean(v)*100:.4f}分 max={max(v)*100:.4f}分 局合计={sum(v):.4f}元")
print("ALL",len(tot),f"mean={st.mean(tot)*100:.4f}分 median={st.median(tot)*100:.4f}分 max={max(tot)*100:.4f}分 合计={sum(tot):.4f}元")
```

### A.7 `invT0.py`

```python
import json, os
B = os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
s = json.load(open(os.path.join(B, 'summary.json'), encoding='utf-8'))
for e in s['slots']:
    a = next((x for x in e['attempts'] if x['attempt'] == e['kept']), None)
    if not a or not a.get('t0'):
        continue
    t0 = json.load(open(os.path.join(B, a['t0']), encoding='utf-8'))
    fin = json.load(open(os.path.join(B, 'store', a['saveId'] + '.json'), encoding='utf-8'))
    i0 = t0['world']['character'].get('inventory')
    i1 = fin['world']['character'].get('inventory')
    print(e['slot'], 'T0 inventory =', json.dumps(i0, ensure_ascii=False), '| 终局存档 turn', fin['state']['turn'], '相同' if i0 == i1 else '不同', '| T0 顶层键', sorted(t0.keys()), '| T0 state 键', sorted(t0['state'].keys()))
```

### A.8 `q3.py`

```python
# 勘察用:在重渲染的回合 prompt 里定位「正确答案」字串。输出:出现次数、每次出现所在段、离 prompt 末尾的字符数。只读。
import sys,os,json
P=sys.argv[1]
def seg(prompt,pos):
    ctx0=prompt.find('\n\n世界设定与当前状态(state 是真理之源):\n')
    if pos<ctx0: return '骨架/指令段'
    body=prompt[ctx0:]
    js_start=ctx0+len('\n\n世界设定与当前状态(state 是真理之源):\n')
    # contextJson 是一行 JSON;找其结束(下一个 \n\n)
    js_end=prompt.find('\n\n',js_start)
    if pos<js_end:
        sub=prompt[js_start:pos]
        for key in ['"state":','"endings":','"rules":','"character":','"world":']:
            i=sub.rfind(key)
            if i>=0:
                # 取最近的键
                pass
        keys=[(sub.rfind(k),k) for k in ['"world":{"title"','"background"','"tone"','"character"','"inventory"','"traits"','"rules"','"endings"','"state"','"timeline"','"logSummary"','"log"']]
        k=max(keys)[1]
        if k=='"log"':
            # 哪一回合的 log
            j=sub.rfind('{"turn":'); t=sub[j+8:sub.find(',',j)]
            return f'contextJson.state.log[T{t}]'
        return 'contextJson.'+k.strip('"').split('"')[0].replace('world":{','world.title')
    return '尾部(档位/时钟/状态行/推进句+所选行动)'
for spec in sys.argv[2:]:
    f,needle=spec.split('::')
    p=open(os.path.join(P,f+'.txt'),encoding='utf-8').read()
    occ=[]; i=p.find(needle)
    while i>=0: occ.append(i); i=p.find(needle,i+1)
    print(f"{f}\t「{needle}」\t次数={len(occ)}\tprompt总长={len(p)}")
    for o in occ: print(f"    @{o}\t离末尾={len(p)-o}\t段={seg(p,o)}")
```

### A.9 `invproxy.py`

```python
# 勘察用:inventory 条目字面在每回合叙事 / 所选行动文本里出现的回合(机械代理,不判对错)。只读。
import json,glob,os
B=os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
slots={a['saveId']:s['slot'] for s in json.load(open(B+'/summary.json'))['slots'] for a in s['attempts']}
items={'xiu-1':['聚气丹','半瓶聚气丹'],'xiu-2':['灵石','半块下品灵石'],'rong-1':['定神丹','半瓶定神丹'],'xiu-long':['回气散','半瓶回气散']}
for f in sorted(glob.glob(B+'/traces/*.trace.jsonl')):
    s=slots[os.path.basename(f).split('.')[0]]
    if s not in items: continue
    rs=[json.loads(l) for l in open(f)]
    for it in items[s]:
        nar=[r['turnBefore']+1 for r in rs if it in ((r['parsed'] or {}).get('narrative') or r.get('streamedNarrative') or '')]
        sel=[r['turnBefore']+1 for r in rs if any(a.get('id')==r['actionId'] and it in a.get('text','') for a in r['pre']['currentActions'])]
        print(f"{s}\t「{it}」\t叙事含:{len(nar)} {nar}\n\t\t所选行动文本含:{len(sel)} {sel}")
```

### A.10 `q5timeline.py`

```python
# 勘察用:时间类 ② 所在回合进 prompt 的上回合 timeline 与本回合写下的 timeline。只读。
import json,glob,os
B=os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
slots={a['saveId']:s['slot'] for s in json.load(open(B+'/summary.json'))['slots'] for a in s['attempts']}
want={'xiu-long':[29,30,31,76,77,79,86,87,88,89,90,93,94,95,97],'xiu-2':[22,59,60],'xun-2':[115,116,117]}
for f in sorted(glob.glob(B+'/traces/*.trace.jsonl')):
    s=slots[os.path.basename(f).split('.')[0]]
    if s not in want: continue
    for l in open(f):
        r=json.loads(l); b=r['turnBefore']+1
        if b in want[s]:
            print(s,f"T{b}","上回合timeline(进prompt):",repr(r['pre']['state']['timeline']),"| 本回合写:",repr((r['parsed'] or {}).get('stateUpdate',{}).get('timeline')))
```

### A.11 `q6first.py`

```python
# 勘察用:《寻常》两局叙事(settled 取 parsed.narrative,降级取 streamedNarrative)里家人称谓出现的回合。只读。
import json,glob,os,re
B=os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
slots={a['saveId']:s['slot'] for s in json.load(open(B+'/summary.json'))['slots'] for a in s['attempts']}
terms={'xun-1':['老伴','儿子','闺女','女儿','孙'],'xun-2':['老伴','媳妇','妻','哥','儿子','闺女','孙']}
for f in sorted(glob.glob(B+'/traces/*.trace.jsonl')):
    s=slots[os.path.basename(f).split('.')[0]]
    if s not in terms: continue
    rs=[json.loads(l) for l in open(f)]
    for t in terms[s]:
        hits=[r['turnBefore']+1 for r in rs if t in ((r.get('parsed') or {}).get('narrative') or r.get('streamedNarrative') or '')]
        print(s,f'「{t}」','叙事含回合数',len(hits),'首',hits[:6])
    bg=rs[0]['pre']['world']['world']['background']
    print(s,'background 含:',[t for t in terms[s] if t in bg])
```

### A.12 `q6ctx.py`

```python
# 勘察用:列出家人称谓命中处前后 30 字,供人工判断指的是谁。只读。
import json,glob,os,sys
B=os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
slots={a['saveId']:s['slot'] for s in json.load(open(B+'/summary.json'))['slots'] for a in s['attempts']}
want=[a.split(':') for a in sys.argv[1:]]  # slot:turn:term
for f in sorted(glob.glob(B+'/traces/*.trace.jsonl')):
    s=slots[os.path.basename(f).split('.')[0]]
    for r in (json.loads(l) for l in open(f)):
        n=r['turnBefore']+1
        nar=(r.get('parsed') or {}).get('narrative') or r.get('streamedNarrative') or ''
        for ws,wt,term in want:
            if ws==s and int(wt)==n:
                i=nar.find(term)
                while i>=0:
                    print(f'{s} T{n} 「{term}」 …{nar[max(0,i-30):i+30]}…'); i=nar.find(term,i+1)
```

### A.13 `q6vigor.py`

```python
# 勘察用:《寻常》家人首现回合前后数值、T44 起模型提议气力 <15 的回合数与落账后 =15 的回合数、钳制 issue 条数。只读。
import json,glob,os
B=os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
slots={a['saveId']:s['slot'] for s in json.load(open(B+'/summary.json'))['slots'] for a in s['attempts']}
want={'xun-1':[35,36,37,109,110,111],'xun-2':[86,87,88,89,90,106,107,118,119]}
for f in sorted(glob.glob(B+'/traces/*.trace.jsonl')):
    s=slots[os.path.basename(f).split('.')[0]]
    if s not in want: continue
    rs=[json.loads(l) for l in open(f)]
    for r in rs:
        b=r['turnBefore']+1
        if b in want[s]:
            print(s,f"T{b}","pre",r['pre']['world']['character']['attributes'],"post",r['post']['attributes'],"提议vigor",(r['parsed'] or {}).get('stateUpdate',{}).get('vigor'))
    late=[r for r in rs if r['turnBefore']+1>=44]
    below=[r['turnBefore']+1 for r in late if r['path']=='settled' and r['parsed']['stateUpdate'].get('vigor',99)<15]
    post15=sum(1 for r in late if r['post']['attributes']['vigor']==15.0)
    cl=[i for i in rs[-1]['pre']['issues'] if '收束下限钳制' in i]
    print(s,'T44+回合',len(late),'提议气力<15',len(below),'首',below[:1],'末',below[-1:],'落账后气力=15',post15,
          '| 末回合 pre.issues 里收束下限钳制',len(cl),'首',cl[:1],'末',cl[-1:])
```

### A.14 `degraded.py`

```python
# 勘察用:降级回合的原因、已流出叙事末尾与 usage。只读。
import json,glob,os
B=os.path.expanduser('~/aiuniverse-selfplay/20261123-a')
slots={a['saveId']:s['slot'] for s in json.load(open(B+'/summary.json'))['slots'] for a in s['attempts']}
for f in sorted(glob.glob(B+'/traces/*.trace.jsonl')):
    for l in open(f):
        r=json.loads(l)
        if r['path']=='degraded':
            print(slots[r['saveId']],'T%d'%(r['turnBefore']+1),r['degradeReason'],'completionTokens=',r['usage'][0]['usage']['completionTokens'],'叙事末 40 字:',repr(r['streamedNarrative'][-40:]))
```
