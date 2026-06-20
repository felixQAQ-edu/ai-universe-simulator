"""把 bake-off 录制的回合级 I/O(out/calls.jsonl)重放过 Python `Engine`,
导出一份 **golden 端状态** 夹具,供 server 侧 Java `Engine` 端口做逐字段 parity 断言。

为什么:event-loop 内核(数值结算 / 触发·发现规则 / log 压缩 / 兜底)从 Python 忠实
移植到 Java,parity 必须锁死。本脚本不打 API、不跑 LLM——只把已录制的世界 + 三路径
× 10 回合的模型产出喂给已验证的 `scenarios.Engine`,捕获端状态写成 JSON 夹具。

用法:`python3 replay_golden.py`(需 venv 里有 jsonschema)。
输出:../server/src/test/resources/golden/event-loop-golden.json
重放策略复刻 `run_scenario_B`(B1 取首 / B2 取末 / B3 取中;首回合用同一 fallback 动作集)。
"""
from __future__ import annotations

import copy
import json
import os

import scenarios

CALLS = os.path.join(os.path.dirname(__file__), "out", "calls.jsonl")
OUT = os.path.join(
    os.path.dirname(__file__), "..", "server", "src", "test", "resources",
    "golden", "event-loop-golden.json",
)

# run_scenario_B 的 fallback 动作集(A1 世界未带 availableActions 时用)。
FALLBACK = [
    {"id": "A", "text": "谨慎观察四周"},
    {"id": "B", "text": "原地等待"},
    {"id": "C", "text": "主动探查异常"},
]

# 三路径确定性选择(= _pick)。
PICK = {
    "B1": lambda a: a[0],
    "B2": lambda a: a[-1],
    "B3": lambda a: a[len(a) // 2],
}


def _rows():
    with open(CALLS, encoding="utf-8") as f:
        return [json.loads(line) for line in f]


def _raw(rows, scenario):
    hits = [r for r in rows if r["scenario"] == scenario and r["schema_ok"]]
    if not hits:
        raise SystemExit(f"夹具缺失:{scenario} 无 schema_ok 记录")
    return json.loads(hits[0]["raw"])


def main():
    rows = _rows()
    world = _raw(rows, "A1")
    fixture = {
        "_note": "由 bakeoff/replay_golden.py 从 out/calls.jsonl 生成,勿手改。",
        "world": world,
        "fallbackActions": FALLBACK,
        "paths": {},
    }

    for path, pick in PICK.items():
        eng = scenarios.Engine(world=copy.deepcopy(world))
        actions = world.get("availableActions") or FALLBACK
        turns = []
        for t in range(1, 11):
            choice = pick(actions)
            parsed = _raw(rows, f"{path}-T{t}")
            turns.append({"actionId": choice["id"], "parsed": parsed})
            eng.apply(parsed, choice["id"])
            actions = parsed.get("availableActions", actions)
            if eng.status == "ended":
                break
        fixture["paths"][path] = {
            "turns": turns,
            "expected": {
                "turn": eng.turn,
                "status": eng.status,
                "hp": eng.hp,
                "san": eng.san,
                "timeline": eng.timeline,
                "triggered": sorted(eng.triggered),
                "discovered": sorted(
                    r["id"] for r in eng.world["rules"] if r.get("discovered")
                ),
                "endingsReached": [
                    e["id"] for e in eng.world["endings"] if e.get("reached")
                ],
                "issuesCount": len(eng.issues),
                "logSummary": eng.log_summary,
                "log": eng.log,
            },
        }

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(fixture, f, ensure_ascii=False, indent=2)
    print(f"写出 {OUT}")
    for path, p in fixture["paths"].items():
        e = p["expected"]
        print(f"  {path}: turn={e['turn']} status={e['status']} "
              f"hp={e['hp']} san={e['san']} triggered={e['triggered']}")


if __name__ == "__main__":
    main()
