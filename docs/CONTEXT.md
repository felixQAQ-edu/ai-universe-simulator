# CONTEXT · 项目约定与共享定义

> 本文档是 AI Universe Simulator 的"约定真理之源":术语、核心数据结构、命名与工程约定都以此为准。
> Claude Code 写代码、起草 ADR、动任何模块前,先读这里,避免各写各的。约定的变更走 ADR,定稿后回写本文件。

## 一、术语表

| 术语 | 含义 | 备注 |
|------|------|------|
| 世界 World | 一局游戏的完整设定(背景 + 角色 + 规则 + 状态 + 结局) | |
| 模式 / archetype | 玩家选的世界类型 | 玩家看中文名(规则怪谈/修仙…),内部用 archetype id |
| 单体 / 混合模式 | `archetypes` 数组长度 = 1 / 2–3 | 混合是杀手锏 |
| 回合 turn | 一次"叙事 + 玩家抉择"的循环 | |
| 决策圈 | 每回合给玩家的 2–4 个可选行动 | = `availableActions` |
| 状态 state | 游戏的真理之源,每回合回传模型 | 见 §三.1 |
| 世界线 / 存档 | 玩家视角叫"世界线",技术上是一条 save 记录 | |
| 规则 rule | 规则怪谈核心,真假混合 | `isTrue` / `hiddenLogic` 仅引擎可见,**绝不泄露给玩家** |
| 数值 | hp / san / 灵根 等,因模式而异 | 默认 0–100,模式特殊说明除外 |
| UG Engine | 通用生成引擎,所有模式共用的生成管线 | world-gen → char-gen → rule-gen → event-loop → ending-gen |
| 世界融合 fusion | 混合模式下把多套设定调和成一套自洽规则的 meta-prompt 步骤 | 见 ROADMAP Phase 3 |

## 二、核心数据结构(统一 JSON Schema · v0.1)

> 所有模式共用一套结构。**state 是真理之源**,每回合连同它一起回传模型。本结构为 v0.1,Phase 0 验证后会调整。

```json
{
  "schemaVersion": "0.4",
  "mode": "single",                 // single | hybrid
  "archetypes": ["rules_creepy"],   // 1 个 = 单体,2–3 个 = 混合
  "world": {
    "title": "雨夜便利店",
    "background": "...",
    "dangerLevel": "high",          // low | medium | high | extreme
    "tone": "..."
  },
  "character": {
    "attributes": { "hp": 100, "san": 100 },   // 必填对象;字段因模式而异(修仙:hp/mana/realm=气血/灵力/境界…)
    "traits": ["..."],
    "inventory": ["..."]
  },
  "rules": [
    // 注意:rules[].id 是【整数】,与 endings[].id(字符串)刻意不同,详见字段职责。
    // isTrue【可选】(ADR-009 F-013):真假守则世界(规则怪谈/克苏鲁)给,心法守则世界(修仙)不给。
    { "id": 1, "content": "...", "isTrue": true, "hiddenLogic": "...", "discovered": false }
  ],
  "state": {
    "turn": 0,
    "status": "ongoing",            // ongoing | ended
    "timeline": "...",              // 当前世界线一句话摘要
    "logSummary": "...",            // 旧回合压缩后的摘要(成本控制)
    "log": [
      { "turn": 1, "narrative": "...", "playerAction": "A" }
    ]
  },
  "availableActions": [
    { "id": "A", "text": "...", "hint": "" }
  ],
  "endings": [
    // id 是【snake_case 字符串】;title 必填(短名);description 可选(整句结局描述)。
    // outcome【可选】(ADR-010 F-014):AI 标的结局极性 success|failure|neutral,引擎据它在致命轴濒零时拒绝成功结局。
    { "id": "survive_dawn", "title": "...", "description": "", "condition": "...", "outcome": "success", "reached": false }
  ]
}
```

字段职责:

- **AI 生成**:`world` / `character` 初值 / `rules` / `availableActions` / 每回合的 `narrative` 与结局判定。
- **引擎维护**:`state.turn` / `status` / `log` / `logSummary`、数值结算、行动合法性校验。
- `rules[].isTrue` **可选**(v0.3,ADR-009 F-013):真假守则世界(规则怪谈/克苏鲁)给(标每条规则真伪),心法守则世界(修仙)不给;`isTrue` 与 `hiddenLogic` 都是**作者/引擎视角**字段,任何返回给玩家的文本都不得泄露。校验器对 `isTrue` 零分派(给了校验布尔、不给容忍),不按 archetype 判(守 ADR-008 校验无知)。
- **id 约定(v0.2 收敛,见 ADR-001 / bakeoff FINDINGS F-001)**:`rules[].id` 用**整数**(便于引擎引用、状态回传里轻量);`endings[].id` 用 **snake_case 英文字符串**(作为稳定语义标识,便于命中判定与跨回合引用)。两者刻意不同且**各自固定**——生成时务必按类型产出,勿混用。
- **endings 字段(v0.2)**:`title` 必填(短标题);`description` 可选(整句结局描述,模型偏好产出,予以承认);`condition` 为可判定的中文条件;`reached` 初始 `false`。`outcome` **可选**(v0.4,ADR-010 F-014):结局极性 `success | failure | neutral`,**world-gen 时 AI 标**(它是结局作者、最清楚好坏),**引擎只读不解读**(据它在致命轴濒零时拒绝 `success` 结局,§三.8);缺省视 `neutral`/不 gate(向后兼容)。`outcome` 是结局元信息非作者隐藏字段,`toClientState` **不剥它**(客户端可不显)。
- `character.attributes` 为**必填对象**,至少含该模式的核心数值(规则怪谈:`hp`/`san`)。

## 三、关键约定

1. **状态是真理之源**:LLM 无记忆。每回合把 `state`(必要时含 `logSummary`)回传模型;旧 `log` 超过阈值就摘要压缩进 `logSummary`,控制 token 成本。
2. **两个模型别混**:build-time = 用 Claude / Claude Code **写代码**;run-time = 用 DeepSeek(provider 可换)**给玩家生成内容**。运行模型选型见 ADR-001。
3. **命名**:JSON 字段用 camelCase 英文;面向玩家的文案用中文;archetype id 用 snake_case。
4. **archetype 枚举**:`rules_creepy`(规则怪谈,**已激活**)、`life_sim`(人生模拟)、`cultivation`(修仙,**已激活**——世界库第二级,全新数值体系 hp/mana/realm=气血/灵力/境界,主角轴=境界 accumulation 累积成长;捆 ADR-009 架构正解落地)、`cyberpunk`(赛博朋克)、`apocalypse`(末日生存,**已激活**)、`cthulhu`(克苏鲁,**已激活**——加世界流水线第一次复用上架,签名轴=禁忌知识 knowledge 累积型双刃,见世界库 backlog 第一级)。原 5 枚举为初版基线,后续世界经加世界流水线(registry 加一条已激活元数据)陆续上架,本枚举同步追加。
5. **数值范围与模式特有数值(ADR-008 展开)**:默认 0–100。`character.attributes` 是**开放字典**(§二原 schema 就是 `{hp,san}` 示例、从未限定 key),模式特有数值=换 key:规则怪谈 `{hp,san}` / 末日 `{hp,hunger}` / 克苏鲁 `{hp,san,knowledge}` / 修仙 `{hp,mana,realm}`(气血/灵力/境界)。**引擎/校验对数值 key 语义无知**——`Engine.apply` 遍历 `attributes` map 通用结算(绝对值/clamp 0–100/跳变>40 标记),`validateWorld`/`TURN_SCHEMA` **只硬校验每个已给轴的范围 0–100,不硬校验 key 集合**(不做 per-archetype 硬清单)。「该渲染/生成哪些轴」由 **per-archetype 元数据**(§三.14,非强制校验)告知消费方,不靠 schema 拦。**衰减型数值(如末日饥饿)由 AI 落**(每回合 `stateUpdate` 给新绝对值,衰减提示喂提示词),**引擎不读 `decay`、不认识「会衰减」语义**。加 key(hunger/knowledge/mana/realm)**不算 schema 字段变更**。详见 ADR-008。**轴角色 axisRole 见 §三.14 / §三.8(ADR-009 F-012)**。
6. **提示词是核心资产**:统一放 `prompts/`,按管线步骤组织(`world-gen` / `char-gen` / `rule-gen` / `event-loop` / `ending-gen` / `fusion`),版本化管理。
7. **内容安全**:所有生成文本经审核网关通过后再返回前端(方案见 ADR-004)。
8. **数值权威**:数值由引擎落账,AI 只提议。event-loop 每回合 AI 在 `stateUpdate` 里回传 `hp`/`san` 的**绝对新值**(非增量);`Engine.apply()` 负责校验落账,三道闸门分工——`TURN_SCHEMA` 硬性范围 0–100(越界→修复重试)、单回合跳变 > `JUMP_THRESHOLD`(默认 40)记「需复核」但不拒绝(允许有据恢复,见 FINDINGS F-003)、`clamp(0,100)` 兜底。结局:AI 提议 `ending{id,reached,outcome}`,引擎校验 `id` 存在于 `endings[]` 并转 `status`。**触底按轴角色 `axisRole`(ADR-009 F-012)+ 致命标 `lethal`(ADR-010 F-015)**——**致命 depletion 轴(hp/san/hunger/气血,`lethal=true`)≤0** 时引擎强制 `ended` 并据极性兜底挑一个失败结局 id;**非致命 depletion 轴(灵力,`lethal=false`)≤0 不触底**(枯竭=力竭非必死);**accumulation 轴(knowledge/境界)≤0 不触底**(0 是安全起点)。**结局极性 gate(ADR-010 F-014,根治濒死得成功结局)**:AI 提议 `outcome==success` 结局时,若有致命轴**濒零**(value ≤ `ENDING_GATE_THRESHOLD`,默认 10),引擎**拒绝该成功结局**、确定性改挑 `failure` 结局(`pickFailureEnding`:优先 failure 极性 + 致命轴中文名匹配,逐级退);`neutral`/无 `outcome` 老结局一律放过(向后兼容)。引擎**只读 AI 标的 `outcome` + 致命轴数值**,不懂结局语义(守 ADR-008)。角色/致命/非致命集均由播种层据 per-archetype 元数据传入引擎(累积轴 key 集合、非致命 depletion 轴 key 集合),引擎只据集合 gate。详见 Phase 1 event-loop 规格 §5、ADR-009、ADR-010。
9. **state 三视图与消毒边界**:同一真理之源有三个投影——(1) 引擎内部全量(含 `isTrue`/`hiddenLogic`);(2) 喂模型的(也含 `hiddenLogic`,裁决真假规则需要);(3) 客户端消毒投影(绝不含 `isTrue`/`hiddenLogic`)。任何下发前端的 SSE 事件 / state 快照只走 (3)。实时防护 = 提示词硬禁吐隐藏逻辑 + 结构层消毒;`detect_leak` 在流式路径里是事后遥测(只抓逐字照抄 + 字段名,抓不到改写式泄露),非实时拦截。见 ADR-006、规格 §1。
10. **两套线上口径别混**(回合 vs world-gen):**回合**(event-loop)走 ADR-006——丢 `response_format: json_object` 换逐字流叙事(叙事先行 + 哨兵 `<<<DELTA>>>` + 结构化尾巴 + 叙事回灌),修复发才开回 json_object;**world-gen**(INITIALIZING 胖调用)走 **ADR-007**——**保 `response_format: json_object`、纯 JSON、无哨兵**,把可靠性留在最险的那次生成(world-gen JSON 首次失败是头号失败模式,真 key 冒烟只证回合侧、不外推)。别把回合的哨兵/叙事回灌/保守 no-op 搬到 world-gen 上。
11. **world-gen 失败 = 整局 ERROR**(非 no-op 降级,异于回合):world-gen 救不回时无前态可守(尚无游戏)→ 干净失败 + 提示「重新生成」,**不进半残 PLAYING**;对照回合修复用尽走保守 no-op(守一局 ongoing)。胖调用 → `LooseJson` → `validateWorld` → 不通过一次修复 → 仍败 ERROR。见 ADR-007、设计稿 §4。
12. **开场叙事 reveal 不流式**(ADR-007):world-gen 产 `openingNarrative` 作 **transient init 字段**,随 `POST /api/game/init`(plain POST 无 SSE)响应一次性下发,**不进持久化 `state`** → `schemaVersion` 仍 `"0.2"`(无字段变更);逐字 vibe 由前端 client-side reveal 动画补,后端不为它丢 json_object。init 响应的 `world` 走 `toClientState()` 消毒投影(剥 `isTrue`/`hiddenLogic`)。
13. **前端跨端边界(ADR-003)**:`web/` 逻辑/状态/类型/展示层**平台无关**(Taro 直接复用),**禁引用任何平台 IO**(`fetch`/`EventSource`/`WebSocket`/`wx.*`);网络/流 IO **全收进 `web/src/api/` 薄适配层**(迁移时只换它,镜像后端 `LlmClient`/`TokenStream` 接缝哲学)。`api/` 对上暴露 **provider-agnostic 流接口** `TurnStream{onNarrative/onDelta/onEnding/onError}`——逻辑层只见这四类语义事件,**永不见底层传输**;H5 实现用 `fetch`+流式读 body 解析 SSE(**非原生 `EventSource`**:回合端点 `POST` 带 body,`EventSource` 只能 GET 无 body)。Phase 4 小程序流式回合**主预案 `wx.request`+`enableChunked` 分块回调**(与 H5 同构:`POST` body + 流式读 chunk 自解析,后端无需新端点、复用现有 SSE 响应,只换 chunk 读取适配),**备选 WebSocket**(更重的另一套范式,后端加 WS 端点桥到同一 `TokenStream`)——两路都只动适配层、新增一个 `TurnStream` 实现,逻辑层零改(主次序据 H5 实测校正,详见 ADR-003 §3)。硬线**可检查**:逻辑/状态层对 `fetch`/`EventSource`/`wx` 的引用计数 = 0(`web/eslint.config.js` `no-restricted-globals`,`api/` 与测试豁免)。约定层补充,`schemaVersion` 不动。

14. **per-archetype 元数据(ADR-008 决策 1,非强制校验)**:一份服务端元数据(`server/.../archetype/`,非 JSON schema 字段)声明每个 archetype 的数值轴 + 规则形态 + 世界观,供两个消费方读取——**(a) 前端**按它渲染哪些数值面板项及中文名;**(b) world-gen / event-loop 提示词**按它注入该生成/维护哪些数值 + 衰减提示。**不进硬校验**(模型漏轴靠提示词 + 冒烟门兜)。每条声明:

    ```
    archetype:    "apocalypse"           // ∈ §三.4 枚举
    displayName:  "末日生存"              // 玩家可见中文名
    worldview:    "<世界观描述,喂 world-gen 注入块>"
    attributes:                          // 数值轴清单(顺序即面板渲染顺序)
      - { key: "hp",     displayName: "体力", range: [0,100], axisRole: depletion,    lethal: true,  behaviorHint: null }
      - { key: "hunger", displayName: "饥饿", range: [0,100], axisRole: depletion,    lethal: true,  behaviorHint: "每回合约 -5~10(AI 落,引擎无知)" }
      // 非致命资源轴示例(修仙灵力,ADR-010 F-015):- { key: "mana",  displayName: "灵力", axisRole: depletion,    lethal: false, behaviorHint: "施法/突破消耗、可恢复" }
      // 累积轴示例:                                  - { key: "realm", displayName: "境界", axisRole: accumulation, lethal: false, behaviorHint: "修炼/顿悟则上涨、纯成长不致死" }
    ruleForm:     "<规则形态描述,喂注入块>"  // 末日:生存法则/资源约束(非规则怪谈真假规则,但仍复用 discovered 机制)
    rulesCarryTruth: true                // 规则是否真假守则型(ADR-009 F-013):true=真假混合带 isTrue;false=心法守则不带 isTrue(修仙)
    ```

    `decay` **只是喂提示词的提示文本,引擎不读它**(决策 2:衰减 AI 落、引擎无知)。规则怪谈也补一条(`rules_creepy`:hp/san=体力/理智,真假规则形态),两模式走同一元数据驱动路径,不让规则怪谈成特例。约定层补充,`schemaVersion` 仍 "0.2"(`attributes` 一直是开放字典,元数据是伴生结构非 schema 字段)。

    **轴行为提示字段泛化(克苏鲁批,2026-06-25)**:`decay` 字段泛化为 **`behaviorHint`**——不止「衰减」,而是该轴**任何逐回合特殊行为**的提示文本,涵盖衰减型(末日 `hunger` 每回合下降)、**累积型/联动型**(克苏鲁 `knowledge`:玩家求知则上涨、且 knowledge 越高 `san` 流失越快;修仙 `realm` 修炼则上涨/纯成长、`mana` 施法消耗)。**引擎一概不读 behaviorHint**,一切由 AI 在 `stateUpdate` 落新绝对值(守决策 1/2 引擎无知);world-gen / event-loop 两消费方同源读。

    **轴角色 `axisRole` + 规则形态弹性(修仙批,ADR-009 根治 F-012/F-013)**:元数据每轴增 **`axisRole: depletion | accumulation`**——这是**引擎唯一会读的轴语义**(刻意最小二分):depletion 轴 `≤0` 触底致死、accumulation 轴 `≤0` 不致死(§三.8)。角色由播种层据元数据算出累积轴 key 集合传入引擎,引擎只据集合 gate、不懂任何具体轴(克苏鲁 `knowledge`「高了拖累 san」、修仙 `realm`「纯成长」的区别继续归 AI 落)。元数据另增 **`rulesCarryTruth`**(规则是否真假守则型,F-013):驱动 world-gen 注入块的 rules 措辞(真假混合带 `isTrue` / 心法守则不带);校验器本身对 `isTrue` 零分派(§二)。**F-012 / F-013 已由 ADR-009 根治、关闭**(克苏鲁批的「提示词兜」升级为引擎/schema 正解)。axisRole/rulesCarryTruth 在元数据(伴生结构)非 state schema 字段,本身不触发 `schemaVersion`;ADR-009 批 `schemaVersion` 由 `isTrue` 改可选升至 **"0.3"**(§二)。

    **致命标 `lethal`(结局极性 gate 批,ADR-010 根治 F-014 + 关闭 F-015)**:元数据每个 depletion 轴增 **`lethal: true | false`**——在 `axisRole=depletion` 内部细分「致命生命轴(hp/san/hunger/气血 `lethal=true`)vs 非致命资源轴(修仙灵力 `mana` `lethal=false`,枯竭=力竭非必死)」(accumulation 轴恒 `lethal=false`、本就不触底)。**引擎只读这一个 bool**(经播种层算出**非致命 depletion 轴 key 集合**传入,同 axisRole 的播种路径):只有**致命轴** `≤0` 触发触底死亡(§三.8)、`≤ ENDING_GATE_THRESHOLD`(默认 10)触发**结局极性 gate**(致命轴濒零时拒绝 AI 标 `outcome==success` 的结局、确定性改挑 `failure`,根治 F-014)。`lethal` 关闭 F-015(灵力非致命 → `≤0` 既不死也不 gate)。`lethal` 在元数据(伴生结构)非 state schema 字段,**不触发 `schemaVersion`**;ADR-010 批 `schemaVersion` 由 **`endings[].outcome` 新增**(§二 state schema 字段)升至 **"0.4"**;校验器接受 `{"0.2","0.3","0.4"}` 三版本守 parity 夹具。**F-014 / F-015 已由 ADR-010 根治、关闭**。

    **行为档 `bands`(#3 数值行为化批,Phase 2 打磨期,2026-06-30)**:元数据每轴可选增 **`bands`**——把该轴 0–100 的连续值域切成**三档**「状态/叙事色彩」,每档 `{ threshold, label, narrationHint }`:`threshold` 是档边界(**`axisRole` 感知**:depletion 轴=该档**上界 inclusive**、值越低进越危的档;accumulation 轴=该档**下界 inclusive**、值越高进越深的档),`label` 玩家可见中文短词(如「濒危」「灵力枯竭」「深陷」),`narrationHint` 喂提示词的叙事色彩文本。阈值草案:depletion 切 50/20、accumulation 切 30/60(Felix 2026-06-30 签字)。良构由构造器校验(阈值单调/不重/在域内 + 覆盖顶/底档 + label 非空);纯函数 `resolveBand(value)` 据 `axisRole` 解析当前档。**只染叙事、绝不 gate 选项**(gating=#4,推迟到状态层)。**引擎一概不读 `bands`**(同 behaviorHint),两个消费方:**(a) event-loop 提示词**每回合按当前绝对值算当前档、注入**当前档** `label`+`narrationHint`(只送当前档不送整表,守成本,让叙事跟着状态走);**(b) 前端**数字旁显示当前档 `label`(如「气血 28 · 受创」)。**下发投影**:轴元数据下发走 **API DTO**(`InitResponse.attributes`,非被校验 wire schema),`bands` 投影为**显式 inclusive 区间 `{min,max,label}`**(`AttributeAxis.bandRanges()` 据 `axisRole` 算,连续覆盖 [0,100])——前端 **role-agnostic**、只需 `min≤value≤max` 解析,无须懂 depletion/accumulation(守 §三.13 展示层语义无关);`narrationHint` **不下发前端**(仅服务端注入 prompt,守消毒)。`bands` 在元数据(伴生结构)非 state schema 字段,**不触发 `schemaVersion`**(仍 "0.4");Felix 真 key 浏览器冒烟验通(克苏鲁档位随值切换 + 叙事贴合当前档 + 未顶爆 A-1 + 无泄露)。**末日「饥饿」轴改名**(displayName 与值方向反直觉)因「饥饿」已织进 prompt/condition 文案、改 displayName 会两套叫法 → 拆**独立小单元**入 [打磨与愿景 backlog §6](phase2-polish-and-vision-backlog.md),本批未动。

15. **选择屏目录契约 `GET /api/archetypes`(ADR-008 决策 4,A 计划落地)**:一个轻量只读端点,供前端「选择你的世界」第一屏渲染世界目录(不让前端硬编码模式清单)。响应 `{ "archetypes": [ {archetype, displayName, tagline, vibeTag, active} ] }`——**已激活**(可玩:`rules_creepy`/`apocalypse`/`cthulhu`/`cultivation`)在前(全字段),**已知未开放**(§三.4 枚举里 `life_sim`/`cyberpunk`)在后(`active:false`、`tagline`/`vibeTag` 为 `null`,前端灰显「敬请期待」、不可选)。数据源 = `ArchetypeRegistry.listForSelection()`。为此 **`ArchetypeMeta` 增两个玩家可见文案字段** `tagline`(一句话钩子)/`vibeTag`(氛围/危险短标签)——**仅供选择屏卡片展示,不进 world-gen 注入**(注入仍用长 `worldview`,§三.14);玩家可见文案用中文(§三.3)。前端经 `api/listArchetypes`(平台 IO 只在 `api/` 适配层,守 §三.13)消费。**加新世界**:registry 加一条已激活元数据即自动进目录(未配文案则 `tagline`/`vibeTag` 留空);卡片视觉氛围由前端 per-archetype CSS 主题承载(展示层,不入后端)。约定层补充,JSON `schemaVersion` 仍 "0.2"(端点是只读投影 + registry 伴生字段,非 state schema 字段变更)。

## 四、版本历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| v0.1 | 2026-06-16 | 初版:术语表 + 统一 JSON Schema v0.1 + 关键约定 |
| v0.2 | 2026-06-17 | schema 收敛(据 bakeoff 实测 FINDINGS F-001/F-004):明确 `rules[].id` 整数 / `endings[].id` 字符串的刻意差异;endings 增可选 `description`、`title` 改“短名必填”;`character.attributes` 标必填。详见 ADR-001。 |
| v0.3 | 2026-06-19 | 追加 §三.8 数值权威(绝对值)+ §三.9 state 三视图消毒(据 ADR-006 与 Phase 1 规格);约定层补充,JSON schemaVersion 仍 "0.2"(字段未变),仅 CONTEXT 文档版本升 v0.3 |
| v0.4 | 2026-06-21 | 追加 §三.10 两套线上口径(回合 ADR-006 丢 json_object vs world-gen ADR-007 保 json_object)+ §三.11 world-gen 失败=整局 ERROR + §三.12 开场叙事 reveal 不流式(`openingNarrative` transient init 字段);据 ADR-007 与 world-gen 设计稿,约定层补充,JSON schemaVersion 仍 "0.2"(字段未变),仅 CONTEXT 文档版本升 v0.4 |
| v0.5 | 2026-06-23 | 追加 §三.13 前端跨端边界(ADR-003):`web/` 逻辑/状态/类型/展示层平台无关、禁引用平台 IO,网络/流收进 `api/` 薄适配层并暴露 provider-agnostic `TurnStream` 流接口(H5 用 fetch+SSE,非原生 EventSource;Phase 4 换 WS 只增实现);硬线由 eslint `no-restricted-globals` 守。约定层补充,JSON schemaVersion 仍 "0.2",仅 CONTEXT 文档版本升 v0.5 |
| v0.6 | 2026-06-23 | 校正 §三.13 Phase 4 小程序流式回合预案主次序(随 ADR-003 §3 同步):因 H5 实测确认回合走 `fetch`+`POST` body+流式读 chunk(非原生 `EventSource`),`wx.request`+`enableChunked` 分块回调与之同构(复用最多、后端无需新端点)升为**主预案**,WebSocket(更重范式)降为**备选**;原主次序基于「H5 用 EventSource」的假设、已被实现校正。纯文档校正,无字段/schema 变更,JSON schemaVersion 仍 "0.2" |
| v0.7 | 2026-06-24 | 多模式扩展架构落档(ADR-008,Phase 2 第一批=末日生存):§三.5 展开为「attributes 开放字典 + 引擎/校验对数值 key 语义无知(遍历 map 通用结算、只校验已给轴范围)+ 衰减 AI 落引擎无知」;新增 §三.14 per-archetype 元数据结构(数值轴中文名/衰减提示/规则形态,非强制校验,消费方=前端面板 + 提示词注入)。**事实订正**:核心实为 key-fixed 到 `{hp,san}`,据 ADR-008 决策 1 一次性泛化为 key-agnostic、golden parity 139 守零回归。约定层补充,JSON `schemaVersion` 仍 "0.2"(`attributes` 一直是开放字典,加 hunger 非字段变更),仅 CONTEXT 文档版本升 v0.7 |
| v0.8 | 2026-06-25 | 追加 §三.15 选择屏目录契约 `GET /api/archetypes`(ADR-008 决策 4 / A 计划落地):只读端点供前端世界选择第一屏(已激活在前 + 已知未开放占位灰显),数据源 `ArchetypeRegistry.listForSelection()`;`ArchetypeMeta` 增玩家可见文案字段 `tagline`/`vibeTag`(仅选择屏展示、不进 world-gen 注入);卡片视觉氛围由前端 per-archetype CSS 主题承载(展示层)。约定层补充,JSON `schemaVersion` 仍 "0.2"(只读投影 + registry 伴生字段,非 state schema 变更),仅 CONTEXT 文档版本升 v0.8 |
| v0.9 | 2026-06-25 | 加世界流水线第一次复用·克苏鲁(`cthulhu`)落地 + 真 key 冒烟验通:§三.4 archetype 枚举追加 `cthulhu`(已激活,签名轴=禁忌知识 `knowledge` 累积型双刃)、§三.15 已激活示例补 cthulhu;§三.14 轴行为提示字段 `decay` 泛化为 `behaviorHint`(涵盖衰减/累积/联动,引擎仍不读),并记两条架构缺口 F-012(引擎 ≤0 触底假设只对 depletion 轴成立)/ F-013(骨架强制 isTrue 与非真假守则世界冲突)——均本批提示词兜、引擎正解留修仙批。约定层补充,JSON `schemaVersion` 仍 "0.2"(registry 伴生字段 + 元数据提示文本,非 state schema 变更),仅 CONTEXT 文档版本升 v0.9 |
| **v1.0** | 2026-06-25 | **修仙(`cultivation`)落地 + ADR-009 架构正解(根治 F-012/F-013),真 key 冒烟验通**:§三.4 枚举 `cultivation` 标已激活(全新数值体系 `{hp,mana,realm}`=气血/灵力/境界,主角轴=境界 accumulation)、§三.5/§三.15 同步;**§二 `rules[].isTrue` 改可选**(F-013,校验零分派)+ **JSON `schemaVersion` "0.2"→"0.3"**(首次真动字段约束;`WORLD_SCHEMA` 接受双版本守 parity 夹具,见 ADR-009);**§三.8 触底按轴角色 `axisRole`**(depletion ≤0 致死 / accumulation 不触底,F-012 引擎根治)、**§三.14 元数据增 `axisRole` + `rulesCarryTruth`**。golden parity 字节级守 depletion 零回归(server 173 / web 30 全绿)。F-012/F-013 已关闭。CONTEXT 文档版本升 v1.0(本批是首个真动 JSON `schemaVersion` 的批次)。 |
| **v1.1** | 2026-06-26 | **结局极性 gate(ADR-010 根治 F-014 + 关闭 F-015),真 key 冒烟验通**:**§二 `endings[].outcome` 新增**(可选极性 `success|failure|neutral`,AI 标、引擎只读;`toClientState` 不剥)+ **JSON `schemaVersion` "0.3"→"0.4"**(`WORLD_SCHEMA` 接受 `{0.2,0.3,0.4}` 三版本守 parity);**§三.8 结局极性 gate**(致命轴濒零 ≤`ENDING_GATE_THRESHOLD`(10)时引擎拒绝 AI `outcome==success` 结局、据极性确定性挑 failure;触底/gate 收窄到致命轴)+ **§三.14 元数据增 `lethal`**(depletion 内部细分致命生命轴/非致命资源轴;灵力 `lethal=false` 关闭 F-015)。引擎只读 outcome 标 + lethal 轴值、不懂结局语义(守 ADR-008);golden parity 字节级零回归(server 188 / web 30 全绿)。真 key 冒烟两局验通:气血 0→拦成功结局给「身死道消」、气血 5 濒死未死→不强制结束给挣扎选项(两边界都对);灵力两局 0 未误死。F-014/F-015 已关闭。CONTEXT 文档版本升 v1.1。 |
| **v1.2** | 2026-06-30 | **数值行为档 `bands`(#3 数值行为化,Phase 2 打磨期,descriptive 只染叙事),真 key 冒烟验通**:**§三.14 元数据每轴可选增 `bands`**——0–100 切三档 `{threshold,label,narrationHint}`,`axisRole` 感知(depletion=上界/降序进带、accumulation=下界/升序进带),阈值 depletion 50/20、accumulation 30/60(Felix 签字);纯函数 `resolveBand` 解析当前档,构造器校验良构。**只染叙事、绝不 gate 选项**(gating=#4 推迟状态层)。引擎一行不动、不读 `bands`(守 ADR-008);两消费方=event-loop 每回合注入**当前档** label+narrationHint(只送当前档守成本,叙事跟着状态走)+ 前端数字旁显示当前档 label。**走 API DTO(`InitResponse.attributes`)非校验 wire schema**,投影为显式区间 `{min,max,label}`(前端 role-agnostic);`narrationHint` 不下发前端(守消毒)。**JSON `schemaVersion` 不动(仍 "0.4")**——`bands` 是元数据伴生结构非 state schema 字段。golden parity 字节级零回归(server 212 / web 36 全绿)。真 key 冒烟验通(克苏鲁档位随值切换 + 叙事贴合 + 未顶爆 A-1 + 无泄露)。末日「饥饿」改名因 woven-in 拆独立 backlog 单元(本批未动)。仅 CONTEXT 文档版本升 v1.2。 |
