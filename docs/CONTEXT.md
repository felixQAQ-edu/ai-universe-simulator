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
  "schemaVersion": "0.2",
  "mode": "single",                 // single | hybrid
  "archetypes": ["rules_creepy"],   // 1 个 = 单体,2–3 个 = 混合
  "world": {
    "title": "雨夜便利店",
    "background": "...",
    "dangerLevel": "high",          // low | medium | high | extreme
    "tone": "..."
  },
  "character": {
    "attributes": { "hp": 100, "san": 100 },   // 必填对象;字段因模式而异(修仙:灵根/境界…)
    "traits": ["..."],
    "inventory": ["..."]
  },
  "rules": [
    // 注意:rules[].id 是【整数】,与 endings[].id(字符串)刻意不同,详见字段职责。
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
    { "id": "survive_dawn", "title": "...", "description": "", "condition": "...", "reached": false }
  ]
}
```

字段职责:

- **AI 生成**:`world` / `character` 初值 / `rules` / `availableActions` / 每回合的 `narrative` 与结局判定。
- **引擎维护**:`state.turn` / `status` / `log` / `logSummary`、数值结算、行动合法性校验。
- `rules[].isTrue` 与 `hiddenLogic` 是**作者/引擎视角**字段,任何返回给玩家的文本都不得泄露。
- **id 约定(v0.2 收敛,见 ADR-001 / bakeoff FINDINGS F-001)**:`rules[].id` 用**整数**(便于引擎引用、状态回传里轻量);`endings[].id` 用 **snake_case 英文字符串**(作为稳定语义标识,便于命中判定与跨回合引用)。两者刻意不同且**各自固定**——生成时务必按类型产出,勿混用。
- **endings 字段(v0.2)**:`title` 必填(短标题);`description` 可选(整句结局描述,模型偏好产出,予以承认);`condition` 为可判定的中文条件;`reached` 初始 `false`。
- `character.attributes` 为**必填对象**,至少含该模式的核心数值(规则怪谈:`hp`/`san`)。

## 三、关键约定

1. **状态是真理之源**:LLM 无记忆。每回合把 `state`(必要时含 `logSummary`)回传模型;旧 `log` 超过阈值就摘要压缩进 `logSummary`,控制 token 成本。
2. **两个模型别混**:build-time = 用 Claude / Claude Code **写代码**;run-time = 用 DeepSeek(provider 可换)**给玩家生成内容**。运行模型选型见 ADR-001。
3. **命名**:JSON 字段用 camelCase 英文;面向玩家的文案用中文;archetype id 用 snake_case。
4. **archetype 枚举**:`rules_creepy`(规则怪谈)、`life_sim`(人生模拟)、`cultivation`(修仙)、`cyberpunk`(赛博朋克)、`apocalypse`(末日生存)。
5. **数值范围与模式特有数值(ADR-008 展开)**:默认 0–100。`character.attributes` 是**开放字典**(§二原 schema 就是 `{hp,san}` 示例、从未限定 key),模式特有数值=换 key:规则怪谈 `{hp,san}` / 末日 `{hp,hunger}` / 修仙日后 `{灵根,境界}`。**引擎/校验对数值 key 语义无知**——`Engine.apply` 遍历 `attributes` map 通用结算(绝对值/clamp 0–100/跳变>40 标记),`validateWorld`/`TURN_SCHEMA` **只硬校验每个已给轴的范围 0–100,不硬校验 key 集合**(不做 per-archetype 硬清单)。「该渲染/生成哪些轴」由 **per-archetype 元数据**(§三.14,非强制校验)告知消费方,不靠 schema 拦。**衰减型数值(如末日饥饿)由 AI 落**(每回合 `stateUpdate` 给新绝对值,衰减提示喂提示词),**引擎不读 `decay`、不认识「会衰减」语义**。加 hunger **不算 schema 字段变更,`schemaVersion` 仍 "0.2"**。详见 ADR-008。
6. **提示词是核心资产**:统一放 `prompts/`,按管线步骤组织(`world-gen` / `char-gen` / `rule-gen` / `event-loop` / `ending-gen` / `fusion`),版本化管理。
7. **内容安全**:所有生成文本经审核网关通过后再返回前端(方案见 ADR-004)。
8. **数值权威**:数值由引擎落账,AI 只提议。event-loop 每回合 AI 在 `stateUpdate` 里回传 `hp`/`san` 的**绝对新值**(非增量);`Engine.apply()` 负责校验落账,三道闸门分工——`TURN_SCHEMA` 硬性范围 0–100(越界→修复重试)、单回合跳变 > `JUMP_THRESHOLD`(默认 40)记「需复核」但不拒绝(允许有据恢复,见 FINDINGS F-003)、`clamp(0,100)` 兜底。结局:AI 提议 `ending{id,reached}`,引擎校验 `id` 存在于 `endings[]` 并转 `status`;`hp`/`san`≤0 时引擎强制 `ended` 并兜底指派一个坏结局 id。详见 Phase 1 event-loop 规格 §5。
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
      - { key: "hp",     displayName: "体力", range: [0,100], decay: null }
      - { key: "hunger", displayName: "饥饿", range: [0,100], decay: "每回合约 -5~10(AI 落,引擎无知)" }
    ruleForm:     "<规则形态描述,喂注入块>"  // 末日:生存法则/资源约束(非规则怪谈真假规则,但仍复用 discovered 机制)
    ```

    `decay` **只是喂提示词的提示文本,引擎不读它**(决策 2:衰减 AI 落、引擎无知)。规则怪谈也补一条(`rules_creepy`:hp/san=体力/理智,真假规则形态),两模式走同一元数据驱动路径,不让规则怪谈成特例。约定层补充,`schemaVersion` 仍 "0.2"(`attributes` 一直是开放字典,元数据是伴生结构非 schema 字段)。

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
