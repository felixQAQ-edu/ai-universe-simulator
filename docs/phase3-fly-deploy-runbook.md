# Fly.io 部署 Runbook · ADR-015 Slice 3 第 3 步(部署真跑 + 附录 B 冒烟)

> 平台=Fly.io,区域 **syd**(冒烟阶段 Felix 是唯一用户,悉尼延迟最低拍板体感最准;
> 国内朋友测试前 `fly volumes fork` + `fly machine clone` 到 sin,平台内单命令级)。
> 配置真理之源 = 仓库根 [`fly.toml`](../fly.toml)(全显式,不吃平台默认)。
>
> **分工红线**:所有 `fly ...` 命令与花钱确认由 **Felix 亲手敲**;本文只给精确命令文本。
> `DEEPSEEK_API_KEY` 明文不进仓库、不进对话、不进日志。

成本参照(7 天试用期内尽量完成主要验证):shared-cpu-1x 512MB 常驻 ≈ US$3.2/月、
1GB 卷 ≈ US$0.15/月——托管固定成本对 ¥200/月 预算占比极小,大头仍是 LLM 调用。

---

## 〇、前置确认(已就绪项,逐条核对即可)

```sh
fly version            # flyctl 已装
fly auth whoami        # 应显示 subaru3xx@gmail.com
git log --oneline -1   # 在 phase3/containerize 分支,含 fly.toml 的提交
```

在**仓库根目录**执行后续所有命令(fly 默认读当前目录 fly.toml)。

---

## 一、一次性创建(app + 卷)

```sh
fly apps create wanjie-ai --org personal
```
- 若报 `Name has already been taken`:app 名全局唯一 → 换名(如 `wanjie-ai-syd`),
  并同步改 `fly.toml` 第一行 `app = "..."` 后重新 commit,再继续。

```sh
fly volumes create wanjie_data --app wanjie-ai --region syd --size 1
```
- 会警告「单卷无冗余」并要确认 → 输 `y`(单副本单卷正是 ADR-015 形态,不是错)。
- **如果看到**输出里 `Region` 不是 `syd` → 错了,`fly volumes destroy` 删掉重建,
  卷区域错了机器就不会落在 syd。

---

## 二、阶段 1 · mock 冒烟(不设 key,不花 LLM 钱)

### 2.1 首次部署

```sh
fly deploy --ha=false \
  --build-arg GIT_SHA="$(git rev-parse --short HEAD)$(git diff --quiet HEAD || echo -dirty)"
```
- `--ha=false` **必带**:Fly 默认给新 app 起 2 台机做高可用,单副本内存表语义会被打破。
- `--build-arg GIT_SHA=...` **必带**:线上靠它自报家门(`/actuator/info` 的 `build.commit`),
  §3.1.5 的前置检查全指望这一条;漏了不会报错、只会显示 `unknown`(那时 SHA 检查失效,只能退回比对 bundle 哈希)。
  用 `git diff --quiet HEAD` 而非 `git diff --quiet`:后者**看不见已 `git add` 但未提交的改动**,
  会在「暂存后直接部署」这个最容易发生的场景里报出假的干净(实测确认)。
- 预期:远程构建(首次 Maven 依赖预热层无缓存,**8–15 分钟**正常;之后重建 2–5 分钟)
  → 推镜像 → 起 1 台机 → health check 通过 → `deployed successfully`。
- 部署完核对:

```sh
fly status
```
- **必须看到**:1 台 machine、region `syd`、state `started`、health `passing`。
- **如果看到** 2 台 machine → `--ha=false` 没生效,`fly scale count 1` 收回单台再继续。
- **如果看到** `auto_stop` 相关缩零行为(过几分钟机器变 stopped)→ fly.toml 的
  `auto_stop_machines = "off"` 没被吃到,停下报告,不要继续冒烟。

```sh
fly machine list   # 再确认:1 台、挂载 wanjie_data → /data
```

### 2.2 冒烟步骤(判定标准写死,结果如实记录)

**① 健康与前端**

```sh
curl -s https://wanjie-ai.fly.dev/actuator/health
curl -s https://wanjie-ai.fly.dev/actuator/health/liveness
curl -s https://wanjie-ai.fly.dev/actuator/health/readiness
curl -sI https://wanjie-ai.fly.dev/
```
- 判定:三个 health 均 `{"status":"UP"}`;`GET /` 返回 200 + `text/html`(前端同源伺服)。

**② SSE 不缓冲(附录 B ②,mock 是最苛刻载荷:逐字 echo 40ms/字,平台若缓冲此处必现形)**

先起一局拿 saveId 与选项:

```sh
curl -s -X POST https://wanjie-ai.fly.dev/api/game/init \
  -H 'Content-Type: application/json' -d '{"archetype":"rules_creepy"}'
```
- 记下响应里的 `saveId` 与 `availableActions[0].id`(mock 下通常是 `"A"`)。

再打回合流,给每行落到达时间戳(macOS 自带 perl,毫秒精度):

```sh
curl -sN -X POST https://wanjie-ai.fly.dev/api/game/<saveId>/turn \
  -H 'Content-Type: application/json' -d '{"actionId":"A"}' \
  | perl -MTime::HiRes=time -ne 'printf "%.3f  %s", time, $_'
```
- **过**:narrative 各 token 事件的时间戳**沿整个流持续散布**(相邻到达间隔量级
  ~40ms–几百 ms,肉眼看是逐字滴出来);
- **不过**:前面长时间无输出、结尾一两个时间戳簇里成批吐出全部事件(=反代整体缓冲),
  或连接在流中被掐断。
- 复跑 2–3 次防单次网络抖动误判。

**③ 不过怎么办**:停下、结果如实记进 §四记录表 → 回 Project 窗口触发换平台预案
(Railway),**不恋战**(不调参硬救、不换姿势重试到过为止)。

---

## 三、阶段 2 · 真 key,附录 B 全套

### 3.1 注入 key + 切 active(Felix 亲手)

用 stdin 导入,key 不落 shell 历史;`--stage` 只暂存不触发自动重启,部署仍由手动 deploy 控制:

```sh
fly secrets import --stage
```
然后逐行粘贴(粘贴完按回车、再按 Ctrl-D 结束;终端不回显确认属正常):
```
DEEPSEEK_API_KEY=<粘贴真实 key>
AIUNIVERSE_LLM_ACTIVE=deepseek-v4-flash
```
(active 走 secret 注入是刻意的两阶段口径:fly.toml [env] 不写它,阶段 1 吃
application.yml 默认 mock;secret 本质是加密 env,切阶段不用改已审查的 fly.toml。)

```sh
fly secrets list     # 应见两个名字(只显示摘要,无明文)
fly deploy --ha=false \
  --build-arg GIT_SHA="$(git rev-parse --short HEAD)$(git diff --quiet HEAD || echo -dirty)"
```

### 3.1.5 镜像换新前置检查(每次 `fly deploy` 后、功能冒烟前必做)

> **弯路教训立字(2026-07-22,ADR-016 首轮冒烟)**:同源单容器一个镜像同时打包 web dist + server jar,`fly deploy` 若未真正换镜像(缓存命中 / 部署失败静默 / 部署了别的 commit),线上跑的仍是旧产物——功能级冒烟会得出**假阴性**(如成本闸门「设了阈值不触发」,实为旧镜像根本没闸门代码,secret 只是没人读的 env)。当时误判为前端 header 注入 bug,靠 **bundle 哈希对比**才坐实是部署陈旧。
>
> **口径升级(2026-08-06)**:改用 **commit SHA 直接比对**——它回答的是同一个问题,但**直接问、直接答**,不必绕道产物指纹。下面的 bundle 哈希法**降为退路**(SHA 显示 `unknown` 时用),**两套口径不并存**:默认只跑 SHA 那条。

**部署命令必须带上 SHA**(否则线上无从知道自己是谁):

```sh
# GIT_SHA 带 -dirty 后缀:工作区不干净时 SHA 会撒谎(它指向的 commit 不含你未提交的改动)
fly deploy --ha=false \
  --build-arg GIT_SHA="$(git rev-parse --short HEAD)$(git diff --quiet HEAD || echo -dirty)"
```

功能冒烟**之前**先跑这条零成本检查,确认镜像真换了:

```sh
# 1) 线上自报家门
curl -s https://wanjie-ai.fly.dev/actuator/info
# 2) 本地期望值
git rev-parse --short HEAD
```

**判定(三档,别只看「有没有回值」)**:

- **`build.commit` == 本地 SHA** → 镜像确实换成了你要的那个,继续 §3.2。
- **不等** → 先按下面「SHA 不一致的判读」分辨是**纯 docs 滞后**还是**真陈旧**;判定为真陈旧就**重新 deploy,别往下验**(这正是 2026-07-22 那次的形状)。
- **`unknown` 或 `-dirty`** → `unknown` = 这次 deploy **没走上面那条命令**(漏了 `--build-arg`),此时 SHA 检查失效、**退回下面的 bundle 哈希法**;`-dirty` = 部署的是**未提交的工作区**,线上跑的东西在仓库里找不到对应 commit,冒烟结论无法复现,**先提交再重部署**。

**SHA 不一致的判读(主口径)**:

> **纯 docs commit 不重新部署 → `/actuator/info` 的 SHA 允许滞后于 `main`。**

不一致时先看差的是什么,再决定要不要重部署:

```sh
# <线上SHA> = 上面 curl 回来的 build.commit
git log <线上SHA>..HEAD --stat
```

- 差的那几个 commit **只动 `docs/`** → **不是部署陈旧,代码一致**,照常继续 §3.2。
- 只要有**任何一个** commit 碰了 `docs/` 之外的东西 → **才是真的陈旧**,重新 deploy。

按 **commit 粒度**判,不按文件或按行:一个同时动了 docs 与代码的 commit,一律判真陈旧。

> **判据刻意偏保守**——只有「只动 `docs/`」才放行,其余一律判真陈旧。误判方向是**多部署一次**(便宜);放松它则是给自己留「这个应该也不影响吧」的口子。（`.dockerignore` 实际排除的目录不止 `docs/`,但本判据不据此放宽:例如 `prompts/*.md` 在 `server/` 侧有运行时同义副本(prompt lockstep 守着),改了 `.md` 不改副本判真陈旧反而是对的。逐个论证哪个目录"其实也安全",省下的一次部署不值这个代价。）
>
> **这条口径的存在理由**:防止「SHA 检查」变成「每次 docs 提交都得重新部署」——那会把一个**便宜的取证手段**变成一条**昂贵的纪律**,人就会开始忽略它。同 CI 审计门槛那次的判据:门必须是「**红了就一定有人动手**」的（见 [工程债清单](backlog-engineering-debt.md) 第 2 层）。

> 为什么 SHA 会缺省成 `unknown` 而不是构建失败:`.dockerignore` 刻意排除 `.git`(构建上下文不带版本库),构建层无法自读 commit,只能靠 `--build-arg` 传。**让它显示 `unknown` 而不是编个假值,是刻意的**——假 SHA 会让本检查得出**假阴性**,而假阴性正是这一节要治的病。实现见 `server/pom.xml`(`build-info` + `${git.sha}`)与 `Dockerfile`(`ARG GIT_SHA`)。

**退路(仅当 `build.commit` 为 `unknown`)**——原 bundle 哈希法:

```sh
# 1) 本地把待部署分支构建一次,记下 dist 的 bundle 哈希
(cd web && npm run build) && ls web/dist/assets/index-*.js
# 2) 线上首页引用的 bundle 名应等于上面的哈希;不等 = 镜像没换,重新 deploy,别往下验
curl -s https://wanjie-ai.fly.dev/ | grep -o 'assets/index-[^"]*\.js'
```

> 退路的**已知弱点**(也是升级 SHA 的理由之一):bundle 哈希只覆盖**前端**——纯后端改动不改 dist,哈希一致并不能证明 jar 换了;且它证明不了「换成了**哪个** commit」,只能证明「与本地这次构建相同」。（另:验前端接线证据仍可用,如线上 bundle `grep -c X-Device-Id` 应回 `2` = init + turn 两处。）

### 3.1.6 secret 完整性检查(每次 `fly deploy` 后、功能冒烟前必做)

> **弯路教训立字(2026-07-29,ADR-018 刀 4 真机冒烟)**:临时 app 上冒烟时,`AIUNIVERSE_LLM_ACTIVE` **漏设**、`DEEPSEEK_API_KEY` **值有误** → 线上**静默退回 mock / 调用失败**,表现为「**生成很久然后失败**」。排查方向一开始全指向应用代码(以为是流式或超时),实际是环境配置。
>
> 这与 §3.1.5「镜像换新」是**同一族**缺陷:**环境配置的静默失效** —— 应用没崩、日志不显眼、功能冒烟只看到一个含糊的失败,而根因在容器之外。两条都属「**先证明环境是你以为的那个,再验功能**」。

功能冒烟**之前**先跑这条零成本检查:

```sh
fly secrets list --app <app 名>
```

逐项核对(**三条都要过**):

1. **该有的都在** —— 真 key 阶段必须同时看到 `DEEPSEEK_API_KEY` **与** `AIUNIVERSE_LLM_ACTIVE`。
   只设 key 不设 active = `application.yml` 默认 `active: mock` 仍生效,**线上跑的是 mock**(它不读 key,所以「key 设了」不构成任何证据)。
2. **状态皆 `Deployed`** —— 若显示 `Staged`(用了 `--stage` 后还没 deploy),secret 尚未生效,先按 §2.1 那条完整命令重新 deploy(**带 `--build-arg`**,别在这里省成 `fly deploy --ha=false`)。
3. **值确实正确** —— `fly secrets list` **只显摘要不显明文**,故它证不了值对不对。要证只有一条路:看启动日志与实际行为。

```sh
fly logs --app <app 名> | grep -i -m5 'active\|llm\|deepseek'
```

- 判定:日志须表明 provider 是 `deepseek-v4-flash` **而非 mock**;起局后叙事应是**真实生成的中文**(mock 是逐字 echo 固定文案,一眼可辨)。
- **若起局「生成很久然后失败」**:先按本节回头查 active 与 key,**不要先怀疑应用代码** —— 本次就是在这里绕了一圈。

### 3.2 附录 B 冒烟清单(顺序固定)

**⑤ active 覆盖生效**:
```sh
fly logs | grep -i -m5 'active\|llm'
```
- 判定:启动日志证明线上 provider 是 deepseek-v4-flash 而非 mock;起局后叙事为真实
  生成中文(mock 是逐字 echo 固定文案,一眼可辨)。

**⑥ 真 key 起局 + 落盘进卷**:浏览器开 `https://wanjie-ai.fly.dev/` 起一局;然后:
```sh
fly ssh console -C 'ls -la /data'
```
- 判定:出现 `<saveId>.json`(落盘真的写进了持久卷,不是容器临时层)。

**⑦ total_tokens 线上有无**(launch-plan §六挂账的尾巴):
```sh
fly logs | grep -i usage
```
- 判定并记录:`[world-gen]`/`[event-loop]` usage INFO 里 `total_tokens` 是真值还是 -1。

**⑧ 续局两档**:
- 档 1:浏览器刷新 → 「继续上局」→ 恢复到同回合(含 notice「已从上次落笔处接续」)。
- 档 2:`fly deploy --ha=false` 重新部署(容器销毁重建)→ 再续局成功 = 卷跨 deploy
  保留 + 启动回载实证。

**⑨ 出口延迟体感(syd → api.deepseek.com),记数值**:
- world-gen(起局)整体耗时:_____ s(本地真 key 历史体感对照:____)
- 单回合首 token TTFT:_____ s;逐字流速率体感:顺畅 / 卡顿
- **盯一个已知风险**:world-gen 是纯 JSON 胖调用,首字节前长时间无数据;若起局在
  ~60s 处稳定报 502/连接断 = Fly 反代 idle 超时掐了长静默连接 → 如实记录,回
  Project 窗口议(属平台硬约束②的边界情况,不现场改代码绕)。

**⑩ 真机浏览器整局(拍板项)**:Felix 手机浏览器完整玩一局融合世界
(识海遗蜕或人防工程),老规矩体感拍板。

---

## 四、冒烟结果记录(2026-07-20 回填;附录 B 全项通过)

| # | 项 | 结果 | 数值/备注 |
|---|-----|------|----------|
| ① | health 三端点 + GET / | ✅ 过 | |
| ② | SSE 不缓冲(mock 逐字) | ✅ 过 | 手机+桌面叙事逐字流;mock init 阻塞数分钟未被掐断(§⑨ 预埋 60s idle 风险未现形) |
| ⑤ | active 覆盖生效(非 mock) | ✅ 过 | |
| ⑥ | 真 key 起局 + /data 落盘 | ✅ 过 | `55e9834c` JSON 5917B,属主 appuser |
| ⑦ | total_tokens 线上有无 | ✅ 真值 | 5429 / 3675 / 3712;-1 容错备而不用,launch-plan §六挂账尾巴关闭 |
| ⑧ | 续局档 1(刷新) | ✅ 过 | |
| ⑧ | 续局档 2(redeploy 后) | ✅ 过 | 卷跨 deploy 保留实证 |
| ⑨ | world-gen 耗时 / 回合 TTFT | ✅ 过 | 首局 ~120s、次局 ~15s(本地基线 ~10s);稳态与本地持平,首局慢判为冷因素非稳定跨境代价,sin 迁移不因延迟提前 |
| ⑩ | 真机整局(Felix 拍板) | ✅ 过 | 2026-07-20 手机整局体感通过 |

另记两条部署事实:远程构建 amd64 镜像实测 **118MB**(远小于本机 arm64 520MB);首局 ~120s 为单次观察不立 FINDINGS(记 ROADMAP 日志)。

---

## 五、常见坑速查

- **区域不是 syd**:卷创建时 `--region syd` 漏了 → 机器跟着卷走,延迟体感全废。删卷重建。
- **两台机器**:`fly deploy` 忘带 `--ha=false`。`fly status` 见 2 台 → `fly scale count 1`。
- **机器会睡**:`fly status` 里机器过几分钟变 `stopped` → auto_stop 配置没生效,
  停下报告(ADR-015 硬约束①,休眠=毒药)。
- **health check 一直红、机器重启循环**:JVM 冷启没跑完就被判死;fly.toml 已给
  `grace_period = "45s"`,若仍循环看 `fly logs` 里 Boot 是否真起来了(内存 OOM 会在
  日志现形;512MB 下 JVM 默认堆 ~128MB,理论够用,OOM 则升 1GB 需重新拍板成本)。
- **secrets 粘贴后想验证**:只看 `fly secrets list` 的名字与摘要,**不要**用任何方式
  回显明文(不 `fly ssh console -C 'env'` 到聊天/截图里)。

## 六、收口(已执行,2026-07-20)

ROADMAP Slice 3 收口条 + ADR-015 附录 B 回填 ✅ 已落档;CONTEXT v1.5 回写评估结论
待回 Project 窗口对齐(本轮不动 CONTEXT)→ ff 合并 + push 等点头。
