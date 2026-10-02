#!/usr/bin/env python3
"""ADR-028 刀 3 · 从一局新开的《动物人生》本地存档派生「停在第 10 回合」的夹具。

只读输入、输出新文件;原存档一个字节都不改。只用于本地落盘目录(默认 server/data),
不进线上卷(拒绝写到 /data 下),也不进 Docker 镜像(.dockerignore 只放行 web/ 与 server/)。

用法:
    python3 scripts/adr028_derive_box_fixture.py <源存档.json> <输出目录>
成功时向标准输出打印一行新的 saveId(输出文件名 = <saveId>.json)。

派生改动的字段(其余一律原样):
  - state.turn          → 10(纸箱局面第一回合是 11;下一回合由引擎编排为纸箱阶段 1)
  - phaseHint           → "AWAITING_ACTION"(只作取证,回载时按 status 重置,不读它)
  - boxScene            → 新局初始状态(未结算、无处境、无映射、无意图历史)
  - 文件名(saveId)    → 新 UUID(不覆盖原存档)

拒绝的输入(非零退出,不写任何文件):
  - 没有局面键 boxScene 的存档 —— 那是旧局,不得被改成新局;
  - 局面键是旧局标记 {"legacy": true};
  - 局面已开始(g≠0 / 已结算 / 有处境 / 有映射 / 有意图历史);
  - 不是单体《动物人生》、局已结束、回合号已超过 10、文档形状不对。
"""

import json
import os
import sys
import uuid

ARCHETYPE = "animal_life"
# 纸箱局面第一回合(BoxSceneTables.ANIMAL_LIFE_BOX 的 firstTurn = 11)的前一回合。
FIXTURE_TURN = 10
DOC_KEY = "boxScene"


def fail(msg):
    print("拒绝:" + msg, file=sys.stderr)
    sys.exit(2)


def initial_box_scene():
    # 与 BoxSceneState.fresh().toJson(...) 的形状一致。
    return {
        "legacy": False,
        "g": 0,
        "result": None,
        "settledTurn": None,
        "situation": None,
        "slots": {},
        "history": [],
    }


def check_unstarted(box):
    if not isinstance(box, dict):
        fail("局面键不是对象")
    if box.get("legacy") is True:
        fail("局面键是旧局标记 {\"legacy\": true};旧局不得被改成新局")
    if box.get("g") != 0:
        fail("局面已开始(g = %r)" % (box.get("g"),))
    for k in ("result", "settledTurn", "situation"):
        if box.get(k) is not None:
            fail("局面已开始(%s = %r)" % (k, box.get(k)))
    if box.get("slots"):
        fail("局面已开始(slots 非空)")
    if box.get("history"):
        fail("局面已开始(history 非空)")


def derive(doc):
    if not isinstance(doc, dict) or not isinstance(doc.get("world"), dict):
        fail("不是存档文档(缺 world 对象)")
    if doc["world"].get("archetypes") != [ARCHETYPE]:
        fail("不是单体《动物人生》存档:archetypes = %r" % (doc["world"].get("archetypes"),))
    state = doc.get("state")
    if not isinstance(state, dict) or not isinstance(state.get("turn"), int):
        fail("state.turn 缺失或不是整数")
    if state.get("status") != "ongoing":
        fail("局已结束(status = %r)" % (state.get("status"),))
    if state["turn"] > FIXTURE_TURN:
        fail("回合号 %d 已超过 %d,不是纸箱之前的局" % (state["turn"], FIXTURE_TURN))
    if DOC_KEY not in doc:
        fail("存档没有局面键 %s —— 这是旧局,不得被改成新局" % DOC_KEY)
    check_unstarted(doc[DOC_KEY])

    out = json.loads(json.dumps(doc))  # 深拷贝,原文档不动
    out["state"]["turn"] = FIXTURE_TURN
    out["phaseHint"] = "AWAITING_ACTION"
    out[DOC_KEY] = initial_box_scene()
    return out


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(64)
    src, out_dir = argv[1], argv[2]
    if os.path.abspath(out_dir).rstrip("/").startswith("/data"):
        fail("输出目录在 /data 下:夹具只放本地落盘目录,不进线上卷")
    if not os.path.isdir(out_dir):
        fail("输出目录不存在:" + out_dir)
    with open(src, encoding="utf-8") as f:
        doc = json.load(f)
    out = derive(doc)
    save_id = str(uuid.uuid4())
    target = os.path.join(out_dir, save_id + ".json")
    if os.path.exists(target):
        fail("目标已存在:" + target)
    with open(target, "x", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    print(save_id)


if __name__ == "__main__":
    main(sys.argv)
