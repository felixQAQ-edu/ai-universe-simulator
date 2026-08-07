# 工程债清单 · 外部代码审查结论分层

> 建档:2026-08-06,据一次针对 `main@00b20af` 的**外部代码审查**结论整理。
>
> **为什么另起一份文件、不并进现有 backlog**:仓库里已有的三份 backlog
> ([打磨与愿景](phase2-polish-and-vision-backlog.md) / [future-experience](future-experience-backlog.md) /
> [世界库](world-library-expansion-backlog.md))收的都是**玩家看得见的东西**——玩法、体验、视觉、世界。
> 本文件收的是**玩家看不见但会要命的东西**:能被刷爆的端点、没有上限的线程池、不存在的 CI、
> 以及「这个项目在 Java 后端面试里被怎么读」。两类东西的**开刀条件与判断人都不同**
> (那边看体感,这边看故障与简历),混在一处只会让其中一类永远排不上号。
> 唯一的例外是第 3 层的数据库那一片 —— 它与 [future-experience §2.3](future-experience-backlog.md)
> 是**同一片区域的两个视角**,已双向交叉标注。

**分层口径**:第 0 层是**漏洞**(不挂账,当场修);第 1 层是**会宕机**;
第 2 层是**便宜且顺带有面试价值**;第 3 层是**规模大、需自己的 ADR**。
层内不排序,层间才是优先级。

---

## 第 0 层 · 已处置(2026-08-06,本轮就做,不进清单)

### ✅ dev echo-stream 端点在生产可访问,绕过成本闸门直连真实模型

**这是本次审查里唯一的真漏洞。** ADR-016 的双层成本闸门装在 `init` 与 `turn`
两个入口上,而 `POST /api/dev/echo-stream`(骨架期 SSE 冒烟端点,[ADR-005](adr/ADR-005-sse-web-stack-mvc-thin-seam.md)
§实施 3 留下)收**任意 prompt** 直接调 `LlmClient.streamChat`,**全程不过 `QuotaGate`、也不记账**:

- 日 ¥6 / 月 ¥175 的真闸在这条路径上**形同虚设**——它连自己被花了多少钱都不知道;
- 单 IP / 设备的软闸同样不适用,**无任何速率限制**;
- 它甚至比正常路径更好刷:没有 world-gen 的 ~120s 阻塞,一个 curl 循环就能持续出账。

**处置 = 删除,而非按 profile 禁用**。它当初要证明的「SSE 通路成立」,早已由真实回合端点
`POST /api/game/{saveId}/turn` 在线上承担([ADR-015](adr/ADR-015-overseas-deployment-form-factor.md)
附录 B 冒烟 ② 的逐字流实证就是它跑出来的);留着它、再加一道 profile 开关,
只是**多一个必须记得关的门**——而这次事故的形状恰恰就是「有人忘了关一扇门」。
ADR-005 的承重接缝(`TokenStream` → `SseEmitter`)由 `GameController` 原样承载,换 WebFlux 仍只动 web 层。

- **commit**:`a02148f`(单独 atomic)。server 314 绿不变(**无一个测试引用过该端点**——它从来只被 curl 用过,
  这也是它能悄悄活到线上的原因)。
- **文档**:[ADR-005](adr/ADR-005-sse-web-stack-mvc-thin-seam.md) 作为历史记录**不回改**
  (循「旧 ADR 不改写、由新决策修订」惯例),退役事实记在 `server/README.md` 与本节。
- **同族**:与 runbook [§3.1.5 镜像换新](phase3-fly-deploy-runbook.md) / §3.1.6 secret 完整性
  同属**「入口不止你以为的那一个」**——那两条是「环境不是你以为的那个」,这条是「路径不是你以为的那一条」。

---

## 第 1 层 · 可用性风险(挂账,优先级高)

### 1.1 SSE 线程池 `newCachedThreadPool()` 无上限

**现状**(2026-08-06 复核,与外部审查读数有一处出入,如实记):
审查说的是「**两个** Controller」,其中一个正是上面刚删掉的 `StreamController`;
**现在只剩 `GameController.turnExecutor` 一处**(`web/GameController.java:47`)。
删端点顺带砍掉了一半的暴露面,但剩下这一处的性质没变。

**为什么危险**:`newCachedThreadPool()` 的池上限是 `Integer.MAX_VALUE`,
而 SSE 回合是**阻塞长连接**——每个在途回合占住一个线程直到模型吐完(线上实测 world-gen ~120s、回合 ~15s)。
两个放大器叠在一起:

1. **模型变慢时自动放大**:延迟翻倍 = 同时在途的连接数翻倍 = 线程数翻倍,**没有任何东西会说不**;
2. **恶意请求直接放大**:ADR-016 的软闸是**日次数**闸(init 10 / 回合 300),
   **不是并发闸**——一个脚本在一秒内打满它当天的额度,配额账面上完全合规,而线程池已经炸了。
   512MB 的 Fly 单机上,这条路通向 `OutOfMemoryError: unable to create native thread` 而非优雅降级。

**方向**(不预拍参数):有界线程池 + 有界队列 + 显式拒绝策略(拒绝时返回结构化 503/429
而不是让异常裸奔到 emitter)+ 池状态指标(活跃线程 / 队列深度 / 拒绝计数)。
**注意这不只是换一行 `Executors.*`**:拒绝之后「玩家看到什么」是产品决策
(排队?直接失败?),而 ADR-016 已经立字「排队不做」——需要一并对齐。

### 1.2 内容审核仍是 no-op,泄露检测抓不到改写式泄露

**这一条与 ② [ADR-004 内容安全](phase3-launch-plan.md) 是同一条,不是两条**——
交叉标注,开工时一并做,不要各修一半。

- `NoopModerationGateway.review()` **原样返回入参**([ADR-004](phase3-launch-plan.md#九) 未定方案前的占位),
  接缝装配是真的、被调用点依赖也是真的,**但没有任何东西被审**。
- `LeakDetector` 按其 javadoc 自陈是**事后遥测而非实时拦截**:只抓两类**逐字**泄露
  (引擎字段名 `isTrue`/`hiddenLogic`/…,或整段照抄 `hiddenLogic` ≥8 字符子串),
  **改写式泄露一律抓不到**(模型用自己的话把「这条规则是假的」讲出来,检测器完全沉默)。
  这不是缺陷、是**已知设计**(CONTEXT §三.9 立过字:实时防护靠结构层消毒 + 提示词硬禁),
  但它意味着**「泄露检测」这四个字不能被当成一道防线来依赖**。

**为什么仍列在第 1 层而不是「已知即可」**:软启动开闸前这是最后一环
(见 [phase3-launch-plan.md §四腿②](phase3-launch-plan.md)),而路线 B 下它的理由已从
「境内合规硬要求」改为**防模型输出伤玩家 / 防滥用刷成本 / 为未来补合规预留可插拔接缝**。

---

## 第 2 层 · 工程卫生 —— ✅ 三条已全部落地(2026-08-06,`chore/ci-and-buildinfo`)

> **为什么这一层被提到第 1 层前面做**(Felix 裁定):它改变的不是某一个功能,
> 而是**以后每一次工作的安全边界** —— 有了 CI,测试红的 commit 推不上线;
> 有了 commit SHA,部署漂移一眼可见。第 0 层那扇门、`-DskipTests` 那半边、
> 部署陈旧那两次,**都是同一种依赖:靠人记得**。这一刀就是把两处「靠记得」换成「靠机制」。
> 第 1 层(线程池 / 内容审核)仍挂账。

### 2.1 ✅ 无 CI —— 已建(2026-08-06,`9b5be1c`)

**原症状(本层里唯一带「当时就在流血」性质的一条)**:仓库**没有 `.github/`**,
而 `Dockerfile:21` 是 `mvn -q -B package -DskipTests` ——

> **`main` 上一个把 314 个测试全跑红的 commit,可以被 `fly deploy` 一路推上线,全程无人喊停。**

唯一在挡的是「Felix 每次都记得先在本地跑测试」——这跟第 0 层那扇门是同一种依赖(**靠人记得**)。
`-DskipTests` 本身在多阶段构建里是**对的**(构建镜像不该重跑测试,那是 CI 的活),
错的是**CI 那一半从来没建**,于是「跳过」变成了「没人跑过」。

**已落地**(`.github/workflows/ci.yml`,三个 job):server `mvnw test` 314 /
web `lint` + `tsc -b` + `test` 350 + `build` / deps `npm audit`。触发 = push `main` + PR。
**golden parity 与 prompt lockstep 这两条最重的护城河从此在 CI 里跑**(此前只在本地)。
**刻意不做**:自动部署(`fly deploy` 仍 Felix 亲手)、镜像构建上传(暂无 registry 需求)。
`Dockerfile` 的 `-DskipTests` **保留不动**——它本身是对的,补的是缺失的那一半。

**审计门槛的取舍**(不设一个天天红、没人处理的门):**阻塞门只看生产依赖的 high/critical**
(`npm audit --omit=dev --audit-level=high`)——红了就一定有人动手,因为那意味着玩家浏览器里
真跑着有洞的代码;**构建/测试期依赖只报不挡**(它们不进 dist、玩家侧攻击面为零,而更新极频繁,
拿它挡合并会训练我们忽略红色)。

**变异验证**(§4.13,**不许只看到绿就宣布成功**):四条闸门逐条把它弄红再验回绿——
server 故意失败用例 → `BUILD FAILURE` 且 `Tests run: 315, Failures: 1`、退出码 1;
web 故意失败用例 → 退出码 1;类型错误 → `tsc -b` 退出码 1;未用变量 → `lint` 退出码 1;
四条清理后一律回 0。**取证边界如实记**:这证明的是**命令本身会失败**(GitHub Actions 的步骤
正是据退出码判红),**不等于工作流接线正确**(YAML 语法 / 触发器 / runner 只能由推上去的第一次
真实运行来证)——故「第一次真实跑绿」列为本条的收尾验收项,见 §2.1 收尾。

**✅ 收尾验收:第一次真实运行已跑绿**([run 31149392363](https://github.com/felixQAQ-edu/ai-universe-simulator/actions/runs/31149392363),
`main@3411afe`,push 触发,全程 **51s**):三个 job 全绿 —— web 47s / server 38s / deps 16s。
**至此本地变异验证证明不了的那一环(YAML 语法 / 触发器 / runner 接线)已被真实运行证明。**

**且不是「绿因为它什么都没做」**(§4.13「绿有两种解释」,故逐条查了 job 日志而非只看对勾):
server 日志实见 `Tests run: 314, Failures: 0` + `BUILD SUCCESS`;web 实见 `Tests 350 passed`
(29 个测试文件)+ `✓ built in 388ms`;audit 两步实见 `found 0 vulnerabilities`
(**阻塞的生产依赖那步与只报不挡的全量那步都是 0** —— 后者是 F 升级换来的干净基线,
以后再红就一定是新东西)。

### 2.2 ✅ `/actuator/info` 暴露 commit SHA —— 已落地(2026-08-06,`410b265`)

**原症状**:`application.yml` 的 `management.endpoints.web.exposure.include` **只有 `health`**,
`info` 既未暴露、也没配 build-info(Maven 需 `spring-boot-maven-plugin` 的 `build-info` goal 才会生成)。

**为什么这条便宜得离谱却价值不低**:我们**已经在这个坑里摔过两次**——
[runbook §3.1.5](phase3-fly-deploy-runbook.md)(④ 成本闸门冒烟时「设了阈值不触发」,
最后靠**抓线上 bundle 哈希**才坐实是旧镜像)+ §3.1.6(secret 漏设致线上静默退回 mock)。
两次的形状一样:**「先证明环境是你以为的那个,再验功能」,而当时没有任何一条命令能直接回答这个问题**。
一个带 commit SHA 与构建时间的 `/actuator/info`,把那次靠 bundle 哈希做的人工取证变成一条 `curl`。

**已落地**:`pom.xml` 加 `build-info` 执行(`additionalProperties.commit = ${git.sha}`,缺省 `unknown`)/
`application.yml` exposure 加 `info` **一项**并显式 `management.info.env.enabled: false` /
`Dockerfile` 加 `ARG GIT_SHA` 透传 `-Dgit.sha`。**后端逻辑零动,纯构建配置**。
安全面按原计划守住:`env` / `configprops` / `beans` **一律没开**(实测 `/actuator/env` 仍 404)。

**`.git` 拿不到怎么解决(这是本条唯一的真难点,如实报)**:`.dockerignore` **刻意排除 `.git`**
(构建上下文不该带版本库),所以 `git-commit-id-plugin` 一类「构建时自己读 `.git`」的方案
**在多阶段构建里根本不可用**;放开 `.dockerignore` 让 `.git` 进构建上下文是**用更大的代价换更小的便利**。
唯一干净的路是**外部传入构建参数**。

**对现有 `fly deploy` 流程的影响(有,必须说清)**:部署命令从 `fly deploy --ha=false` 变成

```sh
fly deploy --ha=false \
  --build-arg GIT_SHA="$(git rev-parse --short HEAD)$(git diff --quiet HEAD || echo -dirty)"
```

runbook 里**三处 `fly deploy` 全部同步**(§2.1 首次部署 / §3.1.4 真 key 阶段 / §3.1.6 的回指),
**不留一条不带参数的旧命令**——留着就等于留了一条会让检查失效的捷径。

**没传会怎样 = 显示 `unknown`,而这是刻意选的**:让它**可见地不知道**,好过编一个假 SHA;
假值会让 §3.1.5 得出**假阴性**,而假阴性正是这一节要治的病(与 [ADR-018 §4.14
「观测工具本身需要被验证」](adr/ADR-018-base-world-visual-migration-and-severity-contract.md) 同族)。
`-dirty` 后缀同理:工作区不干净时 SHA 会撒谎(它指向的 commit 不含未提交的改动)。
**顺带实测发现并修正**:`git diff --quiet` **看不见已 `git add` 但未提交的改动**,
在「暂存后直接部署」这个最容易发生的场景里会报出假的干净 → 改用 `git diff --quiet HEAD`。

**本地实证**:传 SHA → `build.commit=338a6a4` 与 `git rev-parse --short HEAD` **一致**;
不传 → `unknown`;`/actuator/health` 照常 `UP`;`/actuator/env` **404**;server 314 绿。
**取证边界**:线上真实读数须待下次 `fly deploy`(§2.1 那次发布正好是它的第一次用武之地)。

### 2.3 ✅ npm audit —— 复核后确认 **0 个影响生产依赖**,并已同批升级归零(2026-08-06)

外部审查说「21 个告警」;**2026-08-06 在当前 lockfile 上实测为 3 个 high,且全部不在生产依赖里**
(读数出入可能来自不同时间点或不同 `--omit` 口径,以现测为准):

| 包 | 严重度 | 引入者 | 是否进 dist |
|---|---|---|---|
| `postcss` 8.5.15 | high | `vite@8.0.16` | ❌ 构建期 |
| `undici` 7.28.0 | high | `jsdom@29.1.1` | ❌ 测试期 |

`npm audit --omit=dev` = **found 0 vulnerabilities**。两者都不进浏览器,
玩家侧攻击面为零;真实风险面是**构建机与测试环境**(postcss 那条是任意 `.map` 文件读取,
需攻击者能控制 CSS 源——在我们的构建里不成立)。

**已升级**(2026-08-06,`338a6a4`,按原计划与 CI 同批):`npm audit fix` →
postcss 8.5.15→8.5.26 / undici 7.28.0→7.29.0(连带 nanoid / browserslist / brace-expansion 补丁位),
**只动 `package-lock.json`,`package.json` 零改**(全是传递依赖的补丁级提升)。
`npm audit` 全量 **0 vulnerabilities**;server 314 + web 350 全绿、lint/tsc/build 全过;
**dist bundle 哈希未变**(`index-CxT44S0n.js`)= 产物逐字未受影响。

**定性:这不是补漏,是清噪音**——生产侧本来就是 0,升级的真实收益是让 CI 里那条
「只报不挡」的全量 audit **从今天起是干净的**,以后再红就一定是新东西(有基线才有信号)。

---

## 第 3 层 · 简历价值缺口(挂账,规模大,需自己的 ADR)

### 3.1 外部判断(原样记录)

> 项目的技术深度落在「**AI 应用可靠性**」上(schema 校验 / 修复重试 / 保守 no-op 降级 /
> golden parity / 提示词 lockstep / 成本闸门),**而 Java 后端岗位面试看的是「数据库 / 并发 / 分布式」**。
> 现状是**单实例 + 内存会话 + 文件存储**——这三样恰好把上述三块全绕开了。

建议的**一刀**:**受控 Tool Calling 执行器 + Agent Trace + 状态变更入数据库事务**,
而不是先做 RAG——**「只有向量检索、没有工具权限」的简历型 RAG 价值低**(人人都有,且不体现后端能力)。
这一刀能同时补齐:Agent 编排 / Java 接口与事务边界 / 数据库建模 / 可观测性 / 失败恢复。

### 3.2 Felix 的补充判断(一并记,且这条约束优先)

> **这一刀的规模远大于此前任何一刀,且会动到引擎与存档这两个最稳的部分,
> 不能按「一刀一个世界」的方式切——需要自己的 ADR 与至少三刀切分。**

这条判断值得展开一句,因为它与项目已有的经验直接对得上:视觉移植四刀之所以能一刀一个世界,
是因为**每刀的爆炸半径都被 feature gate 和「未登记世界 DOM 逐字节不变」焊死**;
而引擎(golden parity 字节级零回归,ADR-009/010 至今守住)与存档
(ADR-015 restore 守护测试)**恰恰是全项目最不该被「顺手动一下」的两块**。
一刀切下去同时动这两处,等于把项目最强的两条护城河同时置于返工风险中。
**挂账,不急开工。**

### 3.3 数据库迁移的具体形态(另记,与 §2.3 存档同一片区域)

> **交叉**:[future-experience-backlog §2.3「存档系统只做了一半」](future-experience-backlog.md)——
> 那边是从**产品**视角看(档找不回来 / 孤儿档 / 无 TTL),这边是从**架构**视角看同一片地。
> **两边开工时必须一起看**:先迁 PostgreSQL 再补多存档管理,和反过来做,代价差一整轮。

PostgreSQL / MySQL 迁移待评估的形态清单(**只是清单,不是方案,勿当结论**):

- `game_session` 快照 + **版本号**;`game_event` 事件表(与现行 `state.log` 折叠的关系待定)
- **乐观锁**(替代现行内存 `ConcurrentHashMap` + CAS 忙态守卫)
- **turn 幂等键**(现行靠忙态守卫防重,断线重试路径上并不严密)
- **Redis 分布式忙态锁与限流**(ADR-016 的日计数现在是**内存**的,deploy 即清零)
- **会话淘汰 / TTL**(= §2.3 第三面,卷与内存单调增)
- **多实例**(与 [ADR-015](adr/ADR-015-overseas-deployment-form-factor.md) 硬约束①「单副本可钉死」直接冲突——
  那条约束是**当时刻意选的**,不是疏漏;迁多实例等于修订它)

---

## 另记 · 一条状态(不是待办,是已做的决定)

### main 与线上差了整整一个视觉移植阶段

**四刀皮肤(规则怪谈 / 修仙 / 末日 / 克苏鲁)+ 修仙屏视觉收敛 + 导航层(返回键)+ B1 + B2 融合入口,
全部已合并进 `main`,但从未部署到正式实例 `wanjie-ai`。** 每一刀的真机验收都用**临时 app**
跑完即销毁(见各刀收口记录),正式实例上跑的**至今是旧 UI**。

**这是刻意的,不是缺陷**——临时 app 让每刀的真机取证互不污染,也避免半成品长期挂在唯一的公开地址上。
外部审查观察到的「静态资源时间指向 7 月 22 日」正是此事的外部表征(7-22 = ④ 成本闸门那次部署)。

**待 Felix 决定的只有一件事:何时发布。** 发布前建议顺带确认两件已挂账的事:
- [ADR-018 §6](adr/ADR-018-base-world-visual-migration-and-severity-contract.md) 的
  **「生成中点返回的真机行为」**——线 C 的桩后端没有 SSE 端点,那条路径真机未验,
  正好在真后端上顺带确认(只丢一回合、局仍可续 + 续局 notice 文案够不够用);
- 本文件 §2.2 的 `/actuator/info`——**若先做它,这次发布就是它的第一次真实用武之地**。

---

## 简历素材(不是工程活,单独记)

### 数据缺口:方括号必须从日志补齐

外部给出的项目经历文本**可用**,但其中的方括号占位数据**必须用真实读数填**,
一个都不许拍脑袋。要补的八项:

world-gen 首轮有效率 / 修复后成功率 / 回合 TTFT / P50–P95 总延迟 /
平均输入输出 token / 单局与单回合成本 / no-op 降级比例 / 最长稳定连续回合数。

**这些日志里都有,只是从来没统计过**——per-turn INFO(`fd5a2c3`)记了 action + 落账数值 + 提议 ending,
usage INFO(`fc1491d` + `46a22b8`)记了 prompt/completion/total + 缓存命中/未命中,
no-op 降级与修复触发都有 WARN/INFO 行,月度 ¥ 落盘在 `/data/quota-YYYY-MM.json`。

**可作为一个独立小活**:写一个日志统计脚本(捞 Fly 日志 → 按 save/turn 归并 → 出上述八项)。
**规模小、无架构风险、产出直接可用**,适合在任意两刀之间插。
**注意**:线上样本目前几乎全部来自 Felix 自己的冒烟与试玩,**统计口径要写明这一点**——
「稳定连续回合数」来自单人长局与来自真实玩家群体,是两个数。

### 「不要写」清单(现在写了就是面试雷)

以下六项**当前均不属实**,写进简历会在追问第二层时当场崩:

| 不要写 | 现状 |
|---|---|
| Agent 工具调用 | 不存在(= 第 3 层那一刀要建的东西) |
| RAG / 向量库 | 不存在,且外部判断**也不建议先做** |
| 多模型动态路由 | provider 是**配置表切换**(ADR-001),非运行时路由 |
| 分布式高并发 | **单实例 + 内存会话**(ADR-015 硬约束①刻意钉死) |
| 完善的内容安全 | `NoopModerationGateway` 原样放行(见 §1.2) |
| 完整全链路可观测性 | 只有结构化 INFO 日志,**无 trace / 无指标 / 无面板** |

**反过来说,现在就能理直气壮写的**(且区分度不低,因为大多数同类项目没有):
schema 校验 + 一次修复重试 + 保守 no-op 降级的三级可靠性策略 /
**golden parity 字节级回归**(跨语言移植的正确性护城河)/ prompt lockstep 守护测试 /
双层成本闸门与真实计价记账 / 流式 SSE wire 协议设计(哨兵 + 叙事回灌)/
**变异验证**(§4.13:绿有两种解释,而测试套件本身不区分)。
最后一条尤其值得讲——它体现的是**对「测试通过」这件事本身的怀疑**,面试里很少有人主动提。
