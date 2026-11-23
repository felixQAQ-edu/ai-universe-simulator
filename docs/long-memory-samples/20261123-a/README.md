# 长局自玩稿件 · 套件 20261123-a

[ADR-045](../../adr/ADR-045-long-game-selfplay-measurement.md) 刀 3 第一套的玩家可见稿,供长局记忆勘察底稿
([`docs/long-memory-survey.md`](../../long-memory-survey.md))按「槽位 + 回合号」引用。

## 这些稿件是什么,不是什么

- **本机自玩**:Felix 本机起 server(默认 profile、真 key),由 `bakeoff/selfplay/selfplay.py` 走 HTTP 驱动,与玩家同一条路。
- **选项均匀随机**:每回合从本回合选项里(《寻常》去掉「就到这里」`X`)按种子均匀抽一个。**不是真人玩家**:
  真人会追线索、回访人物,随机选择不会(ADR-045 已知代价 2)。
- **视图 3 口径**:稿件只含玩家屏幕上能看到的东西(世界段、开场、选项、叙事、落账数值、timeline),
  不含 `isTrue` / `hiddenLogic`,不含 saveId(用槽位名)。
- 不入库的同套产物(`http/` SSE 原文、`traces/` 轨迹、`store/` 存档、`t0/` 冻结 T0 存档、`summary.json`)只在 Felix 本机。

## 套件参数

| 项 | 值 |
|---|---|
| suiteId | `20261123-a`(录制 2026-11-23) |
| server commit | `5a15778`(轨迹 `commit` 字段) |
| 选项种子 | `20261122`(每槽位 `20261122:<槽位>:<attempt>`) |
| 计划 | `default`:xiu-1 60 / xiu-2 60 / xiu-long 120 / gui-1 60 / rong-1 60(识海遗蜕)/ xun-1、xun-2 到自然结局上限 120 |
| 结果 | 7 局 571 回合;补局 0;降级 2 回合;对齐失败 0;叙事不一致 0 |

每局结局与降级见 [`summary.md`](summary.md)。只有 gui-1 在 T31 结局(理智崩解);其余六局跑到目标回合上限、未结局
(两局《寻常》的原因见 ADR-045「实际效果」与 `bakeoff/FINDINGS.md` 对应条目)。

## 产出方式

按 ADR-045「运行方式」:

1. server:`AIUNIVERSE_SESSION_STORE_DIR` / `AIUNIVERSE_TRACE_DIR` 指向仓库外的套件目录,日限额闸 ¥12 作整套成本上限,
   `-Dgit.sha` 注入 commit。
2. `selfplay.py --suite-seed 20261122 --plan default` 跑完 7 个槽位,init 后立即冻结 T0 存档。
3. 测试侧 `SelfPlayDraftTool`(`exec:java`)读 `http/` + 轨迹对齐成稿,过两道扫描(敏感形态 / 隐藏字段逐字),
   写出 `drafts/`。
4. Felix 通读后,只把 `drafts/*.md`(7 份稿件 + `summary.md`)复制到本目录。复制后再扫一遍
   UUID 形态、`sk-` 形态、`hiddenLogic` / `isTrue` 字段名(另加 `isCorrect` / `groundTruth`、32 位十六进制、
   saveId / bearer / authorization / api key 字样),全部 0 命中。

⚠️ 扫描只拦敏感形态与逐字照抄的隐藏字段,拦不住改写式泄露(ADR-045 已知代价 9)。仓库公开,本目录即公开。

## 稿件怎么读

每回合一节 `## T<t>`:「模型可见原文的回合」= 回合提示词里 log 原文覆盖的回合(`Engine.LOG_KEEP = 4`,T0 开场叙事永远不在其中);
「上回合 timeline」= 模型本回合能看到的那一句摘要;其后是选项、所选、叙事全文、落账与本回合写下的 timeline。
格式定义见 ADR-045 决策 3。
