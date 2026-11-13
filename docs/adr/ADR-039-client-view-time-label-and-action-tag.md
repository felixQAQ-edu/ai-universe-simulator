# ADR-039 · 展示层 DTO 增量:时段标签与选项 tag 由服务端下发

- **日期**:2026-11-13
- **状态**:已采纳(定稿 2026-11-13:待审七项均由 Felix 2026-11-13 定下,见文末「Felix 裁定清单」;标签文案已给齐。实现按「落地切分」进行)
  ⚠️ 草案时的原状态行,原文保留:提议(草案;只落字、不动代码。方向由 Felix 2026-11-12 裁定;字段名、三处口径与玩家可见文案待 Felix 审定,见文末「待 Felix 审定清单」)
- **决策者**:Felix

## 背景

《七日开张》局内皮肤要显示「第几天 · 早午晚」,并把选项里的【怪点子】【打圆场】做成单独的小签。
这两样服务端本来就算得出来(`TurnPromptBuilder.qiriTurnClock`、`QiriActionGuard.prefixOf`),只是没有下发。

Felix 2026-11-12 裁定:**服务端下发,前端什么都不解析**。换算规则不写两份;主题层不认「怪点子」这几个字
(ADR-018 §4.2「任何主题组件不得重新解释轴语义」的同一条线,平移到选项文字上)。

约束条件:

1. **只动视图 3**(CONTEXT §三.9:下发前端的消毒投影)。`session.currentActions`、落盘文档、喂模型的选项文字一个字都不动 ——
   `QiriActionGuard.prefixOf` 读的是存档里的文字,ADR-031 回放的提示词哈希也依赖它。
2. **不是 state schema 字段**,`schemaVersion` 仍 `"0.4"`(同 CONTEXT §三.14 `bands` 走 API DTO 的先例)。
3. **不改提示词、不改 Engine、不改任何世界的落数规则、不做皮肤。**
4. 时段标签与提示词注入行**共用同一份「回合 → 第几天 / 时段」换算**;文字可以不同,换算必须同源。
5. 标签文字是玩家可见文案,归 Felix;ADR 与代码里不留占位符。

### 勘察结论(只读 `main` `2d79c9e`,2026-11-13)

#### 勘察 0 · ADR 编号

`docs/adr/` 最新一份是 `ADR-038-time-travel-world-qiri-kaizhang.md`;全仓无 `ADR-039` 字样。本 ADR 取 039。

#### 勘察 1 · 下发点:三处响应,两个构建函数

| 响应 | 构建点 | 选项来源 | 现状 |
|---|---|---|---|
| `POST /api/game/init` | `GameInitService.init` `:91` `new InitResponse(…, actions, …)` | `resolveInitialActions(world)` 的结果,同一个对象也交给 `sessions.create` | `InitResponse` 五个分量,无时段信息 |
| `GET /api/game/{saveId}/state` | `GameInitService.resume` `:131`,复用 `InitResponse` | `session.currentActions().deepCopy()` | 同上 |
| 回合 `delta` 事件 | `EventLoopService.buildDelta` `:576–598`(唯一构建点);调用点两处:正常落地 `:362`、保守 no-op 降级 `:399` | `session.currentActions().deepCopy()` | `turn` / `status` / 各数值轴 / `discoveredRules` / `availableActions` |

与简报一致:三处响应。实际改动点是 **`init`、`resume`、`buildDelta` 三个函数**;降级回合走的也是 `buildDelta`,不必另改。
`ending` 事件、`/history`、`/api/archetypes` 不带选项也不带时段,不动。

#### 勘察 2 · 回合口径:注入用的是 n = `state.turn` + 1;顶部「第 N 回合」的 N = `state.turn`

- 提示词侧:`TurnPromptBuilder` `:1004` `singleTurnClock(ctx, engine.turn() + 1)`、`:1006`「请推进第 `engine.turn() + 1` 回合」。
  `SINGLE_TURN_CLOCK` 的类注释 `:58` 自陈「按本回合序号 n(= `state.turn` + 1)」。即:**注入描述的是正要生成的那一回合**。
- 前端顶部:`SceneBanner.tsx:62`「第 {turn} 回合」,`turn` 取自 `world.state.turn` / `delta.turn`(`gameStore.ts:222 / :442`)。
  即 **N = 已经落地的回合数**:开场时显示「第 0 回合」,打完第一回合显示「第 1 回合」。
- 降级回合也 `turn++`(`Engine.applyNoOp`),所以降级后时钟照常往前走一格,与提示词侧一致。

#### 勘察 3 · 换算目前写在提示词函数里,没有独立出来

`qiriTurnClock(n)` `:200–219` 在函数体内直接算 `day = (n + 2) / 3`、`part = (n − 1) % 3`,并用 `QIRI_FINAL_TURN = 22` 分三段
(1–21 / 22 / ≥23)。`QiriActionGuard.morning(n)` `:55` 另有一份 `(n − 1) % 3 == 0`。要「换算同源」,得先把换算抽出来,
再让提示词行与标签各自拿它去拼文字。`QiriTurnTest` 已逐回合钉住提示词文字,抽取后它不改、不红即证明提示词逐字节不变。

#### 勘察 4 · 选项文字的读者

| 读者 | 读什么 | 本刀 |
|---|---|---|
| `QiriActionGuard.apply` / `hasOddIdea`(`EventLoopService` `:810` 传入 `session.currentActions()`) | 存档里的 `text`,字面 `startsWith` | 不动 |
| `TurnPromptBuilder`(本回合所选行动的文字,`actionTextOf` `:888`) | `session.currentActions()` | 不动 |
| 落盘 `SessionDocument` `:40–41`、ADR-031 轨迹 `pre.currentActions` | `session.currentActions()` | 不动 |
| `SelfHarmScanner`、`WorldGenService.checkSystemLine` | 模型产出原文 | 不动 |
| `Engine` 的 `log[].playerAction` | **只有选项 id**(`Engine.java:299`),不含文字 | 不涉及 |
| 前端 | 只回传 `{turn, actionId}`,不回传文字 | 不涉及 |

结论:**文字只在三处下发时离开服务端,且前端从不把它传回来**。在下发那一步改写,对上表其余读者没有任何影响。

#### 勘察 5 · 三件简报没提、但会影响设计的事实

1. **`InitResponse` 加一个可空分量,默认会序列化成 `"x":null`**。全仓没有 `default-property-inclusion` 配置,也没有 `@JsonInclude`
   (`grep` 为空)。不处理的话,其他七个世界的 `init` / `state` 响应会多出一个 `null` 键,**不再逐字节相同**。
2. **选项对象里的未知字段今天是原样下发的**。`GameSchemas` `:118–126` 只校验 `id` / `text` / `hint`,不剥多余字段;
   模型若自己写了 `"tag"`,现在会一路进 `currentActions` 并下发。新增 `tag` 这个有含义的键以后,这条路得堵上。
3. **五套皮肤各有自己的决策圈组件**(`RulesActions` / `XianActions` / `WasteActions` / `CthuActions` / `PaidanActions`),
   通用 `DecisionCircle` 只在没登记皮肤的世界里用。`time_travel` 现在 `skin: null`(`theme/registry.ts:115–123`),走的正是通用组件 ——
   所以「通用 `DecisionCircle` 显示 tag」本刀够用;但它**不是**「所有世界都会显示 tag」。
4. `delta` 的 wire 里,数值轴是顶层键。前端 `h5GameApi.ts:170` 用 `DELTA_STRUCTURAL` 白名单 + `typeof v === 'number'` 把其余顶层数字收成数值轴。
   新增一个顶层**字符串**键不会被旧前端当成数值轴(类型不符,直接忽略)。
5. 代码里「phase」已有两个含义:`TurnPhase`(回合状态机相位)与落盘的 `phaseHint`。简报里的代称 `phaseLabel` 会与它们撞词。

**与简报的冲突**:没有需要停下的硬冲突。上面第 1、2、3、5 条是简报没覆盖的地方,本 ADR 各给了处理;
另有三处口径需要 Felix 明确裁定(标签跟着选项走还是跟着正文走、已结束的局给不给标签、字段名),列在文末清单。

## 候选方案

### 方案 A:前端自己算、自己解析

前端按 `turn` 算第几天,按 `text.startsWith('【怪点子】')` 拆前缀。

- 优点:后端零改动。
- 缺点:换算规则写两份(Java 一份、TS 一份),22 回合 / 第 23 回合起这类边界两边各守一遍;
  主题层要认识「怪点子」这三个字,违反 ADR-018 §4.2。
- **排除**:Felix 2026-11-12 已裁定不走这条。

### 方案 B:改模型输出,让它直接产 `tag` 字段、`text` 不带前缀

- 优点:数据从源头就是结构化的。
- 缺点:要改提示词与 `TURN_SCHEMA`;`QiriActionGuard` 的兜底、「缺怪点子选项」计数、ADR-031 的夹具与提示词哈希全部跟着变;
  《七日开张》刚经两轮真 key 冒烟验通,这等于重验一遍。
- **排除**:违反约束 1 与 3。

### 方案 C:落盘与喂模型不动,只在下发投影时派生两个字段(本 ADR 采纳)

三处下发都经过同一个投影函数:时段标签由 `state.turn` 现算,选项 tag 由存档里的文字现拆。

- 优点:视图 1 / 2 一个字不动,兜底、计数、回放天然不受影响;旧存档与旧局续局自动拿到两个字段;换算只有一份。
- 缺点:见「已知代价」。

## 最终决策

### 1. 字段名与形状

| 字段 | 位置 | 类型 | 缺省 |
|---|---|---|---|
| `timeLabel` | `InitResponse`(`init` 与 `state` 共用)顶层;`delta` 顶层 | 字符串 | **键不出现**(不写 `null`) |
| `tag` | 下发的每个选项对象内,排在 `hint` 之后 | 字符串 | **键不出现** |

- 字段名取 `timeLabel` 而不是简报代称 `phaseLabel`:避开 `TurnPhase` / `phaseHint` 已占用的「phase」(勘察 5-5)。
- 两个字段都是**展示文字,不是枚举**。前端原样显示,不比较内容、不按内容分支。
- 「键不出现」而不是 `null`:为的是其他七个世界的三处响应逐字节不变(勘察 5-1)。`InitResponse` 的新分量标 `@JsonInclude(NON_NULL)`,
  只标这一个分量,不改全局序列化配置;`delta` 与选项对象是手工拼的 `ObjectNode`,不 `put` 即可。
- 都不是 state schema 字段:不进 `Engine`、不落盘、不进视图 2,`schemaVersion` 仍 `"0.4"`。

### 2. 投影只有一个落点:`ClientProjection`

新增 `eventloop/ClientProjection`(无状态,两个静态函数 + 两张 per-archetype 登记表):

```
static String    timeLabel(Engine engine)                     // 可能返回 null
static ArrayNode actions(Engine engine, ArrayNode stored)     // 总是返回新对象,绝不改 stored
```

- **剥前缀只发生在 `ClientProjection.actions` 这一个函数里。** 它对 `stored` 先 `deepCopy()` 再改副本;`stored == null` 返回空数组
  (与现在三处的写法一致)。
- 三个调用点各换一行:
  - `GameInitService.init`:`sessions.create(…, actions, …)` 仍传**原始** `actions`;只有 `new InitResponse(…)` 那一处改传投影结果。
  - `GameInitService.resume`:`session.currentActions().deepCopy()` 换成投影。
  - `EventLoopService.buildDelta`:同上;并在 `timeLabel` 非空时 `delta.put("timeLabel", …)`。
- 两张表都**只查单体局**(`world.archetypes` 长度为 1),与 `SINGLE_TURN_CLOCK` 同一规矩;融合局一律不带两个字段。
  `time_travel` 目前没有登记任何融合组合。

### 3. 时段标签

**登记表**:`TIME_LABELS: Map<String, IntFunction<String>>`,archetype → 「回合序号 n → 标签」。本刀只登记 `time_travel`。
《派单》以后要显示「第几夜」时往这张表加一条,用同一个字段;本刀不登记。

**描述的是哪一回合**:n = `state.turn` + 1,与 `SINGLE_TURN_CLOCK` 的注入口径完全相同(勘察 2)。
即标签说的是**玩家正要打的那一回合** —— 屏幕上那组选项所属的回合。

**与顶部「第 N 回合」的关系**:顶部 N = `state.turn`(已落地的回合数),标签的 n = N + 1。对照:

| 时刻 | 顶部显示 | `timeLabel` 描述 | 屏幕上的正文 |
|---|---|---|---|
| 刚开局 | 第 0 回合 | n = 1(第 1 天 · 早) | 开场叙事 |
| 打完第 1 回合 | 第 1 回合 | n = 2(第 1 天 · 午) | 第 1 回合的叙事(写的是早上) |
| 打完第 21 回合 | 第 21 回合 | n = 22(百味夜集) | 第 21 回合的叙事(第 7 天晚) |
| 第 22 回合没能给出结局(如降级) | 第 22 回合 | n = 23(第 23 回合起的兜底文案) | — |

由此有一个要明说的后果:**`delta` 到达之后,标签比屏幕上的正文早一格** —— 正文还是上一回合写的「早上」,标签已经是「午」。
这是「标签跟着选项走」的直接结果,也是与注入口径一致的唯一读法;另一种读法(跟着正文走)列在文末清单第 2 条,由 Felix 裁。
流式生成期间(玩家已点选、`delta` 未到)标签还是旧值,此时它与正在写出的正文是同一回合,没有错位。

**已结束的局**:`status == "ended"` 时**不下发** `timeLabel`(三处都一样)。局已结束,没有「正要打的那一回合」;
按 n = `state.turn` + 1 硬算会在第 22 回合正常结局时给出「第 23 回合起」的兜底文案,那是错的。

**换算同源**:抽出 `QiriCalendar`(`eventloop` 包内,纯函数):

```
record QiriCalendar(int turn, int day, int part, Stage stage)   // Stage ∈ { DAY, NIGHT_MARKET, AFTER }
static QiriCalendar of(int n)   // n < 1 按 1;1–21 → DAY(day 1–7, part 0–2);22 → NIGHT_MARKET;≥ 23 → AFTER
```

- `TurnPromptBuilder.qiriTurnClock` 改为从 `QiriCalendar.of(n)` 取 `day` / `part` / `stage` 再拼**原来那些字**;
  `QiriActionGuard.morning` 改为读同一份(`stage == DAY && part == 0`)。提示词文字与兜底判定逐字节 / 逐格不变,由既有测试证明(决策 6)。
- 标签函数只允许从 `QiriCalendar` 取数,自己不做任何回合算术。
- 「离百味夜集还有几天」(`8 − d`)只有提示词用,留在 `qiriTurnClock` 里,不进 `QiriCalendar`。

**需要 Felix 给齐的文案**(给齐之前不实现;本表只列需要什么,不代拟):

| 回合 n | 可用的量 | 需要的文案 |
|---|---|---|
| 1–21 | 天数 d(1–7)、时段(早 / 午 / 晚) | 一个格式:d 与时段怎么写、中间用什么隔开、数字用阿拉伯还是汉字 |
| 22 | — | 一句固定文字 |
| ≥ 23 | — | 一句固定文字(只在第 22 回合没给出结局时出现,见上表第四行) |

提示词行里现成的写法是「第 d 天 · 早」与「百味夜集 · 开摊」;标签可以沿用,也可以不同,由 Felix 定。
单测按 n = 1…21、22、23、30 逐个钉住 n → 标签全文。

**定稿文案**(Felix 2026-11-13 定,取乙套,逐字;上表是草案时列的「需要什么」,原样保留):

| 回合 n | 标签 |
|---|---|
| 1–21 | 「第{d}日 · {时段}」。d 用汉字 一、二、三、四、五、六、七;时段为 早市 / 午市 / 晚市;中间是一个间隔号「·」,左右各一个半角空格。例:第三日 · 晚市 |
| 22 | 「夜集 · 开张」 |
| ≥ 23 | 「夜集 · 收摊」 |

标签与提示词行的文字不同(提示词行仍是「第 d 天 · 早」「百味夜集 · 开摊」,一字不改),两者只共用 `QiriCalendar` 的换算。

### 4. 选项 tag

**登记表**:`ACTION_TAGS: Map<String, Map<String, String>>`,archetype →(前缀 → tag 文字),内层保持登记顺序。本刀只登记:

```
"time_travel" → { QiriActionGuard.ODD_IDEA    → "怪点子",
                  QiriActionGuard.SMOOTH_OVER → "打圆场" }
```

前缀用 `QiriActionGuard` 里现有的两个常量,不另写字面量。tag 文字「怪点子」「打圆场」是玩家可见文案,取自简报;
`QiriActionGuard.apply` 里拼 issue 用的同名字面量是日志文字,与它无关,不合并。

**规则**(对每个选项,按登记顺序取第一个命中的前缀):

1. 命中 = `text.startsWith(前缀)`。与 `QiriActionGuard.prefixOf` 同一判法(字面精确、必须在开头);
   前缀写在句中、半角括号、前面多空格等一律不算。单测用 `QiriActionGuardTest` 的同一组样例钉住「命中 ⇔ `prefixOf` 非空」。
2. 命中时:`text` 去掉前缀,再去掉紧随其后的空白(含全角空格);`tag` 置为表里的文字,排在 `hint` 之后。
3. **去掉前缀后是空串的,不算命中**:`text` 原样、不带 `tag`(不下发一个没有字的按钮)。
4. 没命中、世界未登记、融合局:`text` 原样,不带 `tag`。
5. **`tag` 只来自服务端登记表**:选项对象里若已有 `tag` 键(模型自己写的,勘察 5-2),投影时一律先删掉,所有世界都如此。

### 5. 前端(同一刀,范围压到最小)

- **类型**:`api/contract.ts` 新增 `ClientAction = AvailableAction & { tag?: string }`(仿 `ClientRule` / `ClientWorld`:视图 3 的类型放 `api/`,
  `types/schema.ts` 是 CONTEXT §二 的镜像,不加非 schema 字段);`InitResult.availableActions`、`TurnDelta.availableActions` 改用它,
  并各加 `timeLabel?: string`。`gameStore`、`DecisionCircle`、`theme/contract.ts` 的 `ActionsProps` 跟着换类型名(只改类型,不改运行时)。
- **解析**:`h5GameApi.parseDelta` 把 `timeLabel` 加进 `DELTA_STRUCTURAL` 并读出(字符串才收,否则 `undefined`);`init` / `state` 是整体展开,自动带上。
  `gameStore` 存一份 `timeLabel`,随 `init` / 续局 / `delta` 更新。
- **`timeLabel` 本刀只接类型与状态,不显示。** 显示归《七日开张》皮肤那一刀。
- **通用 `DecisionCircle` 显示 tag**:选项带 `tag` 时,在 `actionMain` 里、`actionId` 与 `actionText` 之间插一个
  `<span className={styles.actionTag}>{a.tag}</span>`;不带时这个节点不渲染。

  ```
  带 tag:  <span actionMain><span actionId>A.</span><span actionTag>怪点子</span><span actionText>削取号竹牌</span></span>
  不带:    <span actionMain><span actionId>A.</span><span actionText>原路退回</span></span>      ← 与现在逐字节相同
  ```

  `game.module.css` 新增一条 `.actionTag`:所有 tag 同一种样式,不按内容区分;只用现有 token,不加动效(ADR-017)。
  具体长相(描边 / 底色 / 字号)实现时给一版,Felix 在浏览器里看过再定。
- **五套皮肤的决策圈组件本刀不动**,它们不显示 tag。立一条规矩:**给某个世界登记 tag 表时,必须同时确认该世界实际使用的决策圈组件会显示 tag**。
  本刀成立(`time_travel` 走通用组件);《七日开张》皮肤那一刀若自带决策圈,显示 tag 是它的验收项。

### 6. 验证方案(实施时照做)

**A. 其他七个世界的三处响应逐字节不变**

- 新增 `ClientProjectionParityTest`,**基线在动生产代码之前录**(先例:`PaidanPromptSnapshotTest` 在 `cf29c31` 上录):
  实施的第一个 commit 只加测试与基线文件,在 `2d79c9e` 的生产代码上跑绿;之后的 commit 不得改基线。
- 覆盖:七个非 `time_travel` 的单体世界 + 已登记的三个融合组合;每个取 `init` 响应、`state` 响应(经真实的 Spring `JsonMapper` 序列化成字符串,
  这样才抓得到 `null` 键)、一次正常回合的 `delta`、一次降级回合的 `delta`。world-gen 与回合用桩模型,输入固定。
- 其中一个非 `time_travel` 世界的夹具里故意放一条以「【怪点子】」开头的选项:证明未登记世界不剥前缀。
- 断言:序列化后的字符串与基线**全等**。
- 界定:基线输入里没有模型自带的 `tag` 键。带了的情况由决策 4 第 5 条处理(那是有意的行为变化,另有一条单测)。

**B. `QiriActionGuard` 的兜底与「缺怪点子选项」计数前后不变**

- 这些既有测试**文件不改、结果全绿**(交付说明里用 `git diff --stat 2d79c9e -- <这些文件>` 为空作证):
  `QiriActionGuardTest`、`QiriCoverDropTest`、`QiriEndingCheckTest`、`QiriTurnTest`、`TraceReplayTest`(含 `T22 缺怪点子选项` 那条)、
  `TraceFixtureReplayTest`、`TraceRecheckTest`、`SingleTurnClockSeamTest`、`PaidanPromptSnapshotTest`、golden parity。
- 新增一条接线测试(《七日开张》,桩模型回一组带两种前缀的选项):回合落地后
  `session.currentActions()` 与落盘文档的 `currentActions` 里**前缀原样还在**;同一回合 `sink.delta` 的选项里前缀已去掉、带 `tag`;
  再打一回合选那个【怪点子】选项,兜底 issue 照常记下。
- 变异核对(实施时手工做一次并记读数):把投影里的 `deepCopy()` 去掉,上面这条必须变红。

**C. 新行为**

- `QiriCalendar`:n = 0、1…23、30 逐个钉 `day` / `part` / `stage`。
- 标签:n → 全文(文案到位后)。`ended` 不下发;融合局不下发;未登记世界不下发。
- 选项:命中 / 不命中 / 前缀后多空格 / 只有前缀 / 模型自带 `tag` / 前缀等价于 `prefixOf`。
- `init`、`state`、`delta` 三处对《七日开张》都带两个字段;旧存档(磁盘上早于本刀的《七日开张》存档文档)回载后 `state` 也带。
- 守卫:registry 里任何轴的 key 都不与 `delta` 的结构键(`turn` / `status` / `discoveredRules` / `availableActions` / `timeLabel`)重名。

**D. 前端**

- `DecisionCircle.test.tsx`:不带 `tag` 的一组选项,`container.innerHTML` 与**改动前录下的字符串**全等;带 `tag` 的显示 tag 且文字不含前缀。
- `h5GameApi.test.ts`:`delta` / `init` / `state` 带与不带两个字段各一条。
- ADR-018 §4.5.1 双 dev 对拍:除《七日开张》外各世界游戏屏与选择屏全等;《七日开张》带 tag 的一屏两边不等(正对照,§4.5.2)。
- `npm test` / lint / build 全绿。

## 关键理由

1. **下发那一步是文字离开服务端的唯一出口,且前端从不把文字传回来**(勘察 4)。在这里改写,兜底、计数、落盘、回放都读不到改写后的东西。
2. **两个字段都是由已落盘的东西现算的**(`state.turn`、`currentActions[].text`、`world.archetypes`),所以不需要迁移,也不存在新旧存档之分。
3. **换算抽成一份、提示词与标签各拼各的字**,比「标签去解析提示词那行字」稳:提示词措辞以后改了,标签不会跟着坏。
4. **展示文字而不是枚举**:前端不需要为每个新 tag 发版,也没有机会按内容分支(ADR-018 §4.2)。

## 已知代价

1. **还开着旧前端的玩家会看不到前缀,直到刷新。** 服务端一旦剥前缀,旧前端拿到的 `text` 没有【怪点子】,又不认识 `tag`。
   选项照常能点、落数与兜底照常(都在服务端),丢的只是这个提示。缓解:无;部署后刷新即恢复。影响面只有《七日开张》进行中的局。
2. **标签比屏幕上的正文早一格**(决策 3)。皮肤那一刀摆放标签时要知道这一点:它说的是「接下来这一步」,不是「刚才那段」。
   顶部的「第 N 回合」与标签差 1 也是同一件事;要不要在《七日开张》里把顶部回合数换掉或隐藏,归皮肤那一刀。
3. **玩家可见文案进了 Java**(tag 文字、标签格式)。先例是 `displayName` / bands 的 `label` / `tagline`,都在服务端 registry 里。
4. **带皮肤的世界不显示 tag**(勘察 5-3)。现在没有这样的世界登记了 tag;靠决策 5 末尾那条规矩与皮肤那一刀的验收守,不是代码检查。
5. **`delta` 的顶层键空间由数值轴与结构键共用**,多一个结构键就多一个不能当轴名的词。缓解:验证 C 的守卫测试。
6. **模型自带的 `tag` 键从此被丢弃**(所有世界)。今天它会原样下发但没有任何消费方,所以没有可见变化;记在这里是因为它是唯一一处
   对「其他世界逐字节不变」的界定(验证 A)。
7. **存档、轨迹、ADR-033 统计工具里看到的选项文字仍带前缀**,与玩家看到的不同。这是有意的(约束 1);排查时以存档为准。
8. **`@JsonInclude(NON_NULL)` 只标在一个分量上**,以后再给 `InitResponse` 加可空分量的人得记得照做,否则又会多出 `null` 键。
   缓解:验证 A 的基线会立刻变红。

## 重新审视的触发条件

- 第二个世界要登记 `timeLabel` 或 tag(预期是《派单》「第几夜」):核对两张表的形状够不够用,尤其是标签是否需要 `state.turn` 以外的输入。
- 需要按 tag 的**种类**做不同样式或不同交互时:那就不再是「展示文字」,要另立 ADR 引入枚举,而不是让前端比较文字。
- 融合局需要时段标签时(`FusionTurnCopy.turnClock` 已有按回合时段,目前只有《验讫》):本刀的「只查单体」要放开。
- 标签与正文错位在真机上被 Felix 判为别扭:改读法(清单第 2 条的另一选项),只动 `ClientProjection.timeLabel` 里取 n 的那一行与对应单测。

## 落地切分(ADR 审过、文案给齐之后;本 ADR 只写不执行)

| 刀 | 内容 | 单独可编译可测 |
|---|---|---|
| 1 | 测试先行:`ClientProjectionParityTest` 及基线、`DecisionCircle` 不带 tag 的 `innerHTML` 基线。生产代码零改动 | 是(在 `2d79c9e` 的行为上全绿) |
| 2 | 抽 `QiriCalendar`;`qiriTurnClock` 与 `morning` 改读它。行为零变化 | 是(既有测试不改全绿) |
| 3 | `ClientProjection` + 两张表 + 三个调用点 + `InitResponse.timeLabel` + 服务端新测试 | 是 |
| 4 | 前端:类型、`parseDelta`、`gameStore`、`DecisionCircle` 与 `.actionTag`、前端测试 | 是 |
| 5 | 文档回写:本 ADR 实施记录、CONTEXT(§三.9 视图 3 加一条补充 + 版本行)、ROADMAP、README 状态 | 纯 docs |

刀 3 与刀 4 必须同一次部署上线(只上刀 3,新前端没到,所有人都落进已知代价 1)。部署由 Felix 亲手。

## Felix 裁定清单(草案时「待 Felix 审定清单」;Felix 2026-11-13 逐条定下,裁定见清单之后)

1. **字段名**:`timeLabel`(本 ADR 取)还是简报代称 `phaseLabel`。理由见决策 1。
2. **标签描述哪一回合**:本 ADR 取 n = `state.turn` + 1(跟着选项走,与注入口径一致),后果是 `delta` 到达后标签比正文早一格。
   另一选项:跟着正文走(n = `max(state.turn, 1)`),开场与第 1 回合后都显示第 1 天 · 早,没有错位,但与注入口径差一格。
3. **已结束的局**:本 ADR 取不下发。另一选项:下发最后一回合的标签(n = `state.turn`),结局画面可以用。
4. **文案**(决策 3 的表):第 1–21 回合的格式、第 22 回合、第 23 回合起,共三项。
5. **tag 文字**:「怪点子」「打圆场」(照简报),确认即可。
6. **tag 在通用决策圈里的位置**:本 ADR 取编号与文字之间、同一行。长相实现时出一版再看。
7. **模型自带的 `tag` 键一律丢弃**(决策 4 第 5 条,已知代价 6):确认。

**裁定(Felix 2026-11-13;校勘已对过 `07a5d79` 与 `2d79c9e` 源码,勘察属实)**。上面七条是草案原文,保留不改:

1. 字段名:`timeLabel`。
2. 标签描述哪一回合:n = `state.turn` + 1(照本 ADR)。错位由《七日开张》皮肤那一刀在摆放上处理。
3. 已结束的局:不下发(照本 ADR)。
4. 文案:取乙套,见决策 3「定稿文案」。
5. tag 文字:「怪点子」「打圆场」,确认。
6. tag 位置:编号与文字之间、同一行(照本 ADR);长相实现后 Felix 在浏览器里看过再定。
7. 模型自带的 `tag` 键一律丢弃:确认。

## 实际效果(事后补充)

*实施并部署后回填:其他世界三处响应的基线对拍读数;《七日开张》真机上 tag 的显示与旧前端降级的实际表现;标签与正文错位的体感(待皮肤那一刀显示后才看得到)。*

## 跟其他文档的交叉引用

- [ADR-038](ADR-038-time-travel-world-qiri-kaizhang.md) —《七日开张》:七日时钟(决策 2)、选项前缀与服务端兜底(订正一)。本 ADR 不改其中任何一条。
- [ADR-036](ADR-036-cyberpunk-world-paidan.md) — `SINGLE_TURN_CLOCK` 的出处;《派单》「第几夜」是 `timeLabel` 的下一个预期使用者。
- [ADR-018](ADR-018-base-world-visual-migration-and-severity-contract.md) §4.2(主题层不重新解释语义)、§4.5.1 / §4.5.2(DOM 对拍与正对照)。
- [ADR-031](ADR-031-turn-execution-trace-and-offline-replay.md) — 回放与提示词哈希依赖存档里的选项文字,故只动视图 3。
- [ADR-011](ADR-011-action-hint-narrative-metadata.md) — `hint` 是叙事元数据;`tag` 同属展示信息,引擎永不读。
- CONTEXT §三.9(三视图)、§三.14(`bands` 走 API DTO 不动 `schemaVersion` 的先例)、§三.18(a)(单体按回合注入)。
- 源文件:`eventloop/TurnPromptBuilder.java`(`qiriTurnClock`、`SINGLE_TURN_CLOCK`)、`eventloop/QiriActionGuard.java`、
  `eventloop/EventLoopService.java`(`buildDelta`)、`worldgen/GameInitService.java`(`init` / `resume`)、`worldgen/InitResponse.java`、
  `web/src/api/contract.ts`、`web/src/api/h5GameApi.ts`、`web/src/features/game/DecisionCircle.tsx`。
