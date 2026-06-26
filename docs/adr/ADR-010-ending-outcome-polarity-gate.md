# ADR-010 · 结局极性 gate——以 AI 标注的结局极性 + 引擎致命轴把关,根治濒死人物得成功结局(F-014)

- **日期**:2026-06-26
- **状态**:已采纳
- **决策者**:Felix

## 背景

修仙批(ADR-009)真 key 冒烟暴露一个**系统性结局判定 bug**(FINDINGS F-014):修仙人物**气血 8 / 灵力 0、叙事明确濒死**(经脉欲断、丹田枯竭),结局却给「守园有功 / 大比夺魁 / 筑基功成」这类**光明好结局**——结局与人物当前数值死活状态完全矛盾,**两局复现**。

根因(代码逻辑链已诊断):

- **主因 = AI 提议不匹配的成功结局,引擎 §4.4 照单全收**。`Engine.apply` 步骤 9:AI 在尾巴给 `ending{reached:true,id}`,引擎**只 gate `id` 是否存在于 `endings[]`**(`endingExists`),**不校验结局是否匹配死活/数值状态** → 接受、`status=ended`、`aiAccepted=true`;步骤 10 的 §5 兜底**因 `aiAccepted=true` 被跳过**。
- **次因(已在修仙批顺手修)= §5 兜底 `forceBottomOutEnding` 英文 key vs 中文 condition 不匹配**(commit `52b0cbe`,改为优先按轴中文名匹配);但它**只在 AI 给 null 的兜底点火时生效**,拦不住主路径。

**A(提示词强化)已试,不够**:修仙批落地了 A——event-loop / world-gen 加「濒死只能给失败结局」硬约束(commit `52b0cbe`)。但**冒烟实测连三局濒死仍得成功结局**。根因不是提示词不够狠:**AI 作为讲故事模型,天然倾向给主角逆袭结局,这个叙事惯性盖过提示词约束**——是 AI 故事惯性 vs 数值现实的根本冲突,软引导压不住。

**为什么是新世界类型暴露的**:多轴模式(修仙境界 accumulation「成功/强大」信号 与 hp/灵力 depletion「濒死」信号冲突)最易触发,AI 倾向往「故事圆满」解读;单轴模式(规则怪谈 / 末日 hp/san→0 是无歧义死亡信号)AI 可靠给死亡结局、掩盖了它。又一个「新世界类型暴露骨架假设」案例(同 F-012/F-013 家族),但**根因(§4.4 只 gate id 不 gate 死活)是通用的**。

**结论**:不能靠 AI 自觉,需引擎强制把关。A 留作软第一层(减否决频率),B(本 ADR,引擎极性 gate)作硬兜底根治。

约束条件:

1. **golden parity 是第一不变量**(ADR-008/009 同款纪律):本 ADR 第二次正面动 `Engine.apply` 结局判定(继 ADR-009 触底按 axisRole 之后)。golden parity(现 175 测)必须**字节级守零回归**——这是「没动坏」的唯一判据,不绿即停。
2. **守 ADR-008「引擎/校验对数值语义尽量无知」**:引擎不能自己判断「守园有功是好是坏」(那是懂中文语义)。引擎只能读 AI 标的极性标签 + 看 lethal 轴数值,新增语义要**最小**。
3. **向后兼容**:老的「无 outcome 字段」结局在新 schema 下仍合法(视为 neutral / 不 gate);golden / validator-parity / init 录制夹具(`schemaVersion` 钉 `"0.2"`/`"0.3"`)不能被改动失效。
4. **A 提示词软引导保留**(ADR 决策 3):不删,减少引擎需要否决的频率。

## 候选方案

> 两组正交决策:**(甲)如何让引擎知道结局好坏** 与 **(乙)哪些轴算「致命濒死轴」**。下面按这两组列候选。

### (甲) 极性来源 · 方案甲-1:引擎自己判断结局好坏

引擎据 `condition` / `title` 文本启发式判断「这是好结局还是坏结局」。

**优点**:无需 schema 加字段;AI 不用标。

**缺点**:**引擎要懂「筑基功成是好是坏」= 懂中文语义,直接违 ADR-008 引擎无知**;启发式脆弱(「死里逃生」含「死」却是好结局)。**排除**。

### (甲) 极性来源 · 方案甲-2:结局加极性 `outcome`,AI 标注、引擎只读(本 ADR 采纳)

`endings[].outcome`:`success | failure | neutral`,**world-gen 时由 AI 标**(它写「筑基功成」时最清楚这是 success、「经脉俱断」是 failure)。引擎**只读这个标签**,不自己判断语义——同 `axisRole`,只加最小必要语义(结局有个极性标签)。

**优点**:守 ADR-008 引擎无知(引擎只多读一个枚举标签);AI 标极性的准确度比引擎启发式高得多(它就是结局的作者)。

**缺点**:**gate 正确性依赖 AI 标对极性**——AI 把失败结局误标 success,则 gate 会依错标判断、放过。这是本机制的命门(见已知代价 3 + 冒烟观测项 + 重新审视触发条件)。

### (乙) 致命轴范围 · 方案乙-1:所有 depletion 轴都算致命(现状)

`anyAttributeBottomedOut` 现状:任一 depletion 轴 ≤0 即触底死亡;gate 也对所有 depletion 轴生效。

**优点**:零元数据改动。

**缺点**:**误判力竭为死亡(F-015)**——修仙灵力(`mana`)是 depletion 资源池,但「灵力枯竭 ≈ 力竭、非必死」(玩家规则:「灵力枯竭**强行运功**才经脉俱断」,是动作致死、非归零本身致死)。把灵力当致命轴会让灵力=0 误触发死亡 / gate。**排除**。

### (乙) 致命轴范围 · 方案乙-2:致命轴元数据标 `lethal`,仅生命轴致命(本 ADR 采纳,关闭 F-015)

元数据 axis 在 `axisRole=depletion` 基础上再标 `lethal: true|false`。**仅 hp 类生命轴 lethal=true**(规则怪谈/克苏鲁 hp、san;修仙气血;末日 hunger 视模式定致死);**灵力 `lethal=false`**(枯竭=力竭非必死)。引擎据 lethal 标判断哪些轴的 `≤0`/濒零触发死亡与 gate。

**优点**:精确表达「哪些轴归零才算死」,**顺带关闭 F-015**(灵力非致命轴 → 灵力=0 既不触发 §10 死亡、也不触发 §4.4 gate);引擎仍只读一个 bool 标(守无知)。

**缺点**:元数据再多一个轴属性(`lethal`);depletion 内部细分了「致命 / 资源」两类——是 ADR-009 `axisRole` 二分的粒度延伸(F-015 家族),刻意只在 lethal 一处做。

## 最终决策

**(甲) = 方案甲-2(`outcome` AI 标、引擎只读);(乙) = 方案乙-2(`lethal` 元数据,仅生命轴致命)。** 五条锁定决策:

### 1. 结局极性 `outcome`(AI 标注,引擎只读)

- schema `endings[].outcome`:枚举 `success | failure | neutral`,**可选**(缺省视为 `neutral` / 不强 gate)。
- **world-gen 时 AI 标**:每个 ending 生成时标极性(注入块指引:**失败/陨落/死亡类=failure,圆满/突破/逃生/存活类=success,其余=neutral**)。
- 引擎 / `toClientState`:`outcome` 是结局元信息,**不涉隐藏逻辑**(不是 `isTrue`/`hiddenLogic` 那类作者视角字段)→ 消毒**不剥它**,客户端可不显(或日后用于 UI 区分)。

### 2. 致命轴 `lethal`(元数据,仅生命轴;引擎只读 bool)

- 元数据 `AttributeAxis` 在 `axisRole` 之外加 `lethal`(默认对 depletion 为 `true`,对 accumulation 无意义恒 `false`)。
- **现有轴标 lethal**:hp / san / hunger / 气血 = `lethal=true`;**灵力(`mana`)= `lethal=false`**(关闭 F-015);accumulation 轴(knowledge / 境界)恒非致命(本就 ≤0 不触底)。
- **引擎据 lethal 二分**(非 depletion 全体):只有 **lethal 轴**的 `≤0` 触发 §10 死亡、`≤ ENDING_GATE_THRESHOLD` 触发 §4.4 gate。
- **角色如何到达引擎**:同 ADR-009 accumulationKeys 的播种路径——播种层(`GameInitService`→`GameSessionManager`)据元数据算出**非致命 depletion 轴 key 集合**(如 `{mana}`)传入引擎构造;**默认空集 = 全 depletion 致命**(= 现状,golden 走此路、字节级不变)。引擎只据集合 gate,不懂任何具体轴语义(守 ADR-008)。

### 3. §4.4 致命轴把关 gate(引擎改动,parity 守)

加在 `apply` 步骤 9 结局接受处:

```
若 AI 提议 ending{reached:true, id}(且 id 存在于 endings[]):
  若 存在 lethal 轴濒零(value ≤ ENDING_GATE_THRESHOLD,建议 10)
     且 该 ending.outcome == "success":
    → 引擎拒绝该成功结局(不接受 AI 的 id)
    → 从 endings[] 确定性挑一个 failure 结局落账:
       优先 condition 中文名匹配致命轴的 failure 结局 → 退首个 failure 结局
       → 再退「condition 匹配致命轴(任意极性)」→ 最后 endings[] 首条
  否则:正常接受(§4.4 现状)
```

- **引擎只看 outcome 标签 + lethal 轴值**,不懂结局语义。
- 阈值 `ENDING_GATE_THRESHOLD`(建议 10)与触底 0 分开:**濒死(≤10)就 gate 成功结局,不必等触底(0)**。
- **gate 仅对 `outcome == "success"` 生效**:`neutral`(含无 outcome 字段的老结局)/ `failure` 一律放过 → **向后兼容**(老数据无 outcome → 永不 gate → golden 行为零回归)。

### 4. §5 兜底 + §10 触底据极性确定性挑失败结局

- §5 `forceBottomOutEnding`(致命轴 ≤0 且 AI 给 null)与 §4.4 gate **共用同一个失败结局确定性挑选器**:优先 failure 极性 + 中文名匹配,逐级退到中文名匹配(任意极性,= 修仙批 §5 现状)、再退首条。**无 outcome 时退化为修仙批 §5 行为**(golden / 既有单测零回归)。
- §10 触底死亡(`anyAttributeBottomedOut`)由「任一 depletion 轴 ≤0」收窄为「任一 **lethal** 轴 ≤0」——**灵力 ≤0 不再触发死亡**(关闭 F-015)。默认空非致命集 → 全 depletion 致命 = 现状(golden 零回归)。

### 5. A 提示词保留作软第一层(双保险)

event-loop / world-gen 的「濒死倾向给失败结局」约束(修仙批 commit `52b0cbe`)**保留**——它减少引擎需要否决的频率(AI 多数时候标对、给对,引擎只兜偶发错配)。**A 软引导 + B 硬兜底**:AI 仍可写爽文,但**引擎在致命轴濒死时不让 success 生效**,确定性保证「濒死→失败结局」。

### 6. `schemaVersion` `"0.3"` → `"0.4"`(Felix 已拍),校验接受多版本

- `outcome`/`lethal` 是真字段新增(放宽性、可选、向后兼容)→ 升 JSON `schemaVersion` `"0.3"`→`"0.4"`(保纪律一致,同 ADR-009 isTrue 那次)。`lethal` 在元数据(伴生结构)非 state schema 字段,**不单独触发 schemaVersion**;`outcome` 是 state schema 字段、触发。CONTEXT §二 + 前端 `SCHEMA_VERSION` 同步;world-gen 此后产出 `"0.4"`。
- **`WORLD_SCHEMA` 校验接受 `{"0.2","0.3","0.4"}` 三版本**(沿用 ADR-009「接受多版本守 parity 夹具」):golden / validator-parity / init 夹具是录制的真实 `"0.2"`/`"0.3"` 产出,硬翻只认 `"0.4"` 会让 parity 集体失效。`"0.1"` 等仍拒。

### 关键理由

1. **守 ADR-008 引擎无知**:引擎只多读一个极性枚举标签(AI 标)+ 一个 lethal bool(元数据),不自己判断结局好坏 / 轴语义。「让 AI 标 outcome」是守无知的关键——引擎一旦自己判断「筑基功成是好是坏」就破了边界。
2. **复用 ADR-008/009「核心机械改动 + parity 护城河」先例**:gate 是 §4.4/§10 加分支(致命轴濒零 + success → 拒绝改挑 failure),机械改动;默认空非致命集 = 现状 → golden 字节级守零回归,与前两次同款纪律(只做最小必要变换、parity 不绿即停)。
3. **A+B 双保险**:A 软引导减否决频率、B 硬兜底保正确性。承认 A 不够(连三局复现)却不弃 A——它仍是第一层防线,让引擎少否决。
4. **顺带关闭 F-015**:lethal 二分让灵力(枯竭非必死)既不触发死亡也不触发 gate,一次决策解决「结局极性」主问题 + 「资源轴非致命」F-015 旁支(两者都需要「哪些轴算死」这一信息)。
5. **保留演进路径**:outcome 可选缺省 neutral(放宽性);若 AI 标极性真不可靠(冒烟观测),退路是引擎二次校验 / 加 condition 启发式辅助(但那会触碰引擎语义,需新 ADR);lethal 二分若日后还要细分(如「过载致死」)可在元数据再扩。

## 已知代价

1. **引擎再多懂一点(结局有极性、轴有 lethal 标)**:这是**有界的、刻意的让步**(只为濒死结局正确 + F-015),非滑坡。缓解方式:语义严格限「读 AI 标的 outcome + lethal 轴 ≤ 阈值要不要拒 success」,不让引擎判断结局含义;golden parity 字节级守零回归证「没动坏」。
2. **schema 加 `outcome`/`lethal` 字段 + schemaVersion 0.4 多版本过渡**:`WORLD_SCHEMA` 同认三版本,是为守 parity 夹具的过渡态、非长期理想。缓解方式:夹具是录制的历史真实产出,过渡窗口足够;日后录制刷新到 `"0.4"` 后可收紧。
3. **gate 正确性依赖 AI 标对极性(本机制的命门)**:AI 若把失败结局误标 `success`,gate 依错标判断 → 放过濒死成功结局,B 形同虚设。这是「把判断权交给 AI 标注」的固有风险。缓解方式:① A 提示词 + world-gen 注入块给清晰极性指引(失败/陨落=failure);② **列为本批冒烟头号观测项**(AI 是否标对极性);③ 若冒烟显示 AI 标极性也不可靠 → 停下报 Felix(架构信号:B 整个机制依赖 AI 标对,得另想办法,见重新审视触发条件)。
4. **A 软引导保留 = 接受 AI 仍可能给濒死成功结局(被 B 拦)**:A 不删但已知不足,靠 B 兜。缓解方式:B 是确定性硬兜底,A 只为减频率,不指望 A 拦住。

## 重新审视的触发条件

- **golden parity 175 在 gate 改动后无法字节级全绿**:说明 gate 溢出了「致命轴濒零 + success → 拒绝改挑 failure」的机械范围 / 默认非致命集没守住现状 → 停下复盘,回到「只做机械变换」或重审方案。
- **冒烟显示 AI 标极性不可靠**(决策 1 / 已知代价 3 头号观测项):AI 频繁把失败结局标 `success`(或反之)→ B 依错标判断、根治失效 → 重评是否需引擎二次校验(condition 启发式辅助判极性)或换机制(那会触碰引擎语义,需新 ADR / 补本 ADR)。
- **gate 误伤正常结局**:非濒死却被 gate、或 AI 给的合理成功结局被错拒 → 阈值 `ENDING_GATE_THRESHOLD` 或「near-zero」判定需调。
- **lethal 二分不够用**:出现「既非致命也非纯资源」的 depletion 轴,或 accumulation 轴需要「过高致死」(ADR-009 刻意归 AI)→ 重审 lethal / 死亡条件元数据化。
- **混合模式 fusion(Phase 3)**:多套设定调和时,单 archetype 一份 lethal 清单 + 结局极性的假设可能不够 → 届时重审。

## 实施步骤

> 切分**由架构到内容、parity 优先**(同 ADR-009):引擎改动有回归风险,先在 parity 网下做掉。ADR 落档与实现**分开 commit**。

1. **ADR-010 落档**(本文件)+ ROADMAP §五 ADR 索引 + root README ADR 列表同步。**独立 commit,先于实现。**
2. **schema + 字段**:`GameSchemas.validateWorld`(`endings[].outcome` 可选枚举 + schemaVersion 接受 `{0.2,0.3,0.4}`)、CONTEXT §二、前端 `SCHEMA_VERSION` `"0.4"` + `Ending.outcome` 可选;`AttributeAxis` 加 `lethal`。
   ```bash
   mvn -q test && (cd web && npm test)   # 回归全绿
   ```
3. **§4.4 致命轴 gate 引擎正解**:`Engine` 加非致命 depletion 集构造参数 + `ENDING_GATE_THRESHOLD`;步骤 9 加 gate(致命轴濒零 + outcome=success → 拒绝、确定性挑 failure);§5/§10 据极性挑失败 + 收窄触底到 lethal 轴。
   ```bash
   mvn -q test   # golden parity 字节级全绿(唯一判据,不绿即停)+ gate 新单测
   ```
4. **元数据标 lethal + 播种非致命集**:现有 hp/san/hunger=true、灵力=false(关闭 F-015);`GameInitService`→`GameSessionManager` 传非致命 depletion 轴 key 集合。
5. **world-gen 注入块**:AI 标 outcome 指引(失败/陨落=failure…);lockstep `prompts/world-gen.md` + 运行时副本 `WorldGenPromptBuilder`;A 濒死约束保留。
6. **真 key 冒烟(验收门,Felix 在场)**:endings 是否都带 outcome 标 + 标对极性;**气血濒死(≤10)时引擎是否拒绝成功结局、改给失败/陨落结局**(F-014 根治,反复验几局);灵力枯竭(0)但气血尚可不因灵力误成失败结局(F-015 关闭);体感。**此关不过不算完成。**
7. 冒烟通过后 `/roadmap-update` + CONTEXT 回写(§二 `outcome` + schemaVersion 0.4、§三.8 极性 gate、§三.14 `lethal`)+ FINDINGS F-014 标「A+§5+B(ADR-010)根治、已关闭」/ F-015 标「ADR-010 关闭(灵力非致命轴)」。

## 实际效果(事后补充)

*修仙真 key 冒烟时回填:endings 是否都带 `outcome` 且**标对极性**(AI 标注可靠度,本 ADR 命门);气血濒死(≤10)时引擎是否确定性拒绝 success 结局、改给 failure 结局(F-014 根治成立否,反复几局);灵力枯竭(=0)但气血尚可时游戏不因灵力误判死亡/失败(F-015 关闭成立否);濒死给对失败结局后违和感是否消失(体感)。*

*日后加新模式 / 混合模式时回填:`outcome` + `lethal` 二字段是否够用、AI 跨模式标极性是否稳定。*

## 跟其他文档的交叉引用

- **起源 / 经验**:FINDINGS F-014(濒死人物得成功结局,A+§5 部分缓解未根治 → 本 ADR 根治)/ F-015(灵力轴 ≤0 力竭非必死 → 本 ADR lethal 二分顺带关闭)。
- **前序 ADR / 同款纪律**:ADR-008(引擎/校验对数值 key 无知 +「核心机械改动 + parity 护城河」先例)/ ADR-009(`axisRole` 触底二分,本 ADR `lethal` 是其粒度延伸;播种层传 key 集合的同款路径)。
- **数值权威 / 消毒边界**:CONTEXT §三.8(数值=AI 提议绝对值、引擎落账,本 ADR 补「致命轴濒零时引擎拒绝 success 结局、据极性挑 failure」)/ §二(schema,本 ADR 加 `endings[].outcome` + `schemaVersion` `"0.4"`)/ §三.14(per-archetype 元数据,本 ADR 加 `lethal`)。
- **本批实现蓝本**:`docs/phase2-ending-polarity-gate.md`(切分 / 测试矩阵 / 冒烟门)。
- **配套源文件**:`server/.../engine/Engine.java`(步骤 9 gate / §5 / §10 据极性 + lethal)、`server/.../engine/GameSchemas.java`(outcome 可选 + schemaVersion 三版本)、`server/.../archetype/AttributeAxis.java`(`lethal`)、`server/.../archetype/ArchetypeRegistry.java`(现有轴标 lethal)、`server/.../worldgen/GameInitService.java` + `server/.../eventloop/GameSessionManager.java`(非致命集播种)、`prompts/world-gen.md`、`web/src/types/schema.ts`。

## 不在本 ADR 范围

- 结局极性之外的结局质量(叙事好坏文笔);accumulation 轴「过高致死」(归 AI,ADR-009 已定);修仙做厚(backlog,Phase 3 数据拉动);其余世界库模式;混合模式 fusion(Phase 3);难度 / 配图(backlog)。
