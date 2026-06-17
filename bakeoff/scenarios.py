"""场景组 A(world-gen 质量)+ B(连推 10 回合)+ 状态机引擎(ADR-001 §4 / §5.3)。

引擎职责(对齐 CONTEXT §二字段职责、§三.1):
  - state 是真理之源:turn / status / log / logSummary 由引擎维护,每回合回传模型。
  - 数值结算、行动合法性校验、规则一致性核对、泄露检测 都在引擎侧做。
  - 模型只产出叙事 + 增量 + 触发/发现规则 + 可选行动 + 结局判定。
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field

import schema
import prompts
from client import CallRecord

LOG_KEEP = 4          # 近 N 回合 log 原文回传,更旧的压进 logSummary(成本控制)

# ── 种子(A1 固定 / A2 多样)──────────────────────────────────────────
SEED_A1 = """模式:规则怪谈(单体)
场景种子:雨夜便利店,玩家是临时夜班店员,凌晨 0:00–6:00
危险等级:high
要求:生成 6–8 条规则(真假混合),hp/san 初值,2–3 个结局条件"""

SEEDS_A2 = [
    """模式:规则怪谈(单体)
场景种子:深夜末班地铁,玩家错过下车,车厢里只剩自己
危险等级:high
要求:生成 6–8 条规则(真假混合),hp/san 初值,2–3 个结局条件""",
    """模式:规则怪谈(单体)
场景种子:山区民宿,玩家独自入住,墙上贴着住宿须知
危险等级:medium
要求:生成 6–8 条规则(真假混合),hp/san 初值,2–3 个结局条件""",
    """模式:规则怪谈(单体)
场景种子:凌晨医院住院部走廊,玩家是陪护家属
危险等级:extreme
要求:生成 6–8 条规则(真假混合),hp/san 初值,2–3 个结局条件""",
]


def _parse_json(raw: str):
    """容错解析:剥掉可能的 ```json 围栏,取第一个 {...}。"""
    s = raw.strip()
    s = re.sub(r"^```(?:json)?\s*|\s*```$", "", s, flags=re.S)
    try:
        return json.loads(s)
    except json.JSONDecodeError:
        m = re.search(r"\{.*\}", s, re.S)
        if m:
            return json.loads(m.group(0))
        raise


def _generate(client, *, name, step, scenario, vars, validate) -> CallRecord:
    """一次生成 + schema 校验 + 至多一次“修复重试”(ADR-001 指标 #1)。"""
    msgs = prompts.messages(name, vars)
    rec = client.generate(messages=msgs, step=step, scenario=scenario)
    rec = _check(rec, validate)
    if rec.ok and rec.schema_ok is False:
        # 修复重试:把校验错误回喂模型,要求只回修正后的 JSON。
        fix = msgs + [
            {"role": "assistant", "content": rec.raw},
            {"role": "user", "content":
                "上面的 JSON 未通过校验,错误如下,请只回修正后的完整 JSON,不要解释:\n"
                + "\n".join(rec.schema_errors or [])},
        ]
        rec2 = client.generate(messages=fix, step=step, scenario=scenario + "+fix")
        rec2 = _check(rec2, validate)
        rec2.repaired = True
        return rec2
    return rec


def _check(rec: CallRecord, validate) -> CallRecord:
    rec.schema_errors = []  # type: ignore[attr-defined]
    if not rec.ok:
        return rec
    try:
        rec.parsed = _parse_json(rec.raw)
    except Exception as e:  # noqa: BLE001
        rec.schema_ok = False
        rec.schema_errors = [f"JSON 解析失败: {e}"]  # type: ignore[attr-defined]
        rec.error = (rec.error or "") + " | parse_fail"
        return rec
    errs = validate(rec.parsed)
    rec.schema_ok = not errs
    rec.schema_errors = errs  # type: ignore[attr-defined]
    return rec


# ── 场景组 A ────────────────────────────────────────────────────────
def run_scenario_A(client, runs: int = 5):
    """A1 固定种子跑 runs 次 + A2 三个不同种子各一次。返回 (records, 用于 B 的 world)。"""
    records: list[CallRecord] = []
    world_for_B = None

    for i in range(runs):  # A1 稳定性
        rec = _generate(client, name="world-gen", step="world-gen",
                        scenario="A1", vars={"SEED": SEED_A1},
                        validate=schema.validate_world)
        _leak_scan_world(rec)
        records.append(rec)
        if world_for_B is None and rec.schema_ok and rec.parsed:
            world_for_B = rec.parsed

    for j, seed in enumerate(SEEDS_A2, 1):  # A2 多样性
        rec = _generate(client, name="world-gen", step="world-gen",
                        scenario=f"A2-seed{j}", vars={"SEED": seed},
                        validate=schema.validate_world)
        _leak_scan_world(rec)
        records.append(rec)

    # A3 规则质量为人工盲评(rubric §6),此处只导出素材,不打分。
    return records, world_for_B


def _leak_scan_world(rec: CallRecord):
    if not (rec.schema_ok and rec.parsed):
        return
    w = rec.parsed
    visible = " ".join(filter(None, [
        w["world"].get("background", ""), w["world"].get("tone", ""),
        *[r.get("content", "") for r in w.get("rules", [])],
        *[e.get("title", "") for e in w.get("endings", [])],
    ]))
    rec.leak_hits = schema.detect_leak(visible, w)


# ── 状态机引擎 + 场景组 B ────────────────────────────────────────────
@dataclass
class Engine:
    world: dict
    turn: int = 0
    status: str = "ongoing"
    hp: float = 100
    san: float = 100
    timeline: str = ""
    log: list = field(default_factory=list)
    log_summary: str = ""
    triggered: set = field(default_factory=set)
    issues: list = field(default_factory=list)  # 一致性/泄露问题清单

    def __post_init__(self):
        attrs = self.world.get("character", {}).get("attributes", {})
        self.hp = attrs.get("hp", 100)
        self.san = attrs.get("san", 100)

    def context_json(self) -> str:
        """回传模型的真理之源:world + 当前 state(旧 log 已压进 logSummary)。"""
        state = {
            "turn": self.turn, "status": self.status, "timeline": self.timeline,
            "logSummary": self.log_summary, "log": self.log[-LOG_KEEP:],
        }
        payload = {**self.world, "state": state,
                   "character": {**self.world.get("character", {}),
                                 "attributes": {"hp": self.hp, "san": self.san}}}
        return json.dumps(payload, ensure_ascii=False)

    def apply(self, parsed: dict, player_action_id: str):
        """把模型本回合产出落进真理之源,并做一致性/泄露核对。"""
        self.turn += 1
        upd = parsed.get("stateUpdate", {})
        new_hp, new_san = upd.get("hp", self.hp), upd.get("san", self.san)
        # 一致性核对①:hp/san 不得无故回升(允许相等或下降)。
        if new_hp > self.hp:
            self.issues.append(f"T{self.turn} hp 回升 {self.hp}->{new_hp}")
        if new_san > self.san:
            self.issues.append(f"T{self.turn} san 回升 {self.san}->{new_san}")
        self.hp, self.san = max(0, new_hp), max(0, new_san)
        self.timeline = upd.get("timeline", self.timeline)
        # 一致性核对②:已触发规则保持记录(供后续回合核对一致性)。
        self.triggered |= set(parsed.get("triggeredRuleIds", []))
        for rid in parsed.get("discoveredRuleIds", []):
            for r in self.world.get("rules", []):
                if r.get("id") == rid:
                    r["discovered"] = True
        # 泄露核对③:玩家可见叙事不得含 isTrue/hiddenLogic。
        leak = schema.detect_leak(parsed.get("narrative", ""), self.world)
        # 追加 log,旧的压缩进 logSummary(成本控制,CONTEXT §三.1)。
        self.log.append({"turn": self.turn, "narrative": parsed.get("narrative", ""),
                         "playerAction": player_action_id})
        if len(self.log) > LOG_KEEP:
            old = self.log[:-LOG_KEEP]
            self.log_summary = (self.log_summary + " " + " ".join(
                f"[T{e['turn']}选{e['playerAction']}]" for e in old)).strip()
            self.log = self.log[-LOG_KEEP:]
        # 结局判定
        ending = parsed.get("ending")
        if ending and ending.get("reached"):
            self.status = "ended"
            for e in self.world.get("endings", []):
                if e.get("id") == ending.get("id"):
                    e["reached"] = True
        if self.hp <= 0 or self.san <= 0:
            self.status = "ended"
        return leak


# 三条玩家路径策略:对 availableActions 做确定性选择。
def _pick(actions: list[dict], path: str) -> dict:
    if not actions:
        return {"id": "?", "text": ""}
    if path == "B1":   # 求生:倾向第一个(通常最稳妥/遵守规则)
        return actions[0]
    if path == "B2":   # 作死:倾向最后一个(通常最冒险)
        return actions[-1]
    return actions[len(actions) // 2]  # B3 探索:取中间


def run_scenario_B(client, world: dict, turns: int = 10):
    """用 A1 的某个固定世界,连推 3 条路径 × turns 回合。"""
    records: list[CallRecord] = []
    if not world:
        return records, {}
    summary = {}
    for path in ("B1", "B2", "B3"):
        eng = Engine(world=json.loads(json.dumps(world)))  # 深拷贝,路径间隔离
        actions = world.get("availableActions") or [
            {"id": "A", "text": "谨慎观察四周"}, {"id": "B", "text": "原地等待"},
            {"id": "C", "text": "主动探查异常"}]
        for t in range(1, turns + 1):
            choice = _pick(actions, path)
            vars = {"TURN": str(t), "CONTEXT_JSON": eng.context_json(),
                    "PLAYER_ACTION": f"{choice['id']}. {choice['text']}"}
            rec = _generate(client, name="event-loop", step="event-loop",
                            scenario=f"{path}-T{t}", vars=vars,
                            validate=schema.validate_turn)
            records.append(rec)
            if not (rec.schema_ok and rec.parsed):
                eng.issues.append(f"T{t} 产出非法,路径中断")
                break
            leak = eng.apply(rec.parsed, choice["id"])
            rec.leak_hits = leak
            actions = rec.parsed.get("availableActions", actions)
            if eng.status == "ended":
                break
        summary[path] = {
            "turns_done": eng.turn, "status": eng.status,
            "final_hp": eng.hp, "final_san": eng.san,
            "issues": eng.issues, "triggered_rules": sorted(eng.triggered),
        }
    return records, summary
