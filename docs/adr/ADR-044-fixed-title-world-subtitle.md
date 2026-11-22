# ADR-044 · 固定世界名的三个世界加「每局小标题」

- **日期**:2026-11-22
- **状态**:已定(待实施)
  ⚠️ 补充(2026-11-22):九个待审项与六条文案由 Felix 交校勘代定,逐条回填在文末两节各项之后(原有选项与理由保留)。
  原状态行:提议(草案;只勘察、只写 ADR,不动代码、不动提示词、不重录 golden。方向与结构约束由 Felix 2026-11-22 定;
  勘察结论、字段落点与落地切分由本 ADR 补齐,需要 Felix 拍板的列在文末「待 Felix 审定清单」与「待 Felix 定的文案」)
  ⚠️ 补充(2026-11-22,部署后;上文状态、补充与原状态行一字不动):**已部署**。刀 1–2 已 ff 合入 `main`(至 `9510d72`),随 `9510d72` 上线
  (Felix 2026-11-22 亲手部署;校勘已核 `/actuator/info`:`build.commit = 9510d72`、`build.time = 2026-11-22T01:57:24.421Z`;
  fly release **v65**,创建于 2026-11-22T01:57:40Z(UTC))。见「实施记录」;局内顶图(待审 2)未做,「实际效果」待冒烟回填。
- **决策者**:Felix

## 背景

《派单》(`cyberpunk`)、《寻常》(`life_sim`)、《七日开张》(`time_travel`)三个单体世界登记了 `fixedWorldTitle`
(ADR-036 Slice A2 / ADR-037 决策 3 / ADR-038 Slice A),world-gen 之后由服务端把 `world.title` 覆写成固定名。
结果是:**存档页与大厅「继续」行里,这三个世界的档全叫同一个名字**。下面那行 `describeSave` 是「题材名 · 第 N 回合 · 本设备上次游玩时间」,
两条《派单》档只差时间;局内顶图也一律是同一个标题。

**Felix 已定(2026-11-22)**,本 ADR 不再讨论,只照写:

1. **方向(甲)**:固定名不动,每局再加一个**小标题**。小标题在 world-gen 时由模型按这一局的实际内容起名 ——
   同没有固定名的世界自己起 `title` 的做法(「雨夜便利店」「山雾民宿」「屋里的灯」这类:地点 / 处境 + 具体的东西)。**不用种子拼。**
2. **挂点**(方向已定,具体措辞由 Felix 定,见文末「待 Felix 定的文案」):
   - 《寻常》:给这一辈子起名(同《动物人生》),挂在这一生开头具体的人 / 地方 / 年份上;
   - 《派单》:挂在这一局的地点或这个人上;
   - 《七日开张》:挂在摊子本身(卖什么 / 在哪条街)。**不得是店名** —— 结局回合才写出招牌,小标题不能提前剧透。
3. **结构约束**:
   - 是一个**名字**,不是氛围词(反例:「屋里的灯」那局的 tone「孤寂、钝痛、微光」,`bakeoff/FINDINGS.md:1052`);
     长度与其他世界名相当,约 4–8 字,具体上限见待审 4;
   - 定义**只进这三个世界各自的 `WORLD_GEN_DIRECTIVES` 槽**,不进公共骨架 → 其余世界的 world-gen / 回合 prompt 逐字节不变;
   - **字段可选**:旧档没有 → 不显示;模型漏写 → 不算 world-gen 失败(放过还是进修复,见待审 1);
   - **现有约束全部覆盖新字段**:【泄露硬约束】、【现实边界 · 硬约束】、《派单》禁用词与 `systemLineErrors`、《七日开张》不出现真实朝代;
   - **显示**:存档页 / 大厅「继续」行 / 撤销条写成「固定名 · 小标题」;局内顶图怎么摆(已有 title + tone 两行)本 ADR 只列选项,
     样张阶段由 Felix 看了再定。小标题**不用**《寻常》《七日开张》的子集字体(字符集写死,见 `xun/subset.ts`、`qiri/subset.ts`)。

约束条件:

1. **骨架单点维护**(ADR-008;ADR-020 §10「读几次」判据;ADR-037 决策 1):多个世界共用的指令回骨架。本 ADR 的指令只对三个世界成立,
   且一局只读一次 → 进各自 world-gen 槽,不进骨架。
2. **world-gen 首次失败是头号失败模式**(ADR-007;CONTEXT §三.11):修复仍败 = 整局 ERROR。一个展示用字段不应扩大这个失败面。
3. **引擎 / 通用校验器对内容无知**(ADR-008;ADR-009 `isTrue` 可选的先例):世界专属检查只放 `worldSpecificErrors`(CONTEXT §三.18(c))。
4. **存档指针第一原则**(ADR-034 / ADR-042):指针是找回一局的唯一凭证;解析失败只跳过不删除。新字段不得让任何一条指针「看不见」。
5. **前端未登记世界 DOM 对拍**(ADR-018 §4.5.1):没有小标题的局,四个显示点的 DOM 与改动前逐字节一致。

### 勘察结论(只读 `main` `2c7894d`,2026-11-22)

#### 一、简报所列行号复核

| 简报所写 | 复核结果 | 依据 |
|---|---|---|
| 覆写 `FixedWorldTitle.java:35-46` | ✅ 方法体 `:32-47`(`apply` 签名 `:32`,`w.put("title", fixed)` `:45`) | `server/.../worldgen/FixedWorldTitle.java` |
| 调用点 `WorldGenService.java:138 / :150` | ✅ 首过 `:138`、修复后 `:150`,均在 `tryParse`(含 `worldSpecificErrors`)**通过之后** | `WorldGenService.java:136-151` |
| 软层【世界名】`:334`(派单)、`:397`(七日开张);《寻常》槽没有 | ✅ 两行号对;《寻常》槽(`:506` 起,标题「结局池与早逝」)确实没有【世界名】行,ADR-037 已知代价 5 原文即此 | `WorldGenPromptBuilder.java` |
| 登记 `:936`(派单)、`:1008`(七日开张)、life_sim 条末 | ✅ 《寻常》在 `:849` | `archetype/ArchetypeRegistry.java` |
| tone 定义两份骨架 `:75 / :163` | ✅ | `WorldGenPromptBuilder.java:75`、`:163` |
| tone 只显示在 `SceneBanner.tsx:66` | ✅ | `web/src/features/game/SceneBanner.tsx:66` |
| title 显示点 `SceneBanner.tsx:65`、`ArchetypeSelect.tsx:393`、`SavesScreen.tsx:154` 与 `:159` aria-label、`saveText.ts:31` | ⚠️ 其余对;**aria-label 在 `SavesScreen.tsx:161`**,不是 `:159` | `web/src/features/game/saves/SavesScreen.tsx:161` |
| 存档指针 `savePointers.ts:26`、`gameStore.ts:238` `recordFromWorld` | ✅ `recordFromWorld` 定义 `:235`、写 title `:238`;开局 `:289`、续局 / 重同步 `:360 / :397` 等处调用 | `web/src/state/gameStore.ts` |
| `savePointers` 读写与校验 `:87 / :95 / :107 / :246` | ✅ 四处依次为:`parseSummary` 类型检查、返回值、`serializeSummary`、`sameSummary` | `web/src/state/savePointers.ts` |
| 下发契约 `api/contract.ts:194` | ⚠️ **`:194` 是 `EndingPayload.title`(结局标题),不是世界名**。世界的类型在 `types/schema.ts:28-33`(`World {title, background, dangerLevel, tone}`),下发投影 `ClientWorld` 在 `api/contract.ts:41-50`(`world: World`) | 同左 |
| `GameSchemas.java:50` 只要求 title 非空 | ✅ `:50`;tone `:53`。校验器**不拒未知键**(全文无 additionalProperties 类检查) | `engine/GameSchemas.java:48-54` |
| `systemLineErrors` 在 `WorldGenService.java:~304` | ✅ 方法 `:303`,字段清单 `:304`(`title / background / tone`) | `WorldGenService.java:303-327` |

#### 二、三个世界每局会变的东西(勘察 1,只写事实)

| | 《派单》 | 《寻常》 | 《七日开张》 |
|---|---|---|---|
| **种子池** | 6 条(`:252-258`),新号 / 老号各 3;只写天气与地形物件:城中村湿墙、握手楼断电、塔楼货梯 + 消防通道、换电柜、巷口路边摊、黑诊所卷帘门 | 4 条(`:262-270`),每条 = **年份 + 地方 + 家境 + 排行 + 一个同龄人**:1985 北方工业城 / 1992 南方县城 / 1978 西北农场 / 2001 沿海城中村 | **没有种子池**:`SEED_POOLS` 无 `time_travel` 键,`pickSeed`(`:1038`)走缺省「(自由发挥本模式的典型开局场景)」;槽里写明「这个摊子卖什么、隔壁摊是谁由你定」(`:399`) |
| **槽要求 background / 开场写进的具体项** | 【开局】`:337`:新号或老号、天气、腿和车的电量、腿有没有被降速、还差多少房租和月供、黑诊所义体医生、第一单送什么、订单备注;另须「至少一样今天还不存在的物件」 | 槽里不规定 background 内容(只管结局池与早逝);种子给的四样就是开头的具体项 | 【开局】`:399`:失踪摊主、钱匣小抄、卖什么、隔壁摊是谁;【一局的形状】:街名一律虚构、朝代只叫「本朝」 |
| **`character`** | schema 只有 `attributes / traits / inventory`,**没有名字字段**(三个世界都是) | 同左 | 同左 |
| **本地样本**(`server/data/` 两份本地存档) | **无本地样本**(本地 `server/data/` 与 `traces/` 都没有《派单》存档) | `d6d3c966…`:background 写到「1978年,西北」「农场」「粮本」「你是老三」「草滩」「同乡的娃」;traits「沉默 / 耐劳 / 记性好」,inventory「半块玉米面馍 / 母亲缝的布鞋 / 一张折了三道的粮本副页」 | `81acb3bd…`:background 写到「柳筐街」「卖热汤粉食」「一口旧灶、两只粗瓷碗、半袋陈米、一摞缺角的竹牌」「隔壁卖炭的老汉」;traits「手脚勤快 / 嘴比脑子快 / 不合时宜的常识」 |

**读法(事实层面,不是措辞建议)**:
- 《寻常》挂点(人 / 地方 / 年份)每局都有:种子本身就给了年份与地方,样本 background 原样写出;「人」没有名字字段,只有「老三」「同乡的娃」这类称呼。
- 《七日开张》挂点(卖什么 / 哪条街)每局都有:槽要求模型自定卖什么、街名虚构,样本里两样都在(「柳筐街」「热汤粉食」)。
  ⚠️ 槽里一条真规矩是「**招牌先写货名,再写摊名**」(`:408`),「街名 + 货名」的小标题在字面上可能读起来像一块招牌 —— 这是「不得是店名」要防的形状,写进待定文案。
- 《派单》挂点:「地点」每局由种子给地形 + 模型起虚构的楼 / 巷 / 小区名(槽 `:361` 起要求平台名、小区名、街道名一律虚构、不谐音拆字);
  「这个人」**没有名字可挂**,只能挂新号 / 老号、腿况、第一单这类处境。无本地样本,模型实际写出哪些专名未核。

#### 三、字段放哪、怎么流动(勘察 2)

- **落点 `world.world.subtitle`**(与 `title / tone` 同一对象)。依据:服务端 world 是一整块 `ObjectNode`,不经 POJO 绑定 ——
  `Engine.snapshot()`(`engine/Engine.java:381-400`)`world.deepCopy()` 整块拷贝;`toClientState()`(`:374`)只按 `LEAK_TOKENS` 递归剥键;
  `Engine.restore`(`:155` 起)只校验 world 是对象、不看子键。**因此加一个键不需要改 Jackson 读写、Engine、ClientProjection、落盘或 PG 导入导出**:
  它自动进视图 1(落盘)、视图 2(`contextJson`,每回合喂模型)、视图 3(init / state 响应的 `world`)。
  ADR-032 的 PG 表没有 title 列(`db/migration` 只有 `V1__narrative_history` / `V2__turn_request`),ADR-033 统计工具不读 title。
- **`GameSchemas.validateWorld` 不需要改**:它不拒未知键,`subtitle` 有没有都过(勘察一)。
- **前端类型**:`types/schema.ts` 的 `World` 加 `subtitle?: string`;`api/h5GameApi.ts` 只检查 `data.world` 在不在(`:75 / :98`),不需改。
- **存档指针要存它**:存档页与「继续」行只读指针、不拉存档(ADR-034),不存就无从显示。`SaveSummary` 加可选 `subtitle`,
  `parseSummary` / `serializeSummary` / `recordFromWorld` 各加一处;`sameSummary` 是否比它见实施。**解析不合形状时怎么办**见待审 5。
- **`schemaVersion` 结论:不动(仍 `"0.4"`),请 Felix 确认(待审 6)**。依据与反面:
  - 动它的代价:两份骨架都写死「schemaVersion:必须为 "0.4"」(`:73`、`:161`),升版本要改骨架 → **所有世界的 world-gen prompt 都变**,
    直接违反已定的「其余世界逐字节不变」;还要改 `validateWorld`(`:39-44`)与 `Engine.restore`(`:150`)的接受集合。
  - 不动的依据:0.3 / 0.4 两次升版都伴随**引擎或校验行为的变化**(ADR-009 `isTrue` 必需改可选、ADR-010 引擎读 `outcome`);
    `subtitle` 引擎不读、校验器不读,只是展示文字。
  - ⚠️ 反面如实写:`subtitle` **会落盘**,这与 `timeLabel / tag / markedTurns`(ADR-039 / 040,下发时现算、不落盘)不同;
    落盘的 world 层新字段不升版,本项目**没有完全相同的先例**。CONTEXT §二 schema 用补充块记一个可选字段(原文不动),版本号升 CONTEXT 文档版本。

#### 四、回合 prompt(勘察 3)

- 回合 prompt 把 `engine.contextJson()` 整块拼进去(`eventloop/TurnPromptBuilder.java:1000`),所以**新局每回合都会带上 `subtitle`**,
  同 `title`、`tone` 今天每回合都带。旧局(没有这个键)回合 prompt 逐字节不变。
- 回合 golden(`golden/turn-prompts/`、`golden/cyberpunk/turn_T*.txt`、`golden/adr039-client-projection/`)都由测试夹具世界生成,夹具没有 `subtitle` → **不受影响**。
- ADR-031 轨迹回放:新局轨迹的 `pre` 里带着 `subtitle`,回放用同一份 `pre` 重建 prompt,哈希复核仍一致;旧夹具不受影响。
- 有没有害:模型可能在叙事里引用小标题(与引用 title 同性质);《七日开张》若小标题写到了「卖什么」,与 background 同源,不新增信息;
  唯一会新增信息的情形是小标题本身越界(写了店名 / 禁用词)—— 那是 world-gen 侧的问题,回合侧剔掉它只能止住「每回合复读」,治不了 world-gen 已写出的那一次。

#### 五、校验与检查(勘察 4)

| 约束 | 今天的程序层 | 新字段要做什么 |
|---|---|---|
| 【泄露硬约束】 | world-gen **没有程序检查**;`LeakDetector.detect` 只在回合扫叙事(`Engine.java:294`)。约束只在骨架文字里,字段清单写的是「background / tone / rules.content / openingNarrative / availableActions **等**任何玩家可见字段」(`:110-111`) | 骨架不能改 → 槽里那条小标题指令须自带「小标题也是玩家可见字段」一句(措辞归 Felix) |
| 【现实边界 · 硬约束】 | 只靠提示词(ADR-037 已知代价 8) | 骨架原文是总括性的「不写 / 不出现 / 不点名」,天然覆盖;无程序层可加 |
| 《派单》禁用词 | **没有程序检查**(ADR-036 已知代价 8;主代码里「罢单」只出现在两份提示词) | 同上,只靠槽里的禁用词段;它写的是「任何玩家可见字段」,天然覆盖 |
| 《派单》`systemLineErrors` | 有,字段清单写死 `world.title / background / tone`(`WorldGenService.java:304`) | **要加 `world.subtitle`**(一处列表),漏加则系统台词可以从小标题漏出去 |
| 《七日开张》不出现真实朝代 | 只靠槽里【题材边界】 | 同上,天然覆盖;无程序层 |
| 《七日开张》不得是店名 | 无(招牌在结局回合叙事里写出,不在 world JSON 里,程序无从比对) | 只靠槽里指令 |
| 修复提示 `REPAIR_HEAD`(`:206`) | 独立短 prompt,只列结构约束 + 校验错误 + 上次产出;不含骨架与槽 | 若漏写不进修复(待审 1 推荐),则不必改;若进修复,校验错误那一行自带要求即可,`REPAIR_HEAD` 仍不改 |

#### 六、测试面(勘察 5)

**会变的**(实施时有意改动,逐条处理):

| 测试 / 基线 | 为什么会变 | 处理选项(待审 7) |
|---|---|---|
| `PaidanWorldGenTest` ①(`:58-73`):`life_sim` 在「既有世界除种子行外逐字节不变」的 9 张快照里(`:44`),比对前已经过 ADR-037 一层锚定剔除 | 《寻常》槽加一行 → `golden/world-gen-prompts/life_sim.txt` 对不上 | (a) 在 `CommonPromptAdditions.stripWorldGen` 加《寻常》专属的第二层锚定剔除;(b) 把 `life_sim` 移出这组、另立《寻常》自己的快照(同 `PaidanPromptSnapshotTest` 先例);(c) 重录 `life_sim.txt` |
| `PaidanPromptSnapshotTest`(`:41-49`):`golden/cyberpunk/world-gen.txt` **不经剔除**直接比 | 《派单》槽加一行 | 重录该快照(其注释:「只在有意改动《派单》提示词时」更新),或加锚定剔除 |
| `PaidanWorldGenTest` ④ / `QiriWorldGenTest:122`:`prompts/world-gen.md` 逐字收录两份槽 | 槽改了,md 必须同步 | 同刀改 md(lockstep 本来就要求) |
| `PaidanWorldGenTest` ③:禁用词扫描(剔掉【世界名】与【禁用词】两处后扫全文) | 新指令行若含禁用词会红 | 措辞避开即可;测试不改 |
| `PaidanSystemLineRepairTest` | `systemLineErrors` 加 `world.subtitle` | 加一条用例 |
| `savePointers.test.ts`(`:165`「摘要七个字段全等」等)、`SaveList.test.tsx`、`SavesScreen*.test.tsx`、`SceneBanner.test.tsx`、`gameStore.multisave.test.ts` | 新增可选字段与显示 | 加用例;既有「没有小标题」的用例应原样通过 |

**应当逐字节不变的**(护城河):其余 5 个单体(规则怪谈 / 末日 / 克苏鲁 / 修仙 / 动物人生)与 3 个融合的 world-gen 快照(`PaidanWorldGenTest` ①);
全部回合 golden(`turn-prompts/`、`cyberpunk/turn_T*`、`adr028-legacy-turn-prompts/`)、`adr039-client-projection/`、`event-loop-golden.json`、
`validator-parity.json`(校验器不改);`CommonBoundaryPromptLockstepTest`、`FusionMetaPromptLockstepTest`(骨架不改)。

**不受影响的**:`FixedWorldTitleTest`(只钉 `title` 覆写与登记,`:113-127` 三个世界的登记值不变)、`PaidanRegistryTest`、`QiriRegistryTest`
(只读 registry,本 ADR 不动 registry);`xunSkin.test.tsx:421-423` / `qiriSkin.test.tsx:457-465` 钉的是子集字符集等于「可能出现的字」——
**只要小标题不走 `--title-font`,这两个测试不受影响**;反过来,若小标题被放进带 `.title` 类的元素里,它会继承子集字体,
子集里没有的字逐字回落到别的字体,这两个测试也拦不住(它们只核字符表,不核渲染)。

#### 七、旧档与迁移档(勘察 6)

| 显示点 | 有小标题 | 没有(旧档 / 漏写 / 迁移指针) |
|---|---|---|
| 局内顶图 `SceneBanner` | 见待审 2 | 与今天逐字节一致(只有 title + tone) |
| 存档页条目 `SavesScreen.tsx:154`(+ `:161` aria-label) | 「固定名 · 小标题」 | `s.title`;title 为空 → `UNTITLED_SAVE`「上一局(续上后显示世界名)」 |
| 大厅「继续」行 `ArchetypeSelect.tsx:393` | 同上 | 同上 |
| 撤销条 `saveText.ts:31` | 「已从这台设备移除《…》」里带小标题(分隔与书名号见待审 3) | 今天的「已从这台设备移除《派单》」/ 空名「这一局」 |

- **迁移来的旧指针**(`savePointers.ts:207-216`,title 为空串、archetypes 为空):首次续局成功 → `recordFromWorld` 整条重写,
  此时**存档里有没有 `subtitle` 决定指针有没有** —— 迁移指针指向的是 ADR-034 之前开的局,那时还没有小标题,所以它续上后也只显示固定名。两条路径互不干扰。
- **旧档永远不会有小标题**:续局读盘上的 `world`,不重新生成(同 ADR-037 已知代价 3)。
- **部署前开着的旧页签**:旧版前端的 `serializeSummary` 只写七个字段,旧页签每回合 `pointers.patch(...)`(`gameStore.ts:468`)会把同一条指针整条写回、丢掉 `subtitle`;
  下次新版页签 `recordFromWorld` 才补回来。只影响跨部署那一会儿,记为已知代价。

#### 八、成本(勘察 7,**推测**,未用真 key 量)

- world-gen 输出多一个键:`"subtitle":"……"` 约 4–8 个汉字 + 键名与引号,**推测 10–20 个 token**;
  这三个世界的 world-gen 输出(7 或 4 条结局、6–8 条规则、开场叙事)推测在数千 token 量级 → **增量 < 1%**。
- world-gen 输入多一条槽指令:推测 60–150 个汉字 → 约 50–120 token,每局一次。
- 回合输入:每回合 `contextJson` 多一个键,**推测 10–20 token/回合**;回合 prompt 本身数千 token → < 0.5%,
  但每回合都付(《派单》27 回合、《七日开张》22 回合、《寻常》按一生阶段的回合数)。
- 若漏写选择「进修复」(待审 1 的 B / C):一次修复 = 再出一整份 world JSON,**那一局 world-gen 输出成本约翻倍**。

## 候选方案

### 方案 A:不加字段,在 `title` 里拼「固定名 · 小标题」

**优点**:
- 不加字段,四个显示点不用改。

**缺点**:
- `FixedWorldTitle` 的语义从「覆写成固定名」变成「拼接」,`FixedWorldTitleTest` 的不变量(title 恰为固定名)全部改写;
- 回合 prompt、《派单》软层「world.title 必须恰为『派单』」与之冲突;局内顶图的子集字体会吃到小标题里的任意字(子集只有固定名的字);
- 旧档与新档的 title 形态不一,存档行没法统一降级。
**排除**。

### 方案 B:服务端用种子 / background 片段拼小标题

**优点**:
- 确定、零漏写、不增加模型输出。

**缺点**:
- Felix 已定「不用种子拼」;《七日开张》没有种子池;拼出来的是标签不是名字。
**排除**。

### 方案 C:`world.subtitle` 可选字段,三个世界槽里各一条指令,前端四个显示点有则显示(本 ADR 采纳)

**优点**:
- 只动三个世界的槽,其余世界 prompt 逐字节不变;
- 字段随 world 整块自动流过视图 1 / 2 / 3,后端不改 Engine / 校验器 / 投影;
- 可选字段天然兼容旧档、漏写、迁移指针。

**缺点**:
- 小标题质量只靠提示词(泄露、边界、禁用词、不剧透店名都没有程序检查,除《派单》系统台词);
- 每回合 prompt 多一小段(勘察八);落盘新字段不升 `schemaVersion`,无完全相同先例(勘察三)。

## 最终决策

**方案 C**。下面 1–5 是本 ADR 定下的结构;凡标「待审 N」的,以 Felix 在文末清单的裁定为准,实施前回填。

### 1. 字段

- `world.world.subtitle`:可选字符串。**只在登记了 `fixedWorldTitle` 的单体世界里有意义**(今天三个);融合局与其余单体不要求、不显示(见 2)。
- 校验器 `GameSchemas.validateWorld` 不改;`Engine`、`ClientProjection`、落盘格式不改。
- `schemaVersion` 仍 `"0.4"`(待审 6 确认);CONTEXT §二用补充块记这个可选字段,原文一字不动。

### 2. 服务端收口(一处,与 `FixedWorldTitle` 同位)

world-gen 两个成功路径上、`FixedWorldTitle.apply` 旁边加一步(可并进同一个类,实施时定),只做三件事:

- **未登记固定名的世界**(含融合局):若模型自带了 `world.subtitle` 键,**删掉** —— 保证其余世界的下发与落盘与今天一致,
  即使模型自发写了这个键也不显示。(勘察:今天模型自发写出的未知键会原样落盘;此条是新的收口,只对 `subtitle` 一个键。)
- **登记了的三个世界**:去首尾空白;按待审 4 / 5 的裁定处理空串、非字符串、超长;处理结果记一行 INFO(同 `FixedWorldTitle` 的覆写读数,只进日志)。
- 不在 `validateWorld` 里做,不动 `tryParse` 的失败判定(漏写怎么处理以待审 1 为准;推荐方案下这里不产生失败)。

### 3. 提示词(只进三个槽)

- 三个槽各加一条小标题指令,**措辞由 Felix 定**(见「待 Felix 定的文案」);《派单》《七日开张》写在【世界名】那一条旁,《寻常》槽今天没有【世界名】行,位置一并由 Felix 定。
- 每条指令须让小标题明确落在已有约束之下:玩家可见字段(泄露)、【现实边界】、《派单》禁用词与「系统的话」、《七日开张》【题材边界】与「不得是店名」。
- `prompts/world-gen.md` 的《派单》《七日开张》两节同刀逐字同步(lockstep);《寻常》槽历来不收录进 md(`world-gen.md:159`),本 ADR 不回补。
- 两份骨架、`REPAIR_HEAD`、回合骨架、种子池一字不动。

### 4. 检查

- 《派单》`systemLineErrors` 的 world 字段清单加 `subtitle`(`WorldGenService.java:304`)。命中走现有的一次修复(同其余字段)。
- 其余约束无程序层可加(勘察五),如实记入已知代价。

### 5. 前端

- `types/schema.ts` `World` 加 `subtitle?: string`;`SaveSummary` 加可选 `subtitle`(读写校验按待审 5);`recordFromWorld` 写入 `world.world?.subtitle`。
- 存档页条目、大厅「继续」行、撤销条与存档页 aria-label:有小标题时显示「固定名 · 小标题」(分隔与截断按待审 3 / 4);没有时 DOM 与今天逐字节一致。
- 局内顶图:按待审 2 的裁定(样张阶段定)。**任何摆法都不得让小标题继承 `--title-font`**:小标题不放进带 `.title` 类的 `<h1>` 文本里,
  或放进去时单独给它显式的非子集字体。
- 回合 prompt 不剔小标题(待审 8 推荐)。

### 关键理由

1. **只动一个变量**:三个槽各一条指令 + 一个可选键,其余世界的 prompt、引擎、校验器、投影、golden 都不动。
2. **复用既有通路**:world 整块流经三视图的事实(`Engine.snapshot` / `toClientState`)让新键零改动到达前端与落盘;
   世界专属收口沿 `FixedWorldTitle` / `worldSpecificErrors` 的既有位置(CONTEXT §三.18(c))。
3. **不扩大 world-gen 失败面**:展示字段漏写不该让玩家开不了局(同 ADR-038 已知代价 8「凑数要求不值得让玩家开不了局」)。
4. **保留演进路径**:若以后要给《动物人生》以外的固定名世界也加,登记一条槽指令即可;若漏写率高,再升级为修复(重新审视条件)。

## 已知代价

1. **质量只靠提示词**:小标题是不是名字而不是氛围词、有没有剧透店名、有没有现实品牌 / 真实朝代 / 禁用词,除《派单》系统台词外**程序层一概不查**。
   缓解方式:实施后 Felix 冒烟时专看小标题;INFO 日志里能读到每局写了什么。
2. **《派单》「这个人」没有名字可挂**:character 只有 traits / inventory,小标题只能挂处境(新号老号、腿、第一单)或地点。不缓解,写进待定文案供 Felix 取舍。
3. **《七日开张》「街名 + 货名」读起来可能像招牌**:真规矩写着「招牌先写货名,再写摊名」,小标题若恰是「货名 + 摊名」形状,等于提前写出一块招牌。缓解方式:指令措辞(Felix 定)。
4. **每回合 prompt 多 10–20 token(推测)**,每回合都付;不剔(待审 8)。
5. **落盘新字段不升 `schemaVersion`**,无完全相同先例(勘察三)。缓解方式:CONTEXT §二补充块写明「可选、引擎与校验器不读」。
6. **旧档永远没有小标题**;跨部署那一会儿,旧页签的 `patch` 会把指针里的小标题写丢,直到下一次 `recordFromWorld`。
7. **测试基线要动两处**:《寻常》world-gen 快照与《派单》world-gen 快照(勘察六)。若选锚定剔除,《寻常》那张快照就是第二层剔除,
   **下次再改它即触发 ADR-037 已知代价 10「超过两层改为重录」**。
8. **修复发不带小标题指令**:修复提示只含结构约束与上次产出(ADR-037 已知代价 6 同一形状)。修复后的产出若重写了小标题,那一次不受槽指令约束;若删了它,即为漏写。
9. **未登记世界的 `subtitle` 键被删**:这是新增的一处服务端改写。今天没有任何世界写这个键,预期无可见差异;若将来别的世界想用,须先登记。

## 重新审视的触发条件

- 冒烟或线上 INFO 读数里,三个世界的小标题**漏写率明显偏高**(如 ≥ 1/5)→ 重新审视待审 1,考虑升级为修复。
- 小标题**屡次写成氛围词 / 店名 / 带禁用词** → 补程序检查(至少对《派单》禁用词做一个只扫小标题的精确匹配,短字段误报低)。
- 第四个世界登记 `fixedWorldTitle` → 一并决定要不要给它小标题;若到时三个以上世界的指令几乎相同,评估抽成骨架上的公共定义(骨架改动须另立 ADR)。
- state schema 下一次因别的原因升版 → 把 `subtitle` 一并正式写进 schema 正文。

## 落地切分(审过之后;本 ADR 只写不执行)

1. **刀 1 · 后端字段 + 三个槽 + 检查**(一个 commit 或按「收口 → 槽 → 检查」拆三个,各自可编译可测):
   - 收口步骤(最终决策 2)、三个槽指令(措辞按 Felix 定稿)、`prompts/world-gen.md` 两节同步、`systemLineErrors` 加 `subtitle`;
   - 测试:收口单测(未登记世界删键 / 三世界空白 / 空串 / 超长 / 非字符串);`PaidanSystemLineRepairTest` 加一例;
     两张快照按待审 7 处理;`FixedWorldTitleTest` 原样通过;
   - **验证**:`mvn test` 全绿、给具体数字;其余 8 张 world-gen 快照与全部回合 golden 逐字节不变(由既有测试证明);
     手工跑一次 `buildWorldPrompt` 三个世界,贴出新增那一行的上下文。
2. **刀 2 · 前端四个显示点**:
   - `World` / `SaveSummary` 类型、`parseSummary` / `serializeSummary` / `recordFromWorld`、存档页条目与 aria-label、「继续」行、撤销条;
     局内顶图按样张裁定(若样张未定,本刀先不动 `SceneBanner`,另起一刀);
   - 测试:指针旧形态(无 `subtitle`)照常解析、新形态往返、待审 5 的坏形态用例;四个显示点有 / 无小标题各一例;
     **无小标题时 DOM 与改动前逐字节一致**(§4.5.1 对拍);
   - **验证**:`npm test` / lint / build 全绿给数字;375 视口量存档行与「继续」行在最长小标题下的换行 / 截断读数;
     确认小标题元素的计算 `font-family` 不含 `XunSong` / `QiriKai`。视觉体感交 Felix。
3. **刀 3 · docs**:本 ADR 实施记录与状态;CONTEXT §二补充块(可选字段 `world.subtitle`)并升文档版本;
   README ADR 列表一行、ROADMAP(`/roadmap-update`)。
4. **真 key 冒烟**:**不跑**,除非 Felix 另说。若跑,三个世界各 ≥ 2 局,看小标题有无、长度、是否名字、是否越界。

## 实施记录(2026-11-22;刀 1–3 已落,已部署;真 key 冒烟未跑)

分支 `claude/adr-044-world-subtitle`(基于 `main` `2c7894d`),草案 `3e5e1de`;三个实施 commit 各自可编译可测、各自 push,
Felix 点头后 ff 合入 `main`(至 `9510d72`),分支删除。

| commit | 内容 |
|---|---|
| `f84e2b8` | **回填裁定**:九个待审项与六条文案(校勘代 Felix 定,2026-11-22)逐条回填在文末两节各项之后,三条指令逐字收录;状态改「已定(待实施)」。原有选项与理由保留。 |
| `a20d478` | **刀 1 · 后端**:新增 `WorldSubtitle`(与 `FixedWorldTitle` 同位,`WorldGenService.landed` 里排在它之后,首过与修复两条成功路径都经过):未登记 `fixedWorldTitle` 的世界(含任何融合局)删 `world.subtitle` 键;三个登记世界去首尾空白,空串 / 非字符串 / 超过 10 字(按 code point)删键,视同漏写,漏写不算 world-gen 失败;登记世界每局一行 INFO(保留 / 删除 / 漏写 + 原文)。三个槽各加一条【小标题】,措辞逐字照本 ADR 的三条指令(《派单》《七日开张》在【世界名】下一行,《寻常》在槽末【condition 一律用中文轴名】之前);`prompts/world-gen.md` 两节逐字同步,升 v0.14(《寻常》槽本就不收录进该文件)。《派单》`systemLineErrors` 的 world 字段清单加 `subtitle`。骨架、`REPAIR_HEAD`、种子池、回合 prompt、`validateWorld`、Engine 一字未动。 |
| `9510d72` | **刀 2 · 前端(不含局内顶图)**:`World` 与 `SaveSummary` 加可选 `subtitle`;`parseSummary` 遇非字符串小标题当作没有、条目照常显示,`serializeSummary` 没有小标题时不写这个键(旧形态指针序列化逐字节不变),`sameSummary` 比小标题;`recordFromWorld` 只写非空字符串。新组件 `saves/SaveTitle` 供存档页条目与大厅「继续」行共用:没有小标题时渲染与改动前相同的 `svTitle` span,有小标题时「 · 小标题」单独一个元素、整行单行省略兜底(只挂在有小标题的行上)。`saveText` 加 `shownSubtitle` / `saveName`,撤销条与移除按钮 aria-label 经同一处拼《派单 · 小标题》。`SceneBanner` 一字未动。 |

**测试与 CI**:刀 1 `mvn clean test` 1101 个全绿(新增 `WorldSubtitleTest` 9、`XunWorldGenSnapshotTest` 2、`PaidanSystemLineRepairTest` +1);
刀 2 `npm test` 59 个文件 858 个全绿,lint、build 通过。GitHub Actions CI:#361(`f84e2b8`)、#362(`a20d478`)、#363(`9510d72`,分支)、
#364(`9510d72`,合入 `main` 后)全绿。

**两张快照(待审 7)**:
- 《寻常》:移出 `PaidanWorldGenTest` ①「既有世界」组(9 → 8),另立 `XunWorldGenSnapshotTest` + `golden/life_sim/world-gen.txt`,
  出处 = `a20d478` 的 `buildWorldPrompt("life_sim")`(截到最后一个「场景种子:」之前)。录制时与旧快照 `golden/world-gen-prompts/life_sim.txt`
  (`3e72099`)diff:差异恰好是 ADR-037 的 tone 行、【现实边界】块(旧快照比对前本就剔掉的两处)与这一条【小标题】;旧快照随之删除(git 记为改名)。
- 《派单》:重录 `golden/cyberpunk/world-gen.txt`,出处 = `a20d478`;与重录前 diff 恰好多【小标题】一行。回合快照 `turn_T*` 未动。
- 《七日开张》:没有整份 world-gen 快照(`QiriWorldGenTest` 只有 md lockstep 与内容断言),照实不新建。
- 其余 8 张 world-gen 快照与全部回合 golden 逐字节不变(由既有测试证明)。

**无小标题时 DOM 对拍(§4.5.1)**:`SavesNoSubtitleDom.test` + `__fixtures__/adr044-no-subtitle-dom.html`,基线在 `a20d478` 的前端原样上录
(stash 掉刀 2 改动后跑同一场景),覆盖存档页两组、撤销条、大厅「继续」块;改动后逐字节相同。

**375 视口读数**(dev server,本地指针播种):10 字小标题「七日开张 · 一二三四五六七八九十」在「继续」行 274px、存档页 294px 内不溢出
(`scrollWidth = clientWidth`);小标题计算字体 `system-ui`,不含 `XunSong` / `QiriKai`;未挂单行类的长名仍 `white-space: normal`。

**两处实施裁定**(本 ADR 正文没写到,实现时定,记在这里):
1. **`"subtitle": null` 按漏写处理**:登记世界里键在但值为 JSON `null` 时,与没写这个键走同一分支(删键、INFO 记「漏写」),
   不记成「删除(不是字符串)」。理由:模型写 `null` 的意思就是「没起」,日志里归到漏写率才对得上待审 1 的「先读漏写率」。
2. **未登记世界只在带了这个键时才记 INFO**:没有这个键时原样返回,不写日志(其余世界的日志与输出都不变);
   带了键才删并记一行「未登记固定世界名,删除 world.subtitle,原文=…」(`WorldSubtitleTest` 钉了「没这个键不记日志」)。

**遗留**(登记进 ROADMAP 挂账,本 ADR 不做):局内顶图摆法(待审 2,等样张);存档行小标题字重(现与固定名同为 600,等 Felix 真机看);
《寻常》槽缺【世界名】软层(挂账项第一条,照旧)。

## 实际效果(事后补充)

*刀 1–2 部署后、Felix 首次冒烟时回填:三个世界各几局里小标题的有无与原文;漏写率;有无氛围词 / 店名 / 禁用词 / 真实朝代;存档页两条同世界档是否已能一眼区分。*

## 待 Felix 审定清单

1. **模型漏写小标题时怎么办**
   - A. **放过**:不显示,只记 INFO。
   - B. 进修复:`worldSpecificErrors` 报「缺 world.subtitle」→ 一次修复;修复仍缺 → 按 §三.11 整局 ERROR。
   - C. 进修复但只修一次、修复后仍缺则放过(小标题错误在第二次校验里不算失败)。
   - **推荐 A**。理由:约束条件 2;B 让一个展示字段能把整局打成 ERROR,与「漏写不算失败」相冲;C 不 ERROR,但为一个展示字段多付一整份 world-gen 输出(勘察八),
     且 `tryParse` 要区分「哪些错误第二次不算」,复杂度落在最险的那条路径上。漏写率先读 INFO,高了再升级(重新审视条件)。
   - **已定(校勘代 Felix 定,2026-11-22)**:A 放过(不显示,只记 INFO)。
2. **局内顶图摆法**(样张阶段定,本 ADR 只列)
   - A. title 与 tone 之间**新起一行**小字(三行文字);
   - B. **与 title 同一行**,固定名后接「 · 小标题」,小标题单独一个元素、显式非子集字体、字号小于 title;
   - C. 放进顶部「第 N 回合 · 危险度」那一行;
   - D. 局内不显示,只在存档页 / 「继续」行 / 撤销条显示。
   - **倾向 A,待样张**。理由:不碰 `<h1>` 的文本与字体令牌(B 要在 `<h1>` 里拆元素、改动 DOM 最多);C 那一行是导航层,背景稳定但空间被返回键占;
     A 的代价是顶图下部多一行,banner `overflow: hidden`、文字贴底,需实测在 300px 最小高度下不压图上关键部位。
   - **已定(校勘代 Felix 定,2026-11-22)**:推迟到样张阶段;刀 2 不动 `SceneBanner`,局内顶图另起一刀。
3. **存档行 / 撤销条的分隔符与书名号**
   - 存档行与「继续」行:a.「派单 · 小标题」(同 `describeSave` 的「 · 」);b.「派单」大字 + 小标题小字分两段;c.「派单:小标题」。
   - 撤销条:a.「已从这台设备移除《派单 · 小标题》」;b.「已从这台设备移除《派单》· 小标题」;c. 撤销条不带小标题。
   - **推荐 存档行 a、撤销条 a**。理由:Felix 已定「固定名 · 小标题」,「 · 」与下一行 `describeSave` 同一个分隔符;撤销条是一句话,书名号包整个名字读起来是一个名字。
     aria-label 与撤销条同形。
   - **已定(校勘代 Felix 定,2026-11-22)**:存档行 / 「继续」行写「派单 · 小标题」;撤销条写《派单 · 小标题》;aria-label 与撤销条同形。
4. **上限字数与超长怎么办**
   - 指令里写的长度:建议「4–8 字」(同骨架 `endings[].title`「中文短标题 4-8 字」的既有口径)。
   - 程序上限:a. 8;b. 10;c. 12。超过上限:i. 视同漏写(删键,记 INFO);ii. 截断加省略号;iii. 进修复。
   - **推荐 程序上限 b(10 字)+ i 视同漏写**。理由:给模型的要求与程序门槛留两字余量(模型自起的名实测出过 9 字的「住院部三楼夜巡守则」,trace 夹具 `first-real.jsonl`);
     截断会把名字切在半截;进修复同待审 1 的理由。CSS 侧另加单行省略作为兜底,375 视口读数在刀 2 给出。
   - **已定(校勘代 Felix 定,2026-11-22)**:指令里写 4–8 字;程序上限 10 字(按 code point 计);超过上限视同漏写(删键,记 INFO);CSS 单行省略作兜底。
5. **指针里 `subtitle` 不合形状时**(存在但不是字符串)
   - A. 视为没有小标题,条目照常显示;B. 整条视为解析失败(同其余七个字段,跳过不显示,不删除)。
   - **推荐 A**。理由:约束条件 4 —— 指针是找回一局的唯一凭证,不该因为一个展示字段整条「看不见」;其余七个字段决定能不能打开这一局,小标题不决定。
   - **已定(校勘代 Felix 定,2026-11-22)**:A —— 当作没有小标题,条目照常显示。
6. **`schemaVersion` 不动(仍 `"0.4"`)** —— 请确认。依据与反面见勘察三。若要升版,须改两份骨架 → 其余世界 world-gen prompt 全变,与已定约束冲突,需另行裁定。
   - **已定(校勘代 Felix 定,2026-11-22)**:`schemaVersion` 不动,仍 `"0.4"`。
7. **两张会变的快照怎么处理**(勘察六)
   - 《寻常》`golden/world-gen-prompts/life_sim.txt`:a. 第二层锚定剔除;b. 移出「既有世界」组、另立《寻常》快照;c. 重录。
   - 《派单》`golden/cyberpunk/world-gen.txt`:a. 锚定剔除;b. 重录(按该测试注释的更新口径)。
   - **推荐 《寻常》b、《派单》b**。理由:这两个世界的 world-gen prompt 是**有意改动**,「既有世界不变」那组的意思是「没改的世界不变」,
     有意改的世界留在组里靠剔除维持,会把 ADR-037 已知代价 10 的层数用到上限;另立快照 / 重录后,出处记为刀 1 的实现 commit。
   - **已定(校勘代 Felix 定,2026-11-22)**:《寻常》b —— 移出「既有世界」组,另立自己的快照(照 `PaidanPromptSnapshotTest` 先例);《派单》b —— 重录。
8. **回合 prompt 要不要剔掉小标题**
   - A. 不剔(同 title / tone 每回合都带);B. 回合侧剔(需在 `contextJson` 之外另开一条去键路径,动 Engine 或 TurnPromptBuilder)。
   - **推荐 A**。理由:勘察四 —— 无新增信息、token 增量小;B 要动视图 2 的生成路径,换来的只是「越界小标题不被每回合复读」,而越界本身在 world-gen 侧。
   - **已定(校勘代 Felix 定,2026-11-22)**:A 不剔。
9. **未登记世界自带的 `subtitle` 键删不删**(最终决策 2 第一条):推荐删;若 Felix 认为不必为一个尚未出现过的情形加服务端改写,可去掉这一条,
   代价是「其余世界逐字节不变」只对 prompt 成立、对模型自发写出的这个键不成立。
   - **已定(校勘代 Felix 定,2026-11-22)**:删。

## 待 Felix 定的文案

只列要定什么,不写措辞:

1. **三条指令各自的措辞**:《寻常》挂这一生开头的人 / 地方 / 年份;《派单》挂地点或这个人(注意没有名字字段,勘察二);《七日开张》挂摊子(卖什么 / 哪条街)。
   - **已定(校勘代 Felix 定,2026-11-22)**:见下面「三条指令」,逐字照用。

     **三条指令**(逐字照用,标点照抄;不改写、不加粗):

     《派单》—— 放在【世界名】那一条的下一行:
     ```
     - 【小标题】world.subtitle 写这一局的名字,4–8 个字,挂在这一局具体的地点或这个跑腿员的处境上(哪条巷、哪栋楼、新号还是老号、腿怎么了、第一单送什么之类),要和 background、开场对得上。它是一个名字,不是几个氛围词或形容词并列,也不预告结局。小标题是玩家可见字段,本世界所有对玩家可见字段的要求对它一样适用。
     ```
     《七日开张》—— 放在【世界名】那一条的下一行:
     ```
     - 【小标题】world.subtitle 写这一局的名字,4–8 个字,挂在这个摊子本身上(卖什么、在哪条街、摊上哪样旧物之类),要和 background、开场对得上。它是一个名字,不是几个氛围词或形容词并列,也不预告结局。它不是招牌:不得写成摊名或招牌字样,也不得是「货名 + 摊名」的形状,招牌留给结局回合。小标题是玩家可见字段,本世界所有对玩家可见字段的要求对它一样适用。
     ```
     《寻常》—— 放在槽末【condition 一律用中文轴名】那一条之前(不碰末行的续行符 `\`;这个槽走 `.formatted`,新行里没有 `%`):
     ```
     - 【小标题】world.subtitle 给这一辈子起一个名字,4–8 个字,挂在这一生开头具体的人、地方或年份上(种子给的年份与地方、家里的排行、身边的同龄人、手边的一样东西之类),要和 background 对得上。它是一个名字,不是几个氛围词或形容词并列,也不概括这一生会怎样、不预告结局。小标题是玩家可见字段,本世界所有对玩家可见字段的要求对它一样适用。
     ```
2. **「是名字不是氛围词」怎么说**:要不要给正例 / 反例;若给,用哪几个(「雨夜便利店」「山雾民宿」「屋里的灯」与反例「孤寂、钝痛、微光」均出自既有世界,用作例子时模型可能照抄)。
   - **已定(校勘代 Felix 定,2026-11-22)**:不给正例或反例的具体词,只描述形状(用例子的话,模型容易照抄)。
3. **《七日开张》「不得是店名」怎么说**:是否点名「不得写成招牌、不得是『货名 + 摊名』的形状」(勘察二 ⚠️)。
   - **已定(校勘代 Felix 定,2026-11-22)**:点名「不得写成摊名或招牌字样,也不得是『货名 + 摊名』的形状」。
4. **《寻常》指令放在槽的哪里**:今天槽里没有【世界名】行(ADR-037 已知代价 5);是新起一条【小标题】,还是借这个机会一并补上【世界名】软层(后者是另一件事,要不要同刀由 Felix 定)。
   - **已定(校勘代 Felix 定,2026-11-22)**:新起一条【小标题】,放在槽末【condition 一律用中文轴名】之前;不补【世界名】(挂账照旧)。
5. **小标题与覆盖约束的那一句**:是否在指令里点名「小标题也受【泄露硬约束】【现实边界】与本世界禁用词约束」,还是只写「小标题是玩家可见字段」。
   - **已定(校勘代 Felix 定,2026-11-22)**:一句话覆盖 ——「小标题是玩家可见字段,本世界所有对玩家可见字段的要求对它一样适用」;另加「不预告结局」。
6. **能不能用年份 / 数字**:《寻常》年份是挂点之一;《派单》禁用词段规定「数字只用分钟、秒、楼层、单数、余钱」—— 楼层 / 门牌之类能否进小标题。
   - **已定(校勘代 Felix 定,2026-11-22)**:数字不单独规定,由第 5 条覆盖。

## 跟其他文档的交叉引用

- **前序**:[ADR-036](ADR-036-cyberpunk-world-paidan.md) Slice A2(`fixedWorldTitle` 覆写)、已知代价 8(禁用词无程序检查)、订正三(`systemLineErrors`);
  [ADR-037](ADR-037-world-tone-and-common-topic-boundary.md) 决策 1–3(tone 定义、现实边界、《寻常》登记)、已知代价 3 / 5 / 6 / 10;
  [ADR-038](ADR-038-time-travel-world-qiri-kaizhang.md)(《七日开张》槽、招牌软层、已知代价 8)。
- **显示点**:[ADR-034](ADR-034-multi-save-client-pointers.md)(存档指针第一原则、迁移);[ADR-042](ADR-042-saves-area.md)(存档页、「继续」行、撤销条)。
- **字体**:[ADR-020](ADR-020-lifetime-world-family-and-ordinary-life.md)「《寻常》局内皮肤」决策 6(XunSong 子集);ADR-038 Slice G-2 决策 5 / 6(QiriKai 子集、`--title-font`)。
- **约定**:CONTEXT §二(schema)、§三.9(三视图)、§三.11(world-gen 失败 = ERROR)、§三.18(c)(世界专属 world-gen 检查);[ADR-007](ADR-007-world-gen-wire-protocol.md);[ADR-008](ADR-008-multi-mode-extension-architecture.md)。
- **源文件**:`server/.../worldgen/FixedWorldTitle.java`、`WorldGenService.java`、`WorldGenPromptBuilder.java`;`web/src/state/savePointers.ts`、
  `web/src/state/gameStore.ts`、`web/src/features/game/{SceneBanner.tsx,ArchetypeSelect.tsx,saveText.ts,saves/SavesScreen.tsx}`。
- **样本**:`bakeoff/FINDINGS.md:1052`(「屋里的灯」/「孤寂、钝痛、微光」);本地存档 `server/data/81acb3bd…`(《七日开张》)、`d6d3c966…`(《寻常》),未入库。

## 挂账项

- 《寻常》槽缺【世界名】软层(ADR-037 已知代价 5)—— 不在本 ADR 范围,列入「待 Felix 定的文案」第 4 条供顺带决定。
- 《派单》禁用词、《七日开张》真实朝代至今无程序检查 —— 重新审视条件第 2 条。
