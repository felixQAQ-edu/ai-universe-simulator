"""真 key 整局集成冒烟驱动(观测用,不改服务)。给定 saveId + 初始动作,逐回合 POST /turn,
流式解析 SSE(narrative/delta/ending/error),按"危险关键词"启发挑动作以驱 hp/san 下行,
全程转录写盘。不依赖 requests,纯 urllib。

用法:python3 drive.py <init-N.json> <out.sse> [maxturns] [mode]
  mode=danger(默认,挑最危险动作驱触底) | first(挑首个,偏温和,跑 AI 合法结局)
"""
import json, sys, urllib.request, re

BASE = "http://localhost:8080"
DANGER_KW = ["直视", "打开", "进入", "搭乘", "触碰", "朗读", "回应", "开门", "无视",
             "忽略", "继续", "靠近", "查看", "探查", "拨打", "镜", "不要", "强行", "独自"]


def pick(actions, mode):
    if not actions:
        return None
    if mode == "first":
        return actions[0]["id"]
    # danger: 关键词命中最多者;并列取靠后(通常更莽)
    best, score = actions[-1], -1
    for a in actions:
        s = sum(k in (a.get("text", "")) for k in DANGER_KW)
        if s >= score:
            best, score = a, s
    return best["id"]


def turn(save_id, turn_no, action_id, sink):
    body = json.dumps({"turn": turn_no, "actionId": action_id}).encode()
    req = urllib.request.Request(f"{BASE}/api/game/{save_id}/turn", data=body,
                                 headers={"Content-Type": "application/json",
                                          "Accept": "text/event-stream"})
    narrative, delta, ending, error = [], None, None, None
    event = None
    with urllib.request.urlopen(req, timeout=120) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\n")
            sink.write(line + "\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                payload = line[5:].strip()
                try:
                    obj = json.loads(payload)
                except Exception:
                    obj = {"_raw": payload}
                if event == "narrative":
                    narrative.append(obj.get("text", ""))
                elif event == "delta":
                    delta = obj
                elif event == "ending":
                    ending = obj
                elif event == "error":
                    error = obj
    return "".join(narrative), delta, ending, error


def main():
    init_file, out_file = sys.argv[1], sys.argv[2]
    maxturns = int(sys.argv[3]) if len(sys.argv) > 3 else 14
    mode = sys.argv[4] if len(sys.argv) > 4 else "danger"
    d = json.load(open(init_file))
    save_id = d["saveId"]
    actions = d["availableActions"]
    print(f"save={save_id} mode={mode} init-actions={[a['id'] for a in actions]}")
    sink = open(out_file, "w", encoding="utf-8")
    turn_no = 0
    for _ in range(maxturns):
        aid = pick(actions, mode)
        if aid is None:
            print("无可选动作,停"); break
        sink.write(f"\n===== REQUEST turn={turn_no} action={aid} =====\n")
        narr, delta, ending, error = turn(save_id, turn_no, aid, sink)
        if error:
            print(f"  T{turn_no} action={aid} -> ERROR {error}"); break
        hp = delta.get("hp") if delta else "?"
        san = delta.get("san") if delta else "?"
        status = delta.get("status") if delta else "?"
        disc = [r.get("id") for r in (delta.get("discoveredRules") or [])] if delta else []
        print(f"  T{turn_no} act={aid} -> hp={hp} san={san} status={status} "
              f"discovered={disc} narr={len(narr)}chars" + (f" ENDING={ending.get('id')}" if ending else ""))
        if ending:
            print(f"  >>> ENDING event: id={ending.get('id')!r} title={ending.get('title')!r} "
                  f"descLen={len(ending.get('description') or '')}")
        if status == "ended" or ending:
            print("  局终。"); break
        actions = (delta.get("availableActions") or []) if delta else []
        turn_no = delta.get("turn", turn_no + 1)
    sink.close()
    print(f"转录写入 {out_file}")


if __name__ == "__main__":
    main()
