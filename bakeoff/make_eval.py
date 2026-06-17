#!/usr/bin/env python3
"""把 out/calls.jsonl 整理成【盲评清单】+【去匿名 key】(ADR-001 §6 rubric)。

- out/blind-eval.md:只含**玩家可见内容**(世界设定 + 规则原文 + 结局标题/描述 +
  逐回合叙事),用中性 ID(W-x / P-n)打乱顺序,供人工按 5 维 rubric 实打实打分。
  刻意不含 isTrue / hiddenLogic / provider,既保持盲评也顺带复核“无泄露”。
- out/eval-key.md:ID → 真实 scenario / provider 映射 + 引擎视角(规则真伪/隐藏逻辑),
  打完分再看,用于 A3 规则质量复核。
"""
from __future__ import annotations

import json
import random
from pathlib import Path

OUT = Path(__file__).resolve().parent / "out"

RUBRIC = [
    "沉浸感与氛围(规则怪谈是否够“瘆人”)",
    "规则设计巧思(真假混合是否有解谜乐趣)",
    "叙事连贯与逻辑自洽",
    "玩家抉择的有意义程度(选项是否都像样、有取舍)",
    "中文文笔(是否生硬、有无翻译腔)",
]


def _table() -> list[str]:
    rows = ["", "| 维度 | 分(1–5) | 备注 |", "|------|:------:|------|"]
    rows += [f"| {d} |  |  |" for d in RUBRIC]
    rows += ["| **综合均分** |  | ≥4.0 达标 / <3.5 即便便宜也不做主力 |", ""]
    return rows


def main():
    rows = [json.loads(l) for l in (OUT / "calls.jsonl").open(encoding="utf-8")]
    worlds = [r for r in rows if r["step"] == "world-gen" and r["schema_ok"]]
    turns = [r for r in rows if r["step"] == "event-loop" and r["schema_ok"]]

    # 重新解析 parsed(明细行只存了 raw)
    def parse(r):
        import re
        s = re.sub(r"^```(?:json)?\s*|\s*```$", "", r["raw"].strip(), flags=re.S)
        return json.loads(s)

    rng = random.Random(42)
    rng.shuffle(worlds)  # 打乱世界顺序,降低排序偏差
    labels = [f"W-{chr(65+i)}" for i in range(len(worlds))]

    blind = ["# 盲评清单 · 规则怪谈世界生成质量(ADR-001 §6)", "",
             "> 隐去 provider 与引擎字段(isTrue/hiddenLogic)。逐条按 5 维 rubric 打 1–5 分。",
             "> 第一部分评**世界生成**(W-x),第二部分评**连推体验**(P-n)。打完再开 eval-key.md。", ""]
    key = ["# 去匿名 key(打分后再看)", "", "## 世界 ID 映射 + 引擎视角(规则真伪/隐藏逻辑)", ""]

    # ── Part 1:世界 ──
    blind += ["## 一、世界生成(各自独立打分)", ""]
    for lab, r in zip(labels, worlds):
        w = parse(r)
        wd = w["world"]
        blind += [f"### {lab}", "",
                  f"**{wd['title']}** · 危险等级 `{wd['dangerLevel']}` · 基调:{wd['tone']}", "",
                  f"{wd['background']}", "", "**规则(玩家可见原文):**"]
        import re as _re
        blind += [f"{i}. {_re.sub(r'^\s*\d+[.、]\s*', '', rl['content'])}"
                  for i, rl in enumerate(w["rules"], 1)]
        blind += ["", "**可能的结局:**"]
        blind += [f"- {e['title']}" + (f" — {e.get('description','')}" if e.get("description") else "")
                  for e in w["endings"]]
        blind += _table()
        # key:真伪 + hiddenLogic
        key += [f"### {lab} ← `{r['scenario']}` · provider `{r['provider']}`", ""]
        key += [f"- 规则{i} [{'真' if rl['isTrue'] else '假'}] {rl['content']}"
                f"  → hiddenLogic: {rl.get('hiddenLogic','')}" for i, rl in enumerate(w["rules"], 1)]
        key += [""]

    # ── Part 2:连推路径 ──
    blind += ["## 二、连推 10 回合体验(每条路径整体打一次分)", ""]
    paths = {}
    for r in turns:
        p = r["scenario"].split("-")[0]   # B1 / B2 / B3
        paths.setdefault(p, []).append(r)
    pmap = {}
    for n, (p, recs) in enumerate(sorted(paths.items()), 1):
        plab = f"P-{n}"
        pmap[plab] = p
        recs.sort(key=lambda r: int(r["scenario"].split("T")[-1]))
        blind += [f"### {plab}(连续 {len(recs)} 回合)", ""]
        for r in recs:
            t = parse(r)
            tn = r["scenario"].split("T")[-1]
            blind += [f"**第 {tn} 回合**:{t['narrative']}"]
            acts = " / ".join(f"{a['id']}.{a['text']}" for a in t.get("availableActions", []))
            blind += [f"> 当时可选:{acts}", ""]
        blind += _table()
    key += ["## 路径 ID 映射", ""]
    names = {"B1": "求生路径", "B2": "作死路径", "B3": "探索路径"}
    key += [f"- {plab} ← `{p}`({names.get(p,'')})" for plab, p in pmap.items()]

    (OUT / "blind-eval.md").write_text("\n".join(blind), encoding="utf-8")
    (OUT / "eval-key.md").write_text("\n".join(key), encoding="utf-8")
    print(f"✓ 盲评清单 → {OUT/'blind-eval.md'}  ({len(worlds)} 世界 + {len(pmap)} 路径)")
    print(f"✓ 去匿名 key → {OUT/'eval-key.md'}(打分后再看)")


if __name__ == "__main__":
    main()
