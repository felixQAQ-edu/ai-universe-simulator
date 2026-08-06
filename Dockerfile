# ADR-015 Slice 3 · 同源单容器(A2+B2):web/dist 构建后拷入 Spring static/,
# 一个镜像一个域名零 CORS。三阶段:web 构建 → server 构建(dist 併入 static)→ JRE 运行层。
# 尽量贴标准 Boot 容器实践,不为省体积引奇技淫巧。

# ── 1. web 构建(Vite)────────────────────────────────────────────────
FROM node:22-alpine AS web
WORKDIR /build
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ── 2. server 构建(Maven;dist 併入 classpath static → jar 同源伺服)──
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY server/pom.xml ./
# 预热依赖层(pom 不变时缓存命中,重建只编译源码)
RUN mvn -q -B dependency:go-offline
COPY server/src ./src
COPY --from=web /build/dist ./src/main/resources/static/
# 部署身份:.dockerignore 排除 .git(刻意——构建上下文不该带版本库),故构建层无法自己
# 读出 commit,只能由外部传入:`fly deploy --build-arg GIT_SHA=...`(runbook §3.1.5 给了整条命令)。
# 没传 = unknown,而 /actuator/info 显示 unknown 本身就是「这次部署没走那条命令」的可见信号。
ARG GIT_SHA=unknown
# 测试仍由 CI 跑(.github/workflows/ci.yml);构建镜像不重跑测试是对的,别把 -DskipTests 去掉。
RUN mvn -q -B package -DskipTests -Dgit.sha="${GIT_SHA}"

# ── 3. 运行层(JRE 21;非 root;落盘卷挂 /data)───────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 appuser && mkdir -p /data && chown appuser:appuser /data
COPY --from=build /build/target/server-*.jar app.jar
USER appuser
# 落盘目录默认指向持久卷挂载点(ADR-015 C3;平台侧把卷挂到 /data 即可)。
ENV AIUNIVERSE_SESSION_STORE_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
