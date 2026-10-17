# ADR-035 Slice D · 《验讫》真 key 冒烟手册

> 对象:Felix 亲手跑(花钱的动作,agent 不代跑)。依据:[ADR-035](adr/ADR-035-third-fusion-combo-yanqi.md)
> 实施步骤 5、决策 3 / 4 / 7(含修订段)、已知代价 1–4。
> 本手册所有命令、键名、文件路径都对着分支 `claude/adoring-maxwell-30lpkm`(`3f063b1` 之后:时段按回合注入、终仪落点补全)的源码核过;
> 与源码不一致时以源码为准,并回改本手册。
>
> 不新增任何生产入口。前端没有改:选择屏上**还开不出**这个组合(见第三节),开局一律用 curl。

## 一、本地起服务并接真模型

形态同 [ADR-028 手册](adr028-manual-runs.md) 第一节。环境变量(只列名字,值自己填,key 不进仓库、不进对话):

| 变量 | 用途(源码出处) |
|---|---|
| `DEEPSEEK_API_KEY` | 真模型的 key(`application.yml` → `providers.deepseek-v4-flash.api-key-env`) |
| `AIUNIVERSE_LLM_ACTIVE` | 设为 `deepseek-v4-flash`。不设 = `active: mock`,mock 只回 echo、开不出世界(`MockLlmClient`) |
| `AIUNIVERSE_SESSION_STORE_DIR` | 落盘目录(`aiuniverse.session.store-dir`,默认 `./data`)。回合轨迹默认落在它下面的 `traces/` |

```sh
# 终端 1:后端(:8080)
cd server
export DEEPSEEK_API_KEY=...            # 自己填
export AIUNIVERSE_LLM_ACTIVE=deepseek-v4-flash
export AIUNIVERSE_SESSION_STORE_DIR="$PWD/data"
mkdir -p "$AIUNIVERSE_SESSION_STORE_DIR"
./mvnw spring-boot:run

# 终端 2:前端(:5173,/api 由 Vite 代理到 :8080)—— 只在第三节「接到浏览器」时需要
cd web
npm install
npm run dev
```

起来后看启动日志两行:`[session-store] 落盘目录 = …`(确认目录)、`启动回载:载入 N 档 …`。

**配额**:`init-per-key-daily: 10`(单 IP 日开局数)、`daily-budget-cny: 6`(全局日限额)。本地直连时
IP 都是 `127.0.0.1`,A 组 5 局 + B 组几局要控制在 10 局以内;超了会回 429 `quota_exceeded`。

下文命令统一在**仓库根目录**的第三个终端执行,并先设:

```sh
export STORE=server/data          # 与 AIUNIVERSE_SESSION_STORE_DIR 一致
mkdir -p smoke-yanqi              # 本地取证目录(不提交)
```

## 二、开局(curl)

`POST /api/game/init`,融合局 body 用有序双值、host 在前(`GameController.InitRequest`):

```sh
curl -sS --max-time 400 -X POST http://localhost:8080/api/game/init -H 'Content-Type: application/json' -d '{"archetypes":["cultivation","cthulhu"]}' -o smoke-yanqi/init-1.json -w 'HTTP %{http_code}\n'
```

(命令写成一行:多行粘贴时反斜杠续行在部分终端里会断。)

- 一次 world-gen 调用,可能要几十秒。服务端单段时限 180 s(`world-gen.segment-deadline-ms`),超时回 502 `world_gen_failed`。
- 成功响应形状(`InitResponse`):`{saveId, world, openingNarrative, availableActions, attributes}`。
  - `world` 是**消毒视图**,已剥掉 `isTrue` / `hiddenLogic`。所以真假配比**必须看落盘文件**,见下。
  - `openingNarrative` **只在这次响应里有**,不进存档。所以一定要用 `-o` 把响应存下来。
- 取 saveId:

```sh
SAVE=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["saveId"])' smoke-yanqi/init-1.json); echo $SAVE
```

- 落盘全量(含 `isTrue` / `hiddenLogic`,视图 1)在 `$STORE/$SAVE.json`,开局成功即写入。

A 组开 5 局:把上面 curl 的 `init-1.json` 依次换成 `init-2.json` … `init-5.json`。

**复验省开局额度**:A 组开出来、还没推过回合的局(存档里 `state.turn` 为 0)可以直接拿来跑 B 组,不必另开。
把 `SAVE` 设成那局的 saveId,从 `t 0 <选项>` 推起;开局选项在对应的 `smoke-yanqi/init-N.json` 里。
后端重启过也能用:启动回载会把落盘目录里的存档载回内存。

## 三、接到浏览器(可选,看体感用)

### 核实结论:能进局内,不白屏

2026-10-17 用 mock 后端做过一次本地实测(手写一份 `["cultivation","cthulhu"]` 的存档放进落盘目录 →
启动回载 → 前端写存档指针 → 刷新 → 点存档条目),结果如下:

- 局内正常渲染:标题、五轴(气血 / 灵力 / 境界 / 清明 / 窥天,档名「澄澈」「未觉」等)、选项、规则须知都在。
  浏览器 Console 无 error、无 warning。
- 皮肤取 host:修仙封面、修仙金色「境界」印章。原因是 `web/src/features/game/theme/registry.ts` 的
  `resolveWorldTheme` 对未登记的融合组合**回落 host 的单体登记**(`registry.test.ts`「未登记融合组合 → 回落 host」
  守着这条)。`FUSIONS` 里没有 `cultivation×cthulhu` 只影响封面和选择卡,不影响进局。
- 续局后散文区先显示 `world.background`,并有一行「已从上次落笔处接续(第 0 回合)」。这是续局时 log 为空的既定兜底(ADR-035 勘察 e),
  **不是开场叙事**。开场叙事只在第二节存下的 init 响应里。
- 选择屏**开不出**这个组合:拖拽合法性来自后端组合表,会放行;但 `ArchetypeSelect.tsx` 的 `FusionCard`
  在 `FUSION_CARDS` 没有这一项时 `return null`,拖完不出卡。这是 Slice E 的前端清单项,本次不改。

### 步骤

1. 先用第二节的 curl 开局,拿到 `$SAVE`。不需要重启后端,开局时会话已在内存里。
2. 浏览器打开 `http://localhost:5173`,开发者工具 Console 执行(ADR-034 之后的存档指针,一局一个键
   `aiuniverse.save.<saveId>`,形状见 `web/src/state/savePointers.ts` 的 `parseSummary`;七个字段缺一个就会被跳过):

   ```js
   const sid = '<把 $SAVE 粘在这里>';
   localStorage.setItem('aiuniverse.save.' + sid, JSON.stringify({
     saveId: sid, title: '', archetypes: ['cultivation', 'cthulhu'], turn: 0, ended: false,
     lastPlayedAt: Date.now(), availability: 'available' }));
   ```

3. 刷新页面。选择屏顶部出现「我的存档」区块(ADR-034 起它取代了原来的「继续上局」按钮),条目标题是
   「上一局(续上后显示世界名)」,副标题「修仙 × 克苏鲁 · 第 0 回合 · …」。点它进局。
4. 浏览器里玩的同时,第四节 B 组需要的证据从**回合轨迹**和**存档**里取(见 4.2)。浏览器不会替你留每回合正文,
   想留正文证据就走下面的 curl 推回合。

### 最小替代:纯 curl 推回合(不需要前端,也能留下每回合正文)

`POST /api/game/{saveId}/turn`,body `{turn, actionId}`(`GameController.TurnRequest`)。
`turn` 是**客户端游标 = 服务端当前回合号**:开局后是 0,之后每推一回合 +1。不相等会回 409 `turn_stale`。
响应是 SSE,事件依次为 `narrative`(`{text}`,逐段)→ `delta`(`{turn,status,<各轴>,discoveredRules,availableActions}`),
结局回合另有 `ending`(`{id,title,description}`)(`SseTurnEventSink` / `EventLoopService.buildDelta`)。

先把 SSE 解析脚本存成文件(只需一次):

```sh
cat > smoke-yanqi/sse.py <<'PY'
import json, sys
ev = None; text = []; delta = None; ending = None
for line in open(sys.argv[1], encoding="utf-8"):
    line = line.rstrip("\n")
    if line.startswith("event:"): ev = line[6:].strip()
    elif line.startswith("data:"):
        d = json.loads(line[5:])
        if ev == "narrative": text.append(d["text"])
        elif ev == "delta": delta = d
        elif ev == "ending": ending = d
        elif ev == "error": print("ERROR", d)
print("".join(text)); print()
if delta:
    print("turn", delta["turn"], {k: v for k, v in delta.items() if k in ("hp","mana","realm","san","knowledge")})
    for a in delta["availableActions"]: print(" ", a["id"], a["text"], "|", a.get("hint",""))
if ending: print("ENDING", ending)
PY
```

推回合函数 `t <当前游标> <选项字母>`,单行定义(不用反斜杠续行);SSE 原样存进 `smoke-yanqi/$SAVE.t<N>.sse`:

```sh
t() { curl -sSN --max-time 200 -X POST "http://localhost:8080/api/game/$SAVE/turn" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d "{\"turn\":$1,\"actionId\":\"$2\"}" > "smoke-yanqi/$SAVE.t$(( $1 + 1 )).sse" && python3 smoke-yanqi/sse.py "smoke-yanqi/$SAVE.t$(( $1 + 1 )).sse"; }
t 0 A     # 第 1 回合;下一次 t 1 B,依此类推
```

开局时的选项在 `smoke-yanqi/init-N.json` 的 `availableActions` 里。同一局不要浏览器和 curl 混着推:
两边游标会对不上,另一边会收到 409。

## 四、检查清单

### 4.1 A 组 · 只开局、不玩(5 局,每局一次 world-gen 调用)

先跑一遍辅助脚本,把每局的结构项打出来(只读本地文件,不发请求):

```sh
for f in smoke-yanqi/init-*.json; do python3 - "$f" "$STORE" <<'PY'
import json, sys
init = json.load(open(sys.argv[1], encoding="utf-8")); sid = init["saveId"]
full = json.load(open(f"{sys.argv[2]}/{sid}.json", encoding="utf-8"))["world"]   # 视图 1(落盘)
print("=" * 20, sys.argv[1], sid)
rules = full["rules"]
print("rules:", len(rules), "| 旧说(isTrue:false):", sum(r.get("isTrue") is False for r in rules),
      "| 实录(无 isTrue):", sum("isTrue" not in r for r in rules))
print("id 顺序真假:", "".join("假" if r.get("isTrue") is False else "真" for r in sorted(rules, key=lambda r: r["id"])))
print("轴:", [(a["key"], a["displayName"]) for a in init["attributes"]])
print("初值:", init["world"]["character"]["attributes"])
for e in full["endings"]:
    print("ending:", e["id"], e.get("outcome"), "|", e["title"], "|", e["condition"])
leak = json.dumps(init, ensure_ascii=False)
print("init 响应含 isTrue/hiddenLogic:", "isTrue" in leak or "hiddenLogic" in leak)
PY
done
```

逐局核对,每条都记证据(saveId + 原文):

- [ ] **rules 恰好 8 条**,旧说(`isTrue:false`)4 条、实录(不带 `isTrue`)4 条,真假**交错排列**。
      看「id 顺序真假」一行:不该是「真真真真假假假假」这类按真假分前后两段的排法。
- [ ] **五轴齐全**,显示名依次为 气血 / 灵力 / 境界 / 清明 / 窥天;**窥天初值 > 0 且 ≤ 30**(未觉档)。
- [ ] **endings 恰好 4 条**:`body_fails` / `clarity_lost` / `rite_survived` / `alone_through_night`,
      outcome 依次 `failure` / `failure` / `success` / `neutral`;两条 failure 的 condition **各只绑一轴**
      (一条只出现「气血」,一条只出现「清明」,不出现「或」,不出现英文 key)。
- [ ] **玩家可见字段只用第一级词汇**:`world.title` / `world.background` / `world.tone`、`rules[].content`、
      `openingNarrative`、`availableActions` 的 `text` 与 `hint`、`character.traits` / `inventory`。
      逐字读,不得出现 网、格、收、挑拣、成色、捕捞、出栏、育肥、排酸、饵、起网。
      ⚠️ 「格」「收」是高频字(「严格」「收到」都会命中),**只能人工读**,不要靠 grep 下结论(ADR-035 已知代价 1)。
      可以出现「封台终仪」,但**不得写出它在第三夜**。允许宗门说法「封台期满 / 第七日」;
      星位一类可以作为线索出现,只要没有直说终仪在第三夜。
- [ ] **无精确数字或成功率**(守则、hint 里不得出现百分比、「X 成把握」、「达到 X 即……」);
      **无泄露**:辅助脚本「init 响应含 isTrue/hiddenLogic」一行应为 False,可见文本里也读不到 hiddenLogic 的意思。
- [ ] **记下每局用了哪条种子**。种子不进日志,只能从 `openingNarrative` / `background` 认:

      | 种子 | 认法(`WorldGenPromptBuilder.FUSION_SEED_POOLS` 第 N 条) |
      |---|---|
      | 1 | 清扫飞升台、拓下「验讫」,拓纸一角另有小「留养」;记档室三十年前那页名字被涂;管事喊封台七日 |
      | 2 | 袖口擦过雷痕,烧出与篆印一样的焦痕,「身上先有了印」 |
      | 3 | 分余泽丹,同门丹心褐色、只有你的是白的 |
      | 4 | 台角拾到老祖玉佩,背面多出一枚小「验讫」 |
      | 5 | 大典次日**入夜**在观星台值守,星比记档低一大截、第三夜就到底,与「封台七日」对不上 |

      **种子 5 那局重点看**:「留养」同门在观星台值夜(内核【「留养」落点】),而种子 5 开场就在观星台的夜里。
      看他有没有在第一夜就入场、被直接点明是「留养」。按内核,要到第二日按谱牒对账才能对出来。
      另外这条种子本身写了「第三夜就到底」,要看开场有没有因此把「终仪在第三夜」写明。

### 4.2 B 组 · 整局(至少 1 局走到封台终仪)

**每回合证据从哪来**(浏览器和 curl 两条路都能用):

- 回合轨迹 `$STORE/traces/$SAVE.trace.jsonl`,每个已落地回合一行(ADR-031 `TurnTrace`):
  `turnBefore`、`actionId`、`parsed`(模型给的结构化尾巴:`stateUpdate` 含各轴与 `timeline`、`availableActions`、
  `ending`;降级回合为 null)、`post.attributes`(落账值)、`pre`(回合前全量,含上一回合各轴)。
  ⚠️ 保密级别等于存档(含 hiddenLogic),只在本地看,不外传。
- 每回合正文:curl 路径在 `smoke-yanqi/$SAVE.t<N>.sse` 里;存档 `state.log` 只留最近 4 回合正文,
  更早的折成 `[T3选A]` 一类摘要。浏览器路径想留正文就自己从屏幕复制。

逐回合打一张表:

```sh
python3 - "$STORE/traces/$SAVE.trace.jsonl" <<'PY'
import json, sys
for line in open(sys.argv[1], encoding="utf-8"):
    t = json.loads(line); p = t.get("parsed") or {}; su = p.get("stateUpdate") or {}
    pre = t["pre"]["world"]["character"]["attributes"]
    print(f'T{t["turnBefore"]+1} 选{t["actionId"]} {t["path"]} | {su.get("timeline","(无)")}')
    print("   前", {k: pre.get(k) for k in ("hp","mana","realm","san","knowledge")}, "→ 后", t["post"]["attributes"])
    for a in p.get("availableActions") or []: print("   ", a.get("id"), a.get("text"), "|", a.get("hint",""))
    if p.get("ending"): print("    ENDING", p["ending"])
PY
```

检查项(每条附 saveId、回合号、原文摘录):

- [ ] **时段与注入一致**。服务端按本回合序号 n(= 回合前 `state.turn` + 1,即上表的 `T<n>`)每回合注入【本回合时段】
      (ADR-035 决策 4 修订段;`TurnPromptBuilder.yanqiTurnClock`):

      | n | 注入的时段 | 另加一行 |
      |---|---|---|
      | 1–4 | 第一日 · 昼 | — |
      | 5–8 | 第一日 · 夜 | — |
      | 9–12 | 第二日 · 昼 | — |
      | 13–16 | 第二日 · 夜 | — |
      | 17–20 | 第三日 · 昼 | — |
      | 21–22 | 第三日 · 夜 | — |
      | 23 | 第三日 · 夜 | 子时将至:把场面推到封台终仪前一刻,本回合末的选项给出终仪当场的抉择(含当面让运、避开) |
      | 24 | 第三日 · 夜 | 本回合即封台终仪:当回合裁决并给出结局,必须落到 (7) 中的一个结局 |
      | 25 起 | 第三日 · 夜 | 封台终仪已过:本回合必须给出结局 |

      逐回合核 timeline 是否以注入的「第 N 日 · 昼/夜」开头、叙事是否写在这个时段里;记下不一致的回合与下一回合是否被拉回。
      timeline 本身也只用第一级词汇。
- [ ] **第一、二日不点破「终仪在第三夜」**:叙事、选项、timeline 都没有直说;只能出现星位一类现象,让玩家自己推。
      宗门说法「第七日」可以出现。
- [ ] **揭示阶梯**:窥天 ≤ 30 的回合(看上表「前」里的 knowledge,那是生成该回合时注入的档),正文**不出现**第二、三级词汇;
      升到见格(31–60)后才出现 网 / 格 / 收 / 挑拣 / 成色 一类结构描写,升到知渔(61–100)后才出现 捕捞 / 育肥 / 饵 一类。
      人工读,「格」「收」同上不能 grep。窥天只在调查回合(拓印 / 对账 / 验丹 / 观星 / 识破旧说)上涨、其余回合持平,顺带记一笔。
- [ ] **恢复即饲料**:选恢复(服余泽丹 / 调息行功 / 丹房配药)的回合,对应轴「后」比「前」真的上调,**境界同回合上涨**,
      正文写出了代价。调查回合灵力下降。
- [ ] **丹房夜里不出领药类选项**:timeline 为「· 夜」的回合,选项里没有「领药 / 配药」,只给过渡行动
      (服随身丹、调息、观星、回记档室比对)。
- [ ] **第二日能按谱牒对出「留养」是谁**(观星台值夜的那位同门):记下哪一回合、什么动作对出来的。
- [ ] **第 23 回合末的选项给出终仪当场的抉择**:里面要有当面让运,也要有避开;让运**不得在第 23 回合之前谈妥或约定**。
- [ ] **封台终仪在第 24 回合发生并当回合定局**:那一回合 `parsed.ending` 非空,或清明当回合落到 0 由引擎兜底定局。
      对照五种落点(ADR-035 决策 7「终仪落点补全」修订段;回合附加指令 (7)),记下走的是哪一路:

      | 终仪当场 | 当回合 | 结局 |
      |---|---|---|
      | 避开、未让运,清明 ≥ 21 | — | `rite_survived`「守台人还」 |
      | 避开,但清明 ≤ 20(将失档) | 清明落到 0 | `clarity_lost`「叩台」 |
      | 当面让运且对方应下 | — | `alone_through_night`「独过此夜」 |
      | 当面让运但对方不应 | 当回合反噬,清明落到 0 | `clarity_lost`「叩台」 |
      | 既未避开也未让运 | 清明落到 0 | `clarity_lost`「叩台」 |

      `rite_survived` 的 condition 应写「清明≥21」(A 组辅助脚本的 ending 行可见)。第 24 回合没给结局的,看第 25 回合是否给出。
- [ ] **失败结局标对了轴**:气血归零 → `body_fails`;清明归零 → `clarity_lost`。不得错配(ADR-035 已知代价 5)。

## 五、记录格式

每条结论都附证据,不只写「通过」。建议一局一节:

```
### <saveId>(种子 N;A 组 / B 组)
- [项目名] 通过 / 不通过 / 无法判定
  证据:<文件>:<回合号 或 字段路径> 「<原文摘录>」
  (不通过时)现象:……;按 ADR-035「重新审视的触发条件」是否已触发:是 / 否
```

例:

```
- [timeline 天数推进] 不通过
  证据:traces/<saveId>.trace.jsonl T7 「第一日 · 夜:……」→ T8 「第三日 · 昼:……」(跳过第二日)
```

结果回填 ADR-035「实际效果(事后补充)」一节。揭示阶梯守住率、三日推进、被带走 → 叩台是否稳定、四条结局可达性,
这几项是该节点名要的。
