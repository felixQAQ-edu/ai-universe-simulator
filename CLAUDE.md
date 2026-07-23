# AI Universe Simulator

基于 LLM 的生成式文字模拟游戏：玩家作为绝对变量介入 AI 动态生成、逻辑自洽的世界。
杀手锏：概念融合·混合模式（世界观杂交）。目标用户：国内、微信生态。当前 Phase 0。

## 协作分工
架构/决策讨论在 Project 对话；写码、测试、提交在 Claude Code。
改动请先讲清思路再动手，避免无说明的大改。

## 动任何模块前先读
@docs/CONTEXT.md   # 术语 / 统一 JSON Schema / 命名与工程约定（约定的真理之源）
@docs/ROADMAP.md   # 路线图、当前进度、待决 ADR

动前端视觉/动效前另读 `AGENTS.md` 的 **Motion Constraints** 一节（记忆点唯一 / 动效预算 /
新增须替代或降级 / 正文禁区 / 清理与 reduced-motion / 库白名单；真理之源 = ADR-017）。

## 工程约定
- 技术决策落 `docs/adr/`（用 `/adr-author`）；进度用 `/roadmap-update` 更新 ROADMAP。
- 验证中的发现记 `bakeoff/FINDINGS.md`；约定或 schema 变更走 CONTEXT 升版本号，不靠提示词反复叮嘱。
- 提示词是核心资产，放仓库根 `prompts/`，按管线步骤组织（world-gen / event-loop / …）。
- 运行模型（给玩家生成）= DeepSeek，见 ADR-001；写码用 Claude Code，两者别混。

## 安全与纪律
- API key 只进 `bakeoff/.env`（已 gitignore），绝不写进对话、代码或提交。
- 从 `main` 切特性分支干活；一段完整工作就提交一次。
- 详细 ADR / FINDINGS 按需读，不必每次全载，以省上下文。

## 仓库结构速查
- `docs/` — ROADMAP / CONTEXT / adr/
- `prompts/` — 管线各步提示词
- `bakeoff/` — provider 验证脚手架（client / providers / schema / scenarios / report / run + FINDINGS）
