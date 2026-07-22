# ADR-016 · 成本闸门:全局 ¥ 双顶硬熔断 + 单 IP/设备次数软闸(软启动开闸前置硬门槛)

- **日期**:2026-07-22
- **状态**:已采纳
- **决策者**:Felix

## 背景

上线阶段四腿(见 [phase3-launch-plan.md](../phase3-launch-plan.md))中 ③ 境外部署已收口(ADR-015 三刀全落地,万界上线公网),④ 最小可上线形态只剩最后一块工程 = **成本闸门**。路线 B 下境外托管**无实名墙、无微信登录**,任何人拿到链接即可无限起局刷回合,被脚本刷的风险显著高于境内小程序形态——成本闸门因此是**软启动放量前的硬门槛**(launch-plan §七)。

**真实读数已就位(观测两批的回报)**:usage 观测(`fc1491d`/`73e7aea`)+ 缓存命中两字段(`46a22b8`)上线后,线上真实数据回收:

- 同局连续回合缓存命中率稳定 **52%** 上下(cacheHit 阶梯增长 2048→2432→3584,动态 contextJson 段 ~2100–2300 tok 恒 miss);
- **world-gen 跨局命中 0**(勘察期「跨局共享骨架可命中」的乐观假设被线上证伪,起局按全价算);
- 精化换算:**单回合 ≈¥0.0035,融合局(50 回合)≈¥0.18,¥175/月 ≈ 5 万回合**。

**阈值四数(Felix 拍板,基于上述真实数据)**:

| 闸 | 阈值 | 推导 |
|----|------|------|
| 全局日限额(真闸) | **¥6/日** | ¥175/月 ÷ 30 ≈ ¥5.8,取整;≈1700 回合/日,数十真人玩家量级绰绰有余 |
| 全局月顶(真闸) | **¥175/月** | ¥200/月 总预算 − 托管固定成本(Fly ≈US$3.4/月)留余量 |
| 单 IP/设备日 init(软闸) | **10 局/日** | 真人一天开 10 个新世界已属重度;脚本起局最贵(world-gen 全价 ~¥0.01/发) |
| 单 IP/设备日回合(软闸) | **300 回合/日** | 融合长局 50 回合 × 6 局,真人极限之上仍有余量 |

日界线 = **北京时间(UTC+8)**(目标用户在国内,与玩家体感的「明天再来」一致;服务器在 syd,不用机器本地时区)。

**阈值哲学(立字)**:「**假想敌是脚本刷,不是真人玩——阈值宽到真人永远撞不到**」。软闸存在的意义不是精确配额,而是让脚本刷在烧钱之前先撞墙;真闸(¥)才是预算的最后防线,它触发时说明预算真的见顶,拦下是正确行为而非误伤。

约束条件:

1. **守卫必须在 LLM 调用之前**:被刷时单次拒绝的成本必须 ≈0(不产生任何 token 消费),否则闸门本身被刷穿。
2. **引擎/校验/`schemaVersion`(保 "0.4")/golden parity/prompt lockstep 零动**:闸门是入口层横切件,不碰游戏内核。
3. **价格变动改配置不改码**:¥ 记账消费 `application.yml` 现成 `price` 配置块(ADR-001 provider 配置表),DeepSeek 调价只改 yml。
4. **月累计跨重启守住**:月顶是真闸,不能靠 redeploy 清零绕过;日计数允许重启清零(宽松方向,见已知代价)。
5. **mock 路径豁免**:两阶段冒烟(mock 先行不花钱)与本地开发不被闸门干扰。

## 候选方案

### 方案 A:只做次数限流(不记 ¥)

单 IP/设备 init + 回合次数日限,不做 ¥ 记账。

**优点**:
- 实现最薄,零持久化。

**缺点**:
- 防不住分布式刷(多 IP 轮换直接绕过),预算无最后防线;
- usage 观测两批已经把 ¥ 读数送到手边,不用等于浪费已建成的地基。

**排除**:次数限只能防君子,¥ 才是预算的真理之源;两层缺一不可。

### 方案 B:接入平台级/网关级限流(Fly proxy 限流、外置 Redis 计数)

**优点**:
- 平台承担计数,应用零状态。

**缺点**:
- Fly proxy 无按预算(¥)熔断的概念,只能按请求数——而本项目单请求成本差异极大(init 全价 vs 回合 52% 命中);
- 引 Redis 违背 ADR-015「零依赖、单容器」形态,为软启动量级引入纯负担;
- 限流规则活在平台配置,脱离仓库版本化(与 fly.toml 全显式落仓的纪律相悖)。

**排除**:与 ADR-015 已立的形态与量级判断冲突。

### 方案 C:应用内双层闸门——全局 ¥ 双顶(真闸)+ 单 IP/设备次数(软闸)(本 ADR 采纳)

记账在 usage 收口处旁挂累加器,拦截在入口(init 前置 + turn 守卫 0);日计数内存、月累计落盘。

**优点**:
- ¥ 真闸给预算上了确定性保险(全局日 ¥6 + 月顶 ¥175,谁也刷不穿);
- 软闸让单源脚本在花掉几分钱之前就撞墙(守卫在 LLM 调用前,拒绝成本 ≈0);
- 零新依赖,复用已有地基:usage 观测(读数)、price 配置(单价)、FileSessionStore 原子写模式(月累计落盘)、`/data` 卷(跨重启);
- 全部阈值进 yml 可 env 覆盖,冒烟时压低阈值验触发路径不改码。

**缺点**:
- 日计数内存态 deploy 清零(接受:宽松方向,见已知代价 1);
- CGNAT 下多真人共享出口 IP 可能被软闸误伤(接受+缓解:见已知代价 2)。

## 最终决策

**方案 C — 应用内双层闸门**。真闸管钱,软闸防脚本;记账与拦截分层,各在最顺手的接缝上。

### 1. 分层架构:记账在 usage 收口,拦截在入口

**记账(旁挂累加器,纯观测变有账本)**:usage 观测两批已把 `LlmUsage`(prompt/completion/cacheHit/cacheMiss)送到两个收口点——`EventLoopService.logUsage`(主调用+修复)与 `WorldGenService.call`。¥ 记账就旁挂在这两处:流结束后拿 `UsageCapture.usage()`,非 null 则按 price 三段计价累加。**mock 天然免疫**(mock 无 usage 块,`usage()` 返 null,零特判)。

计价公式(CNY,price 单位 = CNY/1M tokens):

```
hit  = max(cacheHitTokens, 0)
miss = cacheMissTokens ≥ 0 ? cacheMissTokens : max(promptTokens − hit, 0)   // 方言缺字段时保守回退
out  = max(completionTokens, 0)
cost = (hit×inputCacheHit + miss×inputCacheMiss + out×output) / 1_000_000
```

缺失字段(-1 口径,usage 观测批立的规矩)按 0 或保守回退处理,**绝不因缺字段抛错**——记账是增值服务,账目偏差方向是「少记」,由宽阈值余量消化。

**拦截(入口,LLM 调用前,拒绝成本 ≈0)**:

- **init**:`GameController.init` 在调 `initService.init` **之前**问闸门(全局 ¥ 双顶 + 单键 init 次数);拒绝 → **429** + 结构化 `{error:{code:"quota_exceeded",message}}`,world-gen 零调用。
- **turn**:`TurnStateMachine.submitAction` 新增**守卫 0(配额)**,插在**合法性守卫之前**(现有两道守卫升格为 1/2);拒绝走既有 `sink.error("quota_exceeded", …)` 通道,executor 零调用、**相位不动**(守卫 0 在 CAS 之前,会话停留 AWAITING_ACTION,次日额度恢复即可续)。

接缝形态照抄 `SessionStore.NOOP` 先例:`QuotaGate` 接口(`checkInit`/`checkTurn`/`record`)+ `NOOP` 常量(全放行、不记账),`EventLoopService`/`WorldGenService`/`TurnStateMachine` 旧构造委托 NOOP(现有测试调用点零改),`@Autowired` 构造收真实现 `QuotaService`。

### 2. 软闸键:IP + deviceId 双键,谁先撞谁拦

- **IP**:读 **`Fly-Client-IP` 头**(经 Fly 反代;勘察已证 `getRemoteAddr` 只见内网地址)。头缺失(本地开发/直连)回退 `getRemoteAddr`。
- **deviceId**:前端 localStorage 生成一次的 UUID(独立于 saveId),随 init/turn 请求头 `X-Device-Id` 带上;服务端缺头则该键不计(不拒)。
- 四路计数:{init, turn} × {ip, device},**任一键任一路先到阈值即拦**。计数在守卫放行时 +1(拒绝不计)。

### 3. 持久化与重启语义

- **日计数(软闸)+ 当日 ¥**:内存 `ConcurrentHashMap`,key 含北京日期(`Asia/Shanghai`),跨日自然换 key = 自动重置,旧日条目惰性清理。**deploy 清零 = 接受**(宽松方向:重启只会多放行、不会误拦;真闸月累计兜底)。
- **月累计 ¥(真闸)**:落盘 `quota-YYYY-MM.json`(与会话同目录 = `aiuniverse.session.store-dir`,线上即 `/data` 卷,跨 deploy 保留已实证),**照抄 FileSessionStore 原子写**(tmp + ATOMIC_MOVE,失败回退普通 move);每次记账后 best-effort 写盘(写失败不抛、账继续活在内存——同 `persist` 口径)。启动/跨月时按当月文件名回载,月切换自然滚动新文件。

### 4. 配置:四阈值进 yml,可 env 覆盖

```yaml
aiuniverse:
  quota:
    daily-budget-cny: 6        # 全局日限额(真闸)
    monthly-budget-cny: 175    # 全局月顶(真闸)
    init-per-key-daily: 10     # 单 IP/设备日 init 局数(软闸)
    turns-per-key-daily: 300   # 单 IP/设备日回合数(软闸)
```

env 覆盖走 Spring relaxed binding(`AIUNIVERSE_QUOTA_DAILY_BUDGET_CNY` 等),与 ADR-015 `AIUNIVERSE_SESSION_STORE_DIR` 同一口径。冒烟压低阈值 = 临时 env,验完撤掉即恢复正式阈值,零代码改动。

**价格配置消费(立字)**:¥ 记账单价**只读** `aiuniverse.llm.providers.<active>.price` 三段(ADR-001 配置表现成字段,此前仅文档性),**跟官方价走,改配置不改码**。DeepSeek V4-Flash 计价(input cache-miss ¥1.0 / cache-hit ¥0.02 / output ¥2.0,CNY/1M)查证日期 **2026-07-20**(④ 成本闸门勘察批,官方计价页;命中/未命中价差 50 倍)。

### 5. 拒绝语义(玩家可见面)

- **init 被拦**:HTTP **429** + `{error:{code:"quota_exceeded",message:"今日体验名额已满,明天再来"}}`(文案实现时润色);前端归一为 `GameApiError` 走现有 initError 分支(「重新生成」屏样式复用,文案换配额提示)。
- **turn 被拦**:SSE `event:error` + `code:"quota_exceeded"`;会话**停留 AWAITING_ACTION**(守卫 0 在 CAS 前,相位零触碰),前端回 awaiting + notice(复用可恢复错误分支)——**次日额度恢复,同一局可无缝续**(落盘续局 ADR-015 已保证)。
- **mock 豁免**:`aiuniverse.llm.active=mock` 时软闸次数计数显式豁免(¥ 记账已天然免疫);两阶段冒烟 mock 段与本地开发不受闸门影响。

### 关键理由

1. **守卫在 LLM 调用前 = 被刷时单次拒绝成本 ≈0**(约束 1):init 拦在 world-gen 之前、turn 拦在守卫链最前,脚本刷到的只是 429/error 事件,一个 token 都不烧。
2. **复用四块已建成地基,零新依赖**:usage 观测(读数)、price 配置表(单价)、FileSessionStore 原子写(落盘)、NOOP 接缝模式(测试零改)——闸门本身没有发明任何新机制。
3. **「AI 提议、引擎裁决」哲学的入口层版本**:模型/玩家怎么玩不管,钱和次数由确定性代码硬把关(同 ADR-010 引擎不信 AI 自觉的思路:不靠「希望没人刷」,靠闸门)。
4. **两层分工清晰**:软闸误伤有真闸兜底、真闸触发有软闸减速——单层方案(A/B)都做不到「预算确定性 + 脚本早撞墙」同时成立。

## 已知代价

1. **日计数 deploy 清零**:重启后当日软闸计数与当日 ¥ 归零,当天已刷过的源可再来一轮。接受:方向宽松(只多放行不误拦),且月累计落盘守住真正的预算底线;软启动期 deploy 频率低,窗口极小。
2. **CGNAT/校园网误伤**:多真人共享出口 IP 时,软闸 300 回合/日可能被合计撞到。缓解:阈值本就按「真人永远撞不到」的单人极限 × 余量定;deviceId 第二键让同 IP 不同设备各有独立额度(次数闸按键取宽);极端情况撞到真闸 = 预算见顶,拦下是**正确行为**而非误伤。
3. **deviceId 形同虚设(防君子)**:localStorage 一清即新身份,脚本可无限伪造。接受+立字:deviceId 只用于缓解 CGNAT 误伤与拦最低级脚本,**对抗性防刷不做**——真正的墙是全局 ¥ 双顶,伪造再多身份也刷不穿总预算。
4. **排队/降级不做**:闸门触发即拒绝,不做「排队等额度」「降级到更便宜模型」。接受:软启动量级不值得这套复杂度;拒绝文案已给玩家明确预期(明天再来)。
5. **¥ 记账是估算不是对账**:按 usage 块 × 配置单价累加,与 DeepSeek 账单可能有小偏差(方言字段缺失时保守回退、开局失败重试等)。接受:闸门要的是量级正确,不是财务级精确;阈值留了余量。

## 重新审视的触发条件

- 软启动真实数据显示真人玩家撞到软闸(哪怕一例)→ 阈值哲学被证伪,重估四数;
- DeepSeek 调价 / 换 provider(ADR-001 配置表换 active)→ 只改 yml price 块,若计价结构变了(如按请求计费)才回本 ADR;
- 引入账号体系(Phase 4 微信生态解冻)→ 软闸键从 IP/deviceId 升级为账号,本 ADR 软闸层重写;
- 多副本部署(ADR-015 单副本约束解除)→ 内存计数失效,需外置计数存储,连带重估方案 B;
- 出现「排队等额度」的真实用户诉求且量级支撑 → 解冻已知代价 4。

## 实施步骤

1. ✅ ADR-016 落档 + README ADR 列表 + ROADMAP §五索引(本批第 1 刀,纯 docs)。
2. ✅ 后端:`quota` 包(`QuotaGate` 接口 + NOOP / `QuotaService` 实现 / `QuotaProperties`)、¥ 记账旁挂两收口点、init 前置 + turn 守卫 0 接线、`Fly-Client-IP`/`X-Device-Id` 读取、月累计落盘/回载;TDD:记账精度 / 四路阈值 / 北京时间跨日重置 / 月落盘原子写与回载 / mock 豁免 / 守卫 0 顺序(合法性之前、相位零触碰)。
3. ✅ 前端:deviceId(localStorage,`api/` 层)随 init/turn 请求头;429/`quota_exceeded` 分支渲染(复用现有 error 样式);测试补 headers 与分支。
4. ✅ 验证:`mvn test` 全绿 + 前端全绿 + golden/`schemaVersion`(保 "0.4")/prompt lockstep 零动;真机冒烟 = 临时 env 压低阈值(如 `AIUNIVERSE_QUOTA_INIT_PER_KEY_DAILY=2`)验四条触发路径,验完撤 env 恢复正式阈值。

## 实际效果(事后补充)

*软启动放量后回填:(a) 四路计数与 ¥ 账本的真实读数(日/月曲线);(b) 是否出现真人撞软闸(阈值哲学的实证);(c) 月累计与 DeepSeek 官方账单的偏差量级(记账估算精度);(d) 429/quota_exceeded 的触发频次与来源分布(脚本刷是否真的来了)。*

## 跟其他文档的交叉引用

- **起源**:[phase3-launch-plan.md](../phase3-launch-plan.md) §七 ④ 最小可上线形态(成本闸门 = 软启动硬门槛;¥200/月 → ¥175 LLM 预算)
- **读数地基**:usage 观测两批(`fc1491d`/`73e7aea` 拆信 + `46a22b8` 缓存两字段)——`LlmUsage`/`UsageCapture`,-1 容错口径沿用
- **单价来源**:[ADR-001](ADR-001-runtime-model-and-provider-abstraction.md)(provider 配置表 `price` 块,本 ADR 首次消费)
- **形态约束**:[ADR-015](ADR-015-overseas-deployment-form-factor.md)(单副本 → 内存计数成立;`/data` 卷 → 月累计跨 deploy;env 覆盖口径;`SessionStore.NOOP` 接缝先例)
- **哲学同源**:[ADR-010](ADR-010-ending-outcome-polarity-gate.md)(不靠 AI/玩家自觉,确定性代码硬把关)
- **相关源文件**:`server/.../quota/`(本批新增)、`EventLoopService.logUsage`、`WorldGenService.call`、`TurnStateMachine.submitAction`、`GameController.init`、`web/src/api/`(deviceId + headers)
