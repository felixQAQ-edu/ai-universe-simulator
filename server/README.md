# server · AI Universe Simulator 后端

Phase 1 骨架(skeleton only · local-first)。后端形态见 [ADR-002](../docs/adr/ADR-002-backend-form-factor.md),
provider 抽象见 [ADR-001](../docs/adr/ADR-001-runtime-model-and-provider-abstraction.md),
SSE/流式 web 栈见 [ADR-005](../docs/adr/ADR-005-sse-web-stack-mvc-thin-seam.md)。

> 本次范围:Spring Boot 骨架 + provider 配置表抽象(mock 实现)+ 一个 SSE 冒烟端点 + 审核网关 no-op 接缝。
> **未接真实 DeepSeek、未实现规则怪谈业务、未碰 CloudBase 部署 / ICP 备案**——均留后续任务。

## 技术栈
- Spring Boot 4.1.x · 编译目标 Java 21(LTS)· Maven · Spring MVC(`SseEmitter`,见 ADR-005)
  - 运行时:Boot 4 支持到 JDK 26,本机用默认 JDK 直接跑,无需固定 `JAVA_HOME`。

## 包结构
- `llm/` — 运行模型抽象(平台无关核心):`LlmClient` / `TokenStream`(最小流式 sink)/
  `LlmProperties`(provider 配置表,对应 bakeoff `providers.py`)/ `ThinkingAdapter`(占位)/ `MockLlmClient`
- `moderation/` — 内容审核网关接缝(ADR-004 未定),`NoopModerationGateway` 占位放行
- `web/` — 薄传输适配层,唯一碰 `SseEmitter` 的地方;换 WebFlux 只动这层
- `platform/` — CloudBase / 微信薄适配层占位(ADR-002),骨架阶段空置

## 本地运行
```bash
./mvnw spring-boot:run        # 起服务(默认端口 8080)
```

## SSE 冒烟
```bash
curl -N http://localhost:8080/api/dev/echo-stream \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"雨夜便利店"}'
# 预期:mock 文本逐字流式返回(每字一个 data: 事件),证明 SSE 通路成立
```

## 换 provider(后续)
改 `src/main/resources/application.yml` 的 `aiuniverse.llm` 配置表即可;真实 API key 只进环境变量
(`api-key-env` 指向变量名),绝不写进 yaml / 代码 / 提交。
