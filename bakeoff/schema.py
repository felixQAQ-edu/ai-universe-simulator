"""v0.1 JSON Schema 校验 + 泄露检测(对齐 docs/CONTEXT.md §二、ADR-001 指标 #1/#3)。

两个校验入口:
  - validate_world(): world-gen 一次产出(world + character + rules + endings)。
  - validate_turn():  event-loop 单回合产出(narrative + 状态增量 + availableActions)。
泄露检测 detect_leak():扫描“玩家可见”文本,确认不含 isTrue / hiddenLogic 内容。
"""
from __future__ import annotations

from jsonschema import Draft202012Validator

# ── world-gen 产出(A 组)──────────────────────────────────────────────
WORLD_SCHEMA = {
    "type": "object",
    "required": ["schemaVersion", "mode", "archetypes", "world",
                 "character", "rules", "endings"],
    "additionalProperties": True,
    "properties": {
        "schemaVersion": {"const": "0.1"},
        "mode": {"enum": ["single", "hybrid"]},
        "archetypes": {"type": "array", "minItems": 1, "items": {"type": "string"}},
        "world": {
            "type": "object",
            "required": ["title", "background", "dangerLevel", "tone"],
            "properties": {
                "title": {"type": "string", "minLength": 1},
                "background": {"type": "string", "minLength": 1},
                "dangerLevel": {"enum": ["low", "medium", "high", "extreme"]},
                "tone": {"type": "string", "minLength": 1},
            },
        },
        "character": {
            "type": "object",
            "required": ["attributes"],
            "properties": {
                "attributes": {
                    "type": "object",
                    "required": ["hp", "san"],
                    "properties": {
                        "hp": {"type": "number", "minimum": 0, "maximum": 100},
                        "san": {"type": "number", "minimum": 0, "maximum": 100},
                    },
                },
                "traits": {"type": "array", "items": {"type": "string"}},
                "inventory": {"type": "array", "items": {"type": "string"}},
            },
        },
        "rules": {
            "type": "array",
            "minItems": 1,
            "items": {
                "type": "object",
                "required": ["id", "content", "isTrue", "hiddenLogic", "discovered"],
                "properties": {
                    "id": {"type": "integer"},
                    "content": {"type": "string", "minLength": 1},
                    "isTrue": {"type": "boolean"},
                    "hiddenLogic": {"type": "string"},
                    "discovered": {"type": "boolean"},
                },
            },
        },
        "endings": {
            "type": "array",
            "minItems": 1,
            "items": {
                "type": "object",
                "required": ["id", "title", "condition", "reached"],
                "properties": {
                    "id": {"type": "string"},
                    "title": {"type": "string", "minLength": 1},
                    "condition": {"type": "string", "minLength": 1},
                    "reached": {"type": "boolean"},
                },
            },
        },
    },
}

# ── event-loop 单回合产出(B 组)──────────────────────────────────────
# state 是真理之源、由引擎维护;模型每回合只回“叙事 + 增量 + 可选行动 + 结局判定”。
TURN_SCHEMA = {
    "type": "object",
    "required": ["narrative", "stateUpdate", "availableActions"],
    "additionalProperties": True,
    "properties": {
        "narrative": {"type": "string", "minLength": 1},
        "stateUpdate": {
            "type": "object",
            "required": ["hp", "san"],
            "properties": {
                "hp": {"type": "number", "minimum": 0, "maximum": 100},
                "san": {"type": "number", "minimum": 0, "maximum": 100},
                "timeline": {"type": "string"},
            },
        },
        # 本回合触发/新发现的规则 id(引擎据此置 discovered、做一致性核对)。
        "triggeredRuleIds": {"type": "array", "items": {"type": "integer"}},
        "discoveredRuleIds": {"type": "array", "items": {"type": "integer"}},
        "availableActions": {
            "type": "array",
            "minItems": 2,
            "maxItems": 4,
            "items": {
                "type": "object",
                "required": ["id", "text"],
                "properties": {
                    "id": {"type": "string"},
                    "text": {"type": "string", "minLength": 1},
                    "hint": {"type": "string"},
                },
            },
        },
        # 命中结局时返回 {reached: true, id: "..."},否则 null 或省略。
        "ending": {
            "type": ["object", "null"],
            "properties": {
                "reached": {"type": "boolean"},
                "id": {"type": "string"},
            },
        },
    },
}

_WORLD_V = Draft202012Validator(WORLD_SCHEMA)
_TURN_V = Draft202012Validator(TURN_SCHEMA)


def _errors(validator, obj) -> list[str]:
    return [
        f"{'/'.join(map(str, e.path)) or '<root>'}: {e.message}"
        for e in sorted(validator.iter_errors(obj), key=lambda e: list(e.path))
    ]


def validate_world(obj) -> list[str]:
    return _errors(_WORLD_V, obj)


def validate_turn(obj) -> list[str]:
    return _errors(_TURN_V, obj)


# ── 泄露检测(指标 #3:hiddenLogic / isTrue 绝不进玩家可见文本)──────────
LEAK_TOKENS = ("isTrue", "hiddenLogic", "isCorrect", "groundTruth")


def detect_leak(player_visible_text: str, world: dict | None = None) -> list[str]:
    """返回命中的泄露证据(空 = 干净)。

    1) 文本里出现引擎专用字段名(模型把内部结构吐给了玩家)。
    2) 文本里整段照抄了某条规则的 hiddenLogic 内容。
    """
    hits: list[str] = []
    text = player_visible_text or ""
    for tok in LEAK_TOKENS:
        if tok in text:
            hits.append(f"出现引擎字段名 {tok!r}")
    if world:
        for r in world.get("rules", []):
            hl = (r.get("hiddenLogic") or "").strip()
            if len(hl) >= 8 and hl in text:
                hits.append(f"照抄 rule#{r.get('id')} 的 hiddenLogic")
    return hits
