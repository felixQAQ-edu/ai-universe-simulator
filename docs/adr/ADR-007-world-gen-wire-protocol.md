# ADR-007 · world-gen 线上协议:胖调用 + json_object 纯 JSON + 开场叙事 reveal 不流式(可靠性优先,异于 ADR-006)

- **日期**:2026-06-21
- **状态**:已采纳
- **决策者**:Felix

## 背景

Phase 1(单模式 H5 闭环·规则怪谈)补全整局闭环的**上游**:INITIALIZING(world-gen 胖调用)→ 已落地的 PLAYING 回合循环 → ENDED。回合循环的线上格式已由 [ADR-006](ADR-006-event-loop-streaming-wire-protocol.md) 敲定——**为逐字流叙事丢了主调用的 `response_format: json_object`**,靠「叙事先行 + 哨兵 `<<<DELTA>>>` + 结构化尾巴 + 叙事回灌」补回可靠性。开写 world-gen 业务前,要先定 world-gen 自己的线上格式:是沿用 ADR-006 那套(把开场叙事也逐字流),还是另立一套。

矛盾点在于:ADR-006 丢 json_object 是**回合**场景的合理取舍——回合输出主体是叙事(逐字体验是底线),结构化尾巴小、且 event-loop 本就比 world-gen 干净。但 world-gen 不同:

1. **world-gen 输出主体是结构**,不是叙事——一把出 `world` / `character` 初值 / `rules`(含 `isTrue`/`hiddenLogic`)/ `endings` / 初始 `availableActions`,开场叙事只是其中一个字段。
2. **world-gen JSON 首次失败是头号失败模式**:bake-off 的 schema/质量问题(FINDINGS F-001/F-004)几乎全 tag 在「world-gen 步」(涉及 endings/character/id 类型),event-loop 规格 §6 也自述「event-loop 本就比 world-gen 干净」。
3. 真 key 冒烟仅证了**回合侧** plan A(丢 json_object + 哨兵 + 修复)可靠性成立,这个结论**不外推**到 world-gen——它是更险的那次生成。

于是 world-gen 需要独立定线上格式:把可靠性留在最险的那次生成。

约束条件:

1. **可靠性优先于开场 vibe**:world-gen 失败 = 整局起不来(无前态可守,设计稿 §4),比回合里一次叙事降级严重得多。
2. **复用已验证资产零改**:`validateWorld` / `WORLD_SCHEMA`(第一批移植 + 8 golden accept-parity)、`LooseJson`、`toClientState` 必须一行不改地复用(守第一批护城河)。
3. **守 ADR-005 薄接缝**:init 不该为了「开场 vibe」凭空引入 SSE 复杂度,除非真有逐字流需求。
4. **schema 不动**:开场叙事的落点不应触发 `schemaVersion` 升版(CONTEXT 字段语义稳定)。

## 候选方案

### 方案 A:复用 ADR-006 回合接缝,逐字流开场叙事

world-gen 也走「叙事先行 + 哨兵 + 结构化尾巴」,开场叙事逐字流给玩家,结构尾巴回灌。这是 event-loop 规格 §2「开场叙事可流式·复用 §4」的原倾向。

**优点**:
- 开场就有逐字流 vibe(loading→世界一个字一个字浮现),沉浸感强。
- 与回合复用同一套切分/回灌接缝,代码路径统一。

**缺点**:
- **为开场 vibe 在最险的那次生成上丢 json_object**——world-gen 首次有效率本就是头号风险点(约束 1),拿掉可靠性拐杖代价最高、最不值。
- world-gen 输出主体是结构而非叙事,逐字流叙事的收益本就小(开场散文只占产出一小部分),却要为它牺牲整个胖调用的结构可靠性。
- init 被迫上 SSE,端点变重(违约束 3)。

### 方案 B:保 json_object 纯 JSON,开场叙事作字段一次性下发(本 ADR 采纳)

world-gen **保留 `response_format: json_object`、输出纯 JSON、无哨兵**;开场叙事作为 world-gen JSON 里的一个 `openingNarrative` 字段,gen 完成后随 init 响应**一次性下发**(loading→世界浮现)。init 是 **plain POST 无 SSE**。逐字流的 vibe 若要,放前端做 client-side reveal 动画。

**优点**:
- **最险的那次生成保住最高可靠性**(命中约束 1):json_object + `validateWorld` + 一次修复,贴 bake-off 验证形态(world-gen 首次有效率 ~97%、修复后 100%)。
- **下游零改**(命中约束 2):`openingNarrative` 是 JSON 字段,无叙事回灌问题(回合才需要回灌);`validateWorld`/`LooseJson`/`toClientState` 一行不改复用。
- **init 端点更薄**(命中约束 3):阻塞 POST 返 JSON,不碰 `SseEmitter`;修复发零切换成本(world-gen 本就开 json_object)。
- **schema 不动**(命中约束 4):`openingNarrative` 是 transient init 字段,不进持久化 `state`,`schemaVersion` 仍 `"0.2"`。

**缺点**:
- 开场叙事不逐字流——loading 后整段浮现,无「一个字一个字冒出来」的原生 vibe。缓解方式:前端用 client-side reveal 动画(假装逐字),后端不为它丢 json_object。
- **整局两套线上口径**(回合 ADR-006 丢 json_object + 哨兵 / world-gen 保 json_object 纯 JSON),文档须讲清,新人易混。缓解方式:两份 prompt(`event-loop.md` / `world-gen.md`)各自标注口径与依据,本 ADR 与 ADR-006 互引。

## 最终决策

**方案 B — world-gen 保 json_object、纯 JSON、无哨兵;开场叙事作字段一次性下发,不 token 流。**

### 1. 线上格式

world-gen 一次胖调用,开 `response_format: json_object`,输出**一个纯 JSON 对象**(贴 CONTEXT §二 schema v0.2),含一个新增 transient 字段:

```json
{ "schemaVersion":"0.2","mode":"single","archetypes":["rules_creepy"],
  "world":{…}, "character":{…}, "rules":[{…,"isTrue","hiddenLogic"}],
  "endings":[{…}], "availableActions":[{"id":"A",…}],
  "openingNarrative":"<开场散文整段,不流式>" }
```

- `rules[].id` 整数 / `endings[].id` snake_case 字符串(F-001,id 类型约定混用即 `validateWorld` 失败)。
- `state` 不由模型产,引擎播种(CONTEXT 字段职责)。

### 2. init 请求/响应(plain POST,无 SSE)

```
POST /api/game/init      { "archetype": "rules_creepy" }
200 → { "saveId", "world": <消毒投影>, "openingNarrative": "<整段>", "availableActions": [...] }
5xx → { "error": { "code", "message" } }     // world-gen 救不回 = 整局 ERROR
```

- `world` 走 `toClientState()` 消毒投影(剥 `isTrue`/`hiddenLogic`,设计稿 §5)。
- `openingNarrative` 是 **transient init 字段**:随响应一次性下发,从 world 根剥除后再播种引擎(**不进持久化 state**)→ 故每回合 contextJson/消毒投影都不夹带它,`schemaVersion` 仍 `"0.2"`。

### 3. 可靠性(异于回合降级口径)

胖调用 → `LooseJson` → `validateWorld`;不通过 → **一次修复**(开同样 json_object,零切换成本)→ 仍败 → **整局 ERROR**(非 no-op 降级)。world-gen 失败**没有可保的前态**(尚无游戏),不像回合降级要守一局 ongoing → 直接干净失败 + 提示「重新生成」,**不进半残 PLAYING**。

### 关键理由

1. **可靠性留在最险的那次生成**(约束 1):world-gen 首次失败是头号失败模式,真 key 冒烟只证了回合侧、不外推——world-gen 独立保 json_object 是把可靠性花在刀刃上。
2. **复用第一批护城河零改**(约束 2):`validateWorld` + 8 golden accept-parity + `LooseJson` + `toClientState` 全部一行不改;world-gen 无叙事回灌问题(narrative 本就是 JSON 字段),比回合更简单。
3. **避开「为 vibe 牺牲可靠性」陷阱**:开场逐字流的体验收益(world-gen 输出主体是结构)远不抵丢 json_object 的可靠性代价;vibe 由前端 reveal 动画无成本补偿。
4. **保留演进路径**:若实测 world-gen 延迟/超时成问题,后续可加一条轻量 progress SSE(不改本决策的 json_object 内核);若日后要存档复现开场,再议 `openingNarrative` 是否进 schema(那才涉及版本号)。

## 已知代价

1. **开场叙事不逐字流**:loading 后整段浮现,无原生逐字 vibe。缓解方式:前端 client-side reveal 动画(假装逐字),后端不为它丢 json_object。
2. **整局两套线上口径**:回合(ADR-006:丢 json_object + 哨兵 + 回灌)vs world-gen(本 ADR:保 json_object 纯 JSON),理解成本 +1。缓解方式:两份 prompt 各自标口径,本 ADR ↔ ADR-006 互引;event-loop 规格 §2「开场叙事可流式」一条已标注「reveal 不流式,见 ADR-007」覆盖。
3. **world-gen 失败即整局 ERROR**(非降级):比回合 no-op 严重,玩家需重新生成。接受理由:无前态可守,半残 PLAYING 比干净重来更糟;一次修复已兜住绝大多数 JSON 首次失败。

## 重新审视的触发条件

- 上线后埋点 **world-gen 首次有效率**:若持续低于阈值(参照 bake-off ~97%),评估是否加第二次修复或换 prompt 策略。
- init **延迟/超时**成体验瓶颈(world-gen 耗时数秒):加一条轻量 progress SSE(保 json_object 内核不变)。
- 引入**混合模式 fusion**(Phase 3):world-gen 可能拆成多步 meta-prompt,届时重审「一把出」是否仍最优(本 ADR 仅约束单模式单次胖调用)。
- 产品决定**存档复现开场**:`openingNarrative` 若要进持久化 state,触发 `schemaVersion` 升版讨论。

## 实施步骤

1. ✅ `WorldGenService`(胖调用 + json_object + `LooseJson` + `validateWorld` + 一次修复 + ERROR)+ `WorldGenException`。
2. ✅ `GameInitService` 播种编排(提取 transient `openingNarrative` + 解析初始动作 FALLBACK + 消毒投影 + moderation 接缝)。
3. ✅ 真实 `POST /api/game/init`(plain POST)+ 退役 dev-init 桩(`/api/dev/game/{id}/init`)。
4. ✅ `prompts/world-gen.md` 生产化(json_object + `openingNarrative` + 初始 actions + id 类型约定 + 泄露硬化);运行时同义副本 `WorldGenPromptBuilder`。
5. ✅ 测试矩阵:world-gen parity(复用 8 golden)+ 修复路径 + ERROR 路径 + init 消毒断言(独立)+ moderation 接缝被调用。`mvn test` 全绿(139)。
6. ⏳ 整局集成冒烟(真 key,`DEEPSEEK_API_KEY` 就位后):init 真实世界 → 玩到结局 → 并验递延 ending + §5 触底兜底端到端(见 `server/README.md`「整局闭环冒烟」)。

## 实际效果(事后补充)

*真 key 整局通关时回填:world-gen 首次有效率(对比 bake-off ~97%)、init 延迟、ERROR 触发率;以及一次修复是否足够兜住 JSON 首次失败。*

## 跟其他文档的交叉引用

- **配套设计稿**:[phase1-world-gen-and-initialization.md](../phase1-world-gen-and-initialization.md)(本 ADR 素材源,§9)
- **对照决策**:[ADR-006](ADR-006-event-loop-streaming-wire-protocol.md)(回合线上协议——丢 json_object + 哨兵;本 ADR 反过来选可靠性优先,两套口径刻意不同)
- **接缝基础**:[ADR-005](ADR-005-sse-web-stack-mvc-thin-seam.md)(SSE 薄接缝;init 不上 SSE 正是守它「只在真需要流的地方用」)
- **provider 抽象**:[ADR-001](ADR-001-runtime-model-and-provider-abstraction.md)(world-gen 同走 OpenAI 兼容 + json_object)
- **约定真理之源**:CONTEXT §二(schema v0.2)/ §三.9(三视图消毒);本 ADR 回写 §三 world-gen 口径
- **配套源文件**:`server/.../worldgen/`(`WorldGenService`/`GameInitService`/`WorldGenPromptBuilder`)、`prompts/world-gen.md`
