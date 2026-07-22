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
fly deploy --ha=false
```
- `--ha=false` **必带**:Fly 默认给新 app 起 2 台机做高可用,单副本内存表语义会被打破。
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
fly deploy --ha=false
```

### 3.1.5 镜像换新前置检查(每次 `fly deploy` 后、功能冒烟前必做)

> **弯路教训立字(2026-07-22,ADR-016 首轮冒烟)**:同源单容器一个镜像同时打包 web dist + server jar,`fly deploy` 若未真正换镜像(缓存命中 / 部署失败静默 / 部署了别的 commit),线上跑的仍是旧产物——功能级冒烟会得出**假阴性**(如成本闸门「设了阈值不触发」,实为旧镜像根本没闸门代码,secret 只是没人读的 env)。当时误判为前端 header 注入 bug,靠 **bundle 哈希对比**才坐实是部署陈旧。

功能冒烟**之前**先跑这条零成本检查,确认镜像真换了:

```sh
# 1) 本地把待部署分支构建一次,记下 dist 的 bundle 哈希
(cd web && npm run build) && ls web/dist/assets/index-*.js
# 2) 线上首页引用的 bundle 名应等于上面的哈希;不等 = 镜像没换,重新 deploy,别往下验
curl -s https://wanjie-ai.fly.dev/ | grep -o 'assets/index-[^"]*\.js'
```

哈希一致才继续 §3.2。（本批还可加验前端接线证据:线上 bundle `grep -c X-Device-Id` 应回 `2` = init + turn 两处。）

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
