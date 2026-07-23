# AI Universe Simulator

> 基于大语言模型的生成式、可交互、无限流文字模拟游戏平台

## 项目简介

打破固定文本边界的文字模拟游戏。玩家作为绝对变量,实时介入一个由 AI 动态编织、逻辑自洽的**涌现式世界(Emergent World)**——不是在读故事,而是在改写故事线。

完整规划见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 核心特性

- **🌟 概念融合 · 混合模式(杀手锏)**:支持世界观杂交(如 修仙 × 规则怪谈、赛博朋克 × 末日生存),诞生传统配置表游戏触不到的混沌体验。
- **通用生成引擎(UG Engine)**:世界生成 → 角色/属性 → 规则矩阵 → 动态事件流 → 多结局收敛,所有模式共用一条管线。
- **结构化驱动**:AI 在叙事之外输出结构化 JSON,驱动血量 / 灵根 / San 值等数值系统,保证可玩性。
- **基础模式**:规则怪谈、人生模拟、修仙、赛博朋克、末日生存(MVP 先做规则怪谈)。

## 当前进度

- **阶段**:Phase 0 · 准备与核心验证(进行中)
- **目标用户**:主要面向中国用户(微信生态为主)
- **平台路线**:H5(响应式网页)先行 → 微信小程序(Taro)→ 可选 App
- **下一步**:定 JSON Schema + 跑通"稳定生成一个好世界、连推 10 回合不崩"

## 技术栈

- **前端**:React + Vite(H5 先行)→ Taro(微信小程序)
- **运行模型**:DeepSeek 为主,provider 可换(备选 通义千问 / Kimi / 智谱 GLM / 豆包)— 见 ADR-001
- **后端**:Spring Boot 运行于 CloudBase 云托管(见 ADR-002)
- **内容安全**:文本审核网关
- **数据**:统一 JSON Schema(世界 / 角色 / 规则 / 状态 / 行动 / 结局)

## 技术决策记录(ADR)

决策随进度建立,完整列表见 [docs/adr/](docs/adr/);首批待决策议题见 ROADMAP 第五节。

- [ADR-001](docs/adr/ADR-001-runtime-model-and-provider-abstraction.md) — 运行模型选 DeepSeek V4-Flash 为主力,provider 走 OpenAI 兼容配置表抽象(依据:[bake-off 实测](bakeoff/out/report.md))
- [ADR-002](docs/adr/ADR-002-backend-form-factor.md) — 后端形态选 Spring Boot 运行于 CloudBase 云托管(应用层自控 + 微信原生集成,薄适配层缓解锁定)
- [ADR-003](docs/adr/ADR-003-frontend-stack-and-taro-boundary.md) — 前端栈选 React+Vite H5,以接口纪律(`api/` 薄适配层 + provider-agnostic 流接口)占住 Taro 迁移边界,Phase 1 不写小程序代码
- [ADR-005](docs/adr/ADR-005-sse-web-stack-mvc-thin-seam.md) — SSE/流式 web 栈选 Spring MVC(SseEmitter)+ 可换 WebFlux 的薄接缝(`TokenStream` 解耦核心与传输)
- [ADR-006](docs/adr/ADR-006-event-loop-streaming-wire-protocol.md) — event-loop 流式线上协议选叙事先行单次调用 + 哨兵 + 结构化尾巴 + 叙事回灌复用(下游校验/引擎零改,守 ADR-005 薄接缝)
- [ADR-007](docs/adr/ADR-007-world-gen-wire-protocol.md) — world-gen 线上协议选胖调用 + 保 json_object 纯 JSON 无哨兵 + 开场叙事 reveal 不流式(可靠性留在最险的那次生成,异于 ADR-006 回合口径)
- [ADR-008](docs/adr/ADR-008-multi-mode-extension-architecture.md) — 多模式扩展架构:引擎/校验对数值语义无知 + per-archetype 轻量元数据,以「加一个模式」的代价结构为设计目标(首个落地模式=末日生存)
- [ADR-009](docs/adr/ADR-009-axis-roles-and-rule-form-flexibility.md) — 数值轴角色(depletion/accumulation)+ 规则形态弹性(isTrue 可选):引擎触底按 axisRole 二分(根治累积轴误判触底 F-012)+ 校验零分派 isTrue 可选(根治非真假守则世界冲突 F-013),golden parity 字节级守 depletion 零回归
- [ADR-010](docs/adr/ADR-010-ending-outcome-polarity-gate.md) — 结局极性 gate:结局加 AI 标注的极性 `outcome`(引擎只读)+ 致命轴 `lethal` 元数据,引擎在致命轴濒零时拒绝成功结局、确定性挑失败结局(根治濒死人物得光明结局 F-014,A 提示词软引导压不住);顺带关闭 F-015(灵力非致命轴),schemaVersion "0.3"→"0.4",golden parity 字节级守零回归
- [ADR-011](docs/adr/ADR-011-action-hint-narrative-metadata.md) — 选项风险提示为叙事元数据(#1 选择反馈定性版):`availableActions[].hint` = 一句定性风险/代价/张力提示、不含精确成功率数字,引擎只读透传永不 gate/掷骰(守 ADR-008 无知),纯提示词引导 + 前端样式、不动 schema/引擎/schemaVersion;真概率掷骰=#1.5 独立未来项冻结
- [ADR-012](docs/adr/ADR-012-hybrid-axis-merge-strategy.md) — 混合模式轴合并策略(host 优先 + 语义换皮,引擎不动):融合轴=按 key 并集,撞键 host 优先(修仙×规则怪谈 host=修仙、hp 取气血),外来轴带显示层换皮 override(规则怪谈 san→道心、key/axisRole/lethal 不变引擎无感);合并只在播种层(ArchetypeRegistry 合并函数 + GameInitService 派生),不动引擎/校验/`schemaVersion`(保 0.4);round 1 只产一组彩蛋,通用换皮引擎/ruleForm 融合留后续 ADR
- [ADR-013](docs/adr/ADR-013-hybrid-fusion-protocol.md) — 混合模式融合协议(内联融合 + init 双值,引擎不动):init 收有序双值(host 在前、向后兼容单值),world-gen 单次胖调用内联注入两 archetype 块 + 一段融合 meta-prompt 产融合世界(mode:hybrid、保 json_object 无哨兵,守 ADR-007 不加预调用),接活 ADR-012 休眠 mergeAxes 喂现有派生;守则真假同墙混合、三根杠杆(数值入守则/先辨体系/真假对射用修仙常识裁)、护道结局,守则不越界判定(守 ADR-011);融合只在播种层+提示词+前端,不动引擎/校验/`schemaVersion`(保 0.4),round 1 手写修仙×规则怪谈一组
- [ADR-014](docs/adr/ADR-014-fusion-skeleton-parameterization-and-second-combo.md) — 融合骨架参数化 + 第二组合「守则即补给」(rules_creepy × apocalypse):FUSION_SKELETON/FUSION_TURN_DIRECTIVE 抽 per-combo 注入槽(致命轴清单/结局条数从 fusedAxes 派生,真假称呼/资源经济/hint 示例等为文案槽),round-1 文案迁回槽位、prompt 逐字节 parity 锁死;第二组合=缺页的人防工程(真页/假页 + 物证与尸体裁决 + hunger 换皮「补给」),AxisSkin 微扩 behaviorHint override 位(修订 ADR-012 不换清单);首例三致命轴 {hp,san,hunger};引擎/校验/`schemaVersion`(保 0.4)不动
- [ADR-015](docs/adr/ADR-015-overseas-deployment-form-factor.md) — 境外部署形态(修订 ADR-002,路线 B):同源单容器(Spring Boot + web/dist 拷入 static/,一个部署单元一个域名零 CORS)+ 最小续局落盘(每 saveId 一个 JSON、Engine 纯增量恢复入口、restore 守护测试把 golden 护城河延伸到持久化边界、落盘=视图 1 全量须在 web 根之外)+ 平台选型硬约束清单先行(单副本/SSE 不缓冲/持久卷/出口可达 DeepSeek/成本/环境变量覆盖);ADR-002 因子 ②③ 失效 ①④ 仍立,命中其预登记迁出触发条件;引擎现有行为/校验/`schemaVersion`(保 0.4)零回归
- [ADR-016](docs/adr/ADR-016-cost-gate.md) — 成本闸门(软启动开闸前置硬门槛):双层闸门——全局 ¥ 双顶硬熔断(日 ¥6 / 月 ¥175,真闸)+ 单 IP/设备次数软闸(日 init 10 局 / 日回合 300,防脚本;假想敌是脚本刷不是真人玩,宽到真人永远撞不到);记账旁挂 usage 收口(消费 ADR-001 price 配置,改价改配置不改码)、拦截在入口(init 前置 429 + turn 守卫 0 走 sink.error,LLM 调用前拒绝成本≈0);日计数内存(deploy 清零=宽松方向)、月累计 /data 原子写跨重启守住;mock 豁免;引擎/校验/`schemaVersion`(保 0.4)零动
- [ADR-017](docs/adr/ADR-017-frontend-visual-charter-and-animation-libraries.md) — 前端视觉宪法与动画库许可名单(修订 ADR-003):路线 B 冻结小程序线 → ADR-003 §5「Taro 迁移最安全」的论据失效,放开动画库限制到白名单三项(GSAP 时间线 / Motion 声明式交互 / Lenis 平滑滚动),缓引 Particles·Lottie·Shader(转正须回窗口对齐)、Three.js 明确不做;同时立视觉宪法四原则(内容第一 / 动画为表达 / 每世界有自己的视觉语言 / 追求记忆点不追求炫)+ 优先级(氛围 50% > 交互 35% > 关键时刻 15%)+ 每世界视觉语言表;引库 = 回国做小程序时视觉层重做的债,显式挂钩 ①② 解冻算账;**ADR-003 接口纪律(api/ 薄适配层 + TurnStream)一字不动**,eslint 硬线只管平台 IO 与依赖正交;探索走 Cowork 样板间、落地回 CC 流程,引擎/校验/`schemaVersion`(保 0.4)零动
- [ADR-018](docs/adr/ADR-018-base-world-visual-migration-and-severity-contract.md) — 基础世界视觉移植:severity 语义契约 + 单一主题注册表 + 四刀切分。立**语义产出方原则**第三次实例化(ADR-010「AI 标 outcome、引擎只读」/ ADR-011「AI 写 hint、引擎不裁决」→ 本次「服务端派生 severity、前端只渲染」):`bands[]` 增 `severity`(neutral/caution/danger)由服务端据 axisRole/lethal/新增 `perilAtHigh` 派生,前端只做区间匹配与渲染、不判危险;`perilAtHigh` 是第四个纯展示层轴元数据(引擎绝不读,区分禁忌知识双刃 vs 境界纯成长——两者同为 accumulation 却相反);派生先按 min 排序再标边缘档(registry 存储顺序是降序,下标不可靠)、融合 AxisSkin 不换该标故 per-combo 零登记;前端四种缺省一律安全降级(绝不默认 danger、绝不回退旧启发式);四刀切分(0 severity / 1 共享基建+规则怪谈试验田 / 2 修仙 / 3 末日 / 4 克苏鲁)各写清「不准顺手做什么」,刀 1 立单一主题注册表 + 通用层主题层分工 + `--t-dur`/`--t-ease` 成对 + 同一 teardown + feature gate;七问裁定入档(融合游戏内视觉挂 ADR-019 / 形近字人工表 / 克苏鲁异常全程极低频回改 ADR-017 §6.3 / 钟鸣挂 realm 向上跨档 / debug 仅 ?debug=1 / 测试守行为不守像素);引擎/校验/`schemaVersion`(保 0.4)零动

## 文档

- [docs/ROADMAP.md](docs/ROADMAP.md) — 开发计划总览(中央档案)
- [docs/adr/](docs/adr/) — 技术决策记录
