package com.aiuniverse.server.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * 统一 JSON Schema 校验(CONTEXT §二 v0.2)—— 忠实手写移植 bakeoff {@code schema.py} 的
 * {@code WORLD_SCHEMA} / {@code TURN_SCHEMA},返回 {@code [path: message]} 错误清单(空 = 合法)。
 *
 * <p><b>为何手写而非引 networknt</b>:Spring Boot 4.1 走 Jackson 3({@code tools.jackson}),而
 * networknt json-schema-validator 基于 Jackson 2({@code com.fasterxml.jackson.databind.JsonNode}),
 * 二者 {@code JsonNode} 类型不通用——引入会造成「双 Jackson 世界」并违背本批「回灌走 ObjectNode 层」
 * 的单一 Jackson 决策。两份 schema 是封闭小集合(required/type/enum/range/minItems/minLength),
 * 手写 ~120 行即忠实覆盖,零新依赖。
 *
 * <p>§10 放宽:{@code validate_turn} 在【结局回合】({@code ending.reached == true})允许
 * {@code availableActions} 为空;否则维持 {@code minItems 2}(向后兼容,不破 golden)。
 */
public final class GameSchemas {

	private static final Set<String> MODES = Set.of("single", "hybrid");
	private static final Set<String> DANGER = Set.of("low", "medium", "high", "extreme");

	private GameSchemas() {
	}

	// ── world-gen 产出 ────────────────────────────────────────────────
	public static List<String> validateWorld(JsonNode w) {
		V v = new V();
		if (!v.requireObject(w, "<root>")) {
			return v.errors;
		}
		// schemaVersion const "0.2"
		if (!"0.2".equals(w.path("schemaVersion").asString(null))) {
			v.add("schemaVersion", "必须为常量 \"0.2\"");
		}
		v.requireEnum(w, "mode", MODES);
		v.requireStringArray(w, "archetypes", 1);

		if (v.requireObject(w.get("world"), "world")) {
			JsonNode world = w.get("world");
			v.requireNonEmptyString(world, "title", "world/title");
			v.requireNonEmptyString(world, "background", "world/background");
			v.requireEnumAt(world, "dangerLevel", "world/dangerLevel", DANGER);
			v.requireNonEmptyString(world, "tone", "world/tone");
		}

		if (v.requireObject(w.get("character"), "character")) {
			JsonNode attrs = w.get("character").get("attributes");
			// ADR-008 决策 1:attributes 是开放字典——每个已给数值轴硬校验范围 0–100,但 key 集合不硬校验
			//（不 require hp/san;末日 {hp,hunger} 同走此路)。模型该给哪些轴由 per-archetype 元数据 + 提示词约束。
			if (v.requireObject(attrs, "character/attributes")) {
				v.requireEachNumberInRange(attrs, "character/attributes", 0, 100);
			}
		}

		if (v.requireArrayMin(w.get("rules"), "rules", 1)) {
			int i = 0;
			for (JsonNode r : w.get("rules")) {
				String p = "rules/" + i++;
				v.requireInteger(r, "id", p + "/id");
				v.requireNonEmptyString(r, "content", p + "/content");
				v.requireBoolean(r, "isTrue", p + "/isTrue");
				v.requireString(r, "hiddenLogic", p + "/hiddenLogic");
				v.requireBoolean(r, "discovered", p + "/discovered");
			}
		}

		if (v.requireArrayMin(w.get("endings"), "endings", 1)) {
			int i = 0;
			for (JsonNode e : w.get("endings")) {
				String p = "endings/" + i++;
				v.requireString(e, "id", p + "/id");
				v.requireNonEmptyString(e, "title", p + "/title");
				v.requireNonEmptyString(e, "condition", p + "/condition");
				v.requireBoolean(e, "reached", p + "/reached");
				v.optionalString(e, "description", p + "/description");
			}
		}
		return v.errors;
	}

	// ── event-loop 单回合产出 ─────────────────────────────────────────
	public static List<String> validateTurn(JsonNode t) {
		V v = new V();
		if (!v.requireObject(t, "<root>")) {
			return v.errors;
		}
		v.requireNonEmptyString(t, "narrative");

		if (v.requireObject(t.get("stateUpdate"), "stateUpdate")) {
			JsonNode upd = t.get("stateUpdate");
			v.optionalString(upd, "timeline", "stateUpdate/timeline");
			// ADR-008 决策 1:每个已给数值轴硬校验 0–100,key 集合不硬校验({hp,san} / {hp,hunger} 同路);timeline 非数值轴,跳过。
			v.requireEachNumberInRange(upd, "stateUpdate", 0, 100, "timeline");
		}

		v.optionalIntegerArray(t, "triggeredRuleIds");
		v.optionalIntegerArray(t, "discoveredRuleIds");

		// §10:结局回合允许 availableActions 为空,否则 minItems 2。maxItems 4 恒定。
		JsonNode ending = t.get("ending");
		boolean endingReached = ending != null && ending.isObject()
				&& ending.path("reached").asBoolean(false);
		int minActions = endingReached ? 0 : 2;
		JsonNode actions = t.get("availableActions");
		if (v.requireArrayRange(actions, "availableActions", minActions, 4)) {
			int i = 0;
			for (JsonNode a : actions) {
				String p = "availableActions/" + i++;
				v.requireString(a, "id", p + "/id");
				v.requireNonEmptyString(a, "text", p + "/text");
				v.optionalString(a, "hint", p + "/hint");
			}
		}

		// ending: object | null;字段可选(reached bool / id string)。
		if (ending != null && !ending.isNull()) {
			if (!ending.isObject()) {
				v.add("ending", "应为对象或 null");
			} else {
				if (ending.has("reached") && !ending.get("reached").isBoolean()) {
					v.add("ending/reached", "应为布尔");
				}
				if (ending.has("id") && !ending.get("id").isString()) {
					v.add("ending/id", "应为字符串");
				}
			}
		}
		return v.errors;
	}

	// ── 小型校验累加器 ────────────────────────────────────────────────
	private static final class V {
		final List<String> errors = new ArrayList<>();

		void add(String path, String msg) {
			errors.add(path + ": " + msg);
		}

		boolean requireObject(JsonNode n, String path) {
			if (n == null || !n.isObject()) {
				add(path, "缺失或非对象");
				return false;
			}
			return true;
		}

		boolean requireArrayMin(JsonNode n, String path, int min) {
			if (n == null || !n.isArray()) {
				add(path, "缺失或非数组");
				return false;
			}
			if (n.size() < min) {
				add(path, "至少需要 " + min + " 项");
				return false;
			}
			return true;
		}

		boolean requireArrayRange(JsonNode n, String path, int min, int max) {
			if (n == null || !n.isArray()) {
				add(path, "缺失或非数组");
				return false;
			}
			boolean ok = true;
			if (n.size() < min) {
				add(path, "至少需要 " + min + " 项");
				ok = false;
			}
			if (n.size() > max) {
				add(path, "至多 " + max + " 项");
				ok = false;
			}
			return ok;
		}

		void requireStringArray(JsonNode parent, String field, int min) {
			JsonNode n = parent == null ? null : parent.get(field);
			if (!requireArrayMin(n, field, min)) {
				return;
			}
			int i = 0;
			for (JsonNode item : n) {
				if (!item.isString()) {
					add(field + "/" + i, "应为字符串");
				}
				i++;
			}
		}

		void optionalIntegerArray(JsonNode parent, String field) {
			JsonNode n = parent == null ? null : parent.get(field);
			if (n == null || n.isNull()) {
				return;
			}
			if (!n.isArray()) {
				add(field, "应为数组");
				return;
			}
			int i = 0;
			for (JsonNode item : n) {
				if (!item.isIntegralNumber()) {
					add(field + "/" + i, "应为整数");
				}
				i++;
			}
		}

		void requireString(JsonNode parent, String field, String path) {
			JsonNode n = parent == null ? null : parent.get(field);
			if (n == null || !n.isString()) {
				add(path, "缺失或非字符串");
			}
		}

		void requireNonEmptyString(JsonNode parent, String field) {
			requireNonEmptyString(parent, field, field);
		}

		void requireNonEmptyString(JsonNode parent, String field, String path) {
			JsonNode n = parent == null ? null : parent.get(field);
			if (n == null || !n.isString()) {
				add(path, "缺失或非字符串");
			} else if (n.asString().isEmpty()) {
				add(path, "不能为空串");
			}
		}

		void optionalString(JsonNode parent, String field, String path) {
			JsonNode n = parent == null ? null : parent.get(field);
			if (n != null && !n.isNull() && !n.isString()) {
				add(path, "应为字符串");
			}
		}

		void requireBoolean(JsonNode parent, String field, String path) {
			JsonNode n = parent == null ? null : parent.get(field);
			if (n == null || !n.isBoolean()) {
				add(path, "缺失或非布尔");
			}
		}

		void requireInteger(JsonNode parent, String field, String path) {
			JsonNode n = parent == null ? null : parent.get(field);
			if (n == null || !n.isIntegralNumber()) {
				add(path, "缺失或非整数");
			}
		}

		/**
		 * 遍历 {@code attrs} 的每个属性,对<b>数值轴</b>(值为数字的键)硬校验 ∈ [min,max]
		 * (path = {@code basePath/<key>});{@code skip} 列出的 key 与<b>非数值值</b>一律忽略
		 * (不当数值轴、不报错)。ADR-008 决策 1:key 集合不硬校验,只校验已给轴范围(对 key 语义无知)。
		 *
		 * <p>忽略非数值值是<b>有意的 parity 行为</b>:真实模型产出常把 {@code traits}/{@code inventory}
		 * (数组)塞进 {@code character.attributes},Python {@code schema.py} 仅按名校验 hp/san、容忍其余 →
		 * 此处「只 range-check 数值键」与之同口径(world-gen golden accept-parity 8 条依赖它)。
		 */
		void requireEachNumberInRange(JsonNode attrs, String basePath, double min, double max, String... skip) {
			Set<String> skipSet = Set.of(skip);
			attrs.properties().forEach(e -> {
				if (skipSet.contains(e.getKey())) {
					return;
				}
				JsonNode val = e.getValue();
				if (val == null || !val.isNumber()) {
					return; // 非数值键(traits/inventory 数组、timeline 等)不当数值轴,容忍(parity)
				}
				double d = val.asDouble();
				if (d < min || d > max) {
					add(basePath + "/" + e.getKey(), "超出范围 [" + (int) min + "," + (int) max + "]");
				}
			});
		}

		void requireEnum(JsonNode parent, String field, Set<String> allowed) {
			requireEnumAt(parent, field, field, allowed);
		}

		void requireEnumAt(JsonNode parent, String field, String path, Set<String> allowed) {
			JsonNode n = parent == null ? null : parent.get(field);
			if (n == null || !n.isString() || !allowed.contains(n.asString())) {
				add(path, "必须为枚举之一 " + allowed);
			}
		}
	}
}
