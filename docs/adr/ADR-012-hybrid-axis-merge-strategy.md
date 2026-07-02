# ADR-012 · 混合模式轴合并策略(host 优先 + 语义换皮,引擎不动)

- **日期**:2026-07-02
- **状态**:提议 → 待落档(混合模式 round 1 实现前)
- **决策者**:Felix

## 背景

混合模式(CONTEXT §一,mode=hybrid、archetypes 2–3)round 1 彩蛋 = 修仙 × 规则怪谈。两世界各一套轴:修仙 {气血 hp, 灵力 mana, 境界 realm}、规则怪谈 {体力 hp, 理智 san},融合须合成一套。勘察确认:引擎、校验、DTO 下发、前端面板/bands 全 key-agnostic,天然吃任意轴数 + 多致命轴;唯一无先例的是两轴撞键(hp 两边都有,displayName 气血 vs 体力)。ArchetypeRegistry.meta(id) 现只按单 id 取一块 ArchetypeMeta,无合并入口。

## 决策

融合轴 = 按 key 并集,合并规则三条:

1. **host 优先**:每个融合组合指定一个 host archetype,撞键时 host 的 displayName/bands/behaviorHint 赢、另一侧同 key 轴并掉。本组合 host=修仙(修士识海主场)→ hp 撞键取气血。
2. **语义换皮 override(per-combo)**:外来轴可带显示层 override 并入 host 世界观。本组合:规则怪谈 san 带过来——key 仍 san、axisRole 仍 depletion/lethal(引擎无感),displayName 换**「道心」**、bands 修仙口吻重写(清明/动摇/崩缺);语义=见不可名状之物掉道心、道心崩=走火入魔。
3. **结果轴集** = {气血(hp)、灵力(mana)、境界(realm)、道心(san)},4 轴;致命轴={hp, san}(realm 累积非致命、mana nonLethal)。播种层据合并结果算 accumulationKeys={realm}/nonLethalKeys={mana} 传入现有引擎。

## 明确不做(承重接缝)

- **合并机制通用、换皮文案 per-combo 手写**:round 1 只产出「修仙×规则怪谈」一组合并结果(彩蛋),不做「任意 N archetype 自动换皮」的通用换皮引擎(属自由勾选 round 2)。
- **引擎/校验/schemaVersion 一律不动**(勘察背书):合并只在播种层(ArchetypeRegistry 新增合并函数 + GameInitService 派生),引擎照旧遍历 attributes;schemaVersion 保 0.4。
- **不做轴数上限裁剪**(4 轴面板吃得下;上限问题留到 round 2 若出现 5+ 轴再议)。

## 影响

新增 ArchetypeRegistry 一个 ArchetypeMeta 合并函数(并集 + host 撞键优先 + 可选换皮 override)+ GameInitService 派生按合并结果算致命/累积集。不动引擎/校验/schemaVersion。

## 替代方案

- **语义去重(san+hp 合成一条"生命类"轴)** → 丢掉怪谈的道心压力维度、融合退化成"就是修仙",弃。
- **san 保原名「理智」不换皮** → 机械可行但在修士识海里出戏、削自洽(§七最难自洽风险),故换皮。
- **不指定 host、撞键报错走人工** → 每组合都要处理,不如 host 规则一次定清。

## 跟其他文档的交叉引用

- **起源 / 范围**:CONTEXT §一(mode=hybrid、archetypes 2–3、世界融合 fusion)、ROADMAP §七(旗舰模式=混合模式=最大差异点)、[打磨与愿景 backlog](../phase2-polish-and-vision-backlog.md)(混合模式=主题 A 替序赢家)。
- **前序 ADR / 同款纪律**:ADR-008(引擎/校验对数值语义无知——本 ADR 合并只在播种层、引擎零解读)/ ADR-009(axisRole depletion/accumulation,合并后据它算 accumulationKeys)/ ADR-010(lethal 致命轴标,合并后据它算 nonLethalKeys、致命集)。「让播种层合并、引擎不动」同 ADR-010「让 AI 标 outcome、引擎只读」哲学。
- **后续 ADR(本 ADR 明确不做、留给它们)**:ruleForm / rulesCarryTruth / worldview 的融合属「融合协议 ADR」与「规则形态 ADR」,本 ADR 只合并轴;init 让两 archetype 真正走进流程亦属融合协议 ADR。
- **配套源文件**:`server/.../archetype/ArchetypeRegistry.java`(轴合并函数 + 修仙×规则怪谈 combo 配置)、`server/.../archetype/AttributeAxis.java`(轴元数据,合并复用)、`server/.../worldgen/GameInitService.java`(致命/累积集派生,合并结果喂现有派生)。

## 不在本 ADR 范围

- **ruleForm / rulesCarryTruth / worldview 融合**:属融合协议 ADR / 规则形态 ADR,本轴合并函数不处理。
- **init 入参改造(让两 archetype 真正进 init)**:属融合协议 ADR;本轮合并函数是「已实现、已测、暂未接线」的休眠件(类比 resolveBand 先纯函数后接线)。
- **通用换皮引擎(任意 N archetype 自动换皮)**:属自由勾选 round 2,本轮只手写「修仙×规则怪谈」一组。
- **轴数上限裁剪**:留到 round 2 若出现 5+ 轴再议。
- **引擎 / 校验 / `schemaVersion` 变更**:本轮零动,`schemaVersion` 保 `"0.4"`。
