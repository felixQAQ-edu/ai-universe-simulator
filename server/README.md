# server · AI Universe Simulator 后端

Phase 1 骨架(skeleton only · local-first)。后端形态见 [ADR-002](../docs/adr/ADR-002-backend-form-factor.md),
provider 抽象见 [ADR-001](../docs/adr/ADR-001-runtime-model-and-provider-abstraction.md),
SSE/流式 web 栈见 [ADR-005](../docs/adr/ADR-005-sse-web-stack-mvc-thin-seam.md)。

> 本次范围:Spring Boot 骨架 + provider 配置表抽象 + SSE 冒烟端点 + 审核网关 no-op 接缝;
> **已接真实 DeepSeek**(OpenAI 兼容流式实现,token 经现有 `TokenStream` 接缝流到 SSE)。
> **未实现规则怪谈业务 / event-loop 契约 / 状态机,未碰 CloudBase 部署 / ICP 备案**——均留后续任务。

## 技术栈
- Spring Boot 4.1.x · 编译目标 Java 21(LTS)· Maven · Spring MVC(`SseEmitter`,见 ADR-005)
  - 运行时:Boot 4 支持到 JDK 26,本机用默认 JDK 直接跑,无需固定 `JAVA_HOME`。

## 包结构
- `llm/` — 运行模型抽象(平台无关核心):`LlmClient` / `TokenStream`(最小流式 sink)/
  `LlmProperties`(provider 配置表,对应 bakeoff `providers.py`)/ `ThinkingAdapter`(思考开关单点适配,移植自 bakeoff)/
  `OpenAiStreamDecoder`(纯 SSE 解析)/ `OpenAiCompatLlmClient`(真实 DeepSeek,JDK `HttpClient`)/ `MockLlmClient`(离线回退)/
  `LlmClientConfig`(按 `active` 选实现)/ `LlmException`(统一降级)
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

## 接真实 DeepSeek(手动集成冒烟)
单测不打真实 API(确定性、零成本)。真实逐字流式靠这条手动冒烟验证:
```bash
export DEEPSEEK_API_KEY=<你的 key>          # 只进环境变量,绝不写进 yaml / 代码 / 提交
# 把 application.yml 的 aiuniverse.llm.active 改成 deepseek-v4-flash(或用 -D 覆盖):
./mvnw spring-boot:run -Dspring-boot.run.arguments=--aiuniverse.llm.active=deepseek-v4-flash

curl -N http://localhost:8080/api/dev/echo-stream \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"用一句话描述雨夜便利店的诡异氛围"}'
# 预期:DeepSeek 生成的文本逐字 data: 事件流式返回,证明真实 token 流过 TokenStream 接缝 → SSE
```
缺 key / 网络失败 / 非 200 / 流中断都收口成 `LlmException` 干净降级(不泄露 key / 原始异常给前端)。

## 换 provider
改 `src/main/resources/application.yml` 的 `aiuniverse.llm` 配置表即可(ADR-001);真实 API key 只进环境变量
(`api-key-env` 指向变量名),绝不写进 yaml / 代码 / 提交。各家「思考开关」非标参数在 `ThinkingAdapter` 单点翻译。
