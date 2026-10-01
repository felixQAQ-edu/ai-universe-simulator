# 层 3.1 勘察底稿 · 确定性事件推进

> **性质**:事实底稿,不是方案。只写事实与问题,不写推荐。截至 `main@6e1d12e`(2026-09-30)。
> 出处写 `文件:行号`;行号会漂,以写成时为准。推断标「推断」,没核实的标「未核」。

## 1. 问题(原文)

[求职线层 3.1](backlog-career-track.md)(`backlog-career-track.md:382-391`):

> **引擎保证事件发生,模型只决定它怎么被写出来。**
> ⚠️ **但它需要一轮发散,不是直接进实现。** …… **先有答案,再有刀。**

层 3 依赖链(同文件 `:375-380`):`PostgreSQL + 幂等(层 2) → 确定性事件推进引擎 → 把事件/状态能力封装为领域工具 → Tool Calling + Trace + 回放`。

## 2. 今天一个回合里,谁决定了什么

**引擎确定性决定**(`Engine.apply`,`server/.../engine/Engine.java:267-343`;降级走 `applyNoOp` `:352`):
回合号 `turn += 1`(`:269`)/ 各轴取模型给的绝对值、跳变 >40 记 issue、clamp 0–100(`:273-281`)/
`log` 追加与 `LOG_KEEP=4` 折叠(`:295-303`,`:39`)/ 结局 id 须存在于 `endings[]`(`:315`)/
结局极性 gate:致命轴 ≤10 时拒绝 `success`、改挑失败结局(`:316-323`,阈值 `:47`)/
致命轴触底强制 `ended`、未给结局时兜底挑一个(`:336-341`)。
事件循环侧:一生制世界的人生阶段(`LifeStageTables.of` → `table.stageAt(nextTurn)`,`TurnPromptBuilder.java:196-204`)、
「就到这里」出口追加(`EventLoopService.java:352,372`)、收束段致命轴下限钳制(`:262,438-470`)。

**模型决定**(`TurnPromptBuilder` 骨架 `:116-130`):叙事正文、各轴新绝对值、`timeline`、
`triggeredRuleIds` / `discoveredRuleIds`、2–4 个 `availableActions`、是否提议 `ending` 及其 id。
世界本身(`rules` / `endings` 及其 `condition` / 开场)由 world-gen 一次生成(`WorldGenPromptBuilder.java:50,68`)。

**两处引擎只「照收」模型给的东西**(不评价):`timeline` 缺省保留、有则整句替换(`Engine.java:283-285`);
`triggeredRuleIds` 并入 `triggered` 集合(`:287-289`)。
**「这一回合发生了什么事件」没有任何引擎侧的字段或判定** —— 事件只存在于叙事文本里(推断:基于上述字段清单,未见其他入口)。

## 3. 已知的病(原文引,不扩写)

- **F-028**(`bakeoff/FINDINGS.md:938`,现行定性 `:940-942`):「**内容不足只是表层;根因是引擎不产生事件,而《寻常》靠『一个人的一生天然会走』掩盖了这一点。**」该条定性改过两次,订正块保留(`:944-947`)。
- **F-030**(`bakeoff/FINDINGS.md:1107`):「**world-gen 的世界理解决定了后续一切:开局那一发若没把『必须发生的事』纳入世界设定,逐回合的素材永远等不到它的位置**」;与 F-028「属同一个大问题的两侧」(`:1129`)。
- **ADR-021《动物人生》**:第二局后「当前实现**停止** —— 不是『再修一刀』」(`docs/adr/ADR-021-…md:759`);解冻条件原文(`:774-778`):「**当『事件推进』这一层有了答案之后再回来。** ⚠️ 不设时间,不设『下一刀』——这条不是排期,是前提。」
- **ADR-020《寻常》刀 4**(`docs/adr/ADR-020-…md:231-240`):「单局玩到**第 200 回合仍未收束**(设计 40–55)」,当时归因:「三条核心约定 …… **全部以『人生阶段』为条件,而模型不知道自己在哪个阶段** …… **缺的不是三条指令,是一个时钟。**」(同见 F-020,`FINDINGS.md:450-466`,已关闭。)
- **「不写死回合数上限,硬上限是引擎层决策不混入」** 出处:`TurnPromptBuilder.java:140`(javadoc)、`prompts/event-loop.md:36`;`LifeStageTables.java:34-35` 引用它并称出自 `FUSION_TURN_DIRECTIVE`。引擎侧今天**没有**回合数硬上限(`Engine.apply` 无此分支;`NarrativeHistoryReader.java:29` 亦据此写「没有硬上界」)。
- 跨世界对照(`FINDINGS.md:1010-1016`):「时钟管刻度、族层管原则、世界层管内容 —— **没有一层管『该发生的事有没有发生』。**」

## 4. 现有可借力的结构(各一句「今天做什么」)

- `timeline`:模型维护的一句话世界线摘要,落盘、进视图 2(`Engine.java:76,283,395`)。
- `triggered`:模型报的已触发规则 id 集合;落盘(`:248-251`),**不进喂模型的 `snapshot()`**(`:392-399` 只含 turn/status/timeline/logSummary/log)。
- `rules[].isTrue / hiddenLogic / discovered`:守则真伪与隐藏逻辑,仅视图 1/2 可见;`discovered` 由引擎按模型报的 id 标注(`:290-292,442`)。
- `endings[]` + `outcome` + `condition`:world-gen 产出的结局池;引擎只读 id 存在性与 `outcome` 极性(`:315-323`)。
- `LifeStageTable`:per-world「回合号 → 阶段」表 + 收敛窗口(《寻常》45–55 `LifeStageTables.java:55-56`,《动物人生》45–48 `:110-111`);只产出位置感,不强制收束(`:34-35`)。
- `exitAlreadyPressed`:从玩家动作 id 历史推出的硬信号(`EventLoopService.java:404`)。
- `Engine.recordIssue`:引擎侧数值异常记录入口,落盘可回载(`EventLoopService.java:465-470`)。
- per-archetype 指令槽 / 族层片段:把世界特有文本注入 prompt 的既有通道(ADR-020 §10、ADR-021)。

## 5. 硬约束(任何方案都得过)

- **golden parity**:`EngineGoldenTest` / `TransformParityTest` / `ValidatorParityTest`,Java 逐字段 == Python 三路径(ROADMAP Phase 1 行)。
- **prompt lockstep**:`ContentSafetyPromptLockstepTest`、`LifetimeFamilyLockstepTest`、`ActionHintPromptLockstepTest`、`TurnFusionLockstepTest`、`FusionMetaPromptLockstepTest`;`prompts/*.md` 与运行时副本同步。
- **schemaVersion**:校验只接受 `{0.2,0.3,0.4}`(`GameSchemas.java:38-42`);现值 "0.4"。
- **三视图消毒**:内部全量 / 喂模型 / 客户端,客户端绝不含 `isTrue`/`hiddenLogic`(CONTEXT §三.9)。
- **引擎对数值 key 语义无知**:只读 `axisRole` / `lethal` 两类由播种层传入的集合(CONTEXT §三.5、§三.8、ADR-008/009/010)。
- **两套线上口径**:回合走哨兵流式、world-gen 保 json_object 无哨兵(CONTEXT §三.10,ADR-006/007)。
- **持久化边界**:落盘 = 视图 1,轴语义集不落盘、经 registry 重派生(CONTEXT §三.17,ADR-015)。
- **事务边界**:「不许把 LLM 流式调用包在数据库事务里」(`backlog-career-track.md` 层 2)。

## 6. 待讨论的问题(只列问题)

1. **来源**:事件由谁定义 —— world-gen 一次给定 / 引擎规则表 / 模型提议、引擎裁决 / 混合?
2. **粒度**:「事件」是一个世界级的必经节点,还是每回合都有?两者是否同一种东西?
3. **触发条件**:按回合号、按数值轴、按玩家动作、按 `timeline` 语义,还是组合?谁判断条件成立?
4. **与叙事的边界**:引擎交给模型的是「必须写出 X」还是「X 已经发生,请描写」?模型不写时怎么算?
5. **验证**:怎么知道模型真的写出了那件事 —— 结构化字段、文本匹配、还是不验?
6. **失败**:模型不执行 / 写错 / 超长时 —— 重试、降级、引擎强行推进、还是只记 issue?
7. **与 F-030 的关系**:开局那一发是否也要受事件层约束?world-gen 失败语义(整局 ERROR)是否变化?
8. **与既有机制的关系**:ending 池、`triggered`、`LifeStageTable`、出口、钳制中,哪些会被事件层取代或包住?
9. **兼容**:四个基础世界 + 两融合 + 《寻常》没有「必须发生的事」时,事件层是空还是必须声明?旧存档怎么回载?
10. **状态落点**:事件状态是否进 state、进哪个视图、是否改 `schemaVersion`?
11. **ADR-008 无知原则**:引擎要不要懂事件语义?若不懂,它靠什么判定「发生了」?
12. **怎么测**:确定性部分能否进 golden;涉及模型的部分用什么代替真机冒烟(F-027 验证成本由人承担)?
13. **层 2 依赖**:依赖链把层 2 列为前置,而层 2 线上未切库(ADR-025 刀 4 挂账)—— 这条依赖是硬的还是可松?

## 7. 与层 3.2 的接口(backlog 原文)

`backlog-career-track.md:393-399`:「建在 3.1 之上以后,被调用的就不是『一个会调函数的模型』,而是一个**真实、可事务化、可回放的领域系统**」;外部审查原判断见[工程债 §3.1](backlog-engineering-debt.md)(`:418` 依赖顺序同链,`:430` 建议的一刀 =「受控 Tool Calling 执行器 + Agent Trace + 状态变更入数据库事务」)。

---

已进入 [ADR-028](adr/ADR-028-box-scene-changeable-left-behind.md)(已采纳):《动物人生》单个局面的纵向样本,不是本底稿 §6 的一般答案。
