# ADR-013 · 混合模式融合协议(内联融合 + init 双值,引擎不动)

- **日期**:2026-07-03
- **状态**:已采纳
- **决策者**:Felix

## 背景

混合模式(CONTEXT §一,`mode=hybrid`、`archetypes` 2–3)是本项目旗舰模式 / 最大差异点(ROADMAP §七)。round 1 彩蛋 = **修仙 × 规则怪谈**(host=修仙,场景③「识海遗蜕」:误入走火入魔大能的识海,一堵墙上真传心法与心魔伪笔无分界地混写)。

[ADR-012](ADR-012-hybrid-axis-merge-strategy.md) 已解决**轴合并**(host 优先 + 语义换皮),`ArchetypeRegistry.mergeAxes` 是「已实现、已测、暂未接线」的休眠件;本 ADR 解决**剩下的融合协议**:两个 archetype 如何真正走进 init 与 world-gen、合成**一个自洽的融合世界**(而非两世界轮流播),以及 init 入参如何从单值扩到双值。

约束条件:

1. **守 ADR-007 world-gen 线上口径**:world-gen 是最险的那次生成(JSON 首次失败是头号失败模式),保 `response_format: json_object` + 纯 JSON + 无哨兵 + reveal 不流式;**绝不把回合侧(ADR-006)的哨兵 / 叙事回灌 / 保守 no-op 搬来**。
2. **守 ADR-008 引擎无知**:引擎 / 校验 / `schemaVersion` 一律不动(`schemaVersion` 保 `"0.4"`);融合只在**播种层 + 提示词 + 前端**。勘察背书:引擎、校验、DTO 下发、前端面板 / bands 全 key-agnostic,天然吃任意轴数 + 多致命轴。
3. **守 ADR-011 承重接缝**:守则(rules)只作定性风险 / 氛围叙事元数据,**不写精确成功率数字、不定判定规则**;引擎只读透传、永不据守则 gate / 掷骰。
4. **round 1 只产一组彩蛋**:通用换皮引擎 / 任意 N archetype 自动融合属自由勾选 round 2,本轮手写「修仙×规则怪谈」一组。

## 候选方案

### 方案 A:预调用融合(先合成融合规格,再 world-gen)

先跑一次 meta-prompt 把两套 worldview / ruleForm 调和成一份「融合规格」中间产物,再把它喂给第二次 world-gen 生成世界。

**优点**:
- 融合逻辑与 world-gen 解耦,中间产物可缓存 / 复用。

**缺点**:
- **多一次生成 = 多一个失败面**,且第一次(融合规格)失败无前态可守 → 直接违背 ADR-007「把可靠性留在最险的那次生成、别加预调用」的核心结论。
- 融合规格自身要定 schema / 校验 / 修复,复杂度陡增,round 1 彩蛋不值当。

### 方案 B:两世界轮流播(拼接而非融合)

init 各自播种两套,回合在两 archetype 间交替 / 拼接叙事。

**优点**:
- 实现最省,近似"两局并排"。

**缺点**:
- **不自洽**——玩家看到的是「一会儿修仙、一会儿规则怪谈」,融合退化成拼接,丢掉「概念融合」这一杀手锏本身(§七「最难自洽」正是价值所在)。直接弃。

### 方案 C:内联融合 · 单次胖调用(本 ADR 采纳)

沿用 ADR-007 的单次 world-gen 胖调用,提示词内**并列注入两个 archetype 的注入块(worldview / ruleForm / 轴)+ 一段融合 meta-prompt**,让模型一次性产出一个融合世界(`mode:"hybrid"` + `archetypes:[两个]`)。

**优点**:
- **零新增生成调用 / 零新增失败面**:融合世界与单模式世界走同一条「胖调用 → `LooseJson` → `validateWorld` → 一次修复 → 仍败 ERROR」管线,可靠性心智完全复用。
- **守全部约束**:保 json_object 无哨兵(约束 1);引擎 / 校验 / `schemaVersion` 不动,融合只在播种层 + 提示词 + 前端(约束 2);守则仍是定性叙事元数据(约束 3)。
- 融合是"一份提示词的组织问题"而非"一套新架构",与 ADR-012 休眠合并函数接上活即可。

**缺点**:
- **融合自洽是提示词的活、非结构可保证**——单次胖调用要同时并列两套设定又不轮流播,靠 meta-prompt 引导,存在「融而不合」风险。缓解:Slice B 落完**必经 Felix 真 key 冒烟人验融合自洽**(本刀最大风险),不靠自动化夹具证融合质量。
- 换皮文案 / 融合 meta-prompt per-combo 手写,round 1 只覆盖一组(接受,见约束 4)。

## 最终决策

**方案 C — 内联融合 · 单次胖调用**,三处接线:

### 1. init 收有序双值(向后兼容单值)

`InitRequest` 从单 `archetype` string 扩为收**有序 archetype 列表(host 在前)**:

- 长度 = 1 → 走原**单体路径**(行为字节级不变);
- 长度 = 2 → 走**融合路径**(host = 第一个);
- **非法组合**(含未知 id / 已知未激活 / 长度 > 2 / 未登记的融合组合)→ `IllegalArgumentException` → **400**(早于 world-gen,不浪费一次生成)。

`GameInitService` 融合路径调 `ArchetypeRegistry` 的融合入口(内部委托 ADR-012 `mergeAxes`,host=第一个)得**融合轴集**,喂**现有派生**(`accumulationKeys` / `nonLethalKeys` / 致命集 / `axisDisplayNames`)→ **现有引擎构造,引擎一行不动**。修仙×规则怪谈融合结果:`accumulationKeys={realm}` / `nonLethalKeys={mana}` / 致命集 `={hp,san}` / 轴集 `{气血,灵力,境界,道心}`。

world-gen 产出:融合路径产 `mode:"hybrid"` + `archetypes:[两个]`(校验器 `requireStringArray("archetypes", 1)` 已容忍 ≥1,勘察背书,`WORLD_SCHEMA` / `MODES` 已含 `hybrid`)。

### 2. 融合 world-gen 提示词(内联融合,守 ADR-007)

`WorldGenPromptBuilder` SKELETON 加**融合分支**:`mode:"hybrid"` + `archetypes:[两个]` + **两个 archetype 的注入块(worldview / ruleForm / 轴)+ 一段融合 meta-prompt**。单次胖调用,保 json_object 纯 JSON 无哨兵。融合 meta-prompt(场景③,修仙×规则怪谈)三块内核:

- **设定内核**(第二人称低语腔):玩家误入走火入魔大能的**识海遗蜕**,一堵墙上真传心法与心魔伪笔**同墙无分界**。
- **守则融合**:一个世界内 `rules[]` **真假混合**——真 = 真传心法(**不带 `isTrue`**)、假 = 心魔伪笔(**带 `isTrue`+`hiddenLogic`)**;schema 已容忍混带(ADR-009 `isTrue` 全局可选)。
- **三根杠杆写成生成指令**:(a) **数值入守则**——守则引用气血 / 灵力 / 境界 / 道心;(b) **先辨体系再辨真假**——心法照做养道心 / 怪谈守则要验真假,墙上不标分类;(c) **真假对射用修仙常识裁**。
- **结局池含护道结局**(碑文腔):守则残魂渡劫、玩家是其心魔劫、化形行礼「多谢道友护道」。

**承重接缝写进提示词约束(守 ADR-011)**:守则只描述风险 / 氛围,**不写精确成功率数字、不定判定规则**。特别地:守则可叙事化提及「破境元婴以上者皆非真传」,但**不得承诺引擎据此拦破境 / 改判定**(去机制暗示);「让玩家回溯确信旧守则」类守则**只作纯叙事毒饵,不要求任何跨回合追踪**。

### 3. 前端王牌入口 + 融合封面(守 ADR-003)

- **融合入口 = 渗漏卡 + 误入手势**:选择屏依次点修仙卡 + 规则怪谈卡后,融合卡从两卡间「渗」出(纯 CSS 渗透 / 撕裂动画 + 组件 state,**零持久化**,每次进选择屏靠手势重触发);点融合卡 → 发双值 init `["cultivation","rules_creepy"]`。
- **融合封面**:`SceneBanner` 融合世界指 `/scenes/fusion-shihai.webp`,放开 `scene.ts` / `GameScreen` 的 `archetypes[0]` 单键假设(融合世界按融合专键取图,别盲取 `[0]` 取错)。
- 平台 IO 只在 `api/` 层,不引动画库(纯 CSS,Taro 可复用)。

### 关键理由

1. **把可靠性留在最险的那次生成**(呼应约束 1 / ADR-007):融合不加预调用、不加失败面,与单模式共用同一胖调用管线。
2. **复用 ADR-012 休眠件 + 现有播种派生**:`mergeAxes` 接活 + `GameInitService` 单一真理源派生委托,不新造。
3. **避开「引擎懂融合」陷阱**(守 ADR-008):融合世界对引擎只是「一个有 4 轴、2 致命轴、1 累积轴的普通世界」,引擎照旧遍历 attributes。
4. **保留 round 2 演进路径**:通用换皮引擎 / 任意 N archetype / ruleForm 自动融合留后续 ADR,本轮手写一组不封死。

## 已知代价

1. **融合自洽无结构保证、只能人验**:单次胖调用同时并列两套设定存在「融而不合 / 轮流播」风险,自动化夹具证不了融合质量。缓解:Slice B 落完**必经 Felix 真 key 冒烟**(本刀最大风险人验),Slice C 全绿后再冒烟一次。
2. **per-combo 手写、不通用**:round 1 只覆盖「修仙×规则怪谈(host=修仙)」一组;反向(host=规则怪谈)、其它组合均 400。缓解:融合组合登记表化,加一组 = 加一条 skins + 一段 meta-prompt,不改机制。
3. **init wire 双形态并存窗口**:Slice A 后端收双值,但前端到 Slice C 才发双值 → 中间窗口 wire 要同时容忍 `{archetype:"x"}` 与 `{archetypes:[...]}`。缓解:`InitRequest` 规范化两形态为单一有序列表,单值路径行为字节级不变。
4. **CONTEXT 融合约定暂缓回写**:host 优先 / 换皮 / 融合协议约定待端到端跑通后一并回写 CONTEXT §一 fusion / §三(守 ROADMAP 既定,避免空头约定)。

## 重新审视的触发条件

- **自由勾选 round 2 开放**(任意 2–3 archetype 自选融合):per-combo 手写换皮 / meta-prompt 不可扩展,需升级为通用换皮引擎 + ruleForm 自动融合 ADR。
- **融合世界出现 5+ 轴**:4 轴面板吃得下,若某组合融合后 ≥5 轴、面板 / token 成本吃紧,需议轴数上限裁剪(ADR-012 已挂)。
- **真 key 冒烟反复出现「轮流播 / 融而不合」**:说明内联融合 meta-prompt 引导不足,需重估方案 A(预调用融合)是否值当那次额外失败面。
- **回合侧需要感知融合**(如按体系分叙事):当前融合只在 world-gen 一次性合成、回合走通用管线;若回合要区分「心法体系 vs 怪谈体系」,需另设融合态 ADR。

## 实施步骤

1. **Slice 0**:`fusion-shihai.jpg` → `.webp`(Pillow q≈84),git status 仅显一个文件。
2. **ADR-013 落档**(本文件)+ README ADR 列表 + ROADMAP §五 ADR 索引追加一行。
3. **Slice A**(后端接线):`InitRequest` 收双值 + `GameInitService` 融合路径 + `ArchetypeRegistry` 融合入口(委托 `mergeAxes`)。验证:
   ```bash
   cd server && mvn -q test   # 双值派生正确 + 单值零回归 + 非法组合 400 + EngineGoldenTest 字节零回归 + schemaVersion 0.4
   ```
4. **Slice B**(融合提示词,工作量主体):`WorldGenPromptBuilder` 融合分支 + `prompts/` 融合内容 lockstep + 运行时副本逐条对齐 + `.md` 升版号。验证:融合分支 prompt 含两注入块 + 融合指令 + 三杠杆 + 护道结局位;无判定 / 精确数字指令泄漏;A-1 叙事长度约束仍在。**落完停下 → Felix 真 key 冒烟(冒烟门)**。
5. **Slice C**(前端入口 + 封面):渗漏卡 + 误入手势 + 双值 init + 融合封面 + 放开单键假设。验证:前端 `npm test` 全绿 + lint/build;融合入口渲染 / 手势触发 / 双值 init / 融合封面正确。
6. **冒烟门**(Felix 真 key 浏览器,Slice B 后 + 全绿后各一次):见 ROADMAP 冒烟门七条(融合自洽 / 四轴齐道心换皮 / 守则双关 / 护道结局 / 守则无判定越界 / 无泄露 / 融合封面+入口)。
7. **退出门**:体感 Felix 拍板 + 全测绿 + golden 字节零回归 + 无引擎 / schema / `schemaVersion` 误动;ff 合并 `main`,**不替 Felix push / merge**,等批准。

## 实际效果(事后补充)

*round 1 融合世界(修仙×规则怪谈,识海遗蜕)真 key 冒烟时回填:验证融合自洽(既是修士识海、又是规则怪谈,不轮流播)、四轴齐(气血 / 灵力 / 境界 / 道心)且道心换皮生效、守则双关(数值入守则 + 先辨体系 + 真假对射可用修仙常识推)、护道结局能触发且基调交接(阴森收在缥缈)、守则无判定越界、无泄露。*

## 跟其他文档的交叉引用

- **起源 / 范围**:CONTEXT §一(mode=hybrid、archetypes 2–3、世界融合 fusion)、ROADMAP §七(旗舰模式=混合模式=最大差异点)、[打磨与愿景 backlog](../phase2-polish-and-vision-backlog.md)(混合模式=主题 A 替序赢家)。
- **前序 ADR / 同款纪律**:[ADR-012](ADR-012-hybrid-axis-merge-strategy.md)(轴合并 host 优先 + 语义换皮,本 ADR 接活其休眠合并函数)、[ADR-007](ADR-007-world-gen-wire-protocol.md)(world-gen 保 json_object 无哨兵,融合守此口径不加预调用)、[ADR-008](ADR-008-multi-mode-extension-architecture.md)(引擎/校验对数值语义无知,融合只在播种层)、[ADR-009](ADR-009-axis-roles-and-rule-form-flexibility.md)(`isTrue` 全局可选 → 融合世界真假守则同墙混带合法)、[ADR-011](ADR-011-action-hint-narrative-metadata.md)(守则=定性叙事元数据、引擎不据它裁决,融合守则同守此接缝)。「让播种层 + 提示词融合、引擎不动」同 ADR-010「让 AI 标 outcome、引擎只读」哲学。
- **配套源文件**:`server/.../web/GameController.java`(`InitRequest` 双值)、`server/.../worldgen/GameInitService.java`(融合路径派生)、`server/.../archetype/ArchetypeRegistry.java`(融合入口 + combo 登记 + `mergeAxes`)、`server/.../worldgen/WorldGenPromptBuilder.java`(融合分支 SKELETON)、`prompts/world-gen.md`(融合段落 lockstep)、`web/src/api/`(双值 init 适配)、`web/src/features/`(渗漏卡 + 误入手势)、`web/src/.../scene.ts`(融合封面选键)。
- **本 ADR 明确不做、留给后续 ADR**:通用换皮引擎(任意 N archetype 自动融合)、ruleForm / rulesCarryTruth / worldview 通用融合算法、回合侧融合态感知、轴数上限裁剪 —— 均属自由勾选 round 2。
