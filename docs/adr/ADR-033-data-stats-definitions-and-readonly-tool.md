# ADR-033 · /data 统计口径与只读统计工具

- **日期**:2026-10-15
- **状态**:**已采纳**(Felix 2026-10-15 定)。实现进度见文末「实现进度」。
- **决策者**:Felix
- **前提**:`main@dd09215`。事实与开放问题以 [统计勘察底稿](../stats-survey.md)(下称「底稿」)为准;
  本 ADR 的「已决」编号与底稿 §6 的开放问题一一对应。
- **范围**:定口径 + 一个只读的测试侧统计工具(`exec:java`,不进生产装配)。
  **不改**日志格式、不加埋点、不碰 `fly logs`(ROADMAP v5.8)、不改任何存档或轨迹格式。
- 背景挂账:[工程债「简历素材」](../backlog-engineering-debt.md)的解冻条件「`/data` 有效档 ≥ 50 局,且单局平均回合数 > 5」。
  本 ADR 给出算这两个数的口径与工具;挂账本身要等 Felix 真实跑完、拿到读数后再更新,本 ADR 不动它。

---

## 背景

简历素材里那几个方括号,勘察确认:一半能从 `/data` 算、一半只能从轨迹算、world-gen 那两项在 `/data` 里根本不留痕;
「有效档」在文件存档上没有定义(ADR-025 已决 3 只定义在 pg 的 `game_event` 上)。
不先把口径写死,任何一次手算都会各算各的,而且最容易把叙事正文或 saveId 带进报告(底稿 §5)。

---

## 已决(编号 = 底稿 §6 开放问题)

1. **有效档 = 存档 `state.turn ≥ 1`**(对应 ADR-025 已决 3 在 pg 上「至少一条 `turn ≥ 1` 的事件」)。
   `turn == 0` 的档单独计数列出,**不进分母**。
2. **降级回合计入回合数**(它让 `turn +1`,玩家确实看到了一回合);降级率在轨迹组单独报。
3. **「单局平均回合数」的分母 = 有效档。** `ended` 与 `ongoing` 合算一个数,另分别各列一行。
4. **不排除作者自玩局与冒烟局**(没有任何字段能区分,底稿 §2)。报告首行固定写:
   「本报告数据含作者自玩与冒烟局;它证明统计可复现,不代表真实玩家留存或活跃度。」
5. **融合局按组合单列一类**(host 在前的有序对,如 `cultivation × rules_creepy`),**不拆到单体世界里**。
6. **存档组与轨迹组分节报告,各自标明分母。** 轨迹组的范围写成
   「2026-10-08 起有轨迹记录的已落地回合」,**不写「此后新开的局」**:旧存档在轨迹上线后继续玩也会产生轨迹;
   写入失败或达到容量上限的回合会缺席。「n 局 / n 回合」一律取**实际读到的样本数**。
7. **本轮 `/data` 报告不计算 world-gen 首轮有效率与修复后成功率**(数据只在日志,底稿 §3.1)。
   不是永久放弃;「把 world-gen 结果写进轨迹」记为挂账(见「挂账」),本轮不做。
8. **存档组标注「跨模型版本,无法切分」**(存档无 model、无时间戳,底稿 §3.4)。
   轨迹组**不凭日期断言版本**:按 `usage[].model` 逐条统计,列出模型分布(含缺 `model` 字段的条数);
   只有分布确认之后,报告才写出模型名 —— 且写出的模型名**只来自数据本身**,工具里不写死任何模型名。
9. **不用文件 mtime 作时间依据**(拷贝后是否保留未核,底稿 §1;工具不读 mtime)。
10. **输入数据沿用 [pg-cutover-drill.md](../pg-cutover-drill.md) 第 2 步 (b) 的只读拷贝流程**(见「运行方式」)。
11. **「最长连续回合数」改名为「单局最大回合数」** = 有效档里 `state.turn` 的最大值(无时间戳,「一次会话不中断」不可判定)。
12. **成本一律称「按配置单价估算的回合成本」,不称实际花费。** 报告写明:
    - 所用三段单价及其来源(`application.yml` 当前配置,该配置注释写明取高峰档);
    - 被中止的调用可能缺 usage,导致估算偏低([工程债「成本闸门少算被中止的调用」](../backlog-engineering-debt.md));
    - 单局数字只是**有轨迹回合之和**,**不含 world-gen 那一次调用**(单局里最贵的一次)。
    简历上**不写成「真实单局成本」**。

---

## 工具形态

- 测试侧 `exec:java` 工具,形态照 `TraceRecheckTool`:
  `com.aiuniverse.server.persistence.DataStatsTool --in <线上 /data 拷贝根目录> --out <报告文件>`。
- **输入**:存档 = 根下 `*.json`(扫描口径同 `FileSessionStore.loadAll`:`Files.list`、只收 `.json`、不递归,
  三分为「载入 / 非存档跳过 / 拒载」);轨迹 = 根下 `traces/*.trace.jsonl`(不递归)。
- **解析一律复用**:`SessionDocument.isSaveDocument` / `decode`、`TraceFileReader`、`TurnTraceCodec`。不写第二份解析器。
- **路径**:输入与输出都过 `MigrationPathGate.strict()`(web 根 + 仓库两道;ADR-031 W-11)。任何地方都不回显输入文件名或 saveId。
- **报告只从数字与枚举字段生成**:叙事字段(`title`、`background`、`narrative`、`timeline`、选项文案、`hint`、规则内容等)
  不进报告。`outcome` 只认 `success` / `failure` / `neutral`,archetype 取 registry 校验过的 id;
  其余标签类取值(`commit`、`model`、`degradeReason`、调用名)只在形如 `[\p{L}\p{N}._:-]{1,64}`(不含空白与标点)
  时原样写出,否则记为「(非常规取值)」。
- **比例只经同一个格式化函数**:格式「x / y(z%)」;分母为 0 时输出「— / 0」,不除零。
- **写出之前的三道闸**:① 叙事闸 —— 把输入里叙事字段的字符串(≥ 4 个字符)收集起来,报告里出现任一条即拒写;
  ② `FixtureScanner` 那组敏感形态扫描(sk-、api-key、Authorization、Bearer、IPv4、IPv6、邮箱);③ UUID 形态扫描。
  任一命中 → 不写文件,非零退出(报错只给类别与计数,不回显命中内容)。
- **计价**:不在工具里另写公式或写死数字。生产代码唯一一处改动:把 `QuotaService.costCny` 的公式抽成
  `quota.TokenPricing.costCny(LlmUsage, LlmProperties.Price)`,两处共用,`QuotaService` 行为不变(既有测试守住)。
  单价由工具从 classpath 上的 `application.yml`(与线上同一份源文件)绑定 `LlmProperties` 得到 ——
  绑定路径同 `YmlBoundPriceTest`;yml 里**恰好一个** provider 带 `price` 时用它,否则拒绝运行。报告打印实际用到的 provider 键与三段单价。

## 报告内容

**存档组**:候选档总数 / 非存档跳过数 / 拒载数 / `turn == 0` 数 / 有效档数;单局平均回合数与中位数
(合计、ended、ongoing 各一行);单局最大回合数;按 archetype 的分布(单体按世界,融合按有序对);
ended 局的结局 `outcome` 分布(含「无 outcome」);`issues` 里结局 gate 改判次数与收束钳制次数(仅计数);
末尾一行解冻判定:「有效档 ≥ 50 且单局平均回合数 > 5」→ 满足 / 不满足,并附两个实际数。

**轨迹组**:读到的轨迹文件数(局)、已落地回合数、半截末行跳过数、无法解码的文件数;
`recordedAt` 的最早与最晚日期(只到日);commit 分布;`usage[].model` 分布(含缺字段条数);
降级率及三种原因的分布;回合侧修复率;主调用与修复调用分开报平均 token(prompt / completion / total)与缓存命中率;
按配置单价估算的回合成本(平均每回合;每局「有轨迹回合之和」的平均与最大,标注「不含 world-gen」);
`durMs` 的 p50 / p90 / max(`-1` 不进分布,单独计数;分位数取最近秩)。

---

## 可以 / 不可以怎么写进简历

**可以写**:
- 工具与口径:「为文件存储写了只读统计工具,口径落档(有效档、降级回合、融合归类、成本估算的边界),
  报告带叙事闸与敏感形态扫描,输入输出强制在仓库外」;
- 带分母和范围的数字,例如「有效档 N 局,单局平均 X 回合(n = N)」「2026-10-08 起有轨迹的 M 个回合里降级 k / M」。

**不可以写**:
- 真实玩家留存或活跃度(数据含作者自玩与冒烟局,无法区分);
- 实际花费、「真实单局成本」(只有按配置单价的估算,且缺 world-gen、缺被中止调用);
- world-gen 首轮有效率 / 修复后成功率(本轮不算,数据只在日志)。

---

## 运行方式(Felix 在 Mac 上;一行一条命令,在仓库根目录执行)

规则同 W-11:拷下来的 `/data` 只放在仓库之外的 `~/wanjie-stats/`;**不进仓库、不进聊天、不交给云端会话**;
跑完**只贴报告**(报告不含 saveId、文件名与正文)。

1. 确认工作副本与 `main` 同步:
```sh
git fetch origin && git status -sb
```
2. 线上卷只读拷贝(沿用 pg-cutover-drill 第 2 步 (b);`fly ssh sftp get` 要求目标目录**事先不存在**,不要先 `mkdir` 它):
```sh
mkdir -p ~/wanjie-stats
```
```sh
fly ssh sftp get -R /data ~/wanjie-stats/data -a wanjie-ai
```
3. 跑工具:
```sh
./server/mvnw -q -f server/pom.xml test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.aiuniverse.server.persistence.DataStatsTool -Dexec.classpathScope=test -Dexec.args="--in $HOME/wanjie-stats/data --out $HOME/wanjie-stats/report.txt"
```
退出码非零(路径在仓库内、敏感形态命中、叙事闸命中)→ 不写报告;把终端最后几行(只有类别与计数)贴回来。
4. 看报告,只贴它:
```sh
cat ~/wanjie-stats/report.txt
```
5. 清理:
```sh
rm -rf ~/wanjie-stats
```

## 实际效果(2026-10-15,Felix 在 Mac 上对线上 /data 只读拷贝运行;原始拷贝已删)

### 报告原文

> ⚠️ **待补**:本轮简报里报告全文的位置是一个未填的占位符(「把 Felix 贴来的报告全文……原样粘在这里」),
> 报告本身没有传到。按「照录、一字不改」的要求,这里**不凭下文的摘录重写一份**;由 Felix 贴入原文后补上。
> 下面各条的数字取自同一份简报里校勘给出的摘录。

### 读数要点(追加,不覆写报告原文)

- **解冻判定:满足**(有效档 147,单局平均回合数 24.3)——
  [工程债「简历素材」](../backlog-engineering-debt.md)的条件「有效档 ≥ 50 局,且单局平均回合数 > 5」。
- 单局回合数分布**右偏**:平均 24.3 / 中位数 8.0 / 最大 300。引用时写中位数,或平均与中位数并列,不单写平均。
- `turn == 0` 的档 83 / 230,对应 [future-experience §2.3](../future-experience-backlog.md)「存档系统只做了一半」(init 无条件落盘)。
- 收束钳制 244 次 = F-022 在全量数据上的表现,不是新问题。
- 推导值:修复后成功 171 / 175(`repair_failed` 的回合一定在修复过的回合里)。
  回合首过率报告没有直接给出,本轮不推算(`no_structured_tail` 是否先尝试修复,未核实)。
- **缺陷:控制台泄漏 saveId**。报告文件是干净的,但运行时 stdout 打出了 5 行
  `[box-scene] save=<UUID> 旧局` WARN —— `SessionDocument.decode` 解码《动物人生》旧局时
  `BoxSceneState` 自己打的日志,违反本 ADR「任何地方不回显标识」。
  修法(`769ee19`):`DataStatsTool` 运行期间把 engine / persistence / eventloop 三个包的 logger 置 OFF、
  结束后还原,只在工具进程内生效,不改 logback 配置;守护 = `DataStatsToolConsoleTest`(`6b7b8b8`,
  合成旧局存档,捕获 stdout + stderr,断言无 UUID、无输入文件名),去掉静默那一处即变红。

---

## 挂账

- **world-gen 结果写进轨迹**(已决 7):首过 / 修复 / 失败今天只在日志。要它得在 `WorldGenService` 旁挂一个落点,
  牵动 ADR-031 W-4「world-gen 不记」。解冻:简历要这两项且样本足够时另开一刀。
- **简历素材挂账**:等 Felix 用本工具跑出真实读数后再更新,本 ADR 不改。
  ⤷ 2026-10-15:读数已取,工程债那一条已更新为已解冻(见「实际效果」)。

## 已知代价

1. 数据含作者自玩与冒烟局,永远无法剔除(已决 4);报告首行写明。
2. 存档组跨模型版本、无法切分(已决 8)。
3. 成本估算偏低:被中止的调用缺 usage;单价取当前配置不是调用当时的价格;单局不含 world-gen(已决 12)。
4. 叙事闸只认 ≥ 4 个字符的叙事字符串,是启发式;它挡住的是「把正文写进报告」这类实现错误,不是证明。
5. 工具读的是拷贝时刻的快照;拷贝期间仍有回合在写的话,个别存档可能与轨迹相差一个回合。

## 重新审视的触发条件

- 存档或轨迹格式变更(`schemaVersion` / `TurnTrace.SCHEMA`);
- 有了能区分玩家的字段(设备 / 时间戳)→ 已决 4 可重议;
- 切库(ADR-032 挂账解冻)→ 统计应改读 pg,口径 1 回到 ADR-025 已决 3 的原始定义。

## 交叉引用

[stats-survey.md](../stats-survey.md)(底稿)· [ADR-025](ADR-025-narrative-history.md) 已决 3 ·
[ADR-031](ADR-031-turn-execution-trace-and-offline-replay.md) W-4 / W-11 · [ADR-016](ADR-016-cost-gate.md) ·
[pg-cutover-drill.md](../pg-cutover-drill.md) 第 2 步 (b) · F-036(模型版本边界)。

## 实现进度

- 本 ADR 落档(纯 docs)。
- 实现(2026-10-15,分支 `claude/clever-dijkstra-vd2eq2`,待校勘 / 未合并,未部署):
  `quota.TokenPricing`(计价公式自 `QuotaService` 原样抽出,行为不变)+ 测试侧 `persistence.DataStats` / `DataStatsTool`
  + `DataStatsTest`(19 条,合成存档 + 合成轨迹)。变异 7 条各自变红。
  ⚠️ 叙事闸那条变异只打红闸门自身的单测:今天报告只由数字与枚举生成,端到端没有一条会把正文带进报告的路径 ——
  叙事闸防的是将来有人这么写,不是今天的泄露。
- **真实读数未取**:等 Felix 按「运行方式」在线上卷拷贝上跑一次。
  ⤷ **已取(2026-10-15)**,见「实际效果」;同批修了控制台泄漏 saveId(`769ee19` / `6b7b8b8`,待校勘 / 未合并)。
