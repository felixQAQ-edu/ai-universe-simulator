"""提示词加载 + 拼装(对齐 CONTEXT §三.6:提示词统一放 repo 根 prompts/)。

每个 .md 用 `## System` / `## User` 小节分隔系统与用户消息;用 {{VAR}} 占位插值。
"""
from __future__ import annotations

import re
from pathlib import Path

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


def _load(name: str) -> tuple[str, str]:
    text = (PROMPTS_DIR / f"{name}.md").read_text(encoding="utf-8")
    sys_m = re.search(r"##\s*System\s*\n(.*?)(?=\n##\s|\Z)", text, re.S)
    usr_m = re.search(r"##\s*User.*?\n(.*?)(?=\n##\s|\Z)", text, re.S)
    if not sys_m or not usr_m:
        raise ValueError(f"{name}.md 缺少 ## System / ## User 小节")
    return sys_m.group(1).strip(), usr_m.group(1).strip()


def _fill(t: str, vars: dict[str, str]) -> str:
    for k, v in vars.items():
        t = t.replace("{{" + k + "}}", v)
    # 去掉提示词里用于演示的 ``` 围栏,保留内容
    return t


def messages(name: str, vars: dict[str, str]) -> list[dict]:
    system, user = _load(name)
    return [
        {"role": "system", "content": _fill(system, vars)},
        {"role": "user", "content": _fill(user, vars)},
    ]
