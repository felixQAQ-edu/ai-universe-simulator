# Phase 2 · A 计划 — archetype 选择 UI(设计 + Code opener)

> 交新 Claude Code 窗口。从 `main`(@ `289925e`,Phase 2 第一批已合)起新分支 `phase2/archetype-select-ui`。
> 性质:已验证闭环的 UI 改造小批(低风险)。补上"末日只能 curl 驱动"→ 前端能选模式玩。
> 纯现有栈(React + 原生 CSS/CSS Modules),**不引入 GSAP 或任何新动画库**(见末尾 backlog)。

## 0. 目标 + 定位

两个模式(规则怪谈 `rules_creepy` + 末日 `apocalypse`)后端都验通了,但玩家**还选不了**。本批补选择 UI:玩家进游戏 → 看见可玩世界 → 选一个 → init 该模式 → 进已验证的整局闭环。

**定位升格(关键)**:这是玩家进游戏的**第一屏 = 产品门面**。不做干巴巴下拉框,做**有氛围感的世界选择**——既补功能,又承接"想让游戏更好看更有吸引力"的诉求。纯 React + CSS 就能做出质感,无需 GSAP。

## 1. 后端已就位(确认,基本零改)

- `POST /api/game/init { archetype }` 已接受值 + 枚举校验(非法 400)——ADR-008 决策 4 第一批已落。
- archetype 元数据(`ArchetypeRegistry`)已有 `displayName`/`worldview`/`attributes`——选择卡片要的展示信息**后端已有**。
- **可能需要一个轻量端点**:`GET /api/archetypes` 返回"已激活的可选模式列表"(每个含 `archetype` id / `displayName` / 一句话描述 / 危险或氛围提示),供前端渲染选择屏。**先确认 `ArchetypeRegistry` 能否暴露这个**;若元数据缺"一句话玩家描述/氛围色",补进 registry(玩家可见中文文案,守 CONTEXT §三.3)。这是本批后端唯一可能的小改。
- 未激活 archetype(`life_sim`/`cultivation`/`cyberpunk`)：列表里可**标"敬请期待"灰显**,不可选——给玩家"未来有更多世界"的预期,也为 Phase 2 后续模式留位。

## 2. 前端设计(本批主体)

**选择屏(新)**:
- 每个可玩模式一张**氛围卡片**:`displayName`(规则怪谈/末日生存)+ 一句话钩子 + 危险等级/氛围标签 + 模式专属色调(规则怪谈=冷色诡异/末日=荒凉琥珀,复用 Phase 1 的 `--accent` 思路按模式取色)。
- 未激活模式:灰显卡片 + "敬请期待"。
- 选中 → 进 init loading → 复用 Phase 1 已验证的 开场 reveal → 回合 → 结局 流程,**零改动**。
- 移动优先(复用 scaffold 基底),卡片在窄屏纵向堆叠。

**质感来源(无 GSAP)**:CSS 渐变/阴影/边框做卡片层次;hover/选中态用 CSS transition;若要入场动效,CSS animation(类似 A-1 reveal)足够。**不引入 GSAP**——它对小程序(Taro)支持差,违 ADR-003 跨端边界(见 backlog)。

**守 ADR-003 边界**:选择屏是纯展示 + 一个 init 调用;`GET /api/archetypes` 走 `api/` 适配层(逻辑/状态层不碰平台 API);archetype 列表数据进 store 或局部 state 均可,但网络调用只在 `api/`。

## 3. 切分顺序

1. (后端,若需)`GET /api/archetypes` 暴露已激活模式列表 + 补 registry 玩家文案/氛围提示。
2. `api/` 适配层加 `listArchetypes()`(provider-agnostic,H5 用 fetch 实现)。
3. 选择屏组件(氛围卡片 + 灰显未激活 + 选中→init)。
4. 接入整局流程:start → **选择屏** → init(选中的 archetype)→ 已验证闭环。
5. 移动优先打磨 + 模式色调。

## 4. 测试 / 验收

- `api/` 层:mock fetch,断言 `listArchetypes()` 解析正确、`initGame(archetype)` 传对值。
- 选择屏组件:渲染两张可玩卡片(规则怪谈/末日)+ 未激活灰显;点击 → 触发 init 对应 archetype。
- 边界纪律:逻辑/状态层零平台 API import(沿用 ESLint 规则)。
- **回归**:Phase 1/2 已有测全绿(server 154 + frontend 18),本批是新增屏,不该动已验证闭环。
- **手动 e2e(真 key,我在场)**:浏览器从选择屏分别进规则怪谈 + 末日各玩几回合,验"选哪个就进哪个世界"+ 选择屏体感(好不好看、有没有吸引力)。这次体感判断是本批重点之一。

## 5. 纪律

- 一段完整工作做完再 commit;**不替我 push/合并**(等 e2e 我定);`bakeoff/recordings/` 别碰。
- **不引入 GSAP 或新动画库**;质感纯 CSS。
- 发现偏差(尤其 registry 暴露不了选择屏要的数据、或后端改动比预期大)如实报。

## 6. 收口(e2e 过后我定了再做)
- `/roadmap-update`(选择 UI 落地,多模式真正可玩可选);CONTEXT 视情(若加了 `/api/archetypes` 契约或 registry 玩家文案字段)。

---

## backlog 记一笔(GSAP,暂不引入)

GSAP(GreenSock,官方出了 AI skills 包)是重型 JS 动画库,擅长复杂时间线/滚动驱动/形变动效。**暂不引入**,原因:(1) 当前需求(逐字 reveal、规则高亮、选择屏质感)CSS 足够,未到需要重型动画引擎;(2) **GSAP 对微信小程序(Taro,非 DOM 渲染)支持差**,违 ADR-003 跨端边界纪律,现在引入=给 Phase 4 迁移埋税。**触发再评估**:若 Phase 2/3 出现动效密集需求(分享长图炫酷动画、混合模式视觉特效),重评 GSAP——但须先确认其在 Taro/小程序侧可行性。记入 backlog,不丢这个发现。
