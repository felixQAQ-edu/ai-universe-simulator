# ADR-005 · SSE/流式 web 栈选 Spring MVC(SseEmitter)+ 可换 WebFlux 的薄接缝

- **日期**:2026-06-18
- **状态**:已采纳
- **决策者**:Felix

## 背景

Phase 1(单模式 H5 闭环)后端骨架动工。后端的承重职责之一是**向前端流式推送 LLM 生成的 token**(SSE)——这是 [ADR-002](ADR-002-backend-form-factor.md) 已知代价 5 标注的「需实测验证」假设(云托管原生支持 SSE,Spring Boot 流式输出可保留)。

在写第一行流式代码前,要先敲定 **web 编程模型**:Spring MVC(servlet,`SseEmitter`)还是 WebFlux(响应式,`Flux<ServerSentEvent>`)。这是**栈级选择**——它决定整条 web 依赖栈、DeepSeek 流式接入方式、测试手感、以及 CloudBase 云托管上的长连接形态,一旦底层选定后续迁移成本不低,值得单独立档。

约束条件:

1. **Phase 1 首要目标是尽快出闭环**(ROADMAP:第一个能玩的版本约第 2 个月底),骨架阶段不宜为未来才用得上的能力付复杂度。
2. **复用既有肌肉记忆**:bake-off 的 `bakeoff/client.py` 已用**命令式 + callback-per-chunk** 跑通流式,沿用同一心智模型最稳、最快(承接 [ADR-001](ADR-001-runtime-model-and-provider-abstraction.md) 的抽象哲学)。
3. **验证期并发低**:Phase 0/1 是脚本与少量真实用户,用不上响应式的背压/高并发强项。
4. **ADR-002 已确立「业务核心平台无关 + 薄适配层」哲学**:本决策把同一原则套到「业务核心 ↔ web 传输」的解耦上。

## 候选方案

### 方案 A:WebFlux(响应式)

`spring-boot-starter-webflux` + 控制器返回 `Flux<ServerSentEvent>`,全链路响应式。

**优点**:
- 更贴流式本质,背压 / 取消天然支持。
- 响应式是后端架构的加分项,有可迁移的学习/求职价值。

**缺点**:
- 响应式心智更重,骨架阶段就要全栈切换范式,直接撞约束 1(尽快出闭环)。
- 与已验证的命令式 `bakeoff/client.py` 心智不一致,复用打折(撞约束 2)。
- 验证期并发低,响应式强项吃不到(撞约束 3)。**排除**:在骨架阶段付响应式的复杂度溢价、却用不上其收益,性价比不对;响应式更适合作为独立学习项目深啃,而非在产品主线边出闭环边学。

### 方案 B:纯 MVC(SseEmitter)

`spring-boot-starter-web` + `SseEmitter`,命令式;业务逻辑直接在控制器里操作 emitter。

**优点**:
- 最简单直接,复用命令式 bake-off client 心智。

**缺点**:
- 业务/LLM 代理逻辑与 web 传输(`SseEmitter`)耦合在一起,日后想换 WebFlux 要动核心。**排除**(相对方案 C):只差一层最小接缝就能解耦,没理由把传输细节焊死进核心。

### 方案 C:MVC + 薄接缝(本 ADR 采纳)

用 Spring MVC + `SseEmitter`,但**把核心逻辑与 web 传输解耦**:核心只把 token 吐给一个最小的流式 sink 接口(`TokenStream`),web 层用一个薄适配把它桥到 `SseEmitter`。

**优点**:
- 命令式、简单、复用已验证的 bake-off client 心智(命中约束 1/2)。
- 核心逻辑(`LlmClient`)对传输无感知,**可独立测试**、可独立演进。
- **保留换 WebFlux 的干净路径**:日后只改 web 层适配(把 token 推进 `Flux` 而非 `SseEmitter`),`llm/` / `moderation/` 一行不动(命中约束 4)。

**缺点**:
- 背压 / 取消需在 MVC 下手动处理(`SseEmitter` 超时 + `completeWithError`)。接受——验证期并发低,暂不需要精细背压。
- 多一层 `TokenStream` 接缝的抽象。接受,但**须把接缝控到最小**(单方法函数式接口,不搭抽象框架),避免过度设计。

## 最终决策

**方案 C — MVC(SseEmitter)+ 薄接缝**。

### 1. 最小流式 sink 接缝

`TokenStream` 是承重接缝,刻意保持单方法、函数式(可用 lambda),不在其上堆完成/异常回调(完成 = 方法正常返回,异常 = 抛出):

```java
@FunctionalInterface
public interface TokenStream {
    void onToken(String token);
}
```

`LlmClient` 是平台无关核心,只依赖 `TokenStream`,完全不知道下游传输是 SSE:

```java
public interface LlmClient {
    void streamChat(ChatRequest request, TokenStream sink);
}
```

### 2. 薄 web 适配(唯一碰 SseEmitter 的地方)

`web/StreamController` 是唯一依赖 `SseEmitter` 的类:开 emitter → 在独立线程跑 `streamChat`,lambda 即 `TokenStream`,把每个 token 桥到 SSE → 正常完成 `complete()`、异常 `completeWithError()`。换 WebFlux 时,只把这层改写成往 `Flux` 推 token。

### 关键理由

1. **呼应约束 1/2**:命令式 + 复用 bake-off client 心智,是骨架阶段出闭环最快、最稳的路径。
2. **复用既有模式**:`TokenStream`(callback-per-chunk)与 `bakeoff/client.py` 的流式回调同形,不重学一套响应式范式。
3. **避开过度工程陷阱**:不为验证期用不上的高并发/背压预付 WebFlux 的复杂度;同时只用一层最小接缝解耦传输,避开「业务焊死进 web 层」的另一个陷阱(方案 B)。
4. **保留演进路径**:把响应式留作日后(有专门时间时)的独立深啃项,届时换 WebFlux 只动 web 适配层,核心不动——与 ADR-002 的「薄适配层缓解锁定」同构。

## 已知代价

1. **背压 / 取消需手动处理**:MVC 下靠 `SseEmitter` 超时与 `completeWithError` 兜底,没有响应式的天然背压。缓解方式:验证期并发低、暂不需要;真出现高并发长连接压力时按「重新审视」触发条件重评。
2. **响应式学习延后**:本决策主动不在产品主线练 WebFlux,这部分加分项暂不兑现。接受——响应式更适合作为独立学习项目深啃,不该让产品闭环为学习目标让路。
3. **多一层 TokenStream 抽象**:若控制不好会滑向过度设计。缓解方式:把接缝钉死在「单方法函数式接口 + 一个薄 web 适配」,骨架已据此落地,后续不得在此处加框架。
4. **方案 B(纯 MVC)的代价**:被排除是因为它把传输焊进核心;本 ADR 用最小接缝换来解耦,接受这一层抽象成本。

## 重新审视的触发条件

- **真实高并发需求出现**:线上长连接并发显著上升、`SseEmitter` 的线程模型成为瓶颈 → 重评是否切 WebFlux。
- **有专门时间深入响应式**:把 WebFlux 作为独立学习目标推进时,可借本接缝低成本切换并对照。
- **云托管长连接出问题**:CloudBase 云托管上 SSE 长连接稳定性 / TTFT 实测不达预期(对照 ADR-001 本地 TTFT~1s) → 触发 ADR-002 的同名重审条件,连带重评 web 栈。

## 实施步骤

1. ✅ Spring Boot 3.5.x(Java 21 / Maven)骨架落 `server/`,只上 `spring-boot-starter-web`(MVC),**不引 webflux**。
2. ✅ 落最小接缝:`llm/TokenStream`(sink)+ `llm/LlmClient`(核心,传输无感知)+ `web/StreamController`(薄 SSE 适配)。
3. ✅ mock 实现(`MockLlmClient` 逐字吐字)+ 一个 dev 冒烟端点 `POST /api/dev/echo-stream`。
4. ✅ 本地冒烟验证:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 21)
   ./mvnw spring-boot:run
   curl -N http://localhost:8080/api/dev/echo-stream \
     -H 'Content-Type: application/json' -d '{"prompt":"雨夜便利店"}'
   ```
   实测:mock 文本**逐字流式**返回(每字一个 `data:` 事件),空 prompt → HTTP 400,`mvn test` 上下文加载绿 → **SSE 通路成立**(印证 ADR-002 承重假设的本地侧)。
5. ⏳ **下个任务**:接真实 DeepSeek 时,在 `LlmClient` 下新增 OpenAI 兼容实现,走完整红绿循环;mock 保留作 `--mock`/测试用途。
6. ⏳ **Phase 1 后段**:CloudBase 云托管上实测 SSE 长连接的 TTFT/稳定性,回填 ADR-002 已知代价 5。

## 实际效果(事后补充)

*接真实 DeepSeek 时回填:命令式 `SseEmitter` + `TokenStream` 接缝承接真实流式 token 是否顺滑,背压缺失在真实回合延迟(~5.8s)下是否构成问题。*

*CloudBase 云托管部署时回填:云托管上 Spring MVC SSE 长连接的真实 TTFT / 稳定性是否与 ADR-001 本地实测一致;若明显劣化,触发本 ADR 与 ADR-002 的 web 栈重审。*

## 跟其他文档的交叉引用

- **抽象哲学(前序)**:[ADR-001](ADR-001-runtime-model-and-provider-abstraction.md)(provider 解耦 / callback-per-chunk 流式,本 ADR 的 `TokenStream` 与之同形)
- **后端形态(前序)**:[ADR-002](ADR-002-backend-form-factor.md)(云托管 SSE 承重假设由本 ADR 在本地侧先行验证;「薄适配层」哲学本 ADR 套到 web 传输解耦)
- **后续待写**:ADR-004(内容安全审核网关——`moderation/` 接缝的审核点放在 prompt 入参 / 完整文本 / 流式缓冲哪层由其定)
- **配套源文件**:`server/src/main/java/com/aiuniverse/server/llm/TokenStream.java`、`.../llm/LlmClient.java`、`.../web/StreamController.java`
