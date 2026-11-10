package com.aiuniverse.server.archetype;

import java.util.Map;

/**
 * 测试用 registry:真实登记不变,另注入一个<b>假的「已知但未开放」占位</b>(ADR-036 裁定 4(a))。
 *
 * <p>《派单》激活 {@code cyberpunk} 后,{@code KNOWN} 里已没有真实成员走「已知但未激活 → 未开放」这条分支;
 * 分支与空表保留,守护测试改用本类继续守它,守的行为不变。
 */
public class FakePlaceholderRegistry extends ArchetypeRegistry {

	/** 假占位 id(不在 {@code KNOWN} 里,也绝不会被登记为已激活)。 */
	public static final String ID = "test_placeholder";
	/** 假占位的玩家可见中文名。 */
	public static final String DISPLAY_NAME = "测试占位";

	@Override
	protected Map<String, String> inactiveDisplayNames() {
		return Map.of(ID, DISPLAY_NAME);
	}
}
