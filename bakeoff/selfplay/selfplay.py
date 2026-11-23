#!/usr/bin/env python3
"""ADR-045 刀 1 · 本机自玩长局驱动(stdlib only)。

走玩家那条 HTTP 路:POST /api/game/init → 逐回合 POST /api/game/{saveId}/turn(读 SSE),
每回合前后各 GET /state 一次;init 成功后立刻冻结 T0 存档。决策口径以 ADR-045 决策 1–6 为准。

产物(全部在 --out 之下,--out 必须在仓库之外):
  http/<slot>-<attempt>.jsonl   每回合:请求、SSE 原文、回合前后 /state(含 saveId,所以只在仓库外)
  t0/<slot>-<attempt>.json      init 之后立即从 <out>/store/<saveId>.json 拷出的 turn 0 存档
  summary.json                  每槽位的 attempt、结局回合与 id、终止原因;降级回合数留给成稿工具补

控制台只打印槽位名、回合号与数字,绝不打印 saveId(ADR-033 摔过)。

退出码:0 完成 / 2 拒跑(未写任何文件)/ 3 预算闸停 / 4 中止(T0 冻结失败等)/
       5 跑完但有槽位「开局失败」或「中止(…)」(状态写「完成(有失败槽位 n)」)。
用法与测试命令见同目录 README.md。
"""
from __future__ import annotations

import argparse
import dataclasses
import datetime
import json
import os
import random
import shutil
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# ── 计划(ADR-045 决策 1,顺序写死)──────────────────────────────────────


@dataclasses.dataclass(frozen=True)
class Slot:
    name: str
    request: dict
    target: int          # 普通槽位 = 目标回合;《寻常》= 上限(到自然结局为止)
    short_below: int     # 结局回合 < 它 ⇒ 提前结局,补局(普通 = 目标一半;《寻常》= 36)
    natural: bool = False  # True = 《寻常》:到自然结局为止,target 只是封顶


_XIU = {"archetype": "cultivation"}
_XUN = {"archetype": "life_sim"}

DEFAULT_PLAN: tuple[Slot, ...] = (
    Slot("xiu-1", _XIU, 60, 30),
    Slot("xiu-2", _XIU, 60, 30),
    Slot("xiu-long", _XIU, 120, 60),
    Slot("gui-1", {"archetype": "rules_creepy"}, 60, 30),
    Slot("rong-1", {"archetypes": ["cultivation", "rules_creepy"]}, 60, 30),
    Slot("xun-1", _XUN, 120, 36, natural=True),
    Slot("xun-2", _XUN, 120, 36, natural=True),
)
# 冒烟(ADR-045 刀 1b):真 key 前先用一局 5 回合核三件事 —— world-gen usage、轨迹 commit、汇总行。
# short_below 0 ⇒ 永不判提前结局,不补局。
SMOKE_PLAN: tuple[Slot, ...] = (
    Slot("smoke-1", _XIU, 5, 0),
)
PLANS = {"default": DEFAULT_PLAN, "smoke": SMOKE_PLAN}

# ── 上限(决策 2;数值写在这里,README 同步)──────────────────────────────

MAX_RERUNS_PER_SLOT = 2      # 每槽位最多补 2 局(attempt 1..3)
MAX_RERUNS_SUITE = 4         # 整套最多补 4 局
WORLDGEN_RETRIES = 2         # init 502 / 400:同一 attempt 再试至多 2 次,不计补局
BACKOFF_503 = (5, 10, 20, 40, 60)  # 503 server_at_capacity:依次等这么多秒再试,用尽即停(合计 ≤ 135 s)
STALE_RETRIES = 3            # 同一回合连续 409 turn_stale 的重同步上限
EXCLUDED_ACTION_ID = "X"     # 《寻常》「就到这里」(LifeStageTable.EXIT_ACTION_ID):不进候选

TIMEOUT_INIT = 420   # server 侧开局 DeferredResult 390 s
TIMEOUT_TURN = 150   # server 侧 SseEmitter 120 s
TIMEOUT_STATE = 30

EXIT_OK, EXIT_REFUSED, EXIT_BUDGET, EXIT_ABORT, EXIT_SLOT_FAILED = 0, 2, 3, 4, 5

STATUS_DONE = "完成"
STATUS_DONE_WITH_FAILURES = "完成(有失败槽位 {n})"
STATUS_BUDGET = "预算闸停"
STATUS_ABORT = "中止"


class Refusal(Exception):
    """开跑前拒跑:不写任何文件。"""


class Abort(Exception):
    """跑到一半必须整套停下(例如 T0 冻结失败)。"""


# ── 仓库路径闸(同 RepoPathGuard 口径:向上找到 .git 即视为仓库内)──────────


def _resolve(path: Path) -> Path:
    """绝对化 + 规范化;对已存在的最近祖先取真实路径(解开符号链接),余下部分原样接上。"""
    abs_path = Path(os.path.abspath(path))
    existing = abs_path
    while not existing.exists() and existing != existing.parent:
        existing = existing.parent
    return existing.resolve() / abs_path.relative_to(existing)


def find_git_ancestor(path: Path) -> Path | None:
    p = _resolve(path)
    for cand in (p, *p.parents):
        if (cand / ".git").exists():
            return cand
    return None


def require_outside_repo(path: Path, what: str) -> None:
    root = find_git_ancestor(path)
    if root is not None:
        raise Refusal(f"{what}在仓库目录之内:真实数据(存档、轨迹、SSE 原文)一律放在仓库之外")


# ── 控制台(只出槽位名、回合号、数字;saveId 兜底脱敏)───────────────────


class Console:
    def __init__(self, stream=None):
        self.stream = stream if stream is not None else sys.stdout
        self._secrets: set[str] = set()

    def add_secret(self, secret: str) -> None:
        if secret:
            self._secrets.add(secret)

    def redact(self, text: str) -> str:
        for s in self._secrets:
            text = text.replace(s, "<saveId>")
        return text

    def say(self, text: str) -> None:
        self.stream.write(self.redact(text) + "\n")
        self.stream.flush()


# ── HTTP ─────────────────────────────────────────────────────────────────


def parse_sse(lines) -> list[tuple[str, str]]:
    """按 SSE 规范切事件:event:/data:,空行派发;data 多行以 \\n 连接;冒号后去掉一个前导空格;: 开头为注释。"""
    events: list[tuple[str, str]] = []
    event, data = "message", []
    for raw in lines:
        line = raw.rstrip("\r\n")
        if line == "":
            if data:
                events.append((event, "\n".join(data)))
            event, data = "message", []
            continue
        if line.startswith(":"):
            continue
        field, _, value = line.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if field == "event":
            event = value
        elif field == "data":
            data.append(value)
    if data:
        events.append((event, "\n".join(data)))
    return events


def collect_turn(events: list[tuple[str, str]]) -> dict:
    """四事件:narrative 拼接(玩家所见)、delta、ending、error。"""
    narrative, delta, ending, error = [], None, None, None
    for name, payload in events:
        try:
            obj = json.loads(payload)
        except ValueError:
            obj = {"_raw": payload}
        if name == "narrative":
            narrative.append(obj.get("text", "") if isinstance(obj, dict) else "")
        elif name == "delta":
            delta = obj
        elif name == "ending":
            ending = obj
        elif name == "error":
            error = obj
    return {"narrative": "".join(narrative), "delta": delta, "ending": ending, "error": error}


class Http:
    def __init__(self, base_url: str):
        self.base = base_url.rstrip("/")
        # 本机直连:不走环境里的代理
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def _request(self, method, path, body=None, accept="application/json"):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {"Accept": accept}
        if data is not None:
            headers["Content-Type"] = "application/json"
        return urllib.request.Request(self.base + path, data=data, headers=headers, method=method)

    @staticmethod
    def _json_body(raw: bytes):
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError:
            return None

    def json(self, method, path, body=None, timeout=TIMEOUT_STATE):
        """→ (status, obj)。HTTP 错误码不抛,照样返回状态与 JSON 体。"""
        req = self._request(method, path, body)
        try:
            with self.opener.open(req, timeout=timeout) as resp:
                return resp.status, self._json_body(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, self._json_body(e.read())

    def sse(self, path, body, timeout=TIMEOUT_TURN):
        """→ (status, raw_text, events | error_json)。200 时读完整条流。"""
        req = self._request("POST", path, body, accept="text/event-stream")
        try:
            with self.opener.open(req, timeout=timeout) as resp:
                lines = [b.decode("utf-8", "replace") for b in resp]
                return resp.status, "".join(lines), parse_sse(lines)
        except urllib.error.HTTPError as e:
            return e.code, None, self._json_body(e.read())


def error_code(obj) -> str | None:
    if isinstance(obj, dict) and isinstance(obj.get("error"), dict):
        return obj["error"].get("code")
    return None


# ── 选项与补局判定(决策 2)────────────────────────────────────────────────


def make_rng(suite_seed, slot: str, attempt: int) -> random.Random:
    return random.Random(f"{suite_seed}:{slot}:{attempt}")


def candidates(actions) -> list[dict]:
    return [a for a in (actions or []) if a.get("id") != EXCLUDED_ACTION_ID]


def is_short(slot: Slot, ending_turn: int | None) -> bool:
    return ending_turn is not None and ending_turn < slot.short_below


def axis_values(meta, source) -> dict:
    """按 init 的 attributes 元数据遍历轴,不写死任何 key。"""
    out = {}
    for axis in meta or []:
        key = axis.get("key")
        if key is not None and isinstance(source, dict) and key in source:
            out[key] = source[key]
    return out


def _fmt_num(v) -> str:
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v)


def _now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).astimezone().isoformat(timespec="seconds")


def _write_json_atomic(path: Path, obj) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


class JsonlLog:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.fh = open(path, "a", encoding="utf-8")

    def write(self, obj) -> None:
        self.fh.write(json.dumps(obj, ensure_ascii=False) + "\n")
        self.fh.flush()

    def close(self) -> None:
        self.fh.close()


class BudgetStop(Exception):
    def __init__(self, where: str, message: str | None, partial: dict | None = None):
        super().__init__(where)
        self.where = where
        self.message = message
        self.partial = partial  # 回合侧闸停时,被停那一局已经玩到的结果


# ── 主体 ─────────────────────────────────────────────────────────────────


class Runner:
    def __init__(self, http: Http, out: Path, suite_seed, plan_name: str, plan, console: Console,
                 sleep=time.sleep, resume_from: Path | None = None):
        self.http = http
        self.out = out
        self.suite_seed = suite_seed
        self.plan_name = plan_name
        self.plan = plan
        self.console = console
        self.sleep = sleep
        self.resume_from = resume_from
        self.reruns_used = 0
        self.summary: dict = {}

    # ---- 汇总 ----

    def _save_summary(self) -> None:
        _write_json_atomic(self.out / "summary.json", self.summary)

    def _init_summary(self, commit: str) -> None:
        self.summary = {
            "tool": "bakeoff/selfplay/selfplay.py",
            "schema": 1,
            "mode": "resume" if self.resume_from else "new",
            "resumeFrom": str(self.resume_from) if self.resume_from else None,
            "plan": self.plan_name,
            "suiteSeed": self.suite_seed,
            "baseUrl": self.http.base,
            "commit": commit,
            "startedAt": _now(),
            "finishedAt": None,
            "status": None,
            "stop": None,
            "limits": {
                "maxRerunsPerSlot": MAX_RERUNS_PER_SLOT, "maxRerunsSuite": MAX_RERUNS_SUITE,
                "worldgenRetries": WORLDGEN_RETRIES, "backoff503": list(BACKOFF_503),
                "staleRetries": STALE_RETRIES,
            },
            "slots": [
                {
                    "slot": s.name, "request": s.request, "target": s.target, "shortBelow": s.short_below,
                    "natural": s.natural, "attempts": [], "kept": None, "result": "未跑",
                }
                for s in self.plan
            ],
        }

    def _slot_entry(self, name: str) -> dict:
        return next(e for e in self.summary["slots"] if e["slot"] == name)

    # ---- 开局 ----

    def _init(self, slot: Slot, attempt: int, log: JsonlLog):
        """→ (resp, worldgen_retries)。失败用尽 → (None, n)。429 → BudgetStop。"""
        failures, cap_tries = 0, 0
        while True:
            status, body = self.http.json("POST", "/api/game/init", slot.request, timeout=TIMEOUT_INIT)
            code = error_code(body)
            if status == 200 and isinstance(body, dict) and body.get("saveId"):
                self.console.add_secret(body["saveId"])
                log.write({"type": "init", "request": slot.request, "httpStatus": status, "response": body,
                           "worldgenRetries": failures})
                return body, failures
            log.write({"type": "init_failed", "request": slot.request, "httpStatus": status, "body": body})
            if status == 429:
                raise BudgetStop("init", (body or {}).get("error", {}).get("message") if body else None)
            if status == 503:
                if cap_tries >= len(BACKOFF_503):
                    return None, failures
                self.console.say(f"{slot.name}#{attempt} 开局 503 等 {BACKOFF_503[cap_tries]} s")
                self.sleep(BACKOFF_503[cap_tries])
                cap_tries += 1
                continue
            if status in (502, 400):
                failures += 1
                self.console.say(f"{slot.name}#{attempt} 开局 {status} 第 {failures} 次")
                if failures > WORLDGEN_RETRIES:
                    return None, failures - 1
                continue
            self.console.say(f"{slot.name}#{attempt} 开局 HTTP {status} {code or ''}".rstrip())
            return None, failures

    def _freeze_t0(self, slot: Slot, attempt: int, save_id: str) -> str:
        src = self.out / "store" / f"{save_id}.json"
        if not src.is_file():
            raise Abort("T0 冻结失败:<out>/store 下没有本局存档 —— server 的 AIUNIVERSE_SESSION_STORE_DIR 是否指向 <out>/store?")
        dst_dir = self.out / "t0"
        dst_dir.mkdir(parents=True, exist_ok=True)
        dst = dst_dir / f"{slot.name}-{attempt}.json"
        shutil.copyfile(src, dst)
        return str(dst.relative_to(self.out))

    # ---- 回合 ----

    def _state(self, save_id: str):
        return self.http.json("GET", f"/api/game/{save_id}/state", timeout=TIMEOUT_STATE)

    @staticmethod
    def _reached_ending(world) -> dict | None:
        for e in (world or {}).get("endings") or []:
            if e.get("reached"):
                return {"id": e.get("id"), "title": e.get("title")}
        return None

    def _play(self, slot: Slot, attempt: int, save_id: str, start: dict, log: JsonlLog) -> dict:
        """从 start(init 响应或 /state 响应)接着玩。→ 本局结果 dict。"""
        rng = make_rng(self.suite_seed, slot.name, attempt)
        meta = start.get("attributes") or []
        actions = start.get("availableActions") or []
        cursor = ((start.get("world") or {}).get("state") or {}).get("turn", 0)
        tag = f"{slot.name}#{attempt}"
        result = {"turnsPlayed": cursor, "endingTurn": None, "endingId": None, "endingTitle": None,
                  "terminated": None, "detail": None}

        def finish(reason, detail=None):
            result["turnsPlayed"] = cursor
            result["terminated"] = reason
            result["detail"] = detail
            return result

        status0 = ((start.get("world") or {}).get("state") or {}).get("status")
        if status0 == "ended":
            return finish("already_ended")

        while True:
            if cursor >= slot.target:
                return finish("cap_reached" if slot.natural else "target_reached")
            cands = candidates(actions)
            if not cands:
                return finish("no_actions")
            idx = rng.randrange(len(cands))  # 每回合恰好抽一次;重试沿用这一下标
            stale, cap_tries = 0, 0
            while True:
                pre_status, pre = self._state(save_id)
                if pre_status != 200:
                    return finish("state_failed", f"HTTP {pre_status} {error_code(pre) or ''}".rstrip())
                action_id = cands[idx % len(cands)]["id"]
                req = {"turn": cursor, "actionId": action_id}
                status, raw, payload = self.http.sse(f"/api/game/{save_id}/turn", req, timeout=TIMEOUT_TURN)
                if status == 409 and error_code(payload) == "turn_stale":
                    stale += 1
                    log.write({"type": "retry", "reason": "turn_stale", "cursor": cursor, "request": req,
                               "httpStatus": status, "pre": pre})
                    if stale > STALE_RETRIES:
                        return finish("turn_stale_loop")
                    st_status, st = self._state(save_id)
                    if st_status != 200:
                        return finish("state_failed", f"HTTP {st_status}")
                    cursor = ((st.get("world") or {}).get("state") or {}).get("turn", cursor)
                    actions = st.get("availableActions") or []
                    self.console.say(f"{tag} T{cursor + 1} 409 重同步")
                    if ((st.get("world") or {}).get("state") or {}).get("status") == "ended":
                        ending = self._reached_ending(st.get("world"))
                        result["endingTurn"] = cursor
                        result["endingId"] = (ending or {}).get("id")
                        result["endingTitle"] = (ending or {}).get("title")
                        return finish("ending")
                    if cursor >= slot.target:
                        return finish("cap_reached" if slot.natural else "target_reached")
                    cands = candidates(actions)
                    if not cands:
                        return finish("no_actions")
                    continue
                if status == 503:
                    log.write({"type": "retry", "reason": "server_at_capacity", "cursor": cursor, "request": req,
                               "httpStatus": status})
                    if cap_tries >= len(BACKOFF_503):
                        return finish("server_at_capacity")
                    self.console.say(f"{tag} T{cursor + 1} 503 等 {BACKOFF_503[cap_tries]} s")
                    self.sleep(BACKOFF_503[cap_tries])
                    cap_tries += 1
                    continue
                if status != 200:
                    log.write({"type": "turn", "cursor": cursor, "turn": cursor + 1, "request": req,
                               "httpStatus": status, "body": payload, "pre": pre})
                    return finish("http_error", f"HTTP {status} {error_code(payload) or ''}".rstrip())
                break

            ev = collect_turn(payload)
            post_status, post = self._state(save_id)
            log.write({
                "type": "turn", "cursor": cursor, "turn": cursor + 1,
                "candidates": [a.get("id") for a in cands], "index": idx, "request": req,
                "httpStatus": status, "pre": pre, "sse": raw,
                "post": post if post_status == 200 else {"httpStatus": post_status},
                "axes": axis_values(meta, ev["delta"]),
            })
            err = ev["error"]
            if err is not None:
                code = err.get("code") if isinstance(err, dict) else None
                if code == "quota_exceeded":
                    raise BudgetStop("turn", err.get("message"), partial=finish("quota_exceeded"))
                return finish(code or "sse_error")
            delta = ev["delta"]
            if not isinstance(delta, dict):
                return finish("sse_incomplete")
            cursor = delta.get("turn", cursor + 1)
            actions = delta.get("availableActions") or []
            nums = " ".join(f"{k} {_fmt_num(v)}" for k, v in axis_values(meta, delta).items())
            self.console.say(f"{tag} T{cursor} {nums}".rstrip())
            if ev["ending"] is not None or delta.get("status") == "ended":
                ending = ev["ending"] if isinstance(ev["ending"], dict) else None
                if ending is None and post_status == 200:
                    ending = self._reached_ending(post.get("world"))
                result["endingTurn"] = cursor
                result["endingId"] = (ending or {}).get("id")
                result["endingTitle"] = (ending or {}).get("title")
                self.console.say(f"{tag} 结局 T{cursor}")
                return finish("ending")

    # ---- 槽位 ----

    @staticmethod
    def _absorb(rec: dict, slot: Slot, res: dict | None) -> None:
        if res:
            rec.update({k: res[k] for k in ("turnsPlayed", "endingTurn", "endingId", "endingTitle",
                                             "terminated", "detail")})
        rec["short"] = is_short(slot, rec["endingTurn"])

    def _record(self, entry: dict, rec: dict) -> None:
        entry["attempts"].append(rec)
        played = [a for a in entry["attempts"] if a.get("saveId")]
        if played:
            best = max(played, key=lambda a: (a.get("turnsPlayed") or 0, -a["attempt"]))
            entry["kept"] = best["attempt"]
        self._save_summary()

    @staticmethod
    def _slot_result(slot: Slot, entry: dict) -> str:
        kept = next((a for a in entry["attempts"] if a["attempt"] == entry["kept"]), None)
        if kept is None:
            return "开局失败"
        if kept["terminated"] in ("ending", "target_reached", "cap_reached", "already_ended"):
            return "未达目标" if is_short(slot, kept["endingTurn"]) else "达标"
        return f"中止({kept['terminated']})"

    def _run_slot_new(self, slot: Slot) -> None:
        entry = self._slot_entry(slot.name)
        attempt = 1
        while True:
            seed = f"{self.suite_seed}:{slot.name}:{attempt}"
            log = JsonlLog(self.out / "http" / f"{slot.name}-{attempt}.jsonl")
            rec = {"attempt": attempt, "seed": seed, "saveId": None, "t0": None,
                   "http": f"http/{slot.name}-{attempt}.jsonl", "worldgenRetries": 0,
                   "turnsPlayed": 0, "endingTurn": None, "endingId": None, "endingTitle": None,
                   "short": False, "terminated": None, "detail": None, "degradedTurns": None}
            try:
                try:
                    resp, retries = self._init(slot, attempt, log)
                except BudgetStop:
                    rec["terminated"] = "quota_exceeded"
                    self._record(entry, rec)
                    raise
                rec["worldgenRetries"] = retries
                if resp is None:
                    rec["terminated"] = "init_failed"
                    self._record(entry, rec)
                    entry["result"] = "开局失败"
                    self._save_summary()
                    return
                save_id = resp["saveId"]
                rec["saveId"] = save_id
                try:
                    rec["t0"] = self._freeze_t0(slot, attempt, save_id)
                except Abort:
                    rec["terminated"] = "t0_failed"
                    self._record(entry, rec)
                    entry["result"] = "T0 冻结失败"
                    raise
                self.console.say(f"{slot.name}#{attempt} 开局")
                try:
                    res = self._play(slot, attempt, save_id, resp, log)
                except BudgetStop as stop:
                    self._absorb(rec, slot, stop.partial)
                    rec["terminated"] = "quota_exceeded"
                    self._record(entry, rec)
                    raise
            finally:
                log.close()
            self._absorb(rec, slot, res)
            self._record(entry, rec)
            if (rec["short"] and attempt - 1 < MAX_RERUNS_PER_SLOT
                    and self.reruns_used < MAX_RERUNS_SUITE):
                self.reruns_used += 1
                self.console.say(f"{slot.name}#{attempt} 提前结局 T{rec['endingTurn']} 补局")
                attempt += 1
                continue
            entry["result"] = self._slot_result(slot, entry)
            self._save_summary()
            return

    def _run_slot_resume(self, slot: Slot, old: dict | None) -> None:
        entry = self._slot_entry(slot.name)
        kept = None
        if old and old.get("kept") is not None:
            kept = next((a for a in old.get("attempts", []) if a.get("attempt") == old["kept"]), None)
        if not kept or not kept.get("saveId"):
            entry["result"] = "未跑(原套件无 T0)"
            self._save_summary()
            return
        attempt, save_id = kept["attempt"], kept["saveId"]
        self.console.add_secret(save_id)
        log = JsonlLog(self.out / "http" / f"{slot.name}-{attempt}.jsonl")
        rec = {"attempt": attempt, "seed": f"{self.suite_seed}:{slot.name}:{attempt}", "saveId": save_id,
               "t0": kept.get("t0"), "resumedFromT0": True, "http": f"http/{slot.name}-{attempt}.jsonl",
               "worldgenRetries": 0, "turnsPlayed": 0, "endingTurn": None, "endingId": None,
               "endingTitle": None, "short": False, "terminated": None, "detail": None, "degradedTurns": None}
        try:
            status, st = self._state(save_id)
            log.write({"type": "resume", "httpStatus": status, "response": st})
            turn0 = ((st or {}).get("world") or {}).get("state", {}).get("turn") if status == 200 else None
            if status != 200 or turn0 != 0:
                rec["terminated"] = "resume_failed"
                rec["detail"] = f"HTTP {status}" if status != 200 else f"turn {turn0}"
                self._record(entry, rec)
                entry["result"] = "续玩失败"
                self._save_summary()
                return
            self.console.say(f"{slot.name}#{attempt} 续玩 T0")
            try:
                res = self._play(slot, attempt, save_id, st, log)
            except BudgetStop as stop:
                self._absorb(rec, slot, stop.partial)
                rec["terminated"] = "quota_exceeded"
                self._record(entry, rec)
                raise
        finally:
            log.close()
        self._absorb(rec, slot, res)
        self._record(entry, rec)
        entry["result"] = self._slot_result(slot, entry)
        self._save_summary()

    # ---- 整套 ----

    def run(self, commit: str, old_summary: dict | None = None) -> int:
        self._init_summary(commit)
        self._save_summary()
        code = EXIT_OK
        try:
            for i, slot in enumerate(self.plan):
                try:
                    if old_summary is None:
                        self._run_slot_new(slot)
                    else:
                        old = next((e for e in old_summary.get("slots", []) if e.get("slot") == slot.name), None)
                        self._run_slot_resume(slot, old)
                except BudgetStop as stop:
                    self._slot_entry(slot.name)["result"] = STATUS_BUDGET
                    self.summary["status"] = STATUS_BUDGET
                    self.summary["stop"] = {"slot": slot.name, "where": stop.where, "message": stop.message,
                                            "skipped": [s.name for s in self.plan[i + 1:]]}
                    self.console.say(f"{slot.name} 预算闸停")
                    code = EXIT_BUDGET
                    break
            else:
                # 冒烟第一次就是这个形状:3 次 502 → 开局失败,却写着「完成」、退出码 0。
                failed = sum(1 for e in self.summary["slots"] if _is_failed_result(e.get("result")))
                if failed:
                    self.summary["status"] = STATUS_DONE_WITH_FAILURES.format(n=failed)
                    code = EXIT_SLOT_FAILED
                else:
                    self.summary["status"] = STATUS_DONE
        except Abort as e:
            self.summary["status"] = STATUS_ABORT
            self.summary["stop"] = {"reason": str(e)}
            self.console.say(f"中止:{e}")
            code = EXIT_ABORT
        finally:
            self.summary["rerunsUsed"] = self.reruns_used
            self.summary["finishedAt"] = _now()
            if self.summary.get("status") is None:
                self.summary["status"] = STATUS_ABORT
            self._save_summary()
        self._print_totals()
        return code

    def _print_totals(self) -> None:
        c = self.console
        c.say("—— 汇总 ——")
        c.say(f"状态 {self.summary['status']}")
        total_games = total_turns = 0
        for e in self.summary["slots"]:
            played = [a for a in e["attempts"] if a.get("saveId")]
            kept = next((a for a in e["attempts"] if a["attempt"] == e["kept"]), None)
            turns = kept["turnsPlayed"] if kept else 0
            end_turn = kept["endingTurn"] if kept and kept["endingTurn"] is not None else "-"
            total_games += len(played)
            total_turns += sum(a.get("turnsPlayed") or 0 for a in played)
            c.say(f"{e['slot']} | 局 {len(played)} | 保留 #{e['kept'] if e['kept'] is not None else '-'}"
                  f" | 回合 {turns} | 结局回合 {end_turn} | {e['result']}")
        c.say(f"合计 | 局 {total_games} | 补局 {self.reruns_used} | 回合 {total_turns}")


def _is_failed_result(result: object) -> bool:
    """槽位结果是「开局失败」或「中止(…)」。其余(达标 / 未达目标 / 续玩失败 / 未跑…)不在此列。"""
    return isinstance(result, str) and (result == "开局失败" or result.startswith("中止"))


# ── 入口 ─────────────────────────────────────────────────────────────────


def preflight_commit(http: Http) -> str:
    try:
        status, body = http.json("GET", "/actuator/info", timeout=TIMEOUT_STATE)
    except (urllib.error.URLError, OSError) as e:
        raise Refusal(f"server 不可达({type(e).__name__})")
    commit = ((body or {}).get("build") or {}).get("commit") if status == 200 and isinstance(body, dict) else None
    if not isinstance(commit, str) or not commit.strip() or commit.strip() == "unknown":
        raise Refusal("/actuator/info 的 build.commit 缺失或为 unknown:server 要带 -Dgit.sha=$(git rev-parse --short HEAD) 起")
    return commit.strip()


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="ADR-045 本机自玩长局驱动")
    p.add_argument("--out", required=True, help="套件根目录(仓库之外;server 的 store 应指向 <out>/store)")
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("--suite-seed", help="选项种子(新套件必填;--resume-t0 时取原套件的)")
    p.add_argument("--plan", default="default", choices=sorted(PLANS))
    p.add_argument("--resume-t0", metavar="DIR", help="原套件根目录(读其 summary.json);对冻结 T0 续玩")
    return p


def main(argv=None, *, sleep=time.sleep, stdout=None, plan=None) -> int:
    console = Console(stdout)
    args = build_parser().parse_args(argv)
    out = Path(args.out).expanduser()
    try:
        # 1) 路径闸 —— 在任何网络与写盘之前
        require_outside_repo(out, "--out ")
        old_summary = None
        resume_from = None
        if args.resume_t0:
            if args.suite_seed is not None:
                raise Refusal("--resume-t0 与 --suite-seed 互斥:续玩沿用原套件的种子")
            resume_from = Path(args.resume_t0).expanduser()
            require_outside_repo(resume_from, "--resume-t0 ")
            try:
                old_summary = json.loads((resume_from / "summary.json").read_text(encoding="utf-8"))
            except (OSError, ValueError):
                raise Refusal("--resume-t0 目录下读不到 summary.json")
            if old_summary.get("plan") != args.plan:
                raise Refusal("原套件的 plan 与 --plan 不一致")
            suite_seed = old_summary.get("suiteSeed")
        else:
            if args.suite_seed is None:
                raise Refusal("新套件必须给 --suite-seed")
            suite_seed = args.suite_seed
        if (out / "summary.json").exists():
            raise Refusal("--out 下已有 summary.json:换一个新目录,不覆盖旧套件")
        http = Http(args.base_url)
        # 2) commit 闸
        commit = preflight_commit(http)
    except Refusal as e:
        console.say(f"拒跑:{e}")
        return EXIT_REFUSED

    out.mkdir(parents=True, exist_ok=True)
    runner = Runner(http, out, suite_seed, args.plan, plan if plan is not None else PLANS[args.plan],
                    console, sleep=sleep, resume_from=resume_from)
    console.say(f"commit {commit} | 槽位 {len(runner.plan)}")
    try:
        return runner.run(commit, old_summary)
    except Exception as e:  # 兜底:不打 traceback(避免任何带 URL 的异常文本),只报类型与脱敏后的文本
        console.say(f"中止:{type(e).__name__} {console.redact(str(e))}")
        return EXIT_ABORT


if __name__ == "__main__":
    sys.exit(main())
