"""把 bake-off 录制的 38 条真实产出(out/calls.jsonl)过 Python 参照校验器
(`schema.validate_world` / `validate_turn`),导出 {id: verdict} 夹具,供 server 侧
手写校验器(`GameSchemas`)做 **accept-parity** 断言:Java 不会拒掉 Python 接受的真实输入。

为何离线生成:Java 运行时不该调 Python。本脚本一次性把 Python 参照判定固化成 JSON 夹具,
Java 测同样 38 条 raw 过 Java 校验器,逐条比对 verdict。

注意(关键):bake-off 的 event-loop raw 是单 `json_object`、`narrative` 是<b>内联字段</b>
(非生产流式的哨兵+尾巴),故直接 `_parse_json` 解析后即可校验,<b>无需回灌</b>——校验器只看
parsed 对象有没有 `narrative` 字段,内联即满足。

用法:`python3 gen_validator_parity.py`(需 venv 里有 jsonschema)。
输出:../server/src/test/resources/golden/validator-parity.json
意图:钉 accept-parity;reject-parity 已由 Java 侧手写破坏用例覆盖,这里不再造大批负例。
"""
from __future__ import annotations

import json
import os

import scenarios
import schema

CALLS = os.path.join(os.path.dirname(__file__), "out", "calls.jsonl")
OUT = os.path.join(
    os.path.dirname(__file__), "..", "server", "src", "test", "resources",
    "golden", "validator-parity.json",
)


def main():
    with open(CALLS, encoding="utf-8") as f:
        rows = [json.loads(line) for line in f]

    cases = []
    for idx, r in enumerate(rows):
        raw = r.get("raw")
        if not raw:
            continue
        step = r["step"]
        kind = "world" if step == "world-gen" else "turn"
        validate = schema.validate_world if kind == "world" else schema.validate_turn
        try:
            parsed = scenarios._parse_json(raw)
            errors = validate(parsed)
            parse_ok = True
        except Exception as e:  # noqa: BLE001
            errors = [f"parse_fail: {e}"]
            parse_ok = False
        cases.append({
            "id": f"{step}:{r['scenario']}:{idx}",
            "kind": kind,
            "raw": raw,
            "parseOk": parse_ok,
            "valid": parse_ok and not errors,
            "errors": errors,
        })

    fixture = {
        "_note": "由 bakeoff/gen_validator_parity.py 从 out/calls.jsonl 生成,勿手改。"
                 "verdict 为 Python schema.py 参照判定,供 Java GameSchemas accept-parity 断言。",
        "cases": cases,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(fixture, f, ensure_ascii=False, indent=2)

    valid = sum(1 for c in cases if c["valid"])
    print(f"写出 {OUT}")
    print(f"  共 {len(cases)} 条:valid={valid} invalid={len(cases) - valid}")
    by_kind = {}
    for c in cases:
        by_kind.setdefault(c["kind"], [0, 0])
        by_kind[c["kind"]][0 if c["valid"] else 1] += 1
    for k, (v, iv) in by_kind.items():
        print(f"  {k}: valid={v} invalid={iv}")


if __name__ == "__main__":
    main()
