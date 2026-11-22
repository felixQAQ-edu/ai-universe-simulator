# bakeoff/selfplay · 本机自玩长局驱动(ADR-045 刀 1)

[ADR-045](../../docs/adr/ADR-045-long-game-selfplay-measurement.md) 的驱动脚本:走玩家那条 HTTP 路把 7 个槽位的长局跑完,
存下每回合的请求、SSE 原文与回合前后 `/state`,并在开局后立刻冻结 T0 存档。
成稿(玩家可见稿 + 两道扫描)是刀 2 的 `SelfPlayDraftTool`,不在这里。

`selfplay.py` 只用标准库(同 `drive.py` 先例,不需要 venv)。

## 测试(纯离线,不碰真 key)

在仓库根目录:

```bash
python3 -m unittest discover -s bakeoff/selfplay -v
```

- 桩服务器用 `http.server` 在线程里起,SSE 全部由测试代码合成;不读 `bakeoff/recordings/` 里的任何真实录制。
- **CI 不跑这组测试**:`.github/workflows/ci.yml` 只有 server(mvn)/ web(npm)/ audit 三个 job,没有 Python job。
  本刀不改 CI;改动 `selfplay.py` 后请手动跑上面这条。

## 冒烟(`--plan smoke`,跑全套之前先跑它;Felix 本机,会花一点钱)

一局修仙、5 回合、不补局(`smoke-1`)。目的只有三件事:world-gen 实测 usage、轨迹里的 `commit`、脚本汇总行都对得上,再开全套。

终端 1 · server(在仓库根目录开始;env 与全套相同,日限额 12):

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
export SP="$HOME/aiuniverse-selfplay/$(date +%Y%m%d)-smoke"
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

终端 2 · 脚本(在仓库根目录;`--out` 必须是仓库外的**新**目录,与终端 1 的 `$SP` 相同):

```bash
export SP="$HOME/aiuniverse-selfplay/$(date +%Y%m%d)-smoke"
```
```bash
python3 bakeoff/selfplay/selfplay.py --plan smoke --out "$SP" --suite-seed 1
```
```bash
head -c 400 "$SP"/traces/*.trace.jsonl | grep -o '"commit":"[^"]*"'
```

跑完回贴三样(都不含 saveId;贴之前自己再看一眼):

1. 脚本最后的汇总行(`—— 汇总 ——` 之后那几行,只有槽位名与数字);
2. 终端 1 server 日志里 `[world-gen] usage` 那一行(内容是 token 数、`model=`、`reasoningChars=`;日志前缀里若带线程名以外的 id,删掉再贴);
3. 上面 `head -c 400 … | grep -o` 那条的输出(应是 `"commit":"<短 sha>"`,不是 `unknown`)。

## 跑一套(Felix 本机,会花钱)

server 的起法与 env 见 ADR-045「运行方式」(store / traces 指到套件目录、日限额 ¥12、`-Dgit.sha` 必带)。server 起来之后,在仓库根目录:

```bash
python3 bakeoff/selfplay/selfplay.py --out "$HOME/aiuniverse-selfplay/$(date +%Y%m%d)-a" --suite-seed 20261122 --plan default
```

开跑前会拒跑(退出码 2,**不写任何文件**)的情形:

- `--out` 落在仓库内:从它(解开符号链接后)向上任一层有 `.git`(目录或文件)即算仓库内。
  ⚠️ 若 `$HOME` 本身是个 git 仓库(dotfiles),`$HOME` 下的目录也会被拒,换一个不在任何仓库里的位置。
- `GET /actuator/info` 的 `build.commit` 缺失、为空或为 `unknown`(server 没带 `-Dgit.sha` 起)。
- `--out` 下已经有 `summary.json`(不覆盖旧套件)。
- 新套件没给 `--suite-seed`。

### 产物(全部在 `--out` 下,都含 saveId 或视图 1,只在仓库外)

| 路径 | 内容 |
|---|---|
| `store/`、`traces/` | server 写的(由 server 的 env 指过来;脚本只读 `store/` 拷 T0) |
| `t0/<slot>-<attempt>.json` | init 成功后立即从 `store/<saveId>.json` 拷出的 turn 0 存档 |
| `http/<slot>-<attempt>.jsonl` | 首行 `init`(含 `openingNarrative`);每回合一行 `turn`:请求、候选 id 与抽中的下标、SSE 原文、回合前后 `/state`、各轴落账值;409 / 503 重试另记 `retry` 行 |
| `summary.json` | 每槽位每个 attempt:种子串、saveId、t0 路径、结局回合与 id、终止原因、是否提前结局;`degradedTurns` 为 `null`(待成稿工具按轨迹补) |

控制台只打印槽位名、回合号与数字(各轴 key + 值),**不打印 saveId**;最后几行是数字汇总,可直接贴进聊天。

退出码:0 完成 / 2 拒跑 / 3 预算闸停 / 4 中止(T0 冻结失败、server 中途不可达等)。

### 规则与上限(写死在 `selfplay.py` 顶部)

| 项 | 值 | 出处 |
|---|---|---|
| 槽位与顺序 | xiu-1 60 → xiu-2 60 → xiu-long 120 → gui-1 60 → rong-1(识海遗蜕)60 → xun-1 → xun-2(到自然结局,上限 120) | 决策 1 |
| 选项 | `random.Random(f"{suiteSeed}:{slot}:{attempt}")`,每回合恰好抽一次 `randrange(len(候选))`;候选 = 本回合选项去掉 id `X` | 决策 2 |
| attempt | 从 1 起;补局 = attempt + 1 | 决策 2 |
| 提前结局 → 补局 | 结局回合 < 目标一半(60 档 < 30、120 档 < 60);《寻常》< 36。每槽位 ≤ 2 次、整套 ≤ 4 次;用尽保留最长一局,标「未达目标」 | 决策 2 |
| 开局 502 / 400 | 同一 attempt 再试 ≤ 2 次,不计补局;仍失败 → 该槽位「开局失败」,继续下一槽位 | 决策 2 |
| 409 `turn_stale` | `GET /state` 重同步后用**同一个已抽出的下标**重发(新候选比下标短时取模);同一回合连续 > 3 次 → 该局停 | 决策 2 |
| 503 `server_at_capacity` | 依次等 5 / 10 / 20 / 40 / 60 s 再试(合计 ≤ 135 s),用尽 → 该局停(开局侧同样退避,用尽算开局失败) | 本刀定 |
| 开局 429 / 回合 SSE `quota_exceeded` | 当前局停、剩余槽位不跑,`summary.json` 标「预算闸停」 | 决策 1 |
| 回合 SSE `busy` / `internal_error` / 其它 code | 该局停并写原因,继续下一槽位;不补局 | 决策 2(`internal_error` 为本刀假设,见下) |
| SSE 流里没有 `delta` | 该局停,原因 `sse_incomplete` | 本刀定 |

## 成稿(刀 2 · `SelfPlayDraftTool`,不花钱)

套件跑完(或冒烟跑完)后,在仓库根目录(`$SP` = 套件根目录,一行一条):

```bash
export SP="$HOME/aiuniverse-selfplay/20261122-a"
```
```bash
./server/mvnw -q -f server/pom.xml test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.aiuniverse.server.eventloop.SelfPlayDraftTool -Dexec.classpathScope=test -Dexec.args="--in $SP"
```

- 产出 `$SP/drafts/`:每个已开局 attempt 一份 `<slot>-<attempt>.md`(ADR-045 决策 3 模板)+ `summary.md`(不含 saveId);`summary.json` 不改。
- 写出前对全文过两道扫描(敏感形态 + 本套 saveId;隐藏字段名 + T0 存档里的隐藏字段原文),**任一命中 → 一个文件都不写**,
  控制台只报类别与计数。`drafts/` 已存在也拒(不覆盖上一次的成稿)。
- 稿件入库(`docs/long-memory-samples/<suiteId>/`)由 Felix 通读后手动 `cp`(ADR-045 待审 5 / 已知代价 9)。
- 守护测试:`./server/mvnw -q -f server/pom.xml test -Dtest=SelfPlayDraftToolTest`(全量 `mvn test` 也会跑到,CI 的 server job 覆盖)。

## 对冻结 T0 续玩(`--resume-t0`)

用于「加记忆账本前后」对比:世界逐字节相同、选项种子相同,回合生成仍随温度变化(ADR-045 勘察 7 (ii))。
脚本**不碰 server 的文件**:T0 存档由下面第 3 步拷进新 store,server 启动回载(ADR-015)把它们当续局读回。

1. 原套件目录与新套件目录(一行一条):

```bash
export OLD="$HOME/aiuniverse-selfplay/20261122-a"
```
```bash
export SP="$HOME/aiuniverse-selfplay/$(date +%Y%m%d)-b"
```
```bash
mkdir -p "$SP/store" "$SP/traces"
```

2. 把原套件每个槽位保留的那一局的 T0 拷成 `<saveId>.json`(只打印份数,不打印 saveId):

```bash
python3 -I - "$OLD" "$SP/store" <<'PY'
import json, os, shutil, sys
old, store = sys.argv[1], sys.argv[2]
s = json.load(open(os.path.join(old, 'summary.json'), encoding='utf-8'))
n = 0
for e in s['slots']:
    a = next((x for x in e['attempts'] if x['attempt'] == e['kept']), None)
    if a and a.get('saveId') and a.get('t0'):
        shutil.copyfile(os.path.join(old, a['t0']), os.path.join(store, a['saveId'] + '.json'))
        n += 1
print('拷入 T0', n)
PY
```

3. 按 ADR-045「运行方式」起 server(store / traces 指向新的 `$SP`),然后:

```bash
python3 bakeoff/selfplay/selfplay.py --out "$SP" --resume-t0 "$OLD"
```

续玩沿用原套件的 `suiteSeed` 与各槽位保留局的 attempt(与 `--suite-seed` 互斥);每槽位先 `GET /state`,
不是 turn 0 或 404 则该槽位「续玩失败」;续玩不补局、不开新局。`http/<slot>-<attempt>.jsonl` 首行是 `resume`(没有开场叙事,T0 取原套件的 init 行)。
