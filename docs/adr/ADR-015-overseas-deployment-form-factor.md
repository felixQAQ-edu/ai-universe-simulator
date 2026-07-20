# ADR-015 · 境外部署形态:同源单容器 + Spring static + 最小续局落盘(修订 ADR-002)

- **日期**:2026-07-16
- **状态**:已采纳
- **决策者**:Felix

## 背景

上线阶段定调转**路线 B(境外托管、不备案、暂不进微信生态)**先完成软启动验证(2026-07-16,见 [phase3-launch-plan.md](../phase3-launch-plan.md)):Felix 人在悉尼,个体工商户执照的境内经营地址 + 经营者远程核验成本过高。四腿重排后 **③ 境外部署接缝升当前主线**,本 ADR 即 ③ 的形态决策。

**与 ADR-002 的关系(修订、非推翻)**:[ADR-002](ADR-002-backend-form-factor.md) 选 Spring Boot @ CloudBase 云托管,其决策因子 **②(微信原生集成)③(小程序衔接)在路线 B 下失效**;但因子 **①(免运维)④(Spring 应用层可迁移经验)仍立**,且不依赖微信专有链路——它们来自「标准容器 + Spring Boot」,换任何容器托管都保留。路线 B **恰好命中 ADR-002 §重新审视预登记的「迁出需求出现(脱离微信生态)」触发条件**——是预案内演进,非意外翻车。本 ADR 的范围 = **②③ 失效后的部署形态重估**;业务核心与应用层架构决策继续有效。

**薄适配层实检结论(入档)**:「适配层还没出生」——`server/.../platform/` 只有空置占位 `package-info.java`,`pom.xml` 零腾讯/CloudBase 依赖,代码无平台专有假设渗漏;DeepSeek key 走环境变量(`api-key-env: DEEPSEEK_API_KEY`),yml 默认 `active: mock`。ADR-002 承诺的「业务逻辑平台无关、集成只作薄适配层」**兑现到了极致形态:适配层根本没写过,迁出成本低于预估**(FINDINGS F-018 同步记录)。

**运行时现状(勘察输入)**:无 DB / 缓存 / 文件存储;session = `GameSessionManager` 内存 `ConcurrentHashMap`;单进程忙态守卫(`AtomicReference<TurnPhase>.compareAndSet`)→ **现阶段只能单副本**。回合 = `POST` + `SseEmitter(120_000L)` 长时流式响应,融合长局 50+ 回合。前端 `web/dist` 纯静态 844KB,api 全走相对路径 `/api/...`,后端现不托管静态、零 CORS;`createH5GameApi(baseUrl)` 参数位已留。无 Dockerfile / CI / 健康检查端点。

约束条件:

1. **成本硬顶 ¥200/月(含 LLM)**:托管费必须给 LLM 留足空间(④ 成本闸门的总预算)。
2. **单副本**:忙态守卫是进程内原语,平台必须能钉死单实例。
3. **SSE 长流不可缓冲**:回合体验建立在逐字流上,平台反代/CDN 缓冲即毁产品。
4. **软启动不接受刷新即丢局**(Felix 拍板 A):内存 session 需最小持久化。
5. 引擎/校验/`schemaVersion`(保 "0.4")/golden parity 零回归——部署形态不动游戏内核。

## 候选方案

> 本 ADR 含三个子决策:(一)前后端拓扑;(二)静态托管方式;(三)最小续局的持久化边界。平台选型**刻意不在本 ADR 拍**——先立硬约束清单,实现 slice 对着清单验(见 §最终决策.4)。

### (一)前后端拓扑

#### 方案 A1:前后端拆分托管(静态托管 + 后端容器两个部署单元)

**优点**:静态走 CDN 全球快;后端独立伸缩。
**缺点**:两个部署单元、两个域名或一套反代配置;跨域 → 引入 CORS 面;SSE 经 CDN/边缘层多一跳缓冲风险。**排除**:软启动量级无拆分收益,复杂度纯支出;拆分托管留给有真实流量之后。

#### 方案 A2:前后端同源单容器(本 ADR 采纳)

一个容器 = Spring Boot 后端 + 前端静态产物,一个部署单元一个域名。

**优点**:零 CORS(前端相对路径 `/api/...` 现状直接成立)、一条部署管线、SSE 链路最短。
**缺点**:静态与 API 同进程(接受:量级小,见勘察结论);前端独立发版需重发容器(接受:软启动前后端本就一起动)。

### (二)静态托管方式

#### 方案 B1:容器内加反代(nginx/caddy 伺服静态 + 反代 API)

**优点**:静态伺服性能好,SPA fallback 配置现成。
**缺点**:容器内多一个进程/组件(supervisord 或多阶段编排),多一层反代=多一处 SSE 缓冲风险(nginx 默认 `proxy_buffering on` 正是 SSE 头号坑)。**排除**:引入的组件恰好制造了我们最怕的问题。

#### 方案 B2:Spring static resources(本 ADR 采纳)

`web/dist` 拷入 `server/src/main/resources/static/`(该目录已存在且为空),Boot 默认伺服,零新组件。

**优点**:零配置零新进程;SSE 链路上没有新增缓冲点。勘察确认无坑:回合 SSE 早已不占 Tomcat 容器线程(`GameController` 独立 `cachedThreadPool` + MVC 异步分派),静态文件只短暂占 Tomcat 请求线程(默认 200),软启动量级无压力;`SseEmitter(120_000L)` 显式构造超时优先于 `spring.mvc.async.request-timeout`,静态伺服不影响它。
**缺点**:静态伺服性能不及专业组件(接受:844KB、软启动量级);**`static/` 成为新的出网面**(升格为显式安全条款,见 §最终决策.3)。

### (三)最小续局(A 项)的持久化边界

#### 方案 C1:引数据库(SQLite/Postgres)

**排除**:软启动无账号无查询需求,引 DB 是纯负担;且与「回国迁移数据零债」(launch-plan §五.3)相悖。

#### 方案 C2:回放重建(只存玩家动作序列,重放推导状态)

**排除**:不可行——历史回合的完整 LLM 产出没存,重放需重新调 LLM,结果不可复现。

#### 方案 C3:每 saveId 一个 JSON 文件落盘 + Engine 纯增量恢复入口(本 ADR 采纳)

**优点**:零依赖;文件即存档、可 scp 可 diff;与单副本形态天然匹配。
**缺点**:需给 Engine 加恢复入口(纯增量,见下);内存+文件混合态有一致性边界(显式记入已知限制)。

## 最终决策

**同源单容器(A2)+ Spring static(B2)+ JSON 文件落盘 + Engine 纯增量恢复入口(C3)+ 平台硬约束清单先行**。

### 1. 部署单元形态

一个容器镜像:Spring Boot fat jar,`web/dist` 构建时拷入 `resources/static/`。一个域名,前端相对路径现状零改。**SPA fallback 不做**:前端无客户端路由(无 react-router、无 pushState,单视图状态机切屏),刷新永远落在 `/`;未来引入客户端路由再补 forward index.html。`SseEmitter` 120s 显式超时维持现状。

### 2. 最小续局(A 项)设计边界

**落盘在 `GameSessionManager` 层**,每 saveId 一个 JSON 文件,不引数据库。据勘察结论定边界如下:

- **持久化格式(视图 1 全量)**:`{schemaVersion, world(含 isTrue/hiddenLogic、discovered/reached 原地标注), state(turn/status/timeline/log/logSummary), triggered, issues, currentActions, phase 提示}`。`GameSession.phase`(AtomicReference)是运行时原语不落盘,回载按 `status` 重置(`ongoing`→`AWAITING_ACTION` / `ended`→`ENDED`);`currentActions` **必须落盘**(合法性校验与 no-op 复用都靠它,丢了续局第一回合必被守卫 1 拒绝)。
- **Engine 恢复入口 = 纯增量**(勘察发现并经 Felix 认可的边界修正):Engine 现状**无法从落盘数据回载**——构造器只从 `world.character.attributes` 读数值轴,`turn/status/timeline/log/logSummary/triggered/issues` 全零值初始化且无 setter;`snapshot()` 是喂模型/出网视图、非回载格式。修法 = Engine 加一个 **restore 工厂(或恢复构造)**,从持久化 JSON 读回七个字段;**现有构造器与 `apply` 逐字不动** → 「Engine/校验/golden 零动」修正为「**现有行为零动、纯增量恢复入口**」,golden parity 字节级零回归照守。
- **写盘时机与忙态守卫的交互**:`TurnStateMachine.submitAction` 中 `executor.execute` 返回后、相位放回 `AWAITING_ACTION`/`ENDED` **之前**(临界区尾部)写盘——忙态守卫保证每 saveId 单写者,零新锁;init 播种后也写一次(否则起局即崩丢局)。写盘失败响亮告警、不阻断回合(内存态仍是权威)。
- **启动回载**:扫落盘目录按文件名重填 map;saveId=UUID 不受影响、无碰撞、生成逻辑零改。三份轴语义集**无需落盘**——world 里有 `archetypes` 数组,回载时经 `ArchetypeRegistry`(单体/`fusedAxes`)原路重派生,单一真理源。
- **前端**:localStorage 记 saveId + 续局入口(守 ADR-003:localStorage 访问收进 `api/` 或独立薄封装,逻辑层不碰平台 IO)。
- **不在范围**:跨设备云存档(冻结的 Phase 2 主题 A)、多存档管理、存档淘汰策略。
- **CONTEXT 暂缓回写**:持久化格式实为跨模块约定,但按 ADR-013 先例等实现端到端验通后再立字,避免空头约定——实现 slice 收口时重新评估是否回写 CONTEXT §三(届时 v1.5)。

### 3. 落盘安全(显式决策条款,与 CONTEXT §三.9 三视图消毒同源)

**落盘文件 = 视图 1 全量(含 `isTrue`/`hiddenLogic`)**——回载后引擎要继续裁决真假规则,消毒版存档等于自残。因此:

- **落盘目录必须在 web 根之外**:持久卷挂载点(如 `/data`),**绝不落在 `resources/static/` 或任何可被静态伺服的路径之下**——同源单容器让 `static/` 成为新攻击面,存档若落进去等于把隐藏逻辑发布到公网。
- **加启动断言或守护测试**:「落盘路径不得位于 static resources 之下」,实现 slice 落地时与 restore 守护测试一起进 CI。
- 出网路径不变:任何下发前端的数据照旧只走 `toClientState()` 消毒投影(视图 3)。

### 4. 平台选型硬约束清单(本 ADR 不预定平台;实现 slice 对着清单逐项验)

1. **单副本可钉死**:实例数可固定为 1,**不强制 scale-to-zero / 自动休眠**(休眠 = 冷启动丢 SSE + 回载延迟;忙态守卫与内存 session 都要求恰一个进程)。
2. **SSE 长时流式不缓冲**:平台反代 / CDN 层**逐项确认**不缓冲 `text/event-stream`(≥120s 长连接不被中途掐断)。
3. **持久卷**:A 项落盘的前提;ephemeral 盘 redeploy 即清,续局白做。
4. **出口可达 `api.deepseek.com`**:境外→境内反向跨境;候选区域定了再**实测延迟**(逐字流对 TTFT 敏感)。
5. **托管费给 LLM 留足空间**:¥200/月 总预算,托管固定成本应压在小头。
6. **环境变量注入**:`DEEPSEEK_API_KEY` + **`AIUNIVERSE_LLM_ACTIVE=deepseek-v4-flash`**(Spring 外置配置覆盖 `aiuniverse.llm.active`,杜绝线上误跑 mock)+ 落盘目录(如 `AIUNIVERSE_SAVE_DIR=/data/saves`,具体名待实现 slice 定)。

### 关键理由

1. **一个部署单元一个域名零 CORS**:前端相对路径、`createH5GameApi(baseUrl='')` 现状直接成立,零代码迁移税;拆分托管的收益(CDN/独立伸缩)在软启动量级不存在。
2. **SSE 链路最短**:B2 不引入任何新反代层——我们最怕的缓冲风险不是被「配置好」而是被「不存在」解决。
3. **落盘复用既有护城河哲学**:restore 守护测试把 golden parity 的护城河延伸到持久化边界(同当年 transform parity 延伸到切分+回灌);Engine 恢复入口纯增量,现有构造器/apply 逐字不动。
4. **保留演进路径**:标准容器随处可跑(回国迁 CloudBase = 搬容器,ADR-002 因子 ④ 兑现);文件存档单向可升级为 DB/云存档(主题 A 解冻时);先约束后选型让平台可换而决策不换。

## 已知代价

1. **崩溃回滚一回合**:写盘在回合完整结算后,崩溃在回合中 → **盘上永远是最后一个完整回合**——这是特性非 bug(半个回合的状态本就不该存在)。玩家体感 = 重启后回到上一回合再选一次。
2. **init 阻塞占 Tomcat 线程数十秒**:`POST /api/game/init` 是阻塞胖调用,占用容器请求线程(默认 200)。软启动量级可接受;**并发起局成真再移独立执行器**(与回合 SSE 的 `cachedThreadPool` 同法炮制)。
3. **无 SPA fallback**:前端无客户端路由,非根路径直访 404。可接受;未来引路由再补 forward。
4. **单副本天花板**:内存 session + 进程内忙态守卫钉死单实例,水平扩展需先把守卫与 session 外置(Redis/DB)——那是有真实流量之后的问题,当前显式接受。
5. **内存 + 文件混合态一致性边界**:进程内内存是权威,文件是异步快照;写盘失败时内存继续、文件落后(响亮告警)。不做写盘事务/fsync 强保证——软启动档位的可靠性目标是「重启不丢局」,不是金融级持久化。Slice 2 落地口径:**`persist` best-effort 绝不抛——写盘是增值服务不是关键路径,磁盘故障丢续局不杀活回合**。
6. **无健康检查端点**:平台探针通常需要一个 200 端点。是否顺手引 actuator(或手写一个 `/healthz`)**留给实现 slice**,ADR 只记决策:探针必须存在、形式不限。
7. **境内玩家跨境访问较慢**:路线 B 的固有代价(launch-plan §二已显式记),非本 ADR 新增,列此存照。

## 重新审视的触发条件

- **并发起局把 Tomcat 线程池吃紧**(init 阻塞排队可感)→ init 移独立执行器(已知代价 2 的预登记出口)。
- **真实流量需要多副本**(单实例顶不住)→ session/守卫外置,重估本 ADR 单副本前提。
- **候选平台实测跨境延迟不可玩**(TTFT 或逐字流卡顿明显劣于本地直连)→ 重选区域乃至重估「境外托管调境内 LLM」的形态(如换境外可达的 provider,走 ADR-001 配置表)。
- **回国 / 冲微信生态解冻**(launch-plan §四)→ 迁回境内托管,本 ADR 的容器形态直接复用,ADR-002 的 ②③ 因子复活。
- **前端引入客户端路由** → 补 SPA fallback(已知代价 3)。

## 实施步骤

> 本 ADR 为形态决策,实现另起 slice;以下为骨架,不含部署代码。

1. ✅(2026-07-17,Slice 1/2)**落盘 + restore slice**:持久化格式定型 → Engine restore 工厂(纯增量)→ `GameSessionManager` 写盘/回载 → **restore 守护测试**(验收硬门,见附录 A,首过)→ 落盘路径安全断言 → 前端 localStorage saveId + 续局入口。
2. ✅(2026-07-17,Slice 3 第 1 步)**容器 slice**:三阶段 Dockerfile(web build → dist 拷入 static/ → jar)+ 探针(actuator 只开 health)。
3. ✅(2026-07-20,Slice 3 第 3 步)**平台选型 slice**:**Fly.io,区域 syd**,六条硬约束逐项实测全过(含跨境延迟,见实际效果);`fly.toml` 全显式落仓 + 部署 runbook。
4. ✅(2026-07-20)**部署后首次真 key 冒烟**:附录 B 全项通过 + Felix 真机整局拍板(结果见实际效果)。
5. 用 `/roadmap-update` 同步 ROADMAP §五 / launch-plan。

## 附录 A · restore 守护测试(A 项验收硬门)

把 golden 护城河延伸到持久化边界(同 transform parity 当年延伸到切分+回灌):

1. **落盘-回载对拍**:跑 N 回合 → 落盘 → restore → `snapshot()` 与直跑实例**逐字节对拍**(含 world 标注、state 五字段、triggered/issues、currentActions)。
2. **续跑一致**:restore 后续跑一回合(喂相同 parsed),与直跑 N+1 回合的结果**逐字节一致**。
3. **路径安全**:落盘路径位于 static resources 之下 → 启动断言失败(或守护测试红)。

## 附录 B · 部署后首次真 key 冒烟验收清单

- **`total_tokens` 线上有无**:看 `[world-gen]`/`[event-loop]` usage INFO(尾巴已挂 launch-plan §六)。
- **SSE 经平台反代不缓冲实测**:逐字流体感 + 长回合(>60s)不被掐断。
- **续局**:浏览器刷新后可继续;容器重启(redeploy)后可继续——两档都验。
- **出口延迟体感**:境外区域调 `api.deepseek.com` 的 TTFT 与逐字流速率,对照本地直连基线。
- 线上跑的是真 provider 非 mock(启动日志确认 `active` 覆盖生效)。

## 实际效果(2026-07-20 回填:Fly.io syd 首次部署 + 附录 B 冒烟全过,Felix 真机拍板通过)

平台选定 **Fly.io,区域 syd**(冒烟阶段 Felix 是唯一用户,悉尼延迟最低拍板体感最准;国内朋友测试前 `fly volumes fork` + `fly machine clone` 迁 sin,平台内单命令级)。单副本 shared-cpu-1x/512MB + 1GB 卷挂 `/data`,公网 **https://wanjie-ai.fly.dev**。配置全显式落仓(`fly.toml`),部署手动 `fly deploy`、无自动流水线;操作与判定见 [phase3-fly-deploy-runbook.md](../phase3-fly-deploy-runbook.md)。

**六条硬约束逐项实测**:

1. **单副本可钉死** ✅:`--ha=false` + `auto_stop_machines="off"` + `min_machines_running=1`,恒 1 台 started、不休眠。
2. **SSE 不缓冲** ✅:mock 逐字 echo(40ms/字,最苛刻流式载荷)手机+桌面均逐 token 到达;真 key 叙事逐字流正常。另记:mock init 阻塞数分钟未被反代掐断——runbook 预埋的「60s idle 超时掐纯 JSON 胖调用」风险**未现形**。
3. **持久卷** ✅:起局落盘进卷(`55e9834c` JSON 5917B,属主 appuser);续局两档全过——刷新续局 + **redeploy 后续局(卷跨 deploy 保留实证)**。
4. **出口可达 api.deepseek.com** ✅:world-gen 首局 ~120s、次局 ~15s(本地直连基线 ~10s)——**稳态与本地持平,首局慢判为冷因素非稳定跨境代价**;sin 迁移不因延迟提前,仍留作国内测试前动作。
5. **托管费** ✅:常驻机 ≈US$3.2/月 + 卷 ≈US$0.15/月,¥200/月预算占比极小,大头留给 LLM。
6. **env 注入** ✅:`fly secrets` 注入 `DEEPSEEK_API_KEY` + `AIUNIVERSE_LLM_ACTIVE=deepseek-v4-flash`(启动日志确认 active 覆盖生效、线上非 mock);落盘目录实名 `AIUNIVERSE_SESSION_STORE_DIR=/data`(fly.toml [env] 显式写)。

**附录 B 其余项**:`total_tokens` 线上**回真值**(实测 5429 / 3675 / 3712;-1 容错备而不用,launch-plan §六挂账尾巴关闭);restore 守护测试(附录 A)Slice 1 落地即首过(2026-07-17);Felix 手机浏览器完整玩一局融合世界,体感通过。

**另两条部署事实**:远程构建 amd64 镜像实测 **118MB**(远小于本机 arm64 520MB);首局 ~120s 为单次观察、不立 FINDINGS(不成模式,记 ROADMAP 日志)。

## 跟其他文档的交叉引用

- **被修订**:[ADR-002](ADR-002-backend-form-factor.md)(部署形态 ②③ 因子失效重估;①④ 仍立;命中其预登记「迁出需求出现」触发条件)
- **上线全图**:[phase3-launch-plan.md](../phase3-launch-plan.md)(路线 B 定调、四腿重排、§六待决冲突由本 ADR 收口)
- **安全同源**:[`docs/CONTEXT.md`](../CONTEXT.md) §三.9(三视图消毒;本 ADR §最终决策.3 落盘安全与之同源)
- **前端边界**:[ADR-003](ADR-003-frontend-stack-and-taro-boundary.md)(localStorage 访问守 `api/` 薄适配层纪律)
- **薄适配层实检**:`bakeoff/FINDINGS.md` F-018(「适配层还没出生」,ADR-002 → ADR-015 检验链)
- **相关源文件**:`server/.../eventloop/GameSessionManager.java` / `GameSession.java` / `TurnStateMachine.java`(落盘层与写盘时机)、`server/.../engine/Engine.java`(restore 纯增量入口)、`server/src/main/resources/application.yml`(`active` 外置覆盖)
