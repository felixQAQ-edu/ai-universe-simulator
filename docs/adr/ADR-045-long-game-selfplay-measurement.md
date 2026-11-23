# ADR-045 · 长局记忆测量:本机自玩长局工具(HTTP 驱动 + 玩家可见稿)

- **日期**:2026-11-22
- **状态**:**已实施、已合并 a9d9cd3(2026-11-23)**。三刀与底稿收尾都已合入 main(底稿收尾提交 `a9d9cd3`,合并记录见 ROADMAP v22.1)。
  以下为原状态,一字不动:
  **已接受(2026-11-22)**。待审 2–6 由 Felix 2026-11-22 认可校勘代定、改记已决;整套成本上限 ¥10 → ¥12(同日,理由见勘察 5)。
  以下为原状态与历次补充,一字不动:
  提议(草案;只勘察、只写 ADR,不写实现、不动代码 / 提示词 / 存档与轨迹格式 / 生产默认。
  方向「自造长局(甲)、不读线上原始轨迹(W-11 不动)」由 Felix 2026-11-22 定;推荐值与理由由本 ADR 给出,
  需要 Felix 拍板的列在文末「待 Felix 审定清单」)
  ⚠️ 补充(2026-11-22;上文原状态一字不动):待审 1 已定 = (ii) 冻结 T0 存档,回填在清单该项之后;其余待审项仍未定,状态仍为提议。
  ⚠️ 补充(2026-11-22,第二次;上两行一字不动):待审 1 理由补齐、改记「已决」;待审 2–6 照本 ADR 推荐值由校勘代定(Felix 审 ADR 时可推翻);
  待审 7 已由附录 A / B 补齐;勘察 6 改写为已查到(官方上下文 1M)。**状态仍为提议**,等 Felix 审完整份 ADR 再改。
- **决策者**:Felix

## 背景

这条线是 [career backlog](../backlog-career-track.md) 层 4「**分层记忆 / RAG**」的**解冻测量**。
该行写死的可测形态有三项:上下文占用达预算 60–70% / 摘要后出现关键事件遗漏 / 角色无法引用早期事件。
本 ADR 只造**测后两项用的尺子**;第一项用线上计数直接回答(见下「线上计数」与勘察 6)。

### 现状:模型在长局里能看到什么(校勘已核,`main` = `eecd280`;本 ADR 勘察复核)

- `Engine.LOG_KEEP = 4`(`server/.../engine/Engine.java:39`)。每条 log = `{turn, narrative, playerAction}`,**不存选项文本**,
  只存所选 id;超窗回合由 `compressLog` 折成 `[T{n}选{A-D}]` 串进 `logSummary`(零 LLM、抽取式)。
- `timeline` 是每回合由模型在 `stateUpdate` 里**覆写**的一句话(`Engine.java:283`)。
- 回合提示词 = 固定指令 + `engine.contextJson()`(视图 2:world 全量含规则与 `hiddenLogic`、character、
  `state{turn,status,timeline,logSummary,log[最近 4 条]}`)+ 本回合所选「id · 选项文本」(`TurnPromptBuilder.java:982,1000`)。
- `world.character.traits / inventory` 只在 world-gen 时写一次,引擎从不更新。
- 开场叙事 `openingNarrative` 是 transient init 字段(CONTEXT §三.12),**不进 log**,回合提示词里永远没有它。
- ⇒ **开局之后生成的人物 / 物品 / 承诺,离开 4 回合窗口后,模型没有任何通道看到原文**;只剩 `timeline` 那一句(若模型恰好写进去)
  与 `[T{n}选X]` 这串 id。

### 线上计数(2026-11-22,Felix 本机,只有数字)

- **方法**:Felix 本机对线上 `/data/traces` 的**只读拷贝**(2026-11-22 拷出)运行一段 python 统计,只输出数字;
  脚本原文见附录 A(逐字),输出只抄数字行,不含 saveId / 文件名。本 ADR 作者未接触原始轨迹(W-11 不动)。
- **分母**:92 局轨迹(目录 25 MB)。轨迹自 ADR-031 刀 3 起才有(ADR-033 读数:2026-10-08 起),故分母 = 有轨迹的局,不是全部存档。
- **每局已落地回合数分布**:

  | 回合数 | 1–10 | 11–20 | 21–30 | 31–50 | 51–100 | 101+ | 合计 |
  |---|---|---|---|---|---|---|---|
  | 局数 | 48 | 16 | 9 | 8 | 4 | 7 | 92 |

- **> 30 回合 19 局**:修仙 11、寻常 5、规则怪谈 2、克苏鲁 1(> 100 回合 7 局)。
  订正(2026-11-22):原写 12,系校勘读数时计数错误;以附录 B 为准。
- **最长 300 回合**:主调用 `promptTokens` 首回合 3223 → 末回合 5689;末回合 `logSummary` 2546 字符。
- **长局(> 30 回合的 19 局)内调用计数**:主调用 1780 / 修复 236。

### 为什么不读线上原始轨迹、改为自造长局

Felix 已定(甲)。本 ADR 补的理由:线上 19 局长局既不能重跑,也不能在「加记忆账本」之后拿同一局对比;
自造长局能做到「同世界、同选项种子、同回合数」重跑(可复现到什么程度见勘察 7,**与简报的前提有出入**)。

约束条件:

1. **不改**引擎 / 提示词 / 存档格式 / 轨迹格式 / 任何生产默认值;只用现成 env 覆盖。
2. 花钱的动作(server 带真 key 起、脚本跑)**Felix 亲手**,key 只在他终端里 `read -s` 后 export,不进对话 / 文件 / 提交。
3. 原始轨迹、存档(视图 1,含 `isTrue` / `hiddenLogic`)**只在仓库之外**。
4. 判读口径(矛盾、当生人重介绍、伏笔断线、正确回指)**不进本 ADR**,留给下一步勘察底稿。

## 勘察结论(逐条)

### 勘察 1 · 驱动方式:HTTP 驱动可行,无硬障碍;`drive.py` 已漂移五处

**开局** `POST /api/game/init`(`GameController.java:155`,plain POST,异步 JSON):

| 局类型 | 请求体 | 备注 |
|---|---|---|
| 单体 | `{"archetype":"cultivation"}` | `rules_creepy` / `life_sim` / `cthulhu` … 同形 |
| 融合 | `{"archetypes":["cultivation","rules_creepy"]}` | **有序,host 在前**;只认 `FUSION_COMBOS` 三组(`ArchetypeRegistry.java:311`):`cultivation×rules_creepy`(识海遗蜕)/ `rules_creepy×apocalypse`(人防)/ `cultivation×cthulhu`(验讫);反向 400 |
| 固定名三世界 | 与单体完全相同(`{"archetype":"life_sim"}` / `cyberpunk` / `time_travel`) | `fixedWorldTitle` 与 `world.subtitle`(ADR-044)都在服务端收口,请求侧无差别 |

响应 = `InitResponse{saveId, world(视图 3), openingNarrative, availableActions, attributes(轴元数据 + bands 区间), timeLabel?, markedTurns?}`。

**回合** `POST /api/game/{saveId}/turn`,body `{"turn": <客户端游标>, "actionId": "A"}`,
SSE 事件四种:`narrative{text}`(多条,拼接即玩家所见)/ `delta` / `ending{id,title,description}` / `error{code,message}`。
`delta` = `{turn, status, <各轴 key>: 值…, discoveredRules[{id,content}], availableActions[…], timeLabel?, markedTurns?}`
(`EventLoopService.buildDelta`,`:580`)。**`delta` 不带 `timeline`,也不带「是否降级」。**
游标比对之前的拒绝走 HTTP JSON 而非 SSE:404 `session_not_found` / 409 `turn_stale` / 400 `illegal_action` / 503 `server_at_capacity`。

**`drive.py`(`bakeoff/recordings/world-gen-smoke/`,最后实质修改 `ec3c3f8`,ADR-010 期)相对现状的漂移**:

1. 轴写死 `hp` / `san`:现在各世界轴 key 不同(修仙 `hp/mana/realm`、寻常另一套),须按 init 的 `attributes` 元数据遍历。
2. 只解析 SSE,**不处理 HTTP 拒绝**:409 `turn_stale` / 503 会抛 `HTTPError` 直接崩;应照前端做法 —— 409 → `GET /state` 重同步。
3. 选项策略是「危险关键词」启发,**偏向驱死**,与长局目标相反。
4. **不认识《寻常》的「就到这里」出口**:服务端在 `life_sim` 青年起每回合追加 id = `X` 的选项(`EventLoopService.appendLifeExitAction`,
   `LifeStageTable.EXIT_ACTION_ID`)。均匀随机若把它算进去,第 8 回合起每回合约 1/4–1/5 概率把这一生推进收束段。
5. 所在目录 `bakeoff/recordings/` 已被 `.gitignore`(F-011:防误带真实录制),新工具**不能**放那里,否则新文件不入库。

端点路径、请求体形状、SSE 四事件名、120 s 超时(`SseEmitter(120_000L)`)与 `drive.py` 一致,**未漂移**。

**结论**:HTTP 驱动无硬障碍,不需要评估测试侧 `exec:java` 进程内装配(取舍见候选方案 B)。

### 勘察 2 · 本机运行会撞的闸

| 闸 | 默认 | 本机会不会撞 | 现成 env 覆盖 | 推荐值(只对本机进程) |
|---|---|---|---|---|
| 成本闸 · 全局日限额(ADR-016,真闸) | ¥6 | **会**:整套按配置单价估算 ¥5.7–9.9(勘察 5) | `AIUNIVERSE_QUOTA_DAILY_BUDGET_CNY` | **12** —— 顺手把它当作**整套成本上限的硬闸**(原推荐 10,Felix 2026-11-22 改 12,理由见勘察 5) |
| 成本闸 · 全局月顶(真闸) | ¥175 | 不会:月账文件 `quota-YYYY-MM.json` 落在 `store-dir` 下,本机新目录从 0 起 | `AIUNIVERSE_QUOTA_MONTHLY_BUDGET_CNY` | 不覆盖 |
| 单 IP/设备日 init(软闸) | 10 局 | **可能**:7 局 + 补局上限 4 = 11 | `AIUNIVERSE_QUOTA_INIT_PER_KEY_DAILY` | **20** |
| 单 IP/设备日回合(软闸) | 300 回合 | **会**:基线上限 600 回合 | `AIUNIVERSE_QUOTA_TURNS_PER_KEY_DAILY` | **1000** |
| 回合并发准入(ADR-022) | 8 | 不会:脚本**逐局逐回合串行** | `AIUNIVERSE_TURN_MAX_CONCURRENT` | 不覆盖 |
| 开局并发准入(ADR-030) | 4 | 不会:串行 | `AIUNIVERSE_INIT_MAXCONCURRENT` | 不覆盖 |
| world-gen 段时限(ADR-030) | 180 s | 偶发撞线 → 502 `world_gen_failed`,当作开局失败重试同一槽位(不计补局) | `AIUNIVERSE_WORLDGEN_SEGMENTDEADLINEMS` | 不覆盖 |
| 年龄门 | — | **不存在**:server 与 web 均无年龄门(工程债 §1.3 挂账未做),无可撞 | — | — |

- 软闸键:本机请求无 `Fly-Client-IP` 头,回退 `getRemoteAddr`(127.0.0.1 / ::1);脚本不发 `X-Device-Id`,设备键不计。
- ⚠️ 日态(当日 ¥ 与计数)**只在内存**(`QuotaService`:「deploy 清零」),故 ¥12 这道闸只在**一个 server 进程**内成立;
  中途重启 server 会把日账清零 —— 运行方式里写死「整套在一个进程里跑完」。
- 四个 env 都是 `QuotaProperties`(`@ConfigurationProperties("aiuniverse.quota")`)的 relaxed binding,**生产默认值一个不改**。

### 勘察 3 · 轨迹:与线上同一格式,但 commit 字段要显式注入

- 轨迹目录:`aiuniverse.trace.dir`,env `AIUNIVERSE_TRACE_DIR`(默认 `${store-dir}/traces`)。每局 `<saveId>.trace.jsonl`,
  每个已落地回合一行(含降级回合),写它的是同一个 `FileTraceSink` / `TurnTraceCollector` —— **自玩局与线上同一格式(`TurnTrace.SCHEMA = 1`)**,
  `TraceReplayer` / `TraceRecheckTool` / `DataStatsTool` 照读。
- ⚠️ server 启动断言只拦「web 根之内」,**不拦仓库之内**(`RepoPathGuard.requireOutsideRepo` 只被测试侧工具调用)。
  所以「轨迹在仓库之外」只靠运行方式里的 env 保证;脚本侧补一道:输出目录落在仓库内就拒跑(同 `RepoPathGuard` 口径)。
- ⚠️ 存档目录也要一起指到仓库之外:`AIUNIVERSE_SESSION_STORE_DIR` 不设时默认 `./data`(即 `server/data/`,虽已 gitignore 但在仓库内),
  存档同为视图 1 全量;月账文件也落在这里。
- ⚠️ 轨迹的 `commit` 字段来自 `build-info.properties` 的 `build.commit` = Maven 属性 `git.sha`,**默认 `unknown`**(`server/pom.xml:37`)。
  本机起 server 必须带 `-Dgit.sha=$(git rev-parse --short HEAD)`,否则评测基线无法对上代码版本。
  (`spring-boot:run` 前置生命周期含 `generate-resources`,build-info 会跑;**本机未实测**,首局跑完先看一眼轨迹里的 `commit`。)
  ⚠️ 订正(冒烟实测,2026-11-23;上句原文不动):「本机未实测」改为**已验证** —— 冒烟局轨迹 `commit` = `5a15778`,
  `-Dgit.sha` 在 `spring-boot:run` 下生效(见「实际效果 · 冒烟」)。
- 单文件上限 5 MB ≈ 460 回合,120 回合局不会撞;总量上限 200 MB 不会撞。

### 勘察 4 · 玩家可见稿的字段来源

| 字段 | HTTP(SSE / `/state`)来源 | 轨迹来源 | 真理之源 | 说明 |
|---|---|---|---|---|
| 世界(标题 / 副标题 / 背景 / tone / 规则文本 / 人物) | init 响应 `world`(视图 3,已剥 `isTrue` / `hiddenLogic`) | 首行 `pre.world`(**视图 1,含隐藏字段**) | 引擎;**稿件只取 HTTP 视图 3** | 轨迹的 world 不得进稿件 |
| 开场叙事 T0 | init 响应 `openingNarrative` | **无**(不落盘、不进轨迹) | init 响应 | 只此一处,脚本必须当场存下 |
| 本回合叙事 | SSE `narrative` 拼接(玩家所见) | settled:`parsed.narrative`;degraded:`streamedNarrative` | **SSE = 玩家所见**;轨迹 = 之后进 log、模型下回合看到的那份 | 两者按 ADR-006 叙事回灌应一致,工具逐回合比对、不一致标出 |
| 本回合给出的选项(text + hint) | 上一回合 `delta.availableActions`(T1 取 init 的) | `pre.currentActions` | 轨迹 `pre.currentActions`(服务端实际受理的那组) | 本套世界都不登记 ADR-039 选项前缀,两侧应一致;`parsed.availableActions` 是服务端改写**之前**的,缺《寻常》的 `X`,**不能用** |
| 所选 id | 脚本自己发的 `actionId` | `actionId` | 轨迹 | |
| 上回合 timeline(模型写本回合时看到的) | 回合前 `GET /state` 的 `world.state.timeline` | `pre.state.timeline` | 轨迹 `pre` | `delta` 不带 timeline;`/state` 只读、不占名额、不走配额 |
| 本回合写下的 timeline | 回合后 `GET /state` | 下一行的 `pre.state.timeline`(末回合无) | 回合后 `/state` | 末回合只有 `/state` 能拿到 |
| 各轴落账值 | `delta` 顶层各轴 | `post.attributes` | 引擎(两侧同源:`engine.attributes()`) | |
| 是否降级 + 原因 | **无** | `path == "degraded"` + `degradeReason` | 轨迹 | 只有轨迹有,工具事后按 `turnBefore` 对齐 |
| 结局 | SSE `ending` | `parsed.ending` + `post`(结局极性改判后的以引擎为准) | SSE `ending`(`buildEnding` 读引擎已落账结局) | `PaidanEndingCheck` / gate 改判后 `parsed` 不是最终值 |

### 勘察 5 · 成本估算(按配置单价估算,**不是实际花费**)

单价 = `application.yml` 当前配置(provider `deepseek-v4-flash`,`model: deepseek-flash`,注释写明取高峰档,核实日期 2026-10-06):
CNY / 1M tokens:命中 0.04 / 未命中 2.0 / 输出 8.0。

- **单回合(含修复)**:ADR-033 读数(n = 1014 回合)平均 **¥0.0089**。复核:主调用 prompt 4524.8 × 命中率 43.2% →
  命中 1954 / 未命中 2571、completion 402.2 ⇒ (1954×0.04 + 2571×2.0 + 402.2×8.0) / 10⁶ = ¥0.00844;
  修复 17.3% × (397.8×2.0 + 263.7×8.0) / 10⁶ = ¥0.00050;合计 ¥0.0089,与 ADR-033 一致。
- **单回合上界**:把 prompt 换成附录 B 末回合最大的 5884(108 回合《寻常》局;同命中率)⇒ ≈ **¥0.0105**。修仙等世界的 prompt 低于《寻常》(附录 B 中位数约 3.5k–4.8k 对 5.4k–5.7k),整体应接近平均值。
- **world-gen**:⚠️ **仓库里没有 world-gen 的 token 读数**(ADR-033 轨迹组不含 world-gen;ADR-016 只写「~¥0.01/发」且是旧单价)。
  本 ADR 按**假设**给上界:prompt 8k 全未命中(ADR-016:跨局命中 0)+ completion 4k ⇒ ¥0.048/发;再含一次修复 ⇒ **≤ ¥0.10/发**。
  首局开出来后看 server 日志 `[world-gen] usage` 行核对,偏离就先停。
- **整套**(推荐配比,见决策 1):基线按各槽位上限 600 回合(修仙 60+60+120、规则怪谈 60、融合 60、《寻常》120×2)+ 7 次 world-gen:
  - 按平均:600 × 0.0089 + 7 × 0.048 ≈ **¥5.7**(《寻常》若按 45–55 回合自然结局,实际约 450–470 回合,≈ ¥4.3–4.5);
  - 按上界且补局用满(4 次补局;被补掉的那局按「不到目标一半」计,最多约 60 回合):(600 + 240) × 0.0105 + 11 × 0.10 ≈ **¥9.9**。
  - 上界估算 ¥9.9 贴着原推荐的 ¥10 闸:¥10 会在最坏情况下先拦下最后的《寻常》槽位,而《寻常》长尾正是放宽到 120 要测的,
    故闸放到 **¥12**(Felix 2026-11-22)。闸只是封顶,常规估算约 ¥5–6;若真撞上,按决策 1 的执行顺序停在当前槽位、剩余不跑,并标「预算闸停」。
- **成本上限 ¥12**(原推荐 ¥10,Felix 2026-11-22 改),用勘察 2 的 `AIUNIVERSE_QUOTA_DAILY_BUDGET_CNY=12` 在同一进程内硬兜(闸按同一单价计账,与本估算同口径)。
- 时长参考:ADR-033 回合 `durMs` p50 4.1 s / p90 5.7 s ⇒ 600 回合约 40–60 分钟,外加 world-gen。

### 勘察 6 · `deepseek-v4-flash` 的上下文窗口:**1M**(官方文档)

- **出处**:<https://api-docs.deepseek.com/quick_start/pricing/>(校勘 2026-11-22 查阅,云端可达)。
  Felix 本机的 WebFetch 与内置浏览器当日均因 TLS 证书链不受信(`unable to get local issuer certificate` /
  `ERR_CERT_AUTHORITY_INVALID`)打不开 —— 照记,出处以云端查阅为准。
- **页面原文要点**:`deepseek-v4-flash` 为旧名,仍受理但已退役,由当前型号 `deepseek-flash` 应答并按 Flash 计价;
  MODEL VERSION = DeepSeek-V4.1-Flash-1017;CONTEXT LENGTH = **1M**。生产配置 `model: deepseek-flash`,与之一致(F-036 同一事实)。
- 历史:`application.yml` 的 `max-context: 1000000` 写于 Phase 1 骨架(`adbcfd6`),未注明出处,F-036 之后没有复核过;
  现与官方 1M 一致,但它本身仍不是出处。
- **占用**:附录 B 里末回合 prompt 最大 = 108 回合《寻常》局的 5884 token ⇒ 5884 / 1,000,000 ≈ **0.59%**
  (300 回合修仙局 5689 ≈ 0.57%)。
- ⇒ 层 4 第一项「上下文占用达预算 60–70%」**2026-11-22 测得未命中**。
  长局问题不是「装不下」,是「装进去的东西里没有」(背景第一节)。

### 勘察 7 · ⚠️ 与简报前提不符:「同种子可重跑」不等于「可逐字重现」

简报目标写「同种子、同世界、同回合数可重跑」。勘察发现两处随机性在脚本控制之外:

1. **world-gen 场景种子**由服务端 `ThreadLocalRandom` 从种子池抽(`WorldGenPromptBuilder.pickSeed` / `pickFusionSeed`,`:1041–1056`),HTTP 无法指定。
   ⇒ 「同世界」靠重开做不到。
2. **采样温度固定 0.7**(`OpenAiCompatLlmClient.TEMPERATURE`)。同一 prompt 两次的叙事与选项不同;T1 之后选项集就分叉,
   同一个选项种子抽出的是「同一串下标」,不是同一串选择。

**本 ADR 不自行调和**,给出可选口径(待审 1):

- (i) 接受「同配置可重跑、不可逐字重现」—— 前后对比改为**统计口径**(同配比、同回合数、多局);
- (ii) **冻结 T0 存档**(推荐):init 之后立刻把 `<store>/<saveId>.json`(turn 0,init 无条件落盘)拷进套件目录。
  以后对比时把它放进一个新的空 store 目录、起 server,现有启动回载(ADR-015)会把它当续局读回来,脚本从 `GET /state` 接着玩 ——
  **世界(规则 / 人物 / 结局池)逐字节相同**,只有回合生成仍随温度变化。不改任何格式,只拷文件。
  代价:续局没有 `openingNarrative`(transient),稿件里的 T0 取原始 init 响应。

## 候选方案

### 方案 A:Python 脚本走 HTTP 驱动本机 server(本 ADR 采纳)

照 `drive.py` 先例:本机起 server(真 key),脚本 `POST /init` + 逐回合 `POST /turn`,读 SSE,每回合前后各 `GET /state` 一次。

**优点**:
- 走的是玩家走的那条路:守卫链、`ClientProjection`、服务端改写(《寻常》出口、收束钳制、结局核对)、落盘、轨迹全部原样生效 —— 测到的就是线上那套行为。
- 轨迹由同一份代码写出,与线上同格式,现成离线工具照读(勘察 3)。
- 零生产代码改动;只用现成 env。stdlib-only(同 `drive.py`,不引 `requests`)。

**缺点**:
- 要起一个 server 进程;闸门要用 env 放宽(勘察 2)。
- 降级标记只在轨迹里,稿件要事后与轨迹对齐(勘察 4)。

### 方案 B:测试侧 `exec:java` 进程内装配 `EventLoopService` / `GameInitService` 直接调

**优点**:
- 不起 HTTP;可直接拿到 `parsed`、降级原因等内部值。

**缺点**:
- 绕过 controller:游标 / 合法性 / 准入守卫不在路径上;要手工装配真 LLM 客户端、`TraceSink`、`QuotaService`,装配本身就是一份要维护、会漂移的「第二套生产装配」。
- 勘察 1 没有发现 HTTP 驱动的硬障碍,B 没有非它不可的理由。**排除**。

### 方案 C:读线上原始轨迹

**排除**:Felix 已定不读(W-11 不动);且不可重跑,无法当前后对比基线。

### 选项策略的候选(决策 2 用)

- **带种子均匀随机(采纳)**:可复现(同种子同下标流)、不带偏好,覆盖面广。
- 关键词启发(`drive.py` 的 `danger`):偏向驱死,与长局目标相反。排除。
- 恒选第一个(`drive.py` 的 `first`):依赖模型的选项排序习惯,系统性偏差。排除。
- 让 LLM 扮演玩家:多一路调用、多一份成本与不可复现性,且把「玩家记不记得」混进「模型记不记得」。排除。

## 最终决策

**方案 A — Python HTTP 驱动 + 测试侧成稿工具**,以下各项均为推荐值,**待 Felix 审**。
⚠️ 回填(2026-11-22;上句原文不动):以下各项 Felix 已认可、改记已决(见待审清单 2–6);整套成本上限由 ¥10 改为 ¥12。

### 1. 世界与局数(待审 2)

按线上 > 30 回合 19 局的分布(修仙 11 / 寻常 5 / 规则怪谈 2 / 克苏鲁 1)配比,**槽位按表中自上而下的顺序执行**:

| 槽位 | 请求 | 目标回合 | 理由 |
|---|---|---|---|
| xiu-1 | `{"archetype":"cultivation"}` | 60 | 修仙占长局 11/19 |
| xiu-2 | 同上 | 60 | |
| xiu-long | 同上 | **120** | 线上 > 100 回合有 7 局;取 120 而非 300:成本与可读性 |
| gui-1 | `{"archetype":"rules_creepy"}` | 60 | |
| rong-1 | `{"archetypes":["cultivation","rules_creepy"]}`(识海遗蜕) | 60 | 见下 |
| xun-1 | `{"archetype":"life_sim"}` | **到自然结局为止,上限 120** | 见下 |
| xun-2 | 同上 | 同上 | |

- **《寻常》不硬定回合数**:它是一生制,`LifeStageTables.ORDINARY_LIFE` 第 44 回合起进「末段」(「本回合比上一回合晚一天到数日」,
  `LifeStage(44, Integer.MAX_VALUE, …)` 没有上界)。故目标写「到自然结局,上限 120」(校勘代定 2026-11-22,Felix 审 ADR 时可推翻);
  第 36 回合(暮年)之后的结局算「完成」不算「提前结局」。
  上限取 120 的理由:线上 > 30 回合的《寻常》是 108 / 59 / 48 / 48 / 35(附录 B),**长尾正是要测的**;
  上限只是封顶,自然结局多在 ADR-020 记的 45–55 回合,一般碰不到。
- **执行顺序写死**:xiu-1 → xiu-2 → xiu-long → gui-1 → rong-1 → xun-1 → xun-2(《寻常》长度最不确定、单回合 prompt 最大,排最后)。
  闸门中途拦下(开局 429 或回合 SSE `quota_exceeded`)时,当前局停、剩余槽位不跑,在 `summary.json` 里标「预算闸停」。
- **融合选识海遗蜕**:host 修仙,与长局主力同宿主;ADR-013 有 51 回合通关先例。**不选《验讫》**:它按回合注入时段,第 24 回合终仪、
  第 25 回合起要求给出结局(CONTEXT §三.16 (9)),到不了 60。人防可作备选。
- 克苏鲁(1/19)本轮不放,控成本;需要时加一槽。
- 合计基线(按各槽位上限)600 回合 + 7 次 world-gen,整套成本上限 **¥12**(原推荐 ¥10,Felix 2026-11-22 改;理由见勘察 5)。

### 2. 选项策略(待审 3)

- 每槽位 `rng = random.Random(f"{suiteSeed}:{slot}:{attempt}")`,每回合 `rng.randrange(len(候选))` 均匀抽。
- **候选集 = 本回合选项去掉 `X`**(《寻常》「就到这里」);《寻常》不让脚本主动收尾,让它走到自然结局。
- **提前结局 / 触底**:记下结局回合与结局 id(稿件头部写明),该局照样保留、照样成稿;
  若「结局回合 < 目标回合的一半」(60 档 < 30、120 档 < 60;《寻常》< 36),用 `attempt + 1` 换种子补一局。
- **补局上限**:每槽位最多 2 次、整套最多 4 次;用尽仍短的,保留最长那一局并在汇总里标「未达目标」,不再补。
- world-gen 失败(502 / 400)重试同一 attempt,最多 2 次,不计补局;409 `turn_stale` → `GET /state` 重同步后照同一 rng 状态继续;
  SSE `error` 事件(`quota_exceeded` / `busy`)→ 该局停,写明原因。

### 3. 玩家可见稿格式(待审 4)

每局一个 Markdown 文件,**不含 saveId**(用槽位名),字段来源按勘察 4:

```markdown
# <槽位> · <world.title>(<world.subtitle 若有>)
- 世界:cultivation | 选项种子:<suiteSeed>:<slot>:<attempt> | commit:<轨迹 commit> | 录制日期:YYYY-MM-DD
- 结局:T<n> <ending.title>(<ending.id>)/ 未结局,停于 T<n>
## 世界(视图 3)
<background / tone / 人物 traits·inventory / 规则 content 列表(无 isTrue、无 hiddenLogic)>
## T0 开场
<openingNarrative>

## T<t>
- 模型可见原文的回合:T<max(1,t-4)>…T<t-1>(T0 开场叙事不在 log 里;t = 1 时为「无」)
- 上回合 timeline:「<pre.state.timeline>」
- 本回合选项:A <text>(<hint>) / B … / …
- 所选:<id>
- 降级:否 | 是(<degradeReason>)
<SSE 叙事全文>
- 落账:<轴中文名> <前> → <后>;…
- 本回合写下的 timeline:「…」
```

「模型可见原文的回合 = t-4…t-1」已按源码核实:提示词构建时 `engine.turn() = t-1`,`snapshot()` 取 log 末 4 条,
条目回合号 = `apply` 自增之后的值,即 T(t-4)…T(t-1)。

### 4. 产物放哪(待审 5:玩家可见稿入不入库)

- **仓库之外**(Felix 本机):`$HOME/aiuniverse-selfplay/<suiteId>/` 下 `store/`(存档,视图 1)/ `traces/` / `t0/`(冻结 T0 存档)/
  `http/`(每回合 SSE 原文 + `/state` 响应的 JSONL)/ `drafts/`(玩家可见稿)/ `summary.json`(每槽位:attempt、结局回合、降级回合数、usage 合计)。
- **推荐入库**:`docs/long-memory-samples/<suiteId>/` 只放 `drafts/*.md` + 一份不含 saveId 的 `summary.md`。理由:勘察底稿与面试复习都要能引用到行;
  稿件是视图 3 口径,与玩家屏幕同级。入库前必须过两道扫描,**任一命中即不复制、非零退出、只报类别与计数**(同 ADR-033 口径):
  1. **敏感形态**:`FixtureScanner.PATTERNS` 同组(sk- key / api key 字段名 / authorization / bearer / IPv4 / IPv6 / email),
     literal 加上本套所有 saveId;
  2. **隐藏字段**:字段名 `LeakDetector.LEAK_TOKENS`(`isTrue` / `hiddenLogic` / `isCorrect` / `groundTruth`),
     外加从 T0 存档(视图 1)取出的每条 `hiddenLogic` 原文作 literal(逐字照抄即命中)。
     ⚠️ 这只抓逐字照抄;改写式泄露抓不到(同 CONTEXT §三.9 对 `detect_leak` 的定性)。
- 不入库的备选:稿件也只留在仓库外,勘察底稿只引用「槽位 + 回合号」。

### 5. 工具形态

- `bakeoff/selfplay/selfplay.py`(stdlib only):开局、驱动、冻结 T0、写 `http/` 与 `summary.json`;输出根目录在仓库内则拒跑。
  另支持 `--resume-t0 <dir>`:对冻结 T0 续玩(勘察 7 (ii))。
- 测试侧 `exec:java` 成稿工具(形态照 `DataStatsTool` / `TraceRecheckTool`,不进生产装配):读 `http/` + 轨迹(`TurnTraceCodec`),
  按勘察 4 对齐成稿,复核叙事一致性,跑第 4 节两道扫描(直接调 `FixtureScanner.scan`,不在 Python 里另抄一份正则 —— 抄一份就会漂)。
  输入输出路径都过 `RepoPathGuard.requireOutsideRepo`;入库那一步由 Felix 手动 `cp`。

### 6. 谁跑

server 与脚本**都由 Felix 在本机跑**(会花钱、key 在他终端)。命令见「运行方式」,一行一条。

### 关键理由

1. 走玩家那条路,测到的就是线上行为;轨迹同格式,现成离线工具链(ADR-031 / ADR-033)直接复用。
2. 闸门不绕、只放宽,且把日限额闸**反过来用作整套成本上限**:超了服务端自己拒,不靠脚本自律。
3. 冻结 T0 存档用的是 ADR-015 的现成回载,让「加记忆账本前后」至少世界逐字节相同 —— 不改一行格式。
4. 扫描复用 `FixtureScanner` 本体而非另抄正则,避开「两份规则漂移」这一本仓库已摔过的坑。

## 已知代价

1. **不可逐字重现**(勘察 7):温度 0.7 + 服务端种子池,同种子只保证同下标流。缓解方式:冻结 T0(世界相同)+ 统计口径对比;
   要逐字可比得动提示词或客户端温度,属本 ADR「不做」。
2. **均匀随机不像真人**:真人会追线索、回访人物,随机选择回访率更低,可能**低估**跨窗口引用的需求、**高估**伏笔断线的严重度。
   缓解方式:判读口径(下一步)按「玩家选了与旧线索相关的选项」分层统计;本 ADR 只记这个偏差。
3. **样本小**:7 局对 19 局线上长局,配比只能粗略对齐。
4. **world-gen 成本是假设值**(勘察 5)。缓解方式:首局后看 `[world-gen] usage` 日志;再有 ¥12 硬闸兜底。
5. **日限额闸只在一个进程内成立**(日账在内存)。缓解方式:运行方式写死整套一个进程跑完;中途必须重启时,剩余预算自己算。
6. **commit 字段靠命令行注入**:漏了 `-Dgit.sha` 轨迹就是 `unknown`。缓解方式:脚本开跑前 `GET /actuator/info` 读 `build.commit`,为 `unknown` 拒跑。
7. **上下文窗口出处来自云端查阅**(勘察 6):Felix 本机当日打不开官方文档,1M 与 0.57% 依赖校勘 2026-11-22 的查阅;
   官方改窗口或改型号映射时这一条要重查。
8. 本机与线上的差别:单进程默认 profile、无 pg、无 Fly 反代;对回合生成本身无影响,对并发 / 持久化路径的结论不外推。
9. **仓库公开,稿件入库即公开**(待审 5 代定入库):两道扫描只拦敏感形态与逐字隐藏字段,拦不住改写式泄露,也拦不住「这一局写了什么」本身。
   缓解方式:入库那一步由 Felix 手动 `cp`,复制前通读;不想公开的套件只留仓库外。

## 重新审视的触发条件

- 判读口径落地后发现均匀随机让「跨窗口引用」样本过少(例如每局有效回指候选 < 5 处):改选项策略(如按种子偏向含旧专名的选项)。
- 加记忆账本(层 4 解冻)时:用冻结 T0 + 同种子重跑本套,作为前后对比;若 `LOG_KEEP` / `compressLog` / 提示词结构变了,稿件的「可见窗口」标注规则要同步改。
- 线上长局分布明显变化(例如 > 30 回合局里某世界占比翻倍,或出现 > 300 回合局)。
- 单价或 provider 变更(F-036 类事件),或 world-gen 实测 usage 偏离本 ADR 假设一倍以上。
- 官方上下文窗口或 `deepseek-v4-flash` → `deepseek-flash` 的型号映射变化(勘察 6 重查;占用口径重算)。
- **记忆账本若改存档字段**:冻结的旧 T0 存档必须能被新版回载(缺字段按空账本起);做不到则这套基线作废、
  按新版重录 T0,并在提交说明里写明。

## 运行方式(Felix 本机;一行一条)

终端 1 · server(在仓库根目录开始):

```bash
cd server
```
```bash
read -s DEEPSEEK_API_KEY
```
```bash
export DEEPSEEK_API_KEY
```
```bash
export SP="$HOME/aiuniverse-selfplay/$(date +%Y%m%d)-a"
```
```bash
mkdir -p "$SP/store" "$SP/traces"
```
```bash
export AIUNIVERSE_LLM_ACTIVE=deepseek-v4-flash
```
```bash
export AIUNIVERSE_SESSION_STORE_DIR="$SP/store"
```
```bash
export AIUNIVERSE_TRACE_DIR="$SP/traces"
```
```bash
export AIUNIVERSE_QUOTA_DAILY_BUDGET_CNY=12
```
```bash
export AIUNIVERSE_QUOTA_INIT_PER_KEY_DAILY=20
```
```bash
export AIUNIVERSE_QUOTA_TURNS_PER_KEY_DAILY=1000
```
```bash
./mvnw -Dgit.sha="$(git rev-parse --short HEAD)" spring-boot:run
```

终端 2 · 脚本(实现落地后;参数名以实现为准):

```bash
python3 bakeoff/selfplay/selfplay.py --out "$HOME/aiuniverse-selfplay/$(date +%Y%m%d)-a" --suite-seed 20261122 --plan default
```

成稿与扫描(实现落地后):

```bash
./server/mvnw -q -f server/pom.xml test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.aiuniverse.server.eventloop.SelfPlayDraftTool -Dexec.classpathScope=test -Dexec.args="--in $HOME/aiuniverse-selfplay/20261122-a --out $HOME/aiuniverse-selfplay/20261122-a/drafts"
```

⚠️ 订正(刀 2,2026-11-22;上一条原文不动):实现只收 `--in`,输出固定为 `<套件根目录>/drafts/`(已存在则拒,不覆盖);命令改为:

```bash
./server/mvnw -q -f server/pom.xml test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.aiuniverse.server.eventloop.SelfPlayDraftTool -Dexec.classpathScope=test -Dexec.args="--in $HOME/aiuniverse-selfplay/20261122-a"
```

排障 · 开局连续 502、server 日志里没有任何 `usage` 行(2026-11-23 冒烟实测):Felix 家里的网络下,本机 server 调 DeepSeek 时
TLS 握手失败(world-gen 日志 `cause=unable to find valid certification path to requested target`),换手机热点后正常。
这时请求没到模型、**没有花钱**。**不建议、也不提供关闭证书校验的办法**;换网络再跑。

要看什么:首局开出来后 server 日志 `[world-gen] usage` 一行(核成本假设)、轨迹首行 `commit` 不是 `unknown`、
`summary.json` 里每槽位的结局回合与降级回合数;跑完把终端最后几行(只有数字)贴回来。

## 实施步骤

1. 本 ADR 审定(待审清单逐条回填)。
2. 刀 1:`bakeoff/selfplay/selfplay.py` + 纯离线单测(桩 HTTP server 回放一段录好的 SSE;不碰真 key)。
   ⚠️ 订正(校勘 2026-11-22;上句原文不动):「回放一段录好的 SSE」改为「**SSE 由测试代码合成**」——
   不读 `bakeoff/recordings/` 里的真实录制(刀 1 已照此实现,见 `bakeoff/selfplay/test_selfplay.py`)。
3. 刀 2:测试侧 `SelfPlayDraftTool` + 两道扫描 + 守护测试(仓库内路径拒跑、命中不写、控制台不泄 saveId —— ADR-033 摔过)。
4. 刀 3:Felix 本机按「运行方式」跑一套;回填「实际效果」;稿件是否入库按待审 5。
5. 判读口径与勘察底稿另起(不在本 ADR)。

## 不做

- 不改引擎 / 提示词 / 存档或轨迹格式 / 任何生产默认值;不加 API 让客户端指定温度或 world-gen 种子。
- 不读线上原始轨迹(W-11)。
- 不定判读口径(矛盾、当生人重介绍、伏笔断线、正确回指)。
- 不做记忆账本本身。

## 实际效果(事后补充)

*刀 3 第一套跑完时回填:每槽位实际回合数与结局回合、补局次数、降级率;world-gen 实测 usage 与本 ADR 假设的偏差;
整套按配置单价估算的成本 vs ¥5.7–9.9 的估算;轨迹 `commit` 字段;两道扫描命中数。*

### 冒烟(Felix 2026-11-23,本机,手机热点网络;commit `5a15778`;`--plan smoke` = 1 局 5 回合不补局)

金额一律**按配置单价估算**(勘察 5 同口径),不是实际花费。全套跑完另回填下一节。

- **成稿**:对齐失败 0、叙事不一致 0;两道扫描无误伤(未拦下正常稿件)。
- **world-gen**:一次成功调用 prompt 1691 / completion 1487 token,约 ¥0.013。冒烟里开局共 3 次调用
  (1 次修复后仍失败 → 502 → 重试成功),合计约 ¥0.048。
  ⚠️ 补充(刀 3 回填,2026-11-23;上两行原文不动):「约 ¥0.013」拆开核对 —— 那次成功开局 prompt 1691 = cacheHit 1408 + cacheMiss 283,
  completion 1487 ⇒ (1408 × 0.04 + 283 × 2.0 + 1487 × 8.0) / 10⁶ = **¥0.0125**,与「约 ¥0.013」一致;输出占其中 95%。
  勘察 5 的 world-gen 假设(¥0.048/发、上界 ¥0.10/发)偏保守,**保留不改**。
- **回合**:5 回合 + 2 次回合修复约 ¥0.034。**整次约 ¥0.08。**
- **轨迹 `commit`** = `5a15778`:`-Dgit.sha` 在 `spring-boot:run` 下生效(勘察 3 那条「未实测」改为已验证)。
- **观察(样本太小,不下结论)**:5 回合里 2 回合触发修复;开局 2 次里 1 次修复后仍失败。
- 冒烟暴露并已修(刀 2 修正):成稿工具读 `title` / `subtitle` / `background` / `tone` 读错了层(它们在 `InitResponse.world`
  的 `world` 子对象下),真实稿件里三项全空;叙事段末尾多空行;`selfplay.py` 在槽位「开局失败」时仍写「完成」、退出码 0
  (冒烟第一次:3 次 502 → 开局失败)—— 现写「完成(有失败槽位 n)」、退出码 5。

### 第一套 `20261123-a`(Felix 2026-11-23,本机;commit `5a15778`;种子 `20261122`;`--plan default`)

金额一律**按配置单价估算**(勘察 5 同口径),**不是实际花费**;数字由校勘按 Felix 贴回的整份 server 日志算出。
稿件入库于 [`docs/long-memory-samples/20261123-a/`](../long-memory-samples/20261123-a/README.md)(Felix 通读确认可公开)。

- **整体**:7 局 **571 回合**;补局 0;降级 2 回合(0.35%);修复 42 / 571(7.4%;ADR-033 读数 17.3%);对齐失败 0;叙事不一致 0。
- **轨迹 `commit`** = `5a15778`(稿件头与 `summary.md` 同值)。
- **两道扫描**:成稿工具写出了全部 7 份稿件(命中即不写)⇒ 0 命中;入库后在仓库内复扫 UUID 形态 / `sk-` 形态 /
  `hiddenLogic` / `isTrue` 字段名(另加 `isCorrect` / `groundTruth`、32 位十六进制、saveId / bearer / authorization / api key 字样)⇒ 0 命中。
- **每局**:

  | 槽位 | 回合 | 结局 | 修复 | 降级 | 估算成本 |
  |---|---|---|---|---|---|
  | xiu-1 | 60 | 未结局 | 5 | 0 | ¥0.40 |
  | xiu-2 | 60 | 未结局 | 9 | 0 | ¥0.44 |
  | xiu-long | 120 | 未结局 | 14 | 1 | ¥0.83 |
  | gui-1 | 31 | **T31「理智崩解」** | 3 | 0 | ¥0.19 |
  | rong-1 | 60 | 未结局 | 11 | 0 | ¥0.50 |
  | xun-1 | 120 | 未结局(到上限) | 0 | 1 | ¥1.29 |
  | xun-2 | 120 | 未结局(到上限) | 0 | 0 | ¥0.94 |

  gui-1 结局回合 31 ≥ 目标一半 30,按决策 2 不补局。xun-1 与 xun-2 回合数相同、修复都是 0,成本差 ¥0.35,**原因未查**。
- **world-gen**:7 次**全部首次成功**(0 修复、0 重试),合计 ¥0.10(≈ ¥0.014 / 发)。
  勘察 5 假设 ¥0.048 / 发,实测低于假设一倍以上 —— 按「重新审视的触发条件」第 4 条字面已触发,但方向是偏低,
  作为上界仍成立;是否改假设值由 Felix 定(本次不改)。
- **整套 ≈ ¥4.68**(回合 + world-gen;按每局四舍五入后相加为 ¥4.59 + ¥0.10,差值是舍入)。对照勘察 5:按平均估算 ¥5.7、上界 ¥9.9;
  ¥12 闸未触发。回合部分 ≈ ¥4.58 / 571 ≈ **¥0.0080 / 回合**(ADR-033 平均 ¥0.0089)。
- **主调用 prompt token,首回合 → 末回合**:修仙 2653–2953 → 3570–3924;融合(识海遗蜕)3596 → 4740;
  《寻常》4688–4698 → 5945–5948(全程最大 6040)。规则怪谈一局未单列。
  最大 6040 / 1,000,000 ≈ 0.60%,与勘察 6 的线上读数(0.59%)同一量级 —— 层 4 第一项仍是未命中。
- ⚠️ **计划外发现 · 两局《寻常》末段没有出口**(记 [`bakeoff/FINDINGS.md`](../../bakeoff/FINDINGS.md) **F-042**,未处理,待 Felix 定):
  - **事实**:两局从 T44 进末段(`LifeStageTables.ORDINARY_LIFE` 第 44 回合起)后,server 日志每回合一条 WARN
    「收束阶段气力 … 低于下限 15,已钳制(§8 兜底)」,合计 153 条(两局 T44–T120 各约 77 条);稿件里两局 T44–T120 共 77 回合,
    落账气力**每回合都是 15**。此后再无结局,两局都跑到上限 120。出口选项「X 不等了」在末段 77 回合每回合都在,脚本从不选它(决策 2 排除 `X`)。
  - **推测(未证实)**:均匀随机排除了出口 X;气力被下限 15 兜住、到不了 ≤ 0 的触底;模型也没有自己提议结局 ⇒ 末段对这套选项策略没有出口。
    线上 108 回合的《寻常》局(附录 B)是否同因,**未证实**(W-11 不读线上原始轨迹)。
  - 对本测量的影响:《寻常》T45–T120 是末段原地打转,勘察底稿把两局按 T1–T44 / T45–T120 分开统计。

## 待 Felix 审定清单

1. **可重现口径**(勘察 7):(i) 统计口径 / (ii) 冻结 T0 存档(推荐)。
   ⚠️ **回填(Felix 2026-11-22 定)**:**(ii) 冻结 T0 存档。** init 后立刻把 turn 0 存档拷进套件目录 `t0/`;
   前后对比时放进新的空 store 目录、经 ADR-015 启动回载续玩,世界逐字节相同、回合生成仍随温度变化
   (已知代价 1 照旧成立)。脚本 `--resume-t0` 与 `t0/` 目录随之从「推荐」转为必做(决策 4 / 5)。
   ⚠️ **已决(Felix 2026-11-22)· 理由**:冻结 T0 只锁世界,回合生成仍随温度,对比本就是统计口径;
   (ii) 在 (i) 之上去掉「世界不同」这层方差,同样局数下对比更站得住。配套重新审视条件见上(记忆账本改存档字段)。
2. **世界与局数**:修仙 3(60 / 60 / 120)、寻常 2(到自然结局,上限 60)、规则怪谈 1(60)、融合识海遗蜕 1(60);整套上限 ¥10。
   ⚠️ **校勘代定 2026-11-22**:照本 ADR 推荐值,Felix 审 ADR 时可推翻。
   其中《寻常》上限改为 **120**(理由见决策 1);执行顺序 xiu-1 → xiu-2 → xiu-long → gui-1 → rong-1 → xun-1 → xun-2,闸停则剩余不跑。
   ⚠️ **已决(Felix 2026-11-22 认可校勘代定)**:配比、目标回合与执行顺序照上。**整套上限 ¥10 → ¥12**(Felix 2026-11-22):上界估算 ¥9.9 贴闸,¥10 会在最坏情况先拦下最后的《寻常》槽位,
   而《寻常》长尾正是放宽到 120 要测的;闸只是封顶,常规估算约 ¥5–6。
3. **选项策略**:带种子均匀随机,排除 `X`;结局回合 < 目标一半则补局,每槽位 ≤ 2、整套 ≤ 4。
   ⚠️ **校勘代定 2026-11-22**:照本 ADR 推荐值,Felix 审 ADR 时可推翻。
   ⚠️ **已决(Felix 2026-11-22 认可校勘代定)**:照决策 2。
4. **稿件格式**:第 3 节模板(含「可见窗口」与「上回合 timeline」两行)。
   ⚠️ **校勘代定 2026-11-22**:照本 ADR 推荐值,Felix 审 ADR 时可推翻。
   ⚠️ **已决(Felix 2026-11-22 认可校勘代定)**:照决策 3 模板。
5. **玩家可见稿是否入库**:入 `docs/long-memory-samples/<suiteId>/`(推荐,先过两道扫描)/ 不入库。
   ⚠️ **校勘代定 2026-11-22**:照本 ADR 推荐值,Felix 审 ADR 时可推翻。 即:入库,先过两道扫描。已知代价 9:仓库公开,稿件入库即公开。
   ⚠️ **已决(Felix 2026-11-22 认可校勘代定)**:入库,先过两道扫描;入库那一步由 Felix 手动 `cp`、复制前通读(已知代价 9)。
6. **闸门放宽值**:日限额 ¥10、单 key init 20、单 key 回合 1000(只在本机进程 env;生产默认不动)。
   ⚠️ **校勘代定 2026-11-22**:照本 ADR 推荐值,Felix 审 ADR 时可推翻。
   ⚠️ **已决(Felix 2026-11-22 认可校勘代定)**:**日限额改 ¥12**(理由同待审 2)、单 key init 20、单 key 回合 1000;只在本机进程 env,生产默认不动。
7. **附录 A 脚本原文**:简报要求逐字放入,但简报未附原文 —— 待 Felix 贴入。
   ⚠️ **已补(2026-11-22)**:附录 A(脚本原文与调用行)、附录 B(原始输出数字行)逐字补入,见 `c5bfd0a`。

## 跟其他文档的交叉引用

- **起源**:[career backlog](../backlog-career-track.md) 层 4「分层记忆 / RAG」(本 ADR 是其解冻测量)。
- **轨迹与离线工具**:[ADR-031](ADR-031-turn-execution-trace-and-offline-replay.md)(轨迹格式、`TraceReplayer`)、
  [ADR-033](ADR-033-data-stats-definitions-and-readonly-tool.md)(每回合 token / 成本读数、`exec:java` 工具形态、控制台不泄 saveId)。
- **闸门**:[ADR-016](ADR-016-cost-gate.md)(成本闸)、[ADR-022](ADR-022-turn-admission-and-rejection-semantics.md)(回合准入)、
  [ADR-030](ADR-030-init-admission-and-world-gen-deadline.md)(开局准入与段时限)。
- **回载**:[ADR-015](ADR-015-overseas-deployment-form-factor.md)(启动回载,冻结 T0 依赖它)。
- **被测对象**:`server/src/main/java/com/aiuniverse/server/engine/Engine.java`(`LOG_KEEP` / `compressLog` / `snapshot`)、
  `eventloop/TurnPromptBuilder.java`、`eventloop/EventLoopService.java`(`buildDelta` / `appendLifeExitAction`)。
- **先例脚本**:`bakeoff/recordings/world-gen-smoke/drive.py`。
- **世界约束**:[ADR-020](ADR-020-lifetime-world-family-and-ordinary-life.md)(《寻常》一生制长度)、
  [ADR-035](ADR-035-third-fusion-combo-yanqi.md)(《验讫》按回合收束,故不入选)。
- **扫描**:`server/src/test/java/com/aiuniverse/server/eventloop/FixtureScanner.java`、`engine/LeakDetector.java`。

## 附录 A · 线上计数脚本原文(逐字)

Felix 2026-11-22 在本机对 `/data/traces` 只读拷贝运行的就是下面这段;调用方式 = 第一行。

```
python3 -I - ~/wanjie-stats/traces-1122 <<'PY'
import json, sys, os, statistics as st
d = sys.argv[1]
files = sorted(f for f in os.listdir(d) if f.endswith('.trace.jsonl'))
buckets = {'01-10':0,'11-20':0,'21-30':0,'31-50':0,'51-100':0,'101+':0}
rows, bad, partial, calls = [], 0, 0, {}
for f in files:
    turns = []
    try:
        with open(os.path.join(d, f), encoding='utf-8') as fh:
            for line in fh:
                if not line.endswith('\n'):
                    partial += 1; break
                turns.append(json.loads(line))
    except Exception:
        bad += 1; continue
    n = len(turns)
    if n == 0: continue
    k = '01-10' if n<=10 else '11-20' if n<=20 else '21-30' if n<=30 else '31-50' if n<=50 else '51-100' if n<=100 else '101+'
    buckets[k] += 1
    if n <= 30: continue
    pt = []
    for t in turns:
        u = t.get('usage') or []
        for c in u: calls[c.get('call')] = calls.get(c.get('call'), 0) + 1
        v = (u[0].get('usage') or {}).get('promptTokens', -1) if u else -1
        pt.append(v)
    pre = turns[0].get('pre') or {}
    arch = '×'.join((pre.get('world') or {}).get('archetypes') or ['?'])
    last = (turns[-1].get('pre') or {}).get('state') or {}
    deg = sum(1 for t in turns if t.get('path') == 'degraded')
    ok = [p for p in pt if p >= 0]
    rows.append((n, arch, deg, ok[0] if ok else '-', int(st.median(ok)) if ok else '-', ok[-1] if ok else '-', len(last.get('logSummary') or '')))
print(f'轨迹文件 {len(files)}(无法解码 {bad},半截末行 {partial})')
print('每局已落地回合数分布:', '  '.join(f'{k}:{v}' for k, v in buckets.items()))
print('调用名计数(长局内):', calls)
print('> 30 回合的局(按回合数降序):回合 | 世界 | 降级回合 | 主调用 promptTokens 首/中位/末 | 末回合 logSummary 字符数')
for r in sorted(rows, reverse=True):
    print(f'{r[0]} | {r[1]} | {r[2]} | {r[3]}/{r[4]}/{r[5]} | {r[6]}')
PY
```

附注:脚本由校勘写,先在云端容器用合成轨迹试跑过;线上数据只在 Felix 本机跑过。

## 附录 B · 线上计数输出(只抄数字行)

原始输出数字行,逐字:

```text
轨迹文件 92(无法解码 0,半截末行 0)
每局已落地回合数分布: 01-10:48  11-20:16  21-30:9  31-50:8  51-100:4  101+:7
调用名计数(长局内): {'主调用': 1780, '修复': 236}
> 30 回合的局(按回合数降序):回合 | 世界 | 降级回合 | 主调用 promptTokens 首/中位/末 | 末回合 logSummary 字符数
300 | cultivation | 5 | 3223/4803/5689 | 2546
215 | cultivation | 5 | 3055/4389/4977 | 1781
165 | cultivation | 2 | 2999/4069/4594 | 1331
151 | cultivation | 6 | 2984/4140/4569 | 1205
119 | cultivation | 1 | 2813/3690/4016 | 917
108 | life_sim | 0 | 4699/5585/5884 | 818
108 | cultivation | 1 | 2960/3821/4113 | 818
86 | cultivation | 2 | 2941/3936/4195 | 638
79 | cultivation | 0 | 2841/3625/3801 | 582
75 | cultivation | 0 | 2823/3607/3830 | 550
59 | life_sim | 0 | 4868/5582/5616 | 422
50 | rules_creepy | 0 | 2254/2944/3112 | 350
48 | life_sim | 0 | 4907/5690/5762 | 334
48 | life_sim | 0 | 4813/5658/5869 | 334
35 | life_sim | 0 | 4783/5391/5567 | 230
35 | cultivation | 0 | 2828/3491/3582 | 230
34 | rules_creepy | 0 | 2387/2955/3053 | 222
33 | cthulhu | 1 | 2718/3327/3456 | 214
32 | cultivation | 0 | 3039/3732/3910 | 206
```
