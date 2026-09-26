package com.aiuniverse.server.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 标注「需要 Docker(Testcontainers 真 PG)」的测试类(ADR-025 测试面)。
 *
 * <p>判定见 {@link DockerGate}:本机无 Docker → <b>跳过,但响亮</b>(每类一条 WARN + 构建末尾
 * {@link DbTestSkipSummary} 汇总条数与原因);<b>CI(env {@code CI=true})无 Docker → 不跳过</b>,
 * 放行让容器启动当场失败 —— 静默跳过的 DB 测试是「绿有两种解释」的最坏形态(ADR-018 §4.14)。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(DockerGate.class)
public @interface RequiresDocker {
}
