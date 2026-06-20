# ADR-006 · event-loop 流式线上协议:叙事先行单次调用 + 哨兵 + 结构化尾巴 + 叙事回灌复用

- **日期**:2026-06-19
- **状态**:已采纳
- **决策者**:Felix

## 背景

Phase 1(单模式 H5 闭环·规则怪谈)要把 event-loop 的**叙事逐字流式推给玩家**。[ADR-005](ADR-005-sse-web-stack-mvc-thin-seam.md) 已为此选定 SSE(`SseEmitter` + `TokenStream` 薄接缝),但**线上传输格式**尚未敲定——这是开写 event-loop 业务前的承重前置。

矛盾点在于:Phase 0 bake-off 验证的 event-loop 产出是**单个 `json_object`**——`narrative` 是 JSON 里的一个字段,且开了 `response_format: json_object` 作可靠性拐杖(`schema.py TURN_SCHEMA` 校验,首次有效率 ~97%、修复后 100%)。这套格式给跑批脚本够用:没有客户端、不需要逐字体验。但生产要逐字流叙事,若沿用「叙事在 JSON 字段里」,要逐字流就得在 token 流里增量解析 JSON 字符串值(转义、未闭合引号、跨 chunk),既脆又容易把 `{"narrative":"…` 这类语法/转义碎片泄给玩家。

于是需要一个**既能逐字流叙事、又能可靠拿到结构化增量**(hp/san、discovered 规则、turn++、结局命中、下回合行动)的线上格式,并尽量复用已验证的引擎(`Engine.apply`)与校验(`validate_turn` / `detect_leak`),守住成本。

约束条件:

1. **必须逐字流叙事**:ADR-005 已选 SSE,叙事 TTFT 是体验底线(bake-off 实测 TTFT~1s)。
2. **成本硬约束**:单回合 ~¥0.002 量级是国内免费引流产品的承重假设,线上格式不能显著抬高调用次数。
3. **最大化复用已验证资产**:`validate_turn` / `detect_leak` / `Engine.apply` 是 bake-off 连推 10 回合自洽的核心,新格式应让它们尽量一行不改地复用。
4. **守 ADR-005 薄接缝**:切分/解析逻辑不得渗进 web 层或 `TokenStream`,只落在服务层。

## 候选方案

### 方案 A:单次调用,叙事先行 + 哨兵 + 结构化尾巴(本 ADR 采纳)

模型一次产出 = 叙事散文 + 定界哨兵 `<<<DELTA>>>` + 结构化尾巴 JSON(**尾巴不含 `narrative` 字段**):

```
<叙事散文,逐字流给玩家>
<<<DELTA>>>
{ "stateUpdate": {...}, "triggeredRuleIds": [...], "discoveredRuleIds": [...],
  "availableActions": [...], "ending": null | {...} }
```

**优点**:
- ~1 call/回合,贴 bake-off 成本(命中约束 2)。
- 叙事 TTFT 不受影响,逐字流式体验达标(命中约束 1)。
- **回灌使下游零改**:服务端把哨兵前捕获的叙事 `N` 回灌进 `parsed["narrative"]`,`validate_turn` / `detect_leak` / `Engine.apply` 原样复用(命中约束 3)。

**缺点**:
- 主调用因输出非纯 JSON,**不能开 `response_format: json_object`**,丢了可靠性拐杖,首次有效率可能略低于 bake-off ~97%。接受——event-loop 本就比 world-gen 干净、尾巴更小,且修复那一发**可开回 json_object**;需埋点持续监控。
- 引入哨兵 → 需跨 chunk 边界扫描(留「哨兵长度-1」尾缓冲)。接受,属一次性接缝成本。

### 方案 B:两次调用(叙事纯文本流 + 结构化 json_object 非流式)

第一发纯文本流叙事,第二发开 `response_format: json_object` 出结构化数据(把叙事喂进第二发以求一致)。

**优点**:
- 可靠性最高:结构化全程 json_object,无哨兵、无边界扫描。

**缺点**:
- 成本 ~1.3–1.5×(DeepSeek prompt cache 可抵一部分前缀),撞约束 2。
- 两发可能不一致(叙事与数值脱节),靠把叙事喂进第二发缓解但非根治。**不排除,留作可切换后路**:若上线后埋点显示首次有效率持续低于阈值,切到 B 的代价可控(接缝同 ADR-005 思路)。

### 方案 C:内联交织(结构化 token 混进叙事流)

把结构化片段实时穿插进叙事 token 流。

**优点**:
- 理论上单次调用、无需哨兵段分离。

**缺点**:
- 玩家可能瞥见 JSON 碎片,最脆、最难保证不泄露隐藏逻辑/字段名。**排除**:与「隐藏逻辑绝不泄露」的核心纪律直接冲突,可靠性最差。

## 最终决策

**方案 A — 单次调用,叙事先行 + 哨兵 + 结构化尾巴 + 叙事回灌**。

### 1. 线上格式与哨兵

模型一次产出叙事散文 → 哨兵 `<<<DELTA>>>` → 结构化尾巴 JSON(尾巴**去掉 `narrative` 字段**)。尾巴键名严格沿用 `schema.py` / `apply()`:`stateUpdate{hp,san,timeline}` / `triggeredRuleIds[int]` / `discoveredRuleIds[int]` / `availableActions[{id,text,hint}]`(限 2–4 个)/ `ending: null | {id<string>, reached<bool>}`。

### 2. 服务端处理序列

1. 哨兵**之前**的 token 实时转发玩家(SSE `event: narrative`),同时整段捕获为字符串 `N`;哨兵之后缓冲为结构化尾巴(留「哨兵长度-1」尾缓冲做跨 chunk 边界扫描)。
2. 解析尾巴 → **回灌** `parsed["narrative"] = N` → 跑现成 `validate_turn(parsed)` → `Engine.apply(parsed, actionId)`。**校验器与引擎一行不改**(`TURN_SCHEMA` 要求 narrative、`apply()` 读 `parsed["narrative"]`,回灌后都满足)。
3. SSE 用**命名事件**:`narrative`(逐字)/ `delta`(消毒后的状态变化,流末一次性下发)/ `ending` / `error`。顺序天然保证「叙事流完 → 才应用 delta」。
4. 切分逻辑落 `EventLoopService`(消费 `TokenStream`、哨兵切分、产两路),**不进 web 层、不进 `TokenStream`**,守 ADR-005 薄接缝(命中约束 4)。

### 3. 可靠性补偿(丢 json_object 的代偿)

主调用不开 json_object,靠强提示词 + 哨兵 + **一次修复重试**(沿用 bakeoff `_generate`)兜底:修复那一发只修**结构化尾巴**、**可开回 json_object**;**已流出的叙事是 canonical**(回灌同一个 `N`,不让修复改写玩家已看到的散文)。修复仍失败 → 保守 no-op(turn++、不动数值、复用/重生行动、响亮告警),**绝不脏写 state**。

### 关键理由

1. **呼应约束 1/2**:单次调用既保叙事逐字流、又贴 ~¥0.002 成本,是体验与成本的最优交点。
2. **复用面最大化**:叙事回灌让 `validate_turn` / `detect_leak` / `Engine.apply` 原样复用,新代码集中在「哨兵切分 + 捕获/回灌 + SSE 事件 + 消毒投影」接缝件(命中约束 3)。
3. **守 ADR-005 薄接缝**:切分只在 `EventLoopService`,`TokenStream` 与 web 层保持笨。
4. **保留演进路径**:B 留作低成本可切换后路,接缝同 ADR-005 哲学——埋点若示警再切,核心不动。

## 已知代价

1. **主调用失去 json_object 拐杖**:首次有效率可能略低于 bake-off ~97%。缓解方式:event-loop 输出本就比 world-gen 干净、尾巴更小;修复发开回 json_object;**埋点持续监控首次有效率/修复触发率**,持续低于阈值再评估切方案 B。
2. **引入哨兵 → 跨 chunk 边界扫描**:实现上需留「哨兵长度-1」尾缓冲,稍增复杂度。缓解方式:边界扫描单测覆盖,逻辑钉死在 `EventLoopService` 一处。
3. **泄露防御口径变化(重要)**:`detect_leak` 在流式路径里从「防护」降为「**遥测**」——叙事逐字流出、扫描在流完后,玩家已看到则救不回;且 `detect_leak` 只抓逐字照抄(≥8 字符)+ 字段名,**抓不到改写/复述式泄露**。故实时防护改由(a)提示词硬禁吐 `isTrue`/`hiddenLogic` +(b)结构层消毒投影(客户端永不接收那两个字段)承担;`detect_leak` 负责记日志、标记存档、喂离线提示词硬化。高危模式可选「先缓冲叙事、扫干净再流」(牺牲 TTFT),Phase 1 不做。
4. **`prompts/event-loop.md` 需改造**:prose 先行 + 哨兵 + 尾巴去 `narrative` 字段,版本化管理(CONTEXT §三.6)。属一次性迁移成本。
5. **方案 B/C 的代价**:B 被暂排是成本 ~1.3–1.5×、且未上线无需先付;C 被排除是玩家可能瞥见 JSON 碎片、与隐藏逻辑不泄露纪律冲突。接受——A 用「哨兵 + 回灌」一层接缝换来单次调用 + 零下游改动。

## 重新审视的触发条件

- **首次有效率持续低于阈值**:event-loop 埋点显示主调用首次有效率/修复后有效率长期低于可接受线 → 切方案 B(两次调用,结构化全程 json_object)。
- **改写式泄露在真实流量中出现**:`detect_leak` 遥测或人工复核发现模型以复述/改写方式泄露 hiddenLogic(逐字扫描抓不到)→ 收紧提示词、或对高危模式启用「先缓冲叙事、扫干净再流」。
- **成本结构变化**:DeepSeek prompt cache / 计费规则变动,使方案 B 的 ~1.3–1.5× 溢价被抹平 → 重评是否直接上 B 换可靠性。
- **哨兵冲突**:真实叙事文本出现 `<<<DELTA>>>` 字面量导致误切(罕见)→ 换更不可能自然出现的哨兵串或加转义约定。

## 实施步骤

1. ⏳ 改造 `prompts/event-loop.md`:prose 先行 + 哨兵 `<<<DELTA>>>` + 尾巴**去 `narrative` 字段**,版本化。
2. ⏳ `EventLoopService` 承哨兵切分(两路:narrative→SSE / 尾巴→回灌→引擎),`TokenStream` 与 web 层保持笨。
3. ⏳ 叙事回灌:捕获哨兵前散文 `N` → 解析尾巴 → `parsed["narrative"] = N` → `validate_turn` → `Engine.apply`(校验器/引擎一行不改)。
4. ⏳ 统一 `toClientState()` 消毒投影,**任何**出网路径都过它;单测断言输出无 `isTrue`/`hiddenLogic`。
5. ⏳ 可靠性:一次尾巴修复重试(开回 json_object、回灌 canonical 叙事不改写)→ 用尽则保守 no-op 降级,绝不脏写 state。
6. ⏳ **各配单测**(bake-off 没有的新接缝件):哨兵边界扫描、叙事回灌、消毒投影、保守 no-op 降级、叙事非法降级、泄露遥测处置。
7. ⏳ **埋点**:event-loop 首次有效率、修复触发率、TTFT/回合延迟、单回合成本。
8. ⏳ 落档后跑 `/roadmap-update` 同步进度表/ADR 索引/周日志。

## 实际效果(事后补充)

*event-loop 接通真实 DeepSeek、连跑若干真实回合时回填:主调用丢 json_object 后的真实首次有效率较 bake-off ~97% 劣化多少;一次修复重试的兜底率;哨兵边界扫描在真实跨 chunk 流下是否稳定不误切。*

*软启动有真实流量时回填:`detect_leak` 遥测是否捕获到逐字泄露;改写式泄露是否在人工复核中出现,实时防护(提示词 + 消毒投影)是否足够。*

## 跟其他文档的交叉引用

- **实现蓝本**:[Phase 1 · event-loop 契约 + 状态机规格](../phase1-event-loop-contract-and-state-machine.md)(§1 消毒边界、§4 线上契约、§5 数值权威、§6 可靠性)——本 ADR 只定线上传输格式,数值权威(绝对值)与状态机细节见该规格。
- **传输接缝(前序)**:[ADR-005](ADR-005-sse-web-stack-mvc-thin-seam.md)(SSE:MVC + `TokenStream` 薄接缝;本 ADR 的哨兵切分落 `EventLoopService`,守同一接缝)
- **provider / 流式基础(前序)**:[ADR-001](ADR-001-runtime-model-and-provider-abstraction.md)(OpenAI 兼容流式、`_generate` 修复重试、`_thinking_extra_body` 单点)
- **约定回写**:[CONTEXT.md](../CONTEXT.md) §三.8(数值权威·绝对值)、§三.9(state 三视图与消毒边界)——v0.3 据本 ADR 与 Phase 1 规格补充。
- **来源资产**:bakeoff `schema.py`(`TURN_SCHEMA` / `validate_turn` / `detect_leak`)、`scenarios.py`(`Engine.apply`)、`client.py`(`_generate` / `_parse_json`)、`FINDINGS`(F-002 修复 harness、F-003 有据恢复)
