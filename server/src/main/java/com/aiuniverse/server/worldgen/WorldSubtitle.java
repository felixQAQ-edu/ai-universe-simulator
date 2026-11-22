package com.aiuniverse.server.worldgen;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 每局小标题 {@code world.subtitle} 的服务端收口(ADR-044 最终决策 2)。与 {@link FixedWorldTitle} 同位:
 * world-gen <b>过校验之后</b>、交给播种层落盘<b>之前</b>,首过与修复两条成功路径都调。
 *
 * <ul>
 *   <li><b>未登记 {@code fixedWorldTitle} 的世界</b>(含任何融合局):删掉 {@code world.subtitle} 键 —— 小标题只属于登记了固定名的
 *       单体世界;模型自发写出这个键也不落盘、不下发(待审 9)。没有这个键时原样返回,输出逐字节不变,也不记日志;</li>
 *   <li><b>登记了的世界</b>(今天:《派单》《寻常》《七日开张》):去首尾空白;空串、非字符串、超过 {@link #MAX_CODE_POINTS} 个字
 *       → 删键(视同漏写,待审 4);漏写不算 world-gen 失败(待审 1)。每局一行 INFO:保留 / 删除 / 漏写,以及原文。</li>
 * </ul>
 * 字数按 Unicode code point 计(不按 UTF-16 长度)。不进 {@code validateWorld}、不改 state schema({@code schemaVersion} 仍 "0.4",待审 6)。
 * 只在新生成时调用;续局回载读盘上的值,不经过这里。
 */
final class WorldSubtitle {

	private static final Logger log = LoggerFactory.getLogger(WorldSubtitle.class);

	/** 程序上限(ADR-044 待审 4):指令里要求 4–8 字,超过 10 字视同漏写。 */
	static final int MAX_CODE_POINTS = 10;

	static final String FIELD = "subtitle";

	private WorldSubtitle() {
	}

	static ObjectNode apply(List<String> archetypes, ArchetypeRegistry registry, ObjectNode world) {
		if (!(world.get("world") instanceof ObjectNode w)) {
			return world;
		}
		JsonNode raw = w.get(FIELD);
		if (!registered(archetypes, registry)) {
			if (raw != null) {
				w.remove(FIELD);
				log.info("[world-gen] archetypes={} 未登记固定世界名,删除 world.subtitle,原文={}", archetypes, raw);
			}
			return world;
		}
		String id = archetypes.get(0);
		if (raw == null || raw.isNull()) {
			w.remove(FIELD);
			log.info("[world-gen] archetype={} world.subtitle 漏写", id);
			return world;
		}
		if (!raw.isString()) {
			w.remove(FIELD);
			log.info("[world-gen] archetype={} world.subtitle 删除(不是字符串),原文={}", id, raw);
			return world;
		}
		String original = raw.asString();
		String s = original.strip();
		int count = s.codePointCount(0, s.length());
		if (count == 0) {
			w.remove(FIELD);
			log.info("[world-gen] archetype={} world.subtitle 删除(空串),原文=「{}」", id, original);
			return world;
		}
		if (count > MAX_CODE_POINTS) {
			w.remove(FIELD);
			log.info("[world-gen] archetype={} world.subtitle 删除(超过 {} 字,共 {} 字),原文=「{}」", id, MAX_CODE_POINTS, count,
					original);
			return world;
		}
		w.put(FIELD, s);
		log.info("[world-gen] archetype={} world.subtitle 保留「{}」({} 字),原文=「{}」", id, s, count, original);
		return world;
	}

	/** 只有登记了 {@code fixedWorldTitle} 的单体世界才有小标题(与 {@link FixedWorldTitle} 同一判据)。 */
	private static boolean registered(List<String> archetypes, ArchetypeRegistry registry) {
		return archetypes.size() == 1 && registry.isActive(archetypes.get(0))
				&& registry.meta(archetypes.get(0)).fixedWorldTitle() != null;
	}
}
