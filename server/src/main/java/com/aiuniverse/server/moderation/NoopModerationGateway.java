package com.aiuniverse.server.moderation;

import org.springframework.stereotype.Component;

/**
 * 占位实现:原样放行,不接任何审核 API(ADR-004 未定方案前的 no-op)。
 * 存在的意义是让审核接缝在 Spring 容器里被真实装配、被调用点依赖,接 ADR-004 时只替换本 bean。
 */
@Component
public class NoopModerationGateway implements ModerationGateway {

	@Override
	public String review(String text) {
		return text;
	}
}
