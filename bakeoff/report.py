"""产出两份文件(ADR-001 §5.6):明细 calls.jsonl + 汇总 report.md。

汇总按 provider × 指标 给对比表 + 通过/未过标注;通过线取自 ADR-001 §3。
本次只有一个 provider,表也照 provider 维度排,便于后续横评直接追加行。
"""
from __future__ import annotations

import json
import statistics
from pathlib import Path

from client import CallRecord

OUT = Path(__file__).resolve().parent / "out"


def _stat(vals):
    vals = [v for v in vals if v is not None]
    if not vals:
        return None, None
    mean = statistics.mean(vals)
    sd = statistics.pstdev(vals) if len(vals) > 1 else 0.0
    return mean, sd


def write(records: list[CallRecord], b_summary: dict, provider_label: str):
    OUT.mkdir(exist_ok=True)
    # ① 明细:每次调用一行
    with (OUT / "calls.jsonl").open("w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r.row(), ensure_ascii=False) + "\n")

    # ② 汇总指标
    # 物理调用口径(成本/延迟/可用性):含首次失败 + 修复重试,= 真实 API 调用数。
    n = len(records)
    ok = [r for r in records if r.ok]
    # 逻辑生成步口径(JSON 有效率):每步只有一条 attempt==1 记录(F-002 起首次失败也落盘)。
    steps = sum(1 for r in records if r.attempt == 1)
    schema_first_ok = sum(1 for r in records if r.attempt == 1 and r.schema_ok)
    schema_final_ok = schema_first_ok + sum(
        1 for r in records if r.attempt == 2 and r.schema_ok)
    repaired = sum(1 for r in records if r.repaired)
    leaks = sum(1 for r in records if r.leak_hits)
    err = sum(1 for r in records if not r.ok)
    ttft_mean, ttft_sd = _stat([r.ttft_s for r in ok])
    lat_mean, lat_sd = _stat([r.latency_s for r in ok])
    cost_total = sum(r.cost_cny for r in records)
    cost_turn = _stat([r.cost_cny for r in records if r.step == "event-loop"])[0]
    hit = sum(r.cache_hit_tokens for r in records)
    miss = sum(r.cache_miss_tokens for r in records)
    hit_rate = hit / (hit + miss) if (hit + miss) else 0

    def pct(x):
        return f"{x/n*100:.1f}%" if n else "—"

    def pct_steps(x):
        return f"{x/steps*100:.1f}%" if steps else "—"

    b_ok = all(s["status"] in ("ongoing", "ended") and not s["issues"]
               for s in b_summary.values()) and bool(b_summary)

    lines = [
        f"# Provider Bake-off 汇总报告 · {provider_label}",
        "",
        "> 自动生成。通过线取自 ADR-001 §3。本次为单 provider(DeepSeek V4-Flash)首跑,",
        "> 横评其余 provider 时按同结构追加列即可。中文叙事质量(指标#4)需人工盲评,见文末。",
        "",
        "## 指标对比",
        "",
        "| # | 指标 | 实测 | 通过线 | 判定 |",
        "|---|------|------|--------|------|",
        f"| 1 | JSON 有效率(首次,软门) | {pct_steps(schema_first_ok)} | ≥90% | "
        f"{_ok(schema_first_ok/steps>=0.90 if steps else False)} |",
        f"| 1 | JSON 有效率(修复后,硬门) | {pct_steps(schema_final_ok)} | ≥99.5% | "
        f"{_ok(schema_final_ok/steps>=0.995 if steps else False)} |",
        f"| 2 | 连推10回合自洽 | 见下方 B 组 | 无逻辑矛盾 | {_ok(b_ok)} |",
        f"| 3 | hiddenLogic 不泄露 | {leaks} 次泄露 | 0 泄露 | {_ok(leaks==0)} |",
        "| 4 | 中文叙事质量 | 待人工盲评 | 均分≥4.0 | ⏳ |",
        f"| 5 | 首字延迟 TTFT | {_fmt(ttft_mean)}s (σ={_fmt(ttft_sd)}) | ≤1.5–2s | "
        f"{_ok(ttft_mean is not None and ttft_mean<=2.0)} |",
        f"| 6 | 完整回合延迟 | {_fmt(lat_mean)}s (σ={_fmt(lat_sd)}) | ≤8–10s | "
        f"{_ok(lat_mean is not None and lat_mean<=10)} |",
        f"| 7 | 单回合成本 | ¥{_fmt(cost_turn,4)} | ≤¥0.01 | "
        f"{_ok(cost_turn is not None and cost_turn<=0.01)} |",
        f"| 8 | 缓存命中率(DeepSeek) | {hit_rate*100:.1f}% | 观察项 | 👀 |",
        f"| 9 | 可用性(错误率) | {pct(err)} | <1% | {_ok(err/n<0.01 if n else False)} |",
        "",
        f"物理调用 {n}(含首次失败+修复)|逻辑生成步 {steps}|成功 {len(ok)}|"
        f"触发修复重试 {repaired}|总成本 ¥{cost_total:.4f}",
        "",
        "## 场景组 B · 连推 10 回合(逐路径)",
        "",
        "| 路径 | 推进回合 | 终态 | 终 hp/san | 一致性问题 | 已触发规则 |",
        "|------|----------|------|-----------|------------|------------|",
    ]
    names = {"B1": "求生", "B2": "作死", "B3": "探索"}
    for p, s in b_summary.items():
        iss = ";".join(s["issues"]) if s["issues"] else "无"
        lines.append(
            f"| {p} {names.get(p,'')} | {s['turns_done']} | {s['status']} | "
            f"{s['final_hp']}/{s['final_san']} | {iss} | "
            f"{s['triggered_rules']} |")

    lines += [
        "",
        "## 待办:人工盲评(指标 #4 / A3 / 场景组 C·D)",
        "- 中文叙事质量:按 ADR-001 §6 rubric 对 `calls.jsonl` 中世界/回合盲评打分(隐去 provider)。",
        "- A3 规则质量:核 rules 真假混合 / hiddenLogic 自洽 / 危险等级与基调匹配。",
        "- 横评:解开 providers.py 中 Qwen/GLM 占位、填 key 与实测单价后,重跑追加对比列。",
        "- 回填:用本表实测值回填 ADR-001 §3 通过线与 §7 Decision(`/adr-author`),",
        "  并 `/roadmap-update` 在 Phase 0 进度表加一行 “provider bake-off”。",
        "",
    ]
    (OUT / "report.md").write_text("\n".join(lines), encoding="utf-8")
    return OUT / "report.md", OUT / "calls.jsonl"


def _ok(b: bool) -> str:
    return "✅" if b else "❌"


def _fmt(x, nd=2) -> str:
    return "—" if x is None else f"{x:.{nd}f}"
