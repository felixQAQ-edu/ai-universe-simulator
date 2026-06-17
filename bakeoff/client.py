"""统一 OpenAI 兼容客户端 + 思考模式适配器 + 每次调用全量日志(ADR-001 §5.1/§5.2/§5.4)。

- generate() 流式调用,记录 TTFT、总延迟、token(命中/未命中/输出)、原始响应、估算成本。
- 各家“思考开关”非标参数在 _thinking_extra_body() 收口,不污染主流程。
- MockClient:无 key 时跑通整条管线用(--mock),返回 schema 合法的假数据。
"""
from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field, asdict
from typing import Any

from providers import Provider


@dataclass
class CallRecord:
    provider: str
    model: str
    step: str                 # world-gen / event-loop / ...
    scenario: str             # A1 / B1 / ...
    attempt: int = 1          # 1 = 首次,2 = 修复重试(F-002:首次失败也落盘)
    ok: bool = False          # 调用是否成功返回
    ttft_s: float | None = None
    latency_s: float | None = None
    input_tokens: int = 0
    cache_hit_tokens: int = 0
    cache_miss_tokens: int = 0
    output_tokens: int = 0
    cost_cny: float = 0.0
    repaired: bool = False    # 是否触发了一次“修复重试”
    schema_ok: bool | None = None
    schema_errors: list[str] = field(default_factory=list)
    leak_hits: list[str] = field(default_factory=list)
    error: str | None = None
    raw: str = ""             # 原始文本响应
    parsed: Any = None        # 解析后的 JSON(若成功)

    def row(self) -> dict:
        d = asdict(self)
        d.pop("parsed", None)              # parsed 体积大,明细行不落
        d["raw"] = (self.raw or "")[:4000]  # 截断,避免明细爆炸
        return d


def _thinking_extra_body(p: Provider) -> dict:
    """把“是否思考”翻译成各家非标参数。⚠️ 具体参数名以官方文档为准,占位待核。"""
    if p.base_url.startswith("https://api.deepseek.com"):
        # DeepSeek V4-Flash 思考/非思考:占位用 thinking.type。VERIFY。
        return {"thinking": {"type": "enabled" if p.thinking else "disabled"}}
    if "dashscope" in p.base_url:                      # 通义千问
        return {"enable_thinking": p.thinking}
    if "bigmodel" in p.base_url:                        # 智谱 GLM
        return {"thinking": {"type": "enabled" if p.thinking else "disabled"}}
    return {}


class LLMClient:
    def __init__(self, provider: Provider, temperature: float = 0.7):
        from openai import OpenAI  # 延迟导入,--mock 时无需安装也能跑
        key = os.environ.get(provider.api_key_env)
        if not key:
            raise RuntimeError(
                f"环境变量 {provider.api_key_env} 未设置。把 .env.example 复制为 "
                f".env 并填入 key,或用 --mock 跑通管线。"
            )
        self.p = provider
        self.temperature = temperature
        self._client = OpenAI(api_key=key, base_url=provider.base_url)

    def generate(self, *, messages: list[dict], step: str, scenario: str,
                 json_mode: bool = True) -> CallRecord:
        rec = CallRecord(provider=self.p.key, model=self.p.model,
                         step=step, scenario=scenario)
        t0 = time.perf_counter()
        try:
            kwargs: dict[str, Any] = dict(
                model=self.p.model,
                messages=messages,
                temperature=self.temperature,
                stream=True,
                stream_options={"include_usage": True},
            )
            if json_mode:
                kwargs["response_format"] = {"type": "json_object"}
            extra = _thinking_extra_body(self.p)
            if extra:
                kwargs["extra_body"] = extra

            chunks: list[str] = []
            usage = None
            for chunk in self._client.chat.completions.create(**kwargs):
                if getattr(chunk, "usage", None):
                    usage = chunk.usage
                if not chunk.choices:
                    continue
                delta = chunk.choices[0].delta
                piece = getattr(delta, "content", None)
                if piece:
                    if rec.ttft_s is None:
                        rec.ttft_s = time.perf_counter() - t0
                    chunks.append(piece)

            rec.latency_s = time.perf_counter() - t0
            rec.raw = "".join(chunks)
            if usage is not None:
                rec.input_tokens = getattr(usage, "prompt_tokens", 0) or 0
                rec.output_tokens = getattr(usage, "completion_tokens", 0) or 0
                # DeepSeek 在 usage 上暴露缓存命中/未命中(指标 #8)。
                rec.cache_hit_tokens = getattr(usage, "prompt_cache_hit_tokens", 0) or 0
                rec.cache_miss_tokens = getattr(usage, "prompt_cache_miss_tokens", 0) or 0
                if not (rec.cache_hit_tokens or rec.cache_miss_tokens):
                    rec.cache_miss_tokens = rec.input_tokens  # 无缓存信息则全算未命中
            rec.cost_cny = self.p.price.estimate(
                rec.cache_hit_tokens, rec.cache_miss_tokens, rec.output_tokens
            )
            rec.ok = True
        except Exception as e:  # noqa: BLE001 — bake-off 要把错误率算进可用性(指标 #9)
            rec.latency_s = time.perf_counter() - t0
            rec.error = f"{type(e).__name__}: {e}"
        return rec


class MockClient:
    """无 key 的离线管线自检。返回 schema 合法的假 JSON,模拟 TTFT/token/成本。"""

    def __init__(self, provider: Provider, temperature: float = 0.7):
        self.p = provider
        self.temperature = temperature
        self._turn = 0

    def generate(self, *, messages: list[dict], step: str, scenario: str,
                 json_mode: bool = True) -> CallRecord:
        time.sleep(0.01)
        rec = CallRecord(provider=self.p.key, model=self.p.model,
                         step=step, scenario=scenario, ok=True,
                         ttft_s=0.3, latency_s=1.2)
        rec.raw = _mock_world() if step == "world-gen" else _mock_turn()
        rec.input_tokens, rec.cache_hit_tokens, rec.cache_miss_tokens = 1200, 800, 400
        rec.output_tokens = 600
        rec.cost_cny = self.p.price.estimate(800, 400, 600)
        return rec


def _mock_world() -> str:
    return json.dumps({
        "schemaVersion": "0.1", "mode": "single", "archetypes": ["rules_creepy"],
        "world": {"title": "雨夜便利店", "background": "凌晨的城郊便利店,雨声不停。",
                  "dangerLevel": "high", "tone": "压抑、潮湿、规则森严"},
        "character": {"attributes": {"hp": 100, "san": 100},
                      "traits": ["胆小", "观察力强"], "inventory": ["手电筒", "员工手册"]},
        "rules": [
            {"id": 1, "content": "0 点后不要回应敲窗声。", "isTrue": True,
             "hiddenLogic": "回应会让窗外之物确认店内有人,触发 san-20。", "discovered": False},
            {"id": 2, "content": "货架第三排的牛奶可以免费喝。", "isTrue": False,
             "hiddenLogic": "喝下后 hp-30,这是诱饵规则。", "discovered": False},
            {"id": 3, "content": "听到电话响必须接。", "isTrue": True,
             "hiddenLogic": "不接则 san-10,接则获得线索。", "discovered": False},
        ],
        "endings": [
            {"id": "survive_dawn", "title": "撑到天亮", "condition": "turn>=10 且 hp>0 且 san>0",
             "reached": False},
            {"id": "lost_mind", "title": "精神崩溃", "condition": "san<=0", "reached": False},
        ],
    }, ensure_ascii=False)


def _mock_turn() -> str:
    return json.dumps({
        "narrative": "窗外传来三声敲击。你按手册压低身子,没有回应,雨声重新盖过一切。",
        "stateUpdate": {"hp": 100, "san": 95, "timeline": "夜班第一小时,守住了第一条规则。"},
        "triggeredRuleIds": [1], "discoveredRuleIds": [1],
        "availableActions": [
            {"id": "A", "text": "继续盘点货架", "hint": ""},
            {"id": "B", "text": "查看监控画面", "hint": ""},
            {"id": "C", "text": "翻看员工手册", "hint": ""},
        ],
        "ending": None,
    }, ensure_ascii=False)


def build_client(provider: Provider, *, mock: bool, temperature: float):
    return MockClient(provider, temperature) if mock else LLMClient(provider, temperature)
