# ADR-046 · 物品状态随回合更新:回合尾巴带整份 inventory,引擎落账

- **日期**:2026-11-24
- **状态**:提议(只写 ADR,不写代码。范围由 Felix 2026-11-24 定;D1–D7 为**校勘代定**,Felix 审 ADR 时逐条认或改;
  本 ADR 勘察与简报的出入集中在文末「与简报的出入」,**未自行调和**)
- **决策者**:Felix

## 背景

B 线第 2 步(记忆账本)的第一刀。上游两份事实底稿:

- [ADR-045](ADR-045-long-game-selfplay-measurement.md) 第一套 `20261123-a`(7 局 571 回合)与 [长局记忆勘察底稿](../long-memory-survey.md):
  46 次矛盾,其中 21 次是「离窗前用掉 / 送出的物品又出现」。
- [记忆账本勘察底稿](../memory-ledger-survey.md)(下称「底稿」)把这 21 次拆开,本 ADR 只引它的事实:
  1. **21 次物品矛盾全是开局 inventory 里的物品**,「中途获得、从没进过 inventory」一类为 0(底稿 Q-2);
  2. **21 个回合全部是所选选项点名了该物品的回合**(底稿 Q-2、附录 A.9 `invproxy.py`);
  3. **基线 7 局的 inventory 从 T0 到终局一字未变**(底稿 §三 前提、附录 A.7 `invT0.py`,7/7「相同」)——
     今天没有任何通道改写它:`Engine.apply` 不读不写 inventory,`stateUpdate` 里的非数值键被校验器放过、引擎也不读(底稿 §1.0 第 2 条);
  4. 其余 25 次里:**承诺 0 次**;人物 6 次,其中 4 次是推测(底稿 Q-2 第二张表)。

即:物品矛盾有一个确定的形状 —— 世界段里那份 inventory 永远停在开局,模型用掉一件东西之后,下一回合 prompt 里它还在,
选项又点名它,模型就照着 prompt 写它还在。本刀只修这一个形状。

范围(Felix 2026-11-24 定):**只修物品状态** —— 回合输出带 inventory 更新,由引擎落账。人物 / 承诺 / 事件那部分账本不做(见「不做」)。

约束条件:

1. 不另起调用(ADR-006 单流 + 哨兵尾巴不动;理由见 D1)。
2. 不动 world-gen、前端、存档格式、`schemaVersion`(仍 "0.4")。
3. 引擎对物品语义无知:只按整表替换落账,不推断「用了一粒就少一粒」(守 ADR-008 引擎无知的口径)。
4. 测量(刀 2)花钱,Felix 本机亲手跑;成本闸 ¥5。

## 勘察(截至 `main@6e8e18f`;路径前缀 `server/…/` = `server/src/main/java/com/aiuniverse/server/`,测试 = `server/src/test/java/com/aiuniverse/server/`)

### 勘察 1 · 改了骨架后要处理的逐字节快照(逐个列出,含处理办法)

**会红的,只有三组存盘快照**。底稿 §1.6 标「未证实」的「其他按世界的逐字节 prompt 测试(《七日》《验讫》等)」**已查实:没有**。
方法:列出全部调用 `buildTurnPrompt` / `renderTurnPrompt` 的测试(28 个文件),逐个看 `isEqualTo` 的对象:
除下表三组外,其余都是**同一 build 内的相对比较**(如 `SingleTurnClockSeamTest:76` 生产 builder 对空表 builder、`XunTiesMarkerTest:155-157`
`renderTurnPrompt` 对 `buildTurnPrompt`、`BoxSceneClockOverrideTest` 比的是阶段表不是 prompt)或只做 `contains` / 禁词扫描
(`QiriTurnTest`、`CultivationCthulhuTurnTest`、各 lockstep),骨架加一行不会让它们红。

| # | 快照 | 测试 | 规模 | 内容是否已含 ADR-037 边界块 | 先例 | 本刀处理(提议) |
|---|---|---|---|---|---|---|
| 1 | `golden/turn-prompts/`(六单体 + 三融合 × 10 回合号) | `PaidanTurnTest` ①(`:47-75`) | 90 份 | **不含**(取自 `3e72099`,经 `CommonPromptAdditions.stripTurn` 剔除后比) | ADR-037 决策 5(c):**锚定剔除**,不重录 | **第二层锚定剔除**。另:`cultivation_x_cthulhu_T*.txt` 10 份还要剔《验讫》那一句(D4),锚 = combo (6) 末行 |
| 2 | `golden/adr028-legacy-turn-prompts/`(8 份) | `AnimalLifeLegacyGoldenTest`(`:64-84`) | 8 份 | **不含**(取自 `583abc9`,同上剔除后比) | 同上;CONTEXT §三.17 ADR-037 订正块 | **第二层锚定剔除**;CONTEXT §三.17 再加一个订正块(「剔除公共边界块**与 ADR-046 物品行**后逐字节相同」) |
| 3 | `golden/cyberpunk/turn_T*.txt`(10 份) | `PaidanPromptSnapshotTest`(`:54-64`) | 10 份 | **含**(取自 `cf29c31`,ADR-038 之前、ADR-037 之后;直接 `isEqualTo`,不剔除) | 本测试自己的先例:ADR-044 刀 1 **重录** `world-gen.txt`,类注释记「重录记录」并写明差异恰好是哪一行(`:33-34`) | **重录** 10 份 turn 快照,类注释追加重录记录:差异恰好是 D4 那一行 |

- **为什么 1、2 不重录**:ADR-037 决策 5(c) 原话的理由(重录会抹掉金样本的出处 `3e72099` / `583abc9`)仍成立;
  ADR-037 已知代价 10 定的规则是「**超过两层就改为重录**」(`ADR-037:316`)。ADR-046 是第二层,仍在线内;**第三次改骨架时就该重录**,
  本 ADR 把这一点写进已知代价,免得下一刀再剔第三层。
- **锚是否唯一**:骨架行的锚取 `TurnPromptBuilder.java:368` 那一行(「…与历史不得矛盾、不得无故回升)」),在三组快照里各出现恰好 1 次
  (已查:`cultivation_T1.txt`、`cyberpunk/turn_T1.txt`、legacy 三份各 1 次);《验讫》锚取 `:863` 那一行,在 `cultivation_x_cthulhu_T1.txt` 里 1 次。
  `anchoredStrip` 本来就断言整串恰好 1 次,锚不唯一会直接红。
- **不受影响**:`golden/world-gen-prompts/`、`golden/cyberpunk/world-gen.txt`、`golden/life_sim/world-gen.txt`(D5 不动 world-gen);
  `TurnTraceTest`(同 build 重渲染)。`TraceRecheck`(手动工具)对**旧轨迹**会全部报 sha 不符 —— 这是底稿 §1.6 已记的预期行为,不是回归。

### 勘察 2 · 五个 parity / 往返测试在 D1–D3 下会不会红

**都不会红**。共同前提已查实:**现有夹具与基线里没有一条 `stateUpdate` 带 `inventory` 键**
(python 遍历 `golden/validator-parity.json`、`golden/event-loop-golden.json`、`trace-fixtures/first-real.jsonl`:三处 0 条;
基线 571 条轨迹的 `parsed.stateUpdate` 非数值键只有 `timeline`,567 条,`inventory` 0 条)。D3 在缺省时沿用,故这些夹具的落账结果逐字节不变。

| 测试 | 结论 | 依据 |
|---|---|---|
| `TraceFixtureReplayTest` | 不红 | 回放重放记下的 `parsed`;6 行里无 `inventory` → `apply` 不写 → `post` sha 不变。`SessionDocument.encode` 不加新键(inventory 本就在 world 里) |
| `ClientProjectionParityTest` | 不红 | **回合 `delta` 不带 `world.character`**:`EventLoopService.buildDelta`(`:580-612`)只放 `turn` / `status` / 各轴 / `discoveredRules` / `availableActions` / 可选 `timeLabel` / `markedTurns`。init / state 响应带 `world`(视图 3 含 inventory),但桩模型的尾巴(`:170`)只有数值轴与 `timeline`,inventory 保持 T0 |
| `ValidatorParityTest` | 不红 | 38 条录制原文都没有 `stateUpdate.inventory`;D2 只在**给了**时查形状 |
| `EngineRestoreTest` | 不红 | `restore`(`Engine.java:142-209`)把 world 整个 deepCopy 进来,`toPersistedState`(`:223-256`)把 world 整个 deepCopy 出去 —— inventory 随 world 往返,不新增读写点 |
| `TransformParityTest` | 不红 | 喂的是 `event-loop-golden.json` 的 `parsed`,无 `inventory` |

### 勘察 3 · `stateUpdate` 里多一个非数值键,别的消费者

| 消费者 | 结论 | 依据 |
|---|---|---|
| `Engine.apply` 轴遍历 + 跳变检查 | 不受影响 | 遍历的是**引擎声明的轴** `attributes.entrySet()`(`Engine.java:271-281`),按 key 去 `upd` 里取;不遍历 `upd`。今天没有任何 archetype 的轴叫 `inventory`(也没有叫 `timeline` 的) |
| `GameSchemas.validateTurn` | **要配合改一处** | `requireEachNumberInRange(upd, "stateUpdate", 0, 100, "timeline")`(`GameSchemas.java:107`)遍历 `upd` 的**每个键**,值是数字就当轴查 0–100。若模型写成 `"inventory": 3`,它会被当成数值轴放过。D2 的形状校验要把 `inventory` 加进同一个 skip 清单,由新的形状检查接管 |
| `DataStats` | 不受影响,口径随之变 | 读的是存档 / 轨迹 `pre` 里的 `world.character.inventory`(`DataStats.java:200`);改后读到的是**当时**的清单而不是开局清单 |
| `SelfPlayDraftTool` 成稿 | 不受影响 | 「世界(视图 3)」那行取的是 `http` 记录里 init / resume 响应的 world(`SelfPlayDraftTool.java:205-215`、`:280`)= T0 → 仍是**开局**清单 |
| `SelfPlayDraftTool` 两道扫描 | 不用改 | ① `LEAK_TOKENS` 字段名、② T0 视图 1 里挂在这四个键下的字符串原文(`:471-518`);`inventory` 不是隐藏字段 |
| `LeakDetector` | 不受影响,但见已知代价 | `detect` 只扫**叙事**(`Engine.apply` 第 7 步,`:294`)。模型写的 inventory 文字进视图 2 与视图 3(init / state 响应),不在任何扫描之内 |
| 引擎 issues 标记读者 | 不受影响 | `ClientProjection.markedTurns`、《派单》《七日》跨档标记按各自固定字样匹配 issue 行;D2 的截断 issue 不含这些字样 |

### 勘察 4 · 「所选选项点名物品」那段数据流:本刀不需要改

- 推进句的选项文字取自 `session.currentActions()`(`EventLoopService.actionTextOf`,`:903-913`;`renderPrompt :232-235` 调用)。
  `currentActions` 是**上一回合尾巴**里的 `availableActions`(`landSettled` 末尾 `updateActionsFromParsed`,`:439-442`)。
- 也就是说:第 N 回合 prompt 里的「所选行动」与世界段里的 inventory,**都出自第 N−1 回合的同一条尾巴**。
  矛盾的形状是「同一条尾巴里,选项还在让玩家用一件已经用完的东西」。所以约束要落在**生成尾巴时**(D4 的最后一句把选项绑到本回合结束后的清单),
  而不是在服务端改选项文字的取法。`actionTextOf` 与 `currentActions` 的流程**不改**。
- 不做服务端拦截(不像 `QiriActionGuard` 那样检查选项):选项与物品的对应是语义判断,引擎无知(见已知代价 2)。

### 附带查到的事实(不是四项之一,但影响落笔)

1. **`--resume-t0` 不能只挑槽位**:`selfplay.py` 续玩时按 `--plan` 的全部槽位逐个走(`run :640-646`),没有槽位参数。
   新 store 里没有 T0 的槽位,`GET /state` 返回 404 → 记「续玩失败」并继续下一个槽位(`_run_slot_resume :608-615`;
   `_is_failed_result` 不把它算失败,退出码仍 0),**不调模型、不花钱**。刀 2 的做法见 D7。
2. **副读数「inventory 实际被更新的回合数」两个现成脚本量不到**:`invT0.py` 只比 T0 与终局两点,`invproxy.py` 只找物品名出现的回合。
   要数每回合是否更新,得读轨迹 `parsed.stateUpdate` 有没有这个键、以及 `pre` 与下一回合 `pre` 的清单是否不同。见 D7。
3. 两个脚本把套件目录写死在 `~/aiuniverse-selfplay/20261123-a`;`invproxy.py` 的物品表也写死(四局四件)。
   另:续玩套件的 summary 里 `t0` 字段沿用原套件的相对路径(`selfplay.py :604`),`invT0.py` 拿新套件目录去拼它**可能找不到文件**(**未证实**,刀 1 入库时实测)。

## 候选方案

### 方案 A:回合尾巴带整份 inventory,引擎落账(本 ADR 采纳)

**优点**:
- 不加调用,成本只多尾巴里一小段(inventory 均值约 29 字,底稿 §1.1);
- 落点有先例:`markRuleDiscovered` / `markEndingReached` 已经在 `apply` 里写 world 内部(底稿置顶 3);
- inventory 本来就在 world 里、本来就进 prompt 与存档,**不新增字段、不迁移**,旧局下一回合起自然开始被改写;
- 档 1 回放天然能重现(`trace.settled(parsed)` 记的就是带这个键的 parsed,`apply` 是纯落账)。

**缺点**:
- 模型漏写时引擎只能沿用,不知道「其实用掉了」(已知代价 1);
- 骨架加一行 → 逐字节快照要处理(勘察 1)。

### 方案 B:另起一次调用专门抽取物品变化

**优点**:主调用的尾巴不变;抽取可以专心做。
**缺点**:每回合约 +0.70–1.10 分,对比现均值 0.80 分,接近翻倍(底稿 §2.3);挂在置相位前会推迟下一回合、挂在之后要先证 `Engine` 并发写安全(底稿 O-6);
回放要另记这次调用的产出。为一个尾巴里加 29 字就能带出来的字段,不值。**排除**。

### 方案 C:账本条目在 prompt 里取代 inventory,inventory 退化成开局种子(底稿 §三(a))

**优点**:为将来的人物 / 承诺账本留了统一形状。
**缺点**:要新增持久化字段、旧存档要按空账本或按 inventory 播种(底稿 §五.2);世界段里会同时有「开局种子」与「账本」两份物品文字,或者要剔 inventory 让所有快照红。
人物 / 承诺这次不做(承诺 0 次、人物多是推测),为不做的部分付这份代价不值。**排除**;解冻条件见「不做」。

### 方案 D:增量(`inventoryAdd` / `inventoryRemove`)而不是整表

**优点**:尾巴更短。
**缺点**:「半瓶聚气丹」用掉一粒是删、是改名还是不动,增量表达不了「数量变化」;引擎要按字符串匹配删项,匹配不上又是一类错误。整表替换让引擎完全不懂物品。**排除**。

## 最终决策

**方案 A**。以下 D1–D7 是**校勘代定**,Felix 审 ADR 时逐条认或改。

### D1 线上形态(校勘代定)

`stateUpdate` 新增**可选**键 `inventory`,值为**中文字符串数组**,表示**本回合结束后的整份清单**(整表替换)。

| 尾巴里 | 含义 | 引擎 |
|---|---|---|
| 没有 `inventory` 键 | 沿用 | 不动 `world.character.inventory`(照 `timeline` 的写法,`Engine.java:283-285`) |
| `"inventory": []` | 身上已无物品 | 写成空数组 |
| `"inventory": ["…", …]` | 新的整份清单 | 整表替换(上限见 D2) |

理由:放进回合输出,不另起调用(底稿 §2.3:另起一次调用每回合约 +0.70–1.10 分,对比现均值 0.80 分);inventory 均值约 29 字,尾巴只多一小段。

### D2 校验(校勘代定)

- `GameSchemas.validateTurn` **只查形状**:给了就必须是数组,元素必须是非空字符串(沿用 `requireNonEmptyString` 的口径:空串算错,纯空白不另判)。
  不合法 → 走现有的那一次修复(不新增修复发数,守 ADR-024「最坏 2 × 20 秒」)。
- `inventory` 加进 `requireEachNumberInRange` 的 skip 清单(勘察 3),防止 `"inventory": 3` 被当数值轴放过。
- **引擎侧最多保留 8 条**:超出的截断为前 8 条(按模型给的顺序),并记一条 issue:
  `T{n} inventory 超过 8 条,截断为前 8 条(ADR-046)`。**不为数量上限触发修复**。
- 引擎原样写入元素,不去重、不去首尾空白、不改字。

### D3 落账(校勘代定)

- `Engine.apply` 在 timeline(第 5 步)之后写 `world.character.inventory`,先例 `markRuleDiscovered` / `markEndingReached`(`Engine.java:439-456`):
  写的是引擎手里那份可变 world,`snapshot` / `toPersistedState` 出口照旧 deepCopy。`world.character` 不是对象时不写(validateWorld 之后不会发生;不为它新造对象)。
- **降级回合**(`applyNoOp`,`:352-362`)不动 inventory —— 与它「绝不脏写 timeline / 数值」同一口径。
- **修复回合以修复后的尾巴为准**:`repairOnce` 返回的是修复尾巴回灌后的 parsed(`EventLoopService :313-339`),`settle` 拿它落账;
  修复尾巴里缺这个键 → 按缺省沿用(即便原尾巴里有过)。
- 档 1 回放:`trace.settled(parsed)` 在 `landSettled` 之前记的 parsed 就带这个键,回放走同一个 `apply`,确定性重现;不改轨迹格式。

### D4 提示词(校勘代定;措辞 CC 起草,与简报原句的差异见文末)

**骨架**(`TurnPromptBuilder.SKELETON` 尾巴字段清单,插在 `:368` 那行之后、`%7$s` 之前;单体与融合共用,两段 md 同步):

```
  物品有增减(用掉、送出、丢失、获得、数量变化)时,stateUpdate 另给 "inventory": ["<物品>", …],写本回合结束后随身物品的整份清单;
  没有变化就不写这个键。用尽的物品从清单里删掉,不保留空壳。选项里要用到的随身物品(服用、拿出、交出),必须是这份清单里还有的;
  本回合没写 inventory 时,这份清单就是世界设定里的 character.inventory。
```

**《验讫》combo 指令**(`FUSION_TURN_COPY` 里 cultivation×cthulhu 的 `extraDirectives` (6) 末尾,`:863` 之后接一句;md 融合段同步):

```
    stateUpdate 的 inventory 清单也只用第一级词汇,物品沿用开局时的叫法。
```

(依据 CONTEXT §三.16 (10):每回合都会回传模型的文本都受揭示阶梯约束。已对过 `CultivationCthulhuTurnTest` 的第二级整表
`网 / 格 / 收 / 挑拣 / 成色`:这句与骨架那行都不含。)

**md 与运行时同步 + 新 lockstep**:`prompts/event-loop.md` 的尾巴字段清单(`:150-153`)与融合段《验讫》(6)(`:242-244`)各加同一句。
新增 `TurnTailFieldsLockstepTest`(仿 `CommonBoundaryPromptLockstepTest`):
(a) 新增那一行作为测试侧逐字常量,断言**回合单体(registry 全部已激活世界)、回合融合(全部已登记组合)、`event-loop.md`** 三面都含它(md 侧不加 markdown 加粗,同 `ContentSafetyPromptLockstepTest` 的做法);
(b) 尾巴五个字段名 `stateUpdate` / `triggeredRuleIds` / `discoveredRuleIds` / `availableActions` / `ending` 在运行时骨架与 md 尾巴段**各出现**
(md 与运行时格式不同 —— 反引号、加粗 —— 整段逐字比做不到,故按短语守,与 `TurnFusionLockstepTest` 同一做法);
(c) 《验讫》那一句并入 `TurnFusionLockstepTest` 的 per-combo 短语表。

**`REPAIR_SYSTEM` 是否提一句 inventory —— CC 勘察后的提议:不改。** 理由:
1. 修复 prompt 把**失败的尾巴原文**回喂(`buildRepairPrompt :1047-1051`),原尾巴里的 inventory 就在模型眼前;
2. 若失败原因就是 inventory 形状,`validateTurn` 的错误清单会点名 `stateUpdate/inventory`,一起回喂;
3. 基线 42 次修复**全部**是「结构化尾巴非合法 JSON」(底稿 §2.3),是语法问题不是字段问题;
4. 修复时漏掉这个键的后果是「本回合沿用」(D3),不会写坏状态。
   代价:修复回合漏写的比例今天量不到(失败尾巴原文不进轨迹,底稿 O-7),见已知代价 6。校勘定。

### D5 不动(校勘代定)

- world-gen(仍产 1–3 件;「半瓶」这类模糊量词不在本刀改);
- 前端(只有 `schema.ts:47` 的类型,组件不渲染);
- `Engine.restore` 与 `SessionDocument`(inventory 字段本来就在存档的 world 里,旧存档与 T0 无需迁移);
- `schemaVersion`(不新增持久化字段,仍 "0.4");
- `actionTextOf` / `currentActions` 的取法(勘察 4)。

### D6 CONTEXT(校勘代定;在代码刀里改,ADR 只写定稿措辞)

按 CONTEXT 既有写法(**上文原文一字不动、后加 ⚠️ 补充块**,ADR-039 / 040 / 044 均如此),在 §二「字段职责」末尾、ADR-044 补充块之后加:

> ⚠️ **补充(ADR-046,<落地日期>;上文原文与 schema 块一字不动)**:`character.inventory` 的归属改为 —— **初值由 AI 在 world-gen 时生成**(仍 1–3 件);
> **回合中 AI 可在 `stateUpdate.inventory` 给出本回合结束后的整份新清单**(中文字符串数组,整表替换),**引擎落账**写回 `world.character.inventory`:
> 缺这个键 = 沿用,空数组 = 身上已无物品,超过 8 条截断为前 8 条并记一条引擎 issue;降级回合不动;修复回合以修复后的尾巴为准。
> 校验器只查形状(给了就须是数组、元素为非空字符串),不懂物品语义,引擎也不推断。上文「AI 生成:… `character` 初值」与「引擎维护」两句,
> 对 `inventory` 以本补充为准。它本来就在 `world` 里,随 `world` 进视图 1 / 2 / 3;**不新增持久化字段,JSON `schemaVersion` 不动(仍 "0.4")**,旧存档无需迁移。
> 出处 [ADR-046](adr/ADR-046-inventory-updates-per-turn.md)。

同刀在 §三.17 ADR-028 补充段的 ADR-037 订正块之后再加一个订正块(勘察 1 第 2 组):
「锚定剔除公共现实边界块**与 ADR-046 物品行**后,回合 prompt 与 `583abc9` 逐字节相同」。文档版本 v3.2 → **v3.3**,版本历史加一行。

### D7 测量(刀 2,Felix 本机跑;校勘代定)

- **同一冻结 T0**(原套件 `20261123-a`),`--resume-t0` 只重跑 **xiu-1、xiu-2、xiu-long、rong-1** 四槽(60 + 60 + 120 + 60 = 300 回合)。
  xun 两局与 gui-1 物品矛盾为 0,不重跑。
  **做法(附带事实 1)**:README 第 2 步的拷贝脚本只拷这四槽的 T0;其余三槽 `GET /state` 404,summary 记「续玩失败」,不花钱。
  这是**不改代码**的做法;读 summary 的人要知道那三行是有意的。(另一条路是给 `selfplay.py` 加槽位参数 —— 改的是 bakeoff 工具,不是 `src/main`。校勘定。)
- **事先登记的读数**(不设通过线,如实记录):

  | 读数 | 口径 | 基线 |
  |---|---|---|
  | **主:物品类 ② 次数** | 按上一份底稿的同一判读口径**人工判**;候选回合由 `invproxy.py` 机械给出 | 21 |
  | 副:inventory 实际被更新的回合数 | 轨迹 `parsed.stateUpdate` 带 `inventory` 键的回合数;其中清单与上回合不同的回合数 | 0(底稿 A.7) |
  | 副:修复率 | 修复回合 / 回合 | 39 / 300 |
  | 副:每回合成本 | 同 `cost.py` 口径 | 底稿 A.6 |
  | 副:durMs | 同 `traceStats.py` 口径 | 底稿 A.4 |

- **成本闸 ¥5**。估算(**不是实际花费**):按基线每回合均值 0.80 分 × 300 ≈ ¥2.4;尾巴每回合多几十个输出 token,按 8.0 元 / 百万 token 每回合不到 0.05 分。
- **尺子入库**(刀 1 一并做,保证前后同一把尺子):底稿附录 A.7 `invT0.py`、A.9 `invproxy.py` 入 `bakeoff/selfplay/`。
  **只把写死的套件目录改成命令行参数,其余逐字不动**(物品表照原样四局四件);`invT0.py` 对续玩套件的 `t0` 路径若拼不上(附带事实 3),
  加第二个参数「T0 所在的原套件目录」。副读数「被更新的回合数」两个脚本量不到(附带事实 2),**另加一个只读小脚本** `invupdates.py`(CC 提议,校勘定)。
  O-1(上一份底稿的跨窗计数)不在本刀。

### 关键理由

1. 底稿给的形状很窄:21 次全在开局物品、全在选项点名的回合、inventory 一字未变。修这个通道就是对症,不需要先造一个通用账本。
2. 全部复用已有肌肉:`timeline` 的「可选 + 缺省沿用」、`markRuleDiscovered` 的「apply 写 world 内部」、ADR-006 尾巴 + 一次修复、ADR-037 的锚定剔除。
3. 引擎仍对物品无知:整表替换,不解析「半瓶」,不按字符串删项 —— 避开了「引擎开始懂内容」这个本项目一直守着的口子(ADR-008)。
4. 留路:若重测后物品矛盾仍高,下一步可以在 D4 的选项约束上加服务端核对,或把清单移出世界段单独成块;若非物品类矛盾仍可观测,解冻人物 / 承诺账本。

## 不做

1. **人物 / 承诺 / 事件账本**。解冻条件(任一):
   - 本刀刀 2 重测后,**非物品类矛盾仍可观测**(按上一份底稿口径人工判);
   - 或有了「**埋专名 + 点名追问**」的探针 —— 今天做不到:回合 API 不收自由文本,selfplay 只能在模型给出的选项里抽(底稿 Q-4);
     做它需要改 `src/main`(放开自由文本或加测试用的行动覆盖入口),**另立 ADR**。
2. world-gen 的模糊量词(「半瓶」「半块」)、要求开局写数量 —— 不改(D5)。
3. 服务端核对「选项点名的物品是否在清单里」—— 不做(勘察 4、已知代价 2)。
4. 前端显示物品栏 —— 不做。
5. O-1 跨窗计数脚本补进仓库 —— 不在本刀。
6. xun-2 T0 世界自相矛盾(底稿 O-2)—— 不在本刀。

## 已知代价

1. **模型漏写 inventory 时,状态照旧不更新,引擎不做推断**。用掉了却没写 → 下一回合 prompt 里它还在,与今天同。
   缓解方式:D4 把「有增减就写」放进骨架;刀 2 副读数直接量出「该写没写」的规模(被更新的回合数对照物品类候选回合)。
2. **选项与清单的一致只靠提示词**。模型若在同一条尾巴里删了物品又让选项用它,服务端不拦。缓解方式:刀 2 主读数会显出来;再出现就是「服务端核对」的解冻信号。
3. **快照处理**:两组金样本走**第二层锚定剔除**(按 ADR-037 已知代价 10 的规则,**再改一次骨架就必须重录**,出处改记为那次的实现 commit);
   《派单》10 份回合快照**重录**,出处随之变成刀 1 的 commit。
4. **世界的模糊量词**:开局是「半瓶聚气丹」,用掉一粒后清单该写什么由模型自己定(「聚气丹(余两粒)」或照旧「半瓶聚气丹」),
   引擎不管;同一件物品可能换叫法,人工判读时要把它当同一件。
5. **模型写的 inventory 文字不在任何扫描之内**:`LeakDetector` 只扫叙事(勘察 3),但 inventory 进视图 3(init / state 响应)。
   今天前端不渲染它,所以玩家看不到;**前端一旦显示物品栏,要先把它纳入泄露与安全扫描**。《验讫》的揭示阶梯靠 D4 那一句提示词,不是引擎保证。
6. **修复回合漏写量不到**:失败尾巴原文不进轨迹(底稿 O-7),无法区分「原尾巴就没写」与「修复时丢了」。`REPAIR_SYSTEM` 不改(D4),代价在此。
7. **`DataStats` 的 inventory 口径变了**:改前它读到的永远是开局清单,改后是当时清单。读旧统计与新统计时要知道这一点。
8. **8 条上限是拍的数**:开局 1–3 件,基线里一局物品从没超过 3 件,8 留了余量;没有数据支持更精确的值。

## 测试面(刀 1)

| # | 面 | 断言 |
|---|---|---|
| 1 | 校验 | `validateTurn`:缺键通过;`[]` 通过;字符串数组通过;非数组(字符串 / 数字 / 对象 / null)报 `stateUpdate/inventory`;元素非字符串或空串报 `stateUpdate/inventory/<i>`;`"inventory": 3` **不再**被当数值轴放过 |
| 2 | 引擎三态 | 给了 → 整表替换;缺省 → 与 apply 前逐字节相同;空数组 → 写成 `[]` |
| 3 | 截断 | 9 条 → 保留前 8 条、记恰好 1 条 issue、字样如 D2;8 条 → 不截断、不记 issue |
| 4 | 降级 | `applyNoOp` 前后 inventory 不变 |
| 5 | 修复 | 桩模型:主尾巴带 inventory 但 JSON 坏、修复尾巴不带 → 落账沿用;修复尾巴带 → 以修复尾巴为准 |
| 6 | 往返 | apply 改写后 `toPersistedState` → `restore` → `contextJson` 带新清单;旧 T0 存档(无改写)照常回载 |
| 7 | 视图 | 改写后的清单出现在 `contextJson`(视图 2)与 `toClientState`(视图 3);回合 `delta` 不新增键 |
| 8 | 回放 | 档 1 回放一条带 `inventory` 的 parsed,重现同一 `post` |
| 9 | lockstep | `TurnTailFieldsLockstepTest`(D4 (a)(b));《验讫》那一句进 `TurnFusionLockstepTest`;`CultivationCthulhuTurnTest` 现有第二级扫描照常覆盖 |
| 10 | 快照 | 勘察 1 三组:两组第二层锚定剔除(断言整串恰好 1 次)、一组重录并写重录记录 |
| 11 | 变异 | 分别摘掉:骨架那一行 / md 那一行 / 《验讫》那一句 / `apply` 里的写入 / skip 清单里的 `inventory` / 截断 —— 各应只有对应的那条红 |

零回归护城河:`EngineGoldenTest`、`TransformParityTest`、`ValidatorParityTest`、`TraceFixtureReplayTest`、`ClientProjectionParityTest` 不动夹具、全绿(勘察 2)。

## 重新审视的触发条件

- 刀 2 主读数没有下降,而副读数显示 inventory 很少被更新 → 先查提示词是否被读到,再考虑清单移出世界段单独成块;
- 刀 2 主读数下降,但出现「选项用了清单外的物品」→ 解冻服务端核对(不做 3);
- 重测后非物品类矛盾仍可观测,或有了探针 → 解冻人物 / 承诺账本(不做 1);
- 前端要显示物品栏 → 先补扫描(已知代价 5);
- 再改一次回合骨架 → 金样本改为重录(已知代价 3)。

## 实施分刀

1. **刀 1 · 代码 + CONTEXT**(一个 feature 分支,atomic commit):
   校验(D2)→ 引擎落账(D3)→ 提示词与 md(D4)→ lockstep 与快照处理(勘察 1)→ CONTEXT §二补充块 + §三.17 订正块 + 版本 v3.3(D6)→
   `bakeoff/selfplay/` 入库 `invT0.py` / `invproxy.py` / `invupdates.py`(D7)。
   完成的定义:`mvn test` 全绿(报具体数字)+ 变异各红一条;不动前端,`npm test` 不必跑但要说明。**只推不合**。
2. **刀 2 · 测量**(Felix 本机,真 key,成本闸 ¥5):按 D7 重跑四槽 300 回合;只给命令文本与要看什么。
3. **刀 3 · 读数回填**(纯 docs):主读数人工判读(同上一份底稿口径)、副读数机械计数,回填本 ADR「实际效果」与 FINDINGS;ROADMAP 一行。

## 实际效果(事后补充)

*刀 2 跑完时回填:四槽物品类 ② 次数(基线 21)、inventory 被更新的回合数、修复率(基线 39 / 300)、每回合成本、durMs。*

## 与简报的出入(按简报要求停下报告,未自行调和;Felix / 校勘定)

| # | 简报 | 勘察 / 本 ADR 的写法 | 依据 |
|---|---|---|---|
| 1 | 「改了骨架后要**重录**的逐字节快照清单」;已知代价写「快照重录」 | 三组里**两组按 ADR-037 先例走第二层锚定剔除、不重录**,只有《派单》10 份回合快照按它自己的先例重录 | 勘察 1;`ADR-037:316`;`PaidanPromptSnapshotTest:33-34` |
| 2 | D4 最后一句「选项里提到的随身物品,必须在这份清单里」 | 改为「选项里**要用到的**随身物品(服用、拿出、交出),必须是这份清单里还有的;本回合没写 inventory 时,这份清单就是世界设定里的 character.inventory」 | ①「提到」会把「去丹房领一瓶」这类尚未到手的物品也框进来;② 缺键时「这份清单」无所指 |
| 3 | D6「§二 字段职责**改写** character.inventory 的归属」 | 按 CONTEXT 惯例**不改写原文**,在 §二 末尾加 ⚠️ 补充块;另需 §三.17 加一个订正块(legacy 金样本的剔除口径) | CONTEXT §二 ADR-044 补充块、§三.17 ADR-037 订正块 |
| 4 | D7「`--resume-t0` 只重跑四槽」 | 工具没有槽位参数;提议用「只拷四槽 T0」实现,三槽记「续玩失败」不花钱 | 附带事实 1 |
| 5 | D7「两个脚本入库,保证前后同一把尺子」 | 两个脚本量不到副读数「被更新的回合数」,需第三个小脚本;两个脚本的套件目录写死,入库要改成参数 | 附带事实 2、3 |
| 6 | 底稿 §1.6「其他按世界快照 未证实」 | 已查实:没有;《验讫》那一句另外碰到 `golden/turn-prompts/cultivation_x_cthulhu_T*.txt` 10 份 | 勘察 1 |
| 7 | D2「只查形状」 | 另需把 `inventory` 加进数值范围检查的 skip 清单 | 勘察 3 |

## 跟其他文档的交叉引用

- **事实底稿**:[`docs/memory-ledger-survey.md`](../memory-ledger-survey.md)(Q-2、§1.6、§2.3、§三(b)、附录 A.7 / A.9)、[`docs/long-memory-survey.md`](../long-memory-survey.md)
- **上游测量**:[ADR-045](ADR-045-long-game-selfplay-measurement.md)(自玩工具、`--resume-t0`、第一套 `20261123-a`)
- **线上协议**:[ADR-006](ADR-006-event-loop-streaming-wire-protocol.md)(哨兵尾巴、一次修复);[ADR-024](ADR-024-stream-segment-deadline.md)(修复发数上限)
- **引擎无知**:[ADR-008](ADR-008-multi-mode-extension-architecture.md)
- **快照先例**:[ADR-037](ADR-037-world-tone-and-common-topic-boundary.md) 决策 5(c) / 已知代价 10;[ADR-044](ADR-044-fixed-title-world-subtitle.md) 刀 1 重录记录
- **揭示阶梯**:[ADR-035](ADR-035-third-fusion-combo-yanqi.md);CONTEXT §三.16 (10)
- **回放**:[ADR-031](ADR-031-turn-execution-trace-and-offline-replay.md)
- **源文件**:`server/…/engine/Engine.java`(`apply` / `applyNoOp`)、`server/…/engine/GameSchemas.java`(`validateTurn`)、
  `server/…/eventloop/TurnPromptBuilder.java`(`SKELETON` / `FUSION_TURN_COPY` / `REPAIR_SYSTEM`)、`prompts/event-loop.md`
