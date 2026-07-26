# AI Universe Simulator

基于 LLM 的生成式文字模拟游戏：玩家作为绝对变量介入 AI 动态生成、逻辑自洽的世界。
杀手锏：概念融合·混合模式（世界观杂交）。当前 **Phase 3 收尾转前端视觉轮**；目标用户原定
国内微信生态，现走**路线 B**（境外托管、不备案、暂不进微信生态）先做软启动验证。
已上公网 https://wanjie-ai.fly.dev 。最新进度以 `docs/ROADMAP.md` 为准。

## 动任何模块前先读
@docs/CONTEXT.md   # 术语 / 统一 JSON Schema / 命名与工程约定（约定的真理之源）
@docs/ROADMAP.md   # 路线图、当前进度、待决 ADR

## 常驻约束的真理源在 `AGENTS.md` —— **动手前读它**

本项目的 agent 常驻约束**统一放 [`AGENTS.md`](AGENTS.md)**（Claude Code / Codex 同一套），
此处只留指针，不维护第二份全文：

- **§ Workflow Discipline（工作流纪律）** — 勘察先行（**与前提不符即停下报告，不自行调和**）／
  决策先落 ADR 再实现／feature 分支 + atomic commit + **ff 合并等 Felix 点头、不擅自 push**／
  如实报别反应式修补（**不为收口说通过**）／视觉体感判断权在 Felix／**花钱与对外动作 Felix 亲手、
  key 不进对话**／文档口径单一真理源／范围纪律（「不准顺手做」当硬线）。
- **§ Motion Constraints（前端视觉/动效）** — 记忆点唯一／动效预算／新增须替代或降级／正文禁区／
  清理与 reduced-motion／库白名单（**白名单是许可不是义务**）／GSAP skill 不适用清单。
  真理之源 = ADR-017。

**为什么真理源是 `AGENTS.md` 而不是本文件**：这两份文件曾各存一份几乎相同的全文，并**已经漂移过**
——2026-07-23 那批只订正了 `AGENTS.md`，本文件直到 2026-07-26 仍写着「Phase 0 / 微信生态」。
既然只能留一处，就留 agent 生态里覆盖面更广的那份（`AGENTS.md` 是 Codex 的自动载入文件名，
也已是 Motion Constraints 的既定落点）；Claude Code 侧靠本文件这条指针跳转，该模式已被实证有效。

## Claude Code 专有
- 技术决策用 `/adr-author` 生成 `docs/adr/`；进度用 `/roadmap-update` 更新 ROADMAP
  （这两个 slash command 只有 Claude Code 有；其它 agent 按 `AGENTS.md` 的纪律手写同样结构即可）。
- 架构/决策讨论在 Project 对话；视觉探索走 Cowork 独立样板间（不直接改 `web/src`），
  写码、测试、提交走 Claude Code（见 ADR-017 §8）。
- 详细 ADR / FINDINGS 按需读，不必每次全载，以省上下文。

## 仓库结构速查
- `docs/` — ROADMAP / CONTEXT / adr/ + 各批设计稿与 backlog
- `prompts/` — 管线各步提示词（world-gen / event-loop / …），是核心资产
- `bakeoff/` — provider 验证脚手架 + `FINDINGS.md`（验证中的发现记这里）
- `server/` — Spring Boot 后端；`web/` — React + Vite 前端
- `.agents/skills/` — 项目级 skill（GSAP 四件装 + adr-author / roadmap-update）
