package com.aiuniverse.server.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@link TraceSink} 装配(ADR-031 刀 1):一律 {@link TraceSink#NOOP},默认行为逐字节不变。文件落点在刀 3。 */
@Configuration
public class TraceSinkConfig {

	@Bean
	TraceSink traceSink() {
		return TraceSink.NOOP;
	}
}
