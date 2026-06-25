# ADR-009 · 数值轴角色(depletion/accumulation)+ 规则形态弹性(isTrue 可选)——根治累积轴误判触底与非真假守则世界的骨架冲突

- **日期**:2026-06-25
- **状态**:已采纳
- **决策者**:Felix

## 背景

加世界流水线(ADR-008)已验两次复用——末日生存(`hp`/`hunger`)、克苏鲁(`hp`/`san`/`knowledge`)。克苏鲁(第一次正式复用)暴露**两个同类缺口**:「骨架/引擎的某个假设对新世界类型不成立」。两条都用提示词软兜过了关、玩家无感,但**非根治**(FINDINGS F-012 / F-013):

- **F-012 · 累积轴误判触底**:引擎 `Engine.apply` 第 10 步 `anyAttributeBottomedOut()` 假设「**任一**数值轴 ≤0 = 触底死亡」。这只对 **depletion 型轴**(`hp`/`san`/`hunger`,越低越危,0=死/疯/饿毙)成立;对 **accumulation 型轴**(`knowledge`/境界,0=「一无所知」「初入修行」是**健康的开局态**)是**错的**——若 AI 把 `knowledge` 落到 0,引擎会误判触底、强制 `ended` 并盖一个坏结局。克苏鲁靠「提示词给正基线、绝不给 0」兜住,但脆弱:每个累积轴模式都要单独兜,AI 一旦误落 0 就误死。

- **F-013 · isTrue 骨架冲突**:world-gen 通用骨架 + `validateWorld` 强制 `rules[].isTrue`(真假混合),但「非真假守则」型世界(克苏鲁禁忌知识渐揭、修仙心法/灵气感应)与之打架——模型跟了 `ruleForm` 框架就丢 `isTrue`,触发首过校验失败 + 一次修复。克苏鲁首发 8 条 rules 全缺 `isTrue`、走一次修复后过(玩家无感,只是首过有效率扣分)。

**为什么现在做**:修仙(本批落地)带来**第二个累积轴样本**(境界)。攒够两个累积轴样本(`knowledge` + 境界)+ 两类规则形态(真假守则 / 心法守则),现在出引擎正解,设计更稳、不靠单点过度设计。本 ADR 在修仙批落地它(架构正解是修仙批的真分量,修仙内容本身是轻量壳)。

**关键洞察(两样本看清的)**:累积轴的「死亡语义」彼此还不一样——

- `knowledge`:累积,**高了间接致死**(高 `knowledge` 拖垮 `san`)。
- 境界:累积,**纯成长、不参与死亡判定**(修仙死于 `hp` 触底/渡劫失败,非境界)。
- → **引擎不必懂这种区别**。引擎**只需管「会不会因 ≤0 致死」这一件事**:depletion(会)vs accumulation(不会)。`knowledge` 的「高了拖累别的轴」与境界的「不参与死亡」都归 accumulation(都不因 ≤0 死),它俩的区别继续靠 AI 落、引擎无知(守 ADR-008)。

约束条件:

1. **golden parity 是第一不变量**(ADR-008 同款纪律):引擎触底逻辑是本项目**第一次正面动刀**的核心改动,golden parity(现 162 测)必须**字节级守 depletion 轴(`hp`/`san`/`hunger`)零回归**——这是「没动坏」的唯一判据,不绿即停。
2. **守 ADR-008「引擎/校验对数值语义尽量无知」**:本 ADR 给引擎加的语义要**最小**(只加一个二分),给校验器加的语义要**零**(不按 archetype/ruleForm 分派)。
3. **向后兼容**:老的「带 `isTrue` 的真假守则世界」数据在新 schema 下仍合法;golden / validator-parity 录制夹具(`schemaVersion` 钉 `"0.2"`)不能被改动失效。
4. **数值权威归引擎不变**(CONTEXT §三.8):AI 提议绝对值、引擎落账;accumulation 轴「过高有代价」仍由 AI 落。

## 候选方案

> 两组正交决策:**(甲)F-012 触底语义** 与 **(乙)F-013 规则形态**。下面按这两组列候选。

### (甲) F-012 · 方案甲-1:继续提示词兜(克苏鲁现状)

每个累积轴模式在提示词给「正基线初值、绝不给 0」,引擎一行不改。

**优点**:零引擎改动、零回归风险。

**缺点**:**脆弱**——每个累积轴模式单独兜;AI 一旦误落 0 即误死(防御全押在 AI 自律上)。两个样本已够根治,继续兜是把已看清的债留着。**排除**。

### (甲) F-012 · 方案甲-2:引擎懂三种轴语义(depletion / accumulation-危险 / accumulation-中性)

引擎区分「knowledge 这种高了致死」与「境界这种不参与死亡」,各设死亡条件。

**优点**:引擎能直接表达「knowledge 过高→疯」。

**缺点**:**过度语义,违引擎无知**——引擎只需管「≤0 是否致死」一件事;「高了有代价」是 AI 的活(克苏鲁 `knowledge↔san` 联动已证 AI 能落)。让引擎懂三种是把 AI 该管的语义焊进引擎。**排除**。

### (甲) F-012 · 方案甲-3:数值轴角色 `axisRole`(最小二分)(本 ADR 采纳)

元数据每轴声明 `axisRole: depletion | accumulation`;引擎触底判定**按角色分支**(唯一新增语义)。

**优点**:引擎只加一个二分(最小必要语义);累积轴根治、此后零提示词补丁、零误触底;在 golden parity 保护下做。

**缺点**:引擎不再「对轴完全无知」——是**有界的、刻意的让步**,只为 `≤0` 触底正确。accumulation 轴「过高致死/有代价」仍靠 AI 落(继续靠冒烟观测,如克苏鲁联动那条)。

### (乙) F-013 · 方案乙-1:按 ruleForm/archetype 分派 isTrue 校验

校验器按 archetype 决定「这个模式的 rules 要不要有 isTrue」。

**优点**:校验仍能对「真假守则世界」硬拦漏 `isTrue`。

**缺点**:**校验器开始懂 archetype 语义,违 ADR-008「校验对模式无知」**;且加模式要改校验分派——正是 ADR-008 要避免的债结构。**排除**。

### (乙) F-013 · 方案乙-2:isTrue 全局可选,校验零分派(本 ADR 采纳)

`rules[].isTrue` 从**必需**改为**可选**:谁要真假规则就给(规则怪谈/克苏鲁),不要就不给(修仙心法)。校验器**零分派**(不按 archetype 判)。

**优点**:校验器零分派、一致性最好(守 ADR-008 校验无知);非真假守则世界不再首过修复;加模式不碰校验核心。

**缺点**:规则怪谈若模型漏 `isTrue`,schema 不再硬拦(靠提示词软兜)——同 ADR-008「校验弱换加世界零分派」的同一哲学权衡。

## 最终决策

**(甲) = 方案甲-3(`axisRole` 最小二分);(乙) = 方案乙-2(isTrue 全局可选、校验零分派)。** 三条锁定决策:

### 1. F-012 正解 = 数值轴角色 `axisRole`(引擎只加一个二分)

- 元数据每个数值轴声明 `axisRole`:`depletion` | `accumulation`(放 `ArchetypeMeta` 的 attributes 轴定义 `AttributeAxis`,沿用 CONTEXT §三.14 元数据结构,**非 JSON state schema 字段**)。
- **引擎触底判定按角色分支**(引擎唯一新增语义,刻意最小):
  - `depletion` 轴 → 保留现状:`≤0` → 强制 `status=ended` + 兜底坏结局(规格 §5)。
  - `accumulation` 轴 → **不触底**:引擎绝不因它 `≤0` 判死(0 是安全起点)。
- **角色如何到达引擎**:引擎从 `world.character.attributes`(纯数值 map)学不到角色 → 由播种层把**该局的 accumulation 轴 key 集合**传入引擎构造。**保留 2 参构造 `Engine(world, mapper)` 默认空集 = 全 depletion**(= 现状),新增 3 参构造 `Engine(world, mapper, Set<String> accumulationKeys)`;触底循环对 accumulation key 跳过。golden 用 2 参构造 → **行为字节级不变**。
- **「过高有代价」仍归 AI 落、引擎无知**(克苏鲁 `knowledge↔san` 联动那套):引擎只懂「这轴是不是 depletion、要不要管它 `≤0`」,不懂「`knowledge` 高了拖累 `san`」「境界是纯成长」。
- **现有轴标角色**:`hp`/`san`/`hunger`/灵力 = `depletion`;`knowledge`/境界 = `accumulation`。这把**克苏鲁 F-012 的提示词兜升级为引擎根治**(此后累积轴不再需要「正基线」提示词补丁,虽提示词仍可给合理初值)。

### 2. F-013 正解 = isTrue 全局可选(校验零分派)

- `validateWorld` 的 `rules[].isTrue` 由**必需**改为**可选**(给了校验布尔类型,不给不报错);校验器**不按 archetype/ruleForm 分派**。
- world-gen 提示词骨架据 per-archetype 的「规则是否真假守则」注入对应措辞:真假守则型(规则怪谈/末日/克苏鲁)仍要 `isTrue` 有真有假;心法守则型(修仙)**明确不输出 `isTrue`**。这是注入块的活,不是校验器的活。
- **消毒不变**:`isTrue` 给了仍是引擎/作者视角字段,`toClientState` 照旧剥(只是现在可能不存在)。

### 3. `schemaVersion` `"0.2"` → `"0.3"`(第一次真动字段约束),校验器接受双版本过渡

- `isTrue` 由必需改可选是**真正的 schema 字段约束变更**(异于历次「字段未变仅文档升」)→ 升 JSON `schemaVersion` `"0.2"` → `"0.3"`(CONTEXT §二 + 前端 `SCHEMA_VERSION` 常量同步)。world-gen 提示词此后产出 `"0.3"`。
- **`WORLD_SCHEMA` 校验接受 `"0.2"` 与 `"0.3"` 双版本**(过渡窗口):golden / validator-parity / init 测试夹具是**录制的真实 `"0.2"` 产出**——硬翻成只认 `"0.3"` 会让 parity 夹具集体失效(违约束 1/3)。接受双版本 = 老数据仍合法(向后兼容)+ 新产出走 `"0.3"`,`"0.1"` 等仍拒。`axisRole` 在元数据(伴生结构)非 state schema 字段,**不单独触发 schemaVersion**(只 `isTrue` 触发)。

### 关键理由

1. **加模式/动引擎的代价结构最优**:F-012 引擎只加一个二分(`depletion`/`accumulation`),不懂任何具体轴语义;F-013 校验器零分派。两者都守 ADR-008「引擎/校验尽量无知」,只在「`≤0` 触底正确性」这一处做**有界、刻意**的让步。
2. **复用 ADR-008「核心泛化 + parity 护城河」先例**:引擎触底改动是机械的「按角色 gate 触底循环」,golden parity 162 字节级守 depletion 零回归——与 ADR-008 那次 key-agnostic 泛化同款纪律(只做最小必要变换、parity 不绿即停)。
3. **诚实面对两类样本**:不让引擎懂「knowledge 致死 vs 境界中性」的区别(那是 AI 的活),只让它管 `≤0`;`isTrue` 可选而非按 archetype 分派(校验器不长 archetype 脑子)——一次决策只动该动的变量。
4. **保留演进路径**:accumulation 轴「过高致死」若日后要引擎化(刻意现在不做),可在 `axisRole` 上再扩;规则怪谈漏 `isTrue` 若真成高频问题,可后续按需加软兜。

## 已知代价

1. **引擎不再「对轴完全无知」**:新增 `axisRole` 二分语义。这是**有界的、刻意的让步**(只为 `≤0` 触底正确),非滑坡。缓解方式:语义严格限「这轴 `≤0` 要不要致死」一件事,「过高有代价」仍归 AI;golden parity 字节级守 depletion 零回归证「没动坏」。
2. **isTrue 可选 = 规则怪谈漏 isTrue 不硬拦**(软兜):同 ADR-008「校验弱换加世界零分派」的同一哲学权衡。缓解方式:提示词骨架对真假守则型仍明确要 `isTrue` 有真有假;真高频漏给再评估加软兜。
3. **accumulation 轴「过高致死/有代价」仍靠 AI 落、稳定性靠冒烟**:引擎不兜(刻意)。缓解方式:列为修仙冒烟专门观测项(境界累积是否稳定、境界低/0 时引擎是否不误死)——延续克苏鲁联动观测的口径。
4. **`schemaVersion` 双版本过渡**:`WORLD_SCHEMA` 同时认 `"0.2"`/`"0.3"`,是为守 parity 夹具的过渡态、非长期理想。缓解方式:夹具是录制的历史真实产出,过渡窗口足够;日后录制刷新到 `"0.3"` 后可收紧只认 `"0.3"`(届时另评估)。

## 重新审视的触发条件

- **golden parity 162 在 `axisRole` 改动后无法字节级全绿**:说明触底改动溢出了「按角色 gate 触底循环」的机械范围 → 停下复盘,回到「只做机械变换」或重审方案(本批已预设此为停机信号)。
- **`axisRole` 二分不够用**:出现「既非纯 depletion 也非纯 accumulation」的轴(如某轴 `≤0` 与过高都要致死),二分表达不了 → 重审是否需要第三种角色或把死亡条件元数据化(甲-2 的受限回潮)。
- **修仙冒烟显示 AI 落境界累积不稳**(决策 3 观测项):境界频繁回落/被误落 0,或 accumulation 轴「过高有代价」AI 执行不稳 → 重评是否需提示词强化或(最后)局部引擎兜底(那会再触碰引擎无知,需新 ADR)。
- **规则怪谈高频漏 `isTrue`**:isTrue 可选(代价 2)在真实流量里真成问题 → 评估加软兜或局部回乙-1 分派。
- **混合模式 fusion(Phase 3)**:多套设定调和时,单 archetype 一份轴角色清单的假设可能不够 → 届时重审 `axisRole` 在 fusion 下的合成。

## 实施步骤

> 切分**由架构到内容、parity 优先**(异于前几批「由纯到脏」):引擎正解有回归风险,先在 parity 网下做掉。ADR 落档与实现**分开 commit**。

1. **ADR-009 落档**(本文件)+ ROADMAP §五 ADR 索引 + root README ADR 列表同步。**独立 commit,先于实现。**
2. **F-012 `axisRole` 引擎正解**:`AttributeAxis` 加 `axisRole`、`Engine` 3 参构造 + 触底按角色 gate、现有轴标角色、播种层(`GameInitService`→`GameSessionManager`)传 accumulation key 集合。
   ```bash
   mvn -q test   # golden parity 162 必须字节级全绿(唯一判据,不绿即停)+ accumulation 不触底新单测
   ```
3. **F-013 isTrue 可选 + schemaVersion `"0.3"`**:`GameSchemas.validateWorld`(isTrue 必需→可选、接受 `"0.2"`/`"0.3"`)、CONTEXT §二、前端 `SCHEMA_VERSION` 常量 + `Rule.isTrue` 可选。
   ```bash
   mvn -q test && (cd web && npm test)   # 回归全绿;无 isTrue 世界过、带 isTrue 世界仍过
   ```
4. **修仙 registry 条目**(`hp`/灵力/境界 + `axisRole` + 灵根 trait + ruleForm 无 `isTrue`)。
5. **world-gen + event-loop 注入块**(修仙世界观 + 境界累积 + 灵力消耗 + 心法规则;lockstep 运行时副本 `WorldGenPromptBuilder`/`TurnPromptBuilder` 与 `.md` 同步)。
6. **修仙卡片 CSS 氛围**(缥缈仙途;沿用 `vibeClass()` 可扩展形态)。
7. **真 key 冒烟(验收门,Felix 在场)**:修仙 world-gen 首过有效率 / 三轴含境界齐 + `axisRole` 对 / rules 无 `isTrue` 不再首过修复(F-013 验证点)/ 境界累积稳 / 境界低或 0 时引擎不误死(F-012 验证点)/ 灵力消耗合理 / 消毒无泄露。**此关不过不算完成。**
8. 冒烟通过后 `/roadmap-update` + CONTEXT 回写(§二 isTrue 可选 + `schemaVersion` `"0.3"`、§三.8 触底按 `axisRole`、§三.14 `axisRole` 字段、§三.4 cultivation 激活)+ FINDINGS F-012/F-013 标「ADR-009 根治、已关闭」。

## 实际效果(事后补充)

*修仙真 key 冒烟时回填:accumulation 轴(境界)`≤0` 是否真的不再被引擎误判触底(F-012 根治成立否);心法型 rules 省略 `isTrue` 是否真的不再触发首过修复(F-013 根治成立否);depletion 轴(`hp`/灵力)触底是否仍正确强制坏结局;以及修仙体感(缥缈仙途张力 + 境界成长感)。*

*日后加第三个累积轴模式(如 AI 觉醒/算力轴)时回填:`axisRole` 二分是否仍够用、是否真做到「加累积轴模式零提示词补丁、零误触底」。*

## 跟其他文档的交叉引用

- **起源 / 经验**:FINDINGS F-012(引擎 `≤0` 触底假设只对 depletion 轴成立)/ F-013(骨架强制 `isTrue` 与非真假守则世界冲突)——本 ADR 根治、届时标「已关闭」。
- **前序 ADR / 同款纪律**:ADR-008(引擎/校验对数值 key 无知 + 「核心泛化 + parity 护城河」先例,本 ADR 延续其哲学,只在 `≤0` 触底处做有界让步)。
- **本批实现蓝本**:`docs/phase2-cultivation-mode.md`(修仙最小可玩 + 本 ADR 落地切分/测试/冒烟门)。
- **数值权威 / 消毒边界**:CONTEXT §三.8(数值=AI 提议绝对值、引擎落账,本 ADR 补「触底按 axisRole 分支」)/ §三.9(消毒)/ §三.14(per-archetype 元数据,本 ADR 加 `axisRole` 字段)/ §二(schema,本 ADR 标 `isTrue` 可选 + `schemaVersion` `"0.3"`)。
- **配套源文件**:`server/.../engine/Engine.java`(`anyAttributeBottomedOut`/`forceBottomOutEnding` 按角色 gate)、`server/.../engine/GameSchemas.java`(isTrue 可选 + schemaVersion 双版本)、`server/.../archetype/AttributeAxis.java`(`axisRole` 字段)、`server/.../archetype/ArchetypeRegistry.java`(修仙条目 + 现有轴标角色)、`server/.../worldgen/GameInitService.java` + `server/.../eventloop/GameSessionManager.java`(accumulation key 集合播种)、`prompts/world-gen.md`、`prompts/event-loop.md`。

## 不在本 ADR 范围

- 修仙具体内容深度(五行灵根细分/功法树/宗门/丹药/法宝/渡劫细节)→ backlog,Phase 3 软启动后依真实用户数据 + 混合模式需求决定;本批只做最小可玩(`hp`/灵力/境界三轴 + 灵根 trait)。
- accumulation 轴「过高致死/有代价」的引擎化(刻意归 AI,引擎只管 `≤0` 触底)。
- 其余世界库模式;混合模式 fusion(Phase 3)。
