# server · AI Universe Simulator 后端

后端形态见 [ADR-002](../docs/adr/ADR-002-backend-form-factor.md)(**已由
[ADR-015](../docs/adr/ADR-015-overseas-deployment-form-factor.md) 修订**:部署改 Fly.io 同源单容器,
不是 CloudBase),provider 抽象见 [ADR-001](../docs/adr/ADR-001-runtime-model-and-provider-abstraction.md),
SSE/流式 web 栈见 [ADR-005](../docs/adr/ADR-005-sse-web-stack-mvc-thin-seam.md)。

> **现状**:整局闭环(world-gen → 回合 → 结局)已上线公网 <https://wanjie-ai.fly.dev>;
> event-loop 契约与状态机、多世界 registry、续局落盘、成本闸门、回合并发准入均已落地。
> 逐刀进展与取证读数以 [docs/ROADMAP.md](../docs/ROADMAP.md) 为准,**本文件不第二次叙述进度**。
> **ICP 备案未做**——路线 B 下整体冻结(ADR-015)。

## 技术栈
- Spring Boot 4.1.x · 编译目标 Java 21(LTS)· Maven · Spring MVC(`SseEmitter`,见 ADR-005)
  - 运行时:Boot 4 支持到 JDK 26,本机用默认 JDK 直接跑,无需固定 `JAVA_HOME`。

## 包结构
- `llm/` — 运行模型抽象(平台无关核心):`LlmClient` / `TokenStream`(最小流式 sink)/
  `LlmProperties`(provider 配置表,对应 bakeoff `providers.py`)/ `ThinkingAdapter`(思考开关单点适配,移植自 bakeoff)/
  `OpenAiStreamDecoder`(纯 SSE 解析)/ `OpenAiCompatLlmClient`(真实 DeepSeek,JDK `HttpClient`)/ `MockLlmClient`(离线回退)/
  `LlmClientConfig`(按 `active` 选实现)/ `LlmException`(统一降级)
- `moderation/` — 内容审核网关接缝(ADR-004 未定),`NoopModerationGateway` 占位放行
- `web/` — 薄传输适配层,唯一碰 `SseEmitter` 的地方(`GameController`);换 WebFlux 只动这层
- `platform/` — CloudBase / 微信薄适配层占位(ADR-002),骨架阶段空置(路线 B 下仍空置)
- `engine/` — 数值结算 / 校验 / 消毒投影 / 泄露遥测(`Engine` 对数值语义无知,ADR-008)
- `eventloop/` — 回合控制面:哨兵切分 / 叙事回灌 / 状态机与三道守卫 / SSE 编排(ADR-006)
- `worldgen/` — world-gen 胖调用 + 校验 + 一次修复 + 播种编排(ADR-007)
- `archetype/` — per-archetype 元数据登记处(轴 / bands / 融合组合 / 一生制族层,ADR-008 起逐批扩充)
- `persistence/` — 最小续局落盘(每 saveId 一个 JSON,ADR-015)
- `quota/` — 成本闸门(ADR-016)

## 本地运行
```bash
./mvnw spring-boot:run        # 起服务(默认端口 8080)
```

## SSE 冒烟(已退役)
> 骨架期的 `POST /api/dev/echo-stream`(任意 prompt 直连 `LlmClient` 逐字流)**已删除**。
> 它绕过 ADR-016 成本闸门(闸门只装在 `init`/`turn` 两个入口),线上可达即等于预算被架空。
> SSE 通路现由真实回合端点 `POST /api/game/{saveId}/turn` 承载,逐字流式冒烟见下方整局闭环。

## 接真实 DeepSeek(手动集成冒烟)
单测不打真实 API(确定性、零成本)。真实逐字流式靠整局闭环冒烟(下一节)验证:
```bash
export DEEPSEEK_API_KEY=<你的 key>          # 只进环境变量,绝不写进 yaml / 代码 / 提交
# 把 application.yml 的 aiuniverse.llm.active 改成 deepseek-v4-flash(或用 -D 覆盖):
./mvnw spring-boot:run -Dspring-boot.run.arguments=--aiuniverse.llm.active=deepseek-v4-flash
```
缺 key / 网络失败 / 非 200 / 流中断都收口成 `LlmException` 干净降级(不泄露 key / 原始异常给前端)。

## 整局闭环冒烟(world-gen → 回合 → 结局,手动 · 真 key)
单模式规则怪谈整局闭环:`POST /api/game/init`(world-gen 胖调用,plain POST 无 SSE)起世界 →
`POST /api/game/{saveId}/turn`(SSE)推进回合。设计稿见
[world-gen + INITIALIZING](../docs/phase1-world-gen-and-initialization.md)、[event-loop 契约](../docs/phase1-event-loop-contract-and-state-machine.md)。
```bash
export DEEPSEEK_API_KEY=<你的 key>
./mvnw spring-boot:run -Dspring-boot.run.arguments=--aiuniverse.llm.active=deepseek-v4-flash

# 1) 起一局世界(阻塞数秒返 JSON:saveId + 消毒 world + openingNarrative + 初始决策圈)
curl -s http://localhost:8080/api/game/init \
  -H 'Content-Type: application/json' -d '{"archetype":"rules_creepy"}'
# 预期:返回体无 hiddenLogic / isTrue(消毒投影);world-gen 救不回 → 502 {error:{code,message}}

# 2) 用上一步的 saveId 推进一回合(逐字叙事 SSE + 流末 delta;命中结局发 ending)
curl -N http://localhost:8080/api/game/<saveId>/turn \
  -H 'Content-Type: application/json' -d '{"turn":0,"actionId":"A"}'
# 预期:event: narrative 逐字 → event: delta(消毒数值/规则)→ 触发结局/触底则 event: ending
```
> 注:`POST /api/dev/game/{id}/init` dev 桩已退役——会话此后只经真实 `init` 播种。
> 递延的 ending / §5 触底兜底端到端在此首次真实通关里一并验。

## 换 provider
改 `src/main/resources/application.yml` 的 `aiuniverse.llm` 配置表即可(ADR-001);真实 API key 只进环境变量
(`api-key-env` 指向变量名),绝不写进 yaml / 代码 / 提交。各家「思考开关」非标参数在 `ThinkingAdapter` 单点翻译。
