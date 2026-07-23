# ADR-018 · 基础世界视觉移植:severity 语义契约 + 单一主题注册表 + 四刀切分

- **日期**:2026-07-24
- **状态**:已采纳
- **决策者**:Felix

## 背景

[ADR-017](ADR-017-frontend-visual-charter-and-animation-libraries.md) 立了视觉宪法、白名单三库与第一版范围,并把执行形态**两段分开**:探索走 Cowork 独立视觉样板间(不直接改 `web/src`),落地回 Claude Code 流程(feature 分支 + 测试 + 冒烟)。探索段已完成——样板间 `~/code/wanjie-visual-lab` 的四世界视觉语言、动效编排与记忆点方案**已验收冻结**,移植勘察已完成,八个待决问题已全部裁定。

本 ADR 是**落地段的总纲**:它不重复样板间的视觉设计(那是被冻结的成果),而是回答「把冻结的成果搬进生产,架构上要立哪些字」。

### 移植不是复制粘贴

样板间与生产是两种东西,四条结构性差异决定了移植必须重写接线而非搬运文件:

| | 样板间(冻结成果) | 生产 `web/` |
|---|---|---|
| 数据 | 写死的示例数值与文案 | AI 实时生成,数值每回合变、文案不可预测 |
| 组织 | 六个彼此独立的演示屏 | **一套组件切皮肤**(`GameScreen`/`StatsPanel`/`Prose`/… 四世界共用) |
| 轴 | 硬编码轴名(「气血」「理智」写死在 JSX) | **轴语义无知**(ADR-008:按 init 下发的 `attributes` 元数据遍历渲染,加世界不改组件) |
| 世界数 | 四个基础世界 | 四基础 + **两个融合局**(识海遗蜕 / 缺页的人防工程),融合局的轴带换皮(道心 / 补给) |

样板间可以写「气血低于 20 就变红」;生产不可以——生产的组件在运行时**不知道自己在渲染哪个世界的哪根轴**,也不该知道。这条约束不是本 ADR 新加的,它是 ADR-008 立的引擎/展示层数值无知在视觉层的必然延伸。于是问题变成:**危险感这种语义,谁来产出。**

### 触发本 ADR 的具体缺口

样板间四世界的数值组件都有「危险态」表现(规则怪谈的血色渗出、修仙的气血枯竭、克苏鲁的深陷、末日的濒临饿毙)。要在生产复现,渲染方必须知道「当前这一档危不危险」。而生产的 `StatsPanel` 今天拿到的 `bands` 只有 `{min, max, label}`——`label` 是给人读的中文短词(「濒危」「深陷」「灵力枯竭」),**不是给机器判的语义**。前端要么按 label 文本猜(脆、一改文案就错)、要么按 key 硬编码(违 ADR-008)、要么按数组位置猜(下一节说明为何这也是错的)。

## 候选方案

### 方案 A:前端自己判断危险(按 key 硬编码 / 按 label 文本匹配 / 按位置启发)

前端维护一张「哪些 key 是生命轴、哪个位置的 band 危险」的表。

**优点**:
- 后端零改动,刀 0 可以直接跳过。

**缺点**:
- **违 ADR-008 引擎/展示层数值无知**:每加一个世界或一根轴,前端要跟着改表——正是 ADR-008 花大力气消除的代价结构;
- 融合世界直接失效:道心(`san` 换皮)、补给(`hunger` 换皮)在前端看是同一个 key,但换皮后的 `bands` 文案与 host 世界观绑定,label 匹配必错;
- 按位置猜更错:轴的档数不保证是 3,且 `bands` 在 registry 里的**存储顺序是降序**(depletion 写作 100/50/20),下标语义完全不可靠;
- 「这个数值危不危险」是**世界设计语义**,前端手里没有做出该判断所需的信息(`axisRole`/`lethal`/是否双刃全在服务端元数据里,且刻意不下发)。

**排除**:让消费方猜生产方才知道的事,是本项目已经三次拒绝过的做法。

### 方案 B:服务端派生 severity,前端只渲染(本 ADR 采纳)

`axesJson` 的每个 band 增一个 `severity: "neutral" | "caution" | "danger"`,由服务端据轴元数据(`axisRole` / `lethal` / 新增 `perilAtHigh`)派生。前端只做**数据匹配**(`min ≤ value ≤ max` 找当前档)与**渲染**(按 severity 决定视觉状态),不做任何语义判断。

**优点**:
- 语义在掌握语义的那一层产出,与 ADR-010 / ADR-011 同构(见「关键理由」);
- 融合世界零登记自动正确:`AxisSkin` 只换 `displayName`/`bands`/`behaviorHint`,不换 `axisRole`/`lethal`/`perilAtHigh` → 道心与补给自动继承 host 侧的正确 severity,加组合不为此做任何事(守 F-016 成本模型);
- 前端四套皮肤只呈现风险等级,加世界 = 后端加一条元数据,前端零改。

**缺点**:
- 多一个展示层元数据字段(`perilAtHigh`),元数据面继续变厚。

**采纳**:缺点是一个字段,优点是把「危险感」从四套皮肤里各判一次收敛成服务端派生一次。

### 方案 C:服务端直接下发视觉指令(颜色 / class 名 / 动画参数)

**优点**:
- 前端最薄。

**缺点**:
- 把展示层决策搬进后端,违 ADR-003「展示层语义无关」的反向——后端开始关心红色和闪烁;
- 每次调色板微调都要改后端并重新部署;
- 四世界视觉语言不同(同为 danger,规则怪谈是血色渗出、克苏鲁是孢子扭曲),下发颜色等于把每世界视觉语言表也搬进后端。

**排除**:severity 是**风险等级**,不是**视觉表现**。下发前者、不下发后者,是这条线的正确切法。

## 最终决策

### 1. 语义产出方原则(本 ADR 的核心立字)

> **语义由掌握语义的那一层产出,消费方一律无知。**

推论(逐字锁,后续视觉工作一律援引):

- 前端根据明确区间匹配当前 band(`min ≤ value ≤ max`)= **数据匹配,合法**;
- 前端判断这个 band 危不危险 = **语义判断,必须在服务端完成**;
- 四套数值组件**只呈现风险等级,不猜测数值好坏**。任何主题组件不得重新解释轴语义。

这是该原则第三次实例化:

| ADR | 语义 | 产出方 | 消费方 |
|-----|------|--------|--------|
| [ADR-010](ADR-010-ending-outcome-polarity-gate.md) | 结局极性 `outcome` | AI 标注 | 引擎只读、不解读结局语义 |
| [ADR-011](ADR-011-action-hint-narrative-metadata.md) | 选项风险提示 `hint` | AI 写 | 引擎只读透传、永不 gate/掷骰 |
| **ADR-018** | 数值风险等级 `severity` | **服务端派生** | **前端只渲染** |

三次的形状一致:产出方是唯一掌握该语义的层,消费方拿到结论就用、不复算、不反推、缺失就安全降级。

### 2. severity 契约(刀 0 的全部范围)

**2.1 wire 形态**

`InitResponse.attributes[].bands[]` 每项由 `{min, max, label}` 增为 `{min, max, label, severity}`,`severity ∈ {"neutral", "caution", "danger"}`。

- **不改 world JSON、不改 prompt、不改 turn delta**;
- `schemaVersion` 保 **"0.4"**——`bands` 一族走 **API DTO**(`InitResponse`),非被校验的 wire schema(同 #3 数值行为化落 `bands` 时的口径,CONTEXT §三.14);
- **init 与 resume 走同一构建路径**(现状即 `GameInitService.attributeMeta(List<AttributeAxis>)` 唯一构建点,两处共用;本 ADR 把它由现状升为约束)。

**2.2 判别位:`perilAtHigh`(第四个展示层轴元数据)**

`AttributeAxis` 增 `perilAtHigh`(布尔,默认 `false`)。

**定位写死**:`perilAtHigh` 是**纯展示层元数据**——**引擎/校验绝不读它**,不进 JSON schema、不进 wire schema、`schemaVersion` 保 "0.4"。它与 `bands`/`behaviorHint` 同族(伴生结构,非 state schema 字段)。

**血统**(per-archetype 轻量元数据的第四次扩充):

| ADR | 加的字段 | 引擎读不读 |
|-----|---------|-----------|
| [ADR-008](ADR-008-multi-mode-extension-architecture.md) | 立 per-archetype 轻量元数据 + 引擎数值无知;`behaviorHint` | 不读 |
| [ADR-009](ADR-009-axis-roles-and-rule-form-flexibility.md) | `axisRole` | **读**(引擎唯一会读的轴语义,触底二分) |
| [ADR-010](ADR-010-ending-outcome-polarity-gate.md) | `lethal` | **读**(致命轴触底 + 结局极性 gate) |
| [ADR-014](ADR-014-fusion-skeleton-parameterization-and-second-combo.md) | `AxisSkin.behaviorHint` override | 不读 |
| **ADR-018** | **`perilAtHigh`** | **不读** |

**为什么需要它(如实记)**:原 brief 给的派生规则(「lethal accumulation 最高档 danger」)与验收 ②(「禁忌知识高位 danger」)**自相矛盾**——禁忌知识是 accumulation 但 `lethal=false`(`AttributeAxis.accumulating()` 硬写,CONTEXT §三.14 亦立字「accumulation 轴恒 `lethal=false`」),按原规则应全 neutral。仓库里**不存在 lethal accumulation 轴**,且 `lethal` 是引擎会读的字段(改它会改触底与结局 gate 行为)——**绝不能为了染色去动它**。矛盾出在 brief;实现前停下报告是正确处理。

今天能区分「禁忌知识越高越危」(双刃)与「境界越高越好」(纯成长)的,只有 `behaviorHint` 里的中文散文,不可机读。`perilAtHigh` 就是把这个已经存在的世界设计事实变成机器可读的一位:

- `knowledge`(禁忌知识)`perilAtHigh = true`——累积型双刃,越高越接近疯狂;
- `realm`(境界)`perilAtHigh = false`——纯成长,越高越强。

两者同为 accumulation 却结果相反,**正是该字段存在的理由**。

**2.3 派生规则(四分支)**

| 轴 | severity |
|----|----------|
| 非致命 depletion(灵力) | 全 `neutral` |
| 致命 depletion(体力/理智/饥饿/气血/道心/补给) | 最低档 `danger`、次低档 `caution`、其余 `neutral` |
| accumulation 且 `perilAtHigh`(禁忌知识) | 最高档 `danger`、次高档 `caution`、其余 `neutral` |
| 其余 accumulation(境界) | 全 `neutral` |

**实现约束(逐字锁)**:

- **不得按数组下标写死「第二低 / 第二高」**——registry 里 `bands` 的存储顺序是降序(depletion 写作 100/50/20),下标语义不可靠。**先按 `min` 排序**(即挂在已排序的 `bandRanges()` 输出上)**再据轴角色标记边缘档**及其相邻档;
- 档数 `< 2` 时**只标边缘档、不报错**(退化情形合法);
- `bands` 异常(重叠 / 留空 / 无序 / 越界 / label 空)**在后端测试直接失败**——现状已由 `AttributeAxis` 构造器 `validateBands` 保证(registry 配错档 = 类加载即抛,上不了线),本 ADR 把它升为显式验收项。**绝不下发一份让前端猜的区间表**。

**2.4 融合世界:per-combo 对 severity 零登记**

`AxisSkin`(ADR-012 决策 2 / ADR-014 决策 4)**不得换 `perilAtHigh`**——它与 `key`/`axisRole`/`lethal`/`min`/`max` 同属「换皮不换」的清单。换皮只换 `displayName`/`bands`/`behaviorHint`。

因此融合局的 severity **自动等同于对应 host 侧单体**,新增融合组合**不为 severity 做任何事**(守 [F-016](../../bakeoff/FINDINGS.md) 立的复用成本模型:加一对组合 = 后端 5 处纯文案 + 前端 3 条登记,不因视觉再加一处)。

**2.5 前端缺省行为(一律安全降级)**

四种缺省情形——**无 `bands`** / **值找不到所属 band** / **老数据无 `severity`** / **未知 `severity` 值**——统一表现为:

> 数字与 label 正常显示,**不附加危险色、不进入警告态**。

**绝不默认 `danger`,绝不回退旧启发式**(不按 key 猜、不按 label 文本猜、不按位置猜)。降级方向恒为「安静」——把不确定渲染成危险,等于给玩家一个假的状态播报。

### 3. 四刀切分

| 刀 | 范围 | 状态 |
|----|------|------|
| **刀 0** | severity 契约(后端派生 + TS 类型与兼容解析) | 本工作单元 |
| **刀 1** | 共享基建 + 规则怪谈试验田 | 另起 |
| **刀 2** | 修仙(含钟鸣记忆点) | 另起 |
| **刀 3** | 末日 | 另起 |
| **刀 4** | 克苏鲁(含文字异常记忆点) | 另起 |

**刀 0 · severity 前置**
做:后端 severity 派生(init/resume 同一路径)+ 前端 TS 类型与兼容解析。
**不准顺手做**:不上任何新皮肤、不改任何组件渲染、不装任何 npm 依赖、不碰 `game.module.css` 的视觉、不动 `StatsPanel.barClass` 与 `ArchetypeSelect.vibeClass`(它们归刀 1 收编)。

**刀 1 · 共享基建 + 规则怪谈试验田**
做:主题注册表、数值组件通用层/主题层分工、动效令牌(`--t-dur`/`--t-ease`)、生命周期 teardown、feature gate,**只有规则怪谈登记新主题**。
**不准顺手做**:不顺手把另外三个世界一起登记(试验田的意义是先证明基建,四世界一起上等于没有试验田);不做记忆点(记忆点跟着各自世界的刀走);不改后端。

**刀 2 · 修仙**
做:修仙主题登记 + 一级记忆点「一声钟鸣」(ADR-017 §6.3 订正后口径)。
**不准顺手做**:不动共享基建的结构(基建缺口应回头改刀 1 的产物、不在主题层打补丁);不给别的世界加记忆点。

**刀 3 · 末日**
做:末日主题登记 + 电台内容池。
**不准顺手做**:不做「饥饿」轴改名(已在 [打磨与愿景 backlog §6](../phase2-polish-and-vision-backlog.md) 独立挂账,涉及 prompt/condition 文案,不属视觉刀);不动共享基建结构。

**刀 4 · 克苏鲁**
做:克苏鲁主题登记 + 一级记忆点「文字异常」(含形近字表,见 §4 Q3)。
**不准顺手做**:不做 AI 标注异常字方案(依赖尚不存在的 structured narrative schema,已挂 [future-experience-backlog](../future-experience-backlog.md));不因形近字不够而降质替换成任意字。

### 4. 刀 1 的结构性要求(ADR 立字,实现留后续工作单元)

**4.1 单一主题注册表**

**世界判定只发生一次**,子组件消费判定结果,**不得各自再判 archetype**。

收编现有两处私有分派:

- `web/src/features/game/scene.ts` 的封面分派(已有测试与缺图降级,形态正确,收编即可);
- `web/src/features/game/ArchetypeSelect.tsx` 的私有 `vibeClass`(**无测试、无降级**,一并提升到注册表并补上两者)。

**4.2 数值组件职责边界**

- **通用层**:遍历 axes / 匹配 band / 取 severity / 输出可访问文本;
- **主题层**:只决定形态与视觉状态。

**任何主题组件不得重新解释轴语义**(不得读 key 判断这是什么轴、不得读 label 判断危不危险)。

**4.3 动效令牌 `--t-dur` 与 `--t-ease` 成对落地**

只换时长不换缓动 = 四世界只是**快慢不同**,不是**时间感不同**——那不叫每世界有自己的视觉语言。两者必须成对,并含 `prefers-reduced-motion` 覆盖值。

**4.4 生命周期:同一个 teardown**

组件 unmount / 主题切换 / turn 切换**必须走同一个 teardown 函数**,不得出现三套近似清理逻辑(三套近似逻辑 = 三处各自漏一点,且漏在最难复现的路径上)。

- `generating` 与 reveal 打字期,低频调度**停表**(正文是禁区,AGENTS.md Motion Constraints §5);
- 测试环境补 `matchMedia` polyfill(jsdom 不带,同 Slice 2 补 `localStorage` polyfill 的先例)。

**4.5 feature gate**

刀 1 后**只有规则怪谈登记新主题**,其余世界走旧实现。出问题**删一条注册即回退**,不得要求回滚整套基建。

### 5. 其余七问裁定(入档)

**Q2 · 融合局的游戏内视觉**
已定调:入口互噬 / 局内 host 打底 / foreign 走安全渗漏签名 / 共享低频槽位 / 不挂业务轴 / per-combo 只碰文案封面与静态标记。
**但签名未实现前,融合局纯 host 呈现**(同 `scene.ts` 未配图即降级的先例),**不阻塞基础移植**。详细方案挂 **ADR-019**(尚未开写)。

**Q3 · 形近字表**
**100–200 对人工允许表**,入选标准:常用字 / 该字体下肉眼近似 / 替换后不荒诞 / 无罕见异体字 / 不依赖特定字体。
**不用 Unicode 相似度运行时推断**(推断出的「近似」在实际字体下经常不近似,且会撞出荒诞语义)。
**找不到候选即切换其余五型异常,不强行降质**。
AI 标注异常字方案挂 [future-experience-backlog](../future-experience-backlog.md)(依赖 structured narrative schema)。

**Q4 · 克苏鲁文字异常的启动条件(回改 ADR-017 §6.3)**
以样板间实现为准:**全程极低频 + san 调频率**,而非「san<20 才启动」。
**理由**:异常只在低位启动,会使「异常出现」本身成为**状态播报**——玩家看到一次扭曲就知道自己进了危险区,记忆点退化为状态指示器。
补上下界立字:

- 高 san:极低频,但**非绝对零**;
- 低 san:仍保持**长沉默**与**不可预测**;
- **任何状态都不得形成周期**(防止玩家用频率反推状态)。

**Q5 · 修仙钟鸣的触发条件**
挂 `realm` **向上**跨 band(`newBandIndex > oldBandIndex`;**下跌不鸣**)。

- init/resume **首次装载不触发**(须已有前值 → 新 turn 值 → 向上跨档);
- 一次跨多档**只鸣一次**;
- **钟鸣期间新 turn 到达**:数值按真实 turn 更新,当前 timeline 安全收尾或快速归位,**不排队补播**——**仪式感不以状态滞后为代价**;
- `bands` 缺省则不触发(静默降级)。
- 「盯 `realm`」属**主题注册表的按 key 配置**,**锁在注册表内,不得渗进通用数值组件**。

**Q7 · debug 入口**
仅 `?debug=1`。**长按标题不进生产**(该手势已被选择屏的融合渗漏占用)。
`debug` 参数**不写入持久状态**;debug 控件**不得改变生产数据**,只允许触发前端展示与查看调度状态。

**Q8 · 测试双层**

- **共享语义断言**:value / displayName / 当前 band label 可读、severity 正确映射、`bands` 缺省不崩、未知 severity 安全降级;
- **每形态一条特征元素存在性冒烟**。

**不断言 DOM 层数 / 像素 / 分段数量 / class 排列**——同 golden 哲学:**护城河守行为,不守像素**。

### 6. 挂账(不进本批)

| 项 | 去处 |
|----|------|
| 正文版式差异化 | C 批(叙事壳层差异化),已挂 [future-experience-backlog](../future-experience-backlog.md) |
| notice 跳位 | 并入 [打磨与愿景 backlog §10](../phase2-polish-and-vision-backlog.md) 同族(静默 no-op UX) |
| 动效预算在生产的重新记账 | 刀 1 时顺带盘点:`.caret` blink 与 `ruleJustDiscovered` 脉冲是否计入 AGENTS.md §3 的槽位;**超预算则降级**(守「新增须替代或降级」) |

## 关键理由

1. **语义产出方原则第三次实例化,形状与前两次完全一致**。ADR-010 让 AI 标 `outcome`、引擎只读;ADR-011 让 AI 写 `hint`、引擎不裁决;本次让服务端派生 `severity`、前端只渲染。三次都是同一个判断:**让消费方去猜生产方才知道的事,是可预见的错误来源**——而且错法都一样(消费方会发明一套启发式,启发式在新世界/新组合上失效)。
2. **`perilAtHigh` 换来的是融合世界的零登记**。它不只是让禁忌知识出 danger:因为 `AxisSkin` 不换该标,两个融合组合的道心与补给自动继承 host 侧派生结果,per-combo 对 severity 一行不写。这与 F-016 的复用成本模型对齐——视觉层不给「加一对组合」的代价再加一项。
3. **严守引擎无知**。`lethal` 是引擎会读的字段,为了染色去改它会静默改变触底与结局 gate 行为;新加一个引擎不读的展示位,是唯一不污染内核的做法。
4. **降级方向恒为「安静」**。四种缺省情形一律不进危险态。视觉宪法第一条(内容永远第一,唯一一票否决项)在这里的具体形态:**不确定时,不吓玩家**。
5. **试验田 + feature gate 让刀 1 可回退**。只有规则怪谈登记新主题,出问题删一条注册即回退——不必在四个世界都上线之后才发现基建有结构问题。

## 已知代价

1. **元数据面继续变厚**:`AttributeAxis` 现有 8 个字段,再加 `perilAtHigh` = 9。缓解:它是引擎不读的展示位,新加世界不配即默认 `false`(安全方向)。
2. **`perilAtHigh` 是人工判断,可能配错**:没有机制能验证「这根轴高位到底危不危险」。缓解:它只影响染色、不影响任何判定;配错的后果是一根轴少染或多染一档色,不影响游戏逻辑。
3. **severity 是三档粗粒度**:样板间某些世界的危险表现是连续的(渐变而非跳档)。本批不做连续映射——三档对齐已有的三 band 结构,连续映射需要前端拿到更多语义(违本 ADR 的原则)。若后续确有需要,应扩 severity 取值而非让前端插值。
4. **刀 1 后的一段时间里,四世界视觉不一致**(规则怪谈新、其余三个旧)。这是 feature gate 的代价,也是它的目的;若中途暂停,不一致状态会停留更久。
5. **`?debug=1` 是生产可达的入口**:任何人拼 URL 即可打开。缓解:debug 控件不得改变生产数据、不写持久状态,最坏情况是玩家看到一些调度信息。

## 重新审视的触发条件

- **前端出现「想按 key/label 判断点什么」的第二次冲动**——说明 severity 三档不够用,回窗口讨论扩取值,**不得就地发明启发式**;
- **某个融合组合的 severity 需要 per-combo 覆盖**——说明「换皮不换 `perilAtHigh`」的假设破了,须回 ADR-012/014 的换皮清单重新对齐;
- **刀 1 的主题注册表被要求承担轴语义**(如「这个主题下 hp 要特别处理」)——说明通用层/主题层的切分位置错了,回头改切分,不在主题层堆特例;
- **记忆点数量超过一级表**(AGENTS.md Motion Constraints §2 的唯一表)——按 ADR-017 §9「新增须替代或降级」处理,不得只做加法。

## 实施步骤

1. **刀 0 · 后端**:`AttributeAxis` 增 `perilAtHigh` + severity 派生(挂已排序的 `bandRanges()`),`GameInitService.attributeMeta` 下发;init/resume 同一路径。
2. **刀 0 · 前端**:更新 TS 类型与兼容解析(`severity` 可选字段,四种缺省安全降级);**不上皮肤、不改渲染**。
3. **刀 0 · 测试**:① 境界(accumulation,`perilAtHigh=false`)高位 `neutral` ② 禁忌知识(accumulation,`perilAtHigh=true`)高位 `danger` ③ 气血(致命 depletion)低位 `danger` ④ 四种缺省情形安全降级 ⑤ init 与 resume 得到相同 severity ⑥ turn delta 只更新 value 后,前端用 init 保留的 `bands` 正确重算当前档 ⑦ 两个融合局的 severity 与各自 host 单体一致。
4. **刀 1–4**:各自独立工作单元,按 §3 范围与「不准顺手做什么」执行。
5. **ADR-019**:融合局游戏内视觉(Q2 详细方案),尚未开写。

## 实际效果(事后补充)

_待刀 0 落地与后续各刀冒烟后回填。_

## 跟其他文档的交叉引用

- 修订/延续:[ADR-017](ADR-017-frontend-visual-charter-and-animation-libraries.md)(视觉宪法与库白名单;§6.3 记忆点口径由本 ADR §5 Q4 回改)
- 同族原则:[ADR-010](ADR-010-ending-outcome-polarity-gate.md)、[ADR-011](ADR-011-action-hint-narrative-metadata.md)(语义产出方原则前两次实例化)
- 元数据血统:[ADR-008](ADR-008-multi-mode-extension-architecture.md)、[ADR-009](ADR-009-axis-roles-and-rule-form-flexibility.md)、[ADR-012](ADR-012-hybrid-axis-merge-strategy.md)、[ADR-014](ADR-014-fusion-skeleton-parameterization-and-second-combo.md)
- 边界:[ADR-003](ADR-003-frontend-stack-and-taro-boundary.md)(接口纪律一字不动;`api/` 薄适配层与 `TurnStream` 不受本 ADR 影响)
- 约定:[CONTEXT.md](../CONTEXT.md) §三.14(per-archetype 元数据;`perilAtHigh` 为伴生结构,`schemaVersion` 保 "0.4")
- 常驻约束:[AGENTS.md](../../AGENTS.md) Motion Constraints
- 挂账:[phase2-polish-and-vision-backlog.md](../phase2-polish-and-vision-backlog.md) §6/§9/§10、[future-experience-backlog.md](../future-experience-backlog.md)
