package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * ADR-032 测试面 16(刀 2 半边):工具类与应用的组件扫描同包,但都不是组件 —— 默认 profile 的应用上下文里
 * 不出现任何一个(工具只在自己的 main 里起自己的上下文)。
 */
@SpringBootTest
class DefaultProfileHasNoMigrationToolTest {

	@Autowired
	ApplicationContext ctx;

	@Test
	void migrationToolClassesAreNotAssembledIntoTheApp() {
		for (Class<?> c : new Class<?>[] { StoreMigrationTool.ToolConfig.class, StoreMigrationTool.class,
				SessionImporter.class, SessionExporter.class, MigrationPathGate.class }) {
			assertThat(ctx.getBeanNamesForType(c)).as(c.getSimpleName()).isEmpty();
		}
	}
}
