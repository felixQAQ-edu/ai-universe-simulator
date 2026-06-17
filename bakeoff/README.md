# bakeoff · Provider Bake-off 验证脚本

Phase 0 核心验证,落地 [docs/adr/ADR-001-bakeoff-checklist.md](../docs/adr/ADR-001-bakeoff-checklist.md)。
本轮按 ADR §8 第 1–2 步:**只用 DeepSeek V4-Flash 跑通客户端 + provider 配置表 + 场景组 A+B**。

## 跑

```bash
cd bakeoff
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

# 离线跑通整条管线(无需 key,验证工程实现)
.venv/bin/python run.py --mock

# 真实调用:把 .env.example 复制为 .env 填 DEEPSEEK_API_KEY,然后:
cp .env.example .env       # 编辑填 key
.venv/bin/python run.py                       # 默认 A1×5 + A2×3,B 3 路径×10 回合
.venv/bin/python run.py --runs 5 --turns 10   # 自定义
```

产出:`out/calls.jsonl`(每次调用一行明细)+ `out/report.md`(provider×指标 汇总)。

## 结构(对齐 ADR §5)

| 文件 | 职责 |
|------|------|
| `providers.py` | **provider 配置表**:base_url / model / key / 价格 / 思考开关 / maxContext。换 provider 只改这里。 |
| `client.py` | 统一 OpenAI 兼容客户端 + 思考模式适配器(`extra_body` 收口)+ 全量调用日志 + `--mock`。 |
| `schema.py` | v0.1 schema 校验(world / turn)+ 泄露检测(isTrue/hiddenLogic 不进玩家可见文本)。 |
| `prompts.py` | 加载 / 拼装根目录 [`../prompts/`](../prompts/) 提示词。 |
| `scenarios.py` | 状态机引擎(state 真理之源)+ 场景组 A(world-gen 质量)+ B(连推 10 回合,3 路径)。 |
| `report.py` | 产出明细 jsonl + 汇总 report.md,按 ADR §3 通过线标注。 |
| `run.py` | CLI 入口。 |

## 仍待人工 / 后续(ADR §8 第 3–4 步)

- 指标 #4 中文叙事质量 / A3 规则质量:按 ADR §6 rubric **盲评**(脚本只导出素材,不自动打分)。
- 横评:解开 `providers.py` 中 Qwen / GLM 占位,核实单价与 model id、填 key,重跑追加对比列。
- 场景组 C(鲁棒性)/ D(结局收敛)、可选 provider:后续补。
- ⚠️ `providers.py` 里 DeepSeek 单价为**占位**,跑真实成本前去官方定价页核对。

> 注:`run-time` 内容生成用 DeepSeek(provider 可换);本脚本本身是 `build-time` 工具(CONTEXT §三.2)。
