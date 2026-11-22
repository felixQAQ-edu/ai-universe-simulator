"""ADR-045 刀 1 · selfplay.py 的纯离线单测。

桩服务器用 http.server 在线程里起,SSE 全部由本文件合成 —— 不读 bakeoff/recordings 里的真实录制,不碰真 key。
跑法(仓库根目录):python3 -m unittest discover -s bakeoff/selfplay -v
"""
from __future__ import annotations

import io
import json
import os
import random
import shutil
import sys
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import selfplay as sp  # noqa: E402

AXES_META = [{"key": "qi", "displayName": "气"}, {"key": "dao", "displayName": "道"}]


# ── 桩服务器 ─────────────────────────────────────────────────────────────


class Game:
    def __init__(self, save_id, idx, life, ending_at=None):
        self.save_id, self.idx, self.life, self.ending_at = save_id, idx, life, ending_at
        self.turn, self.status = 0, "ongoing"

    @staticmethod
    def option_count(turn):
        return 2 + (turn % 3)  # 选项数随回合变,让 randrange 的上界也变

    def actions(self):
        acts = [{"id": i, "text": f"选项{i}", "hint": ""} for i in "ABCD"[: self.option_count(self.turn)]]
        if self.life:
            acts.append({"id": "X", "text": "就到这里", "hint": ""})
        return acts

    def attrs(self):
        return {"qi": 100 - self.turn, "dao": float(self.turn)}

    def view(self, opening=""):
        return {
            "saveId": self.save_id,
            "world": {
                "title": "桩世界", "archetypes": ["stub"],
                "character": {"attributes": self.attrs()},
                "endings": [{"id": "stub_end", "title": "桩结局", "reached": self.status == "ended"}],
                "state": {"turn": self.turn, "status": self.status, "timeline": f"t{self.turn}",
                          "logSummary": "", "log": []},
            },
            "openingNarrative": opening,
            "availableActions": self.actions() if self.status == "ongoing" else [],
            "attributes": AXES_META,
        }


class Stub:
    def __init__(self, store_dir: Path):
        self.store_dir = store_dir
        self.commit = "abc1234"
        self.info_body = None          # 非 None 时 /actuator/info 原样回它
        self.init_script = []          # 逐次开局消费:int 状态码,或 None = 成功
        self.game_cfgs = []            # 逐个成功开局消费:{"ending_at": n}
        self.faults = {}               # (game_idx, cursor) → [fault...];"503*" 不消费
        self.write_store = True
        self.games: dict[str, Game] = {}
        self.order: list[str] = []
        self.requests: list[tuple[str, str]] = []
        self.init_bodies: list[dict] = []
        self.turn_posts: list[tuple[int, int, str, str]] = []  # (game_idx, cursor, actionId, outcome)
        self.lock = threading.Lock()
        self.server = None

    # ---- 状态 ----

    def preload(self, save_id, life, ending_at=None):
        g = Game(save_id, len(self.order), life, ending_at)
        self.games[save_id] = g
        self.order.append(save_id)
        return g

    def _persist(self, g: Game):
        if self.write_store:
            self.store_dir.mkdir(parents=True, exist_ok=True)
            (self.store_dir / f"{g.save_id}.json").write_text(
                json.dumps({"saveId": g.save_id, "turn": g.turn}), encoding="utf-8")

    # ---- 生命周期 ----

    def start(self):
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _json(self, status, obj):
                data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _sse(self, events):
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                for name, obj in events:
                    self.wfile.write(f"event:{name}\ndata:{json.dumps(obj, ensure_ascii=False)}\n\n".encode("utf-8"))

            def _body(self):
                n = int(self.headers.get("Content-Length") or 0)
                return json.loads(self.rfile.read(n).decode("utf-8")) if n else None

            def do_GET(self):
                with stub.lock:
                    stub.requests.append(("GET", self.path))
                    if self.path == "/actuator/info":
                        body = stub.info_body if stub.info_body is not None else {"build": {"commit": stub.commit}}
                        return self._json(200, body)
                    parts = self.path.strip("/").split("/")
                    if len(parts) == 4 and parts[3] == "state":
                        g = stub.games.get(parts[2])
                        if g is None:
                            return self._json(404, {"error": {"code": "session_not_found"}})
                        return self._json(200, g.view())
                    return self._json(404, {"error": {"code": "nope"}})

            def do_POST(self):
                with stub.lock:
                    stub.requests.append(("POST", self.path))
                    body = self._body()
                    if self.path == "/api/game/init":
                        stub.init_bodies.append(body)
                        step = stub.init_script.pop(0) if stub.init_script else None
                        if step is not None:
                            code = {429: "quota_exceeded", 502: "world_gen_failed", 400: "invalid_archetype",
                                    503: "server_at_capacity"}.get(step, "x")
                            return self._json(step, {"error": {"code": code, "message": "桩"}})
                        cfg = stub.game_cfgs.pop(0) if stub.game_cfgs else {}
                        g = stub.preload(str(uuid.uuid4()), body.get("archetype") == "life_sim", cfg.get("ending_at"))
                        stub._persist(g)
                        return self._json(200, g.view(opening="开场"))
                    parts = self.path.strip("/").split("/")
                    g = stub.games.get(parts[2]) if len(parts) == 4 else None
                    if g is None:
                        return self._json(404, {"error": {"code": "session_not_found"}})
                    key = (g.idx, g.turn)
                    queue = stub.faults.get(key) or []
                    fault = None
                    if queue:
                        fault = queue[0]
                        if not fault.endswith("*"):
                            queue.pop(0)
                    aid = body.get("actionId")
                    if fault in ("409",):
                        stub.turn_posts.append((g.idx, g.turn, aid, "409"))
                        return self._json(409, {"error": {"code": "turn_stale"}})
                    if fault in ("503", "503*"):
                        stub.turn_posts.append((g.idx, g.turn, aid, "503"))
                        return self._json(503, {"error": {"code": "server_at_capacity", "message": "挤"}})
                    if fault and fault.startswith("sse:"):
                        stub.turn_posts.append((g.idx, g.turn, aid, fault))
                        return self._sse([("error", {"code": fault[4:], "message": "桩"})])
                    if fault == "cut":  # 只有叙事、没有 delta 的流
                        stub.turn_posts.append((g.idx, g.turn, aid, "cut"))
                        return self._sse([("narrative", {"text": "半截"})])
                    if body.get("turn") != g.turn or g.status == "ended":
                        stub.turn_posts.append((g.idx, g.turn, aid, "409"))
                        return self._json(409, {"error": {"code": "turn_stale"}})
                    if aid not in [a["id"] for a in g.actions()]:
                        return self._json(400, {"error": {"code": "illegal_action", "message": "无效"}})
                    stub.turn_posts.append((g.idx, g.turn, aid, "ok"))
                    g.turn += 1
                    if g.ending_at is not None and g.turn >= g.ending_at:
                        g.status = "ended"
                    stub._persist(g)
                    delta = {"turn": g.turn, "status": g.status, **g.attrs(), "discoveredRules": [],
                             "availableActions": g.actions() if g.status == "ongoing" else []}
                    events = [("narrative", {"text": "第"}), ("narrative", {"text": f"{g.turn}回合"}),
                              ("delta", delta)]
                    if g.status == "ended":
                        events.append(("ending", {"id": "stub_end", "title": "桩结局", "description": ""}))
                    return self._sse(events)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def stop(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()

    # ---- 读数 ----

    def landed(self, game_idx):
        return [aid for (gi, _c, aid, outcome) in self.turn_posts if gi == game_idx and outcome == "ok"]

    def game_idx_by_save(self, save_id):
        return self.games[save_id].idx


def expected_stream(seed, slot, attempt, turns, start=0):
    """独立于被测代码复算:Random(f'{seed}:{slot}:{attempt}'),每回合 randrange(去掉 X 后的选项数)。"""
    rng = random.Random(f"{seed}:{slot}:{attempt}")
    return ["ABCD"[rng.randrange(Game.option_count(t))] for t in range(start, start + turns)]


SMALL = sp.Slot("t-1", {"archetype": "cultivation"}, 12, 6)
SMALL2 = sp.Slot("t-2", {"archetype": "rules_creepy"}, 12, 6)
SMALL3 = sp.Slot("t-3", {"archetype": "cthulhu"}, 12, 6)


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="selfplay-test-"))
        self.out = self.tmp / "suite"
        self.stub = Stub(self.out / "store")
        self.base = self.stub.start()
        self.sleeps = []

    def tearDown(self):
        self.stub.stop()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_main(self, *extra, plan=None, out=None, seed="S1"):
        buf = io.StringIO()
        argv = ["--out", str(out or self.out), "--base-url", self.base, *extra]
        if seed is not None:
            argv += ["--suite-seed", seed]
        code = sp.main(argv, sleep=self.sleeps.append, stdout=buf, plan=plan)
        self.console = buf.getvalue()
        return code

    def summary(self, out=None):
        return json.loads(((out or self.out) / "summary.json").read_text(encoding="utf-8"))

    def slot(self, name, out=None):
        return next(e for e in self.summary(out)["slots"] if e["slot"] == name)

    def assert_no_save_ids(self):
        for sid in self.stub.games:
            self.assertNotIn(sid, self.console)


# ── 1. 拒跑 ──────────────────────────────────────────────────────────────


class RefusalTests(Base):
    def assert_refused_clean(self, code, target):
        self.assertEqual(code, sp.EXIT_REFUSED)
        self.assertIn("拒跑", self.console)
        self.assertFalse((target / "summary.json").exists())
        self.assertFalse((target / "http").exists())
        self.assertFalse((target / "t0").exists())

    def test_out_inside_repo_refused_before_any_request(self):
        repo = self.tmp / "fake-repo"
        (repo / ".git").mkdir(parents=True)
        target = repo / "deep" / "suite"
        code = self.run_main(out=target, plan=(SMALL,))
        self.assert_refused_clean(code, target)
        self.assertFalse(target.exists())
        self.assertEqual(self.stub.requests, [])

    def test_git_file_counts_as_repo(self):  # worktree 的 .git 是文件
        repo = self.tmp / "wt"
        repo.mkdir()
        (repo / ".git").write_text("gitdir: elsewhere")
        code = self.run_main(out=repo / "suite", plan=(SMALL,))
        self.assertEqual(code, sp.EXIT_REFUSED)
        self.assertEqual(self.stub.requests, [])

    def test_symlink_into_repo_refused(self):
        repo = self.tmp / "fake-repo"
        (repo / ".git").mkdir(parents=True)
        (repo / "inner").mkdir()
        link = self.tmp / "link"
        os.symlink(repo / "inner", link)
        code = self.run_main(out=link / "suite", plan=(SMALL,))
        self.assertEqual(code, sp.EXIT_REFUSED)
        self.assertFalse((repo / "inner" / "suite").exists())

    @unittest.skipUnless(sp.find_git_ancestor(HERE), "测试文件不在仓库里(变异副本)")
    def test_out_inside_this_repo_refused(self):
        target = HERE / "_must_not_exist"
        code = self.run_main(out=target, plan=(SMALL,))
        self.assertEqual(code, sp.EXIT_REFUSED)
        self.assertFalse(target.exists())

    def test_outside_repo_allowed(self):
        self.assertIsNone(sp.find_git_ancestor(self.out))

    def test_commit_unknown_refused(self):
        self.stub.commit = "unknown"
        code = self.run_main(plan=(SMALL,))
        self.assert_refused_clean(code, self.out)
        self.assertFalse(self.out.exists())
        self.assertNotIn(("POST", "/api/game/init"), self.stub.requests)

    def test_commit_missing_refused(self):
        self.stub.info_body = {"app": {}}
        code = self.run_main(plan=(SMALL,))
        self.assert_refused_clean(code, self.out)
        self.assertNotIn(("POST", "/api/game/init"), self.stub.requests)

    def test_existing_summary_refused(self):
        self.out.mkdir(parents=True)
        (self.out / "summary.json").write_text("{}")
        code = self.run_main(plan=(SMALL,))
        self.assertEqual(code, sp.EXIT_REFUSED)
        self.assertEqual((self.out / "summary.json").read_text(), "{}")

    def test_new_suite_requires_seed(self):
        code = self.run_main(plan=(SMALL,), seed=None)
        self.assertEqual(code, sp.EXIT_REFUSED)


# ── 2. 槽位与顺序 ────────────────────────────────────────────────────────


class PlanTests(Base):
    def test_default_plan_is_adr045_decision1(self):
        got = [(s.name, s.request, s.target, s.short_below, s.natural) for s in sp.DEFAULT_PLAN]
        xiu, xun = {"archetype": "cultivation"}, {"archetype": "life_sim"}
        self.assertEqual(got, [
            ("xiu-1", xiu, 60, 30, False),
            ("xiu-2", xiu, 60, 30, False),
            ("xiu-long", xiu, 120, 60, False),
            ("gui-1", {"archetype": "rules_creepy"}, 60, 30, False),
            ("rong-1", {"archetypes": ["cultivation", "rules_creepy"]}, 60, 30, False),
            ("xun-1", xun, 120, 36, True),
            ("xun-2", xun, 120, 36, True),
        ])

    def test_smoke_plan_one_game_five_turns_no_rerun(self):
        self.stub.game_cfgs = [{"ending_at": 1}]  # 即便第 1 回合就结局,也不补局
        code = self.run_main("--plan", "smoke", seed="1")
        self.assertEqual(code, sp.EXIT_OK)
        self.assertEqual(self.stub.init_bodies, [{"archetype": "cultivation"}])
        e = self.slot("smoke-1")
        self.assertEqual(len(e["attempts"]), 1)
        self.assertEqual(self.summary()["rerunsUsed"], 0)
        self.assertEqual(self.summary()["plan"], "smoke")

    def test_smoke_plan_runs_exactly_five_turns(self):
        code = self.run_main("--plan", "smoke", seed="1")
        self.assertEqual(code, sp.EXIT_OK)
        self.assertEqual(len(self.stub.init_bodies), 1)
        a = self.slot("smoke-1")["attempts"][0]
        self.assertEqual((a["turnsPlayed"], a["terminated"], a["short"]), (5, "target_reached", False))
        self.assertEqual(len(self.stub.landed(0)), 5)
        self.assertEqual(self.console.strip().splitlines()[-1], "合计 | 局 1 | 补局 0 | 回合 5")

    def test_is_short_boundaries(self):
        xiu, xun = sp.DEFAULT_PLAN[0], sp.DEFAULT_PLAN[5]
        self.assertTrue(sp.is_short(xiu, 29))
        self.assertFalse(sp.is_short(xiu, 30))
        self.assertTrue(sp.is_short(xun, 35))
        self.assertFalse(sp.is_short(xun, 36))
        self.assertFalse(sp.is_short(xiu, None))

    def test_life_slot_runs_to_cap_and_stops(self):
        xun = sp.DEFAULT_PLAN[5]
        code = self.run_main(plan=(xun,))
        self.assertEqual(code, sp.EXIT_OK)
        att = self.slot("xun-1")["attempts"]
        self.assertEqual(len(att), 1)
        self.assertEqual((att[0]["turnsPlayed"], att[0]["terminated"]), (120, "cap_reached"))

    def test_life_ending_after_36_is_complete(self):
        self.stub.game_cfgs = [{"ending_at": 37}]
        code = self.run_main(plan=(sp.DEFAULT_PLAN[5],))
        self.assertEqual(code, sp.EXIT_OK)
        e = self.slot("xun-1")
        self.assertEqual(len(e["attempts"]), 1)
        self.assertEqual(e["result"], "达标")

    def test_life_ending_before_36_reruns(self):
        self.stub.game_cfgs = [{"ending_at": 35}, {"ending_at": 40}]
        self.run_main(plan=(sp.DEFAULT_PLAN[5],))
        e = self.slot("xun-1")
        self.assertEqual([a["endingTurn"] for a in e["attempts"]], [35, 40])
        self.assertEqual(e["kept"], 2)


# ── 3. 选项 ──────────────────────────────────────────────────────────────


class OptionTests(Base):
    def test_index_stream_matches_seeded_formula_and_excludes_x(self):
        life = sp.Slot("t-life", {"archetype": "life_sim"}, 20, 10)
        self.run_main(plan=(life,), seed="20261122")
        landed = self.stub.landed(0)
        self.assertNotIn("X", landed)
        self.assertEqual(landed, expected_stream("20261122", "t-life", 1, 20))

    def test_same_seed_same_options_same_stream(self):
        self.run_main(plan=(SMALL,), seed="abc")
        first = self.stub.landed(0)
        other = Stub(self.tmp / "suite2" / "store")
        base2 = other.start()
        try:
            buf = io.StringIO()
            sp.main(["--out", str(self.tmp / "suite2"), "--base-url", base2, "--suite-seed", "abc"],
                    sleep=lambda s: None, stdout=buf, plan=(SMALL,))
            self.assertEqual(other.landed(0), first)
        finally:
            other.stop()
        self.assertNotEqual(first, expected_stream("abd", "t-1", 1, 12))  # 换种子换流(防恒等)

    def test_candidates_drop_only_x(self):
        acts = [{"id": "A"}, {"id": "X"}, {"id": "B"}]
        self.assertEqual([a["id"] for a in sp.candidates(acts)], ["A", "B"])


# ── 4. 补局 ──────────────────────────────────────────────────────────────


class RerunTests(Base):
    def test_rerun_limits_per_slot_and_suite(self):
        self.stub.game_cfgs = [{"ending_at": 1}] * 10
        code = self.run_main(plan=(SMALL, SMALL2, SMALL3))
        self.assertEqual(code, sp.EXIT_OK)
        counts = [len(self.slot(n)["attempts"]) for n in ("t-1", "t-2", "t-3")]
        self.assertEqual(counts, [3, 3, 1])  # 每槽 ≤ 2 次补局;整套 4 次用完后第三槽不补
        self.assertEqual(self.summary()["rerunsUsed"], 4)
        for n in ("t-1", "t-2", "t-3"):
            self.assertEqual(self.slot(n)["result"], "未达目标")

    def test_keeps_longest_attempt(self):
        self.stub.game_cfgs = [{"ending_at": 4}, {"ending_at": 2}, {"ending_at": 3}]
        self.run_main(plan=(SMALL,))
        e = self.slot("t-1")
        self.assertEqual([a["endingTurn"] for a in e["attempts"]], [4, 2, 3])
        self.assertEqual(e["kept"], 1)
        self.assertEqual(e["result"], "未达目标")

    def test_rerun_uses_next_attempt_seed(self):
        self.stub.game_cfgs = [{"ending_at": 2}]
        self.run_main(plan=(SMALL,), seed="Q")
        self.assertEqual(self.stub.landed(1), expected_stream("Q", "t-1", 2, 12))
        self.assertEqual([a["seed"] for a in self.slot("t-1")["attempts"]], ["Q:t-1:1", "Q:t-1:2"])


# ── 5. world-gen 重试 ────────────────────────────────────────────────────


class WorldGenRetryTests(Base):
    def test_502_400_retried_same_attempt_not_counted(self):
        self.stub.init_script = [502, 400]
        code = self.run_main(plan=(SMALL,), seed="W")
        self.assertEqual(code, sp.EXIT_OK)
        e = self.slot("t-1")
        self.assertEqual(len(e["attempts"]), 1)
        self.assertEqual(e["attempts"][0]["worldgenRetries"], 2)
        self.assertEqual(self.summary()["rerunsUsed"], 0)
        self.assertEqual(self.stub.landed(0), expected_stream("W", "t-1", 1, 12))  # 仍是 attempt 1 的种子

    def test_three_failures_give_up_slot_and_continue(self):
        self.stub.init_script = [502, 502, 502]
        code = self.run_main(plan=(SMALL, SMALL2))
        self.assertEqual(code, sp.EXIT_OK)
        self.assertEqual(len(self.stub.init_bodies), 4)  # 3 次失败 + 下一槽 1 次
        self.assertEqual(self.slot("t-1")["result"], "开局失败")
        self.assertEqual(self.slot("t-2")["result"], "达标")
        self.assertEqual(self.summary()["rerunsUsed"], 0)


# ── 6. 409 / 503 ─────────────────────────────────────────────────────────


class StaleAndCapacityTests(Base):
    def test_409_resyncs_and_reuses_drawn_index(self):
        self.stub.faults = {(0, 5): ["409", "409"]}
        code = self.run_main(plan=(SMALL,), seed="R")
        self.assertEqual(code, sp.EXIT_OK)
        exp = expected_stream("R", "t-1", 1, 12)
        self.assertEqual(self.stub.landed(0), exp)  # 下标流没有被重试错位
        at5 = [aid for (gi, c, aid, _o) in self.stub.turn_posts if gi == 0 and c == 5]
        self.assertEqual(at5, [exp[5]] * 3)

    def test_503_backoff_then_continue(self):
        self.stub.faults = {(0, 3): ["503", "503"]}
        code = self.run_main(plan=(SMALL,))
        self.assertEqual(code, sp.EXIT_OK)
        self.assertEqual(self.sleeps, [5, 10])
        self.assertEqual(self.slot("t-1")["attempts"][0]["turnsPlayed"], 12)

    def test_503_gives_up_after_schedule(self):
        self.stub.faults = {(0, 3): ["503*"]}
        self.run_main(plan=(SMALL, SMALL2))
        self.assertEqual(self.sleeps, list(sp.BACKOFF_503))
        a = self.slot("t-1")["attempts"][0]
        self.assertEqual((a["terminated"], a["turnsPlayed"]), ("server_at_capacity", 3))
        self.assertEqual(self.slot("t-2")["result"], "达标")  # 只停这一局

    def test_init_503_backs_off_and_is_not_a_worldgen_retry(self):
        self.stub.init_script = [503, 503]
        code = self.run_main(plan=(SMALL,))
        self.assertEqual(code, sp.EXIT_OK)
        self.assertEqual(self.sleeps, [5, 10])
        self.assertEqual(self.slot("t-1")["attempts"][0]["worldgenRetries"], 0)

    def test_sse_internal_error_and_missing_delta_stop_game(self):
        self.stub.faults = {(0, 2): ["sse:internal_error"], (1, 5): ["cut"]}
        code = self.run_main(plan=(SMALL, SMALL2, SMALL3))
        self.assertEqual(code, sp.EXIT_OK)
        a1, a2 = self.slot("t-1")["attempts"][0], self.slot("t-2")["attempts"][0]
        self.assertEqual((a1["terminated"], a1["turnsPlayed"]), ("internal_error", 2))
        self.assertEqual((a2["terminated"], a2["turnsPlayed"]), ("sse_incomplete", 5))
        self.assertEqual(self.slot("t-3")["result"], "达标")


# ── 7. 闸停 ──────────────────────────────────────────────────────────────


class BudgetStopTests(Base):
    def test_init_429_stops_suite(self):
        self.stub.init_script = [None, 429]
        code = self.run_main(plan=(SMALL, SMALL2, SMALL3))
        self.assertEqual(code, sp.EXIT_BUDGET)
        s = self.summary()
        self.assertEqual(s["status"], "预算闸停")
        self.assertEqual(s["stop"]["slot"], "t-2")
        self.assertEqual(s["stop"]["skipped"], ["t-3"])
        self.assertEqual(self.slot("t-2")["result"], "预算闸停")
        self.assertEqual(self.slot("t-3")["result"], "未跑")
        self.assertEqual(len(self.stub.init_bodies), 2)

    def test_sse_quota_exceeded_stops_suite_mid_game(self):
        self.stub.faults = {(0, 7): ["sse:quota_exceeded"]}
        code = self.run_main(plan=(SMALL, SMALL2))
        self.assertEqual(code, sp.EXIT_BUDGET)
        a = self.slot("t-1")["attempts"][0]
        self.assertEqual((a["terminated"], a["turnsPlayed"]), ("quota_exceeded", 7))
        self.assertEqual(self.slot("t-2")["result"], "未跑")
        self.assertEqual(len(self.stub.init_bodies), 1)
        self.assertIn("状态 预算闸停", self.console)

    def test_sse_busy_stops_game_only(self):
        self.stub.faults = {(0, 4): ["sse:busy"]}
        code = self.run_main(plan=(SMALL, SMALL2))
        self.assertEqual(code, sp.EXIT_OK)
        a = self.slot("t-1")["attempts"][0]
        self.assertEqual((a["terminated"], a["turnsPlayed"]), ("busy", 4))
        self.assertEqual(self.slot("t-1")["result"], "中止(busy)")
        self.assertEqual(self.slot("t-2")["result"], "达标")
        self.assertEqual(self.summary()["rerunsUsed"], 0)


# ── 8. 冻结 T0 与续玩 ────────────────────────────────────────────────────


class T0Tests(Base):
    def test_t0_frozen_at_init_not_later(self):
        self.run_main(plan=(SMALL,))
        sid = self.stub.order[0]
        t0 = json.loads((self.out / "t0" / "t-1-1.json").read_text())
        self.assertEqual(t0, {"saveId": sid, "turn": 0})
        live = json.loads((self.out / "store" / f"{sid}.json").read_text())
        self.assertEqual(live["turn"], 12)  # store 之后又被写过,T0 不跟着变
        self.assertEqual(self.slot("t-1")["attempts"][0]["t0"], "t0/t-1-1.json")

    def test_missing_store_file_aborts(self):
        self.stub.write_store = False
        code = self.run_main(plan=(SMALL, SMALL2))
        self.assertEqual(code, sp.EXIT_ABORT)
        s = self.summary()
        self.assertEqual(s["status"], "中止")
        self.assertEqual(self.slot("t-1")["result"], "T0 冻结失败")
        self.assertEqual(len(self.stub.init_bodies), 1)
        self.assertEqual(self.stub.turn_posts, [])  # 没冻住就不玩

    def test_resume_from_t0_replays_same_stream_without_init(self):
        life = sp.Slot("t-life", {"archetype": "life_sim"}, 10, 5)
        self.run_main(plan=(SMALL, life), seed="RS")
        first = {self.stub.order[i]: self.stub.landed(i) for i in range(2)}
        old = self.summary()
        out2 = self.tmp / "suite-b"
        stub2 = Stub(out2 / "store")
        base2 = stub2.start()
        try:
            for e in old["slots"]:  # 运行方式:Felix 把 t0 拷进新 store、server 回载(此处由桩预置)
                sid = e["attempts"][0]["saveId"]
                stub2.preload(sid, e["request"].get("archetype") == "life_sim")
            buf = io.StringIO()
            code = sp.main(["--out", str(out2), "--base-url", base2, "--resume-t0", str(self.out)],
                           sleep=lambda s: None, stdout=buf, plan=(SMALL, life))
            self.assertEqual(code, sp.EXIT_OK)
            self.assertEqual(stub2.init_bodies, [])
            for sid, stream in first.items():
                self.assertEqual(stub2.landed(stub2.game_idx_by_save(sid)), stream)
            s2 = json.loads((out2 / "summary.json").read_text())
            self.assertEqual(s2["mode"], "resume")
            self.assertEqual(s2["suiteSeed"], "RS")
            self.assertTrue(s2["slots"][0]["attempts"][0]["resumedFromT0"])
            for sid in first:
                self.assertNotIn(sid, buf.getvalue())
            first_line = json.loads((out2 / "http" / "t-1-1.jsonl").read_text().splitlines()[0])
            self.assertEqual(first_line["type"], "resume")
        finally:
            stub2.stop()

    def test_resume_rejects_suite_seed(self):
        self.run_main(plan=(SMALL,))
        code = self.run_main("--resume-t0", str(self.out), plan=(SMALL,), out=self.tmp / "b", seed="x")
        self.assertEqual(code, sp.EXIT_REFUSED)


# ── 9. 落盘 ──────────────────────────────────────────────────────────────


class FileTests(Base):
    def test_http_jsonl_and_summary_fields(self):
        self.stub.game_cfgs = [{"ending_at": 12}]
        self.run_main(plan=(SMALL,))
        lines = [json.loads(x) for x in (self.out / "http" / "t-1-1.jsonl").read_text().splitlines()]
        self.assertEqual(lines[0]["type"], "init")
        self.assertEqual(lines[0]["response"]["openingNarrative"], "开场")
        turns = [x for x in lines if x["type"] == "turn"]
        self.assertEqual(len(turns), 12)
        t = turns[0]
        self.assertEqual(t["request"], {"turn": 0, "actionId": t["request"]["actionId"]})
        self.assertIn("event:narrative", t["sse"])
        self.assertIn("event:delta", t["sse"])
        self.assertEqual(t["pre"]["world"]["state"]["turn"], 0)
        self.assertEqual(t["post"]["world"]["state"]["turn"], 1)
        self.assertIn("event:ending", turns[-1]["sse"])
        a = self.slot("t-1")["attempts"][0]
        self.assertEqual((a["endingTurn"], a["endingId"], a["terminated"]), (12, "stub_end", "ending"))
        self.assertIn("degradedTurns", a)
        self.assertIsNone(a["degradedTurns"])  # 待成稿工具补
        s = self.summary()
        self.assertEqual((s["commit"], s["status"], s["plan"]), ("abc1234", "完成", "default"))


# ── 10. 控制台 ───────────────────────────────────────────────────────────


class ConsoleTests(Base):
    def test_console_never_shows_save_id_and_ends_with_numbers(self):
        self.stub.game_cfgs = [{"ending_at": 3}]
        self.stub.faults = {(1, 2): ["409", "503"]}
        self.run_main(plan=(SMALL, SMALL2))
        self.assert_no_save_ids()
        tail = self.console.strip().splitlines()[-4:]
        self.assertEqual(tail[0], "状态 完成")
        self.assertEqual(tail[-1], "合计 | 局 3 | 补局 1 | 回合 27")

    def test_redact_scrubs_known_save_ids(self):
        buf = io.StringIO()
        c = sp.Console(buf)
        c.add_secret("abcd-1234")
        c.say("GET /api/game/abcd-1234/state 失败")
        self.assertNotIn("abcd-1234", buf.getvalue())


# ── 11. SSE 与轴 ─────────────────────────────────────────────────────────


class SseTests(unittest.TestCase):
    def test_parse_sse_spec_details(self):
        raw = ["event:narrative\r\n", "data:{\"text\":\"甲\"}\r\n", "\r\n",
               ": 注释\n", "event: narrative\n", "data: {\"text\":\"乙\"}\n", "\n",
               "event:delta\n", "data:{\"turn\":1,\n", "data:\"qi\":9}\n", "\n",
               "event:ending\n", "data:{\"id\":\"e\"}\n"]
        ev = sp.parse_sse(raw)
        self.assertEqual([n for n, _ in ev], ["narrative", "narrative", "delta", "ending"])
        got = sp.collect_turn(ev)
        self.assertEqual(got["narrative"], "甲乙")
        self.assertEqual(got["delta"], {"turn": 1, "qi": 9})
        self.assertEqual(got["ending"], {"id": "e"})
        self.assertIsNone(got["error"])

    def test_error_event(self):
        got = sp.collect_turn(sp.parse_sse(["event:error\n", "data:{\"code\":\"busy\",\"message\":\"m\"}\n", "\n"]))
        self.assertEqual(got["error"]["code"], "busy")
        self.assertIsNone(got["delta"])

    def test_axes_follow_metadata_not_hardcoded(self):
        delta = {"turn": 1, "qi": 5, "dao": 6.0, "hp": 1, "san": 2}
        self.assertEqual(sp.axis_values(AXES_META, delta), {"qi": 5, "dao": 6.0})


class AxesEndToEndTests(Base):
    def test_axes_from_init_metadata_reach_log_and_console(self):
        self.run_main(plan=(SMALL,))
        self.assertIn("t-1#1 T1 qi 99 dao 1", self.console)
        lines = [json.loads(x) for x in (self.out / "http" / "t-1-1.jsonl").read_text().splitlines()]
        self.assertEqual([x for x in lines if x["type"] == "turn"][0]["axes"], {"qi": 99, "dao": 1.0})


# ── 端到端:默认计划,桩世界第 10 回合给结局 → 补局 → 第二局跑满 ─────────────


class EndToEndDefaultPlan(Base):
    def test_default_plan_rerun_then_full(self):
        self.stub.game_cfgs = [{"ending_at": 10}]
        code = self.run_main(seed="20261122")  # plan=None → 默认计划
        self.assertEqual(code, sp.EXIT_OK)
        order = [b for b in self.stub.init_bodies]
        xiu, xun = {"archetype": "cultivation"}, {"archetype": "life_sim"}
        self.assertEqual(order, [xiu, xiu, xiu, xiu, {"archetype": "rules_creepy"},
                                 {"archetypes": ["cultivation", "rules_creepy"]}, xun, xun])
        s = self.summary()
        self.assertEqual((s["status"], s["rerunsUsed"]), ("完成", 1))
        x1 = self.slot("xiu-1")
        self.assertEqual([(a["attempt"], a["endingTurn"], a["short"], a["turnsPlayed"], a["terminated"])
                          for a in x1["attempts"]],
                         [(1, 10, True, 10, "ending"), (2, None, False, 60, "target_reached")])
        self.assertEqual((x1["kept"], x1["result"]), (2, "达标"))
        self.assertEqual(self.slot("xiu-long")["attempts"][0]["turnsPlayed"], 120)
        self.assertEqual(self.slot("xun-2")["attempts"][0]["terminated"], "cap_reached")
        self.assertEqual(len(list((self.out / "t0").iterdir())), 8)
        self.assertEqual(len(list((self.out / "http").iterdir())), 8)
        self.assertEqual(self.stub.landed(1), expected_stream("20261122", "xiu-1", 2, 60))
        self.assert_no_save_ids()
        self.assertEqual(self.console.strip().splitlines()[-1], "合计 | 局 8 | 补局 1 | 回合 610")


if __name__ == "__main__":
    unittest.main()
