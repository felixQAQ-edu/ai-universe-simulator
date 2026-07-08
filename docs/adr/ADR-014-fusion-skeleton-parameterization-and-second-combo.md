# ADR-014 · 融合骨架参数化 + 第二组合「守则即补给」(rules_creepy × apocalypse)

- **日期**:2026-07-08
- **状态**:已采纳
- **决策者**:Felix

## 背景

混合模式 round 1(修仙 × 规则怪谈「识海遗蜕」,[ADR-013](ADR-013-hybrid-fusion-protocol.md))已真 key 整局验通。round 1.5 目标 = 落第二对彩蛋 **规则怪谈 × 末日生存「守则即补给」**(host=rules_creepy,容器=缺页的人防工程,backlog §8 组合①),并以此回答 F-016 复用性答卷:「加一对组合」的真实代价结构到底是什么。

勘察结论:**机制层对新组合零改动**(F-016 承诺在机制层完全兑现)——init 双值 / `mergeAxes` / 播种派生 / 引擎 / `TurnPromptBuilder.resolveContext` 的融合感知全部 key-agnostic,host 反转零成本。**未兑现的债在文案层**:`FUSION_SKELETON`(world-gen 融合骨架)与 `FUSION_TURN_DIRECTIVE`(event-loop 融合回合指令)在 Slice D/E 抢修时硬编码了 round-1 专属文案(心魔伪笔 / 真传心法 / 道心 / 气血 / 识破伪笔 3 条 / 参悟心法调息丹药…)。直接复制骨架给第二组合 = 违背 ADR-008「通用骨架单点维护」精神(消毒 / id / 单轴绑定这类安全规矩重抄一次错一次)。

约束条件:

1. **引擎 / 校验 / `schemaVersion`(保 "0.4")一律不动**——参数化与第二组合都只在播种层 + 提示词 + 前端(守 ADR-008/012/013)。
2. **守 ADR-007**:world-gen 保 json_object、纯 JSON、无哨兵、不加预调用。
3. **守 ADR-011**:守则只描述定性风险 / 代价氛围,无精确数字、无判定规则;引擎只读透传永不 gate / 掷骰。
4. **round-1 零回归是 parity 线**:参数化前后,识海遗蜕的 world-gen 融合 prompt 与 event-loop 融合段**逐字节不变**;单体四世界 prompt 照旧逐字不变;EngineGoldenTest 字节零回归。

## 候选方案

### 方案 A:每组合复制一份骨架

为 rules_creepy×apocalypse 再写一份 `FUSION_SKELETON_RENFANG` / `FUSION_TURN_DIRECTIVE_RENFANG`,与 round-1 并列。

**优点**:
- 最快,零抽象风险,round-1 天然逐字不变。

**缺点**:
- 骨架里的**安全规矩**(泄露硬约束 / id 类型 / outcome 必填 / 单轴绑定 / 通关判定 / 有据恢复)每组合重抄一份,改一处漏一处——正是 ADR-008 明文反对的形态;round 2 自由勾选后组合数上去,债滚成灾。

### 方案 B:骨架槽位参数化(本 ADR 采纳)

`FUSION_SKELETON` / `FUSION_TURN_DIRECTIVE` 保持**单份骨架**,把 round-1 专属文案抽成 **per-combo 注入槽**(致命轴中文名清单、真 / 假守则两类称呼、资源经济示例、hint 示例、结局条数随致命轴数、裁决口吻等),槽值从 combo 文案配置注入;round-1 文案全部迁回槽位。

**优点**:
- 结构约束(安全规矩)单点维护,组合只带文案;「加一对组合」收敛为「一条登记 + 一份文案配置 + 一段 meta-prompt + 一次冒烟」。
- 致命轴清单 / 结局条数从 `fusedAxes` 派生,与播种同一真理源,不会写漂。

**缺点**:
- 槽位切分本身有一次性成本,且切错会破坏 round-1 逐字 parity。缓解方式:改动前 dump round-1 prompt 基线,参数化后 byte-diff 全空才算过。

### 方案 C:把融合提示词整体下放为 per-combo 全文

骨架消失,每组合一份完整融合提示词文本(类似 `FUSION_META_PROMPTS` 的全文版)。

**优点**:
- 每组合文案自由度最大。

**缺点**:
- 与方案 A 同病:安全规矩随全文复制漂移;meta-prompt(组合内核)与骨架(结构约束)的分层本来就是 ADR-013 已验证的形态,推翻它没有反证证据。

## 最终决策

**方案 B — 骨架槽位参数化**,并按此落第二组合。六个子决策:

### 1. 槽位清单(骨架单点维护,combo 只带文案)

`FUSION_SKELETON` / `FUSION_TURN_DIRECTIVE` 抽出的 per-combo 槽:

- **致命轴中文名清单**(如「气血/道心」→「体力/理智/补给」)——从 `fusedAxes` **派生**,非手写;
- **结局条数**——随致命轴数走:失败结局 = 每致命轴各 1 条单轴绑定 + 成功 ≥1;
- **真 / 假守则两类称呼**(round1=真传心法 / 心魔伪笔;本组合=真页 / 假页);
- **资源经济示例、hint 示例、结局 condition 示例、裁决与恢复口吻**等文案槽——per-combo 文案配置(纯数据)。

round-1(识海遗蜕)文案全部迁回槽位,**parity 线 = 迁移前后 prompt 逐字节不变**(worktree/dump 对拍)。lockstep 守护测试从「全局短语表」改为**按 combo 分组**;`prompts/*.md` 同步参数化说明、升版号。

### 2. 第二组合登记:rules_creepy × apocalypse(host=rules_creepy)

容器=缺页的人防工程;真 / 假称呼=真页 / 假页;裁决体系=**物证与尸体**(第 13 条方法论必入墙上五条)。素材=Fable「守则即补给」成品批,按决策 6 采用。

### 3. hunger 换皮「补给」(AxisSkin)

displayName 饥饿→补给,bands 重写(充足 51–100 / 紧缺 21–50 / 断粮 0–20,阈值沿用 depletion 50/20);`FUSION_IMAGERY` 配套补给口吻;**key / axisRole / lethal 不变,引擎无感**。单体末日的「温饱」改名 backlog 单元不受影响、原样挂着。

### 4. AxisSkin 微扩:behaviorHint 可换(修订 ADR-012)

**修订 ADR-012 决策 2 的「behaviorHint 不换」清单**:hunger 原 hint 含「饥饿值」字样,换皮后两套叫法打架 → `AxisSkin` 增 **behaviorHint override 位**(缺省 null=沿用原 hint,round-1 换皮零变化);本组合给「补给随回合消耗,每回合约 -5~10(搜刮 / 配给才回升)」口吻。key / axisRole / lethal / min / max 依旧不换,引擎依旧无感。

### 5. 三致命轴结局池(首例)

融合轴集 {hp体力, san理智, hunger补给} 全 depletion 全 lethal——**首例三致命轴**,勘察确认引擎天然支持(连特殊分支都不进);结局池 ≥4 条(3 条单轴绑定失败 + ≥1 成功),条数随参数化按轴数走。

### 6. 素材采用

内核=版本 A(配给日)+ 版本 C 的「新住户读墙」作开场事件(第一回合读墙上五条);守则=墙上五条(含第 9 条调包 + 物证三件)+ §a 数值入守则 + §b 四组对射 + §c 两难一二三;通关=结局 B(守夜人)为主、C(新页)入池。

### 关键理由

1. **还的是 ADR-008 的债**:骨架单点维护是多模式扩展架构的既定形态,D/E 抢修硬编码只是权宜;round 2 自由勾选前必须还清。
2. **致命轴清单 / 结局条数派生自 `fusedAxes`**——与播种、event-loop 注入同一真理源(ADR-013 Slice D 的教训:两处各写必漂)。
3. **parity 线可机械验证**(byte-diff),抽槽不靠肉眼保平安。
4. **AxisSkin 微扩走最小口子**:只开 behaviorHint 一个 override 位,且缺省不换——round-1 的「不换清单」行为原样保留。

## 明确不做(承重接缝)

1. 卖页的「庇护松动 / 消失重演」与 Ω 罐头的「迟早对账」均需跨回合追踪 → **降级为纯叙事**(设定与两难保留、机制不实现);完整版记 backlog 归主题 A(持久世界记忆)。
2. 守则只描述风险氛围,**无精确数字、无判定规则**(守 ADR-011)。
3. **不做第三对、不开自由勾选**;前端手势泛化只泛化到「组合表」(为 round 2 留口),不做组合选择 UI。
4. **引擎 / 校验 / `schemaVersion`(0.4)不动**。

## 已知代价

1. **槽位抽取的一次性复杂度**:骨架从「一段直读的文本」变成「模板 + 十余个槽」,读者要跳一次配置才能还原全文。缓解方式:round-1 槽值即原文逐字迁移(byte-parity 锁死),`prompts/*.md` 保留人类可读全文示例。
2. **槽位粒度是赌注**:本次按两个组合的差异切槽,第三对组合可能暴露新的硬编码残留(如裁决体系口吻更异质)。接受:届时按同法再抽,parity 线机制已就位、成本递减。
3. **修订 ADR-012 的「不换清单」**:behaviorHint 开了 override 口子,换皮的「引擎无感」边界从「五项不换」缩为「四项不换 + 一项文案可换」。接受:behaviorHint 本就是「引擎一概不读」的提示文本(CONTEXT §三.14),换它不触引擎语义;缺省 null 保 round-1 零变化。
4. **三致命轴让容错更薄**:三条命都能死,局面比 round-1 更严苛。接受:这正是「守则即补给」的题眼(资源即生存);资源经济 + 通关判定(ADR-013 Slice E 口径)原样生效兜体验。
5. **方案 A 的速度被放弃**:复制骨架本可当天完事。接受:round 2 自由勾选在 backlog §8 排着五对组合,债此刻不还只会更贵。

## 重新审视的触发条件

- round 2 第三对组合落地时,若 per-combo 文案配置仍需改骨架本体 → 槽位粒度切错,重切;
- 自由勾选(任意 2–3 archetype)启动时,手写 per-combo 文案配置不可持续 → 升级为文案生成/组合规则引擎(出新 ADR);
- 若真 key 冒烟发现三致命轴下 AI 结局池稳定漏配某轴失败结局 → 结局条数约束从提示词升级为校验层(动 `validateWorld`,需新 ADR);
- `behaviorHint` override 之外再出现第三个想换的字段(如 min/max)→ 停下重审 AxisSkin 边界,别继续开口子。

## 实施步骤

1. Slice 0:本 ADR 落档 + README / ROADMAP §五 索引。
2. Slice A:骨架参数化 + round-1 迁槽(parity 线先行)——dump 基线 → 抽槽 → byte-diff 全空;lockstep 测试按 combo 分组;`prompts/*.md` 升版号。
3. Slice B:第二组合五处登记(FUSION_COMBOS + AxisSkin + meta-prompt + 种子池 + FUSION_IMAGERY)+ 派生断言(accumulation={}、nonLethal={}、致命={hp,san,hunger});落完停下,真 key curl 冒烟(5 发 init:真假页配比各 ≥3 / 三轴齐且 hunger 显「补给」/ 结局池 ≥4 且失败单轴绑定 / 无泄露 / 无精确数字 / 首过率)。
4. Slice C:前端手势泛化(组合表 + 交叉序列不误触发)+ 第二渗漏卡(冷蓝×锈土)+ 封面键 fusion-renfang(图未就位回落占位)。
5. 验证:`mvn test` 全绿 + 前端全绿 + EngineGoldenTest 零回归 + 识海遗蜕 prompt 逐字零回归 + 单体零回归;Felix 浏览器整局终验。

## 实际效果(事后补充)

*round 1.5 真 key 冒烟 + Felix 整局终验时回填:真假页配比首过率、三轴任一归零是否给对轴失败结局、通关(守夜人 / 新页)可达性、「加一对组合」的实际 per-combo 改动清单(F-016 复用性答卷定量)。*

## 跟其他文档的交叉引用

- **起源**:bakeoff/FINDINGS F-016 复用注(机制层零改动的承诺)+ [打磨与愿景 backlog §8](../phase2-polish-and-vision-backlog.md)(round 2 五对候选,①=本组合)。
- **前序 ADR**:[ADR-012](ADR-012-hybrid-axis-merge-strategy.md)(轴合并 + AxisSkin;本 ADR 修订其 behaviorHint 不换清单)、[ADR-013](ADR-013-hybrid-fusion-protocol.md)(融合协议;本 ADR 参数化其 D/E 抢修的硬编码骨架)、[ADR-007](ADR-007-world-gen-wire-protocol.md) / [ADR-008](ADR-008-multi-mode-extension-architecture.md) / [ADR-011](ADR-011-action-hint-narrative-metadata.md)(约束条件)。
- **相关源文件**:`server/.../worldgen/WorldGenPromptBuilder.java`(FUSION_SKELETON)、`server/.../eventloop/TurnPromptBuilder.java`(FUSION_TURN_DIRECTIVE)、`server/.../archetype/ArchetypeRegistry.java`(FUSION_COMBOS / AxisSkin)、`prompts/world-gen.md` / `prompts/event-loop.md`(lockstep 资产)。
