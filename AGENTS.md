# AI Universe Simulator

基于 LLM 的生成式文字模拟游戏：玩家作为绝对变量介入 AI 动态生成、逻辑自洽的世界。
杀手锏：概念融合·混合模式（世界观杂交）。目标用户：国内、微信生态。当前 Phase 0。

## 协作分工
架构/决策讨论在 Project 对话；写码、测试、提交在 Codex。
改动请先讲清思路再动手，避免无说明的大改。

## 动任何模块前先读
@docs/CONTEXT.md   # 术语 / 统一 JSON Schema / 命名与工程约定（约定的真理之源）
@docs/ROADMAP.md   # 路线图、当前进度、待决 ADR

## 工程约定
- 技术决策落 `docs/adr/`（用 `/adr-author`）；进度用 `/roadmap-update` 更新 ROADMAP。
- 验证中的发现记 `bakeoff/FINDINGS.md`；约定或 schema 变更走 CONTEXT 升版本号，不靠提示词反复叮嘱。
- 提示词是核心资产，放仓库根 `prompts/`，按管线步骤组织（world-gen / event-loop / …）。
- 运行模型（给玩家生成）= DeepSeek，见 ADR-001；写码用 Codex，两者别混。

## 安全与纪律
- API key 只进 `bakeoff/.env`（已 gitignore），绝不写进对话、代码或提交。
- 从 `main` 切特性分支干活；一段完整工作就提交一次。
- 详细 ADR / FINDINGS 按需读，不必每次全载，以省上下文。

## 仓库结构速查
- `docs/` — ROADMAP / CONTEXT / adr/
- `prompts/` — 管线各步提示词
- `bakeoff/` — provider 验证脚手架（client / providers / schema / scenarios / report / run + FINDINGS）

---

# Motion Constraints（前端视觉/动效常驻约束）

> 真理之源 = **[ADR-017](docs/adr/ADR-017-frontend-visual-charter-and-animation-libraries.md)**（前端视觉宪法与动画库许可名单，修订 ADR-003）。本节是其**常驻可执行版**（lockstep 副本）：ADR 管「为什么」，本节管「动手时不许越的线」。两处不一致时**以 ADR-017 为准**并回改本节。
>
> **分工立字**：**GSAP Skill 管技术正确性**（API 怎么用才对、怎么清理、怎么不掉帧）；**AGENTS.md 与 ADR 管审美边界与架构约束**（做不做、做几个、谁给谁让路）。**冲突时项目 ADR 优先。**

## 0. 视觉宪法（ADR-017 §2，援引用）

1. **内容永远第一，任何动画不能影响阅读**（唯一的一票否决项）。
2. **动画不是为了炫，而是为了表达。**
3. **每个世界都有自己的视觉语言**——玩家不看标题就知道进了哪个世界。
4. **追求记忆点，不追求炫**——「世界会变化，而不仅仅是页面会动画」。

优先级配比：**氛围沉浸 50% > 交互反馈 35% > 关键时刻 15%**。反面清单：不做「AI PPT 审美」（紫色玻璃拟态、霓虹渐变堆砌、无意义通用炫技）。

## 1. 每个世界只有一个一级记忆点（唯一，不得增设）

| 世界 | 一级记忆点 |
|---|---|
| 修仙 `cultivation` | **钟鸣共振** bell resonance |
| 规则怪谈 `rules_creepy` | **灯闪** light flicker |
| 克苏鲁 `cthulhu` | **文字异常/不确定** uncertain text anomaly |
| 末日 `apocalypse` | **电台信号** radio transmission |

- **一级 = 该世界的签名动作，一个世界只准有一个。**想让某世界「再多一个记忆点」时，**答案是加强现有那个，不是并列第二个**——并列即稀释，四个世界各自加一点，最后四个世界长得一样。
- 融合世界不新立一级记忆点：**host 世界的记忆点打底 + 渗漏元素点缀**（同 ADR-012「host 优先 + 语义换皮」）。
- 与 ADR-017 §6.3 第一版三点的关系：规则怪谈灯闪 / 克苏鲁文字异常逐条对应；修仙的一级记忆点收敛为**钟鸣共振**（「境界跳变整页照亮」是它的一次表现，不另立一级）；末日电台为 B 批新增。

## 2. 动效预算（每屏硬上限）

**任一时刻同一屏最多**：

- **1 个持续环境动效**（呼吸感 / 云雾 / 沙尘一类，长期在跑的）
- **+ 1 个低频偶发动效**（灯闪 / 远光扫过 / 电台杂讯一类，隔一阵来一下的）
- **+ 1 个用户触发动效**（按压 / 数值滚动 / 规则点亮一类，玩家动作的回应）

超出即撞原则 ①。**预算是硬的**：想加第二个持续环境，就得先关掉现有那个。

## 3. 新增动效必须声明它替代谁或降级谁

任何新效果的提案/实现说明里必须有一句：**「它替代 X」或「它把 X 降级为 Y」**——**不得只做加法**。

原因：护栏若只写在单轮任务指令里，下一轮新需求一来即失效——修仙再加剑影、规则怪谈再加故障、克苏鲁再加扭曲、末日再加沙尘爆闪，每一步都局部合理，合起来就是四个世界一起变成霓虹灯。**加法要付账**，账就是这一句。

## 4. 正文即禁区

- **叙事正文永不持续动画**（不呼吸、不抖动、不循环发光；一次性的进入/揭示动画做完即止）。
- **高亮与粒子进入正文区前必须淡出**——灰尘/孢子/灰烬飘到正文上方即淡出，绝不压在字上。

## 5. 生命周期与清理（技术硬线）

- **所有 `timeline` / `delayedCall` / `Observer` / 事件监听必须在卸载时清理**（React 侧优先 `gsap.context()` + `ctx.revert()`，见 `.agents/skills/gsap-react/`）。
- **重复输入不得堆叠 timeline**：同一触发再次到来时，**先 kill/复用既有 timeline 再起新的**（或直接 `gsap.quickTo` 一类可复用件）。玩家连点选项不该攒出十条并行时间线。
- **必须实现 reduced-motion 降级**：`prefers-reduced-motion: reduce` 下动效降到最简或直接关掉（`gsap.matchMedia()` 的 `reduceMotion` 条件）。现有前端已全局尊重该媒体查询，新增效果不得开天窗。

## 6. 性能

- **优先 `transform` 与 `opacity`**（`x`/`y`/`scale`/`rotation`/`autoAlpha`）；**避免动画化布局属性**（`width`/`height`/`top`/`left`/`margin`/`padding`）。
- 真实风险面是**低端 Android 与微信内置 WebView**（ADR-017 已知代价 2）：动效越重越挑设备，不达标就降级或退回 CSS。

## 7. 库许可（ADR-017 §3，白名单制）

**白名单第一批，仅三个**：**GSAP**（时间线编排）/ **Motion**（React 声明式交互）/ **Lenis**（平滑滚动）。
**缓引不引**：Particles / Lottie / 轻量 Shader。**Three.js 明确不做。**
**白名单之外的任何库（含缓引清单转正）须回 Project 窗口对齐，不得在实现批自行引入。**

**减债习惯**：能用 CSS 达到的效果优先用 CSS；库只用在 CSS 明显不划算处（时间线编排、可中断的连续插值）。引的每个库都是回国做小程序时视觉层重做的债。

## 8. GSAP Skill 的通用建议里，本项目不适用的部分

`.agents/skills/gsap-*` 是上游 GreenSock 原样拷贝（见 [`.agents/skills/GSAP-VENDOR.md`](.agents/skills/GSAP-VENDOR.md)），面向通用 web 场景。以下建议**本项目 ADR 优先，该建议不适用**：

| Skill 里的建议 | 本项目口径 |
|---|---|
| 推荐 **ScrollTrigger** 做滚动驱动动画（`gsap-core` / `gsap-timeline` / `gsap-performance` / `gsap-react` 多处提及） | **不适用。非真正滚动驱动的效果不得引入 ScrollTrigger。** 本项目是屏内状态型叙事界面，动效由**状态变化**驱动而非滚动位置；`gsap-scrolltrigger` skill 已刻意不装 |
| `gsap-react` §Installation 让 `npm install @gsap/react`（`useGSAP()` 钩子） | **不得自行引入**——它是白名单三库之外的**第四个 npm 包**，按 ADR-017 §3 升级条款须**回 Project 窗口对齐**。未对齐前走同一 skill 给出的退路：`useEffect` 内 `gsap.context()` + 清理里 `ctx.revert()` |
| `gsap-core` 指向 **`gsap-plugins`**（Flip / Draggable / MorphSVG…）与 **CustomEase** | 同包插件（`registerPlugin`）**不算新依赖、无移植债增量**，但仍受**动效预算**与原则 ② 约束：用之前要能回答「它服务哪个原则、替代了谁」。ScrollTrigger 是明确禁项，不在此列 |
| `gsap-core`「**Prefer GSAP Instead of CSS Animations**」 | **反过来**：ADR-017 §4 减债习惯 = **能用 CSS 的优先 CSS**，GSAP 留给时间线编排与连续插值 |
| `gsap-core`「未指定库时**默认推荐 GSAP**」 | 本项目**分工已定**：React 声明式交互（按压/进出场/数值滚动）归 **Motion**，GSAP 只做时间线编排——不因 skill 的默认推荐把 Motion 的活挪给 GSAP |
| `gsap-performance` 的 `will-change` / ScrollTrigger 性能节 | `will-change` 只给真正在动的元素（skill 自己也这么说）；ScrollTrigger 相关整节不适用 |
