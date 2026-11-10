package com.aiuniverse.server.worldgen;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

import tools.jackson.databind.node.ObjectNode;

/**
 * 固定世界名的服务端覆写(ADR-036 决策 1 / Slice A2)。world-gen <b>过校验之后</b>、交给播种层落盘<b>之前</b>,
 * 用 {@code ArchetypeMeta.fixedWorldTitle} 覆写 {@code world.title}。
 *
 * <p><b>边界</b>(与 {@link EndingOrder} 同位,两个成功路径都调):
 * <ul>
 *   <li>只对<b>单体</b>(长度 1)生效;融合局世界名照旧由模型起,不读 host 的字段;</li>
 *   <li>字段为 {@code null}(既有六个世界全是)→ 原样返回,输出逐字节不变;</li>
 *   <li>只在新生成时调用;续局回载({@code Engine.restore})读盘上已覆写过的值,不经过这里;</li>
 *   <li>不进 {@code validateWorld}、不改 state schema({@code schemaVersion} 仍 "0.4")。</li>
 * </ul>
 * 只管 {@code world.title} 一个字段:{@code background} / 开场叙事里模型自己写的称呼不受它管(软层靠提示词,ADR-036 已知代价 9)。
 */
final class FixedWorldTitle {

	private static final Logger log = LoggerFactory.getLogger(FixedWorldTitle.class);

	private FixedWorldTitle() {
	}

	static ObjectNode apply(List<String> archetypes, ArchetypeRegistry registry, ObjectNode world) {
		if (archetypes.size() != 1 || !registry.isActive(archetypes.get(0))) {
			return world;
		}
		String fixed = registry.meta(archetypes.get(0)).fixedWorldTitle();
		if (fixed == null || !(world.get("world") instanceof ObjectNode w)) {
			return world;
		}
		String original = w.path("title").asString("");
		if (!fixed.equals(original)) {
			// 软层(提示词行)失守的读数:冒烟时据它看模型自起的名有多常偏离。只进日志。
			log.info("[world-gen] archetype={} world.title 覆写:「{}」→「{}」", archetypes.get(0), original, fixed);
		}
		w.put("title", fixed);
		return world;
	}
}
