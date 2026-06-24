# Phase 2 · 第一批设计稿 — 末日生存模式落地(验多模式管线)

> 草案(决策已锁版) · 交给 Claude Code 当实现蓝本。
> 目标:把已验证的单模式管线(规则怪谈)扩成多模式,**末日生存(`apocalypse`)为第一个落地模式 = 给后续所有模式立模板**。
> 架构依据:ADR-008(本批落地它);配套 CONTEXT、ADR-006/007、event-loop 规格、world-gen 规格。
> **本批是"验多模式管线能跑通",不是"内容丰富的末日生存"**——签名轴只做饥饿一个,口渴/物资等留内容打磨。

## 0. 边界:复用 / 新造

| 模块 | 状态 | 依据 |
|------|------|------|
| `Engine.apply()` 数值结算(绝对值/clamp/跳变/log/结局/兜底) | ✅ 复用零改 | ADR-008 决策 1:引擎对数值 key 无知,通吃 `{hp,hunger}` |
| `validateWorld`/`TURN_SCHEMA`(范围 0–100 硬校验,key 集合不硬校验) | ✅ 复用零改 | ADR-008 决策 1:attributes 开放字典 |
| 回合状态机 / `EventLoopService` / 守卫 / SSE 协议 | ✅ 复用零改 | 模式无关 |
| `toClientState` 消毒 / 前端整局闭环 | ✅ 复用(前端面板需按元数据渲染,见 §4) | |
| **per-archetype 元数据结构 + 末日条目** | 🆕 | ADR-008 决策 1;§2 |
| **world-gen 提示词:通用模板重构 + 末日注入块** | 🆕 | ADR-008 决策 3;§3 |
| **init `archetype` 入参真正接受 + 枚举校验** | 🆕 | ADR-008 决策 4;§5 |
| **末日 world-gen 真 key 冒烟(含 AI 衰减观测)= 验收门** | 🆕 | ADR-008 决策 5;§6 |

> 核心省力点:引擎/校验/状态机/SSE **一行不改**(ADR-008 决策 1 的回报)。本批真正新写的只有元数据 + 提示词重构 + init 入参,加一道冒烟门。

## 1. archetype 枚举现状
CONTEXT §三.4 已定:`rules_creepy`/`life_sim`/`cultivation`/`cyberpunk`/`apocalypse`。本批激活 `apocalypse`,其余仍占位。

## 2. per-archetype 元数据(ADR-008 决策 1 核心新件)

一份服务端元数据(建议 `server/.../archetype/ArchetypeMeta`,落点 Claude Code 定),每个 archetype 声明:

```
archetype: "apocalypse"
displayName: "末日生存"          // 玩家可见中文名
worldview: "<世界观描述,喂 world-gen 注入块的素材>"
attributes:                      // 数值轴清单
  - key: "hp",     displayName: "体力",   range: [0,100], decay: null
  - key: "hunger", displayName: "饥饿",   range: [0,100], decay: "每回合约 -5~10(AI 落,见 ADR-008 决策 2)"
ruleForm: "<该模式规则形态描述,喂注入块>"   // 末日:生存法则/资源约束类,非规则怪谈的真假规则
```

- **消费方**:(a) 前端按 `attributes` 渲染数值面板项 + 中文名(末日显"体力/饥饿");(b) world-gen/event-loop 提示词按它注入该生成/维护哪些数值 + 衰减提示。
- **非强制校验**(ADR-008 决策 1):元数据不进 `validateWorld` 硬清单;模型漏给 hunger 靠提示词 + 软处理兜。
- **规则怪谈也补一条元数据**(`rules_creepy`:hp/san,真假规则形态)——让两模式走同一元数据驱动路径,证模板通用,不让规则怪谈成特例。

> ⚠️ 注意衰减字段是 hp/san 没有的特性——它只是"喂提示词的提示文本",**引擎不读它**(ADR-008 决策 2:引擎无知)。别让 Claude Code 把 decay 接进引擎。

## 3. world-gen 提示词重构(ADR-008 决策 3)

- **通用骨架(单点维护)**:输出 schema、id 约定(F-001:rules[].id 整数 / endings[].id snake_case 字符串)、消毒硬约束(禁 isTrue/hiddenLogic/正确解法进玩家可见文本)、json_object、openingNarrative 字段——这些**模式无关,固定**。
- **per-archetype 注入块(变量)**:从元数据注入 `worldview`/`attributes`(含 hunger + 衰减提示)/`ruleForm`。
- **末日注入块要点**:世界观=末日生存场景;数值=hp(体力/伤势)+ hunger(饥饿,**提示 AI 每回合自然衰减 ~5~10**);规则形态=生存法则/资源约束(非规则怪谈的真假规则机制,但仍可有"被发现才知道的硬规矩",复用 `discovered` 机制)。
- **lockstep 纪律**:若运行时提示词有 Java 同义副本(如 `WorldGenPromptBuilder` / `TurnPromptBuilder`),`.md` 资产 + 运行时副本**同步改**(沿用 Phase 1 两次教训:只改 `.md` 运行时失效)。
- **event-loop 提示词**:也要按 archetype 知道维护哪些数值 + 衰减(回合里 AI 要持续落 hunger 衰减)→ 注入同源元数据。Phase 1 的叙事清洁度硬约束(禁内部字段名/markdown 头)保留。

## 4. 前端(本批最小改动,选择 UI 不做)

- 数值面板从写死 hp/san 改为**按元数据/返回的 attributes 动态渲染**(键 + 中文名)。这是 ADR-008 决策 1 前端消费方的落地——否则末日玩家看不到"饥饿"。
- 守 ADR-003 边界:纯展示层动态化,逻辑/状态层不碰平台 API;面板永不渲染 isTrue/hiddenLogic。
- **archetype 选择 UI 不做**(ADR-008 决策 4):第一批经 init 入参验,前端暂可写死或留参数入口验末日。选择 UI 作管线验通后的独立小批。

## 5. init 开放 archetype 入参(ADR-008 决策 4)

- `POST /api/game/init { archetype }`:从写死 `rules_creepy` 改为接受值,**校验 ∈ archetype 枚举**(非法 → 400)。未激活的 archetype(life_sim 等)可先拒或标"未开放"。
- 下游 world-gen 按 archetype 取元数据 + 注入块生成。其余(消毒投影、openingNarrative、播种 PLAYING)复用 Phase 1 GameInitService 路径零改。

## 6. 切分顺序 + 测试策略 + 冒烟门

**切分(由纯到脏)**:
1. **元数据结构 + 末日条目 + 规则怪谈条目**(纯数据)——先它,下游都依赖。
2. **world-gen 提示词重构**(通用骨架抽离 + 末日/规则怪谈注入块,lockstep 运行时副本)。
3. **init archetype 入参 + 枚举校验**(下游 world-gen 取元数据分派)。
4. **前端数值面板动态渲染**(按 attributes + 元数据中文名)。
5. **末日 world-gen 真 key 冒烟(验收门,§6 冒烟)**。

**测试矩阵(确定性,不打真实 API)**:
- **回归护城河**:规则怪谈走元数据驱动后,**Phase 1 全套测仍全绿**(139 + 14)——证重构没回归单模式。这是本批第一不变量(别为加末日弄坏规则怪谈)。
- 元数据:末日/规则怪谈条目结构正确、attributes key 集合对。
- init:`archetype` 合法值播种对应模式、非法值 → 400、未激活 archetype 处置。
- world-gen 分派:给定 archetype → 取对应注入块(可用录制/mock 验提示词组装含正确 worldview/attributes)。
- 前端面板:喂末日 attributes(含 hunger)→ 面板渲染"体力/饥饿"中文名;喂规则怪谈 → 渲染"体力/理智"(确认动态化没写死)。
- 引擎无知确认:`Engine.apply` 对 `{hp,hunger}` 与 `{hp,san}` 行为一致(同一通用结算)——证 ADR-008 决策 1。

**冒烟门(真 key,ADR-008 决策 5,此关不过不算完成)**:
- 跑几个末日 `init` → 验:world-gen 首次有效率(对照规则怪谈 ~100%,模式特有不外推)、格式完整(id 类型/endings/openingNarrative)、**hunger 轴在 attributes 里齐**、消毒投影无泄露。
- 驱几局回合 → **专门观测项(决策 2):AI 是否每回合稳定落 hunger 自然衰减**?衰减幅度合理吗?忘记/不稳定要如实记(影响所有衰减型数值模式的发现)。
- 末日叙事泄露人眼扫(规则形态变了,首次真实测末日面)。
- 存末日真实 raw + SSE 转录(补 world-gen 真实格式录制,untracked)。
- 这是 Felix 在场读数 + 拍板的步(像 Phase 1 几次冒烟):首次有效率/衰减稳定性/体感由他判,决定末日是否算验通、要否调提示词。

## 7. 落档路由
- ADR-008 先落(本稿引用它);本设计稿进 `docs/`;冒烟通过后 `/roadmap-update`(Phase 2 行 🟨);CONTEXT 回写按 ADR-008 落档路由(§三.5 展开 + 元数据结构,`schemaVersion` 仍 "0.2")。

## 8. 不在本批
- 修仙/赛博朋克等(各自独立批 + 独立 world-gen 冒烟,不外推末日成功);口渴/物资等末日额外轴(内容打磨);前端 archetype 选择 UI(独立小批);难度系统/配图(backlog);混合模式(Phase 3)。
