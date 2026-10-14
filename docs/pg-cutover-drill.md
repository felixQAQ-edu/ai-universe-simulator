# 切库演练 runbook · ADR-032 刀 3(导入 → pg 运行 → 导出回退 → 拒启)

> 依据:[ADR-032](adr/ADR-032-pg-cutover-readiness-import-export.md) §六(演练)、「已决 · D-1 / D-2 / D-5」、
> 「切库前检查清单」、「对外口径」。
> **本演练不碰任何线上东西**:不部署、不改 `fly.toml`、不在 Fly 上建库或 secret;第 9 步与来源 (b) 只读。
> 演练通过之前,对外仍只能说「在 pg profile 下实现与测试」,**不能说「含导入 / 回退演练」**(ADR-032「对外口径」)。

## 〇、规则(先读)

- **W-11**:真实存档只在本机、仓库之外的 `~/wanjie-drill/` 里。原始文件**不进仓库、不进聊天、不交给云端会话**;
  跑完只贴第十二节那张记录表(只有数字和退出码)。
- **一行一条命令**:下面每个代码块里每一行都可以单独复制执行,没有需要整块粘贴的多行命令。
  命令里永远不出现 key 或密码的值。
- **命令在仓库根目录执行**(`scripts/`、`server/` 用相对路径)。shell 写法按 **zsh**(macOS 默认)。
- **应用运行期间不得执行 import / export**:工具不防并发写者(ADR-032 §三)。第 5、7 步的应用必须先停掉,才能做第 6 步。
- **判定**:每步写明「看到什么算过、看到什么就停」。第 3、4、6 步的工具或比对脚本**任何非零退出 → 演练未通过**
  (ADR-032「已决 · D-2」:任何非零退出都不得被当成可切库的成功演练);记下原因、**停止**,不要往下做。
  第 8 步要的恰好是非零退出,判据在那一步单独写。
- **端口**:应用用 `18080`,演练库用 `55432`,避免撞上本机已在跑的 8080 / 5432(ADR-015 那次端口被占的教训)。

目录(全部在仓库之外):

| 目录 | 用途 |
|---|---|
| `~/wanjie-drill/src` | 存档副本(导入源;只读) |
| `~/wanjie-drill/store` | 第 5 步应用的落盘目录(`pg` 下只放月账与轨迹,不碰仓库里的 `server/data`) |
| `~/wanjie-drill/export` | 第 6 步导出目标(必须空);第 7、8 步也指向它 |
| `~/wanjie-drill/reports` | 工具报告、应用日志、比对名单 |
| `~/wanjie-drill/app.jar` | 本次演练用的 jar(拷出来,固定这一份) |

## 一、准备

```sh
java -version
```
要求 21 或以上;低于 21 → 停,报告。

```sh
docker version
```
看到 `Server:` 段(有 `Version`)算过;**报错或只有 Client 段 → 停在这一步**(Docker Desktop 没起),报告。

```sh
mkdir -p ~/wanjie-drill/src ~/wanjie-drill/store ~/wanjie-drill/reports
```

```sh
export DRILL_PG_PASSWORD=$(openssl rand -hex 16)
```
```sh
echo ${#DRILL_PG_PASSWORD}
```
应打出 `32`。这个密码只活在这个终端里,演练完即弃;**不要写进任何文件或聊天**。

构建 jar(仓库现有的打包命令,与 `Dockerfile` 同一个 Maven 构建;不含前端,演练只用 API):

```sh
(cd server && ./mvnw -q -B -DskipTests package)
```
```sh
cp server/target/server-0.0.1-SNAPSHOT.jar ~/wanjie-drill/app.jar
```

⚠️ **工具只能用 jar + `PropertiesLauncher` 调用**(ADR-032 §三),**不能**拿解开的 `BOOT-INF/classes` 当 classpath 直接跑:
`META-INF/spring.factories` 在 jar 根、不在那里,`DATABASE_URL` 的解析(`PgConnectionEnvironmentPostProcessor`)
会**静默不生效**,表现为「url 未指定」(刀 2 本地踩到过)。

(可选,只有要走真模型时)设置 key —— 三行,zsh 写法;bash 的等价写法是 `read -rsp "key: " DEEPSEEK_API_KEY`:

```sh
read -s "DEEPSEEK_API_KEY?key: "
```
```sh
export DEEPSEEK_API_KEY
```
```sh
echo ${#DEEPSEEK_API_KEY}
```
只看长度(非 0 即可),**不要 echo 值**。不设 key 也能做完整个演练:第 5 步那一回合会走 mock、降级为 no-op,
回合号照样 +1 并落库,验证的是落库与 `game_event`,不是叙事。

2026-10-14 Felix 实跑时订正:加 `java -version` 一行。

## 二、第 1 步 · 起演练库

```sh
docker run -d --name wanjie-drill-pg -e POSTGRES_USER=drill -e POSTGRES_DB=wanjie_drill -e POSTGRES_PASSWORD="$DRILL_PG_PASSWORD" -p 127.0.0.1:55432:5432 postgres:16-alpine
```
```sh
for i in $(seq 30); do docker exec wanjie-drill-pg pg_isready -h 127.0.0.1 -U drill -d wanjie_drill && break; sleep 1; done
```
看到 `127.0.0.1:5432 - accepting connections` 算过(必须带 `-h 127.0.0.1` 走 TCP:镜像初始化期间有一个只听 unix socket 的临时服务器,
不带 `-h` 可能在它身上报「就绪」,随后它重启);30 秒内没有 → 停,`docker logs wanjie-drill-pg` 看原因。

## 三、第 2 步 · 拷存档、数文件

**一次演练只用一种来源**;两种都想做,第一次做完走第 10 步清理后重来。两种都**只拷目录直接下的 `*.json`**
(导入不递归,`traces/` 本就不在候选里,不拷)。

**(a) 本机存档副本**(仓库里的 `server/data`;只读它,不动原件):

```sh
cp -p server/data/*.json ~/wanjie-drill/src/
```

**(b) 线上卷的只读拷贝(可选)**:只读、不改动线上任何东西,拷到仓库外。
2026-10-14 已实跑。⚠️ `fly ssh sftp get` 要求目标目录**事先不存在**,否则报 `already there`;**不要**先 `mkdir` 它。
拷下来的文件直接落在 `fly-raw/` 下,没有 `data/` 子目录。

```sh
fly ssh sftp get -R /data ~/wanjie-drill/fly-raw -a wanjie-ai
```
```sh
cp -p ~/wanjie-drill/fly-raw/*.json ~/wanjie-drill/src/
```
(只拷顶层 `*.json`。)

数一遍并记录:

```sh
ls ~/wanjie-drill/src/*.json | wc -l
```
```sh
ls ~/wanjie-drill/src | grep -c '^quota-.*\.json$'
```
第一个数记为 **SRC_N**(源目录直接下 `*.json` 总数),第二个记为 **QUOTA_N**(月账文件数)。

2026-10-14 Felix 实跑时订正:(b) 删掉事先建 `fly-raw` 的一行(sftp 要求目标目录不存在),拷存档一行改为 `fly-raw/*.json`。

## 四、第 3 步 · 导入

```sh
SPRING_PROFILES_ACTIVE=pg SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/wanjie_drill SPRING_DATASOURCE_USERNAME=drill SPRING_DATASOURCE_PASSWORD="$DRILL_PG_PASSWORD" java -cp ~/wanjie-drill/app.jar -Dloader.main=com.aiuniverse.server.persistence.StoreMigrationTool org.springframework.boot.loader.launch.PropertiesLauncher import ~/wanjie-drill/src --report=$HOME/wanjie-drill/reports/import-1.txt; echo "exit=$?"
```

记:`imported` / `skipped`(及括号里的非存档数)/ `integrity_failed` / `不可读` / `source='import'` 行数导入前后 / `exit`。

- **算过**:`exit=0`,且 `候选 N = SRC_N`、`imported + skipped = SRC_N`、`integrity_failed = 0`、`不可读 = 0`、
  「库与源文件不一致」没有出现、`导入后 − 导入前 = imported`、非存档数 = QUOTA_N。
- **停**:`exit` 非 0(2 = 有不可读档或库与源不一致;3 = 读回不一致已整批回滚;1 = 工具 / 配置错误)。
  记下报告里的类别与计数(**不贴文件名以外的任何内容**),停止。

## 五、第 4 步 · 再导入一次(幂等)

```sh
SPRING_PROFILES_ACTIVE=pg SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/wanjie_drill SPRING_DATASOURCE_USERNAME=drill SPRING_DATASOURCE_PASSWORD="$DRILL_PG_PASSWORD" java -cp ~/wanjie-drill/app.jar -Dloader.main=com.aiuniverse.server.persistence.StoreMigrationTool org.springframework.boot.loader.launch.PropertiesLauncher import ~/wanjie-drill/src --report=$HOME/wanjie-drill/reports/import-2.txt; echo "exit=$?"
```

- **算过**:`exit` 与第 3 步相同(即 0);`imported = 0`;`重复导入` = 第 3 步的 `imported`;`source='import'` 行数导入前 = 导入后 = 第 3 步的导入后。
- **停**:任何一项不符。

## 六、第 5 步 · 以 `pg` profile 启动应用,走一回合

选一局进行中的存档(只放进 shell 变量,不打印):

```sh
export DRILL_SID=$(docker exec wanjie-drill-pg psql -U drill -d wanjie_drill -tAc "select save_id from game_session where status='ongoing' order by save_id limit 1")
```
```sh
echo ${#DRILL_SID}
```
非 0 即可。记下它这时的回合号:

```sh
export DRILL_T0=$(docker exec wanjie-drill-pg psql -U drill -d wanjie_drill -tAc "select turn from game_session where save_id='$DRILL_SID'")
```
```sh
echo $DRILL_T0
```

启动(无 key 的版本;要走真模型,在这一行最前面加 `AIUNIVERSE_LLM_ACTIVE=deepseek-v4-flash `):

```sh
SPRING_PROFILES_ACTIVE=pg SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/wanjie_drill SPRING_DATASOURCE_USERNAME=drill SPRING_DATASOURCE_PASSWORD="$DRILL_PG_PASSWORD" AIUNIVERSE_SESSION_STORE_DIR=$HOME/wanjie-drill/store java -jar ~/wanjie-drill/app.jar --server.port=18080 > ~/wanjie-drill/reports/step5.log 2>&1 &
```
```sh
export DRILL_APP_PID=$!
```
```sh
for i in $(seq 90); do curl -sf localhost:18080/actuator/health >/dev/null && echo UP && break; sleep 1; done
```
90 秒内没有 `UP` → 停,看 `step5.log` 末尾。

读启动日志三行:

```sh
grep -E '\[pg-connection\]|\[session-store:pg\] 启动回载' ~/wanjie-drill/reports/step5.log
```
- **算过**:「启动回载:载入 X 档,0 档拒载」的 X = 第 3 步 `imported`;连接来源行写的是 `来源 = spring.datasource.*`(主机 `127.0.0.1`、端口 `55432`、库 `wanjie_drill`);
  连接池行 `maximum-pool-size = 5`(未设 env 时)。
- **停**:X 不等、拒载非 0、连接来源不对。

```sh
grep -c '\[session-store\] 落盘目录' ~/wanjie-drill/reports/step5.log
```
应为 `0`(`pg` 下文件存储没被装配)。

记一次进程内存(KB;**演练观测值,本机,非 512 MB 机器,单次非压测**,ADR-032 D-5):

```sh
ps -o rss= -p $DRILL_APP_PID
```

走一回合(无 key 时约 20 秒:mock 被 ADR-024 段时限掐断后降级为 no-op 回合):

```sh
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d "{\"turn\":$DRILL_T0,\"actionId\":\"A\"}" localhost:18080/api/game/$DRILL_SID/turn
```
应打出 `200`。(若该局的选项里没有 `A`,会是 `400 illegal_action`:换一局重做本节,不要改动存档。)

换一局:

```sh
export DRILL_SID=$(docker exec wanjie-drill-pg psql -U drill -d wanjie_drill -tAc "select save_id from game_session where status='ongoing' order by save_id limit 1 offset 1")
```
然后从 `DRILL_T0` 那一行起重做;应用不用重启。

```sh
docker exec wanjie-drill-pg psql -U drill -d wanjie_drill -tAc "select turn, source from game_session where save_id='$DRILL_SID'"
```
```sh
docker exec wanjie-drill-pg psql -U drill -d wanjie_drill -tAc "select count(*), min(turn), max(turn) from game_event where save_id='$DRILL_SID'"
```
- **算过**:`game_session.turn = DRILL_T0 + 1`、`source` 仍是 `import`;`game_event` 有行,`max(turn) = DRILL_T0 + 1`(补齐规则补进 ≤4 条 + 新回合)。
- **停**:回合号没动、或 `game_event` 为 0 行。

```sh
export DRILL_T1=$(docker exec wanjie-drill-pg psql -U drill -d wanjie_drill -tAc "select turn from game_session where save_id='$DRILL_SID'")
```

停应用(**第 6 步之前必须停**):

```sh
kill $DRILL_APP_PID
```
```sh
sleep 3; ps -p $DRILL_APP_PID >/dev/null && echo 仍在运行 || echo 已停
```
要看到 `已停` 才往下。

2026-10-14 Felix 实跑时订正:补「换一局」的可复制命令,写明从 `DRILL_T0` 起重做、应用不用重启。

## 七、第 6 步 · 导出 + 比对

```sh
SPRING_PROFILES_ACTIVE=pg SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/wanjie_drill SPRING_DATASOURCE_USERNAME=drill SPRING_DATASOURCE_PASSWORD="$DRILL_PG_PASSWORD" java -cp ~/wanjie-drill/app.jar -Dloader.main=com.aiuniverse.server.persistence.StoreMigrationTool org.springframework.boot.loader.launch.PropertiesLauncher export ~/wanjie-drill/export --report=$HOME/wanjie-drill/reports/export.txt; echo "exit=$?"
```
- **算过**:`exit=0`;`库中行数 N` = 第 3 步 `imported`;`写出` = N;拒写 0;载不动 0。
- **停**:非 0。⚠️ **导出返回 1 时目标目录可能已有部分文件**(ADR-032 退出码表),须 `rm -rf ~/wanjie-drill/export` 清空后才能重跑。

排除名单 = 第 5 步走过一回合的那一局(预期「不同」)+ 月账(按设计不往返):

```sh
echo "$DRILL_SID.json" > ~/wanjie-drill/reports/compare-exclude.txt
```
```sh
ls ~/wanjie-drill/src | grep '^quota-.*\.json$' >> ~/wanjie-drill/reports/compare-exclude.txt
```
```sh
./scripts/pg-drill-compare.sh ~/wanjie-drill/src ~/wanjie-drill/export ~/wanjie-drill/reports/compare-exclude.txt; echo "exit=$?"
```
- **算过**:`exit=0`;「不同 = 0 / 一侧缺失 = 0」;相同数 = 第 3 步 `imported` − 1;排除项里 `DRILL_SID` 那一局为「不同」、月账为「只在源」。
- **停**:非 0(脚本只打文件名,不打内容)。

## 八、第 7 步 · 回退:默认 profile 读导出目录,续那一局

```sh
AIUNIVERSE_SESSION_STORE_DIR=$HOME/wanjie-drill/export java -jar ~/wanjie-drill/app.jar --server.port=18080 > ~/wanjie-drill/reports/step7.log 2>&1 &
```
```sh
export DRILL_APP_PID=$!
```
```sh
for i in $(seq 90); do curl -sf localhost:18080/actuator/health >/dev/null && echo UP && break; sleep 1; done
```
```sh
grep -E '\[session-store\] 启动回载' ~/wanjie-drill/reports/step7.log
```
- **算过**:「载入 X 档,跳过 0 个非存档文件,0 档拒载」,X = 第 6 步的「写出」。

用游标验证回合号(ADR-027:游标与服务端不相等就 409,故 409 / 200 这一对就是回合号的读数,不靠肉眼读 JSON):

```sh
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d "{\"turn\":$DRILL_T0,\"actionId\":\"A\"}" localhost:18080/api/game/$DRILL_SID/turn
```
应为 `409`(第 5 步之前的回合号已经过期)。

```sh
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d "{\"turn\":$DRILL_T1,\"actionId\":\"A\"}" localhost:18080/api/game/$DRILL_SID/turn
```
应为 `200`(服务端的回合号 = 第 5 步之后的值;回退不丢切库后的进度)。

```sh
kill $DRILL_APP_PID
```
```sh
sleep 3; ps -p $DRILL_APP_PID >/dev/null && echo 仍在运行 || echo 已停
```

## 九、第 8 步 · D-1 拒启(`pg` + 不可达的库)

先给导出目录拍一份「之前」,并放一个时间标记(比对用脚本和 `find`,不靠肉眼):

```sh
cp -Rp ~/wanjie-drill/export ~/wanjie-drill/export-before
```
```sh
touch ~/wanjie-drill/reports/d1-marker
```
```sh
sleep 2
```

指向一个不可达的库(`127.0.0.1:1` 没有人监听;URL 里故意带上真密码,好检查它不进日志)。
落盘目录指向导出目录 —— 那里有存档,若有隐藏的「回退到文件存储」,它会在这里露出「载入 N 档」:

```sh
SPRING_PROFILES_ACTIVE=pg DATABASE_URL="postgres://drill:$DRILL_PG_PASSWORD@127.0.0.1:1/wanjie_drill" AIUNIVERSE_SESSION_STORE_DIR=$HOME/wanjie-drill/export java -jar ~/wanjie-drill/app.jar --server.port=18080 > ~/wanjie-drill/reports/step8.log 2>&1; echo "exit=$?"
```
```sh
grep -c "$DRILL_PG_PASSWORD" ~/wanjie-drill/reports/step8.log
```
```sh
grep -cE '\[session-store\] (落盘目录|启动回载)' ~/wanjie-drill/reports/step8.log
```
```sh
grep -E '\[pg-connection\]|Application run failed|UnableToConnect' ~/wanjie-drill/reports/step8.log | cut -c1-200
```
```sh
find ~/wanjie-drill/export -newer ~/wanjie-drill/reports/d1-marker | wc -l
```
```sh
./scripts/pg-drill-compare.sh ~/wanjie-drill/export-before ~/wanjie-drill/export; echo "exit=$?"
```

- **算过**(这一步要的就是非零):`exit` **非 0**;密码出现次数 `0`;`[session-store]` 行数 `0`;
  原因行能看出是连接 / 迁移失败(`[pg-connection] 来源 = DATABASE_URL,主机 = 127.0.0.1,端口 = 1`、`Application run failed`、
  `FlywaySqlUnableToConnectToDbException … Connection to 127.0.0.1:1 refused`;都不含密码);`find` 计数 `0`(没有任何文件或目录被新建或改写);
  比对脚本 `exit=0`(文件个数与内容都没变)。
- **停**:`exit=0`(应用居然起来了)、密码出现、`[session-store]` 行出现、`find` 非 0、比对非 0 —— 任何一条都是 D-1 未成立。

## 十、第 9 步 · D-1 (a):读线上机器的重启策略(**只读**)

⚠️ **只读配置,不验证行为**:行为仍记为「**未在 Fly 上验证**」。**不得**重启、停机、`fly machine update` 或改任何配置。
2026-10-14 已实跑。

```sh
fly machine list -a wanjie-ai
```
下面两行里的 `ID` 要换成上一行输出 ID 列的值(直接替换这两个字母;⚠️ 不要写成带尖括号的占位符,zsh 会把 `<` 当成重定向):

```sh
fly machine status ID -a wanjie-ai --display-config
```
可选,只看 restart 那一段:

```sh
fly machine status ID -a wanjie-ai --display-config | grep -A4 -i '"restart"'
```
在输出里找 `"restart"` 一段,记下 `policy` 与 `max_retries`(若配置里**没有** `restart` 字段,记「配置中无 restart 字段,取平台默认,具体值未读到」——**不凭文档推断次数**)。

2026-10-14 Felix 实跑时订正:去掉尖括号占位符(zsh 当重定向),改为替换 ID;补可选过滤命令;去掉「未实跑」警告。

## 十一、第 10 步 · 清理

只删演练用的东西:

```sh
docker rm -f wanjie-drill-pg
```
```sh
rm -rf ~/wanjie-drill
```
```sh
unset DRILL_PG_PASSWORD DRILL_SID DRILL_T0 DRILL_T1 DRILL_APP_PID DEEPSEEK_API_KEY
```

## 十二、记录模板(跑完只贴这张;不含存档内容、saveId、正文)

| 项 | 值 |
|---|---|
| 来源 | (a) 本机 / (b) 线上只读拷贝 |
| 构建 commit(`git rev-parse --short HEAD`) | |
| SRC_N / QUOTA_N | / |
| 第 3 步 imported / skipped(非存档)/ integrity_failed / 不可读 / exit | / ( ) / / / |
| 第 3 步 import 行数 前 → 后 | → |
| 第 4 步 imported / 重复导入 / exit | / / |
| 第 5 步 载入 X / 拒载 | / |
| 第 5 步 连接来源 / 池大小 | / |
| 第 5 步 RSS(KB,演练观测值,本机,非 512 MB 机器) | |
| 第 5 步 回合 HTTP / T0 → T1 / game_event 行数 | / → / |
| 第 5 步 是否走真模型(是 / 否:mock 降级回合) | |
| 第 6 步 导出 N / 写出 / 拒写 / 载不动 / exit | / / / / |
| 第 6 步 比对 相同 / 不同 / 缺失 / exit | / / / |
| 第 7 步 载入 X / 非存档 / 拒载;游标 T0 → HTTP;T1 → HTTP | / / ; ; |
| 第 8 步 exit / 密码出现次数 / [session-store] 行数 / find 计数 / 比对 exit | / / / / |
| 第 9 步 restart policy / max_retries | / (或「未读」) |
| 结论 | 通过 / 未通过(第几步、原因类别) |

**演练通过**的判定照 ADR-032「已决 · D-2」:第 3 步退出码 0 且三项计数与源目录清点一致;第 3、4、6 步任何非零退出都不算通过。
第 9 步不影响「通过」,但它的行为部分仍留在「切库前检查清单」里,不得写成已验证。

## 十三、Felix 真实演练记录(2026-10-14)

Felix 在自己的 Mac 上按本文第 1–10 步跑完;来源 (b)。第 1–8 步全部按判定通过,第 9 步只读到 restart 配置。
只记数字与退出码,不含任何存档标识、文件名或存档内容。

| 项 | 值 |
|---|---|
| 来源 | (b) 线上卷只读拷贝(2026-10-14) |
| 构建 commit | `9899c5e` |
| SRC_N / QUOTA_N | 234 / 4 |
| 第 3 步 imported / skipped(非存档)/ integrity_failed / 不可读 / exit | 230 / 4(4)/ 0 / 0 / 0 |
| 第 3 步 import 行数 前 → 后 | 0 → 230 |
| 第 4 步 imported / 重复导入 / exit | 0 / 230 / 0 |
| 第 5 步 载入 X / 拒载 | 230 / 0;`[session-store] 落盘目录` 行数 0 |
| 第 5 步 连接来源 / 池大小 | `spring.datasource.*` / 5 |
| 第 5 步 RSS(KB,演练观测值,Felix 的 Mac,非 512 MB 机器,单次) | 214320 |
| 第 5 步 回合 HTTP / T0 → T1 / game_event 行数 | 200 / 0 → 1 / 1(该局导入时在第 0 回合,无旧回合可补) |
| 第 5 步 是否走真模型 | 是:usage 行 `model=deepseek-flash`、`reasoningChars=0`,日志无降级 |
| 第 6 步 导出 N / 写出 / 拒写 / 载不动 / exit | 230 / 230 / 0 / 0 / 0 |
| 第 6 步 比对 相同 / 不同 / 缺失 / exit | 229 / 0 / 0 / 0(排除项:走过回合那一局「不同」、4 份月账「只在源」) |
| 第 7 步 载入 X / 非存档 / 拒载;T0 → HTTP;T1 → HTTP | 230 / 0 / 0;0 → 409;1 → 200(mock 回合) |
| 第 8 步 exit / 密码出现次数 / [session-store] 行数 / find 计数 / 比对 exit | 1 / 0 / 0 / 0 / 0;原因行 = 来源 `DATABASE_URL`、端口 1 → `FlywaySqlUnableToConnectToDbException`(`Connection to 127.0.0.1:1 refused`)→ `Application run failed`,无密码 |
| 第 9 步 restart policy / max_retries | `on-failure` / `10`(只读配置;行为仍「未在 Fly 上验证」);线上 1 台机器 started、checks 1/1 |
| 结论 | 通过(按 ADR-032「已决 · D-2」:第 3、4、6 步退出码均 0;第 8 步按判定成立) |

本次实跑改了文档四处(各小节末尾标「2026-10-14 Felix 实跑时订正」):第一节加 `java -version`;
第 2 步 (b) 的 sftp 目标目录与拷贝路径;第 5 步补「换一局」命令;第 9 步去掉尖括号占位符并补过滤命令。

---

## 附 · CC 合成数据自跑记录(2026-10-14,刀 3 的「验产物」)

⚠️ **这不是 Felix 的演练,不算「导入 / 回退演练通过」**(ADR-032「对外口径」不因本节改变):数据是合成的,
环境是 CC 的 Linux 容器(bash,dockerd 现起),第 5 步没有真 key。

- **数据**:测试侧 `SyntheticSaves.writeAll` 现场生成到仓库外 `~/drill-gen`(代替「(a) 本机 `server/data`」),
  删掉三份按设计不可读的档(它们的非零退出由 CI 用例 `StoreMigrationToolTest` 覆盖),余 12 份存档 + 1 份月账 + `traces/`。
  含四个基础世界、融合、《寻常》、《动物人生》新局与旧局、已结束局、`phaseHint=SETTLING/GENERATING`、一份字节形态与 encode 不同的档。
- **与文档的差别**(只有这三处,其余命令逐字照抄):第 2 步拷的是 `~/drill-gen/*.json`;没执行设置 key 的三行(zsh 写法,容器是 bash,且走 mock);
  因为每次工具调用是新 shell,密码与 `DRILL_*` 变量经仓库外的临时文件传递,跑完即删。

| 项 | 值 |
|---|---|
| 来源 | 合成(代替 (a)) |
| 构建 commit | `5b29c2a`(本刀提交 3) |
| SRC_N / QUOTA_N | 13 / 1 |
| 第 3 步 imported / skipped(非存档)/ integrity_failed / 不可读 / exit | 12 / 1(1)/ 0 / 0 / 0 |
| 第 3 步 import 行数 前 → 后 | 0 → 12 |
| 第 4 步 imported / 重复导入 / exit | 0 / 12 / 0 |
| 第 5 步 载入 X / 拒载 | 12 / 0;`[session-store] 落盘目录` 行数 0 |
| 第 5 步 连接来源 / 池大小 | `spring.datasource.*` / 5 |
| 第 5 步 RSS(KB,演练观测值,CC 容器,非 512 MB 机器,单次) | 274036 |
| 第 5 步 回合 HTTP / T0 → T1 / game_event 行数 | 200 / 5 → 6 / 1(合成档的 log 为空,无可补齐的旧回合,只有新回合 1 条) |
| 第 5 步 是否走真模型 | 否:mock,ADR-024 段时限 20 s 自掐 → no-op 降级回合(`durMs=20048`),回合号仍 +1 并落库 |
| 第 6 步 导出 N / 写出 / 拒写 / 载不动 / exit | 12 / 12 / 0 / 0 / 0 |
| 第 6 步 比对 相同 / 不同 / 缺失 / exit | 11 / 0 / 0 / 0(排除项:走过回合那一局「不同」、月账「只在源」) |
| 第 7 步 载入 X / 非存档 / 拒载;游标 T0 → HTTP;T1 → HTTP | 12 / 0 / 0;5 → 409;6 → 200 |
| 第 8 步 exit / 密码出现次数 / [session-store] 行数 / find 计数 / 比对 exit | 1 / 0 / 0 / 0 / 0 |
| 第 9 步 restart policy / max_retries | 未读(CC 无 fly 权限;D-1 (a) 仍「未在 Fly 上验证」) |
| 第 10 步 | 容器已删,`~/wanjie-drill`、`~/drill-gen` 已删;仓库内 `server/data` 不存在(全程未被写) |
| 结论 | 合成数据下第 1–8 步全部按判定通过;第 9 步未做 |

**自跑中改过的文档(命令与实际不符的地方)**:
1. 第 1 步 `pg_isready` 加 `-h 127.0.0.1`。⚠️ 这一处**不是实跑失败**:自跑时不带 `-h` 也等到了就绪;
   改它是因为不带 `-h` 走 unix socket,会在镜像初始化期的临时服务器上报就绪(已知窗口)。改后的命令重跑过一次,输出 `127.0.0.1:5432 - accepting connections`。
2. 第 5 步连接来源行的判定写错了:日志实际是 `来源 = spring.datasource.*`,不是 `spring.datasource.url`。
3. 第 8 步找原因行的 grep 写错了:Boot 4.1 没有 `APPLICATION FAILED` 横幅、也没有「拒绝启动」字样(那是 `JdbcSessionStore` 读表失败才打的,
   这里在更早的 Flyway 连接就失败了),原 grep 只命中 `[pg-connection]` 一行。改为匹配 `Application run failed` 与 `UnableToConnect`。
