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
5. **数值范围**:默认 0–100;模式特有数值(境界等)在该模式的提示词与 schema 扩展里定义。
6. **提示词是核心资产**:统一放 `prompts/`,按管线步骤组织(`world-gen` / `char-gen` / `rule-gen` / `event-loop` / `ending-gen` / `fusion`),版本化管理。
7. **内容安全**:所有生成文本经审核网关通过后再返回前端(方案见 ADR-004)。

## 四、版本历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| v0.1 | 2026-06-16 | 初版:术语表 + 统一 JSON Schema v0.1 + 关键约定 |
| v0.2 | 2026-06-17 | schema 收敛(据 bakeoff 实测 FINDINGS F-001/F-004):明确 `rules[].id` 整数 / `endings[].id` 字符串的刻意差异;endings 增可选 `description`、`title` 改“短名必填”;`character.attributes` 标必填。详见 ADR-001。 |
