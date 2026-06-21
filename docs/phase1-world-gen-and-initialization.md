# Phase 1 · 规则怪谈 world-gen + INITIALIZING 设计稿

> 草案(决策已锁版) · 交给 Claude Code 当实现蓝本。
> 补全 Phase 1 整局闭环的**上游**:INITIALIZING(world-gen 胖调用)→ 接已落地的 PLAYING 回合循环 → ENDED。
> 配套:CONTEXT v0.3、event-loop 规格 §2、ADR-005(SSE 薄接缝)、ADR-006(回合线上协议);本稿 §9 出 ADR-007。

## 锁定决策(本批核心)

- **world-gen 与回合循环走不同线上格式**:回合丢 json_object 是为逐字流叙事;world-gen **保留 `response_format: json_object`、纯 JSON、无哨兵**。
- 依据:world-gen 输出绝大部分是结构(world/character/rules/endings/初始 actions),且 world-gen JSON 首次失败是**头号失败模式**(event-loop 规格 §6 自述「event-loop 本就比 world-gen 干净」);把可靠性留在最险的那次生成。真 key 冒烟已确认回合侧 plan A 成立,但该结论**不外推**到 world-gen——world-gen 独立保 json_object。
- **开场叙事不 token 流**:作为 world-gen JSON 里的一个字段,gen 完成后随 init 响应一次性下发(loading→世界浮现)。逐字流的 vibe 若要,放前端做 client-side reveal 动画,**后端不为它丢 json_object**。
- 这与 event-loop 规格 §2「开场叙事可流式·复用 §4」是**取舍分歧**:规格当时倾向复用流式接缝,冒烟数据让我们反过来选可靠性优先。本稿覆盖 §2 该条,理由入 ADR-007。

---

## 0. 边界:复用 / 新造

| 模块 | 状态 | 依据 |
|------|------|------|
| `validateWorld` / `WORLD_SCHEMA`(`GameSchemas`) | ✅ 复用零改 | 第一批已移植 + validator parity(8 world-gen golden) |
| `LooseJson`(剥围栏/取首 {…}/降级) | ✅ 复用 | 第一批 |
| `toClientState()` 消毒投影 | ✅ 复用 | 第一批;init 下发前必经 |
| PLAYING 回合循环(turn FSM + `EventLoopService` + 守卫) | ✅ 复用 | 第二批,121 绿 |
| 会话管理(`ConcurrentHashMap<saveId, …>`)、`OpenAiCompatLlmClient`、`ThinkingAdapter`、moderation no-op 接缝 | ✅ 复用 | 第一/二批 |
| **`WorldGenService`**(胖调用 + json_object + `validateWorld` + 一次修复 + ERROR) | 🆕 | 本批 |
| **整局状态机 / 会话生命周期**(INITIALIZING→PLAYING→ENDED/ERROR) | 🆕 | §1 |
| **真实 `POST /api/game/init`**(替换并退役 dev-init 桩) | 🆕 | §3、§6 |
| **`prompts/world-gen.md` 生产化**(沿用 bake-off 已验提示词,json_object) | 🆕 | §7 |
| **world-gen 失败 = 整局 ERROR(非 no-op 降级)** | 🆕 | §4,与回合降级口径不同 |

> 省力点:world-gen 的纯逻辑(`validateWorld`/`LooseJson`/`toClientState`)第一批已落且 parity 锁死;本批真正新写的是**胖调用编排 + 会话播种 + ERROR 路径 + init 端点**这些接缝件。回合循环原样接在 PLAYING 下。

---

## 1. 整局状态机(game-level)

```
INITIALIZING ──(world-gen 成功 + validateWorld 通过)──▶ PLAYING ──(结局命中/数值触底兜底)──▶ ENDED
     │                                                    │(嵌 turn FSM,第二批)
     └──(gen 连修复都救不回)────────────────────────────────┴──────────────────────────────▶ ERROR
```

- **INITIALIZING**:一发 world-gen 胖调用,产 `world + character(初值) + rules(含 isTrue/hiddenLogic, discovered=false) + endings + 初始 availableActions + openingNarrative`,`validateWorld` 校验。Phase 1 单模式**不拆** char/rule/ending-gen(省 round trip、bake-off 已验形态;分步留 fusion)。
- **PLAYING**:播种后进第二批的 turn FSM(`AWAITING_ACTION` 起),原样复用。
- **ENDED**:回合循环命中结局/兜底后到达,取 `endings[].id` 出结局画面。
- **ERROR**:world-gen 救不回 → 干净失败 + 提示重生成,**不进半残 PLAYING**(§4)。

---

## 2. world-gen 线上契约(已锁)

### 2.1 模型输出格式

**纯 JSON,开 `response_format: json_object`,无哨兵**。一个对象,贴 CONTEXT §二 schema v0.2:

```json
{ "schemaVersion":"0.2","mode":"single","archetypes":["rules_creepy"],
  "world":{"title","background","dangerLevel","tone"},
  "character":{"attributes":{"hp":100,"san":100},"traits":[…],"inventory":[…]},
  "rules":[{"id":<int>,"content","isTrue","hiddenLogic","discovered":false}],
  "endings":[{"id":<snake_case str>,"title","description","condition","reached":false}],
  "availableActions":[{"id":"A","text","hint"}],          // TURN_SCHEMA 限 2–4
  "openingNarrative":"<开场散文,整段,不流式>"               // 🆕 transient 字段,见 §3
}
```

- **id 约定严格按类型**(F-001):`rules[].id` 整数,`endings[].id` snake_case 英文字符串。混用即 `validateWorld` 失败。
- `state` **不由模型产**:引擎播种(turn 0 / ongoing / log 空 / timeline 由 world-gen 给的一句话世界线或 background 摘要)——`state` 是引擎维护字段(CONTEXT 字段职责)。

### 2.2 校验复用

`validateWorld(parsed)`(`WORLD_SCHEMA`,零改)→ 通过则播种会话。world-gen 无叙事回灌问题(narrative 本就是 JSON 字段),比回合简单。

---

## 3. init 请求/响应(plain POST,无 SSE)

不流式 → init **不用 `SseEmitter`**,就是阻塞 POST 返 JSON(守 ADR-005:SSE 只在真需要流的地方用)。

```
POST /api/game/init           { "archetype": "rules_creepy" }   // Phase 1 固定单模式
200 → { "saveId", "world": <消毒投影>, "openingNarrative": "<整段>", "availableActions": [...] }
5xx/4xx → { "error": { "code", "message" } }   // ERROR 路径,见 §4
```

- **`openingNarrative` 是 transient init 字段**,随响应一次性下发,**不进持久化 state** → 故 **`schemaVersion` 仍 "0.2",无字段变更、不升 schema 版**。客户端 reveal 动画(假装逐字)是前端事,后端整段返。
- world-gen 耗时数秒可接受(一次性 init + 前端 loading);若实测延迟/超时成问题,**后续**再加一条轻量 progress SSE,Phase 1 先 plain POST。

---

## 4. 可靠性(校验 + 修复 + ERROR,与回合降级不同口径)

1. 胖调用 → `LooseJson` 解析 → `validateWorld`。
2. 通过 → 播种 PLAYING。
3. **不通过 → 一次修复重试**:把校验错误回喂模型「只回修正后的完整 world JSON」,**修复发同样开 json_object**(world-gen 本就开,无切换成本)。
4. **修复仍失败 → 整局 ERROR**(🆕,**关键区别**):world-gen 失败**没有可保的前态**(尚无游戏),不像回合降级要守一局 ongoing。故不 no-op、不进半残 PLAYING,直接 ERROR + 面向用户「重新生成」。
5. 响亮告警 + 埋点 world-gen 首次有效率(F-005 质量监控钩子,§7)。

> 与回合的对照:回合修复用尽 → **保守 no-op**(守一局 ongoing,叙事当氛围);world-gen 修复用尽 → **ERROR**(无前态可守,干净重来)。别把两套口径混了。

---

## 5. 消毒边界(三视图,init 处首次施加于完整 world)

world-gen 产的 `rules[]` 含 `isTrue`/`hiddenLogic`(CONTEXT §三.9 视图 1/2);**init 响应只走视图 3**:

- 完整 world(含隐藏逻辑)= 引擎内存真理之源 + 存档 + 回合喂模型用;**绝不下发前端**。
- init 响应的 `world` 字段 = `toClientState()` 消毒投影,**剥 `isTrue`/`hiddenLogic`/`isCorrect`/`groundTruth`**;`rules` 未 discovered 的连 `content` 都按现有口径处理(discovered=false 初始,前端不拿隐藏逻辑)。
- **审核网关接缝**:init 下发的玩家可见文本(`world.background`/`title`/玩家可见 `rules.content`/`openingNarrative`)过 moderation no-op 接缝——ADR-004 落地时 world-gen 输出已在网关后,无需回改。Phase 1 仍 no-op。

---

## 6. 会话生命周期 + 播种 + 退役 dev-init

- `POST /api/game/init` → 新 `saveId` → `WorldGenService` 跑 world-gen → 完整 world 存会话(复用第二批 `ConcurrentHashMap` 会话管理,同时持真理之源 world + turn FSM `TurnState`)→ 整局态 INITIALIZING→PLAYING、turn FSM 置 `AWAITING_ACTION`、下发该 world 的初始 `availableActions` → 返消毒投影 + openingNarrative。
- **退役 dev-init**:删 `POST /api/dev/game/{id}/init` 桩(第二批临时入口),回合循环此后只经真实 init 播种。冒烟脚本若依赖 dev-init 同步更新。

---

## 7. `prompts/world-gen.md` 生产化

- **沿用 bake-off 已验 world-gen 提示词**(8 golden parity),适配生产:开 json_object、产全 schema + `openingNarrative` + id 类型约定(F-001)、泄露硬化(**禁止把 `hiddenLogic`/正确解法吐进 `world.background`/玩家可见 `rules.content`/`openingNarrative`**,§1b 同口径)。版本化(CONTEXT §三.6)。
- **F-005 质量监控**(已知弱点,非阻塞):单一种子致沉浸感套路化(bake-off 沉浸感 3.25)。提示词注入种子变量提多样性;埋点 world-gen 首次有效率 + 留意沉浸感,作为质量观察项,不卡本批落地。

---

## 8. 切分顺序(由纯到脏)+ 测试矩阵

**切分**(纯/复用件已就位,本批主要是接缝):
1. `WorldGenService`(胖调用 + json_object + `LooseJson` + `validateWorld` + 一次修复 + ERROR)——先它,可单测。
2. 会话播种(init → 存完整 world → 引擎播种 state → 置 PLAYING/turn FSM `AWAITING_ACTION`)。
3. 真实 `POST /api/game/init`(消毒投影 + openingNarrative + 初始 actions)+ 退役 dev-init。
4. `prompts/world-gen.md` 生产化。
5. **整局集成冒烟(真 key)**:init 真实世界 → 玩到结局 → **此处并验递延的 `ending` + §5 兜底真实端到端**(回合冒烟没跑到的结局/触底路径,在这第一次真实通关里一并验)。

**测试矩阵**:
- **world-gen parity(复用 8 golden)**:录制 raw world-gen → `LooseJson` → `validateWorld` 通过 → 播种 → 断言(a)init 消毒投影**无** `isTrue`/`hiddenLogic`,(b)PLAYING 播种了正确初始 `availableActions`、turn FSM 在 `AWAITING_ACTION`。
- **修复路径**:`validateWorld` 失败 → 一次修复(json_object)→ 成功播种。
- **ERROR 路径**(🆕):修复仍失败 → 会话 ERROR、**不进 PLAYING**、返重生成提示;断言无半残会话残留。
- **init 消毒断言**:init 响应整串无 `isTrue`/`hiddenLogic`/`isCorrect`/`groundTruth`(硬安全闸,独立测,别只内嵌)。
- **moderation 接缝**:init 玩家可见文本过 no-op 网关(断言被调用)。
- **整局集成冒烟**:见切分 5,含 ending/兜底真实端到端。

---

## 9. ADR-007 素材(交 `/adr-author`)

- **标题**:world-gen 线上协议——胖调用 + json_object 纯 JSON + 开场叙事 reveal 不流式(可靠性优先,异于 ADR-006 回合协议)
- **背景**:ADR-006 为逐字流回合叙事丢了主调用 json_object;world-gen 输出主体是结构、且 JSON 首次失败是头号失败模式,真 key 冒烟仅证回合侧 plan A、不外推 world-gen。需为 world-gen 单独定线上格式。
- **决策**:world-gen **保 json_object、纯 JSON、无哨兵**;开场叙事作字段一次性下发,不 token 流。
- **备选(记录)**:复用 §4 流式接缝逐字流开场叙事(event-loop 规格 §2 原倾向)→ 否决:为开场 vibe 在最险生成上丢 json_object,可靠性代价不值;vibe 可由前端 reveal 动画补。
- **后果**:正面=最险生成保最高可靠、修复发零切换成本、init 端点更薄(无 SSE)、下游 `validateWorld` 零改;代价=开场叙事不逐字流(前端 reveal 补偿)、整局两套线上口径(回合 vs world-gen,文档须讲清)。

---

## 10. 决策状态 / 待补 / 边界

**已锁**:① world-gen 保 json_object 纯 JSON 无哨兵;② 开场叙事 reveal 不流式;③ world-gen 失败 = ERROR(非 no-op);④ `openingNarrative` transient、`schemaVersion` 不动。

**待你过一眼(非阻塞)**:
- init 用 plain POST(非 progress SSE)——延迟可接受的前提下选最薄;若实测 world-gen 耗时触超时再加轻量 progress 流。
- `openingNarrative` 命名/落点:倾向独立 transient 字段(不入持久化 state,保 schemaVersion);若日后要存档复现开场,再议是否进 schema(那才涉及版本号)。

**落档路由**:本稿进 `docs/`;§9 走 `/adr-author` 成 ADR-007;§2 线上口径 + §5 init 消毒回写 CONTEXT(约定层补充,`schemaVersion` 不动,CONTEXT 文档版本视情升 v0.4);之后 `/roadmap-update`。顺手把 event-loop 规格 §2 该条标注「reveal 不流式,理由见 ADR-007」。

**本批边界(不做)**:fusion/混合模式(Phase 3)、多模式(仅 `rules_creepy`)、真实 moderation 实现(ADR-004 待定,仅接缝)、CloudBase 部署/ICP 备案、前端(独立批;client-side reveal 动画属前端)。
