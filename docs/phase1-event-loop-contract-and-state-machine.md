# Phase 1 · 规则怪谈 event-loop 契约 + 状态机规格

> 草案(决策已锁版) · 交给 Claude Code 当实现蓝本。
> 来源:把 bake-off 已验证逻辑(`scenarios.py` 的 `Engine` + `client.py` + `schema.py`)形式化为生产契约。
> **已锁决策**:线上协议 = **单次调用**(叙事先行 + 哨兵 + 结构化尾巴);数值 = **绝对值**。
> 配套:CONTEXT v0.2、ADR-001(provider 抽象)、ADR-002(CloudBase)、ADR-005(SSE 薄接缝)、ADR-006(本规格 §8)。

---

## 0. 边界声明:什么已验证 / 什么是新设计

实现前必读。下表把「照搬即可」和「新造、未经 bake-off 验证、要配单测」分开。

| 模块 | 状态 | 依据 / 注意 |
|------|------|------------|
| `Engine.apply()` 结算序列 | ✅ 已验证 | `scenarios.py`,连推 10 回合三路径自洽 |
| 数值=**绝对值**,clamp 0–100,跳变 >40 标「需复核」不拒绝,允许有据恢复 | ✅ 已验证 | F-003;`JUMP_THRESHOLD=40` |
| `triggeredRuleIds`(一致性追踪)/ `discoveredRuleIds`(玩家可见 flag)两数组(整数 id) | ✅ 已验证 | `apply()` + `TURN_SCHEMA` |
| 抽取式 logSummary(`LOG_KEEP=4`,折成 `[T{n}选{action}]`,零 LLM 成本) | ✅ 已验证 | `apply()` 日志压缩段 |
| `hp/san≤0 → status=ended` 兜底 | ✅ 已验证 | 但**未指定结局 id**(§5 补丁) |
| 容错 JSON 解析 + **一次**修复重试 | ✅ 已验证 | `_parse_json` / `_generate` |
| `validate_turn` / `validate_world` / `detect_leak` | ✅ 已验证 | `schema.py`;**经叙事回灌后可原样复用**(§4.4) |
| 真理之源每回合回传(`context_json`:全量 world 含 hiddenLogic + state + attributes) | ✅ 已验证 | 模型侧需要 hiddenLogic 裁决 |
| **流式线上协议**(叙事 out-of-band + 命名 SSE 事件 + 叙事回灌) | 🆕 新设计 | bake-off 是单 `json_object`、无流式、无客户端 → ADR-006 |
| **客户端消毒投影**(剥 isTrue/hiddenLogic) | 🆕 新设计 | bake-off 无客户端 |
| **动作合法性校验 + 非法动作处置** | 🆕 新设计 | `_pick` 只挑合法动作,从未触发 |
| **忙态守卫(并发/双提交)** | 🆕 新设计 | bake-off 单线程同步 |
| **兜底结局 id 指派** | 🆕 新设计 | §5 |
| **泄露命中处置(流式路径=遥测,非防护)** | 🆕 新设计 | §1;`detect_leak` 只记录、且只抓逐字 |
| **单次调用丢 json_object 的可靠性补偿** | 🆕 新设计 | §6 |

> 核心省力点:happy path 把**已流出的叙事回灌进 parsed** 后,`validate_turn` / `detect_leak` / `apply` **一行不改**地复用;
> 真正新写的只有「哨兵切分 + 叙事捕获/回灌 + SSE 事件 + 消毒投影 + 合法性/忙态/兜底/降级」这些接缝件。

---

## 1. 三份 state 视图与消毒边界(焊死 hiddenLogic 不泄露)

LLM 无记忆,state 是真理之源(CONTEXT §三.1)。生产里同一份 state 三个投影:

1. **引擎内部全量**:含 `rules[].isTrue` / `hiddenLogic`。真理之源,只在 server 内存/存档。
2. **喂模型的**(= `context_json()` 现状):**也含 hiddenLogic**——模型必须知道隐藏逻辑才能裁决真假规则。
3. **客户端消毒投影**:**绝不含 `isTrue` / `hiddenLogic`**。任何下发前端的 SSE 事件与 state 快照都走它。`discoveredRuleIds` 命中后,前端只拿规则 `content`(已 discovered),不拿那两个字段。

**泄露防御——分清「防护」与「遥测」**(选了流式后的真实口径):

- (a)**结构层(实时防护,有效)**:消毒投影直接剥掉 `isTrue`/`hiddenLogic`——结构化通道前端永远拿不到隐藏逻辑。
- (b)**提示词(实时防护,主力)**:event-loop / world-gen 系统提示词硬性禁止把 isTrue/hiddenLogic 吐进玩家可见文本。
- (c)**`detect_leak` 内容扫描(流式路径=事后遥测,非防护)**:
  - 局限一:叙事**逐字流给玩家**,流完才扫 → **玩家已看到,救不回**。
  - 局限二:只抓 `LEAK_TOKENS=(isTrue, hiddenLogic, isCorrect, groundTruth)` 字段名 + **逐字照抄**(≥8 字符子串)的 hiddenLogic;**抓不到改写/复述式泄露**。
  - 故其角色 = 记日志 + 标记该存档/回合供复核 + 喂离线提示词硬化;**不是实时拦截器**。
  - 高危模式可选「**先缓冲叙事、`detect_leak` 扫干净再流**」(牺牲 TTFT 换安全),Phase 1 规则怪谈先不做。

---

## 2. 整局状态机(game-level)

```
INITIALIZING ──(world-gen 成功)──▶ PLAYING ──(结局命中/兜底)──▶ ENDED
     │                               │
     └──(生成失败,修复也救不回)──────┴────────────────────────▶ ERROR
```

- **INITIALIZING**:一发 **world-gen 胖调用**,产出 `world + character(初值) + rules + endings + 初始 availableActions`,`validate_world` 校验。
  > 依据:F-001/F-004 失败全 tag 在「world-gen 步」且涉及 endings/character → bake-off 就是一把出。
  > Phase 1 单模式**不拆** char/rule/ending-gen(省 round trip、已验证形态);分步留给将来 fusion。开场叙事可流式(复用 §4)。
- **PLAYING**:跑单回合状态机(§3),循环到 `status=ended`。
- **ENDED**:取命中的 `endings[].id` 出结局画面。
- **ERROR**:生成连修复都救不回 → 优雅降级,不脏写 state(§6)。

---

## 3. 单回合状态机(turn-level)

```
AWAITING_ACTION ─▶ VALIDATING_ACTION ─▶ GENERATING ─▶ SETTLING ─┬─▶ AWAITING_ACTION (ongoing)
      ▲  (只有此态接受玩家动作)                                    └─▶ ENDED
      └──────────────(非法动作:确定性拒绝,不调 LLM)──────────────┘
```

- **AWAITING_ACTION**:已下发 `availableActions`,等玩家选 id。
  **忙态守卫(🆕)**:仅此态接受动作;`GENERATING`/`SETTLING` 期间到来的动作一律拒(防一回合被双花)。
- **VALIDATING_ACTION(🆕)**:引擎校验所选 id ∈ 当前 `availableActions`(id 是字符串)。
  非法 → 发 `event: error`、**不调 LLM**、停在 `AWAITING_ACTION`。Phase 1 **只允许选 id,不开自由文本**。
- **GENERATING**:组 event-loop prompt(`context_json` + `PLAYER_ACTION`)→ LLM 调用 → 叙事逐字流式(§4),结构化尾巴 server 缓冲。
- **SETTLING**:回灌叙事 → 校验/修复(§6)→ `Engine.apply()`(§5)。**先结算数值,再判结局**(结局条件可能依赖 post-apply 数值)。
- 分支:`ongoing` → 回 `AWAITING_ACTION`(下发新 `availableActions`);`ended` → `ENDED`。

---

## 4. event-loop 线上契约(the wire) — 单次调用(已锁)

### 4.1 玩家 → server

```
POST /api/game/{saveId}/turn
{ "turn": <int 当前回合>, "actionId": "A" }
```

### 4.2 server → 玩家(SSE 命名事件)

| event | data | 客户端动作 |
|-------|------|-----------|
| `narrative` | `{ "text": "<token 增量>" }` | 逐字追加到散文区 |
| `delta` | **消毒后**的状态变化(hp/san/discovered rules/turn) | 流末一次性更新数值/规则面板 |
| `ending` | `{ "id", "title", "description" }` | 出结局画面 |
| `error` | `{ "code", "message" }` | 非法动作 / 不可恢复失败 |

顺序天然保证「叙事流完 → 才应用 delta」。**切分逻辑放 `EventLoopService`**(消费 `TokenStream`、哨兵切分、产两路),**不放 web 层、不放 `TokenStream`**——守 ADR-005 薄接缝。

### 4.3 模型输出格式(单次调用)

```
<叙事散文,逐字流给玩家>
<<<DELTA>>>
{ "stateUpdate": {...}, "triggeredRuleIds": [...], "discoveredRuleIds": [...],
  "availableActions": [...], "ending": null | {...} }
```

- 哨兵 `<<<DELTA>>>`:server 转发叙事 token 到哨兵为止(留「哨兵长度-1」尾缓冲做跨 chunk 边界扫描),其后缓冲为结构化尾巴。
- **主调用不能开 `response_format: json_object`**(输出非纯 JSON)。可靠性靠强提示词 + 哨兵 + §6 尾巴修复(修复那发**可开回** json_object)。
- 需改 `prompts/event-loop.md`:prose 先行 + 哨兵 + 尾巴**去掉 `narrative` 字段**(版本化,CONTEXT §三.6)。

### 4.4 校验复用:叙事回灌(关键,免改 schema)

`TURN_SCHEMA` **要求 `narrative`**,`apply()` 也从 `parsed["narrative"]` 取值(log + 泄露扫描)。流式把叙事移出了 JSON,故:

```
1. 流式时捕获哨兵前全部散文为字符串 N(同时已逐字下发玩家)
2. 解析哨兵后的尾巴 → parsed(_parse_json,剥围栏/取首个 {…})
3. 回灌:parsed["narrative"] = N          ← 关键一步
4. validate_turn(parsed)                   ← 现成校验器,一行不改
5. 通过 → Engine.apply(parsed, actionId)   ← 现成引擎,一行不改(读 parsed["narrative"])
```

尾巴键名**严格沿用**(否则引擎/校验读不到):`stateUpdate{hp,san,timeline}` / `triggeredRuleIds[int]` / `discoveredRuleIds[int]` / `availableActions[{id,text,hint}]`(`TURN_SCHEMA` 限 **2–4** 个)/ `ending: null | {id<string>, reached<bool>}`。
结局命中:`"ending": {"id":"survive_dawn","reached":true}`(`id` 必须存在于 world `endings[]`)。

---

## 5. 数值权威与结算(`Engine.apply()`,照搬 + 补丁)

**权威归引擎**:AI 提议、引擎校验落账。`apply(parsed, player_action_id)` 序列(照搬):

1. `turn += 1`
2. 读 `stateUpdate.hp` / `.san` 为**新绝对值**(缺省=当前值)
3. 一致性核对:`abs(new - old) > 40` → push `issues`「需复核」**(不拒绝,允许有据恢复 F-003)**
4. `clamp(0,100)` 后写 `hp/san`
5. `timeline = stateUpdate.timeline`(缺省保留)
6. `triggered |= triggeredRuleIds`;`discoveredRuleIds` → `world.rules[id].discovered=true`
7. `detect_leak(narrative)` → 命中处置(§1c:流式路径记日志/标记,非实时拦截)
8. 追加 `log`;`len(log) > 4` → 旧的折进 `logSummary`(§7)
9. 结局:`parsed.ending.reached` → `status=ended` + 标 `endings[id].reached`
10. **兜底**:`hp≤0 or san≤0` → `status=ended`

**三道数值闸门的分工(别混)**:
- **schema 范围**(`TURN_SCHEMA` hp/san ∈ 0–100):**硬**,越界 → `validate_turn` 失败 → §6 修复(在 apply 之前就拦掉)。
- **跳变标记**(>40):**软**,在范围内的大跳变,记 `issues` 供复核,不拒绝。
- **clamp**:防御纵深兜底(理论上 schema 已挡住越界)。

**🆕 补丁**:第 10 步只设 `status=ended` 却**没指定结局 id** → 前端无结局可显。生产补:数值触底但 AI 未给 `ending` 时,引擎**强制兜一个坏结局 id**(在 world `endings[]` 找 `condition` 匹配 `hp<=0`/`san<=0` 的那条,如 `lost_mind`;找不到用约定 fallback)。即 **AI 主导 + 引擎兜底**。

---

## 6. 结构化可靠性(校验 + 修复 + 降级)

1. 切出尾巴(哨兵后)→ `_parse_json`(剥 ```json 围栏、取第一个 `{…}`)。
2. **回灌叙事**(§4.4 第 3 步)→ `validate_turn(parsed)`。
3. 通过 → `apply()`。
4. **不通过 → 一次修复重试**(照搬 `_generate`):把校验错误回喂模型「只回修正后的**结构化尾巴** JSON」,**此发可开 json_object**;**已流出的叙事是 canonical**,不让修复改它 → 把同一个 N 回灌进修复后的尾巴再校验。
5. **修复仍失败 → 保守 no-op(🆕 降级)**:`turn++`、不动数值、复用/重生 `availableActions`,**响亮告警**,已展示叙事当氛围文字。**绝不脏写 state**。
6. **叙事本身非法**(回灌后 `narrative` 空 / minLength 失败):修复救不了(已流出)→ 直接走第 5 步降级。罕见。

> 注:主调用丢 json_object,首次有效率可能略低于 bake-off ~97%;但 event-loop 本就比 world-gen 干净、尾巴更小、修复发开回 json_object → 综合可接受。上线后埋点盯首次有效率,持续低于阈值再评估切两次调用。

---

## 7. log 压缩(照搬,抽取式,零 LLM 成本)

- `LOG_KEEP = 4`:近 4 回合 `log` 原文回传;更旧的折进 `logSummary`,格式 `[T{turn}选{action}]`。
- 每回合最多折 1 条,纯字符串拼接,**不调 LLM**。
- `timeline`(一句话世界线)由 AI 经 `stateUpdate.timeline` 维护。
- 续写连贯性若下降,再升级 LLM 抽象式摘要 + 批量滚出;Phase 1 不做。

---

## 8. ADR-006 素材(交给 `/adr-author`)

- **标题**:event-loop 流式线上协议——叙事先行单次调用 + 哨兵 + 结构化尾巴 + 叙事回灌复用
- **背景**:bake-off 验证的 event-loop 输出是单 `json_object`(narrative 是字段、开 json_object),无法逐字流式;生产要 SSE 流叙事(ADR-005),需重定线上格式,同时保住可靠性与成本。
- **决策**:**单次调用,叙事先行 + 哨兵 + 结构化尾巴**;服务端**回灌叙事**使 `validate_turn`/`detect_leak`/`apply` 原样复用。
- **备选(已记录)**:
  - B · 两次调用(叙事纯文本流 + 结构化 json_object 非流式):可靠性最高,成本 ~1.3–1.5×(DeepSeek prompt cache 抵一部分);留作可切换后路。
  - C · 内联交织:否决(玩家可能瞥见 JSON 碎片)。
- **后果**:正面=~1 call/回合(贴 ~¥0.002)、低延迟、复用 ADR-005 接缝、下游引擎/校验零改;代价=主调用失 json_object(修复补)、需哨兵边界扫描、`detect_leak` 在流式路径降为遥测(§1c)、`prompts/event-loop.md` 改造。

---

## 9. 给 Claude Code 的实现要点(🆕 项重点配单测)

- `EventLoopService` 承哨兵切分(两路:narrative→SSE / 尾巴→回灌→引擎),`TokenStream` 与 web 层保持笨。
- 统一 `toClientState()` 消毒投影;**任何**出网路径都过它,单测断言输出无 `isTrue`/`hiddenLogic`。
- 动作合法性 + 忙态守卫:状态机层强制,非法/忙态发 `event: error`、不调 LLM。
- 叙事回灌(§4.4)、兜底结局 id(§5)、修复用尽保守 no-op(§6.5)、叙事非法降级(§6.6)、泄露遥测处置(§1c)——bake-off 没有,各配单测。
- 修复发只修尾巴、回灌 canonical 叙事;勿让修复改写已流出的叙事。
- `prompts/event-loop.md` 改造(prose+哨兵+尾巴去 narrative),版本化。
- 键名严格对齐 `schema.py` / `apply()`。

---

## 10. 决策状态 / 待补

1. ✅ **已锁**:线上协议 = 单次调用(ADR-006 决策 A)。
2. ✅ **已锁**:数值 = 绝对值(`apply()` 现状,clamp+jump-flag 兜)。
3. ✅ **已并入**:`schema.py` → §4.4 尾巴口径(回灌复用 `validate_turn`)、§1c 泄露口径(`detect_leak` 仅逐字+字段名、流式降为遥测)已焊死。
4. 落档路由:本规格进 `docs/`;§8 走 `/adr-author` 成 ADR-006;§5 数值权威(绝对值)+ §1 三视图消毒回写 CONTEXT 约定;之后 `/roadmap-update`。

**可选小修(非阻塞)**:
- `schema.py` 文件头 docstring 仍写 v0.1,但 `WORLD_SCHEMA` 已 `const "0.2"` → 顺手同步注释。
- `TURN_SCHEMA` 在**结局回合**仍 require `availableActions`(minItems 2)→ 模型得给一组废弃动作、客户端在 `ending.reached` 时忽略;将来可在 CONTEXT 放宽为「ending 命中时 actions 可空」。
