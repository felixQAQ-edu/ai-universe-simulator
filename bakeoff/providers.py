"""Provider 配置表(ADR-001 §5.1)。

约定:统一走 OpenAI 兼容接口,换 provider 只改本表(base_url / model / key / 价格 /
思考模式开关 / maxContext)。非标参数(各家思考模式)在 client.py 的适配器里收口,
不污染主流程(ADR-001 §5.2)。

⚠️ 价格与 model id 变动频繁。本次只激活 DeepSeek V4-Flash;其余为横评阶段占位,
   跑前务必去各家官方定价页核对(ADR-001 §2 注)。单价单位:CNY / 1M tokens。
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Price:
    """CNY per 1M tokens。input 区分缓存命中/未命中(DeepSeek 缓存专项,指标 #8)。"""
    input_cache_miss: float
    input_cache_hit: float
    output: float
    currency: str = "CNY"

    def estimate(self, hit_tokens: int, miss_tokens: int, out_tokens: int) -> float:
        return (
            miss_tokens / 1_000_000 * self.input_cache_miss
            + hit_tokens / 1_000_000 * self.input_cache_hit
            + out_tokens / 1_000_000 * self.output
        )


@dataclass(frozen=True)
class Provider:
    key: str                 # 内部唯一标识,= CLI --provider 值
    label: str               # 人读名
    base_url: str
    model: str
    api_key_env: str         # 从该环境变量读 key
    price: Price
    max_context: int
    thinking: bool = False   # 是否开启思考模式;event-loop 主力默认关
    priority: str = "core"   # core | optional
    # 各家“思考开关”落到非标参数,由 client 适配器翻译,这里只声明意图。
    notes: str = ""


# ── 配置表 ──────────────────────────────────────────────────────────────
# 本次 bake-off 实跑对象:仅 DeepSeek V4-Flash(非思考)。
PROVIDERS: dict[str, Provider] = {
    "deepseek-v4-flash": Provider(
        key="deepseek-v4-flash",
        label="DeepSeek V4-Flash(非思考)",
        base_url="https://api.deepseek.com",
        model="deepseek-v4-flash",          # 用户确认的 model id;变动请回此处改
        api_key_env="DEEPSEEK_API_KEY",
        price=Price(input_cache_miss=1.0, input_cache_hit=0.02, output=2.0),
        max_context=1_000_000,
        thinking=False,
        priority="core",
        notes="event-loop 高频回合主力候选。缓存命中/未命中成本差为指标 #8 观察项。",
    ),

    # ── 以下为横评阶段占位(本次不跑),价格/model 均为占位待核 ───────────────
    "deepseek-v4-pro": Provider(
        key="deepseek-v4-pro", label="DeepSeek V4-Pro",
        base_url="https://api.deepseek.com", model="deepseek-v4-pro",
        api_key_env="DEEPSEEK_API_KEY",
        price=Price(input_cache_miss=3.0, input_cache_hit=0.025, output=6.0),
        max_context=1_000_000, thinking=False,
        priority="core", notes="world-gen / 难场景候选,低频高价值。占位 VERIFY。",
    ),
    "qwen3.5-plus": Provider(
        key="qwen3.5-plus", label="通义千问 qwen3.5-plus",
        base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
        model="qwen3.5-plus", api_key_env="DASHSCOPE_API_KEY",
        price=Price(4.0, 4.0, 12.0), max_context=128_000, thinking=False,
        priority="core", notes="兜底候选。思考开关用 enable_thinking。占位 VERIFY。",
    ),
    "glm-5.1": Provider(
        key="glm-5.1", label="智谱 glm-5.1",
        base_url="https://open.bigmodel.cn/api/paas/v4",
        model="glm-5.1", api_key_env="ZHIPU_API_KEY",
        price=Price(5.0, 5.0, 15.0), max_context=128_000, thinking=False,
        priority="core", notes="备选。思考开关用 thinking 参数。占位 VERIFY。",
    ),
}


def get(provider_key: str) -> Provider:
    if provider_key not in PROVIDERS:
        raise KeyError(
            f"未知 provider: {provider_key!r}。可选:{', '.join(PROVIDERS)}"
        )
    return PROVIDERS[provider_key]
