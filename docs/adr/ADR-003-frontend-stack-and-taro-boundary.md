# ADR-003 · 前端栈选型与 Taro 跨端边界——React+Vite H5 先行,以接口纪律占住小程序迁移边界

- **日期**:2026-06-23
- **状态**:已采纳
- **决策者**:Felix

## 背景

Phase 1(单模式 H5 闭环·规则怪谈)的里程碑 = **朋友能用手机从头玩到一个完整结局**。后端整局闭环(init→回合→结局)已合入 `main`、真 key 冒烟通过,前端要接的 wire 已实测定型:

- **init = plain POST 返消毒 JSON**([ADR-007](ADR-007-world-gen-wire-protocol.md)):`POST /api/game/init` → `{ saveId, world:<消毒投影>, openingNarrative, availableActions }`,失败 `502 {error}`。
- **回合 = SSE 命名事件**([ADR-006](ADR-006-event-loop-streaming-wire-protocol.md) §4.2,冒烟实测线序 `narrative…→delta→ending`/`error`)。

ROADMAP §三 早定大方向(React+Vite H5 → Taro 小程序),`web/` 已 scaffold(`features/state/api` 占位 + `src/types/schema.ts` 落 schema v0.2 TS 类型 + 移动优先基底)。故 ADR-003 的真正价值**不是选框架**(框架已定),而是**把 Taro 迁移边界划在哪**——现在写 H5 的每个决定,哪些日后能无痛编译进小程序,哪些会变成迁移税。

两处 Taro 天然不兼容,逼出边界设计:

1. **平台 IO**:H5 用 `fetch`/`EventSource`,小程序用 `wx.request`/`wx.connectSocket`,且**小程序无原生 SSE**。
2. **DOM/CSS**:小程序是 `View`/`Text` 非 `div`,部分 CSS 不支持,CSS-in-JS 运行时方案基本不可用。

约束条件:

1. **聚焦 Phase 1 里程碑**:用户数为零、仍在 Phase 1 的项目,头号敌人是范围膨胀(业余每周 10–20h),不应提前背完整跨端实现。
2. **前端有确定契约可接**:wire 已由 ADR-006/007 定型,不悬空——边界设计要正好卡在「H5 实现」与「平台无关逻辑」之间。
3. **复用后端薄接缝哲学**:后端 `LlmClient`/`TokenStream`([ADR-001](ADR-001-runtime-model-and-provider-abstraction.md)/[ADR-005](ADR-005-sse-web-stack-mvc-thin-seam.md))已证「用接缝隔离可换部件」可行,前端应镜像同一心智,避免各写各的。

## 候选方案

### 方案 A:现在就把 Taro 边界实现完整(写小程序适配)

H5 与小程序两套 IO 实现一起写,边界用「双实现并存」占住。

**优点**:
- Phase 4 迁移时小程序侧已就绪,无新实现工作。

**缺点**:
- 给用户数为零的 Phase 1 项目提前背跨端实现,**过早优化、违反范围膨胀纪律**(撞约束 1)。
- 小程序 WS 端点、`wx.*` 适配在没有真实小程序工程跑验的情况下写,大概率返工。**排除。**

### 方案 B:完全不留边界,H5 怎么顺手怎么写

逻辑层直接 `fetch`/`EventSource`,样式随手 Tailwind/CSS-in-JS。

**优点**:
- 当下最快,无任何接缝设计成本。

**缺点**:
- Phase 4 迁移返工税过高——逻辑层散落平台 API、运行时 CSS-in-JS 在小程序基本不可用,等于重写。**排除**:接口纪律是当下近乎零成本、日后省最多的折中,放弃它得不偿失。

### 方案 C:接口纪律占边界,不写小程序实现(本 ADR 采纳)

**核心原则:用接口纪律占住边界,不用实现占住边界。** 逻辑/状态/类型层平台无关(Taro 直接复用);平台 IO 收进一个薄适配层(`web/src/api/`),迁移时只换它。Phase 1 **不写任何小程序代码**,只保证边界接缝在对的位置。

**优点**:
- 聚焦 Phase 1 里程碑(能发朋友玩),H5 每个决定带跨端意识写、迁移返工最小(命中约束 1)。
- `api/` 适配层 + provider-agnostic 流接口让 Phase 4 换 WS 只动一层,不动逻辑层(命中约束 2)。
- 复用后端薄接缝哲学,build-time/run-time 两侧心智统一(命中约束 3)。
- 零小程序实现 = 零当下成本。

**缺点**:
- 接口纪律需评审/lint 守住——逻辑层一旦漏 import `EventSource` 就破功。缓解方式:加可检查硬线(逻辑/状态层对 `EventSource`/`fetch`/`wx` 的 import 计数 = 0,lint 规则或自查)。
- WS 预案未实现 = Phase 4 仍有未验风险。缓解方式:接口已隔离,风险被限在适配层一处,不外溢逻辑层。

## 最终决策

**方案 C — 接口纪律占住 Taro 边界,Phase 1 只写 H5 实现,不写任何小程序代码。**

### 1. 栈

React + Vite + TypeScript(已 scaffold),移动优先 H5。Taro 迁移留 Phase 4 专门 ADR,本 ADR 不实现。

### 2. 跨端边界(核心)

- **平台无关层(Taro 直接复用)**:游戏逻辑、状态管理、`src/types/schema.ts`(schema v0.2 TS 类型)、纯展示组件的逻辑部分。**不得 import 任何平台 API**(`fetch`/`EventSource`/`wx.*`)。
- **平台适配层 `src/api/`(迁移时只换它)**:所有网络/流 IO 经此。对上暴露 provider-agnostic 接口,对下 H5 用 `fetch`+`EventSource` 实现。
- **验收纪律(可检查的硬线)**:逻辑/状态层对 `EventSource`/`fetch`/`wx` 的 import 计数 = 0(lint 规则或评审约束)。

### 3. SSE 跨端(c 纪律 + a 预案)

- **Phase 1 只写 H5/SSE**,但 `api/` 把回合流抽象成 **provider-agnostic 流接口**——逻辑层只见「narrative 增量流 / 一个 delta / 一个 ending / error」,**永不见 `EventSource`**:

  ```ts
  interface TurnStream {
    onNarrative(cb: (textDelta: string) => void): void;
    onDelta(cb: (delta: ClientStateDelta) => void): void;
    onEnding(cb: (ending: ClientEnding) => void): void;
    onError(cb: (err: { code: string; message: string }) => void): void;
  }
  ```

  H5 实现用 `fetch`+`ReadableStream` 流式读 body 解析 SSE 喂这个接口,映射到已定型的 SSE 事件(ADR-006 §4.2)。**非原生 `EventSource`**——回合端点是 `POST /api/game/{saveId}/turn` 带 JSON body,而浏览器 `EventSource` 只能 GET、不能带 body,接不了这个 wire(实现期校正,见下「实际效果」与 CONTEXT §三.13;边界不受影响,逻辑层依旧只见 `TurnStream`)。

- **预案(记录,不实现)**:小程序无原生 SSE → Phase 4 流式回合两条路,**主次序据 H5 实现校正**:
  - **主预案 `wx.request` + `enableChunked` 分块回调**:与 H5 **同构**——同样是「`POST` 带 body + 流式读 chunk 自解析」的范式(H5 用 `fetch`+`ReadableStream`,小程序用 `wx.request` 的 `onChunkReceived` 分块回调),后端**无需新端点**(复用现有 `POST .../turn` 的 SSE 响应,小程序侧只换 chunk 读取与帧解析的适配实现)。沿用 H5 已跑通的 SSE 帧切分逻辑,迁移税最低。兼容性(基础库版本 / 微信内核)待 Phase 4 真机验。
  - **备选 WebSocket**:`enableChunked` 若在目标机型/基础库不稳,退回后端加 WS 端点桥到同一 `TokenStream`(守 ADR-005 再加一层薄 web 适配),前端新增一个 `TurnStream` 的 WS 实现。这是**更重的另一套范式**(双向长连、后端新端点),故降为备选。
  - 两路**都只动适配层**,逻辑层零改——接口已留对。**主次对调的理由**:H5 落地实测确认回合走 `POST`+body+流式读 chunk(非 `EventSource`),`enableChunked` 与之同构、复用最多;原先把 WebSocket 列主、chunked 列备的判断基于「H5 用 EventSource」的假设,该假设已被实现校正,故对调。

### 4. 状态管理

**Zustand**。理由:状态规模小(单局单人——一份消毒 client world + 流式增量:narrative 累加、delta 落数值/规则面板、ending 出画面),避免 Redux 过度工程,Taro 兼容好。**不选** Redux Toolkit(规模不配)、**不选**任何把状态耦进平台 IO 的方案。

### 5. 样式

**原生 CSS / CSS Modules**。理由:Taro 迁移最安全(Tailwind 在 Taro 需额外配置;CSS-in-JS 运行时小程序基本不可用)。移动优先基底(viewport/safe-area/深浅色)已在 scaffold。`frontend-design` 内置 skill 此阶段可用于视觉方向,但**不引入运行时 CSS-in-JS**。

### 关键理由

1. **边界划在「H5 实现 vs 平台无关逻辑」之间**,正好卡住 Taro 两处天然不兼容(平台 IO + DOM/CSS),呼应约束 1/2。
2. **复用后端薄接缝肌肉记忆**:`api/` 之于前端 = `LlmClient`/`TokenStream` 之于后端,build/run 两侧统一(约束 3)。
3. **避开「为 Phase 4 提前背实现」陷阱**:接口纪律近乎零成本,实现成本留到真有小程序工程时付。
4. **保留演进路径**:WS 预案接口已留,Phase 4 换传输只动适配层。

## 已知代价

1. **接口纪律靠人/lint 守**:逻辑层漏 import `EventSource`/`fetch`/`wx` 就破功。缓解方式:落可检查硬线(import 计数 = 0,lint 规则或评审),CI/自查兜底。
2. **WS 预案未实现**:Phase 4 仍有未验风险(小程序无 SSE,WS 端点两侧都要新写)。缓解方式:接口已隔离,风险限在适配层,不外溢逻辑/状态层。
3. **Zustand/CSS Modules 的 Taro 兼容性未在真实小程序工程验**:若 Phase 4 暴露兼容坑需局部返工。接受理由:已在各自类别里选最安全项(Zustand 轻量无平台耦合、CSS Modules 无运行时),风险低。
4. **方案 A/B 的代价**:A 是给零用户项目提前背跨端实现(过早优化);B 是 Phase 4 重写税过高。两者均不接受——C 用一层薄接缝换「当下零成本 + 日后省最多」。

## 重新审视的触发条件

- **Phase 4 启动 Taro 迁移**:届时写专门 ADR 落小程序 WS 端点 + `wx.*` 适配实现;若 Zustand/CSS Modules 暴露 Taro 兼容坑,在那条 ADR 里评估替换。
- **回合传输需求变化**:若 Phase 2/3 发现 SSE 在某些网络/微信内置浏览器下不稳,提前评估 WS,接口已留对则只动适配层。
- **逻辑层平台 import 出现**:评审/lint 抓到逻辑或状态层 import 了 `EventSource`/`fetch`/`wx` → 立即收回适配层,边界破功即修。
- **状态规模增长**:若 Phase 2+ 多模式/云存档使单局状态显著膨胀,重评 Zustand 是否仍够(目前单局单人规模不配 Redux)。

## 实施步骤

1. ✅ 据本 ADR 落 `docs/adr/ADR-003-frontend-stack-and-taro-boundary.md`;ROADMAP §五 把 ADR-003 移出「首批待决策议题」、移入「已完成 ADR 索引」;root README ADR 列表追加一行。**落档单独 commit,与实现分开。**
2. ⏳ `web/src/api/` 适配层:`initGame()`(plain POST)+ `TurnStream` 接口及 H5/`EventSource` 实现,映射 ADR-006/007 wire。
3. ⏳ 状态层(Zustand store):消毒 client world + 流式增量(narrative 累加 / delta 落面板 / ending 出画面 / error 处置),只依赖 `api/` 接口。
4. ⏳ 回合循环 UI(散文区 + 决策圈 + 数值/规则面板)+ 整局流程(init→reveal→回合→结局/ERROR)。
5. ⏳ 验收纪律:逻辑/状态层对 `EventSource`/`fetch`/`wx` 的 import 计数 = 0(lint/自查);`api/` 层 mock fetch/SSE 单测、状态层合成事件序列单测。
6. ⏳ 手动 e2e(真 key,后端起着):浏览器从 init 玩到一个结局——Phase 1 里程碑本身。

## 实际效果(事后补充)

*Phase 1 前端落地、手动 e2e 通关时回填:`api/` 适配层接口是否真做到「逻辑层永不见 `EventSource`」(import 计数硬线是否守住);SSE 在手机浏览器/微信内置浏览器下逐字流是否稳。*

*Phase 4 Taro 迁移时回填:接口纪律边界是否真做到「只换适配层」;Zustand/CSS Modules 是否暴露 Taro 兼容坑;WS 预案落地是否如预期只动适配层。*

## 跟其他文档的交叉引用

- **要接的 wire(前序)**:[ADR-006](ADR-006-event-loop-streaming-wire-protocol.md)(回合 SSE 命名事件 `narrative→delta→ending`/`error`)、[ADR-007](ADR-007-world-gen-wire-protocol.md)(init plain POST + 消毒投影 + `openingNarrative` reveal 不流式)。
- **薄接缝哲学(镜像来源)**:[ADR-001](ADR-001-runtime-model-and-provider-abstraction.md)(provider 抽象)、[ADR-005](ADR-005-sse-web-stack-mvc-thin-seam.md)(`TokenStream` 解耦核心与传输)——`api/` 之于前端镜像它们之于后端。
- **约定真理之源**:CONTEXT §二(schema v0.2,`src/types/schema.ts` 据此)/ §三.9(三视图消毒,client world 走消毒投影)/ §三.3(面向玩家文案用中文)。
- **配套源文件**:`web/src/api/`(适配层)、`web/src/state/`(Zustand store)、`web/src/features/game/`(回合循环 UI)、`web/src/types/schema.ts`(schema v0.2 TS 类型)。
- **不在本 ADR 范围**:Taro 具体迁移(Phase 4 专门 ADR)、小程序 WS 端点实现、[ADR-004](#) 内容安全、CloudBase 部署/ICP 备案、具体 UI 视觉设计(走 `frontend-design` skill,实现期定)。
