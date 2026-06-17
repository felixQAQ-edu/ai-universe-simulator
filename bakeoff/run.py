#!/usr/bin/env python3
"""Provider bake-off 入口(ADR-001)。

用法:
    python run.py --mock                       # 离线跑通整条管线(无需 key)
    python run.py                              # 真调用 DeepSeek V4-Flash(读 .env)
    python run.py --runs 5 --turns 10          # 自定义 A1 次数 / B 每路径回合数
    python run.py --provider deepseek-v4-flash # 换 provider(横评阶段)

产出:bakeoff/out/calls.jsonl(明细)+ bakeoff/out/report.md(汇总)。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().parent / ".env")
except ModuleNotFoundError:
    pass  # --mock 不需要 dotenv

import providers
import scenarios
import report
from client import build_client


def main() -> int:
    ap = argparse.ArgumentParser(description="Provider bake-off (ADR-001)")
    ap.add_argument("--provider", default="deepseek-v4-flash")
    ap.add_argument("--scenarios", default="A,B", help="逗号分隔,可选 A,B")
    ap.add_argument("--runs", type=int, default=5, help="A1 固定种子重复次数")
    ap.add_argument("--turns", type=int, default=10, help="B 每条路径回合数")
    ap.add_argument("--temperature", type=float, default=0.7)
    ap.add_argument("--mock", action="store_true", help="离线假数据跑通管线")
    args = ap.parse_args()

    p = providers.get(args.provider)
    print(f"▶ provider = {p.label}  model = {p.model}  mock = {args.mock}")
    try:
        client = build_client(p, mock=args.mock, temperature=args.temperature)
    except RuntimeError as e:
        print(f"✗ {e}", file=sys.stderr)
        return 2

    want = {s.strip().upper() for s in args.scenarios.split(",")}
    records = []
    world_for_B = None

    if "A" in want:
        print(f"▶ 场景组 A:A1×{args.runs} + A2×{len(scenarios.SEEDS_A2)} …")
        recs_a, world_for_B = scenarios.run_scenario_A(client, runs=args.runs)
        records += recs_a
        print(f"  A 完成,{len(recs_a)} 次调用;"
              f"{'拿到可用世界供 B 起点' if world_for_B else '⚠ 未拿到合法世界'}")

    b_summary = {}
    if "B" in want:
        if world_for_B is None:
            print("⚠ 未跑 A 或 A 未产出合法世界,B 用内置兜底世界。")
            world_for_B = scenarios._parse_json(
                __import__("client")._mock_world())  # 兜底
        print(f"▶ 场景组 B:3 路径 × {args.turns} 回合 …")
        recs_b, b_summary = scenarios.run_scenario_B(client, world_for_B,
                                                     turns=args.turns)
        records += recs_b
        print(f"  B 完成,{len(recs_b)} 次调用。")

    rpt, jsonl = report.write(records, b_summary, p.label)
    print(f"✓ 明细 → {jsonl}")
    print(f"✓ 汇总 → {rpt}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
