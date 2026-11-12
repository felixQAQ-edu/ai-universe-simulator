# ADR-037 · world.tone 统一定义 + 公共题材边界进骨架 + 《寻常》登记固定世界名

- **日期**:2026-11-12
- **状态**:**已实施**(2026-11-12:第 1 刀 `d2bec94`(Felix 静态校勘核过)+ 第 2 刀文档回写,均在分支 `claude/adr-037-tone-boundary`;待 CI 绿后 ff 合并 `main`,**未部署**,未跑真 key)。
  ⚠️ 定稿时的状态行,原文保留:已采纳(定稿 2026-11-12:三项决策与待审四项均由 Felix 2026-11-12 定下,见文末「Felix 裁定清单」;实现按「落地切分」第 1 刀进行,第 2 刀文档回写待 Felix 核过第 1 刀后再做)。
  ⚠️ 草案时的原状态行,原文保留:提议(草案;三项决策 Felix 2026-11-12 已定,本 ADR 只落字、不动代码。骨架里的放置位置与小标题、golden 怎么订正两处待 Felix 审定,见文末清单)
- **决策者**:Felix

## 背景

ADR-036 留下两条「另开一刀的待办」(ADR-036「待 Felix 定稿清单」末尾),本 ADR 处理这两条,外加一处在《派单》以外也出现的毛病:

1. **标题下那一行写成了写作要求**。游戏标题下那一行是 `world.tone`。骨架对它只说「用中文」,模型就会把 worldview 里的
   风格说明照抄进去。《派单》冒烟时出现过(ADR-036 订正 5,当时只在《派单》指令槽里修);《动物人生》冒烟时副标题是
   「孤寂、钝痛、微光」(`bakeoff/FINDINGS.md:1052–1053`,正是一串氛围词);Felix 观察到线上《寻常》局标题下显示的
   也是写作要求(仓库里没有这一条的记录,以 Felix 观察为准)。三个世界、两种形状(写作要求 / 氛围词清单),同一个成因。
2. **「不写政治隐喻、不写真实品牌」只对《派单》生效**。ADR-036 裁定 5 当时只放进《派单》两份指令槽,并记下「提升为全局约束另开一刀」。
3. **《寻常》《动物人生》要不要登记 `fixedWorldTitle`**(ADR-036 勘察 5 补记 ①、已知代价 9 记为待办)。

Felix 2026-11-12 已就这三项拍板(下文「最终决策」照原话,文案一字不改)。

约束条件:

1. **骨架单点维护**(ADR-008:安全规矩全在骨架、不得 per-mode 重抄;ADR-020 已知代价 5)。多个世界共用的指令回骨架。
2. **不改既有行**:骨架只新增行 / 段,已有的每一行一字不动(Felix 原话「各新起一行,不改既有行」)。
3. **引擎、校验器、state schema 不动**,`schemaVersion` 仍 `"0.4"`。
4. **只写最小公共子集**:更严的边界(不出现现实年代、地名一律虚构等)不进骨架,留在各世界自己的指令槽。
5. **不跑真 key 冒烟**(Felix 已定),改为上线后观察。

### 勘察结论(只读 `origin/main` `a6e0b0f`,2026-11-12)

#### 勘察 0 · ADR 编号

`docs/adr/` 最新一份是 `ADR-036-cyberpunk-world-paidan.md`;全仓 `docs/` 无 `ADR-037` 字样,也没有含 `037` 的分支。本 ADR 取 037。

#### 勘察 1 · `world.tone` 的消费方:只有一处,单体与融合都显示

| 位置 | 做什么 | 单体 / 融合 |
|---|---|---|
| `web/src/features/game/SceneBanner.tsx:66` | `<p className={styles.tone}>{tone}</p>`,渲染在 `<h1>` 标题正下方 | 组件本身不分 |
| `web/src/features/game/GameScreen.tsx:195–202` | 无条件渲染 `<SceneBanner … title={world.world.title} tone={world.world.tone} />`,前后没有按 `mode` 分支 | 都显示 |
| `web/src/App.tsx:10` | 只有 `<GameScreen />` 一个局内屏,没有单独的融合局屏 | — |
| `web/src` 其他处 | `tone` 只剩类型定义 `types/schema.ts:31`;存档指针(`state/savePointers.ts`)不存它 | — |
| 服务端 | `GameSchemas.java:53` 校验非空;`WorldGenService.java:243` 把它列进玩家可见字段做流末扫描;**不进任何判定** | — |

结论:tone 只有「标题下那一行」这一个用途,单体、融合都显示 → 定义写进两份 world-gen 骨架是对的落点。

#### 勘察 2 · 骨架位置:world-gen 两份独立骨架,回合一份骨架单体融合共用

| 骨架 | 位置 | tone 现状 | 【绝境的写法】块 | 块后紧接 |
|---|---|---|---|---|
| `WorldGenPromptBuilder.SKELETON`(单体) | `:56` | `:70`「- world:{ title, background, tone 用中文;dangerLevel … 取种子给定值 }」 | `:93–100` | `:102`【泄露硬约束】,行尾挂 `%8$s`(本世界 world-gen 指令槽) |
| `WorldGenPromptBuilder.FUSION_SKELETON`(融合) | `:125` | `:152`「- world:{ title, background, tone 用中文;dangerLevel ∈ {…} }」 | `:179–186` | `:188`【泄露硬约束】,骨架到此结束(融合文案槽在 `%9$s`,不在末尾) |
| `TurnPromptBuilder.SKELETON`(回合) | `:224` | —(回合不产 tone) | `:244–251` | `:253`【输出格式 · 严格遵守】 |

- 两份 world-gen 骨架**确实是独立两份**(类注释 `:118–124` 自陈「本骨架与 SKELETON 是两份独立骨架…必须同步改」)。
- **回合侧融合局走同一份 `SKELETON`**:`buildTurnPrompt` 在 `:823` 用 `SKELETON.formatted(…)`,
  融合指令 `FUSION_TURN_DIRECTIVE` 以 `%8$s` 注入(`:835`,单体为空串),单体世界指令以 `%9$s` 注入(`:838`,融合为空串)。
  所以回合侧只改一处,单体融合都拿到。
- 修复提示不含骨架:world-gen `REPAIR_HEAD`(`:192`,`buildRepairPrompt` `:903`)、回合 `REPAIR_SYSTEM`(`:762`)都是独立短文,
  今天也不带【绝境的写法】。本 ADR 不改它们(见已知代价 6)。
- 三份骨架都经 `String.formatted` 渲染:新增文字里**不得出现 `%`**。Felix 两段原话都没有 `%`,已核。

#### 勘察 3 · 《派单》两份指令槽里已有的、比公共子集更严的边界句(行号与 brief 一致)

| 位置 | 原句(节选) | 与公共子集的关系 |
|---|---|---|
| world-gen `:317` | 「【标题下那一行】world.tone 会显示在游戏标题下面:写成一句让玩家想进入这个世界的介绍,从跑腿员的处境说起;不得写成写作要求、风格说明或氛围清单。」 | 被决策 1 的骨架行取代 → **本刀删这一行** |
| world-gen `:347` | 「平台名、商家名、小区名、街道名、城市名一律虚构,不与任何现实名称谐音、拆字;…」 | 比公共子集严(地名、谐音拆字),留槽 |
| world-gen `:350` | 「不出现现实年代、真实新闻事件的影子,不写政治隐喻;」 | 「不写政治隐喻」**与公共子集重复**;「现实年代、真实新闻」更严,留槽 |
| world-gen `:361` | 禁用词「现实品牌、平台名、地名;…」 | 「现实品牌」**与公共子集重复**;平台名、地名更严,留槽 |
| 回合 `:460` | 「平台、商家、小区、街道、城市一律虚构;…」 | 更严,留槽 |
| 回合 `:462` | 「城中村只是地形,…;不写政治隐喻,不出现现实年代与真实新闻的影子;」 | 「不写政治隐喻」**重复**;其余更严,留槽 |
| 回合 `:477` | 禁用词「现实品牌、平台名、地名;…」 | 「现实品牌」**重复**;其余更严,留槽 |

重复的四处本刀**不删**(Felix 没下这个指令),记为已知代价 2。

#### 勘察 4 · 《寻常》tone 的来源

`ArchetypeRegistry.ordinaryLife()`(`:753`)的 worldview(`:759–764`)以「《寻常》——一个普通人的一辈子:不逆袭、不觉醒、没有主线,没有异常与敌人;」开头,
以「氛围克制具体、不煽情——重量来自日常细节本身。」结尾。这句结尾与《派单》订正 5 删掉的「具体而克制」同一形状:一句写作要求,
而骨架对 tone 只说「用中文」,模型就有可能照抄。

- `WORLD_GEN_DIRECTIVES["life_sim"]`(`:431` 起)只讲结局池与早逝,**不提 tone,也不提 title**(ADR-036 勘察 5 补记已记)。
- 本 ADR **不改**这句 worldview(Felix 没下这个指令)。决策 1 的骨架行是对所有世界的正面定义,预期足以压住照抄;
  如果上线后观察到《寻常》仍照抄,再考虑删 worldview 这半句(见重新审视的触发条件)。
- 《寻常》的 `registry` 登记现为 8 参构造(`:826` 末参 `false`,无 `fixedWorldTitle`);《派单》是 9 参,末参 `"派单"`(`:912`)。

#### 勘察 5 · 会因本刀变红的既有测试(读源码推断,**未实跑**;实施时以实跑为准)

| 测试 | 断言 | 为什么红 | 预期内? |
|---|---|---|---|
| `PaidanWorldGenTest`(`:61–65`) | 其他世界的 world-gen prompt(种子尾巴前)逐字节等于 `golden/world-gen-prompts/*.txt`(取自 `3e72099`,9 份:6 单体 + 3 融合) | 每份都多 tone 行与边界块 | **是**:正是要验的那件事,订正法见决策 5 |
| `PaidanWorldGenTest`(`:105`) | 《派单》prompt 含「【标题下那一行】world.tone 会显示在游戏标题下面…」 | 决策 1 删了这一行 | **是**:改为断言不含,并断言骨架那一行在场 |
| `PaidanWorldGenTest` ④ lockstep(`:146–`) | `prompts/world-gen.md`「单体世界专属 ·《派单》」节逐字收录指令槽 | 只改 Java 不改 md 时红 | **是**:md 同步删那一行后应恢复绿 |
| `PaidanTurnTest`(`:50–70`) | 9 个世界(6 单体 + 3 融合)× 10 个回合号的回合 prompt 逐字节等于 `golden/turn-prompts/*.txt` | 每份都多边界块 | **是**:订正法见决策 5 |
| `AnimalLifeLegacyGoldenTest.legacyTurnPromptIsByteIdenticalTo583abc9`(`:64–69`) | 旧局《动物人生》回合 prompt 与 `583abc9` 逐字节相同(8 份金样本) | 回合骨架多边界块 | **是,但它守的是一条立字**:CONTEXT §三.17 ADR-028 补充段原文「回合 prompt 与 `583abc9` 逐字节相同」。决策 2 让这句不再成立,需 Felix 确认(见文末清单第 3 项) |
| `AnimalLifeLegacyGoldenTest`(`:79`) | 新局 prompt 与同一金样本**不相等** | 不会红(两边都多同一块,仍不等) | — |
| `FixedWorldTitleTest.onlyPaidanRegistersAFixedTitle`(`:113–122`) | 除 `cyberpunk` 外所有世界 `fixedWorldTitle == null` | `life_sim` 登记「寻常」 | **是**:改为《派单》「派单」、《寻常》「寻常」、其余 null |
| `PaidanRegistryTest.onlyPaidanRegistersAFixedWorldTitle`(`:37–43`) | 同上 | 同上 | **是**:同上 |

**确认不会红**(读过断言):

- `ContentSafetyPromptLockstepTest`:对五个面只做 `contains(DESPERATION_RULE)`,新块加在【绝境】块之后不影响。
- `WorldGenPromptBuilderTest:202`:「隐藏逻辑只进 hiddenLogic。\n\n按以下种子生成世界」——**前提是边界块放在【泄露硬约束】之前**(决策 2 的放置正是这样);
  若放到【泄露硬约束】之后,这条会红。`:155–161` 的「- endings:2-3 个,含至少一个」是 `contains`,不受影响。
- `FixedWorldTitleTest.unregisteredWorldsOutputIsByteIdentical`:循环里跳过已登记的世界,《寻常》登记后自动跳过。
- `TurnFusionLockstepTest` / `FusionMetaPromptLockstepTest` / `ActionHintPromptLockstepTest` / `LifetimeFamilyLockstepTest`:只对拍融合指令、融合 meta、hint 措辞与一生制族文字,不碰骨架这两处。
- 揭示阶梯词汇扫描(`CultivationCthulhuWorldGenTest:180`、`CultivationCthulhuTurnTest:92–94 / :167–170`):扫的是《验讫》内核、文案槽与时段注入,不扫骨架。
  (tone 那句含「风格」的「格」字,但骨架里本来就有「输出格式」的「格」,第二级词汇按语义而非单字判定,不构成冲突。)
- 全仓测试字符串字面量与两段新文字做了子串比对,命中的只有上面「格」与 `LifeStageClockTest:97` 的「游戏」——后者扫的是 `exitText` / `exitHint`,不是 prompt。
- 轨迹回放(`TraceReplayer:79–82`、`TraceFixtureReplayTest`)比的是 `post.sha256`(state),不比 prompt 哈希;`promptSha256` 只在 `TraceRecheck` 里用于重渲染一致性告警,不进回归夹具断言。

## 候选方案

### 方案 A:继续在各世界指令槽里各写一份

把 tone 定义和题材边界分别抄进每个世界的 world-gen / 回合指令槽。

**优点**:
- 每个世界的 prompt 只在自己登记时变,其余世界逐字节不变,golden 不用动。

**缺点**:
- 违反 ADR-008「安全规矩全在骨架、绝不 per-mode 重抄」;7 个单体 + 3 个融合要抄十份以上,一份漏改就是一个世界没有边界。
- 融合组合没有 world-gen 侧的单体指令槽(融合文案在 `%9$s` meta 里),要另开口子。
- 新世界上架时要记得再抄一遍,没有任何东西提醒。
**排除理由**:正是 ADR-036 当时「只对本世界生效」留下的待办要消除的形状。

### 方案 B:只改 worldview(删掉会被照抄的风格句)

逐个世界删 worldview 里的写作要求,不动骨架。

**优点**:
- 不改骨架,golden 不动。

**缺点**:
- 只治 tone,不治题材边界。
- 治标:骨架对 tone 仍只说「用中文」,下一个新世界的 worldview 再写一句风格说明,问题照旧。
- 动 worldview 会同时改 world-gen 注入块的措辞,影响面比加一行大。
**排除理由**:Felix 的决策是给 tone 一个正面定义,不是逐个删来源。

### 方案 C:骨架新增 tone 定义行 + 公共题材边界块,更严的留各世界槽;《寻常》登记固定世界名(本 ADR 采纳)

**优点**:
- 一处定义,所有世界(含将来新增的世界和融合组合)都拿到;与【绝境的写法】同一个维护形态,可以照抄它的 lockstep 守护。
- 只新增不改行,dump diff 能精确预测(每个世界只多固定的那几行)。
- 更严的边界仍在各世界槽里,公共层只放所有世界都能接受的最小子集,不会把《派单》的题材限制强加给修仙或克苏鲁。

**缺点**:
- 所有世界的 world-gen 与回合 prompt 都变,三组 golden(共 9 + 90 + 8 份)都要订正。
- 每回合 prompt 多一小段,token 成本略增。
- 《派单》槽里与公共子集重复的句子本刀不删,会出现两遍(已知代价 2)。

## 最终决策

**方案 C**。三项决策照 Felix 2026-11-12 原话,文案一字不改;放置位置、小标题、golden 订正方式由 Felix 2026-11-12 第二轮审定(文末清单)。

### 1. `world.tone` 统一定义(world-gen 两份骨架各新起一行)

Felix 原话:

> world.tone 显示在游戏标题下面,写成一句让玩家想进入这个世界的介绍,从玩家的处境说起;不得写成写作要求、风格说明或氛围词清单。

**放置(已定,Felix 2026-11-12:按推荐)**:紧接在 `- world:{ title, background, tone 用中文;… }` 那一行之后,作为它的子条目,
格式与同一骨架里 `rules` / `endings` 的子条目一致(两个空格缩进 + `- `):

```text
- world:{ title, background, tone 用中文;dangerLevel ∈ {low,medium,high,extreme} 取种子给定值 }   ← 既有行,一字不动
  - world.tone 显示在游戏标题下面,写成一句让玩家想进入这个世界的介绍,从玩家的处境说起;不得写成写作要求、风格说明或氛围词清单。   ← 新增
- character:{ … }   ← 既有行,一字不动
```

- `SKELETON` 加在 `:70` 之后,`FUSION_SKELETON` 加在 `:152` 之后,两份同一句。
- 「两个空格 + `- `」是排版,不改 Felix 原话的任何字。
- **《派单》world-gen 指令槽删掉 `:317`「【标题下那一行】…」那一整行,其他行一字不动。**
  其中「从跑腿员的处境说起」这一点《派单》专属的提法随之去掉,由骨架的「从玩家的处境说起」覆盖。
- `prompts/world-gen.md` 同步:「输出格式」第 2 条之后加同一句;「单体世界专属 ·《派单》」节删掉对应行。

### 2. 公共题材边界(world-gen 两份骨架 + 回合骨架)

Felix 原话:

> 不写政治隐喻、不借设定影射现实政治事件或人物;不出现现实品牌和商号;不点名现实在世人物。

**放置与小标题(已定,Felix 2026-11-12:位置按推荐,小标题用备选)**:仿【绝境的写法 · 硬约束】另起一块,小标题 **【现实边界 · 硬约束】**,
紧接在【绝境的写法】块的最后一行「不进入「具体怎么做」。」之后,空一行:

```text
不进入「具体怎么做」。                 ← 【绝境的写法】块末行,既有,一字不动

【现实边界 · 硬约束】                  ← 新增
不写政治隐喻、不借设定影射现实政治事件或人物;不出现现实品牌和商号;不点名现实在世人物。   ← 新增

【泄露硬约束】…                        ← world-gen 两份:既有,一字不动
【输出格式 · 严格遵守】…               ← 回合:既有,一字不动
```

选这个位置的理由:

1. 它和【绝境的写法】是同一族(「怎么写玩家可见的散文」),放在一起,将来读骨架的人一眼看到两条公共写作硬约束;
2. **world-gen 侧必须在【泄露硬约束】之前**:`%8$s`(单体指令槽)挂在【泄露硬约束】行尾,放在之后会改变槽前的字节,
   `WorldGenPromptBuilderTest:202` 当场红,且空串槽的 parity 证据失效;
3. 回合侧放在【输出格式】之前,与 world-gen 两份位置对称,三份骨架里这块的上下文相同。

**小标题为什么是【现实边界】(Felix 2026-11-12)**:三条约束说的都是**现实**(现实政治、现实品牌商号、现实在世人物);
且《派单》两份槽里已有一个「【题材边界】只写这一个人的三个夜班:」,草案的【题材边界 · 硬约束】会与它重名。
改名只换小标题,原话一字不动。
⚠️ 草案原写「小标题 【题材边界 · 硬约束】」并把【现实边界】列为备选,已由上句取代;本 ADR 其余各处一律用【现实边界 · 硬约束】。

**更严的不进骨架**:不出现现实年代、地名一律虚构、不与现实名称谐音拆字、不写集体行动等,留在各世界自己的指令槽(目前只有《派单》有)。

`prompts/world-gen.md` 与 `prompts/event-loop.md` 各在【绝境的写法】块之后加同一块(逐字,不加 markdown 加粗,与【绝境】块的 md 惯例一致)。

### 3. 《寻常》登记 `fixedWorldTitle = 「寻常」`;《动物人生》不登记

- `ArchetypeRegistry.ordinaryLife()` 末参由 `false);` 改为 `false, "寻常");`(走 ADR-036 Slice A2 已有的 9 参构造器)。
  覆写机制(`worldgen/FixedWorldTitle`,只对单体、过校验之后、落盘之前)不改。
- **《动物人生》不登记**。理由(Felix 2026-11-12):**每一辈子的故事名不同是这个世界的特色**——每局是一只具体动物的一生,
  模型按这一生起的名字(冒烟里出过「屋里的灯」,`bakeoff/FINDINGS.md:1052`)本身就是体验的一部分;固定成同一个名字反而抹掉它。
  ADR-036 勘察 5 当时把「屋里的灯」记成「世界名会漂」的反证,自本 ADR 起改读为**有意保留的特色**,不是缺陷。
- **关掉 ADR-036 的待办**:ADR-036 已知代价 9 末句「要不要推广到《寻常》《动物人生》另记待办」与「待 Felix 定稿清单」
  末尾「另开一刀的待办」两条,**均由本 ADR 处理完毕**:
  - 「不写政治隐喻、不写真实品牌和地名」提升为全局约束 → 决策 2(公共子集为政治隐喻 / 影射 / 现实品牌商号 / 在世人物;
    地名不进公共层,仍只在《派单》槽);
  - 《寻常》《动物人生》要不要登记 `fixedWorldTitle` → 决策 3(《寻常》登记,《动物人生》不登记)。
  - ADR-036 原文不在本 ADR 里改;实施时按先例在 ADR-036 那两处后面各加一行 ⚠️ 指针指向本 ADR(见落地切分)。

### 4. 不动的东西

- 引擎、校验器(`validateWorld` / `GameSchemas`)、state schema:**`schemaVersion` 仍 `"0.4"`**。
- 各世界 worldview(含《寻常》那句「氛围克制具体、不煽情」)、修复提示(`REPAIR_HEAD` / `REPAIR_SYSTEM`)、种子池、融合 meta。
- 前端:`SceneBanner` 照旧显示 `world.tone`,不加任何过滤。
- 《派单》槽里与公共子集重复的四处句子(勘察 3)。

### 5. 验证方案(实施时照做)

**(a) 提示词 dump diff(临时 harness,用完删,不进提交)**

- 每个世界各 dump 一份 world-gen prompt 与回合 prompt。世界清单**从 registry 实数**:
  单体 = `new ArchetypeRegistry().activeMetas()`,融合 = `listFusionCombos()`;不写死数量(截至 `a6e0b0f` 为 7 + 3)。
  回合 prompt 用与 `PaidanTurnTest` 相同的夹具与回合号(`TURNS = {0, 6, 7, 8, 15, 16, 17, 25, 26, 27}`),
  这样《派单》的时段 / 腿况注入、一生制时钟、《验讫》时段都覆盖到;《动物人生》另 dump 旧局路径(`legacyScene = true`)。
- **先自检**:同一构建 dump 两次必须全等。world-gen 的随机种子尾巴两种都要裁掉——单体「按以下种子生成世界」、
  融合「按以下种子生成融合世界」(engineering-practices §3:第四第五次假红都是漏了融合那句)。
- 改前构建(`a6e0b0f`)、改后构建**各自重新编译**,各 dump 一次,逐个世界列 diff。正对照天然存在(每个世界两边都不同),
  不落入 engineering-practices §4「失去正对照」;但仍要在报告里写出「改前改后两份 jar 的提交号不同」作为证据。
- **预期**:
  - world-gen:每个世界只多 ① tone 那一行、② 【现实边界 · 硬约束】块(小标题 + 一行 + 前置空行);《派单》另外少 `:317` 那一行;
  - 回合:每个世界、每个回合号只多 ② 那一块;
  - 其他字节零变化。
  - **任何一处不符合就停下报告,不解释掉。**

**(b) 单测**

- 新增 lockstep 守护(仿 `ContentSafetyPromptLockstepTest`):【现实边界 · 硬约束】块在回合单体、回合融合、world-gen 单体、
  world-gen 融合四个运行时面与两份 md 里逐字在场;tone 定义行在 world-gen 单体、融合与 `world-gen.md` 里逐字在场。
- 变异验证(engineering-practices §1,**分别**红,读红用例名):
  - 只删 `FUSION_SKELETON` 里的边界块 → 新 lockstep 红;只删 `SKELETON` 里的 tone 行 → 新 lockstep 红;
  - 把 world-gen 单体的边界块挪到【泄露硬约束】之后 → `WorldGenPromptBuilderTest:202` 红,且 `PaidanWorldGenTest` 的 golden 比对红;
  - 把 `FUSION_SKELETON` 的边界块挪到【泄露硬约束】之后 → `PaidanWorldGenTest` 的**融合** world-gen golden 比对红(Felix 2026-11-12 追加);
  - 把回合 `SKELETON` 的边界块挪离锚点(挪到【输出格式】段之后)→ `PaidanTurnTest` 回合 golden 比对红、`AnimalLifeLegacyGoldenTest` 红(Felix 2026-11-12 追加;
    回合骨架没有【泄露硬约束】,对应的「挪到后面」是挪过下一段);
  - 均还原。
- 勘察 5 表里的预期红逐条订正;订正后再跑全量。
- 引用测试数前先 `rm -rf server/target/surefire-reports` 再重跑(memory「量具里躺着尸体」)。

**(c) golden 怎么订正(已定,Felix 2026-11-12:按推荐剔除后比原金样本,不重录;另加锚定约束)**

- **不重录金样本,「剔除已知新增后再比」**。`PaidanWorldGenTest`、`PaidanTurnTest`、`AnimalLifeLegacyGoldenTest`
  在比对前从实际 prompt 里剔除本 ADR 新增的内容,再与原金样本逐字节比。
  理由:三组金样本都写明了出处(`3e72099` / Slice D 之前 / `583abc9`),重录会抹掉出处;剔除法让「只多这几行」本身成为常驻断言,
  而不只是一次性 dump 的结论。
- **剔除必须带上下文锚定**(Felix 2026-11-12 追加):用「前一行 + 新增内容」整串替换回「前一行」,并**断言该整串在 prompt 里恰好出现 1 次**;
  不允许在全文任意位置删掉新增行 —— 否则新增内容挪到别处也能被删干净、golden 照绿,位置不受守护(只有 world-gen 单体有 `:202` 兜着)。
  锚定的三条整串(测试侧逐字常量,与新 lockstep 共用同一份):
  | 新增 | 前一行(锚) | 整串 → 替换回 |
  |---|---|---|
  | tone 行(world-gen 单体) | `- world:{ title, background, tone 用中文;dangerLevel ∈ {low,medium,high,extreme} 取种子给定值 }` | 前一行 + `\n  - ` + tone 原话 → 前一行 |
  | tone 行(world-gen 融合) | `- world:{ title, background, tone 用中文;dangerLevel ∈ {low,medium,high,extreme} }` | 同上 |
  | 边界块(三份骨架) | `不进入「具体怎么做」。`(【绝境的写法】末行) | 前一行 + `\n\n【现实边界 · 硬约束】\n` + 边界原话 → 前一行 |
- 《派单》world-gen 删掉的【标题下那一行】不涉及:`PaidanWorldGenTest` 的 golden 比对只比**其他世界**,《派单》自己不比 golden;
  它的删除由改写后的 `:105` 断言(不含该行)守护。
- 备选(未采纳):用改后构建重录全部金样本。

## 关键理由

1. **一处定义、所有世界生效**:tone 和题材边界都是「所有世界都该守」的东西,按 ADR-008 归骨架;这正是 ADR-036 当时记下的待办的终点。
2. **复用【绝境的写法】的维护形态**:同样是一段跨三份骨架、两份 md 的公共硬约束,lockstep 与变异验证都有现成范式。
3. **只新增不改行,让 diff 可预测**:dump diff 能精确到「每个世界只多这几行」,任何意外字节都会显形。
4. **最小公共子集**:只放所有世界都接受的那几条;更严的题材限制是世界的设计,不该由骨架强加给修仙、克苏鲁、动物人生。
5. **固定世界名只给需要的世界**:《寻常》的世界名就是「寻常」;《动物人生》的故事名本身是特色。可空字段让两种需求共存。

## 已知代价

1. **提示词改了但没有经过真 key 验证**(Felix 已定不跑冒烟)。tone 会不会仍写成氛围词、叙事里会不会仍出现现实品牌或政治影射,都没验过。
   **缓解方式**:上线后观察 —— Felix 下次玩任何世界时,看标题下那一行是不是一句介绍、叙事里有没有现实品牌或政治影射。
2. **《派单》槽里与公共子集重复的句子仍在**(勘察 3 的四处:world-gen `:350`「不写政治隐喻」、`:361`「现实品牌」,回合 `:462`、`:477` 同)。
   《派单》的 prompt 里这两点会说两遍,措辞还不完全一样(「现实品牌」vs「现实品牌和商号」)。Felix 本刀未下删除指令,保留;
   预期无害(同向、不冲突)。要删另开一刀。
3. **旧存档不变**:续局读盘上的 `world`,不重新生成。旧局的 tone / title 照旧 —— 线上已有的《寻常》局标题下仍显示写作要求,标题仍是模型当时起的名字。
   `FixedWorldTitle` 只在 world-gen 时覆写,读档路径不经过它(ADR-036 Slice A2)。
4. **《寻常》设定在真实年代,禁品牌可能削弱年代质感**:1985 北方工业城、1992 南方县城这类种子,原本可以靠某个牌子的自行车、
   某种汽水唤起年代感;「不出现现实品牌和商号」之后只能写物件本身。这是创作代价,如实记下,不缓解。
5. **`fixedWorldTitle` 只管 `world.title`**:`background` 与开场叙事里模型自己起的称呼不受它管(同 ADR-036 已知代价 9)。
   《寻常》的 worldview 以「《寻常》——」开头,但指令槽里**没有**《派单》那样的「world.title 必须恰为…,background 里也这样叫」的软层提示词行;
   本 ADR 不加(Felix 没下这个指令)。若上线后观察到开场里出现别的世界名,再考虑补。
6. **修复提示不带边界**:world-gen 修复与回合修复发都是独立短 prompt,不含骨架(今天也不含【绝境的写法】)。修复发的产出若重写了玩家可见文字,
   那一次不受边界约束。概率低(修复发通常只改结构字段),本 ADR 不处理。
7. **每回合 prompt 多一小段**:约 60 个汉字 + 小标题,每回合都付。token 成本略增,未量化。
8. **边界只靠提示词**:程序层没有任何拦截;ADR-004 流末扫描只记自伤命中,不扫品牌或政治词。现实品牌是开放集合,无法列表 grep。
9. **CONTEXT §三.17 有一句立字会失效**:ADR-028 补充段「回合 prompt 与 `583abc9` 逐字节相同」(旧局《动物人生》)。
   决策 2 之后旧局也拿到边界块,这句只在「剔除公共边界块之后」成立。**Felix 2026-11-12 已定接受**:改成「剔除公共边界块后逐字节相同」,
   第 2 刀(文档回写)按 CONTEXT 补充块先例加订正块,原文不动。
10. **golden 剔除清单会随骨架改动累积**(Felix 2026-11-12 追加):每次往骨架新增公共内容,三组 golden 测试就要多一层「锚定剔除」;
   层数越多,测试离「原金样本逐字节」越远,读起来也越难。**规则:超过两层就改为重录**,出处改记为当次实现 commit。
   本 ADR 是第一层。

## 重新审视的触发条件

- 上线后观察到任一世界标题下那一行仍是写作要求、风格说明或氛围词堆叠 → 先查该世界 worldview 有没有可被照抄的风格句(《寻常》那句在列),
  再考虑把 tone 的要求升为 world-gen 后的服务端检查(CONTEXT §三.18 (c) 的形状)。
- 叙事、选项或 world 字段里出现现实品牌、商号、在世人物或可指认的现实政治影射 → 停止对外开放该世界并另立 ADR(同 ADR-036 重新审视条件第 5 条)。
- 《寻常》的年代质感在 Felix 体感里明显变薄 → 重新评估「品牌」在《寻常》里的处理(例如允许已不存在的老字号?需另行裁定,不在本 ADR)。
- 新增世界或融合组合时,其指令槽想写与公共子集冲突的东西(例如需要点名历史人物)→ 先回到本 ADR 讨论公共子集是否要收窄,不在槽里覆盖骨架。
- 《动物人生》的故事名被玩家反馈为混乱(例如存档列表里认不出是哪一局)→ 重新评估决策 3 对《动物人生》的不登记。

## 落地切分(定稿时只写不执行;两刀均已完成,见「实施记录」)

1. **实现刀**(一个 commit,单独可编译可测):
   - `WorldGenPromptBuilder.SKELETON` / `FUSION_SKELETON` 各加 tone 行与边界块;`TurnPromptBuilder.SKELETON` 加边界块;
   - `PAIDAN_WORLD_GEN_DIRECTIVE` 删【标题下那一行】;类注释同步(`:304–305` 提到 tone 一条);
   - `prompts/world-gen.md` / `prompts/event-loop.md` 同步,两份 md 版本行各升一版;
   - `ArchetypeRegistry.ordinaryLife()` 末参加 `"寻常"`;
   - 测试:新 lockstep、勘察 5 表逐条订正、golden 按决策 5(c) 定下的方式处理;
   - 验证:决策 5(a) dump diff(harness 不进提交)+ 全量 `mvn test` + golden parity。
2. **文档回写刀**(按先例,纯 docs;**Felix 核过第 1 刀之后再做**):本 ADR 补实施记录并改状态;ADR-036 已知代价 9 与「另开一刀的待办」两处后加 ⚠️ 指针;
   CONTEXT §三.17 ADR-028 补充段加订正块(已知代价 9),视需要在 §三.18 或 §三.7 附近补一条「公共题材边界在骨架」的约定(升 CONTEXT 版本);
   ROADMAP 记一笔;README ADR 列表状态更新。

不准顺手做:删《派单》槽里的重复句;改任何 worldview;给《寻常》加世界名软层提示词行;动修复提示;跑真 key 冒烟。

## Felix 裁定清单(草案时「待 Felix 审定清单」;Felix 2026-11-12 逐条定下)

1. **tone 行的位置与格式** —— **已定**:按推荐,作为 `- world:{…}` 的子条目(两空格 + `- `)。(决策 1)
2. **边界块的小标题与位置** —— **已定**:位置按推荐(【绝境的写法】块之后、【泄露硬约束】/【输出格式】之前);
   小标题用备选 **【现实边界 · 硬约束】**(三条约束都是关于现实的;避开与《派单》槽里【题材边界】重名)。原话一字不动。(决策 2)
3. **CONTEXT §三.17「与 `583abc9` 逐字节相同」** —— **已定**:按推荐,接受改成「剔除公共边界块后逐字节相同」,实施时用补充块订正,原文不动。(已知代价 9)
4. **golden 订正方式** —— **已定**:按推荐,剔除后再比原金样本,不重录。追加三条约束:
   剔除带上下文锚定、整串恰好 1 次(决策 5(c));变异验证加「挪到【泄露硬约束】之后,回合 golden 与融合 world-gen golden 也必须分别红」(决策 5(b));
   已知代价补「剔除清单累积,超过两层改为重录」(已知代价 10)。

## 实施记录

### 第 1 刀 · 骨架 + 槽 + md + registry + 测试(2026-11-12,基于 `ef1b649`,实现 commit `d2bec94`)

勘察与本 ADR 一致,未发现冲突。Felix 2026-11-12 静态校勘核过(原话逐字、三份骨架位置、《派单》删行、锚定剔除、md 同步、lockstep、变异)。

- **源码**:`WorldGenPromptBuilder.SKELETON` / `FUSION_SKELETON` 各加 tone 子条目与【现实边界 · 硬约束】块;`TurnPromptBuilder.SKELETON` 加边界块;
  `PAIDAN_WORLD_GEN_DIRECTIVE` 删【标题下那一行】;`ArchetypeRegistry.ordinaryLife()` 末参加 `"寻常"`;三处类注释同步。
- **md**:`prompts/world-gen.md` v0.11、`prompts/event-loop.md` v1.10。
- **测试**:新增 `CommonPromptAdditions`(逐字常量 + 锚定剔除,整串恰好 1 次,失败信息带世界名)与 `CommonBoundaryPromptLockstepTest`(7 条,世界清单从 registry 实数);
  `PaidanWorldGenTest` / `PaidanTurnTest` / `AnimalLifeLegacyGoldenTest` 改为锚定剔除后比原金样本(未重录);《派单》tone 断言、两处 fixedWorldTitle 断言订正。

**dump diff 读数**(临时 harness,用完即删、未提交):

- registry 实数:7 个单体 + 3 个融合。dump 118 份 = world-gen 10 + 回合 100(每个世界 10 个回合号,同 `PaidanTurnTest`)+ 《动物人生》旧局 8。
- 改前(`ef1b649`)、改后各自 `clean` 编译;同一构建 dump 两次全等(两次都验);单体、融合两种种子尾巴都裁掉。
- 逐份 diff:world-gen 10 份每份只多 tone 子条目与边界块,《派单》另少【标题下那一行】;回合 100 份与旧局 8 份每份只多边界块。118 / 118 符合预期。
- 反证:按决策 5(c) 的锚定整串剔回后,118 份与改前**逐字节相等**。

**变异验证**(每次只改一处、各自还原;跑 `CommonBoundaryPromptLockstepTest`、`ContentSafetyPromptLockstepTest`、`PaidanWorldGenTest`、`PaidanTurnTest`、`AnimalLifeLegacyGoldenTest`、`WorldGenPromptBuilderTest` 共 45 例):

| 变异 | 变红的用例 |
|---|---|
| 删 `FUSION_SKELETON` 的边界块 | `CommonBoundaryPromptLockstepTest.fusionWorldGenPromptsCarryBoundary`;`PaidanWorldGenTest.existingWorldGenPromptsAreByteIdenticalExceptTheSeedLine` |
| 删 `SKELETON` 的 tone 行 | `CommonBoundaryPromptLockstepTest.singleWorldGenPromptsCarryToneLine`;`PaidanWorldGenTest` 的 golden 比对与 `paidanPromptCarriesRulesEndingsAndWorldName` |
| 单体 world-gen 边界块挪到【泄露硬约束】之后 | `WorldGenPromptBuilderTest.worldGenDirectiveSlotIsEmptyForFourWorldsAndLeavesNoBlankLine_ADR020`(`:202`);`PaidanWorldGenTest` 的 golden 比对 |
| 融合 world-gen 边界块挪到【泄露硬约束】之后 | `PaidanWorldGenTest` 的 golden 比对,红于 `[cultivation_x_rules_creepy]`(锚定整串 0 次) |
| 回合边界块挪到【输出格式】之后 | `PaidanTurnTest.existingWorldsAndFusionsAreByteIdenticalToSnapshotsFromBeforeSliceD`,红于 `[rules_creepy_T1.txt]`;`AnimalLifeLegacyGoldenTest` 两例,红于 `[legacy T3]` / `[fresh T3]` |

**全量**:先 `rm -rf server/target/surefire-reports`,`./mvnw clean test`:**968 例,0 失败 0 错误 0 跳过**(含新增 7 例;改前总数未单独跑)。
日志里的「APPLICATION FAILED TO START」来自 `PgStartupRefusalTest` 的有意拒启。前端未改,未跑 web。

**⚠️ 已知缺口(如实记,不补)**:《派单》自身不在任何 golden 内(`PaidanWorldGenTest` 的 golden 比对与 `PaidanTurnTest` 都跳过 `cyberpunk`)。
它 world-gen prompt「只少【标题下那一行】、只多 tone 行与边界块」这件事,只有本刀的一次性 dump 读数,加上 `PaidanWorldGenTest` 的
`doesNotContain("【标题下那一行】")` / `contains(TONE_LINE)` 与 lockstep 的 `contains` 兜着 —— **没有逐字节的常驻断言**。
将来有人改《派单》槽里别的行,不会有测试报「变了」。

### 第 2 刀 · 文档回写(2026-11-12,基于 `d2bec94`;纯 docs,零代码)

- 本 ADR:实施记录 + 状态改为已实施。
- [ADR-036](ADR-036-cyberpunk-world-paidan.md):已知代价 9 末句、「另开一刀的待办」两处后各加一行 ⚠️ 指针指向本 ADR,原文不动。
- [CONTEXT](../CONTEXT.md):§三.17 ADR-028 补充段加订正块(「与 `583abc9` 逐字节相同」改读为「锚定剔除公共现实边界块后逐字节相同」);
  §三.18 之后补一条约定 §三.19「tone 定义与公共现实边界在骨架,更严的边界留各世界指令槽」;CONTEXT 升 v2.7。`schemaVersion` 不动(仍 "0.4")。
- ROADMAP:Week 10 记一笔;「上线后观察」登记两项(标题下那一行是不是介绍句;叙事里有没有现实品牌或政治影射)。
- README ADR 列表状态更新为「已实施」。

## 实际效果(事后补充)

*Felix 上线后第一次玩任一世界时回填:标题下那一行是不是一句从玩家处境说起的介绍;叙事 / 选项里有没有现实品牌、商号、在世人物或政治影射;
新开《寻常》局的标题是不是「寻常」;《寻常》的年代质感有没有明显变薄。*

## 跟其他文档的交叉引用

- **待办出处**:[ADR-036](ADR-036-cyberpunk-world-paidan.md) 勘察 5 补记 ①、已知代价 9、「待 Felix 定稿清单」第 5 项与「另开一刀的待办」;订正 5(《派单》tone 首次修法)。
- **骨架单点维护**:[ADR-008](ADR-008-multi-mode-extension-architecture.md)(安全规矩全在骨架);[ADR-020](ADR-020-lifetime-world-family-and-ordinary-life.md) §10(指令槽「读几次」判据)、已知代价 5。
- **同形先例**:[ADR-004](ADR-004-content-safety.md) 刀 2【绝境的写法 · 硬约束】(跨三份骨架 + 两份 md 的公共硬约束,`ContentSafetyPromptLockstepTest`)。
- **固定世界名机制**:ADR-036 Slice A2(`ArchetypeMeta.fixedWorldTitle`、`worldgen/FixedWorldTitle`)。
- **旧局逐字节立字**:[ADR-028](ADR-028-box-scene-changeable-left-behind.md) 刀 2b、CONTEXT §三.17 补充段、`AnimalLifeLegacyGoldenTest`。
- **验证方法**:`docs/engineering-practices.md` §1(变异分别红)、§3(dump harness 两种种子尾巴 + 自检)、§4(正对照)。
- **源文件**:`server/.../worldgen/WorldGenPromptBuilder.java`、`server/.../eventloop/TurnPromptBuilder.java`、
  `server/.../archetype/ArchetypeRegistry.java`、`web/src/features/game/SceneBanner.tsx`、`prompts/world-gen.md`、`prompts/event-loop.md`。
