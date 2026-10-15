# 多存档管理勘察底稿(E-3)

> 只读勘察,2026-10-15,基线 `main@51edd68`。**只写事实与问题,不写方案、不推荐。**
> 背景:[future-experience-backlog §2.3](future-experience-backlog.md)「存档系统只做了一半」三面一体
> (① 单 saveId 槽 → 开新局即丢旧局指针 / ② init 无条件落盘 → 取消也产生孤儿档 / ③ 无清理 / TTL)。
> 读数:[ADR-033](adr/ADR-033-data-stats-definitions-and-readonly-tool.md)「实际效果」报告 —— 线上只读拷贝载入 230 档,
> `turn == 0` 83 档,有效档(`turn ≥ 1`)147 档。
> ⚠️ 83 档 `turn == 0` **不全是第 ② 面的孤儿档**:存档里没有任何字段能区分「取消等待产生的」与
> 「起了局、看完开场就离开的」(见 §3.4),本底稿不给二者的比例。
>
> 行号以 `51edd68` 为准,会漂;方法名是定位的主手段。

---

## 1. 前端存档指针现状

### 1.1 localStorage 里的键

全 `web/src`(不含测试)对 Web Storage 的使用只有两个键,都走 `localStorage`;
`sessionStorage` / IndexedDB / `storage` 事件监听 **零命中**(grep `sessionStorage|indexedDB|'storage'`)。

| 键 | 值 | 读 | 写 | 清 |
|---|---|---|---|---|
| `aiuniverse.saveId` | **一个** saveId 字符串(服务端 `UUID.randomUUID()`) | `gameStore.ts` `readSavedId`(:111) | `writeSavedId`(:119),唯一调用点 = `startGame` 成功分支(:321) | `clearSavedId`(:127),调用点 = `resumeGame` 的 catch(:386)、`forgetDeadSave`(:229–230,比对后清) |
| `aiuniverse.deviceId` | 首次生成的 UUID(`crypto.randomUUID`,无则拼随机串) | `deviceId.ts` `getDeviceId` | 同上(首次) | 无清除路径 |

- 三个 helper 全部 `try/catch`,storage 不可用(隐私模式等)时 `readSavedId` 返回 null、写与清静默失败
  (`gameStore.ts` :106–108 注释:「优雅降级为『无续局入口』」)。
- `getDeviceId` 在 storage 不可用时退化为进程内随机 id,不跨刷新(`deviceId.ts` 文件头注释)。

### 1.2 `resumableSaveId`(内存)与 localStorage 的关系

- `resumableSaveId` 是 `gameStore` 状态字段(`gameStore.ts` :88–89 声明),**初值在 store 创建时读一次**
  `readSavedId()`(:293,`createGameStore` 返回对象里)。之后不再从 localStorage 重读,也没有 `storage` 事件监听。
- 刻意放在 `INITIAL` 之外:`reset()`(:462)只 `set({...INITIAL})`,不动它(注释 :466–468「返回不弃局」)。
- 写入路径:`startGame` 成功 → 同时 `writeSavedId(res.saveId)` 与 `set({resumableSaveId: res.saveId})`(:321–324)。
  世代守卫过期(`staleEpoch(mine)`)时整段丢弃,连 saveId 都不写(:319)。
- 清除路径:
  - `resumeGame` 失败(404 / 损坏 / 网络,一律)→ `clearSavedId()` **无条件** + `resumableSaveId: null`(:379–387);
    世代过期时 `return`,不清(:384)。
  - 回合流拿到 `session_not_found` → `forgetDeadSave(saveId)`(:431 → :229)。

### 1.3 ADR-022 刀 1.5「比对后清 / 无条件清」两半判据的现行代码

`gameStore.ts` `forgetDeadSave`(:229–232):

```ts
if (readSavedId() === deadId) clearSavedId();   // localStorage:比对后清(跨页签共享)
set({ resumableSaveId: null });                 // 内存:无条件清(本页签视图)
```

注释(:221–228)写明两半条件不同的理由是作用域不同。**`resumeGame` catch 里的清除不比对**(:386 直接 `clearSavedId()`);
`forgetDeadSave` 的注释(:221–223)与 `resumeGame` 的注释(:380–381)都提到「拿一次过期失败去删一个跟它无关的存档」这一形状。

### 1.4 多页签下的行为(据代码推得,未实测)

- 每个页签各有一个 store 实例,`resumableSaveId` 各自在创建时读一次 localStorage。
- 页签 2 开新局 → localStorage 改写为 B;页签 1 的 `resumableSaveId` 仍是它创建时读到的值,直到刷新
  (无 `storage` 事件监听,见 §1.1)。
- 页签 1 在回合里拿到 A 的 `session_not_found` → localStorage 比对不等(是 B)不清;页签 1 内存无条件清(§1.3)。
- 页签 1 在选择屏点「继续上局」续 A 失败 → `resumeGame` catch 无条件 `clearSavedId()`,此时 localStorage 里是 B,**B 的指针被清**
  (该路径未由刀 1.5 改动;ADR-022 刀 1.5 记为「这条路径不再喂它」而非修复,见 ROADMAP v8.3)。

### 1.5 选择屏两个入口各自依赖什么

`ArchetypeSelect.tsx`:

- **「继续上局」**(:133–138):`resumableSaveId` 非空即显,点击调 `resumeGame()`。不读 localStorage、不请求服务端。
- **「回看上局」**(:140–149):三项同时成立才显 ——
  `resumableSaveId` 非空 / `capabilities.history === true`(:59,来自 `GET /api/archetypes`)/
  `historyStore.visible[resumableSaveId] === true`(:62–64,由 `probe` 结果写入)。
  探测在 effect 里触发(:65–67)。线上默认 profile 下 `capabilities.history` 为 false(ROADMAP v16.9 部署冒烟),故该入口线上不出现。
- 两个入口都只认 **一个** saveId(`resumableSaveId`)。`historyStore` 注释(:5–7)写明它以 gameStore 的 `resumableSaveId`
  为「上局是哪一局」的真相源,由调用方传入。

---

## 2. 服务端能提供 / 不能提供什么

### 2.1 端点清单(`GameController` / `ArchetypeController`)

| 端点 | 方法 | 出处 |
|---|---|---|
| `POST /api/game/init` | `GameController.init`(:166) | 开局 |
| `GET /api/game/{saveId}/state` | `GameController.state`(:215) | 续局查询 |
| `GET /api/game/{saveId}/history` | `GameController.history`(:241) | 叙事历史(仅 `pg` 有实现) |
| `POST /api/game/{saveId}/turn` | `GameController.turn`(:313) | 回合 |
| `GET /api/archetypes` | `ArchetypeController` | 目录 + fusions + capabilities |

- 全仓 `@DeleteMapping` / `@PutMapping` **零命中**;没有按设备 / IP / 用户列出存档的端点。
- 所有按存档的端点都以路径里的 saveId 为唯一键。

### 2.2 deviceId 与客户端 IP 进不进会话和存档(复核)

- 读取:`GameController.clientKey`(:403–408)读 `Fly-Client-IP` 与 `X-Device-Id`,构造 `QuotaGate.ClientKey`。
- 消费方:grep `deviceId|ClientKey` 在 `server/src/main` 只命中 `GameController`、`TurnStateMachine`(作为参数转交配额)、
  `QuotaGate`、`QuotaService` 四个文件。**`GameSession`、`GameSessionManager`、`Engine`、`SessionDocument`、
  `FileSessionStore`、`JdbcSessionStore`、`TurnTrace` 均不含。**
- `SessionDocument.encode` 写入的键 = `Engine.toPersistedState()` + `currentActions` + `phaseHint` +(接局面层的世界)`boxScene`。
- `TurnTrace` 记录字段:`schema, saveId, turnBefore, recordedAt, commit, actionId, path, pre, parsed, degradeReason,
  streamedNarrative, promptSha256, usage, durMs, repairErrors, post` —— 无设备 / IP。
- `QuotaService.check`:设备 / IP 只作内存日计数的键(`dayCounters`,形如 `init|dev:<id>`),不落盘(月账只落 ¥ 总额)。
- 结论(事实):**E-1 勘察结论复核成立 —— deviceId 与 IP 不进会话、不进存档、不进轨迹。**

### 2.3 `GET /state` 返回什么、404 语义、能否只取摘要

- `GameInitService.resume`(:121):内存表没有 → 返回 null → controller 发 404 `{error:{code:"session_not_found"}}`,只带 code
  (`GameController.state` :215–221,ADR-022 立字 11)。
- 有 → `InitResponse(saveId, world=toClientState()(视图 3), openingNarrative="", actions=currentActions 拷贝, attributes=轴元数据)`。
  视图 3 里含 `world.title`、`archetypes`、`state.turn`、`state.status`、`state.log`(末 `LOG_KEEP=4` 条)等。
- **没有摘要形态的端点或参数**:要拿世界名 / 回合号 / status,只能取整个 `InitResponse`。
- 读的是**内存表**(`GameSessionManager.get`),不读盘;内存表由启动时 `reloadFromStore` 全量回填(:50–55)。
- 默认 profile 下:**存档里没有任何时间戳**(ADR-033 底稿 §1 已核;`SessionDocument.encode` 不写时间)。
  `pg` profile 下 `game_session` 表有 `created_at` / `updated_at`(`V1__narrative_history.sql`),线上未切库。

### 2.4 删除机制与生命周期

- 全 `server/src/main` grep `DELETE|Files.delete|deleteIfExists|sessions.remove` **零命中**。
  内存表、文件目录、`game_session` 表三处都没有删除路径。
- 内存表:`GameSessionManager.sessions`(`ConcurrentHashMap`,:34),`activeCount` 注释(:97)写「Phase 1 无淘汰,单调增」。
- 文件:每 saveId 一个 `<saveId>.json`,`FileSessionStore.persist`(:90)原子写;`loadAll`(:115)启动全量读。
  同目录还有月账 `quota-YYYY-MM.json`(ADR-016,永不删除)与 `traces/` 子目录(ADR-031,不自动删除,到限停写)。
- 轨迹:每局一个 `<saveId>.trace.jsonl`,同样无清理(ADR-031 W-7 已决「不自动删,到限停写」)。

### 2.5 saveId 是唯一凭证(只写事实)

- saveId 由服务端生成(`GameInitService.init` :82,`UUID.randomUUID()`),随 init 响应下发,客户端只存在 localStorage。
- 持有 saveId 即可 `GET /state`、`POST /turn`、`GET /history`;服务端不校验请求来自哪台设备 / 哪个 IP(§2.2)。
- 服务端没有任何「这个 saveId 属于谁」的记录 —— 存档文档、内存会话、`game_session` 表都不含归属字段。
- 因此「按某个客户端列出它的存档」在今天的数据里**没有可查询的键**;要么由客户端自己持有 saveId 集合,
  要么服务端需要先开始记录某种归属(今天不存在)。
- ADR-004 背景与 ADR-004 基线语料 §四(四个未鉴权 saveId 进公开仓库)已记「`GET /state` 无鉴权,返回视图 3」。

---

## 3. init 落盘时机与取消

### 3.1 init 调用链(`GameController.init` :166 → `runInit` :194 → `GameInitService.init` :64)

1. 容器线程:读 `ClientKey` → 建 `DeferredResult`(超时 = 2 × 段时限 + 30 s,默认 390 s)→ `initAdmission.submit`。
   准入被拒 → 503 `server_at_capacity`,**不扣额度、不调 world-gen、不建 session**(:157–158 注释,ADR-030)。
2. 池线程 `runInit`:`quota.checkInit(client)` —— **放行即计数**(`QuotaService.check`:先全查再 `bump`,:117–121)。
   拒绝 → 429,拒绝不计数。
3. `GameInitService.init`:校验 → `worldGen.generate`(失败抛 `WorldGenException` → 502,**不建 session**)→
   `UUID.randomUUID()` → `sessions.create(...)`。
4. `GameSessionManager.create`(:76):建 Engine → 放进内存表 → **`store.persist(session)`(:89)** → 返回。
5. 回到 `GameInitService.init` 组装 `InitResponse` → `result.setResult(...)` → 写响应。

**落盘发生在第 4 步,早于响应写出(第 5 步)。** 服务端不知道客户端是否还在等。

### 3.2 前端「取消」时服务端发生了什么

- `LoadingScreen`(`GameScreen.tsx` :46–59)的「取消」= `reset()`。`status === 'initializing'` 时显示,
  `startGame` 与 `resumeGame` 都进这个状态(`gameStore.ts` :316、:352),故两者共用同一个取消按钮。
- `reset()`:关回合流、`nextEpoch()`、`set({...INITIAL})`。**不发任何请求、fetch 不带 `AbortSignal`**(epoch 注释 :190–203)。
- 服务端:那次 init 请求照常跑完 —— 准入名额被占到 world-gen 结束、`checkInit` 已计数、`create` 已落盘。
  客户端回来后因 `staleEpoch` 整段丢弃,**saveId 不写进 localStorage**(:319),这一档客户端永远拿不到。
- `GameScreen.tsx` :36–45 注释与 backlog §2.3 第 ② 条已记这两笔(孤儿档 + 吃一次 init 额度)。

### 3.3 其他产生「客户端拿不到 saveId 的档」的路径(据代码推得)

- `DeferredResult` 超时(390 s):controller 回 502 `world_gen_failed`,但 ADR-030 已知代价 2 写明「超时不中断 worker」——
  worker 若之后完成,`create` 照样落盘,响应已经发出,客户端拿到的是 502。
- 客户端在 init 等待中关页 / 刷新 / 断网:同 §3.2,服务端照常落盘。
- world-gen 失败、准入拒绝、额度拒绝:**不产生档**。

### 3.4 `turn == 0` 档的构成(只列能区分什么、不能区分什么)

- 存档里能看到:`state.turn`、`state.status`、`world.archetypes`、`world.title`、`currentActions`。
- 存档里**没有**:创建时间、最后写入时间、客户端是否收到过 saveId、玩家是否看过开场。
- 因此 `turn == 0` 的档至少有两种来源,存档字段**无法区分**:(a) 客户端从未拿到 saveId(§3.2 / §3.3);
  (b) 客户端拿到了、看了开场、没走第一回合就离开。
- 文件 mtime 在 ADR-033 已决中**不使用**(拷贝会改写 mtime),此处同样不作依据。

---

## 4. 与既有机制的交点(只列)

| 机制 | 交点 | 出处 |
|---|---|---|
| ADR-015 续局 | 「继续上局」= `resumableSaveId` + `GET /state`;启动 `reloadFromStore` 全量回载;单 saveId 槽 | `gameStore.resumeGame` :346;`GameSessionManager.reloadFromStore` :50 |
| ADR-015 §不在范围 | 跨设备云存档 / 多存档管理 / 存档淘汰策略三项明列不在范围 | ADR-015 |
| ADR-022 刀 1.5 | `session_not_found` → `forgetDeadSave` 两半判据 | `gameStore.ts` :229、:431 |
| ADR-023 游标 | `TurnRequest.turn` 与 `engine.turn()` 比对;按单 saveId 判 | `GameController.turn` :313 |
| ADR-027 双向比对 | `turn_stale` 时前端 `resyncAfterStaleTurn` 拉 `/state` 覆盖本地,失败不清 saveId | `gameStore.ts` resyncAfterStaleTurn(:255 起) |
| ADR-025 回看上局 | 探测以 `resumableSaveId` 为输入;入口受 `capabilities.history` 门控;`historyStore.visible` 只按 saveId 缓存「是」 | `ArchetypeSelect.tsx` :56–67;`historyStore.ts` :96 `probe`、:112 `open` |
| ADR-016 init 额度 | `checkInit` 在 world-gen 之前计数;取消不退额度;双键 IP + deviceId 日计数 | `QuotaService.checkInit` :92、`check` :101 |
| ADR-030 init 准入 | 准入在额度之前;准入拒绝不建档;超时不中断 worker | `GameController.init` :166、`runInit` :194 |
| 线 C 世代守卫 | `startGame` / `resumeGame` 过期结果整段丢弃(不写 / 不清 saveId);回合流 `stale()` | `gameStore.ts` :204 `epoch`、:319、:355、:384 |
| ADR-031 轨迹 | 每局一个 `traces/<saveId>.trace.jsonl`,不自动删除,到限停写 | ADR-031 W-7 |
| ADR-033 统计 | 「有效档 = `turn ≥ 1`」;`turn == 0` 单列;存档无时间戳 | ADR-033 已决 1、底稿 §1 |
| ADR-025 / ADR-032 pg | `game_session` 有 `created_at` / `updated_at`;线上未切库 | `V1__narrative_history.sql` |

---

## 5. 前端存储的约束(事实)

- 现存 localStorage 键只有 `aiuniverse.saveId` 与 `aiuniverse.deviceId` 两个(§1.1)。两者值均为 UUID 级长度。
- localStorage 容量:各浏览器按源(origin)限额,常见约 5 MB 量级(**通用浏览器行为,本项目未实测**);超额时
  `setItem` 抛异常 —— 现有三个 helper 都 `try/catch` 静默吞掉(写不进 = 无续局入口)。
- 清浏览器站点数据:两个键一起消失 → 选择屏无「继续上局」;下次 init 生成新 deviceId(ADR-016 已知代价 3「一清即新身份」)。
  服务端档仍在 `/data`,客户端无从找回(无列出端点,§2.1)。
- 无痕窗口:localStorage 可用但关窗即清(浏览器行为,未实测);在 storage 抛异常的环境下,`readSavedId` 返回 null、
  `deviceId` 退化为进程内 id(§1.1)。
- 换设备 / 换浏览器:新设备 localStorage 为空;服务端无账号、无归属字段(§2.5),**没有任何跨设备找回路径**
  (backlog §2.3 依赖标注:「跨设备找回」压在冻结中的账号 + 云存档上)。

---

## 6. 选择屏的现有结构与约束条款

### 6.1 组件结构(`ArchetypeSelect.tsx`)

自上而下(:124–207):
`<main class=screen>` → `<header class=selectHeader>`(标题 + 拒绝提示 `fusionNote`)→
**「继续上局」按钮 `.resumeBtn`**(:133)→ **「回看上局」按钮 `.historyEntry`**(:140)→
加载中 / 错误 / `<div class=cardList>`(已激活卡 `ArchetypeCard` 带拖拽 → 揉出的 `FusionCard` → 未激活灰卡)→
揉合时的 `FusionMerge` 覆盖层。
`GameScreen.tsx` :30:`status === 'idle'` 时,`historyStore.viewing` 非空渲染 `HistoryScreen`,否则 `ArchetypeSelect`。
样式在 `game.module.css`(`.resumeBtn` :1299、`.historyEntry` :1327)。

### 6.2 对选择屏有约束的条款(只列)

| 条款 | 内容摘要 | 出处 |
|---|---|---|
| 融合入口方向语义 | 被拖者 = foreign、承接者 = host,有序双值喂 init,不新增字段 | ADR-019 §5 |
| 合法组合只读投影 | 合法组合来自 `GET /api/archetypes` 的 `fusions`,前端不自备组合表 | ADR-019 §6 |
| 长按入口退役 | 180 ms 抓起是 600 ms 长按的真子集,不并存 | ADR-019 §7 |
| 静态构图已冻结 | 仪式空间 156px / 世界核 ⌀60 / 融合卡 322×116 / 四帧中心重合 / 非参与卡 28% —— 均以卡片为度量单位 | ADR-019 §挂账「静态构图已冻结」 |
| 融合卡形态不换 | 三方案否决,维持渗漏卡双层边 + 金线 | ADR-019 §挂账(2026-08-06) |
| 融合方向可发现性 | 与视觉焦点管理同批,不单独开轮 | ADR-019 §挂账 |
| 入口范式待决 | 是否退回长按,动画按构图重做后再判 | ADR-019 §挂账 |
| 动效预算 | 每屏 1 持续 + 1 低频 + 1 用户触发;选择屏既有每卡氛围动画超预算记为刀 1 旧账 | AGENTS.md §2;ADR-019 背景第 3 条;ROADMAP v5.2 |
| 单一主题注册表 | 卡片氛围 class 由 `cardTheme` 给出,世界判定只发生一次 | ADR-018 §4.1;`ArchetypeSelect.tsx` :31 |
| 对拍验收法 | 选择屏 `<main>` 子树 outerHTML 归一化后对拍,作为未改动的证据 | ADR-018 §4.5.1 / §4.5.2 |
| 导航层 | 返回不弃局、不做二次确认、「继续上局」复用 ADR-015 机制 | ADR-018 §6(返回键条目);线 C |
| ARCHIVE 形态 | 与冻结构图几何上不兼容,谁先做另一个就得重来 | world-catalog-and-experience-modes 立字六 |
| 平台边界 | 平台 IO 只在 `api/`;逻辑 / 状态 / 展示层禁 `fetch` 等 | ADR-003;CONTEXT §三.13 |

---

## 7. 开放问题(只列不答)

1. **存档列表放在哪里**:客户端(localStorage 里存多个 saveId)还是服务端(需要先有某种归属键,今天不存在,§2.5)?
   两者是否互斥?
2. **列表每一条显示什么**:世界名 / 回合号 / status / 时间?今天 `/state` 只能取整局(§2.3),存档无时间戳;
   列表条目的数据从哪里来、要不要一个摘要形态?
3. **上限**:每个客户端保留几个存档指针?满了之后怎么办?
4. **孤儿档(`turn == 0`)**:要不要不落盘、延迟到第一回合再落盘、或落盘但标记?
   ADR-015 立字「起局即崩不丢局」(init 后写一次)与此如何取舍?ADR-025 已决 2 的 turn 0 开场叙事在 `pg` 下依赖 create 那次 persist。
5. **83 档 `turn == 0` 里有多少是取消产生的**:是否需要先能区分(§3.4),还是不区分?
6. **删除 / 归档**:玩家可见的「删除」要不要?是只删客户端指针还是服务端也删?无鉴权下服务端删除接口意味着什么?
7. **旧的单槽指针怎么迁移**:`aiuniverse.saveId` 里那一个值进新结构,还是并存、还是一次性搬家?多页签同时打开时谁来搬?
8. **多页签**:`resumableSaveId` 只在 store 创建时读一次(§1.2),多存档后是否需要跨页签同步?
   `resumeGame` catch 里无条件清(§1.4)在多存档下还成立吗?
9. **服务端清理**:要不要做?按什么判据(回合数 / status / 时间 —— 时间今天不存在)?
   与「卷单调增」「启动全量回载单调增」(§2.4)怎么配合?清理是否同时覆盖轨迹文件与 `game_session` 表?
10. **清理与统计的关系**:清掉的档会改变 ADR-033 的分母,统计是否需要在清理之前留底?
11. **取消与额度**:取消仍扣一次 init 额度(§3.2),多存档是否改变这一点的可见性 / 必要性?
12. **「回看上局」入口**:多存档后它针对哪一局 —— 仍跟随单个「上局」还是挂到列表每一条上?
13. **与选择屏冻结构图的关系**:新入口 / 列表放在选择屏的哪里?会不会触碰 ADR-019 冻结构图的度量单位、
    ADR-018 §4.5.1 对拍基线、动效预算?
14. **跨设备**:是否明确留在范围外(压在冻结的账号 + 云存档上),还是本轮要有任何形态(例如手动导出 saveId)?
15. **与 `pg` 切库的先后**:`game_session` 已有时间戳列;多存档管理先做还是切库先做,
    backlog §2.3 交叉段已记「先迁数据库再补多存档管理,和反过来做,代价差一整轮」。

---

## 出处抽样(供核对)

1. `web/src/state/gameStore.ts` `forgetDeadSave`(:229–232):localStorage 比对后清 / 内存无条件清。
2. `web/src/state/gameStore.ts` `startGame`(:311,写 saveId 在 :321):唯一的 `writeSavedId` 调用点,世代过期不写。
3. `server/.../eventloop/GameSessionManager.java` `create`(:76,persist 在 :89):init 落盘早于响应写出。
4. `server/.../web/GameController.java` `clientKey`(:403):设备 / IP 只构造 `ClientKey` 交配额,不进会话。
5. `server/.../quota/QuotaService.java` `check`(:101,计数在 :117–121):`checkInit` 放行即计数、早于 world-gen。
