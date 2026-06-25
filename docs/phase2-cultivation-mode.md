# Phase 2 · 修仙模式设计稿(最小可玩 + 两个架构正解)

> 交新 Claude Code 窗口。从 `main`(@ `cac5238`,克苏鲁已合)起新分支 `phase2/cultivation-mode`。
> 性质:**Phase 2 至今最重的一批**——最小可玩修仙内容 **捆** 两个攒下来的架构正解(ADR-009:F-012 轴角色 + F-013 isTrue 可选)。**重心在架构正解,不在修仙深度。**
> 依据:ADR-009 brief(本批落地它)、ADR-008(引擎/校验对数值 key 无知)、FINDINGS F-012/F-013、`docs/world-library-expansion-backlog.md`(修仙=第二级压力测试)、末日/克苏鲁设计稿(同款流水线)。

## 0. 核心定位 + 范围闸

- 修仙是 backlog **第二级压力测试**:灵根/境界离 hp/san 最远,验流水线能否扛全新数值体系。
- **境界 = 第二个累积轴样本**(克苏鲁 knowledge 第一个)→ 现在出 F-012 引擎正解时机成熟。
- **范围闸(严防修仙膨胀)**:修仙世界观极庞大(炼气/筑基/金丹…境界体系、五行灵根、功法/丹药/法宝/宗门…)。**本批只做最小可玩**:够味道 + 验流水线 + 落架构正解。**修仙深度挂 backlog,Phase 3 软启动后依真实用户数据 + 混合模式需求决定**(让数据拉动,非偏好推动)。
- **本批两件事别混**:(A) 修仙内容(轻)、(B) ADR-009 架构正解(重,引擎改动)。B 是真分量。

## 1. 修仙内容(轻,最小可玩)

**签名轴**:`hp`(气血,depletion 复用)+ **`境界`(accumulation,主角)** + `灵力`(depletion,类 san 的资源池)。
- **境界**:累积型成长轴,玩家修炼/突破 → 上涨。**纯成长、不参与死亡判定**(修仙死于 hp 触底/渡劫失败,非境界)。它是 F-012 `accumulation` 角色的样本。
- **灵力**:depletion 型,施法/突破消耗,可恢复(打坐/丹药)。≤0 = 力竭(走 depletion 触底语义,或仅惩罚——实现时定,但它是 depletion 角色)。
- 最小化:不做五行灵根细分、不做功法树、不做宗门系统——这些是"做厚"的内容,Phase 3 再说。灵根可作 `character.traits` 里一个文字属性(如"天灵根/废灵根"),影响叙事但不单开数值轴。

**元数据**(`ArchetypeMeta`,沿用末日/克苏鲁形态 + ADR-009 新 `axisRole`):
- `displayName`:修仙
- `tagline`(钩子):如「逆天改命,踏上仙途。一念成圣,一念成魔。」
- `vibeTag`:如「缥缈 · 仙途」
- `worldview`:修仙母题(灵气、宗门、洞天福地、渡劫飞升、心魔)
- `attributes`:hp(气血,`axisRole=depletion`)/ 灵力(`depletion`)/ 境界(`axisRole=accumulation`)——各带 displayName + 范围 + behaviorHint
- `ruleForm`:修仙不是真假守则——**心法/天道法则/修行禁忌**型(如"心魔不可纵"、"渡劫忌分心")。**rules 不带 isTrue**(F-013 正解落地点)。

**规则形态(F-013 落地)**:修仙规则是"修行法则/心法",非真假混合 → **rules 省略 isTrue**(ADR-009 isTrue 可选)。`discovered` 机制复用(顿悟/突破时揭示法则)。

## 2. 架构正解(本批真分量,ADR-009)

### F-012 · 数值轴角色 axisRole(引擎改动,parity 守)
- 元数据每轴声明 `axisRole: depletion | accumulation`。
- **引擎触底判定按角色分支(唯一新增语义,最小)**:depletion 轴 ≤0 → 强制 ended + 兜底坏结局(现状);accumulation 轴 → **不触底**(引擎绝不因它 ≤0 判死)。
- "过高有代价"仍归 AI 落、引擎无知(克苏鲁 knowledge↔san 那套)。引擎只懂"这轴是不是 depletion、要不要管 ≤0"。
- 现有轴标角色:hp/san/hunger/灵力=`depletion`;knowledge/境界=`accumulation`。**克苏鲁 knowledge 从"提示词正基线兜"升级为引擎根治**(F-012 关闭)。
- **golden parity 字节级守**(现 162 测):depletion 轴(hp/san/hunger)触底行为零回归——证 accumulation 分支没动 depletion。沿用 ADR-008 泛化纪律:机械改动、parity 不绿即停。**这是引擎动刀的安全网,优先做、先确认 parity 再继续。**

### F-013 · isTrue 全局可选(schema 放宽)
- `rules[].isTrue` 必需→**可选**:谁要给(规则怪谈/克苏鲁真假守则型)、谁不要不给(修仙心法型)。
- **校验器零分派**(不按 archetype 判),守 ADR-008 校验无知。
- **`schemaVersion` "0.2"→"0.3"**(Felix 已拍:第一次真动字段约束,升主版本保纪律一致)。CONTEXT §二 `rules[].isTrue` 标可选 + version history;`WORLD_SCHEMA` 的 const schemaVersion 同步 "0.3";golden 夹具/parity 若钉 "0.2" 需同步评估(向后兼容:老带 isTrue 数据在新 schema 仍合法)。

## 3. 切分顺序(由架构到内容,parity 优先)

> 与前几批"由纯到脏"不同:本批**引擎正解优先**(它有回归风险,先在 parity 网下做掉)。

1. **F-012 axisRole 引擎正解**:元数据加 `axisRole`、引擎触底按角色分支、现有轴标角色 → **golden parity 162 字节级守 depletion 零回归 + 加 accumulation 不触底单测**。先它,先确认 parity。
2. **F-013 isTrue 可选**:schema 放宽 + `schemaVersion`→"0.3" + 校验/golden 同步 → 回归全绿。
3. **修仙 registry 条目**(hp/灵力/境界 + axisRole + 灵根 trait + ruleForm 无 isTrue)。
4. **world-gen + event-loop 注入块**(修仙世界观 + 境界累积 + 灵力消耗 + 心法规则形态;lockstep 运行时副本;保留叙事清洁度 + 长度约束)。
5. **修仙卡片 CSS 氛围**(缥缈仙途:青白/流光 + 缓慢上浮光点/雾气,沿用 `vibeClass()` 可扩展形态)。
6. **真 key 冒烟**(验收门,**待 Felix 在场**)。

## 4. 测试矩阵(确定性,不打真实 API)

- **回归护城河(第一不变量,本批风险最高)**:引擎改 axisRole + schema 改 isTrue 后,**server 162 + frontend 29 全绿、golden parity 字节级守 depletion 零回归**。引擎动了,parity 是没动坏的唯一判据。
- **F-012 新测**:accumulation 轴(境界/knowledge)≤0 **不**触发 ended(对照 depletion 轴 ≤0 仍 ended);引擎对 `{hp,灵力,境界}` 按角色正确分支。
- **F-013 新测**:无 isTrue 的 rules 通过 `validateWorld`(修仙型);带 isTrue 的仍通过(规则怪谈/克苏鲁型);schemaVersion "0.3" 校验对。
- 修仙 registry 条目 + 三轴 + axisRole 标注;`listForSelection()` 含修仙(active);前端面板渲染"气血/灵力/境界"。
- 引擎无知再确认:`Engine.apply` 对 `{hp,灵力,境界}` 行为 = 通用结算 + axisRole 触底分支(无修仙语义)。

## 5. 冒烟门(真 key · 此关不过不算完成 · 待 Felix 在场)

- 跑几个修仙 init → world-gen 首次有效率(对照,**不外推**)、格式完整、**三轴(含境界)齐 + axisRole 对**、**rules 无 isTrue 不再触发首过修复**(F-013 验证点)、消毒无泄露。
- 驱回合,专门观测:
  1. **境界累积**:修炼/突破时 AI 是否稳定让境界上涨?
  2. **境界不误死(F-012 验证点)**:境界即使低/为 0,引擎不误判触底死亡?
  3. **灵力消耗**:施法/突破时灵力 depletion 是否合理?
- 修仙题材叙事泄露人眼扫;体感(缥缈仙途张力 + 境界成长感出来没)。
- 存修仙真实 raw + SSE 转录(untracked)。
- **Felix 读数 + 拍板**:修仙是否验通 / 架构正解真实成立否(accumulation 不误死、isTrue 省略无首过修复)/ 体感 / 要否调提示词。

## 6. 落档 / 纪律 / 收口

- **ADR-009 先落**(本稿引用它,实现前独立 commit);本设计稿入 `docs/`。
- 冒烟过后:`/roadmap-update`(修仙落地 + ADR-009 根治 F-012/F-013 + 流水线扛全新数值体系验证);CONTEXT v?.?(§二 isTrue 可选 + schemaVersion "0.3" + §三.8 触底按 axisRole + §三.14 axisRole 字段 + §三.4 cultivation 激活);FINDINGS F-012/F-013 标"ADR-009 根治、已关闭"。
- 一段完整工作做完再 commit;**不替 Felix push/合并**(等冒烟 Felix 定);`bakeoff/recordings/` 别碰。
- **引擎改动撞到意外(parity 守不住、axisRole 二分不够用、isTrue 可选连累别处)→ 停下报 Felix**(架构信号)。

## 7. 不在本批
- 修仙做厚(五行灵根/功法树/宗门/丹药/法宝/渡劫细节)→ backlog,Phase 3 软启动后依数据 + 混合需求定;accumulation 轴"过高致死"的引擎化(刻意归 AI);其余世界库模式;混合模式(Phase 3)。
