# ADR-035 · 第三组合《验讫》(cultivation × cthulhu,host=cultivation)

- **日期**:2026-10-16
- **状态**:已采纳(Felix 2026-10-16 定;本 ADR 只落决策,不含实现);后端已实现并经真 key 冒烟验通(2026-10-17);前端 Slice E 未做,未部署;**Slice E 已实现(封面暂借修仙图,2026-10-18,`5c9f322`;局内体感待 Felix 判,未部署)**(前句原文保留);**已部署 `faeeb8c`(Felix 2026-10-19);线上选择屏可拖出《验讫》卡、开局与局内(修仙皮肤、五轴、开场)真机验通**(前句原文保留)
- **决策者**:Felix

## 背景

混合模式已有两组融合:round 1 识海遗蜕(修仙 × 规则怪谈,ADR-012/013)与 round 1.5 缺页的人防工程(规则怪谈 × 末日,
ADR-014)。ADR-014 把融合提示词参数化为「骨架 + per-combo 槽位」,并预言「加一组融合 = 本表项 + 文案槽 + 融合 meta-prompt
+ 种子池,骨架零改」。本 ADR 是这条预言的第三次兑现,也是 [打磨与愿景 backlog §8](../phase2-polish-and-vision-backlog.md)
第 ⑤ 对「飞升是捕捞」(当时留作压轴)。

**素材来源**:两份创作成品批原样存档于 [`docs/fusion-yanqi-drafts/draft-1.md`](../fusion-yanqi-drafts/draft-1.md)、
[`draft-2.md`](../fusion-yanqi-drafts/draft-2.md)。**以第二份为底**;本 ADR「最终决策」与底稿冲突处一律以本 ADR 为准
(底稿是历史素材,不回改)。

### 勘察结论(只读 origin/main `6f020d3`,2026-10-16)

**a. 外来轴换皮(`ArchetypeRegistry.applySkin`)**:可以换。`AxisSkin` 换 `displayName` 与 `bands`(**总是**替换 ——
skin 给空档表 = 该轴丢档,故必须给全三档);`behaviorHint` 仅当 skin 给了 override 才换。`key` / `min` / `max` /
`axisRole` / `lethal` / **`perilAtHigh`** 全保留 → 窥天换皮后**仍是双刃轴**(最高档 danger、次高档 caution,
ADR-018 severity 自动等同克苏鲁单体,per-combo 对 severity 零登记)。⚠️ behaviorHint override 是**整段替换**:
克苏鲁 knowledge 原文里的「越高 san 流失越快」联动与「初值较低正基线、绝不给 0」取值约定**会一起丢**,必须在新 hint 里写回(见决策 1)。
撞键:cultivation 与 cthulhu 都有 `hp` → host 赢,取修仙「气血」;存活的外来轴 = `san`、`knowledge`。

**b. 换皮 bands 阈值**:可以沿用,构造器校验能过。累积轴 threshold 写 `0 / 31 / 61`(下界 inclusive)→ 区间
0–30 / 31–60 / 61–100,与现有克苏鲁禁忌知识、修仙境界逐字一致;损耗轴写 `100 / 50 / 20`(上界 inclusive)。
`validateBands` 要求 depletion 含 `threshold == max`(100)、accumulation 含 `threshold == min`(0),两者都满足。

**c. per-combo 槽位实际清单(源码为准)**:
- registry:`FUSION_COMBOS`(有序 key → `Map<String, AxisSkin>`)。
- world-gen:`FUSION_WORLD_COPY` → `FusionWorldCopy` 16 个文案槽(`trueName` / `fakeName` / `recoveryExamples` /
  `recoveryCosts` / `endingIdExamples` / `failureOutcomeExamples` / `successOutcomeExamples` / `failureConditionExamples` /
  `mixedConditionExample` / `axisKeysBan` / `englishKeyExample` / `axisConsistency` / `successConditionExample` /
  `vagueExamples` / `thresholdModeration` / `hintExamples`);`FUSION_META_PROMPTS`(融合内核,自由文本,注入 `%9$s`);
  `FUSION_SEED_POOLS`(`List<String>`);派生槽 `%14$s` 结局条数 = `endingCountRange`(致命轴 n → `n+1`–`n+2`)、
  `%15$s` 致命轴中文名清单。
- event-loop:`FUSION_TURN_COPY` → `FusionTurnCopy` 6 槽(`penaltyClause` / `rewardClause` / `nearMissExample` /
  `recoveryMeans` / `recoveryCosts` / `extraDirectives`,后者是自由文本、接在 (4) 之后);`FUSION_IMAGERY`(per-key 意象 override)。

  本组合四项新需求逐项判断 —— **全部装得进现有槽,骨架零改**:

  | 需求 | 落点 |
  |---|---|
  | 揭示阶梯(按窥天档限词汇) | world-gen 侧 → 融合内核 `%9$s`;回合侧 → **窥天三档的 `narrationHint`**(event-loop 每回合只注入当前档,正好是「按档放行词汇」的天然载体)+ `extraDirectives` 写总规则;`FUSION_IMAGERY` 的窥天意象是静态的、只准第一级词汇 |
  | 恢复推涨境界(恢复即饲料) | 融合内核 + `recoveryMeans` / `recoveryCosts` + `extraDirectives`;**不进境界 behaviorHint**(修仙原样) |
  | 三日结构 | 融合内核 + `extraDirectives`(与人防工程 (5) 昼夜节律同形) |
  | 起网事件当回合裁决 | 融合内核 + `extraDirectives` |

  **底稿结局池与骨架的冲突(已裁定,见「候选方案」)**:底稿结局池 5 条、含一条不绑轴的 failure「验讫(stamped)」。
  它撞上骨架两处**通用约束**:派生槽 `%14$s` 对 2 致命轴给「3-4」;骨架原文「每条失败结局的 condition 只绑定【单一】
  致命轴、并点名其中文名」。**Felix 裁定:不重切骨架,改结局池。ADR-014「重新审视的触发条件」第一条(第三对组合仍需改
  骨架本体 → 槽位粒度切错)不成立** —— 问题出在结局池违反了骨架对所有融合世界一视同仁的通用约束,而不是某段 per-combo
  文案被错误地焊在了骨架里;槽位粒度没有切错。

**d. 日夜推进**:人防工程的多日昼夜**只靠提示词表达**(融合内核「时间结构 · 多日尺度」段 + 回合 `extraDirectives` 第 (5) 条)。
state 里**没有任何承载天数的字段**;`state.timeline` 是模型每回合写的一句话,会随 state 回传模型;旧回合由 `compressLog`
抽取式折成 `[T3选A]` 串(不含叙事),模型只见最近 `LOG_KEEP = 4` 回合叙事。→ 三日推进**完全依赖模型叙事**,天数可能漂移
或停滞。列为已知代价 + 冒烟项。

**e. 前端可见性**:
- `world.tone` —— **界面可见**(`SceneBanner` 横幅)。
- `world.background` —— 平时只在 payload;**续局且 log 为空时**作叙事兜底显示(`gameStore.ts` 两处)。
- `endings` 池 —— **随 state 全量下发**(`toClientState` 只剥 `isTrue` / `hiddenLogic`;`title` / `description` /
  `condition` / `outcome` 都在 payload,devtools 可见);界面只显示**已达成**那条的 `title` + `description`(`EndingScreen`)。
  (这条回答了 draft-2 §2 末尾带给 ADR 窗口的待勘察项。)另:轴的档名随 `InitResponse.attributes` 下发并**显示在状态栏**,
  故「见格」「知渔」在玩家升到该档时可见 —— draft-2 §1 说明这是有意的(档名本身就是阶梯的一部分)。

**f. 失败结局兜底(`Engine.pickFailureEnding`)**:按「触底/濒零致命轴 → failure 且 condition 含该轴中文名 → 首个 failure →
任意极性含轴名 → endings[0]」逐级退。气血 / 清明触底时,第一步会命中 condition 写了「气血」/「清明」的 failure 结局。
neutral 结局在前两步**永远不会被选中**(只匹配 failure);结局极性 gate **只拦 success**(致命轴 ≤ 10 时),对 neutral
放行 —— 濒死时模型提议 neutral 会被接受。底稿里不绑轴的 failure「验讫」在第二步「首个 failure」会被误选(若排在前),
本裁定已删去它。

**g. 种子池**:`FUSION_SEED_POOLS` 每组合一个 `List<String>`,随机取一条拼在提示词末尾;worldview 固定由元数据注入、
融合内核也是 per-combo 而非 per-seed。**没有「某条种子另带一份 worldview 补丁」的承载位** → 重变体 C **挂账**,不在本 ADR 实现。

**h. 第三组合的前端代价(只列清单,不设计)**:
1. `web/src/features/game/theme/registry.ts` `FUSIONS` 加一项(封面 `sceneUrl` + 卡皮肤 `cardClass`);
2. `web/src/features/game/ArchetypeSelect.tsx` `FUSION_CARDS` 加一条(三层标题、`ariaLabel`、`tagline`;未配文案的组合不渲染);
3. `game.module.css` 新卡皮肤 class(渗漏卡,修仙 × 克苏鲁色板互噬);
4. `web/public/scenes/` 一张封面图(键如 `fusion-yanqi`;未就位回落占位);
5. 组合表本身走 `GET /api/archetypes` 的 `listFusionCombos`(ADR-019),**前端零改**;
6. 局内皮肤一律取 host(修仙「金光流云」);克苏鲁的「文字异常」签名**不会出现**;修仙一级记忆点「钟鸣」挂在境界向上跨档 ——
   本组合「恢复即饲料」让境界上涨带上反讽含义,这层体感**归 Felix 判**,本 ADR 不设计。

约束条件:

1. **引擎 / 校验 / `schemaVersion`("0.4")零动**(CONTEXT §三.16):融合只在播种层 + 提示词 + 前端。
2. **骨架零改**,既有两组合与单体的 world-gen / 回合提示词**逐字节零回归**。
3. **守 ADR-007**:world-gen 单次胖调用、`json_object`、无哨兵、无预调用。
4. **守 ADR-011**:守则与 hint 只写定性风险,不写数字概率、不写「达到 X 即触发 Y」,不承诺引擎据守则判定。
5. **守 CONTEXT §三.16 (5)(7)(8)**:结局单轴绑定 + 中文轴名;时间尺度在 combo 槽里明确;恢复手段可自主执行或兑现窗口明确。

## 候选方案

### 方案 A:保留底稿 5 条结局池,重切骨架

给 `endingCountRange` 加 per-combo 覆盖、把「每条失败结局单轴绑定」改成槽(缺省原文)以容纳不绑轴的事件型失败结局「验讫」。

**优点**:
- 「被盖章带走」作为独立结局,题眼最直接。

**缺点**:
- 为一个组合松动骨架里**对所有融合世界一视同仁的安全规矩**(单轴绑定是治 ADR-013「结局错配」的那一刀)。
- 不绑轴的 failure 正是 `pickFailureEnding` 第二步「首个 failure」误选的那种形状(勘察 f)。
- **排除理由(Felix 裁定)**:问题是结局池违反通用约束,不是槽位粒度切错;ADR-014 触发条件不成立,不为它重切。

### 方案 B:另起一份《验讫》专用骨架

**优点**:
- 不碰既有骨架。

**缺点**:
- 安全规矩(泄露硬化 / 绝境写法 / 单轴绑定)被复制一份,正是 ADR-014 参数化要消灭的形态;
  `ContentSafetyPromptLockstepTest` 守的面从五个变六个,漏改不报红。
- **排除理由**:成本远大于一条结局的价值。

### 方案 C:结局池改为 4 条,「被网带走」并入清明线(本 ADR 采纳)

删去 stamped;「起网当场你没有避开也没有让运」→ 回合 `stateUpdate` 把清明落到 0 → 引擎触底、按中文轴名挑中 `clarity_lost`。

**优点**:
- 4 条落在派生槽「3-4」之内,两条 failure 各绑一轴,骨架两处约束原样满足;**骨架零改**。
- 「被带走」走引擎既有的触底兜底路径,不新造判定;兜底误选面收窄为「两条绑轴 failure 之间」。

**缺点**:
- 「被盖章带走」不再是独立结局名,而是叩台的一种落法 —— 题眼要靠叙事和描述承载。
- 「起网时清明归零」是回合提示词的约定,不是引擎保证(见已知代价)。

## 最终决策

**方案 C**,其余按以下定稿口径(与底稿冲突处以此为准)。

### 1. 轴(registry:`FUSION_COMBOS` 登记 `cultivation×cthulhu` + 两条 `AxisSkin`)

| key | 显示名 | 来源 | 角色 | 致命 | 档(label) | behaviorHint |
|---|---|---|---|---|---|---|
| `hp` | 气血 | 修仙原样(撞键 host 赢) | depletion | ✅ | 修仙原样 | 修仙原样 |
| `mana` | 灵力 | 修仙原样 | depletion | ❌ | 修仙原样 | 修仙原样 |
| `realm` | 境界 | 修仙原样 | accumulation | ❌ | 修仙原样 | **修仙原样**(「恢复即饲料」不写进这里) |
| `san` | **清明** | 克苏鲁「理智」换皮 | depletion | ✅ | `100 澄澈 / 50 起雾 / 20 将失` | **override,取 draft-2 §1 那一版,其中「误信饵文」改为「误信旧说」**(行为提示每回合都注入提示词,「饵」属揭示阶梯第三级词汇,不能出现在第一级就可能注入的文本里;「拓到不该拓的东西」保留原样)(下降情形 + 窥天越高掉得越多 + 调息 / 丹房安神药回升)(Felix 2026-10-16 定);另「将失」档的 `narrationHint` 须写明「不要把低清明写成玩家失去选择权」(取自 draft-1 §1 的护栏)—— 这一档的危险是让人觉得舒服,而不是夺走选择 |
| `knowledge` | **窥天** | 克苏鲁「禁忌知识」换皮 | accumulation(双刃,`perilAtHigh` 保留) | ❌ | `0 未觉 / 31 见格 / 61 知渔` | **override**,见下 |

派生集:致命 = {hp, san}(气血 / 清明)、累积 = {realm, knowledge}、非致命资源 = {mana};`endingCountRange` = 「3-4」。

**窥天 behaviorHint override 必含三点**(前两点从克苏鲁原文写回,第三点是本组合的):
1. **联动**:窥天越高,本回合清明流失就应越快、越凶;须在 `stateUpdate` 里让清明随窥天高低体现加速流失。
2. **取值约定**:初值给一个较低的正基线,绝不给 0;此后也绝不降到 0(累积轴,0 只是起点意味,不是结局)。
3. **只随调查动作上涨**:拓印、对账、验丹、观星、识破旧说;其余回合只持平。

**窥天三档 `narrationHint` 承载揭示阶梯**(措辞实现刀从 draft-2 落,文案归 Felix 审):每档写明本档叙事可用的词汇上限(见决策 3)。
**清明 / 窥天的 `FUSION_IMAGERY` 意象**是静态文本、每回合都注入,故**只准第一级词汇**。

**修订(Felix 2026-10-17,冒烟驱动)。** 上表与上文保留作记录,清明 / 窥天的行为提示以本段为准。

起因:Slice D 首轮冒烟,窥天行为提示里的「窥天越高,本回合清明流失就应越快、越凶」被模型读成**被动衰减** ——
清明每回合都降,歇息的回合也降,28 回合从 72 落到 0。

- **清明下降只挂钩其行为提示列出的情形**(直视真相、拓到不该拓的东西、误信旧说并照着去做、夜里站进不该站的位置)。
- **窥天只放大这些情形造成的下降**:窥天越高,同一情形的冲击越重。
- **未发生这些情形的回合,清明不因窥天而降**(歇息、调息、寻常行走的回合清明持平)。
- **调息与丹房安神药明显回升清明**(调息小幅,丹房安神药明显)。

### 2. 恢复即饲料 / 资源经济

- **写进融合内核与回合融合段**(`recoveryMeans` / `recoveryCosts` / `extraDirectives`),**不写进境界 behaviorHint**。
- 三种恢复:**服余泽丹**(随时)、**调息行功**(随时)、**丹房配药**(仅白天;**夜里不出「领药」选项**,只给过渡行动 ——
  CONTEXT §三.16 (8) 兑现语义)。
- **代价当回合可见**:恢复的同一回合,`stateUpdate` 真的上调对应轴,境界随之上涨,叙事写出代价。
- **调查动作耗灵力**(拓印 / 对账 / 验丹 / 观星)。

### 3. 守则与揭示阶梯

- 真守则称「**实录**」(不带 `isTrue`),假守则称「**旧说**」(`isTrue:false`)—— 两个称呼即 `FusionWorldCopy.trueName` /
  `fakeName` 的值,会出现在叙事里。配比沿骨架:6–8 条,两类各 ≥3,每条都带 `hiddenLogic` + `discovered:false`。
  **守则固定 8 条(Felix 2026-10-16 定)**:draft-2 §3 原列实录 5 条 + 饵文 4 条 = 9 条,超出骨架 6–8 条;**删去实录 3(拓影)**,
  保留实录 1、2、4、5 + 旧说 1、2、3、4(两类各 4 条)。「拓影」这个两难降级为纯叙事,不占守则条目。融合内核按这 8 条给出,
  **不让模型自行挑选**。draft-2 的「饵文」即本 ADR 的「旧说」;实录 4「须在子时之前」是世界内守则原文,保留。
- **旧说 1 保留「封台七日」**;星位显示**第三夜即到**,作为可选线索(玩家可借观星识破)。
- **裁决至少要两种不同来源互相印证**(四处地点的证据,见决策 6)。
- **揭示阶梯(单门槛,按窥天当前档)**:
  - **未觉**(0–30):只写现象和印文原字;
  - **见格**(31–60):才可写 网、格、收、挑拣、成色;
  - **知渔**(61–100):才可写 捕捞、出栏、育肥、排酸、饵。
  - **world-gen 产出的所有玩家可见字段**(`world.background`、`world.tone`、`rules[].content`、`openingNarrative`、
    `availableActions`)**只准用第一级**。
  - ⚠️ **这是提示词约束,不是引擎保证**:窥天数值由模型在 `stateUpdate` 提出、引擎只落账(clamp / 跳变标记),
    引擎不认识「档」与「词汇」,也不拦截任何输出。

### 4. 时间结构(三日三夜,故事结构,不按回合数推进)

- **第一日** 印文与承运;**第二日** 辨真假,用谱牒对出「留养」同门;**第三日** 抉择;**第三夜子时起网,当回合裁决**。
- 不写死回合数(守 ADR-013「不写死回合上限」);推进靠融合内核 + `extraDirectives`(同人防工程 (5) 形态)。
- **让运**:只在起网当场当面提出,当回合兑现(让运是起网那一回合给出的选项)。
- **缓解天数漂移(提示词约定,非引擎保证)**:`extraDirectives` 要求 `stateUpdate.timeline` 每回合以「第 N 日 · 昼/夜」开头 ——
  `timeline` 会随 state 回传模型,是现有结构里唯一跨回合带着走的一句话。
  `timeline` 对玩家可见(随 state 下发到客户端;今天界面不渲染它,但在 payload 里),故它**也只准用揭示阶梯第一级词汇**。

**修订(Felix 2026-10-17,冒烟驱动):时段改为按回合数推进。** 上文保留作记录,时段推进以本段为准。

起因:Slice D 两轮冒烟,第二夜分别持续 15、31 回合;首轮修复后 `extraDirectives` 已明写「一个昼或一个夜通常容得下三到五件事」
「玩家选歇息即直接推进到下一时段」,第二轮仍然无效。终仪拖了 5 回合没有当回合裁决,让运在终仪之前就被谈好。
这触发了本 ADR「三日推进稳定漂移」的重审条件。

- **服务端按本回合序号 n 算出时段**(n = `state.turn` + 1,即提示词末行「请推进第 n 回合」的那个 n),每回合注入回合提示词:

  | 本回合序号 n | 时段 |
  |---|---|
  | 1–4 | 第一日 · 昼 |
  | 5–8 | 第一日 · 夜 |
  | 9–12 | 第二日 · 昼 |
  | 13–16 | 第二日 · 夜 |
  | 17–20 | 第三日 · 昼 |
  | 21–24 | 第三日 · 夜 |

- **第 23 回合**:终仪前一刻,本回合末的选项须给出终仪当场的抉择(含当面让运、避开)。
- **第 24 回合**:封台终仪,按玩家所选当回合裁决并给出结局(三路同决策 7 修订段与回合 `extraDirectives` (7):
  未避开未让运 → 清明落 0 走「叩台」;让运且对方应下 → 「独过此夜」;避开且未让运、清明 ≥ 30 → 「守台人还」)。
- **第 25 回合起**(终仪已过仍未结束):每回合注入「封台终仪已过,本回合必须给出结局」。
- **推翻原口径**:上文「三日是故事结构,不按回合数自动推进」(本节标题与「不写死回合数」一条),以及首轮修复加进
  `extraDirectives` (5) 的「玩家选歇息即推进到下一时段」。理由是冒烟数据:靠叙事推进时段,两轮第二夜分别 15、31 回合,
  补「三到五件事」的节奏说明后也没有收敛;时段只有在服务端每回合明说时才稳定。
- 让运只在终仪当场提出,**不得在此前的回合谈妥或约定**(回合 `extraDirectives` (7) 同步改写)。
- **第三夜的事实只给模型**:第 1–22 回合的注入只写日子与昼夜,不提封台终仪;叙事、选项与 `timeline` 仍不得替玩家点破
  它在第三夜(沿用 (5) 末句)。
- **不动 schema、不动引擎**:只是回合提示词多注入一行由 `state.turn` 算出的文字(同窥天当前档注入、同 ADR-028《动物人生》
  按回合注入时钟的先例)。按回合文案挂在 per-combo 文案槽上,缺省为空,既有两组合与单体的提示词逐字节不变。
- 注入文字只用揭示阶梯第一级词汇。

### 5. 「留养」落点与开场

- 本次大典的拓印里,除「验讫」外还有一枚小「**留养**」;第二日对出:是那位不愿飞升的同门(在观星台值夜)。
- 三十年前那页只留护法名单和被涂掉的名字(混获,**只作叙事**,不跨回合追踪,守 ADR-011)。
- **开场改为「大典次日」**(种子池)。

### 6. 地点四处(调查动作 ↔ 窥天上涨来源)

飞升台 — 拓印;记档室 — 对账;丹房 — 验丹;观星台 — 观星位。

### 7. 结局池(4 条,落在派生槽「3-4」之内)

| id | title | outcome | condition(要点) |
|---|---|---|---|
| `body_fails` | 身殒封台 | failure | 气血归零(只绑气血) |
| `clarity_lost` | 叩台 | failure | 清明归零(只绑清明);「走上台心叩拜」写进 **description**,不进 condition |
| `net_passed` | 网过 | success | 角色存活,起网已发生,你未被带走,起网当场未选择让运,清明 ≥ 30(**去掉窥天门槛**,免得收不了尾) |
| (实现刀定 snake_case id) | 独过此夜 | neutral | 起网已发生,你因起网当场让运而未被带走;只确认活过这一夜,**不写同门去留** |

- **不设**「你与同门都活下来」的结局。
- **原 `stamped`「验讫」删除,本裁定取代底稿**(底稿原样存档,不回改)。「被网带走」并入 `clarity_lost`:
  **起网当场你没有避开也没有让运 → `stateUpdate` 把清明落到 0** → 引擎触底(`anyAttributeBottomedOut`)→ 模型未给结局时
  `pickFailureEnding([san])` 按中文名「清明」命中 `clarity_lost`。**这条写进回合 `extraDirectives`**。
- 结局极性 gate 自然生效:清明 ≤ 10 时模型若提议 `net_passed`,引擎拒绝并改判 `clarity_lost`。

**修订(Felix 2026-10-16):结局池去「网」。** 上表保留作记录,以本段为准。

起因:回合提示词每回合都带完整 endings(`id` / `title` / `condition` / `description`)。上表里的「网过」「`net_passed`」
「起网已发生」,以及描述里的「网没有破」,会在窥天「未觉」档时每回合把第二级词「网」喂给模型,绕过揭示阶梯的按档放行
(与 Slice B 补「hiddenLogic 只用第一级词汇」同一个漏洞形状)。

- **终局事件改称「封台终仪」**(第一级称呼),本局唯一的终局事件只用这个名字。凡判定用事件本身:「封台终仪已发生」。
  「第三夜子时」只留在故事节奏里(时间结构、叙事),**不进任何 condition**。
- `net_passed` → **`rite_survived`**,标题「**守台人还**」(success)。condition:角色存活,封台终仪已发生,你未被带走,
  封台终仪当场未选择让运,清明 ≥ 30。
- `alone_through_night`「独过此夜」(neutral)的 condition:封台终仪已发生,你因封台终仪当场让运而未被带走。
- `body_fails`、`clarity_lost` 的 id、标题、条件不变;`clarity_lost` 的「被带走」说明改称「封台终仪当场既没有避开也没有让运」。
- **结局池的 `id`、`title`、`condition`、`description` 四个字段每回合都回传模型,一律只准第一级词汇。**
- 上文凡写 `net_passed` / 「网过」处(本表、上面的 gate 句、已知代价 2、交叉引用)一律按本段读作
  `rite_survived`「守台人还」;原文不改写。

**修订(Felix 2026-10-17,冒烟驱动):终仪落点补全。** 上两段保留作记录,终仪当场的裁决以本段为准。

起因:第三轮冒烟(R3)时段推进已通过,但第 24 回合玩家选「避开」时清明为 28,不满足「守台人还」的清明 ≥ 30,
又不属于另外两路,模型给不出结局,拖到第 25 回合。「当面让运但对方不应」同样没有落点。终仪当场的每一种选择都要有确定的结局。

- **终仪当场的选择与结局一一对应**:

  | 终仪当场 | 当回合 | 结局 |
  |---|---|---|
  | 避开、未让运,清明 ≥ 21(不在将失档) | — | `rite_survived`「守台人还」(success) |
  | 避开,但清明 ≤ 20(在将失档) | `stateUpdate` 把清明落到 0 | `clarity_lost`「叩台」(将失档的人觉得被挑中是福分,自己走上台) |
  | 当面让运且对方应下 | — | `alone_through_night`「独过此夜」(neutral) |
  | 当面让运但对方不应 | 当回合反噬,等同未避开,清明落到 0 | `clarity_lost`「叩台」 |
  | 既未避开也未让运 | 清明落到 0 | `clarity_lost`「叩台」(不变) |

- **`rite_survived` 的 condition 改为**:角色存活,封台终仪已发生,你未被带走,封台终仪当场未选择让运,清明 ≥ 21。
  门槛从 30 降到 21 的理由:R3 有意护清明、只踩一次陷阱,终仪时仍只剩 28;21 与清明「将失」档上界(20)对齐,
  且高于引擎结局极性 gate 的 10(`ENDING_GATE_THRESHOLD`)。
- 落点表写进回合 `extraDirectives` (7),只用第一级词汇;第 24 回合的注入句补「每一种选择都对应 (7) 中的一个结局」。
  world-gen 文案槽与融合内核【结局池】段同步改为 ≥ 21,`clarity_lost` 补写叩台的三种落法。
- 不动 schema、不动引擎:三条「叩台」落法都借道既有的触底路径(清明落 0 → `pickFailureEnding` 按中文名命中)。

### 8. per-combo 登记清单(五处 + 两份 lockstep 资产)

`FUSION_COMBOS` + 两条 `AxisSkin` / `FUSION_WORLD_COPY` / `FUSION_META_PROMPTS` / `FUSION_SEED_POOLS` /
`FUSION_TURN_COPY` + `FUSION_IMAGERY`;`prompts/world-gen.md`、`prompts/event-loop.md` 融合段同步(lockstep 测试守)。
**骨架、`endingCountRange`、`Engine`、`validateWorld` 一行不动。**

### 关键理由

1. **复用成本模型第三次验证**:四项新需求全部落进现有槽 —— 这正是 ADR-014 参数化要证明的事。
2. **不为一个组合松动通用约束**:单轴绑定是 ADR-013 治「结局错配」的那一刀,勘察 f 证明不绑轴的 failure 正是兜底误选的形状。
3. **「被带走」借道引擎既有路径**(触底 + 中文名兜底),不新造判定,守 ADR-008 引擎语义无知。
4. **揭示阶梯借 bands 的「只注入当前档」**:已有机制恰好是按档放行的载体,不新造字段。

## 已知代价

1. **揭示阶梯只靠提示词**:窥天值由模型提议、引擎只落账;词汇上限没有任何程序层拦截(流末扫描按 ADR-004 只作遥测,且只认
   泄露 token,不认这些词)。缓解方式:冒烟逐局人工核 world-gen 可见字段与前几回合叙事。⚠️「格」「收」等是高频字,机械 grep
   会大量误报,只能人工读。
2. **endings 池随 state 下发**(勘察 e):`网过` 的 title、「起网」「让运」等条件文字在 payload 里,devtools 可见,揭示阶梯管不到。
   界面只显示已达成那条。接受:与既有世界同一口径(`outcome` / `condition` 本就不剥)。
   **修订注(Felix 2026-10-16)**:「网过」已改名 `rite_survived`「守台人还」,条件里的「起网」改称「封台终仪」,
   见决策 7 修订段;本条原文保留不改写。
3. **时段与终仪靠提示词落实**(修订 Felix 2026-10-17,见决策 4 修订段):天数漂移的主因(时段由叙事推进、停在同一夜)
   已由服务端按回合注入【本回合时段】治掉。残余:模型可能不照注入的时段写叙事 / `timeline`;第 24 回合若不给结局,
   只能靠第 25 回合起每回合注入的「本回合必须给出结局」兜。仍不加 state 字段(那会动 schema / 引擎,另需 ADR)。
4. **「被网带走 → 清明落 0」是提示词约定**:模型若在起网回合不落清明、或另提议别的结局,不会走 `clarity_lost`。缓解方式:
   `extraDirectives` 写成硬约束;冒烟专项核。
5. **兜底残余误选**(勘察 f):模型若把轴名写错(如写成「理智」),兜底会落到首个 failure;现在池里 failure 都绑轴,误选也只会落在
   另一条失败结局上。接受。
6. **neutral 不受 gate 约束**:濒死时模型提议「独过此夜」会被接受;同回合气血触底时也不会被改判成 `body_fails`。接受(neutral
   本就是「活过这一夜」的收束,结局判定的语义产出方是模型,ADR-010)。
7. **「被盖章带走」失去独立结局名**,题眼由叩台的描述与起网回合叙事承载(方案 C 的代价)。
8. **重变体 C 挂账**(勘察 g):种子池无 per-seed worldview 补丁位。解冻条件:有第二个组合也需要「一条种子改世界观」时,再开 ADR 定承载位。
9. **成功门槛与「十几回合」**:骨架要求成功结局「十几回合内可以达成」,三日结构可能更长。只作冒烟观察项。
10. **终仪落点由提示词约束**(决策 7「终仪落点补全」修订段):模型若不按表落清明 0 或不给结局,只靠第 25 回合起的注入兜底。

## 重新审视的触发条件

- 真 key 冒烟中,world-gen 可见字段或「未觉」档叙事**稳定**出现第二 / 三级词汇 → 揭示阶梯从提示词升级为程序层(另立 ADR,涉及流末扫描职责)。
- 三日推进稳定漂移(跳日 / 停在第一日 / 起网不在第三夜)→ 考虑 state 层时间字段(动 schema,另立 ADR)。
- 起网回合「未避开未让运」却未落清明 0 的比例偏高 → 重审「借道触底」这条路径。
- 第四组合再次需要「不绑轴的事件型失败结局」→ 那时它才是通用需求,回头重审骨架单轴绑定的措辞(ADR-014 触发条件届时再评估)。
- 出现第二个需要 per-seed worldview 补丁的变体 → 解冻重变体 C。

## 实施步骤

(Felix 审过本 ADR 后才开工;每步 atomic commit,ADR 在前。)

1. **Slice A · registry**:`FUSION_COMBOS` 登记 `cultivation×cthulhu` + 清明 / 窥天 `AxisSkin`;单测钉派生集
   (致命 {hp,san}、累积 {realm,knowledge}、非致命 {mana})、窥天 `perilAtHigh=true`、两轴 `bandRanges()` 区间与 severity。
2. **Slice B · world-gen**:`FusionWorldCopy` 一条 + 融合内核 + 种子池(「大典次日」;重变体 C 不收)+ `prompts/world-gen.md` lockstep。
3. **Slice C · event-loop**:`FusionTurnCopy` 一条(含 `extraDirectives`:三日 + 起网裁决 + 被带走落清明 0 + 让运 + 夜里不出领药 +
   timeline 前缀(只准第一级词汇)+ 揭示阶梯总规则)+ `FUSION_IMAGERY` + `prompts/event-loop.md` lockstep。
4. **零回归**:
   ```bash
   cd server && mvn test   # 全绿;EngineGoldenTest 零回归
   ```
   既有两组合与单体的 world-gen / 回合提示词按 ADR-014 Slice A 做法 dump 基线后逐字节比对为空。
5. **Slice D · 真 key 冒烟(Felix 亲手,花钱)**:多发 init + 至少一局走到起网。核:真假配比各 ≥3;五轴齐且显名清明 / 窥天;
   结局 4 条、极性对、failure 单轴绑定;world-gen 可见字段只用第一级词汇(人工读);天数前缀逐回合推进、起网在第三夜且当回合裁决;
   未避开未让运 → 清明 0 → 叩台;让运 → 独过此夜;恢复当回合涨境界;夜里无领药选项;无泄露 / 无精确数字。
6. **Slice E · 前端**(勘察 h 清单),局内体感归 Felix。
   **Slice E 已实现(封面暂借修仙图,2026-10-18,`5c9f322`)**:勘察 h 清单 1–3 落地(`FUSIONS` / `FUSION_CARDS` / `.cardFusionYanqi`,
   色值取自修仙卡与克苏鲁卡、复用三段既有渗漏卡关键帧);清单 4 专属封面未就位,`sceneUrl` 暂借 `/scenes/cultivation.webp`,
   图到后另换;清单 5 前端零改。局内皮肤取 host(修仙),选择屏与局内视觉体感待 Felix 判;未部署。
   **Slice E 已部署(`faeeb8c`,Felix 2026-10-19)**:同批上线的还有融合卡标语颜色修复(三张融合卡);线上选择屏可拖出《验讫》卡,
   开局与局内(修仙皮肤、五轴、开场)真机验通。**挂账**:① 专属封面图 —— 仍暂借修仙封面,待 Felix 提供后改 `FUSIONS` 里一行
   `sceneUrl`;② 三张融合卡标语对比度最亮处约 4.6:1,贴线过 —— 若调亮融合卡氛围层(云气 / 接缝 / 光点),须按实际合成底色重测。
   **挂账 ① 专属封面图 已完成(Felix 2026-10-19)**(前句原文保留):Felix 定稿原图 `web/public/scenes/fusion-yanqi.webp`
   (2048×768,sha256 `664d5c79d2a0edad…`,原样入库未重压)就位,`FUSIONS['cultivation×cthulhu'].sceneUrl` 改指该图、删「暂借 host 封面」注释,
   对应单测改断言专属封面(`e6d3c03`);卡片样式、文案、其他组合未动;未部署。挂账 ② 标语对比度照旧。
7. 验通后回写 CONTEXT §三.16(循「待验通再立字」先例),`schemaVersion` 保 "0.4"。

## 实际效果(事后补充)

- **Slice D 首轮冒烟(Felix 2026-10-17)发现 4 项,已修,待复验**:开局 hint 复述隐藏判定(泄露守则真假、露出机制词)/ 清明被当成每回合被动衰减(28 回合 72→0)/ 第二夜一个时段持续 15 回合、始终没到第三日 / 模型自开「崖下」场景、把真相写成实物。
- **Slice D 第二轮冒烟(R2,79 回合)**:hint 泄露、清明螺旋、场景越界三项复验通过;时段节奏未通过(第二夜持续 31 回合,终仪拖 5 回合未当回合裁决,让运被提前谈好)→ 决策 4 修订段(时段按回合数推进)。
- **Slice D 第三轮冒烟(R3,25 回合)**:时段节奏、第 23 回合抉择、让运时机通过;暴露终仪落点缺口(第 24 回合避开时清明 28,不满足「守台人还」的清明 ≥ 30 又不属另外两路,拖到第 25 回合;「让运但对方不应」也无落点)→ 决策 7「终仪落点补全」修订段。

### Slice D 冒烟总账(Felix 2026-10-17,本地真 key)

5 轮、10 次开局、6 整局。saveId 只记前 8 位。

- **A 组 5 局**:只开局不玩,结构项(冒烟手册 4.1)全过。首轮发现 hint 复述隐藏判定。
- **R1(`c1298eea`,28 回合)/ R2(`75970b83`,79 回合)/ R3(`3d6cdcff`,25 回合)**:发现并修复五项 ——
  hint 泄露 / 清明被动衰减 / 场景越界 / 时段停滞 / 终仪落点缺口。修订段分别在决策 1(清明 / 窥天行为提示)、
  决策 4(时段按回合数推进)、决策 7(终仪落点补全)下;hint 与场景两项的修复在回合 `extraDirectives` (9)(10)
  与融合内核【守则】段。
- **R4 / R4b 终仪三路各走通一次**:
  - R4(`70d5befa`)当面让运 → 第 24 回合「独过此夜」;
  - R4(`4d991589`)走上台阶 → 第 24 回合清明落 0、「叩台」;
  - R4b(`fd18f04e`)避开、清明 74 → 第 24 回合「守台人还」。
- **时段**:按回合注入后,73 回合中 72 回合的叙事与 timeline 与注入一致。唯一一次偏差是 `70d5befa` T7 跳日,
  下一回合被注入拉回。
- **残余小瑕疵,不修**:
  - 第 23 回合的「避开」选项有时措辞不直白(`4d991589`);
  - 偶尔提前一回合就写「子时前一刻」;
  - 结局描述由 world-gen 预写,可能与实际裁决时的站位不符(`fd18f04e`「你还在台上站着」)。

### 「加一组融合」的实际改动清单(第三次复用的定量答卷)

Slice A–C 与三轮冒烟修复(`9a7b036` … `3f063b1`)动过的生产文件只有三个 `.java` 与两份提示词资产:

| 文件 | 动了什么 |
|---|---|
| `archetype/ArchetypeRegistry.java` | `FUSION_COMBOS` 登记一条 + 清明 / 窥天两条 `AxisSkin`(含行为提示与 bands) |
| `worldgen/WorldGenPromptBuilder.java` | `FUSION_WORLD_COPY` / `FUSION_META_PROMPTS`(融合内核)/ `FUSION_SEED_POOLS` 各加一条 |
| `eventloop/TurnPromptBuilder.java` | `FUSION_TURN_COPY` / `FUSION_IMAGERY` 各加一条;新增通用可空槽 `FusionTurnCopy.turnClock`(按回合文案,缺省 null)与本组合的 `yanqiTurnClock` |
| `prompts/world-gen.md`、`prompts/event-loop.md` | lockstep 同步 |

- **零改**:融合骨架(`FUSION_SKELETON` / `FUSION_TURN_DIRECTIVE` 正文)、`Engine`、`validateWorld`、schema(`schemaVersion` 仍 "0.4")、前端。
- **唯一的通用改动**是 `turnClock` 这个可空槽:既有两组合取 null,提示词逐字节不变(以 `28cf20d` 为基线比对为空)。
  另有几个 per-combo 表由 `private` 改为包内可见,只为测试逐槽核词汇。
- 测试:新增 `CultivationCthulhuFusionTest` / `CultivationCthulhuWorldGenTest` / `CultivationCthulhuTurnTest` 三个类,
  既有 lockstep 与计数断言五个类小改。

## 跟其他文档的交叉引用

- **素材**:[`docs/fusion-yanqi-drafts/draft-1.md`](../fusion-yanqi-drafts/draft-1.md)、[`draft-2.md`](../fusion-yanqi-drafts/draft-2.md)(以第二份为底;
  以下各处已被本 ADR 取代:5 条结局池、`stamped`「验讫」、`slept_through`、`net_passed` 的窥天门槛;§3 实录 3(拓影,降级为纯叙事);窥天区间 0–29 / 30–59 / 60–100 →
  0–30 / 31–60 / 61–100;「饵文」改称「旧说」;§7 意象表把「网」列在第三级,与 §2 阶梯(网在见格)不一致 → 以阶梯为准;
  §2 内核「三日前……飞升」与开场「大典次日」不一致 → 以「大典次日」为准)。
- **起源**:[打磨与愿景 backlog §8](../phase2-polish-and-vision-backlog.md) 第 ⑤ 对「飞升是捕捞」。
- **前序 ADR**:[ADR-012](ADR-012-hybrid-axis-merge-strategy.md)(轴合并 + AxisSkin)、[ADR-013](ADR-013-hybrid-fusion-protocol.md)
  (融合协议、单轴绑定)、[ADR-014](ADR-014-fusion-skeleton-parameterization-and-second-combo.md)(骨架参数化;本 ADR 判定其触发条件不成立)、
  [ADR-010](ADR-010-ending-outcome-polarity-gate.md)(结局极性 gate)、[ADR-011](ADR-011-action-hint-narrative-metadata.md)、
  [ADR-018](ADR-018-base-world-visual-migration-and-severity-contract.md)(perilAtHigh / severity)、[ADR-019](ADR-019-fusion-visual-entry-and-composability.md)(组合表 / 渗漏卡)、
  [ADR-004](ADR-004-content-safety.md)(流末扫描只作遥测)。
- **约定**:CONTEXT §三.16 (5)(7)(8)。
- **相关源文件**:`server/.../archetype/ArchetypeRegistry.java`(`FUSION_COMBOS` / `applySkin`)、`server/.../archetype/AttributeAxis.java`
  (`validateBands` / `bandRanges`)、`server/.../worldgen/WorldGenPromptBuilder.java`(`FUSION_SKELETON` / `endingCountRange`)、
  `server/.../eventloop/TurnPromptBuilder.java`(`FUSION_TURN_DIRECTIVE`)、`server/.../engine/Engine.java`(`pickFailureEnding`)、
  `web/src/features/game/theme/registry.ts`、`web/src/features/game/ArchetypeSelect.tsx`。
