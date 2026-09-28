# ADR-027 · 送达失败不丢回合 + 游标双向比对

- **日期**:2026-09-28
- **状态**:**已采纳**(2026-09-28,Felix 定;校勘核过 `65bb50c`)。
  ⤷ 原状态行(留档):「**提议** —— 草稿,待校勘与 Felix 过目。点头之前不写一行实现。」
- **实现进度**(活状态格):刀 1(已落地必写盘)待校勘 / 未合并(分支 `claude/adr-027-knife-1`);刀 2 未起;收口刀未起。⚠️ 本 ADR 的实现合并,是 [ADR-026](ADR-026-turn-acceptance-record.md) 实现的**开工前置**(ADR-026 已决 E / E′)。
- **决策者**:Felix

## 名字先说清

沿用简报给的名字,理由与一处限定:

- 「**送达失败不丢回合**」说的是今天那条**已知**的来源(`delta` / `ending` 的 SSE 写失败)。
  但本 ADR 选的修法(决策 1)**不按来源枚举**,它守的是一个更宽的不变式 ——
  「**回合已在内存落地 ⇒ 本次 persist 一定会被尝试**」,对落地之后任何一处 `RuntimeException` 都成立。
  名字取窄、机制取宽,是刻意的:名字说的是玩家会遇到的那件事,机制不能只防那一件。
- 「**游标双向比对**」:ADR-023 只比了「落后」;本 ADR 把「超前」也纳入,判据改为「不相等」。

两件事都在**默认 profile**、都**不需要数据库**、都**线上真实存在**。

## 背景

### 问题 ①:内存 N+1 / 盘 N(送达失败时 persist 被跳过)

回合路径(照源码):

```
TurnStateMachine.submitAction
  CAS AWAITING → GENERATING
  try {
    executor.execute(...)                         // EventLoopService
      ├─ llm.streamChat → sink.narrative(...)     // 叙事逐字
      ├─ settle():  engine.apply(...)  ← turn N→N+1(内存落地)
      │             updateActionsFromParsed(...)
      │             sink.delta(...)    ← SSE 写
      │             sink.ending(...)   ← SSE 写(结局回合)
      └─ degrade(): engine.applyNoOp(...) ← turn N→N+1(内存落地)
                    sink.delta(...)    ← SSE 写
    store.persist(session)                        // ← 只在 execute 正常返回时才跑
    phase = ended ? ENDED : AWAITING
  } catch (RuntimeException e) {
    phase = AWAITING
    sink.error("internal_error", ...)
  }
```

`SseTurnEventSink.send` 在 `emitter.send` 抛 `IOException` 时转成 `IllegalStateException` 抛出。
`delta` / `ending` 写失败时,这个异常在 `engine.apply` / `applyNoOp` **之后**冲出 `execute`,
被 `TurnStateMachine` 的 catch 接住 —— **`store.persist` 那一行被跳过**。
内存是 N+1,盘上是 N;此后进程重启(deploy / 崩溃)即回滚一回合。

这正是 [CONTEXT §三.17](../CONTEXT.md) 那句「⚠️ **而「内存 N+1 / 盘 N」这一偏差本身仍未治**」,
与 [ADR-023 §挂账](ADR-023-turn-cursor-idempotency.md) 那条「本刀明确不治」。

⚠️ **叙事流中途**的 SSE 写失败**不在此列**:那时 `engine.apply` 还没跑(异常从 `onToken` 穿出 `streamChat`),
内存与盘都是 N,不产生偏差(ADR-026 待核第 3 条已判定)。那条中止是「客户端走了就别再烧 token」,**本 ADR 不改它**。

### 问题 ②:客户端超前

玩家已收到第 N+1 回合的 `delta`(新选项),但落盘没成 —— 可以是 ①,也可以是 `persist` 自己失败(best-effort 吞异常),
或进程死在 `sink.delta` 与 `store.persist` 之间那几毫秒。重启后服务端在 N,玩家带 N+1 提交:

```java
if (req.turn() < session.engine().turn() && !session.phase().get().inFlight()) → 409 turn_stale
```

只拦落后,`N+1 < N` 为假 → 放行。而选项 id 跨回合稳定为 A–D,守卫 1 认得它 →
**玩家按 N+1 那组选项的文字选了「B」,被当作 N 那组选项里的「B」执行**。
他点的是一件事,服务端做的是另一件事,且没有任何提示。

[ADR-023](ADR-023-turn-cursor-idempotency.md) 正文**没有讨论超前方向** ——
`aheadTurnIsNotTreatedAsStale` 那条用例的注释只防「比较方向写反」(把 `<` 写成 `>`),
不是一个「超前应当放行」的论证。

### 勘察顺带撞见的一条(不在简报里,报)

**结局回合的 `ending` 写失败,今天会让一局「结束了又能继续玩」。**
catch 无条件 `phase = AWAITING`,而此刻 `engine.status()` 已是 `ended`。若 `delta` 已送达(客户端游标 = N+1)、
结局回合模型没给新选项(`updateActionsFromParsed` 在空数组时不覆盖,`currentActions` 仍是上一组),
玩家再点一次:游标相等 → 放行;守卫 1 认得旧 id → 放行;CAS 从 `AWAITING` 起得来 → **在一个已收束的世界上再跑一回合**。
它与 ① 同一处病灶(catch 不看回合是否已落地),故决策 1 一并治;不另开刀。

### 约束条件

1. **默认 profile**,不需要数据库;线上即生效。与 ADR-026(只在 `pg`、默认 profile 逐字节不变)正交。
2. **叙事流中途断开仍须中止**(省 token);`TurnEventSink` 的契约不因本 ADR 变语义。
3. **不引入必须永不失败的写**([ADR-022](ADR-022-turn-admission-and-rejection-semantics.md) 立字 5):`persist` 保持 best-effort。
4. **`Engine` 一行不动**(golden parity)。
5. ① 与 ② **各自可单独回滚**。

## 候选方案

### ① 方案 A:`SseTurnEventSink` 在 `delta` / `ending` 写失败时不抛、只记一笔

`narrative` 照旧抛(省 token 的中止);`delta` / `ending` 失败时 catch 住、记 WARN、返回。
`execute` 正常返回 → `persist` 与相位转换走正常路径。

**优点**:
- 相位由 `result.ended()` 决定,结局回合自然落到 `ENDED`;catch 保持「真·意外故障」的单一语义。
- 不碰 `TurnStateMachine`。

**缺点**:
- **按来源枚举**:只覆盖 sink 里那两处写失败。落地之后的其他 `RuntimeException`
  (`buildDelta` / `buildEnding` / `updateActionsFromParsed` / `appendLifeExitAction` 里任何一个 bug)照样冲出 `execute`、照样跳过 `persist`。
  不变式「落地 ⇒ 尝试 persist」**不是被结构保证的,是被「目前已知只有这两处」保证的**。
- 同一个接口里三个事件方法两种失败语义(`narrative` 抛,`delta` / `ending` 吞),读 `TurnEventSink` 的人要记住这个不对称;
  `EventLoopService` 的单测里用的伪 sink 也须同步这个不对称,否则测的不是生产行为。

### ① 方案 B:在 catch 里区分「已落地未送达」与「未落地」,前者补一次 persist(本 ADR 采纳)

CAS 成功后、`execute` 之前读一次 `turnBefore = engine.turn()`;catch 里若 `engine.turn() != turnBefore` → 已落地 →
`store.persist(session)`,相位按 `engine.status()` 定(`ended` → `ENDED`,否则 `AWAITING`)。

**优点**:
- **不按来源枚举**:落地之后任何一处 `RuntimeException` 都走同一条补写路径。不变式由**一处结构**保证,而不是由「记得在每个新的失败点吞异常」保证 —— 与 ADR-022「归还在结构上不可遗忘」同形。
- 「落地了没有」读的是一个**事实**(`engine.turn()` 变没变),不是一个被维护的标志 —— 与 ADR-022「在途数是投影不是计数」同形。
  `apply` 与 `applyNoOp` 的第一条语句都是 `turn += 1`,两条落地路径都被同一个判据覆盖。
- `TurnEventSink` 契约一字不动;`EventLoopService` 一字不动。
- 顺带治掉上面那条「结束了又能继续玩」。

**缺点**(见已知代价 3、4):
- 判据是「`apply` **开始了**」,不是「`apply` **完成了**」:`apply` 在 `turn += 1` 之后若抛,半截状态会被写盘。
- 相位决定在两处出现(正常路径看 `result.ended()`,catch 看 `engine.status()`)。两者读的是同一个事实,但写在两处。

### ① 方案 A + B 都做

**否决**:A 能覆盖的情形 B 全覆盖;两者都做,等于同一个不变式有两个守护者,出问题时分不清是谁在干活
(与 ADR-020 刀 7「两条同刀才能读出信息」相反 —— 那里两条贡献不同,这里 A 是 B 的真子集)。

### ② 方案 C:另起一个 code(如 `turn_ahead`)

**优点**:服务端 wire 上区分方向。

**缺点**:前端对两个方向的**正确处理完全相同**(丢弃本地视图、拉 `/state`、静默刷新),另起 code 只会让前端多一处登记
(`RECOVERABLE_TURN_ERRORS` 一条 + `onError` 显式抑制分支一条),两条字节级相同的处理日后会漂移。
「区分方向」的真实需求是**观测**,那不必走 wire(见决策 2)。

### ② 方案 D:复用 `turn_stale`,判据改为「不相等 ∧ 不在途」(本 ADR 采纳)

**优点**:前端零行为改动(勘察已核,见决策 2);ADR-023 立字 3 / 4 / 4.1 / 5 全部原样成立。

**缺点**:`turn_stale` 的语义从「落后」扩为「不一致」;须在 ADR-023 加指针,前端注释里「落后」的措辞须同步(实现刀 2 做)。

## 最终决策

### 1. ① 采纳方案 B —— 不变式写死

> **回合已在内存落地 ⇒ 本次 persist 一定会被尝试。**

「一定会被尝试」不是「一定成功」:`persist` 保持 best-effort(约束 3)。落盘失败的残余由决策 2 在重启后兜住。

形状(实现刀 1 定细节,此处只定语义):

```java
if (!session.phase().compareAndSet(AWAITING_ACTION, GENERATING)) { ...busy... }
int turnBefore = session.engine().turn();        // CAS 之内读,此刻本线程是唯一写者
try {
    TurnResult result = executor.execute(session, actionId, sink);
    store.persist(session);
    session.phase().set(result.ended() ? ENDED : AWAITING_ACTION);
} catch (RuntimeException e) {
    if (session.engine().turn() != turnBefore) {
        // 已落地未送达:补写盘,相位按引擎事实定。不向客户端发 internal_error(见下)。
        log.warn(... "回合已落地,送达/收尾失败,补写盘" ...);
        store.persist(session);
        session.phase().set("ended".equals(session.engine().status()) ? ENDED : AWAITING_ACTION);
    } else {
        session.phase().set(AWAITING_ACTION);
        sink.error("internal_error", "回合处理失败,请重试");
    }
}
```

三处细节,各有理由:

- **`turnBefore` 在 CAS 之后读**:CAS 之前读,另一个线程可能在两次读之间推进回合;CAS 之后,忙态守卫保证本线程是这一局唯一写者(ADR-015 勘察 2 同一前提)。
- **已落地分支不发 `internal_error`**:那句「回合处理失败,请重试」在这里是**假话** —— 回合没有失败,它已经落账了。
  送达失败时它本来也发不出去(sink 已断);落地后某个 bug 抛出时,不发它,客户端收流后回 `awaiting`、游标仍是 N,
  下一次点击拿 `turn_stale` → 拉 `/state` → 看到他错过的那一回合(ADR-023 立字 4.1 的路径原样复用)。
- **已落地分支记一条 WARN**:今天这个 catch **不记任何日志**(报:勘察撞见,本 ADR 只在已落地分支补一条,未落地分支维持现状 —— 那是另一件事)。
  读者 = 排查「为什么这一回合玩家说没看到」的人;读了决定 = 去看 SSE 断开还是代码 bug。

### 2. ② 采纳方案 D —— 比对从「落后」改为「不相等」

```java
if (req.turn() != session.engine().turn() && !session.phase().get().inFlight()) → 409 turn_stale
```

`turn_stale` 的语义从「你的游标落后于服务端」扩为「**你的游标与服务端不一致**」。
在两个方向上这句话都是真的,且客户端该做的事一样:**你手里那一局不是服务端那一局,丢掉它,拉 `/state`**。
⚠️ 「stale」这个词在超前方向上仍然成立:客户端那份视图来自一条服务端已经不存在的时间线,它是过期的,只是过期的方向不同。

服务端另打一条 **WARN**(只在超前方向):超前**从不**是正常流程的产物 —— 它只在「落盘没成 + 重启」之后出现,
是默认 profile 下「曾经丢过一次落盘」**唯一可观测的痕迹**。读者 = 回看 ADR-026 已决 E′ / 本 ADR 残余代价的人。
落后方向不打(断流后重点一次是常态,打了是噪音;ADR-022 刀 2 前置「先清噪音再加信号」)。
⚠️ 日志是瞬时的([工程债挂账](../backlog-engineering-debt.md)),这条 WARN 只在有人当场看时有用 —— 如实记,不夸大。

#### 要核一:复用 `turn_stale`,前端对 409 的处理在超前情形下是否照样正确 —— **正确,零行为改动**

出处:`web/src/state/gameStore.ts`

- `onError` 里 `err.code === 'turn_stale'` 分支:`set({ status: 'awaiting' })` 后调 `resyncAfterStaleTurn`,**不看方向**。
- `resyncAfterStaleTurn` 用 `/state` 的结果**无条件**覆盖本地:`turn: res.world.state?.turn ?? 0`、`availableActions: res.availableActions`、
  叙事取 `state.log` 末条、数值取 `character.attributes`。**没有「只接受更新的回合」这类单调性判断** ——
  故服务端回合比本地小时,本地照样被换成服务端那一回合。
- `/state` 读内存现值(`GameInitService.resume`),重启后内存由 `reloadFromStore` 从盘回载,即服务端的 N。

结论:超前情形下前端把玩家同步回 N(即回滚),符合决策 3。⚠️ **这个正确性依赖一条今天没有测试钉住的性质**
(resync 不做单调性判断),故测试面新增一条前端用例钉它(见测试面)。
⚠️ 前端**注释**里「只是我们落后了一个回合」等措辞在超前方向上不准,实现刀 2 同步改注释(不改行为)。

#### 要核二:在途窗口内会不会误伤正常流程 —— **不会,且与今天行为逐字相同**

出处:`EventLoopService.settle`(`engine.apply` → `updateActionsFromParsed` → `sink.delta`,此时相位 `SETTLING`)、
`degrade`(`applyNoOp` → `sink.delta`,相位可能仍是 `GENERATING`)、`TurnStateMachine.submitAction`(`persist` 与相位放回在 `execute` 返回之后)。

正常流程:`delta` 已送达、服务端还在 `SETTLING`(或 `GENERATING`),客户端立刻提交下一回合 ——
客户端带的游标 = `delta.turn` = 服务端 `engine.turn()`(`apply` 已推进),**相等** → 不进 409 分支;
守卫 1:`currentActions` 在 `sink.delta` 之前已更新 → 认得;准入;CAS 从 `SETTLING` 起不来 → `busy`。
**与今天完全相同**(今天比的是 `<`,相等同样不进)。

就算不相等,在途时判据的第二个合取项(`!inFlight()`)为假,也不会 409 —— 那是 ADR-023 立字 2 已有的保护,本 ADR 原样继承。

唯一能「超前 ∧ 在途」同时成立的情形:重启后,**另一个页签**先在 N 上提交了一回合(服务端在途),本页签带 N+1 提交 → 不 409,拿到 `busy`。
单页签构造不出来(重启后相位是 `AWAITING`,本页签的第一次提交就会被 409)。其后果见已知代价 2。

#### 要核三:`aheadTurnIsNotTreatedAsStale` 怎么改,新测试守什么

出处:`server/src/test/java/com/aiuniverse/server/web/GameControllerTurnGuardsTest.java`

- 原用例守的是**比较方向**:把 `<` 写成 `>` 或 `!=` 时只有它会红(注释逐字如此)。判据改成 `!=` 之后,「超前放行」本身就是要消灭的行为,原断言(200)必须反过来。
- **改写为** `aheadTurnIsTreatedAsStale`:服务端 1、客户端 2、相位 `AWAITING` → 409 `turn_stale`、只带 code、零提交。
- **新增** `aheadTurnWhileInFlightIsNotStale`:服务端 1、客户端 2、相位 `SETTLING`(另一条 `GENERATING`)→ 200(走池线程,在那里撞 `busy`)。守的是「超前方向同样受 `inFlight()` 保护」。
- 新判据的守护由**三条**合起来完成:落后 → 409(既有 `staleTurnReturns409AndSubmitsNothing`)、相等 → 200(既有 `currentTurnProceedsNormally`)、超前 → 409(改写后的这条)。
  只有 `!=` 能同时满足三条:写成 `<` → 超前那条红;写成 `>` → 落后那条红;写成「一律 409」→ 相等那条红。
  这正是原用例注释说的「(b) 是 (a) 的控制组」那套结构,扩到三点。

### 3. 玩家侧:同步到服务端那一回合(即回滚),不加提示

与 [ADR-026 已决 D](ADR-026-turn-acceptance-record.md) 同口径:静默。玩家会再读到一次第 N 回合的叙事与选项。
⚠️ 这是回滚,不是「修好了」—— 他在 N+1 看到的那段叙事**在服务端已不存在**,下一次他在 N 上选,模型会写出另一个 N+1。
不加提示的理由:加一句「上一回合未保存」要新文案与新前端分支,而触发它的前提(落盘失败 + 重启)在线上极罕见;
代价照记(已知代价 1)。

### 4. 与 ADR-023 的关系

超前方向是 ADR-023 没讨论过的。本 ADR 在 ADR-023 立字 3 之后加**一行指针**(加注,原文保留),
不改写 ADR-023 任何立字:立字 1(排序)、2(连相位读)、3(在途 = GENERATING/SETTLING;ENDED 判 stale)、4(只发 code)、4.1(不提示只刷新)、5(`inFlight()` 单点)在新判据下全部原样成立。
ADR-023 §挂账「内存 N+1 / 盘 N」的解冻条件原写「方案 C(数据库)开工时」—— 本 ADR 表明它**不需要数据库**,该条由本 ADR 实现兑现。

### 5. 与 ADR-026 的关系(实现时须吸收)

ADR-026 决策 4 写「`catch (RuntimeException)` 里一行 `ledger.failed(...)`」,并论证「delta 写失败那条路标 FAILED 是准确的 —— 快照没写到 target」。
**本 ADR 实现之后这个前提变了**:已落地分支会补一次 `persist`,快照**会**写到 target(若补写成功)。
故 ADR-026 刀 1 实现时,`ledger.failed` 只应放在**未落地**分支;已落地分支交给 `persist` 事务里的落地更新(ADR-026 决策 2)。
本 ADR 不改 ADR-026 正文(范围外);此处写下,由 ADR-026 刀 1 开工时吸收。

### 6. CONTEXT §三.17

「⚠️ **而「内存 N+1 / 盘 N」这一偏差本身仍未治**」由本 ADR 实现兑现(残余:`persist` 自己失败 / 进程死在毫秒窗口 —— 那两种由决策 2 在重启后兜住,见已知代价 5)。
**CONTEXT 的订正放在实现收口那一刀**(订正块保留原文);本刀(草稿)不动 CONTEXT。

### 关键理由

1. **不变式由结构保证,而不是由枚举保证**:方案 B 在一处读一个事实,覆盖落地之后的所有异常来源(ADR-022「归还在结构上不可遗忘」、「在途数是投影不是计数」同形)。
2. **超前与落后的正确处理相同,故共用一个 code**:两个 code 同一个处理,是会漂移的第二份真相(ADR-018 §4.1 同族)。
3. **两件都不需要数据库**:ADR-023 与 ADR-026 都把它们挂在「数据库那一刀」上,勘察证明病灶在异常路径与比较运算符上,不在存储介质上。
4. **① 缩小 ② 的触发面,② 兜住 ① 兜不住的残余**:两者互补,但各自可单独回滚。

## 已知代价

1. **超前时玩家被静默回滚**:他看过的第 N+1 回合叙事不复存在,重新站在 N 上,无任何提示(决策 3)。
   缓解:触发前提(落盘失败 + 重启)罕见;服务端 WARN 留痕。与 ADR-023 已知代价 4 同性质 ——「不提示」只在问题确实被修好时是诚实。
2. **游标只是回合号,不是内容身份**:重启后多页签可以走到「游标相等、选项集不同」(见要核二末段):页签 A 在 N 上推进出一组新的 N+1,
   页签 B 手里是重启前那组 N+1,游标相等 → 放行 → B 的字母被映射到 A 生成的新选项上。本 ADR 不治;
   真正的治法是请求携带内容身份(选项集指纹或 ADR-026 否决项里的请求 id),规模不在本刀。
3. **「已落地」判据 = `apply` 开始了,不是完成了**:`Engine.apply` 在 `turn += 1` 之后若抛,半截状态会被补写盘,覆盖盘上上一个完整版本。
   今天这条路径**未观察到**;且那份半截状态本来就已是内存里的权威(`/state` 读它、下一回合的 `persist` 也会写它),补写不制造新的不一致。
   若半截状态非法,`Engine.restore` 会在重启时拒载该档(「拒载不半载」)—— 那是比回滚更坏的结果,如实记。
   缓解:不改 `Engine`(约束 4);若日后要精确判据,须给 `Engine` 一个「本回合结算完成」的事实,那是 golden 要重录的一刀。
   ⤷ **加注(2026-09-28,采纳时,原文保留)**:这不是本 ADR 引入的代价 —— 今天内存为权威,半落账状态本来就会随下一回合的 persist 落盘;本 ADR 只是让它提前一回合。
4. **catch 只接 `RuntimeException`**:`Error`(OOM 等)不走补写,相位停在 `GENERATING`(今天的行为,本 ADR 不变)。进程状态已不可信时补写盘不是好主意。
5. **不变式是「尝试」不是「成功」**:`persist` 仍 best-effort;落盘失败或进程死在 `sink.delta` 与 `persist` 之间的毫秒窗口,仍会产生「内存 N+1 / 盘 N」,重启后由决策 2 兜住(以代价 1 的形式)。
   CONTEXT §三.17 的订正须写清这条残余,不许写成「已治」。
6. **重启后「重掷」仍在**:落盘没成、客户端**也**没收到 `delta`(游标 = N)时,重启后服务端也是 N,游标相等 → 放行 → 同一个 N 被重新生成一次(ADR-026 待核第 2 条「重掷」)。本 ADR 不治(需请求身份,同代价 2)。
7. **超前方向的 WARN 依赖日志**:日志是瞬时的,没人当场看就等于没有。

## 挂账(本 ADR 不做,记着)

- **`TurnStateMachine` 未落地分支的 catch 不记日志**:真·意外故障今天是静默的。本 ADR 只在已落地分支补 WARN;未落地分支要不要记、记在哪一级,是另一件事。
- **守卫 2 在 `ENDED` 上的文案**(ADR-023 §挂账「客户端不落后而局已结束时仍拿 `busy`」)原样挂着;本 ADR 修的是「ENDED 被错放回 AWAITING」,不是那句文案。

## 重新审视的触发条件

- `Engine.apply` / `applyNoOp` 的结算顺序改变,`turn += 1` 不再是第一步 → 决策 1 的判据须重估(代价 3)。
- `persist` 改为非 best-effort(失败即拒绝)→ 撞 ADR-022 立字 5,决策 1 重估。
- `TurnPhase` 增加第五个值 → 在 `inFlight()` 一处决定(ADR-023 立字 5),本 ADR 的判据自动跟随。
- 前端 resync 加入单调性判断(「只接受更新的回合」)→ 决策 2 的前端正确性前提失效;测试面那条前端用例就是为这一刻写的。
- 请求携带内容身份(ADR-026 否决项解冻 / 多实例)→ 代价 2、6 重估。
- 线上观察到超前 WARN → 看频次;若非罕见,重估决策 3「不提示」。

## 实施步骤(本刀不实现)

两刀,**各自独立、可单独回滚**;建议顺序 刀 1 → 刀 2(刀 1 缩小刀 2 的触发面),但无依赖。

1. **刀 1 · ① 已落地必写盘**(`TurnStateMachine` 一处):
   - CAS 后读 `turnBefore`;catch 分两支(已落地:补 `persist` + 相位按 `engine.status()` + WARN、不发 `internal_error`;未落地:原样)。
   - `EventLoopService` / `SseTurnEventSink` / `Engine` 一字不动;golden、prompt、`schemaVersion`(保 "0.4")零动。
2. **刀 2 · ② 游标双向比对**(`GameController` 一处 + 前端注释):
   - `<` → `!=`;超前方向打一条 WARN;控制器 javadoc 拒绝链图与行内注释「落后」措辞同步。
   - 前端 `gameStore.ts` 里 `turn_stale` 相关注释的「落后」措辞同步(**只改注释,不改行为**)。
   - [`docs/architecture.md`](../architecture.md) 拒绝链图若写了「落后」,同步(它自认是 javadoc 那张图的副本)。
3. **收口刀**:CONTEXT §三.17 订正(订正块保留原文,写清残余);ADR-023 §挂账「内存 N+1 / 盘 N」加注兑现;活状态格同步。

## 测试面(写进 ADR,本刀不实现)

**刀 1**(`TurnStateMachineTest`,用记录型 `SessionStore` 替身):
- 已落地后抛:executor 先 `engine.applyNoOp(...)` 再抛 `IllegalStateException` → `persist` 被调用 1 次、相位 `AWAITING_ACTION`、**未**发 `internal_error`。
- 已落地且已收束后抛:executor 先把引擎推到 `ended` 再抛 → 相位 **`ENDED`**、`persist` 1 次(钉「结束了又能继续玩」)。
  同一用例续一步(采纳时确认必含):ended 局 ending 写失败后相位仍为 **`ENDED`**,再点(旧游标或当前游标)**不会推进** —— `engine.turn()` 不变、`persist` 不再增加。
- 未落地就抛:executor 直接抛 → `persist` 0 次、相位 `AWAITING_ACTION`、发 `internal_error`(既有行为的守护)。
- 端到端一条:真 `EventLoopService` + `MockLlmClient` + 一个 `delta` 时抛的 sink → 内存与盘上的回合号一致。
- 变异(读红用例名,不读计数):摘掉补写 → 前两条红;相位写死 `AWAITING` → 只有「已收束」那条红;`!=` 改成「一律当已落地」→ 只有「未落地」那条红。

**刀 2**(`GameControllerTurnGuardsTest`):见要核三 —— 改写 `aheadTurnIsNotTreatedAsStale` 为 `aheadTurnIsTreatedAsStale`,
新增 `aheadTurnWhileInFlightIsNotStale`(`SETTLING`、`GENERATING` 各一条);既有落后 / 相等 / 在途落后五条不动。
- 变异:`!=` → `<`(超前那条红)、`!=` → `>`(落后那条红)、摘掉 `!inFlight()`(在途四条红)。
- WARN:超前方向打、落后方向不打,各一条(`ListAppender`,同 ADR-022 刀 2 前置的日志断言形态)。

**刀 2 前端**(`gameStore.stale.test.ts`):
- 本地回合 2、`/state` 返回回合 1 → 本地被同步回 1(回合号、选项、叙事都换成服务端那份)。
- 变异:给 resync 加「只接受更新的回合」→ 这条红。⚠️ 这是决策 2「前端零行为改动」的唯一守护。

## 实际效果(事后补充)

*刀 1 合并后回填:一次真机「生成中点返回」(ADR-018 §6 那条路径)后重启,盘上回合号是否等于玩家最后看到的回合号。*
*刀 2 合并后回填:线上是否出现过超前 WARN;若出现,频次与前后日志。*

## 跟其他文档的交叉引用

- [ADR-015](ADR-015-overseas-deployment-form-factor.md)(已知代价 5:崩溃回滚一回合;best-effort persist)
- [ADR-022](ADR-022-turn-admission-and-rejection-semantics.md)(立字 5「必须永不失败的写」;结构上不可遗忘)
- [ADR-023](ADR-023-turn-cursor-idempotency.md)(游标比对立字 1–5;§挂账「内存 N+1 / 盘 N」;已知代价 4、5)
- [ADR-026](ADR-026-turn-acceptance-record.md)(已决 D / E / E′;决策 4 的 `ledger.failed` 位置须随本 ADR 调整)
- [CONTEXT §三.17](../CONTEXT.md)(「内存 N+1 / 盘 N 仍未治」—— 本 ADR 实现收口时订正)
- 源文件:`TurnStateMachine.submitAction`、`SseTurnEventSink.send`、`EventLoopService.settle` / `degrade`、`GameController.turn`、`web/src/state/gameStore.ts` `resyncAfterStaleTurn`
