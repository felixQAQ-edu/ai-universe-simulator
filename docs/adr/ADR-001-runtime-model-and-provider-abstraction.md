# ADR-001 · 运行模型选 DeepSeek V4-Flash 为主力,provider 走 OpenAI 兼容配置表抽象

- **日期**:2026-06-17
- **状态**:已采纳
- **决策者**:Felix

## 背景

Phase 0 · 核心验证阶段。MVP(规则怪谈 `rules_creepy`,单体模式)要在前端动工前,先敲定**给玩家生成内容的运行模型(run-time)** 以及**调用各家模型的抽象方式**——这是后续所有内容生成的地基。

决策前置:CONTEXT 已约定「两个模型别混」——build-time 用 Claude/Claude Code 写代码,run-time 用可换的国产 provider 给玩家生成内容(CONTEXT §三.2)。本 ADR 只定 run-time。

本决策**不靠拍脑袋**:先按 [ADR-001-bakeoff-checklist.md](ADR-001-bakeoff-checklist.md) 实现了一套对比测试脚本(`bakeoff/`),用统一提示词 + 固定场景组 A(world-gen 质量)/ B(连推 10 回合 ×3 路径)横评,产出可复核数据([bakeoff/out/report.md](../../bakeoff/out/report.md)),并把过程暴露的 schema 问题记进 [bakeoff/FINDINGS.md](../../bakeoff/FINDINGS.md)。

约束条件:

1. 目标用户在中国(微信生态),运行模型须用**国内可稳定调用**的 provider(DeepSeek / 通义千问 / 智谱 / 豆包 / Kimi 一类),不依赖境外 API。
2. event-loop 是**高频**步骤(每回合一次调用),成本与延迟敏感;world-gen 是**低频高价值**步骤。
3. 抽象层要满足 ADR-001 清单点 2 的假设:**换 provider 只改配置**,不重写业务管线。
4. 本轮范围只测 run-time 内容生成、只测规则怪谈单体;混合模式(fusion)与内容审核(ADR-004)不在本轮(留素材)。

## 候选方案

### 方案 A:硬编码单一 provider 原生 SDK

直接用 DeepSeek 官方 SDK,接口、参数、解析全写死在管线里。

**优点**:
- 最快上手,无抽象开销。
- 能用上该家所有原生特性(缓存、思考开关等)。

**缺点**:
- 违反约束 3:换 provider 要重写调用层与解析,绑死单一供应商。
- 限流 / 涨价 / 模型下架时无兜底,风险集中。
- 后续横评、A/B、分层都难做。**排除**。

### 方案 B:各 provider 原生 SDK + 自建统一抽象接口

为每家写适配器,封一层自定义统一接口。

**优点**:
- 每家原生特性都能吃满。
- 抽象边界清晰。

**缺点**:
- 每接一家都要啃一套 SDK 与鉴权,工作量随 provider 数线性膨胀,对业余时间投入不友好。
- 多数国产大模型**已提供 OpenAI 兼容接口**,自建原生适配是重复造轮子。**排除**(性价比低)。

### 方案 C:OpenAI 兼容接口 + 配置表抽象(本 ADR 采纳)

统一用 OpenAI 兼容协议调所有 provider,换家只改一张**配置表**(`baseUrl`/`model`/`apiKey`/价格/思考开关/`maxContext`);各家非标参数(思考模式)在一个**适配器函数**里收口。主力选 **DeepSeek V4-Flash(非思考)** 跑 event-loop。

**优点**:
- 直接满足约束 3:实测「改配置即可换 provider」成立(见最终决策)。
- 接新 provider 几乎零成本(填一行配置),利于后续横评与兜底。
- 复用成熟的 `openai` SDK,无需逐家啃。

**缺点**:
- OpenAI 兼容接口可能吃不到某家**全部**原生特性;非标能力要靠 `extra_body` 逐个适配。接受——收口在单点,主流程不被污染。

## 最终决策

**方案 C — OpenAI 兼容 + 配置表抽象**,主力 **DeepSeek V4-Flash**。

### 1. provider 配置表

单一事实源 [`bakeoff/providers.py`](../../bakeoff/providers.py),每个 provider 一条记录:`base_url` / `model` / `api_key_env` / `price`(命中/未命中/输出分列)/ `thinking` / `max_context`。换 provider = 改这张表。本轮实跑 `deepseek-v4-flash`,Qwen / GLM / Pro 为横评占位。

### 2. 思考模式适配器

各家「思考开关」非标参数在 `client._thinking_extra_body()` 单点翻译(DeepSeek `thinking.type` / Qwen `enable_thinking` / GLM `thinking`),经 `extra_body` 注入,**不污染主流程**。

### 3. 分层与兜底(建议,待横评最终确认)

- **主力**:DeepSeek V4-Flash 跑高频 event-loop(本轮已验证)。
- **高价值步骤**:world-gen / 难场景建议走 DeepSeek V4-Pro(占位,单价待核)。
- **兜底**:建议通义千问(阿里云基建稳),待横评数据确认后定。

### 4. 验收实测(回填 ADR-001 清单 §3,DeepSeek V4-Flash · CONTEXT v0.2)

| 指标 | 实测 | 校准后通过线 | 判定 |
|---|---|---|---|
| JSON 有效率(修复后,硬门) | 100% | ≥99.5% | ✅ |
| JSON 有效率(首次,软门) | 100%(0 修复) | ≥90% | ✅ |
| 连推 10 回合自洽(3 路径) | 0 矛盾 | 无逻辑矛盾 | ✅ |
| hiddenLogic 不泄露 | 0 | 0 | ✅ |
| 首字延迟 TTFT | ~1.0s | ≤2s | ✅ |
| 完整回合延迟 | ~5.8s | ≤10s | ✅ |
| 单回合成本 | ~¥0.002 | ≤¥0.01 | ✅ |
| 缓存命中率 | ~30% | 观察项 | 👀 |
| 可用性(错误率) | 0% | <1% | ✅ |
| 中文叙事质量 | 待人工盲评 | 均分≥4.0 | ⏳ |

### 关键理由

1. **数据驱动,不拍脑袋**:9 项工程指标 8 项达标 + 1 项观察,唯一未结是主观叙事质量(需盲评)。呼应约束 1/2:国产、便宜、低延迟、高频可承受。
2. **抽象假设被实测证伪/证成**:只改 `base_url`/`model`/`api_key` 即可切换,「改配置换 provider」成立(约束 3)。
3. **避开供应商绑死陷阱**:相比方案 A,保留了横评、兜底、分层的演进路径。
4. **schema 在验证中收敛**:bake-off 暴露并修掉两类「模型产出 vs schema 错配」(F-001 id 异型陷阱、F-004 endings 字段),已固化进 CONTEXT v0.2,从源头降 JSON 失败率,而非靠提示词反复打补丁。

## 已知代价

1. **结论建立在单 provider 上**:本轮只实跑了 DeepSeek V4-Flash,Qwen / GLM / 豆包 / Kimi 的横评未做,「主力 vs 兜底」「是否分层」尚缺对照数据。缓解方式:配置表已留好占位,横评只需填 key + 核单价后重跑,追加对比列即可。
2. **价格为占位、思考模式参数待核**:`providers.py` 中 DeepSeek 单价虽已按用户核对填入(命中 0.02 / 未命中 1 / 输出 2),但 Pro 与其余家仍占位 VERIFY;各家思考开关的真实参数名以官方文档为准。缓解方式:成本/延迟达标且留有 ~5× 余量,价格小幅波动不翻盘结论。
3. **中文叙事质量未结**:指标 #4 需人工盲评(ADR-001 §6 rubric),叙事差则即便便宜也不做主力。缓解方式:`calls.jsonl` 已留全量素材,可隐去 provider 直接盲评。
4. **缓存命中率偏低(~30%)**:DeepSeek 省钱假设在本轮多样化场景下命中不高;真实玩家「同一存档连续回合」场景下应更高,本轮未模拟到位。挂为观察项。
5. **抽象层只验证了「OpenAI 兼容」这一类**:若未来要接某家**仅有原生接口**的模型,本抽象不覆盖,需回到方案 B 思路单点扩展。接受——当前候选均提供兼容接口。
6. **event-loop 偶发长尾失败**:v0.2 后残留唯一首次失败为 `availableActions: []`(1/34,经一次修复重试已纠正),暂记观察,未单独立 finding。

## 重新审视的触发条件

- **横评数据出来**:Qwen / GLM 等跑完同样场景组 A+B,若某家在叙事质量或成本/延迟上明显优于 DeepSeek,重定主力 / 兜底 / 分层。
- **人工盲评不达标**:DeepSeek 叙事均分 < 3.5 → 即便工程指标全过也不做主力。
- **DeepSeek 出事**:限流频发 / 大幅涨价 / V4-Flash 下架 → 启用兜底并重排配置表优先级。
- **延迟劣化**:线上 TTFT 持续 > 2s 或回合延迟 > 10s → 重新评估模型档位或分层策略。
- **Phase 3 混合模式(fusion)**:多套设定调和的 meta-prompt 对模型能力要求更高,可能需要给 world-gen/fusion 单独选更强档位(Pro 或他家)。
- **接入仅有原生接口的新 provider**:触发抽象层从「纯 OpenAI 兼容」向「兼容 + 原生适配器」演进(代价 5)。

## 实施步骤

1. ✅ 按 [ADR-001-bakeoff-checklist.md](ADR-001-bakeoff-checklist.md) §5 实现 bake-off 脚本:统一客户端 + 配置表 + 状态机引擎 + 场景组 A/B + 报告。
   ```bash
   cd bakeoff && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
   cp .env.example .env   # 填 DEEPSEEK_API_KEY
   .venv/bin/python run.py
   ```
2. ✅ 首跑暴露 schema 问题 → 收敛进 **CONTEXT v0.2**(F-001 id 异型、F-004 endings 字段/必填 attributes),同步 `schema.py` / `prompts/world-gen.md`。
3. ✅ 重新校准 ADR-001 §3 验收标准为实测口径,复跑确认:首次/修复后 JSON 有效率 100%,工程指标全部达标(产出 `bakeoff/out/report.md`)。
4. ⏳ **待办**:人工盲评中文叙事质量(指标 #4)与 A3 规则质量。
5. ⏳ **待办**:横评 Qwen / GLM(解开 `providers.py` 占位、填 key、核单价后重跑),据数据最终确认兜底与分层。
6. ⏳ **待办**:补场景组 C(鲁棒性)/ D(结局收敛)与可选 provider。
7. 用 `/roadmap-update` 在 Phase 0 进度表加一行「provider bake-off」为已完成子任务。

## 实际效果(事后补充)

*横评 + 人工盲评完成时回填:DeepSeek V4-Flash 是否真为最优主力(对照 Qwen/GLM 的成本/延迟/叙事质量),兜底与分层的最终配置,以及真实玩家「同存档连续回合」下的缓存命中率与省钱幅度。*

*MVP 上线后回填:线上真实 TTFT / 回合延迟 / 错误率是否与实测一致。*

## 跟其他文档的交叉引用

- **决策依据 / 测试清单**:[ADR-001-bakeoff-checklist.md](ADR-001-bakeoff-checklist.md)(怎么测 + 校准后验收标准)
- **实测数据 / 发现日志**:[`bakeoff/out/report.md`](../../bakeoff/out/report.md)、[`bakeoff/FINDINGS.md`](../../bakeoff/FINDINGS.md)(F-001 ~ F-004)
- **约定基础**:[`docs/CONTEXT.md`](../CONTEXT.md) v0.2(schema / 管线 / 命名,本 ADR 触发其 v0.2 收敛)
- **配套源文件**:[`bakeoff/providers.py`](../../bakeoff/providers.py)(配置表)、[`bakeoff/client.py`](../../bakeoff/client.py)(统一客户端 + 思考模式适配器)
- **后续相关**:ADR-002(后端选型)、ADR-004(内容安全审核网关,本轮只观察留样)
