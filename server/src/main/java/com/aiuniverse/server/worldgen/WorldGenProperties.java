package com.aiuniverse.server.worldgen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * world-gen 配置(ADR-030 决策 4 / 已决 2)。
 *
 * <p>{@code segmentDeadlineMs}:流式段总时长上界,主调用与修复调用各自一段。默认 180 000 ms,
 * <b>无实测依据</b>(先宽后收,收紧以 {@code [world-gen] durMs} 日志读数为依据)。可配置是为了冒烟能
 * 压低到几秒确定性触发(同 ADR-022 容量 N 的理由)。
 */
@ConfigurationProperties("aiuniverse.world-gen")
public record WorldGenProperties(@DefaultValue("180000") long segmentDeadlineMs) {

	/** 未注入配置时的缺省(既有构造调用点用)。 */
	public static final long DEFAULT_SEGMENT_DEADLINE_MS = 180_000L;
}
