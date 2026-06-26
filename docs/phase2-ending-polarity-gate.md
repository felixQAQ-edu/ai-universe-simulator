# Phase 2 · 结局极性 gate 设计稿(B · 根治 F-014)

> 交新 Claude Code 窗口。从 `main`(@ 修仙批合并后的最新)起新分支 `phase2/ending-polarity-gate`。
> **前提**:修仙批(含 A 提示词强化 + §5 修复)需先收口合并到 main——本批从那之后起。若修仙批尚未合,先确认合并状态(见 §0)。
> 性质:**引擎结局判定改动**(ADR-010),根治 F-014——A(提示词)连三局濒死仍得成功结局,证明 AI 故事惯性压不住,需引擎强制把关。
> 依据:ADR-010 brief(本批落地它)、ADR-008(引擎/校验无知)、ADR-009(axisRole)、FINDINGS F-014/F-015、event-loop 规格 §4.4/§5。

## 0. 前置:修仙批合并状态

本批改的结局判定,叠在修仙批(A + §5 修复)之上。**起分支前先确认**:`git log --oneline -3 main` 看修仙批(含 `52b0cbe` F-014 A+§5)是否已在 main。
- **已合** → 从 main 起 `phase2/ending-polarity-gate`。
- **未合**(可能 Felix 让先验 B 再一起收口)→ 报 Felix 定:是先合修仙批再起本批,还是本批续在 `phase2/cultivation-mode` 上。**别擅自合并。**

## 1. 边界:复用 / 新造

| 模块 | 状态 | 依据 |
|------|------|------|
| `Engine.apply` 数值结算 / clamp / 跳变 / log / accumulation 不触底(ADR-009) | ✅ 复用零改 | 本批只加结局极性 gate,不碰数值结算 |
| §5 兜底(刚修的中文名匹配) | ✅ 复用 + 接入 gate | gate 挑 failure 结局时复用 |
| A 提示词约束(濒死倾向失败结局) | ✅ 保留作软第一层 | ADR-010 决策 3:A+B 双保险 |
| **`endings[].outcome` 极性字段(AI 标)** | 🆕 | ADR-010 决策 1 |
| **axis `lethal` 标(仅 hp 类)** | 🆕 | ADR-010 决策 2,关闭 F-015 |
| **§4.4 致命轴把关 gate** | 🆕 引擎改动 | ADR-010 决策 2 |
| **schemaVersion 0.3→0.4** | 🆕 | outcome/lethal 字段新增 |

> 引擎仍无知:只读 `outcome` 标签 + 看 lethal 轴数值,不懂结局中文语义(守 ADR-008)。

## 2. 架构正解(本批核心,ADR-010)

### 2.1 结局极性 `outcome`(AI 标,引擎只读)
- schema `endings[].outcome`:`success | failure | neutral`(可选,缺省视为 `neutral`/不强 gate)。
- **world-gen 时 AI 标**:每个 ending 生成时标极性(AI 写"筑基功成"标 success、"经脉俱断"标 failure)。注入块加指引:**结局必须标 outcome,失败/陨落/死亡类=failure,圆满/突破/逃生类=success**。
- 引擎/`toClientState`:outcome 是结局元信息,客户端可不显(或用于 UI 区分),不涉隐藏逻辑。

### 2.2 致命轴 `lethal`(仅 hp,关闭 F-015)
- 元数据 axis 在 `axisRole=depletion` 基础上加 `lethal: true|false`。
- **仅 hp 类生命轴 lethal=true**(规则怪谈/克苏鲁/末日 hp、san;修仙气血)。**灵力 lethal=false**(枯竭=力竭非必死,关闭 F-015)。hunger 视模式定(末日饥饿致死 → lethal=true)。
- 引擎据 lethal 标判断哪些轴濒零触发 gate。

### 2.3 §4.4 致命轴把关 gate(引擎改动,parity 守)
逻辑(加在 `apply` 步骤 9 结局接受处):
```
若 AI 提议 ending{reached:true, id, outcome}:
  若 存在 lethal 轴濒零(value ≤ ENDING_GATE_THRESHOLD,建议 10):
    若 该 ending.outcome == success:
      → 引擎拒绝该成功结局(不接受)
      → 从 endings[] 挑一个 outcome==failure 的确定性落账
         (优先 §5 中文名匹配致命轴 condition 的 failure 结局;退而挑首个 failure;再退 §5 兜底)
    否则(failure/neutral):正常接受(濒死给失败/中性合理)
  否则(无 lethal 轴濒零):正常接受(§4.4 现状,gate 不介入)
```
- **引擎只看 outcome 标签 + lethal 轴值**,不懂结局语义。
- 阈值 `ENDING_GATE_THRESHOLD`(建议 10)与触底 0 分开:濒死(≤10)就 gate 成功结局,不必等触底(0)。

### 2.4 schemaVersion 0.3→0.4(Felix 已拍)
- outcome/lethal 字段新增 → 升 0.4。CONTEXT §二 + `WORLD_SCHEMA` const 同步;校验接受 {0.2,0.3,0.4}(沿用修仙批"接受多版本守 parity 夹具"做法,parity 夹具是旧版本产出)。
- 向后兼容:无 outcome 的旧结局视为 neutral(不 gate);无 lethal 的旧轴默认按生命轴语义保守处理(实现时定缺省,但 golden 夹具行为须零回归)。

## 3. 切分顺序(parity 优先,引擎改动先做)

1. **schema + 字段**:`endings[].outcome` + axis `lethal` + schemaVersion 0.4(校验接受多版本)→ 回归全绿。
2. **§4.4 致命轴 gate 引擎正解**:lethal 轴濒零拒绝 success 结局 + 确定性挑 failure → **golden parity 字节级守零回归**(golden 无 outcome/非濒死路径行为不变)+ gate 新单测(濒死+success→拒绝改 failure / 濒死+failure→接受 / 非濒死→不介入 / 无 failure 结局可挑时的退路)。先它,先确认 parity。
3. **元数据标 lethal**:现有 archetype 的 hp/san/hunger=lethal,灵力=false;关闭 F-015。
4. **world-gen 注入块**:AI 标 outcome 指引(失败/陨落=failure…)；lockstep `.md` + 运行时副本。A 的濒死约束保留。
5. 冒烟(验收门,**待 Felix 在场**)。

## 4. 测试矩阵(确定性,本批回归风险在引擎结局判定)

- **回归护城河(第一不变量)**:加 gate 后 server 175 + frontend 30 全绿、**golden parity 字节级守零回归**(golden 结局结算路径不变)。引擎动了,parity 是唯一判据,不绿即停。
- **gate 新测**:lethal 轴濒零(hp≤10）+ AI success → 引擎拒绝、改挑 failure；+ AI failure → 接受;非濒死 + success → 接受(不介入)；lethal=false 轴(灵力)濒零 + success → **不**触发 gate(关闭 F-015 验证)；无 failure 结局可挑 → 退路(§5 兜底/首个/合理 fallback)。
- outcome/lethal schema 测:带 outcome 的 world 过校验、缺省 neutral；lethal 标解析对;schemaVersion 0.4 + 接受 {0.2,0.3} 旧夹具。
- 引擎无知再确认:gate 只读 outcome + lethal 轴值,不含结局语义判断。

## 5. 冒烟门(真 key · 此关不过不算完成 · 待 Felix 在场)

- 跑修仙 init → endings 是否都带 outcome 标(AI 标对没)、格式完整、消毒无泄露。
- **核心验证(F-014 根治)**:把角色玩到**气血濒死(≤10)**→ **AI 即使想给成功结局,引擎是否拒绝、改给失败/陨落结局**?这是本批存在的唯一理由,反复验几局确认稳定。
- **F-015 验证**:灵力枯竭(=0)但气血尚可时,**游戏不因灵力 gate 成失败结局**(灵力非致命轴)。
- §5 兜底:AI 给 null 时,兜的是失败结局(中文名匹配修复 + gate 协同)。
- 体感:濒死给对失败结局后,沉浸感是否回来(不再"濒死却筑基功成"的违和)。
- 存修仙真实 raw + SSE 转录(untracked）。
- **Felix 读数 + 拍板**:F-014 是否真根治(濒死稳定给失败结局）、F-015 关闭(灵力不误死）、体感。

## 6. 落档 / 纪律 / 收口

- **ADR-010 先落**(本稿引用,实现前独立 commit);本设计稿入 `docs/`。
- 冒烟过后:`/roadmap-update`；CONTEXT(outcome + lethal + schemaVersion 0.4 + §三.8 极性 gate)；FINDINGS **F-014 标"A+§5+B 根治、已关闭"**、**F-015 标"ADR-010 关闭"**。
- 一段完整工作做完再 commit;ADR 落档与实现分开;**不替 Felix push/合并**(等冒烟 Felix 定）；`bakeoff/recordings/` 别碰。
- **引擎改动撞意外(parity 守不住 / gate 误伤正常结局 / outcome 标注 AI 不可靠到 gate 形同虚设)→ 停下报 Felix**(架构信号)。

## 7. 不在本批
- 结局叙事质量(好坏文笔);accumulation 过高致死(归 AI,ADR-009 已定);修仙做厚(backlog,Phase 3 数据拉动);其余世界库模式;混合模式(Phase 3)；难度/配图(backlog)。
