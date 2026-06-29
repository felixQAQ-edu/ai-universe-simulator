# Bake-off 发现日志(ADR-001)

记录 bake-off 过程中暴露的 schema / 约定 / 提示词问题,供回填 ADR-001 §3/§7 与
迭代 CONTEXT v0.1 时参考。每条含:现象、根因、影响面、处置。

---

## F-001 · `endings[].id` 类型混淆(schema 歧义,非模型能力问题)

- **日期**:2026-06-17 | **provider**:DeepSeek V4-Flash(非思考)| **步骤**:world-gen
- **现象**:首跑 JSON 首次有效率仅 78.9%、修复后 94.7%(均未达 ADR §3 通过线)。
  失败**全部**集中于一点:模型把 `endings[].id` 输出成整数 `1,2,3`,schema 校验报
  `endings/N/id: 1 is not of type 'string'`。8 次 world-gen 首次失败全是此因,修复重试
  救回 6 次,仍有 2 次连重试都没把 int 改成 string。event-loop 30 回合 100% 干净。
- **根因(schema 层,不只是 prompt)**:CONTEXT v0.1 里两个 `id` 类型不一致——
  `rules[].id` 是**整数**、`endings[].id` 是**字符串**(示例 `"survive_dawn"`)。
  这种“同名字段不同类型”对模型是天然陷阱,它倾向把同一结构里的 id 都当整数。
  问题根在**约定设计的一致性**,提示词修复只是治标。
- **影响面**:任何 endings 生成步骤;换 provider 大概率复现(是结构歧义不是某模型的毛病)。
- **本次处置(治标)**:在 `prompts/world-gen.md` 显式声明
  “`endings[].id` 必须是 snake_case 英文字符串,与 rules[].id(整数)区分”。
- **建议(治本,待 ADR/CONTEXT 决策)**:二选一,下次修订 CONTEXT v0.x 时定:
  - (a) 统一:`endings[].id` 也改成整数,面向玩家的稳定标识另用 `slug` 字段;或
  - (b) 统一:两处 id 都用字符串。
  在 schema 层消除“同名异型”,从源头降低 JSON 失败率,而非靠每个提示词反复叮嘱。
- **关联指标**:ADR §3 #1(JSON 有效率)。
- **复跑验证(2026-06-17,改 prompt 后)**:`endings/N/id` 整数错误**完全消失**,
  修复后有效率 94.7% → **100%**。证实根因判断正确。但首次有效率仍 81.1%(见 F-002)。

---

## F-002 · harness 盲点:首次失败的原始响应未落盘

- **日期**:2026-06-17 | **现象**:F-001 修复后首次有效率仍只 81.1%(7 次触发修复重试),
  但**无法回看这 7 次首次失败的原因**——`scenarios._generate()` 只返回并落盘“修复后”的
  记录,首次失败的 `raw` 被覆盖,`calls.jsonl` 里看不到原始错误。
- **根因**:harness 设计——修复重试用新 CallRecord 替换了原记录,没有保留 first-pass。
- **影响**:无法定位剩余 ~19% 首次失败的真因,挡住继续把 JSON 有效率推到 ≥98%。
- **建议处置(harness 侧,待办)**:`_generate()` 在触发修复时,把首次失败记录也
  追加进明细(标 `attempt=1, schema_ok=False`),修复记录标 `attempt=2`。
  这样才能按 schema_errors 聚类首次失败、针对性改 prompt 或 schema。
- **关联**:F-001、ADR §3 #1。
- **✅ 已处置(2026-06-17)**:`_generate()` 加 `sink` 参数,触发修复时把首次失败
  (`attempt=1`)落进明细;report 区分“物理调用 / 逻辑生成步”,JSON 有效率按逻辑步算。
  立刻见效——复跑首次失败可回看,定位到 F-004(endings.title 缺失)。

---

## F-003 · 引擎“san/hp 不得回升”一致性核对可能过严(语义待定)

- **日期**:2026-06-17 | **现象**:B 组复跑出现多条 `san 回升` 一致性告警
  (如 B1 T3 san 70→75),导致指标 #2 判 ❌。
- **疑问**:规则怪谈里 san 适度恢复(安全屋/休息/解谜成功)是否就是“矛盾”?
  当前 `Engine.apply()` 把**任何** hp/san 上升一律记为问题,可能误伤合理恢复。
- **影响**:指标 #2(连推 10 回合自洽)判定偏严,可能低估模型表现。
- **建议处置(待 CONTEXT/ADR 决策)**:明确数值语义——是“只减不增”还是“允许有据恢复”。
  若允许恢复,引擎应改成“校验 stateUpdate 与 narrative/规则后果是否自洽”,
  而非简单禁止上升。本条不改模型,改的是验收口径。
- **关联**:ADR §3 #2、CONTEXT §三.5(数值范围)。
- **✅ 已处置(2026-06-17)**:口径改为“允许有据恢复”——`Engine.apply()` 不再把任何
  回升判为矛盾,只在单回合 hp/san 跳变 > `JUMP_THRESHOLD`(默认 40)时标“需复核”;
  同时把数值 clamp 到 0–100。复跑 B 组一致性问题清零,指标 #2 由 ❌ 转 ✅。
  注:阈值 40 是工程默认,数值语义最终以 CONTEXT/ADR 决策为准。

---

## F-004 · world-gen 漏 `endings[].title`(改用 `description`)+ 偶发漏 `character.attributes`

- **日期**:2026-06-17 | **provider**:DeepSeek V4-Flash | **步骤**:world-gen
- **现象(F-002 落盘后才看见)**:F-001 修好后首次有效率升到 86.8%,剩余首次失败 5/5
  全在 world-gen:4 条报 `endings/N: 'title' is a required property`,1 条报
  `character: 'attributes' is a required property`。
- **根因**:模型给 endings 输出了更丰富的 `description`(整句结局描述)却**漏了 schema
  要求的 `title`**(短标题);偶发把 `character.attributes` 整个漏掉/换层级。
  又是“模型自发产出 vs schema 约定”的字段错配——`description` 其实比单 `title` 更有用。
- **影响**:挡住把 JSON 首次有效率从 86.8% 推到 ≥98%。
- **建议处置**:
  - (治标,提示词)world-gen 显式要求 endings 同时给 `title`(短)与必填 `character.attributes`;
  - (治本,schema)下版 CONTEXT 给 endings 增加可选 `description` 字段(承认模型偏好),
    `title` 仍必填但强调“短名”;明确 character 结构层级,降低漏字段概率。
- **关联**:F-001(同类:模型产出与 schema 错配)、ADR §3 #1。
- **✅ 已处置(2026-06-17,CONTEXT v0.2 收敛)**:CONTEXT 升 v0.2——endings 增可选
  `description`、`title` 改“短名必填”、`character.attributes` 标必填,并明确两个 id 的刻意
  差异;同步 `schema.py` / `prompts/world-gen.md` / mock。复跑首次有效率 **86.8% → 97.1%**
  (修复次数 5 → 1),过软门 ≥90%;修复后仍 100%。残留唯一首次失败转移到 event-loop
  偶发 `availableActions: []`(1/34,经修复重试已纠正,属长尾,暂记观察)。

---

## F-005 · 重复种子致世界沉浸感套路化(质量短板,挂 Phase 1)

- **日期**:2026-06-18 | **provider**:DeepSeek V4-Flash | **步骤**:world-gen
- **现象(人工盲评 ADR-001 §6 暴露)**:8 个盲评世界里,「沉浸感与氛围」维度
  世界组均分仅 **3.25(< 3.5 软线)**,是五维中唯一触线项;其余四维均 4.4–5.0。
- **根因——种子套路化**:A1 固定种子(雨夜便利店)跑 5 次 + A2 才换场景,导致
  盲评样本里便利店占多数,且同种子多次生成趋同、不够“瘆人”。按场景分组看很清楚:

  | 分组 | 样本 | 沉浸感均分 | 综合均分 |
  |---|---|:--:|:--:|
  | 便利店(W-A/B/E/G/H) | 5 | **2.6** | 4.2 |
  | 非便利店(W-C 民宿 / W-D 医院 / W-F 地铁) | 3 | **4.33** | 4.6 |

  非便利店场景沉浸感(4.33)显著高于便利店(2.6);文笔/连贯/巧思各组都不差。
  即**不是模型写不好,是同一种子反复生成同一类世界 → 桥段重复、惊吓套路化**。
- **影响**:纯质量短板,不涉 schema/工程,不影响 ADR-001 主力选型(综合 ~4.4 已达标)。
- **建议处置(Phase 1 world-gen 提示词待办)**:
  1. 提示词加“反套路”约束:显式要求规则/惊吓桥段多样化,避免“红雨衣/白卡片/监控倒影”等
     高频套路堆叠;可注入“避免与以下已用桥段雷同”的负样本列表。
  2. 种子层多样化:world-gen 入参带场景/主题/惊吓母题的随机维度,降低同质化。
  3. A2 多样性测试常态化:盲评样本按场景均衡抽样,别让单一种子主导沉浸感评分。
- **关联**:ADR-001 §3 #4 / §6、bakeoff 场景组 A2、Phase 1 提示词工程。

---

## F-006 · DeepSeek 思考开关 `thinking.type=disabled` 实测有效(解掉 ThinkingAdapter 的 VERIFY 占位)

- **日期**:2026-06-19 | **provider**:DeepSeek V4-Flash | **步骤**:Phase 1 接真实 DeepSeek 集成冒烟
- **现象**:直连 `deepseek-v4-flash`(未带思考开关)时,**默认走思考模式**——早期 chunk 全是
  `delta.reasoning_content` 有值、`delta.content=null`,模型先吐"内心独白"再给答案。而 server
  端经 `ThinkingAdapter` 发 `{"thinking":{"type":"disabled"}}` 后,同一 prompt 直接吐纯 `content`、
  零 reasoning(冒烟输出「雨夜便利店,灯光惨白如殡仪馆。」逐字流式)。
- **意义**:
  1. ADR-001 §5.2 里 DeepSeek 思考参数原是 bakeoff 沿用的 **VERIFY 占位**(`_thinking_extra_body`
     注释标"占位待核")——本次实测证实 `thinking.type=disabled` 真实生效,**占位转已实测**。
  2. 顺带验证 `OpenAiStreamDecoder` 只取 `delta.content`、忽略 `reasoning_content` 的设计是对的:
     哪怕将来误开思考,模型推理过程也不会泄给玩家(契合规则怪谈 `hiddenLogic` 绝不泄露的纪律)。
- **影响面**:DeepSeek 系 provider 的思考开关已可信赖;Qwen(`enable_thinking`)/ GLM(`thinking`)
  两支仍为待实测占位,接通各家时同法验证。
- **处置**:`server` 端 `ThinkingAdapter` 注释 DeepSeek 分支 VERIFY 占位 → 已实测(本条交叉引用);
  本条记入 FINDINGS。**未改任何参数值**(冒烟即用现值跑通)。
- **关联**:ADR-001 §5.2、`server/.../llm/ThinkingAdapter.java`、`OpenAiStreamDecoder`。

---

## F-007 · Boot 4.1 锁 Jackson 3,库选型须 Jackson-3-native(否决 networknt）

- **日期**:2026-06-20 | **步骤**:Phase 1 event-loop 内核移植(第一批,选 JSON-schema 校验实现)
- **现象**:Spring Boot 4.1 实测带 `tools.jackson.core:jackson-databind:3.1.4`(Jackson 3),依赖树里
  唯一的 `com.fasterxml` 是 `jackson-annotations`(annotations-only,无 databind/JsonNode)。而
  networknt json-schema-validator 基于 Jackson 2,其 API 吃 `com.fasterxml.jackson.databind.JsonNode`。
- **意义**:引入这类「Jackson-2-native」库会造**双 Jackson 世界**(同一份 JSON 要按两套 `JsonNode`
  各 parse 一次),并直接顶撞本批「event-loop 回灌走单一 `tools.jackson` ObjectNode 层、不卡类型化 DTO」
  的决策——校验/回灌/引擎本应共用一棵节点树。
- **影响面**:event-loop 校验器、以及今后任何在 `server` 侧碰 JSON 节点的库选型。
- **处置**:event-loop 两份 schema(WORLD/TURN)是封闭小集合(required/type/enum/range/minItems/minLength)→
  **手写校验器**(`GameSchemas`,~120 行,直接吃 `tools.jackson.JsonNode`,零新依赖、单一 Jackson)。
  约束:今后任何库选型先验 **Jackson-3-native**,否则一票否决。
- **关联**:`server/.../engine/GameSchemas.java`、ADR-005(单一 ObjectNode 接缝)、Phase 1 event-loop 规格 §4.4/§6。

---

## F-008 · Java 引擎有意偏离 bake-off Python:ending id 存在性 gate(依 §4.4,golden-safe)

- **日期**:2026-06-20 | **步骤**:Phase 1 event-loop 内核加固(结局三子分支单测)
- **背景**:bake-off `scenarios.py` 的 `Engine.apply()` 对 AI 提议的结局,只要 `ending.reached==true`
  就 `status=ended`,再遍历 `endings[]` 标 `reached`——但**若 `ending.id` 不在 `endings[]`,则 status
  已 ended 却标不到任何 ending**。
- **现象/判断**:这不是设计,是个**潜伏 bug**——前端会拿到一个「没有对应 ending 对象的幽灵结局」
  (无 title/description 可显)。规格 §4.4「`ending.id` 须存在于 world `endings[]`」才是真正的契约;
  Python 当初只是没覆盖这个缺口(bake-off 连推从未触发结局,golden 三路径亦全程 ongoing)。
- **处置**:Java `Engine.apply()` 把结局接受 **gate 于 id 存在性**——幽灵 id 不 flip `status`、不标任何
  ending;触底兜底改用 `aiAccepted`(替 `aiReached`)判断,幽灵 id + 触底 → §5 兜底接管。
  **golden 无 ending,故 parity 不受影响**(golden-safe)。
- **取向**:「忠实移植 Python」只是信任 bake-off 验证的**手段**,非目的;**契约说了算**。引擎对齐 §4.4 正确。
- **备注**:留此条是防将来谁重跑 parity 看到 **Java ≠ Python** 误判为移植 bug——这是有意偏离,非回归。
- **关联**:`server/.../engine/Engine.java`(apply 第 9–10 步)、`EngineEndingTest`、Phase 1 event-loop 规格 §4.4/§5、commit `4e66056`。

---

## F-009 · world-gen 种子聚集 + 开场雷同(F-005 延伸,真 key 整局冒烟暴露)

- **日期**:2026-06-22 | **provider**:DeepSeek V4-Flash | **步骤**:Phase 1 整局闭环真 key 冒烟(world-gen init ×5)
- **现象**:5 次 `POST /api/game/init` 产出场景分布 = 民宿 ×2(均 medium)/「永夜病房」×3(均 extreme),
  便利店、末班地铁各 0;且 3 个「永夜病房」开场散文高度雷同(同为凌晨三点住院部、护士站无人、电子钟秒针倒转)。
- **根因**:`WorldGenPromptBuilder.SEED_POOL` 仅 4 条场景 + `ThreadLocalRandom` 等概率抽——小样本下扎堆,
  且单一种子多次生成趋同(正是 F-005 的根因在生产侧复现:不是写不好,是种子空间窄 + 同种子产出同质)。
- **影响**:纯质量/多样性短板,不涉 schema/工程/闭环正确性(格式完整性 5/5、首次有效率 100%)。**非闭环阻塞项。**
- **建议处置(挂 Phase 2 打磨,非本轮)**:① 扩 `SEED_POOL`(更多场景/主题/惊吓母题维度);
  ② 开场 anti-repetition——种子注入「避免与近期已用桥段雷同」负样本,或随机母题维度降同质化(承 F-005 建议 1/2)。
- **关联**:F-005(同根:重复种子致套路化)、`server/.../worldgen/WorldGenPromptBuilder.java`、`prompts/world-gen.md`、
  真实录制 `bakeoff/recordings/world-gen-smoke/`(5 world raw)。

---

## F-010 · §5 引擎强制兜底结局:真 key 未命中(诚实曝光缺口,接受不强造)

- **日期**:2026-06-22 | **provider**:DeepSeek V4-Flash | **步骤**:Phase 1 整局闭环真 key 冒烟(危险动作驱 hp/san 触底)
- **背景**:规格 §5 补丁——数值触底(`hp<=0`/`san<=0`)但 **AI 未提议结局**时,引擎强制 `ended` 并兜一个坏结局 id
  (`forceBottomOutEnding`,挑 `endings[]` 首个 condition 含 san/hp 的条目;找不到用首条)。
- **现象**:3 局危险驱动里 2 局触底(init-3 san→0、init-4 san→0),均干净 `ended` + ending 事件带 id/title/description
  到客户端——但**两局都是 AI 主动提议结局**(`lost_in_mirror` / `lost_mind`),经 §4.4 id 存在性 gate 接受;
  §5 兜底**未被命中**(判据:§5 会挑首个 cond 含 san 的 `survive_dawn`,实际下发的却是 AI 给的死亡结局 ≠ 兜底会挑的)。
- **判断**:真实 DeepSeek 在杀死玩家时**稳定地主动提议死亡结局** → §5 这层防御网在「模型不失灵」时本就不点火。
  即**触底→ended→ending 事件端到端已真 key 验**(经 AI 提议路径),但 §5「AI 给 null 时引擎补一个 id」这一子分支
  无法按需复现。
- **取向**:**接受、不强造**——人为逼模型失灵(如篡改提示让它故意不给 ending)属测试污染,不做。§5 是防御网,
  模型没失灵就不该亮;其正确性已由 golden + `EngineEndingTest`(触底无匹配→约定 fallback / 幽灵 id 不接受)
  **确定性覆盖**。本条记为**诚实的真实曝光缺口**(real-key coverage gap),非缺陷、非回归。
- **复现条件(若将来要真 key 验)**:需一局触底且 AI tail `ending:null`——只能靠运气撞,或等接入更弱/更易失灵的 provider 时顺带观测。
- **关联**:`server/.../engine/Engine.java`(apply 第 10 步 `forceBottomOutEnding`)、`EngineEndingTest`、规格 §5、
  F-008(同涉 §4.4 ending gate)、真实录制 `bakeoff/recordings/world-gen-smoke/`(game-init3/4-danger.sse)。

## F-011 · 末日真实 raw + SSE 转录接进测试夹具(替换合成数据,backlog 独立小活)

- **日期**:2026-06-24 | **provider**:DeepSeek V4-Flash | **步骤**:Phase 2 第一批·末日生存真 key 冒烟
- **背景**:Phase 2 第一批的末日测试用的是**合成/构造数据**——`GameInitServiceTest.apocalypseWorld()`(手写末日 raw)、
  `EngineKeyAgnosticTest`(手搓 `{hp,hunger}` turn)、`StatsPanel.test.tsx`(合成 axes)。规则怪谈侧有 golden /
  validator-parity 真实录制兜底,末日侧暂无对应的**真实产出夹具**。
- **现象**:真 key 冒烟跑出了末日真实产出(world-gen raw + 多回合 SSE 转录,Felix 本地 `/tmp/apoc-init-*.json` 等),
  其中含 AI 落 hunger 衰减(40→0/每回合 -5)、触底 `starved_to_death` 端到端 —— 这些是末日侧**首批真实形态样本**。
- **取向(backlog,非本批)**:把这几发末日真实 raw + SSE 转录沉淀成测试夹具,替换/补强末日侧的合成数据
  (仿规则怪谈 golden parity / validator-parity 的做法,给末日也建一份真实-产出 parity),提升末日侧回归护城河的真实性。
  **独立小活**,与「选择 UI 小批 / 第二个模式 / 衰减率微调」并列待排;不在 Phase 2 第一批范围。
- **注意**:`bakeoff/recordings/` 是既有真实录制区(规则怪谈),沉淀末日夹具时按既有约定组织;Felix 的 `/tmp` 样本
  是临时产物,需有意识地挑选/清洗后入库,不直接照搬。
- **关联**:`server/.../worldgen/GameInitServiceTest.java`(`apocalypseWorld`)、`EngineKeyAgnosticTest`、
  `web/.../StatsPanel.test.tsx`、规则怪谈对照 `bakeoff/replay_golden.py` / `gen_validator_parity.py`、ADR-008 决策 5。

## F-012 · 引擎「≤0 即触底死亡」假设只对 depletion 轴成立——accumulation 轴(克苏鲁 knowledge)需引擎正解(backlog,留修仙批)

- **日期**:2026-06-25 | **步骤**:Phase 2 加世界·克苏鲁(加世界流水线第一次复用,"流水线成色"测试)
- **背景**:克苏鲁签名轴 `knowledge`(禁忌知识)是**累积型双刃**(求知则上涨、越高 san 流失越快),
  与末日 `hunger`(衰减型)行为相反——正是用来验「流水线能否容纳不同行为的轴」的样本。
- **现象(架构信号)**:`Engine.apply` 第 10 步 `anyAttributeBottomedOut()` 对**任一**数值轴 `≤0` 即强制 `ended` +
  兜坏结局(`server/.../engine/Engine.java:275`)。这对 **depletion 轴**(hp/san/hunger,越低越危,0=死/疯/饿毙)正确;
  但对 **accumulation 轴**(knowledge,0=「一无所知」是**健康常见的开局态**)是**错的**——若 AI 把 knowledge 落到 0,
  引擎会**误判触底、把游戏强制 ended 并盖一个坏结局**。引擎对数值 key 语义无知(ADR-008 决策 1)的代价在此第一次具体暴露:
  「≤0 即死」本身就是一种 depletion 语义,被无差别套到了所有轴上。
- **影响面(非克苏鲁特有)**:所有**累积/危险来自高位**的轴都会踩——修仙(修为/境界越高越招劫)、AI 觉醒(算力/暴露度)等。
  这是「轴角色(depletion vs accumulation)」缺失的通病,不是克苏鲁一个模式的坑。
- **处置(Felix 拍板·两段)**:
  1. **本批(克苏鲁)= 提示词约定兜,引擎一行不改**:world-gen 给 knowledge **正基线初值**(如 5–15、**绝不给 0**)、
     event-loop 注明 knowledge **累积只涨/持平、不无故回落、不落到 0**(承载在 `knowledge` 轴的 `behaviorHint`,
     world-gen/event-loop 两消费方同源读)。引擎/golden parity **零回归**、**不出新 ADR**,保本批"又短又顺"。
     代价:靠 AI 自律(同 hunger 衰减稳定性的口径,ADR-008 决策 2 哲学)——**列为本批冒烟专门观测项**
     (AI 有没有把 knowledge 误打到 0 触发误结局)。
  2. **引擎正解 = 留修仙批一起做 + 出 ADR**:给数值轴加**角色**(depletion vs accumulation,元数据驱动),触底判定**只对
     depletion 轴**;accumulation 轴的「死亡/失败条件」另设(如 knowledge 过高→疯,而非过低→死)。
     **推迟到修仙批**——届时有 knowledge + 修为/境界**两个累积轴样本**,设计更稳,不靠单点过度设计。
- **关联**:`server/.../engine/Engine.java`(`anyAttributeBottomedOut`/`forceBottomOutEnding`)、
  `server/.../archetype/ArchetypeRegistry.java`(克苏鲁 `knowledge` `behaviorHint` 承载正基线/不落0约定)、
  ADR-008 决策 1/2 + 「重新审视的触发条件」(非 0–100/非 depletion 语义的轴)、`docs/world-library-expansion-backlog.md`(修仙批)。
- **✅ 已关闭(2026-06-25,ADR-009 根治)**:数值轴增 `axisRole`(depletion/accumulation),引擎触底**按角色二分**——
  depletion 轴 ≤0 致死、**accumulation 轴 ≤0 不触底**(引擎只据播种层传入的累积轴 key 集合 gate,仍不懂具体轴语义)。
  现有轴标角色:hp/san/hunger/灵力=depletion,knowledge/境界=accumulation。克苏鲁 knowledge 的「提示词正基线兜」**升级为引擎根治**
  (此后累积轴零提示词补丁、零误触底)。golden parity 字节级守 depletion 零回归(2 参构造默认全 depletion = 现状)。
  修仙真 key 冒烟验通:境界 accumulation 累积不误死成立。commit `c8191db`。

## F-013 · world-gen 通用骨架强制 `rules[].isTrue` 与「非真假守则」型世界(克苏鲁)的 ruleForm 打架(backlog,留修仙批)

- **日期**:2026-06-25 | **provider**:DeepSeek V4-Flash | **步骤**:Phase 2 加世界·克苏鲁 world-gen 真 key 冒烟
- **现象**:克苏鲁首发 init 探针,world-gen **首过未过校验**(8 条 rules **全部缺 `isTrue`**),触发**一次修复后过**
  (修复产出有效世界,HTTP 200、三轴齐、消毒无泄露——玩家侧无感)。Felix 浏览器整局闭环验通,本条只是首过有效率的一次扣分。
- **诊断**:克苏鲁 `ruleForm` 写「禁忌知识在探索中渐揭(**非规则怪谈的一纸真假守则**)」,而通用骨架仍强制
  「rules 6–8 条**真假混合**,`isTrue` 有真有假」——两处口径冲突,模型**跟了 ruleForm 的「非真假」框架、丢了 `isTrue`**。
- **同类归因(与 F-012 同根)**:这是「**骨架的某个假设对新世界类型不成立**」的第二例——F-012 是引擎「≤0=死」假设对
  accumulation 轴不成立;F-013 是骨架「rules 必带真假 `isTrue`」假设对**非真假守则型规则世界**不成立。克苏鲁是第一个
  撞上的世界,**修仙 / SCP 等规则形态非「真假守则」的世界可能也撞**。
- **处置(Felix 拍板)**:**本批先接受偶发首过修复**(修复后照样出好世界,不影响玩家,不改提示词)。**引擎/骨架正解留修仙批
  一起做**——给骨架加「`isTrue` 对非真假规则世界**可选**」的口径,或按 `ruleForm` 分派 rules 形态(与 F-012 的轴角色正解
  同批解决,届时样本更多、设计更稳)。
- **关联**:`server/.../worldgen/WorldGenPromptBuilder.java`(SKELETON 强制 isTrue)、`prompts/world-gen.md`、
  `server/.../archetype/ArchetypeRegistry.java`(克苏鲁 `ruleForm`)、`GameSchemas.validateWorld`(isTrue 校验)、
  F-012(同类:骨架假设对新世界不成立)、`docs/world-library-expansion-backlog.md`(修仙批)。
- **✅ 已关闭(2026-06-25,ADR-009 根治)**:`rules[].isTrue` **全局改可选**(校验器零分派、不按 archetype 判,守 ADR-008 校验无知);
  world-gen 骨架据元数据 `rulesCarryTruth` 注入 rules 措辞(真假守则型仍要 isTrue / 心法守则型如修仙不输出 isTrue)。
  `schemaVersion` "0.2"→"0.3"(首次真动字段约束;`WORLD_SCHEMA` 接受双版本守 parity 夹具)。修仙真 key 冒烟验通:
  心法型 rules 无 isTrue **不再触发首过修复**。commit `ba8c56c`。

## F-014 · 结局判定与数值死活状态不匹配(濒死却给光明结局;**A+§5+B(ADR-010)根治、✅ 已关闭**)

- **日期**:2026-06-25 | **provider**:DeepSeek V4-Flash | **步骤**:Phase 2 修仙真 key 冒烟(Felix 浏览器,两局复现)
- **现象**:修仙人物 **气血 8 / 灵力 0、叙事明确濒死**(经脉欲断、丹田枯竭),结局却给「守园有功 / 大比夺魁」这类**光明好结局**——
  结局与人物当前数值死活状态完全不匹配。世界里明明有「灵力枯竭强行运功→经脉俱断」该导向坏结局。**两局都复现 = 系统性。**
- **诊断(代码逻辑链,未拿到该两局的 tail raw 故无法 100% 定位是哪一环,但根因明确)**:
  - **主因(最可能)= AI 提议了不匹配的成功结局,引擎 §4.4 照单全收**。`Engine.apply` 步骤 9:AI 在尾巴给 `ending{reached:true,id}`,
    引擎**只 gate `id` 是否存在于 `endings[]`**(`endingExists`),**不校验结局是否匹配死活/数值状态** → 接受、`status=ended`、
    `aiAccepted=true`;步骤 10 的 §5 兜底**因 `aiAccepted=true` 被跳过**。提示词侧:world-gen 造 `condition`、event-loop 说
    「命中某 `endings[].condition` 时给 ending」,**两处都没把"核心 depletion 数值濒零/濒死 → 只能给失败结局"传导进结局选择**。
  - **次因(真实潜伏 bug)= §5 兜底 `forceBottomOutEnding` 选错**。若 AI 给 `ending:null`、引擎因 depletion 轴(此处灵力=0)
    触底而兜底:`findEndingByConditionMentioning(key)` 用**英文轴 key**(`"mana"`)去匹配**中文 `condition`** → 真实世界几乎永不命中 →
    回落 `firstEndingId()`(`endings[0]`,常是成功结局)。这条在所有模式都在,只是**单轴模式 AI 稳定主动给死亡结局(F-010)故 §5 极少点火**而被掩盖。
- **为何多轴露馅、单轴没事**:修仙**境界(accumulation)高=「成功/强大」信号**与 hp/灵力 depletion 的「濒死」信号**冲突**,AI 倾向往
  「故事圆满」解读、给成功结局;规则怪谈/末日单轴 hp/san→0 是**无歧义的死亡信号**,AI 可靠给死亡结局。**根因(§4.4 只 gate id 存在性、
  不 gate 死活)是通用的**,只是多轴(修仙境界 / 克苏鲁 knowledge 高)更易触发。**注意**:灵力=0(力竭)是否该=死/触底本身也存疑
  (灵力是 depletion 但「枯竭≠必死」),与轴角色粒度(F-012 家族)相关,一并待定。
- **修法方向(待 Felix 定,本条仅诊断未改代码)**:
  - **(A) 提示词强化(便宜·不动核心·无 ADR·可逆)**:event-loop + world-gen 明确「结局必须匹配当前数值死活——任一致命 depletion 轴
    (hp/灵力等)濒零或叙事中角色濒死/重伤时,只能给失败/陨落类结局,绝不给成功/圆满结局;成功结局仅在角色安然达成目标且核心数值健康时给」。
    代价:靠 AI 自律(同衰减稳定性口径),冒烟可观测。
  - **(B) 引擎 gate(健壮·但需结局极性元数据 + 动 event-loop 核心 + ADR)**:给 `endings[]` 加极性(success/failure),引擎 §4.4
    拒绝「致命轴触底时的成功结局」、§5 确定性挑失败结局。**引擎当前无法判定「守园有功」是好是坏(endings 只有中文 condition、无极性)**,
    故纯引擎 gate 必须先加极性字段=schema/registry 变更。
  - 另:次因的 §5 中文 condition vs 英文 key 不匹配,是个**独立的小引擎缺陷**,值得顺手修(但在我刚动过的触底敏感区,需谨慎)。
- **影响面**:通用(任何多轴且轴间给出冲突成功/死亡信号的世界:修仙境界、克苏鲁 knowledge);单轴模式偶发安全。**会不会动 event-loop 核心**:
  (A) 不动;(B) 动 + 需 ADR。
- **关联**:`server/.../engine/Engine.java`(`apply` 步骤 9 §4.4 accept gate / 步骤 10 §5 `forceBottomOutEnding`/`findEndingByConditionMentioning`)、
  `prompts/event-loop.md` + `prompts/world-gen.md`(ending 生成无死活约束)、`server/.../eventloop/TurnPromptBuilder.java`、F-010(单轴 AI 稳定给死亡结局)、F-012(轴角色粒度)。
- **处置(Felix 拍板 2026-06-26,走路 A:修仙批先合并,F-014 由 B 批根治)**:
  1. **(A) 提示词强化【已做,但仅软引导、不足以根治】**:event-loop(`TurnPromptBuilder` + `prompts/event-loop.md`)+ world-gen
     (`WorldGenPromptBuilder` + `prompts/world-gen.md`)的结局生成部分加硬约束——核心 depletion 数值濒零(≤约 10)或叙事濒死/重伤/
     理智崩解/陨落时,**只能命中失败/陨落类结局,绝不给成功结局**,宁可 null;world-gen 侧并要求 condition 绑定死活前提 + 点名数值轴中文名。
     lockstep `.md` + 运行时副本。**冒烟实测:连三局濒死仍得成功结局 → A(提示词软引导)不够、未根治**(同衰减/联动那类「靠 AI 自律」的稳定性短板)。
  2. **(次因)§5 确定性 bug 顺手修【已做】**:`findEndingByConditionMentioning` 原用**英文 key vs 中文 condition** 永不命中 → 回落 `endings[0]`(常好结局)。
     改为**优先按轴中文名匹配**(播种层经 `GameInitService`→`GameSessionManager`→`Engine` 4 参构造传 `axisDisplayNames` 元数据),中文名缺失才回落英文 key。
     **golden parity 字节级零回归**(2/3 参构造无中文名 → 回落 key,= 旧行为;golden 本就不触发 §5)+ 2 新单测(中文名命中失败结局 / 无名回落 endings[0] parity 安全)。
     这是确定性逻辑修复(真 bug),但**只在 §5 兜底点火时生效**——主路径(AI 主动给成功结局走 §4.4)仍未拦,故 §5 修复**不构成根治**。
  3. **(B) 引擎结局极性 gate = 根治路径,独立 B 批(ADR-010)做**:给 `endings[]` 加极性(success/failure)元数据,引擎 §4.4 **拒绝
     「致命轴触底/濒死时的成功结局」**、§5 据极性确定性挑失败结局——把「结局须匹配死活」从 AI 自律升级为引擎硬保证。**动 event-loop 核心 + schema/registry → 出 ADR-010**。
     B 是独立工作单元(Felix 新窗口起);修仙批不等它、先合并。
- **状态:缓解中,B 根治**（修仙批时）。本批落地的是 **A(提示词软引导)+ §5(确定性兜底修复)= 部分缓解**(commit `52b0cbe`,随修仙批合并 `main`),
  **未根治**——濒死仍可能得成功结局(A 不拦主路径)。根治由 **B 批(引擎结局极性 gate,ADR-010)** 承担。
- **✅ 已关闭(2026-06-26,ADR-010 根治 = A+§5+B)**:B 批落地引擎结局极性 gate——`endings[].outcome`(success/failure/neutral,**AI 标、引擎只读**)+
  致命轴 `lethal` 元数据;`Engine.apply` 步骤 9 加 §4.4 gate:**致命轴濒零(≤ `ENDING_GATE_THRESHOLD`=10)且 AI 提议 `outcome==success` → 引擎拒绝该成功结局,
  确定性改挑失败结局**(`pickFailureEnding`:优先 failure 极性 + 中文名匹配,逐级退);§5/§10 据极性挑失败 + 触底收窄到 lethal 轴。引擎只读 outcome 标 + lethal 轴值、
  不懂结局语义(守 ADR-008);outcome 缺省 neutral → 不 gate(向后兼容,golden 零回归)。`schemaVersion` "0.3"→"0.4"(`WORLD_SCHEMA` 接受 {0.2,0.3,0.4} 守 parity)。
  **真 key 冒烟两局验通根治**:① 气血 0(真死)→ gate 拦下成功结局、给「身死道消」失败结局;② 气血 5(濒死未死)→ gate **不矫枉过正**、不强制结束,
  给继续挣扎选项 + AI 自给的 D「闭目等死」——**该死给失败结局、没死透让继续,两种边界都对**(数值/叙事/选项三者对齐,沉浸感满)。AI 标极性可靠(「标错极性」哨兵未触发)。
  A 软引导保留作第一层(减否决频率)。golden parity 字节级零回归(默认空非致命集=全 depletion 致命=现状)。commit `ec3c3f8`(schema)/`08ff650`(§4.4 gate)/`2dee42a`(lethal 元数据)/`73e4b68`(world-gen outcome 注入)。详见 [ADR-010](../docs/adr/ADR-010-ending-outcome-polarity-gate.md)。

## F-015 · 灵力轴角色粒度存疑:`mana` 是 depletion 但「枯竭≠必死」(**ADR-010 关闭,灵力非致命轴**)

- **日期**:2026-06-26 | **步骤**:Phase 2 修仙 F-014 诊断旁支(Felix 拍板「一次只解决结局对齐,灵力存疑独立记」)
- **现象/疑问**:修仙 `mana`(灵力)当前标 `axisRole=depletion` → 引擎对它 `≤0` 即触底强制 `ended`(死)。但修仙语义里
  **灵力枯竭 ≈「力竭 / 暂时使不出法术」,并非必死**(玩家自己的规则是「灵力枯竭**强行运功**才经脉俱断」——是「强行运功」这个动作致死,
  不是灵力=0 本身致死)。即 depletion 二分(ADR-009 F-012)对 `mana` 这种「**可耗尽的资源池、归零是惩罚而非死亡**」的轴**粒度不够**——
  它既不是「≤0 必死」的 hp/气血,也不是「≤0 无所谓」的累积轴(境界),而是第三种「≤0 = 受限/惩罚、不直接死」。
- **同类归因**:这是 F-012 轴角色二分(depletion/accumulation)的**粒度边界**第一次被具体追问——depletion 内部还可再分
  「致命型(hp/san/hunger:≤0 死)vs 资源型(mana:≤0 仅惩罚)」。ADR-009 刻意只做最小二分,资源型轴的「≤0 惩罚非死」当时未纳入。
- **现状/影响**:本批 `mana` 暂走 depletion(≤0 触底死)。真 key 冒烟里若 AI 让灵力到 0 会被引擎判死——但实测玩家通常不会把灵力耗到 0
  且叙事多为「力竭」,**影响面有限**;且与 F-014 结局对齐**正交**(F-014 是「结局匹配死活」,F-015 是「mana=0 算不算死」)。
- **处置(Felix 拍板)**:**本批不碰**(一次只解决结局对齐一个问题)。记为独立待办,日后评估——选项:(a) 给轴角色加第三种
  `resource`(≤0 不触底、仅作惩罚/限制,AI 落后果);(b) 提示词约定「灵力可到很低但别落 0 / 落 0 仅力竭非死」(同 F-012 克苏鲁初版兜法);
  (c) 维持现状(mana=0 触底死,接受其语义粗糙)。若选 (a) 则与 ADR-009 轴角色体系一并扩,出 ADR 或补 ADR-009。
- **关联**:`server/.../archetype/ArchetypeRegistry.java`(修仙 `mana` `axisRole=depletion`)、`AttributeAxis`(角色二分)、
  ADR-009(轴角色 depletion/accumulation 最小二分)、F-012(同源:轴角色粒度)、F-014(正交:结局对齐)。
- **✅ 已关闭(2026-06-26,ADR-010 顺带关闭 = 选项 (a) 的精简版)**:ADR-010 给元数据 axis 加 **`lethal: true|false`**(非新角色,在 depletion 内部细分
  「致命生命轴 vs 非致命资源轴」)。**灵力标 `lethal=false`**(`AttributeAxis.resource("mana"…)`)→ 引擎据 lethal 二分:灵力 `≤0` **既不触发 §10 死亡、
  也不触发 §4.4 结局极性 gate**(播种层把非致命 depletion 轴 key 集合 `{mana}` 传入引擎)。hp/san/hunger/气血 `lethal=true`(≤0 死 + gate)。
  默认空非致命集 = 全 depletion 致命 = 现状(golden parity 字节级零回归)。**真 key 冒烟两局验通**:灵力两局都到 0 但**均未误触发死亡/失败结局**(F-015 关闭成立)。
  commit `2dee42a`(元数据标 lethal + 播种非致命集)。详见 [ADR-010](../docs/adr/ADR-010-ending-outcome-polarity-gate.md) 决策 2。
