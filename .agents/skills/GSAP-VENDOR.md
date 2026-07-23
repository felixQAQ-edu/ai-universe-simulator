# GSAP Agent Skills · 出处与许可(vendored)

本目录下的 `gsap-core/` `gsap-timeline/` `gsap-performance/` `gsap-react/` 四个 skill 是**上游原样拷贝**,供本仓库的 Agent 在写 GSAP 动效时读取。

## 出处

| 项 | 值 |
|---|---|
| 上游 | https://github.com/greensock/gsap-skills(GreenSock 官方) |
| 版本 | commit `aed9cfd3277740755f6bfc1155c7aa645403b760`(2026-04-21) |
| 许可 | MIT(见文末,随拷贝分发) |
| 拷入日期 | 2026-07-23 |
| 作用域 | **项目级**(`.agents/skills/`),**不装全局** `~/.agents/skills` |

**四个 SKILL.md 保持上游逐字节原样、不就地改写**——这样上游更新时可直接对拍 diff、看清建议有没有漂移。本项目的口径覆盖**一律写在 [`AGENTS.md`](../../AGENTS.md) 的 Motion Constraints 一节**,不改这些文件。

更新方式:重新 clone 上游对应 commit → 覆盖四个目录 → diff 审一遍新增建议有没有和 ADR-017 打架 → 更新本文的 commit 与日期。

## 只装四个:为什么不装另外四个

| 未装 | 理由 |
|---|---|
| `gsap-scrolltrigger` | **刻意不装**。本项目是**屏内状态型叙事界面**(一屏一回合、动效由状态变化驱动),不是滚动营销页;装了会把 Agent 往「滚动驱动」的思路上带 |
| `gsap-plugins` | 插件(Flip / Draggable / MorphSVG…)第一版用不到,且引插件属白名单话题(见 AGENTS.md) |
| `gsap-utils` | 工具函数(clamp / mapRange…)按需查文档即可,不值一个常驻 skill 的上下文 |
| `gsap-frameworks` | Vue / Svelte / Nuxt 相关,本项目是 React |

## 与 ADR-017 的关系(读之前先知道)

- **[ADR-017](../../docs/adr/ADR-017-frontend-visual-charter-and-animation-libraries.md) 是审美边界与架构约束的真理之源**;这四个 skill 只管**技术正确性**(GSAP API 怎么用才对、怎么清理、怎么不掉帧)。
- 四个 skill 里确有**与本项目 ADR 相抵触的通用建议**(推荐 ScrollTrigger、推荐装 `@gsap/react`、推荐「优先 GSAP 而非 CSS」等)。**逐条的项目口径见 [`AGENTS.md` § Motion Constraints](../../AGENTS.md)**——冲突时**项目 ADR 优先**。

## LICENSE(上游 MIT,随拷贝分发)

```
MIT License

Copyright (c) 2026 GreenSock

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
