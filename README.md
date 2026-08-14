# AI Universe Simulator

> 基于大语言模型的生成式、可交互、无限流文字模拟游戏平台

## 项目简介

打破固定文本边界的文字模拟游戏。玩家作为绝对变量,实时介入一个由 AI 动态编织、逻辑自洽的**涌现式世界(Emergent World)**——不是在读故事,而是在改写故事线。

**已上公网,可直接玩**:<https://wanjie-ai.fly.dev>

完整规划与逐周进展见 [docs/ROADMAP.md](docs/ROADMAP.md)(中央档案,本文只给概览)。

## 核心特性

- **🌟 概念融合 · 混合模式(杀手锏)**:世界观杂交成**一个自洽世界**(非轮流播/拼接)。已落地两组——**识海遗蜕**(修仙 × 规则怪谈)、**缺页的人防工程**(规则怪谈 × 末日生存)。
- **通用生成引擎(UG Engine)**:世界生成 → 角色/属性 → 规则矩阵 → 动态事件流 → 多结局收敛,所有世界共用一条管线;**加一个世界 ≈ 加一条元数据 + 一个提示词注入块**。
- **结构化驱动**:AI 在叙事之外输出结构化 JSON,驱动数值系统;**引擎对数值语义无知**(ADR-008),故各世界数值轴可完全不同——气血/灵力/境界、体力/理智、体力/饥饿、禁忌知识……
- **已实现世界(4 基础 + 2 融合)**:规则怪谈 / 末日生存 / 克苏鲁 / 修仙;人生模拟与赛博朋克为枚举占位,尚未开放。

## 当前进度

- **阶段**:Phase 3(混合模式 + 上线)收尾;**当前主线 = 前端视觉移植**(ADR-018 四刀:刀 0 severity 契约 ✅ / 刀 1 共享基建 + 规则怪谈 ✅ / 刀 2 修仙 ✅ / 刀 3 末日、刀 4 克苏鲁待起)。
- **上线路线**:走**路线 B —— 境外托管、不备案、暂不进微信生态**,先做软启动验证;境内合规(执照 / ICP 备案 / 微信支付)整体冻结,待回国或验证通过后解冻(见 ADR-015)。
- **待收口**:内容安全网关(ADR-004)—— 软启动开闸前的最后一环。

## 技术栈

- **前端**:React + Vite(移动优先 H5)+ GSAP;**小程序 / Taro 线随路线 B 冻结**,接口纪律仍占住迁移边界(见 ADR-003 / ADR-017)
- **运行模型**:DeepSeek 为主,provider 可换(OpenAI 兼容配置表抽象)— 见 ADR-001
- **后端**:Spring Boot(Java 21)— 见 ADR-005
- **部署**:Fly.io(syd)**同源单容器** + 持久卷续局落盘 — 见 ADR-015
- **成本闸门**:全局 ¥ 双顶熔断 + 单 IP/设备日次数软闸 — 见 ADR-016
- **内容安全**:文本审核网关 **待落地**(ADR-004 未启)
- **数据**:统一 JSON Schema(世界 / 角色 / 规则 / 状态 / 行动 / 结局)— 见 [docs/CONTEXT.md](docs/CONTEXT.md)

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
- [ADR-019](docs/adr/ADR-019-fusion-visual-entry-and-composability.md) — 融合视觉:**入口互噬、局内单主语**,与「四套材质覆盖六对组合」的可组合机制。两个场景任务不同故正确解不同——入口停留约 1 秒、任务是宣告「两个世界撞在一起」故允许强视觉;局内是 50+ 回合持续阅读故 host 打底、foreign 只低频渗漏,**入口的强视觉不得带进游戏内**;否决 per-combo 特供 UI(资产随世界数 N 增长、覆盖组合 O(N²),守 F-016 成本模型):入口侧 = 四套碎解材质 + **四套独立物理**(被裁切 / 崩落 / 被拖走 / 化散,**不共用运动曲线**——玩家看到的主要是过程,形态只是结果),局内侧 = 安全渗漏签名六条约束(与记忆点同源但不是缩小播放 / 只作用通用表面不得要求 host 提供特定组件 / 与 host 低频事件共享一槽且代码保证互斥 / 不挂业务轴 / 默认统一权重偏离须记账 / 缺失即纯 host 呈现不报错);五拍 1000ms(挤压 200 / 碎解 180 / 揉合 260 / **停顿 140 不可省** / 展开 220);host 真相源 = `world.archetypes[0]`,方向语义「被拖者 foreign、承接者 host」直接生成有序双值不新增字段;**后端唯一改动 = `GET /api/archetypes` 加只读 `fusions` 投影**(与 severity 契约同族:语义产出方在掌握语义的那一层、消费方无知;引擎/校验/`schemaVersion`(保 0.4)零动),因勘察实证「接 registry」与「后端零动」互斥、双真相源今天已存在且后果具体(玩家能拖出后端 400 的组合);ADR-013 误入手势退役并记明理由(180ms 抓起是 600ms 长按的真子集,并存只能靠让长按永不触发),渗漏卡视觉资产保留复用为结果卡 —— **复用的是形式,变化的是解释**
- [ADR-020](docs/adr/ADR-020-lifetime-world-family-and-ordinary-life.md) — 一生制世界族与首个实例《寻常》(非恐怖线首刀):把**时间尺度**从 per-combo(CONTEXT §三.16 (7))**扩到 per-archetype**,并立「一生制」为第一个具名族(一局覆盖完整生命周期、回合密度非匀速;动物人生 / 校园青春 / 穿越古代同族)——**族级复用的是原则(幼年压缩 / 选择密集期一年一回合 / 中段加速 / 末段变密),切分表 per-world 不得跨世界复用**(硬套人的段式会得到「毛茸茸的人」);「归零不死」两条轴(热望 / 路口)标 **`resource`(depletion + `lethal=false`)取得引擎硬保证**——勘察证伪 brief「引擎只有二分、第三种没做」的前提(该工厂今天就在、修仙 `mana` 早已是它、F-015 已由 ADR-010 关闭),故**软自律职责收窄到只管选项退化**,并写成**可数判据**(热望归零后连续 5 回合 × 四个选项不得引入新的人 / 地点 / 时间约定 —— 态度不可验、可数才可验,同 F-017 兑现语义教训);逐字锁热望与路口的语义差(「你还想不想选择」vs「人生还给不给你大的选择」,并**就地订正**创意稿「选项变成处理事务」的错误措辞);**留白清单写成 prompt 硬禁令**(生成模型天然倾向补全,同 ADR-018 §5 Q3「不能指望模型自己克制」);**「承诺可不兑现」作用域切分**(凡承诺数值变化的必须兑现,只有「人生里的没做成」可落空,**不得外溢到有资源经济的世界**,不改 F-017);**早逝三段式**(种子倾向 → 累积调制 → 单次选择不决定,否决「完全由开局种子决定」);三项裁定 = 第一版不做皮肤走默认降级路径(ADR-017 §5 视觉语言表**不补行**,同赛博朋克「上架时才定」政策)/ 体验分类只进文档不加字段 / **key 复用 `life_sim`** 激活既有灰显占位(对外名「人生模拟」、世界名「寻常」,**CONTEXT §三.4 与 schema.ts 与 world-catalog 全部零改**);唯一结构性新增 = `TurnPromptBuilder` 的 **per-archetype 可选指令槽**(仿 ADR-014 `extraDirectives`,缺省空串 → 四世界回合 prompt 逐字节零回归,**不准与融合槽合并**);四刀切分各写清「不准顺手做什么」,引擎/校验/golden/prompt lockstep/`schemaVersion`(保 0.4)零动
- [ADR-021](docs/adr/ADR-021-lifetime-family-layer-and-animal-life.md) — 一生制**族层**抽取(补上「族」这一层)与第二个实例《动物人生》:兑现 ADR-020 自己挂的触发条件「第二个一生制世界上架时检验族级约定是否真的可复用」,而**诚实的答卷是「原则可复用,但代码里没有地方放」** —— 故**就地订正 ADR-020 §1**(族级可复用的是原则,**且原则必须有独立的存放层**;没有那一层,原则会退化成每个世界各抄一遍;⚠️ **立字二本身没写错,它缺的是落地形态**,被证伪的是「有原则就够了」这个隐含前提)。三条证据:三条族级原则(不显示岁数 / 重要的事不给专门回合 / 最后一回合是活着的)**全在 `life_sim` 槽内、骨架零承载**;「最后一回合是活着的」**已在两个槽里逐字各写一遍**,第三份是 `EventLoopService` 的 `VIGOR_KEY = "vigor"` —— **一个世界、三份拷贝**。决策 = 两个 builder 各开一层 family directive、拼接顺序 **骨架 → 族 → 世界**,判据**可数**:**一条指令若对族内每个世界都成立属族层,只对一个世界成立属世界层**(内容型判据会退化成各人各判,同 ADR-020 §10「读几次」);族层缺省空串 → 四世界零回归是**构造保证**,`%s` 挂末行行尾不得独占一行。**`LifeStage` 参数化**:enum 的 `values()` 是**类级单例**——**这是它在语言层面就做不成 per-archetype 的原因,不是风格问题**;同批解 `VIGOR_KEY` 与 `LIFETIME_EXIT_ARCHETYPES` 两条硬编码(**动物的致命轴不叫 vigor,该兜底对它静默不生效**——编译过、测试绿、prompt 里出现动物在「31–55 岁」)。**「身子」的不可逆降级为措辞约束并挂账**(引擎里不存在「上限」概念:`AttributeAxis.max` 四个读者全是描述/投影,真正落账的是 `Engine` 里写死的 `Math.min(100,x)`;要它须引擎/校验/schema/落盘四处开位置 + **golden 重录**;走软约束亦不成立,因**上限值不在 `snapshot()` 里**、模型看不见它,撞 F-025)→ 立字:**一个世界要的机制不开引擎,两个世界要的才开**。复用率读数逐项记实测(✅ 两个指令槽机制 / `exitAlreadyPressed`;❌ `LifeStage` 人类专有 / 三条原则槽内独占 / 出口措辞结构耦合),并订正两处:**加世界的代价是「后端一条 + 前端一行」**(`schema.ts` 联合类型必须加 —— 《寻常》刀 1 的前端零改是复用灰显占位的收益,不是常态)、**槽的填充管道被焊死在《寻常》的时钟上**(`LifeStage.of` 对任何注册了模板的 archetype 无条件执行);三刀顺序不可换(1 补族层纯重构·**可独立验证** / 2 `LifeStage` 参数化 / 3 登记动物人生),`schemaVersion`(保 0.4)/ 引擎 / 校验 / golden 零动

## 文档

- [docs/ROADMAP.md](docs/ROADMAP.md) — 开发计划总览(中央档案:阶段、周度日志、ADR 索引)
- [docs/CONTEXT.md](docs/CONTEXT.md) — 术语 / 统一 JSON Schema / 跨模块约定(约定的真理之源)
- [docs/adr/](docs/adr/) — 技术决策记录
- [docs/world-catalog-and-experience-modes.md](docs/world-catalog-and-experience-modes.md) — 世界目录与体验模式(浮世 / 深界:分类归属与大厅立字;成本分级另见世界库 backlog)
- [docs/world-ordinary-life-writing-standards.md](docs/world-ordinary-life-writing-standards.md) — 《寻常》写作标准(措辞铁律六条 / 三处留白 / 回收铁律)= 该世界写作标准的**真理源**,逐字注入 `TurnPromptBuilder` 指令槽;**两处不得漂移:先改本文件再同步 prompt**
- [AGENTS.md](AGENTS.md) — agent 常驻约束(工作流纪律 + 前端动效约束)
- [bakeoff/FINDINGS.md](bakeoff/FINDINGS.md) — 验证过程中的实测发现
