# ADR-040 · 视图 3 增量:《寻常》牵挂升档的回合清单由服务端记录并下发

- **日期**:2026-11-15
- **状态**:**已实施**(2026-11-15:刀 1–3 经 ff 合入 `main`(至 `a280fdc`),CI 通过(run `43694545342`:server `mvn test` / web lint + tsc + test + build / `npm audit` 三个 job 全绿);校勘已核、Felix 点头;**未部署**;前端未接;未跑真 key)
  ⚠️ **补充(2026-11-20,上一行原文不动)**:已于 2026-11-20 随 `080494f` 部署(Felix 亲手部署;校勘已核 `/actuator/info`:`build.commit = 080494f`、`build.time = 2026-11-20T06:28:42Z`)。同一次部署里前端已接上(ADR-020《寻常》局内皮肤一节,刀 2 `be27432`),上一行「前端未接」随之不再成立。
  ⚠️ 合并前的状态行,原文保留:**已实施**(2026-11-15:刀 1–3 见文末「实施记录」,分支 `feat/adr-040-xun-kept-turns`;**未合并、未部署**;前端未接;未跑真 key)
  ⚠️ 定稿时的状态行,原文保留:**已接受**(定稿 2026-11-15:待审六项由 Felix 2026-11-15 全部按草案提议定下,见文末「Felix 审定结果」。实现按「落地切分」进行)
  ⚠️ 草案时的原状态行,原文保留:提议(草案;只写 ADR、不动代码。方向由 Felix 2026-11-15 裁定:走后端记录。ties 的档口径由 Felix 2026-11-15 裁定为服务端口径;
  键名、已结束的局、空清单三项是本 ADR 提议的口径,待 Felix 审定,见文末「待 Felix 审定清单」)
- **决策者**:Felix

## 背景

《寻常》(`life_sim`)局内皮肤的记忆点已由 Felix 在样张里定下:标题下面一行刻痕,每回合多一道,越早的越淡;
**牵挂(`ties`)往上走一档的那一回合,那道刻痕长高,以后不再变淡**。这就要求前端在刷新、续局之后,仍然知道「哪几回合牵挂升过档」。

现在做不到:

- `AmbientProps.signatureTick` 只数本次装载以来的升档,首次装载一律不加,刷新就清零;
- 前端拿到的历史(ADR-025 `HistoryEventEntry`)只有 `narrative` / `playerAction`,没有逐回合的数值。

Felix 2026-11-15 裁定:**走后端记录**。不接受「刷新就没了」,也不接受「只闪一下」。

约束条件:

1. **只动视图 3**(CONTEXT §三.9 下发前端的消毒投影)和一行引擎 issue。**不新增落盘字段、不改 `Engine`**,只用已有的 `Engine.recordIssue`。
2. **不是 state schema 字段**,`schemaVersion` 仍 `"0.4"`(同 ADR-039 `timeLabel`、CONTEXT §三.14 `bands` 走 API DTO 的先例)。
3. **只对单体 `life_sim` 生效**。其他世界的三处响应(`init` / `state` / 回合 `delta`)逐字节不变,回合提示词逐字节不变。
4. 「档」用 registry 里 `ties` 的 `bands` 解析,**不在代码里写死数字**。
5. 前端怎么消费(`StatsProps` / `AmbientProps` 加什么可选字段、刻痕怎么画)**不在本 ADR**,归之后 ADR-020 的新一节(寻常局内皮肤)。
   本 ADR 只定 wire 形状与服务端算法。

### 勘察结论(只读 `main` `bb88938`,2026-11-15)

#### 勘察 0 · ADR 编号

`docs/adr/` 最新一份是 `ADR-039-client-view-time-label-and-action-tag.md`,本 ADR 取 040。

#### 勘察 1 · `ties` 的档表是 0 / 31 / 61,不是 30 / 60

`ArchetypeRegistry.ordinaryLife()` `:812`:`AttributeAxis.accumulating("ties", "牵挂", …).withBands(band(0, "轻身"), band(31, "有所系"), band(61, "深系"))`。
accumulation 轴的 `threshold` 是档的**下界**(含),所以三档是 [0,30] / [31,60] / [61,100](`AttributeAxis.bandRanges()` `:228` 的投影与此相同)。
brief 里的「30 / 60」只是概数。这一条恰好说明不该在代码里写死数字。

#### 勘察 2 · 档的口径:服务端取整,玩家看到的面板也取整;只有两处吃原始值

- **数值可以是小数**:`GameSchemas` `:287` 对 `stateUpdate` 只要求 `isNumber`;`Engine.apply` `:276–281` 读 `asDouble()`、只 clamp 不取整。
  所以 `ties` = 30.6 这种值会出现。
- **服务端**:提示词注入当前档时用 `a.resolveBand((int) Math.round(engine.attribute(a.key())))`(`TurnPromptBuilder` `:1152`)。
  `AttributeAxis.resolveBand` `:190` 自己只 clamp 不取整,取整由调用方做。
- **玩家看到的档也是取整口径**(Felix 2026-11-15 订正了我初稿勘察里「前端不取整」的说法,订正后的事实如下):
  `StatsPanel` 用 `useAnimatedValues` 返回的 `shown` 构造 `AxisView`(`StatsPanel.tsx` `:37`、`:41`)。`shown` 经 `rounded()` 逐键 `Math.round`
  (`useAnimatedValues.ts` `:106`、`:112`);`toAxisView` 的 `resolveBandLabel` / `resolveSeverity` 吃的就是取整后的值。
  所以 `ties` = 30.6 在面板上显示「有所系」,与服务端取整后的判档一致。
- **真正吃原始值、会落进 30–31 / 60–61 那段空隙的只有两处**:
  `useSignalCrossing.ts` `:61` / `:64`(`GameScreen.tsx` `:124` 传进去的是 `attributeValues`),以及 `theme/DebugPanel.tsx` `:51–55`。
  前端的 `resolveBand`(`bands.ts` `:16`)只认 `min ≤ v ≤ max`,30.6 不在任何一档里,返回 `null`。
  寻常没有 `signatureAxisKey`(`theme/registry.ts` `:87` 起的 `life_sim` 条目没有这个键),所以不经过前者。
  这是一个独立的潜在缺陷,会影响登记了签名轴、且模型给出小数的世界。**本 ADR 只把它列进挂账项,不修**。

**Felix 裁定(2026-11-15)**:`ties` 的档用服务端口径,也就是 `Math.round` 之后 `resolveBand`,取档序号,比较落账前后的序号。

#### 勘察 3 · 先例:两个落盘标记,都在 `apply` 之后、都用 `recordIssue`

`EventLoopService.landSettled` `:418–440`:先做落账前的改写(钳制、结局核对、七日兜底),再取 `profileBefore` / `coverBefore`,
`engine.apply`,然后 `markPaidanProfileCrossing(engine, profileBefore)` `:427`、`markQiriCoverDrop(engine, coverBefore)` `:428`。

- `markPaidanProfileCrossing` `:835`:前缀来自 `TurnPromptBuilder.paidanCrossingIssuePrefix(int)`(`"T" + turn + " 画像向上跨档"`),
  后面接 `" a→b"`;一回合连跨两档也只记一行。**它写死了 30 / 60**(`paidanProfileCrossedUp` 里 `before < 30 && after >= 30 || …`),
  用的是原始值,不查 bands。
- `markQiriCoverDrop` `:859`:形状相同,每局只记一次。
- 两者的回合号都取 `engine.turn()`。`Engine.apply` 第一步就是 `turn += 1`(`:269`),所以这个值是**刚落地的那一回合**。

本 ADR 的标记照这个形状写,只有一处不同:判档查 bands、用取整口径,不写死数字(约束 4、勘察 2 的裁定)。

#### 勘察 4 · issues 不进喂模型的视图(视图 2)

- 喂模型的状态是 `Engine.contextJson()` `:365` → `snapshot()` `:380–403`,内容是 world + `state{turn,status,timeline,logSummary,log}` + 当前数值。
  **不含 `issues`**。`issues` 只在 `toPersistedState()` `:252` 写进落盘文档。
- `TurnPromptBuilder` 读 `engine.issues()` 只有两处:`paidanMemoryLine` `:131` 和 `qiriMemoryLine` `:272`,各自只认自己世界的前缀,
  并且只对各自世界生效。新前缀谁都不认。
- ⇒ 新 issue 不改变任何一份回合提示词。证据就是上面两条,实施时再加一条测试直接证明(决策 7-B)。

#### 勘察 5 · ADR-031 回放:`post` 摘要含 issues

- 轨迹的 `pre` = `SessionDocument.encode(session)`(含 issues);`post` 摘要 = 落账后 `encode` 去掉 `phaseHint` 的 sha256(ADR-031 §1.2 表,`:93`)。
  档 1 回放:`decode(pre)` → 同一份 `landSettled` → 比 `post` 摘要(ADR-031 `:187`、`:374`)。
- 新标记写在 `landSettled` 里,线上和回放走同一份代码,所以**本 ADR 实施之后录的轨迹,回放结果是确定的**,包括那行 issue。
- **实施之前录的寻常轨迹,拿到新代码上回放**:凡是牵挂升档的回合,新代码会多记一行,`post` 摘要对不上。
  ADR-031 的定义是「在 `r.commit` 对应的代码上」回放(`:182`),所以这不违反契约,但会让「拿旧轨迹在新代码上复盘」在这些回合失败。
  《派单》《七日开张》加标记时是同一种情况。
- 提示词哈希复核(`TraceRecheck`:由 `pre` + `actionId` 重新渲染、比 `promptSha256`)不受影响,理由见勘察 4。
- 仓库里唯一的回归夹具 `trace-fixtures/first-real.jsonl` 是 `rules_creepy`,不受影响。

#### 勘察 6 · issues 的其他读者

| 读者 | 怎么读 | 受影响吗 |
|---|---|---|
| `TurnPromptBuilder.paidanMemoryLine` / `qiriMemoryLine` | 按各自前缀 `startsWith` | 否(前缀不同,世界不同) |
| `markQiriCoverDrop`(经 `qiriCoverDropAlreadyMarked`) | 按「底细跌破 20」前缀 | 否 |
| ADR-033 统计工具 `DataStats` `:281` | `issue.contains("ADR-010 gate")` / `contains("收束下限钳制")` 计数(`:56`、`:58`) | 否,只要新前缀和后缀**不含这两个子串**(决策 2 写死这一条) |
| `Engine.restore` `:188–206` | 原样回载 | 否 |
| 前端 | issues 从不下发(视图 3 `toClientState` 不含它) | 否 |

#### 勘察 7 · `recordIssue` 的 javadoc 已经不符合事实

`Engine.recordIssue` `:697–716` 的 javadoc 写着:issues 的语义是「引擎侧的数值异常记录」,「不是哪里想记一笔就记一笔的地方」;
「本刀唯一调用方 = `EventLoopService` 的收束气力下限钳制点」;「新增调用方须在 ADR 里说明它为何属于『数值异常』」。

实际的调用方(`EventLoopService` `:741`、`:772`、`:800`、`:817`、`:841`、`:865`)现在承载**两类**内容:

| 类 | 内容 | 调用点 |
|---|---|---|
| 数值异常 / 服务端干预 | 收束下限钳制、结局核对改判(《派单》《七日开张》)、七日怪点子兜底与钳制、「缺怪点子选项」 | `:741`、`:772`、`:800`、`:817` |
| **服务端判定的落盘标记** | 《派单》画像向上跨档、《七日开张》底细跌破 20 | `:841`、`:865` |

(`Engine` 自己写的「跳变过大」「ADR-010 gate 改判」属于第一类,不经 `recordIssue`。)

自《派单》起,第二类就已经存在,javadoc 没有跟着改。牵挂升档是**正常玩法**,不是数值异常;本 ADR 是**第三个**标记使用者。
本 ADR 不默认继承那句「须说明它为何属于数值异常」,在决策 6 正面处理。

#### 勘察 8 · 下发点与序列化

同 ADR-039 勘察 1 / 5:三处响应,改动点是 `GameInitService.init` `:94–95`、`GameInitService.resume` `:136–137`、`EventLoopService.buildDelta` `:596–598`。
`InitResponse` 的可空分量要标 `@JsonInclude(NON_NULL)`,否则多出 `"x":null`;`delta` 是手工拼的 `ObjectNode`,不 `put` 即可。

旧前端对新键的反应:`init` / `state` 是整体展开进状态,多一个键不影响;`h5GameApi.parseDelta` 把非结构键里 `typeof v === 'number'` 的收成数值轴
(ADR-039 勘察 5-4),**数组不是 number,会被忽略**。所以本 ADR 的服务端可以先于前端上线,不需要像 ADR-039 那样强制同一次部署。

#### 与 brief 的出入

1. brief 写「形状同 `markPaidanProfileCrossing`」。照做,只有判档一处不同:《派单》写死 30 / 60、用原始值,本 ADR 查 bands、用取整口径(勘察 3)。
2. brief 写「与 `signatureTick` 的跨档同一口径」。`useSignalCrossing` 吃原始值、不取整(勘察 2),与服务端口径在小数落进空隙时不同。
   Felix 已裁定用服务端口径,所以本 ADR **与玩家看到的面板档一致**,与 `useSignalCrossing` 的算法相同(档序号升序比较、连跨只记一次),但取整口径不同。
3. `recordIssue` 的 javadoc 与现状不符(勘察 7),brief 没提。本 ADR 在决策 6 处理。

## 候选方案

### 方案 A:前端自己存(`localStorage`)

前端每次看到升档就把回合号写进浏览器存储,刷新后读回来。

**优点**:
- 后端零改动。

**缺点**:
- 换设备、清缓存、隐私窗口一律丢失;
- 首次装载照样看不到历史升档,续局也一样(存储里只有本浏览器打过的那几回合);
- 判档又写一份在前端,和服务端口径会漂。
- **排除**:Felix 2026-11-15 裁定走后端记录,不接受「刷新就没」。

### 方案 B:下发逐回合数值,前端自己判档

给 `/history` 或 `init` 加每回合的 `ties` 值,前端据此算出哪几回合升档。

**优点**:
- 数据最原始,以后别的皮肤要别的轴也能用。

**缺点**:
- 逐回合数值今天**没有落盘**:`state.log[]` 只有 `turn` / `narrative` / `playerAction`(`Engine.apply` `:296–300`)。
  要下发就得新增落盘字段,违反约束 1;旧局照样没有历史。
- 判档放到前端,前端要认「这根轴、这种口径」,违反 ADR-018 §4.2(主题层不重新解释轴语义);口径问题见勘察 2。
- **排除**。

### 方案 C:落账时记一行引擎 issue,下发时从 issues 解析出回合号清单(本 ADR 采纳)

**优点**:
- 不新增落盘字段、不改 Engine;issues 本来就落盘、本来就在轨迹 `pre` 里,刷新、续局、重启都在;
- 判档只在服务端算一次,和提示词注入当前档同一口径;
- 与《派单》《七日开张》的标记同一形状,回放天然覆盖;
- 前端只拿到一串回合号,不认轴、不判档。

**缺点**:
- 旧局没有历史标记(已知代价 1);
- issues 又多一类使用者,javadoc 要跟着改(决策 6);
- 旧轨迹在新代码上回放会在升档回合对不上(已知代价 3)。

## 最终决策

**方案 C**。

### 1. 判定:什么算「牵挂升了一档」

落账前后各取一次 `ties` 的档序号:

```
idx(v) = ties 轴的 bands 按 threshold 升序排列后,resolveBand((int) Math.round(v)) 所在的位置(0 = 最低档)
```

- **升档** ⇔ `idx(after) > idx(before)`。只要序号变大就记,一回合连跨两档(如 20 → 70)**也只记一行**。
- 持平、下降都不记(`ties` 按 behaviorHint 只涨或持平,下降是模型越界,不归本 ADR 管)。
- 取整口径照 Felix 裁定,与 `TurnPromptBuilder.currentBandBlock` `:1152` 相同。例:29 → 30.4 取整为 30,不记;29 → 30.6 取整为 31,记。
- 档表从 `ArchetypeRegistry` 取 `life_sim` 的 `ties` 轴,不写死任何数字。档序号的算法(升序排列后取位置)新增成 `AttributeAxis` 上的一个纯函数
  `bandIndex(int value)`,放在 `resolveBand` 旁边,保证取档只有一处实现;无档表时返回 -1,此时不记。
- `before` 取不到(本局没有 `ties` 轴)→ 不记。

**生效范围**:只在**单体**局、且 `world.archetypes[0] == "life_sim"` 时判定。融合局、其他世界一律不判。
**不顺手登记 `animal_life`**:《动物人生》同属一生制家族(`LifetimeFamily`),但它没有 `ties` 轴,记忆点也不是刻痕(门缝光,ADR-021 局内皮肤)。
判定条件写的是 archetype id,不是「一生制家族」。

**降级回合不判**:`landDegraded` 走 `applyNoOp`,数值不动,不经过标记函数。

### 2. 记录:一行引擎 issue

- 落点:`EventLoopService.landSettled`,与 `profileBefore` / `coverBefore` 一起在 `apply` 之前取 `tiesBefore`,
  `apply` 之后紧接在 `markQiriCoverDrop` 之后调用新的标记函数。线上与 ADR-031 档 1 回放走同一份代码。
- 格式:`前缀 + " " + fmt(before) + "→" + fmt(after)`,前缀由一个静态方法生成:

  ```
  static String xunTiesUpIssuePrefix(int turn)  →  "T" + turn + " 牵挂升档"
  ```

  例:`T14 牵挂升档 28→33`。前缀含回合号和末尾的空格匹配(解析时用 `前缀 + " "`),T1 不会误配 T12(同《派单》)。
- 回合号 = 落账后的 `engine.turn()`,也就是**刚落地的那一回合**(勘察 3)。
- 前缀与整行**不得包含** `ADR-010 gate`、`收束下限钳制` 两个子串(ADR-033 统计按子串计数,勘察 6)。实施时用一条测试钉住。
- 放置:新建 `eventloop/XunTiesMarker`(无状态,包内可见):`ARCHETYPE = "life_sim"`、`AXIS = "ties"`、前缀方法、标记方法、解析方法。
  不放进 `TurnPromptBuilder`:《派单》《七日开张》的前缀放在那里,是因为提示词要读它们;这一条提示词不读。

### 3. 投影:一项下发时现算的展示数据

**键名**:`markedTurns`(提议,待审定)。通用词,不带「牵挂」语义:前端只知道「这些回合被标记过」,不知道是哪根轴、为什么。
避开 `signature*`:前端已有 `signatureAxisKey` / `signatureTick`,意思是「盯哪根轴、跨了几次」,而寻常没有签名轴,同名会误导。
备选:`keptTurns`(与分支名同义,但「kept」暗含了刻痕「不再变淡」这个皮肤语义)。

**形状**:整数数组,升序、去重;每个元素 n 表示「第 n 回合落账时被标记过」。

| 响应 | 位置 | 缺省 |
|---|---|---|
| `POST /api/game/init` | `InitResponse` 顶层(新分量,`@JsonInclude(NON_NULL)`) | 键不出现 |
| `GET /api/game/{saveId}/state` | 同上(共用 `InitResponse`) | 键不出现 |
| 回合 `delta` | 顶层,`markedTurns != null` 时才 `put` | 键不出现 |

`ending` 事件、`/history`、`/api/archetypes` 不带。

**落点**:`ClientProjection` 新增一张 per-archetype 登记表和一个静态函数:

```
static final Map<String, Function<List<String>, List<Integer>>> MARKED_TURNS = Map.of(
        "life_sim", XunTiesMarker::turns);

public static List<Integer> markedTurns(Engine engine)   // 可能返回 null
```

- 只查单体局(同 `TIME_LABELS` / `ACTION_TAGS`,复用 `singleArchetype`);未登记的世界返回 `null`。
- `XunTiesMarker.turns(issues)`:逐行看,行首是 `T<数字> `,且 `issue.startsWith(xunTiesUpIssuePrefix(n) + " ")`,就收下 n;结果升序去重。
  只认这一种前缀,其余 issue 一律跳过。
- 解析只读 `engine.issues()`,不读数值、不读 registry,不重新判档:**判档只发生在落账那一刻**。

**已结束的局:照常下发**(提议)。理由:`timeLabel` 说的是「正要打的那一回合」,局结束就没有了,所以 ADR-039 不下发;
`markedTurns` 说的是**已经发生过的事**,结局画面上那行刻痕依然成立,结局回合本身升档也该显示出来。
最后一回合的 `delta`(先于 `ending` 事件发出)和之后的 `state` 都带。

**清单为空:不写这个键**(提议)。理由:

1. ADR-039 的 `ClientProjectionParityTest` 基线里有 `life_sim`(十个世界之一,各轴 60 → 55,没有升档)。空清单不写键,这份基线**不用重录**,
   直接证明「没升过档的寻常局三处响应逐字节不变」;写 `[]` 则要改基线,违反 ADR-039 的「之后的 commit 不得改基线」。
2. 与 `timeLabel`、选项 `tag` 同一条规矩:**没有就不写键**。前端把「没有这个键」当成空清单即可,本来就得这么处理未登记的世界。
3. 代价:前端从 wire 上分不出「登记了、但还没升过档」和「没登记」。前端不需要分(画不画刻痕由皮肤登记决定,不由这个键决定)。

### 4. 回合号与刻痕的对应

前端第 k 道刻痕 = 第 k 回合。`markedTurns` 里有 k ⇔ 第 k 道刻痕长高。

| 时刻 | `state.turn` | 刻痕道数 | `markedTurns` 可能含的最大值 |
|---|---|---|---|
| 刚开局 | 0 | 0 | 无(不写键) |
| 第 14 回合落账,牵挂 28 → 33 | 14 | 14 | 14 |
| 第 15 回合降级 | 15 | 15 | 14(降级不判) |

与 `timeLabel` 的 n = `state.turn` + 1 不同:标记描述的是**已落地**的回合,所以 n ≤ `state.turn`,没有错位。
开场叙事不算一道刻痕(第 0 回合没有落账);前端要不要给开场画一道,归皮肤那一节,不影响本 ADR 的对应规则。

### 5. 不改什么

- `Engine` 一行不改(只用已有的 `recordIssue`);落盘格式不改;`schemaVersion` 不改。
- 回合提示词不改(勘察 4)。
- 前端不改(约束 5)。
- 其他世界的任何登记表不改;`TIME_LABELS` / `ACTION_TAGS` 不动。
- `useSignalCrossing` / `DebugPanel` 的取整问题不修(挂账)。

### 6. `recordIssue` 的 javadoc:实施时改写

**决策**:实施的那一刀里改写 `Engine.recordIssue` 的 javadoc,改成如实描述 issues 承载的两类内容。不拆字段,不改方法。

改写后要写清楚:

1. issues 承载**两类**内容:(a) **数值异常 / 服务端干预记录**(跳变、gate 改判、收束钳制、结局核对改判、七日兜底与钳制);
   (b) **服务端判定的落盘标记**(《派单》画像向上跨档、《七日开张》底细跌破 20、《寻常》牵挂升档)。
   (b) 是「某个跨回合的判定结果,下一回合或下发时要用」,借 issues 落盘,是为了不新增落盘字段(CONTEXT §三.18(e) 已经这样写)。
2. 每一行都必须以 `T<回合号> ` 开头,前缀由一个**静态方法**生成,读者只按前缀匹配,不解析自由文本。
3. 新增调用方的门槛**保留**,条件换成:在 ADR 里写明它属于哪一类、前缀由哪个静态方法生成、谁读它、
   以及它不含 ADR-033 统计按子串计数的那几个词。
4. 删掉「本刀唯一调用方 = 收束气力下限钳制点」这一句(已经不成立);刀 8「靠数某一类条数判断」那条读法仍然成立,
   前提是按前缀或子串数,所以保留,并注明要和第 3 条一起看。

**理由**:

- 这条 javadoc 是新调用方动手前会读的那段文字。它今天说「唯一调用方」「只能是数值异常」,两条都已经不成立。
  不改的话,下一个读到它的人要么信了这句错话,要么照着规矩卡住一个合法的标记。
- 不另开一个 `markers` 字段:那要新增落盘字段、改 `Engine.restore` 与 `toPersistedState`,违反约束 1;
  而且《派单》《七日开张》已经落盘的标记要迁移。
- 门槛不能删:javadoc 原本担心的「issues 变成大杂烩、按类计数失效」是真风险。换成「写明类别 + 前缀 + 读者 + 不撞统计子串」,
  比「必须是数值异常」更能挡住它,因为它约束的正是计数要依赖的那几件事。
- 改的是注释,不是行为,所以和实现放在同一刀,不另开 commit。

### 7. 验证方案(实施时照做)

**A. 其他世界三处响应逐字节不变**

- ADR-039 的 `ClientProjectionParityTest` 及 `golden/adr039-client-projection/` 基线**不改**、全绿,交付说明里给 `git diff --stat` 为空。
  实施时先核实基线里的 `life_sim` 夹具确实没有升档(各轴 60 → 55);如果有,停下报告。
- 守卫测试(ADR-039 验证 C 那条)加一项:registry 里任何轴的 key 都不叫 `markedTurns`。

**B. 回合提示词逐字节不变**

- 新增一条测试:同一局寻常,engine 里有和没有 `T<n> 牵挂升档 …` 这一行时,`renderTurnPrompt` 的输出全等。这是勘察 4 的直接证明。
- 既有的 `PaidanPromptSnapshotTest`、`QiriTurnTest`、`SingleTurnClockSeamTest`、golden parity 文件不改、全绿。

**C. 判定**

- 升一档记一行;20 → 70 连跨两档只记一行;持平、下降不记;29 → 30.4 不记、29 → 30.6 记(取整口径);
  降级回合不记;融合局不记;`animal_life` 与其他七个世界不记。
- 前缀:T1 不误配 T12;整行不含 `ADR-010 gate` / `收束下限钳制`。
- 变异核对(实施时手工做一次并记读数):把判档改回不取整,30.6 那条必须变红;把 `bandIndex` 换成写死 31 / 61 的比较,
  另造一份档表不同的 `ties` 轴,那条必须变红。

**D. 投影**

- 清单升序去重;只认本前缀;已结束的局照常下发;空清单不写键;未登记世界、融合局不写键。
- `init` / `state` / `delta` 三处对寻常都带;续局:`SessionDocument.encode` → `decode` 之后照样解析得出(issues 回载)。

**E. ADR-031 回放**

- `TraceReplayTest` 加一局寻常:某回合牵挂升档,档 1 回放后 `post` 摘要相等,并断言 issues 含那一行。
- 变异:把标记挪到 `landSettled` 之外(只在线上路径做),回放必须变红。

**F. 全量**

- `mvn test` 全绿(跑前清 `surefire-reports`,只算当次报告)。本 ADR 不动前端,`npm test` / lint / build 照跑一次作零回归证明。

## 关键理由

1. **issues 是现成的、已落盘的、已进轨迹的跨回合记录通道**。《派单》《七日开张》已经验证过这条路(CONTEXT §三.18(e)),
   这里复用,不新增字段,也不动 Engine。
2. **判档只在落账那一刻做一次,口径与提示词注入、与玩家看到的面板相同**(勘察 2 订正后的事实)。前端只拿回合号,不认轴、不判档(ADR-018 §4.2)。
3. **标记写在 `landSettled` 里**,线上与回放同一份代码,回放天然覆盖(ADR-031 刀 2 冲突的裁定 (b))。
4. **没有就不写键**,让 ADR-039 的逐字节基线原样成立,不需要重录就能证明其他世界不受影响。

## 已知代价

1. **旧局没有历史标记**。上线前已开的寻常局,升过的档没有记录,清单从上线之后打的回合开始累积。
   续这样的局,前端会把上线前的升档回合画成普通刻痕。缓解方式:无。issues 里没有逐回合数值,事后补算不出来。影响面只有上线时正在进行的寻常局。
2. **issues 多了一类读者,也多了一类写法**。靠决策 6 改写 javadoc、决策 2 的「不撞统计子串」测试来守,不是靠类型系统。
3. **旧轨迹在新代码上回放,升档回合的 `post` 摘要对不上**(勘察 5)。按 ADR-031 的定义,回放应该在轨迹对应的 commit 上做,所以不违约;
   但「拿旧轨迹在新代码上复盘」这种用法会在这些回合失败。《派单》《七日开张》已有同样的代价。
   缓解方式:复盘旧寻常轨迹时检出 `r.commit`。
4. **与 `useSignalCrossing` 的取整口径不同**(勘察 2)。将来寻常若登记签名轴,两边在小数落进空隙时判法不同。缓解方式:见挂账项 1。
5. **前端从 wire 上分不出「登记了、还没升过档」和「没登记」**(决策 3 空清单不写键)。前端不需要分。
6. **`delta` 顶层又多一个结构键**,也就多一个不能当轴名的词。缓解方式:验证 A 的守卫测试。
7. **`InitResponse` 再多一个 `NON_NULL` 分量**,ADR-039 已知代价 8 同样适用:以后再加可空分量的人得记得标注,否则基线会立刻变红。
8. **清单随局变长**,每次 `init` / `state` / `delta` 都整份下发。寻常一局 40–55 回合,三档最多升两次,清单最多两个元素,代价可以忽略。

## 重新审视的触发条件

- 第二个世界要登记 `markedTurns`(别的轴、别的判定):核对一张「archetype → 解析函数」的表够不够用,
  以及「升档」以外的判定(比如降档、越过某个值)要不要同一个键。一个世界只能有一种标记;若一个世界需要两种,这个键的形状要重议。
- 前端需要知道标记的**种类**(而不只是回合号)时:那就不是一串回合号了,要另立 ADR。
- 前端要逐回合数值(方案 B 的需求真的出现了):那是新增落盘字段的决定,另立 ADR,本 ADR 的标记届时可以从数值重算后废弃。
- 统计工具或冒烟要按类计数 issues,发现前缀约定守不住:考虑拆字段(决策 6 排除的那条路)。

## 落地切分(ADR 审过之后;本 ADR 只写不执行)

| 刀 | 内容 | 单独可编译可测 |
|---|---|---|
| 1 | `AttributeAxis.bandIndex`;`XunTiesMarker`(前缀、标记、解析);`landSettled` 接入;`recordIssue` javadoc 改写(决策 6);判定与回放测试(验证 B、C、E) | 是。此刀之后只记不发,三处响应不变 |
| 2 | `ClientProjection.MARKED_TURNS` + `markedTurns`;`InitResponse.markedTurns`(`NON_NULL`);三处调用点;投影测试(验证 A、D) | 是 |
| 3 | 文档回写:本 ADR 实施记录、CONTEXT §三.9 视图 3 加补充块 + 版本行、ROADMAP、README 状态 | 纯 docs |

刀 2 可以先于前端上线(勘察 8:旧前端忽略数组型的顶层键)。部署由 Felix 亲手。

**实施时的文档回写项**(本次不改):

- **CONTEXT §三.9 需要加补充块**,照 ADR-039 补充块的写法:视图 3 再多一项下发时现算的展示数据 `markedTurns`;
  它不是 state schema 字段,不落盘(来源是已落盘的 issues),不进视图 2,`schemaVersion` 仍 "0.4";登记表只查单体局,目前只登记 `life_sim`;
  没有就不写键;前端原样使用,不解析 issues。
- CONTEXT §三.18(e) 在实施时**看要不要**加一句:跨回合判定的落盘标记除了「下一回合注入」,也可以供「下发时投影」读取(本 ADR 是第一例)。
  拿不准就只写进 §三.9,不动 §三.18。

## Felix 审定结果(草案时「待 Felix 审定清单」;Felix 2026-11-15 六项全部照提议定下,裁定见清单之后)

1. **键名**:`markedTurns`(本 ADR 提议)还是 `keptTurns` / 其他。理由见决策 3。
2. **已结束的局**:本 ADR 提议照常下发。另一选项:同 `timeLabel` 不下发,结局画面不画长刻痕。
3. **空清单**:本 ADR 提议不写键。另一选项:登记了的世界总写数组(空时 `[]`),代价是 ADR-039 基线里 `life_sim` 那几份要改。
4. **`recordIssue` javadoc 改写**(决策 6):确认改、以及按那四条改。
5. **前缀文字**「T{n} 牵挂升档」:这是日志文字,不是玩家可见文案,确认即可。
6. **类名 `XunTiesMarker`** 与把判档函数放进 `AttributeAxis`:确认,或指定别处。

**裁定(Felix 2026-11-15)**。上面六条是草案原文,保留不改:

1. 键名:`markedTurns`。
2. 已结束的局:照常下发(照本 ADR)。
3. 空清单:不写键(照本 ADR)。
4. `recordIssue` javadoc:按决策 6 的四条改写。
5. 前缀:「T{n} 牵挂升档」。
6. 落点:`XunTiesMarker` + `AttributeAxis.bandIndex`。

## 实施记录(2026-11-15,基于 `docs/adr-040-xun-kept-turns` `d8d7f49`,即 `main` `bb88938` + 本 ADR 两个 docs commit)

| 刀 | commit | 内容 |
|---|---|---|
| 草案 | `e90df77` | 只落字 |
| 定稿 | `d8d7f49` | 状态改已接受,回填六项审定 |
| 1 | `b9be285` | `AttributeAxis.bandIndex`;`XunTiesMarker`;`landSettled` 接入;`Engine.recordIssue` javadoc 改写;判定 / 回放测试 |
| 2 | `4b6dd95` | `ClientProjection.MARKED_TURNS` + `markedTurns`;`InitResponse.markedTurns`(`NON_NULL`);`init` / `resume` / `buildDelta` 三处接入;投影测试 |
| 3 | 本 commit | 本节、CONTEXT v3.0(§三.9 补充块)、ROADMAP v20.3、README 状态 |

**与决策的出入(如实记)**

1. 决策 2 写「放置:新建 `eventloop/XunTiesMarker`」,实现照此;另加了 `tiesAxis(Engine, ArchetypeRegistry)`,把「是不是单体《寻常》」与「从 registry 取 ties 轴」收在一处,
   `landSettled` 只多三行(取轴、取 `tiesBefore`、标记)。`mark` 接收轴作参数,所以测试能传一份档表不同的轴(变异 C-2 靠它)。
2. 决策 3 写 `MARKED_TURNS: Map<String, Function<List<String>, List<Integer>>>`,实现照此;`markedTurns` 返回 `List.copyOf`。
3. **既有回放场景顺带覆盖了标记**:`TraceScenarios` 里原有的 `lifetime_exit_action`、`lifetime_clamp` 两个寻常场景的 ties 都是 30 → 35(轻身 → 有所系),
   刀 1 之后它们也会记一行「牵挂升档」。两者的断言是 `anyMatch`,未改、仍绿;变异 E 时它们和新加的 `lifetime_ties_up` 一起变红(见读数)。
4. 验证 D「续局」测的是 `SessionDocument.encode` → `decode` 后的会话经 `ClientProjection` 现算得出,并断言文档里没有 `markedTurns` 字样;
   **没有**走 `FileSessionStore` 落盘再回载再 `resume` 的整条路(同 ADR-039 实施记录第 4 条)。`resume` 本身在接线测试里用内存会话验过。
5. `ClientProjectionTest`(ADR-039 的测试文件)改了两处:两处 `new InitResponse(…)` 加第 7 个参数 `null`(多了一个分量,不改编不过),
   结构键守卫的集合加 `markedTurns`(验证 A 的守卫项)。断言本身未改。`ClientProjectionParityTest.java` 与 `golden/adr039-client-projection/` 未改。

**读数**

- 服务端 `mvn test`:132 个套件、1085 条,0 失败 0 错误 0 跳过(跑前清了 `surefire-reports`,只算当次产出的报告)。刀 1 之后为 131 套件 1079 条全绿。
- 前端 `npm test`:41 个文件、599 条全绿;`eslint .`、`tsc -b && vite build` 通过(前端未改,作零回归证明)。
- 基线:`git diff --stat bb88938 -- server/src/test/resources/golden server/src/test/java/com/aiuniverse/server/eventloop/{ClientProjectionParityTest,PaidanPromptSnapshotTest,QiriTurnTest,SingleTurnClockSeamTest}.java` 为空。
  ADR-039 基线里的 `life_sim` 夹具各轴 60 → 55(落账前后都在「有所系」),不写键,三处响应逐字节不变。
- 验证 B:`XunTiesMarkerTest.theMarkerLineDoesNotChangeTheTurnPrompt` —— 同一局寻常,有无「T14 牵挂升档 28→33」这一行,
  `renderTurnPrompt` 与 `buildTurnPrompt` 的输出都全等。
- 变异核对(各做一次,读数如下,均已改回,改回后全量 1085 条全绿):

  | 变异 | 改法 | 结果 |
  |---|---|---|
  | C-1 去掉取整 | `XunTiesMarker.mark` 里 `Math.round(v)` 改为 `(int) v`(截断) | `XunTiesMarkerTest` 12 条中 1 条红:`roundingFollowsTheServerReading`(`:58`,29 → 30.6 不再记) |
  | C-2 写死 31 / 61 | `bandIndex` 换成 `round(v) >= 61 ? 2 : >= 31 ? 1 : 0` | 12 条中 1 条红:`bandTableComesFromTheAxisNotFromLiterals`(`:74`,另一份 0 / 40 / 70 档表下 30 → 35 被误记) |
  | E 标记挪出 `landSettled` | 删掉 `landSettled` 里的标记调用,改在 `settle` 的落地钩子里做(只在线上路径) | `TraceReplayTest` 37 条中 3 条红:`lifetime_exit_action T8→9`、`lifetime_clamp T44→45`、`lifetime_ties_up T13→14` 回放差异;`XunTiesMarkerTest` 6 条红(它们直接调 `landSettled`) |

**没做 / 没验**

- 前端未接(约束 5):`markedTurns` 目前没有消费方,浏览器里看不到任何变化。
- 没跑真 key;没有经真实后端在浏览器里走寻常局。
- 没部署。刀 2 可以先于前端上线(勘察 8),但仍等 Felix 定。
- 挂账两项未修。

## 实际效果(事后补充)

*实施并部署后回填:ADR-039 基线对拍读数(其他世界与没升过档的寻常局逐字节不变);线上寻常局里 `markedTurns` 与存档 issues 的对照;
旧局续局时清单从哪一回合开始出现;皮肤那一节接上之后,刷新与续局后长刻痕是否仍在(真机,Felix 看)。*

## 跟其他文档的交叉引用

- [ADR-039](ADR-039-client-view-time-label-and-action-tag.md) — `ClientProjection`、三处下发点、「没有就不写键」、`NON_NULL`、逐字节基线。本 ADR 是它的第三项。
- [ADR-036](ADR-036-cyberpunk-world-paidan.md) 订正三 / [ADR-038](ADR-038-time-travel-world-qiri-kaizhang.md) 决策 6 — issues 落盘标记的两个先例;CONTEXT §三.18(e)。
- [ADR-031](ADR-031-turn-execution-trace-and-offline-replay.md) — `pre` / `post` 摘要含 issues;档 1 回放与 `landSettled` 共用。
- [ADR-033](ADR-033-data-stats-definitions-and-readonly-tool.md) — 统计工具按子串计数 issues。
- [ADR-020](ADR-020-lifetime-world-family-and-ordinary-life.md) — 《寻常》;前端怎么消费归它之后的「寻常局内皮肤」一节。
- [ADR-018](ADR-018-base-world-visual-migration-and-severity-contract.md) §4.2(主题层不重新解释轴语义)、§5 Q5(签名轴跨档)。
- [ADR-025](ADR-025-narrative-history.md) — 历史端点只有叙事与选项,没有逐回合数值(方案 B 排除的依据之一)。
- CONTEXT §三.9(三视图)、§三.14(`bands`)、§三.18(e)(跨档标记由服务端判定)。
- 源文件:`eventloop/EventLoopService.java`(`landSettled`、`markPaidanProfileCrossing`、`markQiriCoverDrop`、`buildDelta`)、
  `eventloop/ClientProjection.java`、`eventloop/TurnPromptBuilder.java`(`:131`、`:272`、`:1152`)、`engine/Engine.java`(`recordIssue`、`snapshot`、`toPersistedState`)、
  `archetype/AttributeAxis.java`(`resolveBand`、`bandRanges`)、`archetype/ArchetypeRegistry.java`(`ordinaryLife`)、`worldgen/GameInitService.java`、
  `worldgen/InitResponse.java`;前端 `web/src/features/game/StatsPanel.tsx`、`theme/useAnimatedValues.ts`、`theme/useSignalCrossing.ts`、
  `theme/DebugPanel.tsx`、`bands.ts`(只读,本 ADR 不改)。

## 挂账项

1. **`useSignalCrossing` 与 `DebugPanel` 吃原始值,小数落进档间空隙时得到 `null`**(勘察 2)。
   位置:`web/src/features/game/theme/useSignalCrossing.ts` `:61` / `:64`(经 `GameScreen.tsx` `:124` 传入 `attributeValues`)、
   `web/src/features/game/theme/DebugPanel.tsx` `:51–55`。影响登记了签名轴、且模型给出小数的世界:签名轴的值从 29 到 30.6 时档序号是 `null`,
   这一次跨档不计;再到 31 时前值是 `null`,也不计,于是这次跨档被吞掉。本 ADR 不修、不扩大范围,留给前端单独一刀
   (修法要先定:取整口径与面板对齐,还是让 `resolveBand` 补空隙)。
2. **《派单》`paidanProfileCrossedUp` 写死 30 / 60、用原始值**(勘察 3),与它自己的 bands 是否同口径未核。本 ADR 不动,记在这里供以后核对。
