package com.aiuniverse.server.persistence;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aiuniverse.server.llm.LlmUsage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 回合轨迹的<b>唯一</b>编解码(ADR-031 刀 2):一条 {@link TurnTrace} ⇄ 一行 JSON。
 * 刀 3 的文件落点({@code <saveId>.trace.jsonl},每行一条)与测试侧档 1 回放走同一个编解码,
 * 理由同 {@link SessionDocument}:两处各写一份「差不多」的解析,正是让一处写错而另一处绿着的掩护。
 *
 * <p><b>解码是严格的,不静默</b>:
 * <ul>
 *   <li>{@code schema} 不是本版本认识的值 → 抛(不猜旧格式、不按「差不多」读);</li>
 *   <li>任何键缺失、类型不对、或出现不认识的键 → 抛。可空字段一律<b>显式写出 {@code null}</b>,
 *       故「缺键」与「值为空」可区分 —— 编码端漏写一个字段,解码当场失败,而不是读成 {@code null} 往下走。</li>
 * </ul>
 *
 * <p>键序由本类固定;{@code encode(decode(encode(t)))} 与 {@code encode(t)} 逐字节相同。
 */
public final class TurnTraceCodec {

	private TurnTraceCodec() {
	}

	private static final List<String> KEYS = List.of("schema", "saveId", "turnBefore", "recordedAt", "commit",
			"actionId", "path", "pre", "parsed", "degradeReason", "streamedNarrative", "promptSha256", "usage",
			"durMs", "repairErrors", "post");
	private static final List<String> CALL_KEYS = List.of("call", "usage", "model", "reasoningChars");
	private static final List<String> USAGE_KEYS = List.of("promptTokens", "completionTokens", "totalTokens",
			"cacheHitTokens", "cacheMissTokens");
	private static final List<String> POST_KEYS = List.of("sha256", "attributes");

	/** 编码为一行 JSON(无换行)。 */
	public static String encode(TurnTrace t, ObjectMapper mapper) {
		ObjectNode o = mapper.createObjectNode();
		o.put("schema", t.schema());
		o.put("saveId", t.saveId());
		o.put("turnBefore", t.turnBefore());
		o.put("recordedAt", t.recordedAt());
		o.put("commit", t.commit());
		o.put("actionId", t.actionId());
		o.put("path", t.path());
		o.set("pre", t.pre());
		o.set("parsed", t.parsed() == null ? o.nullNode() : t.parsed());
		o.put("degradeReason", t.degradeReason());
		o.put("streamedNarrative", t.streamedNarrative());
		o.put("promptSha256", t.promptSha256());
		ArrayNode usage = o.putArray("usage");
		for (TurnTrace.CallUsage c : t.usage()) {
			ObjectNode u = usage.addObject();
			u.put("call", c.call());
			if (c.usage() == null) {
				u.putNull("usage");
			} else {
				LlmUsage lu = c.usage();
				u.putObject("usage").put("promptTokens", lu.promptTokens())
						.put("completionTokens", lu.completionTokens()).put("totalTokens", lu.totalTokens())
						.put("cacheHitTokens", lu.cacheHitTokens()).put("cacheMissTokens", lu.cacheMissTokens());
			}
			u.put("model", c.model());
			u.put("reasoningChars", c.reasoningChars());
		}
		o.put("durMs", t.durMs());
		if (t.repairErrors() == null) {
			o.putNull("repairErrors");
		} else {
			ArrayNode errs = o.putArray("repairErrors");
			t.repairErrors().forEach(errs::add);
		}
		ObjectNode post = o.putObject("post");
		post.put("sha256", t.post().sha256());
		ObjectNode attrs = post.putObject("attributes");
		t.post().attributes().forEach(attrs::put);
		return mapper.writeValueAsString(o);
	}

	/** 解码一行 JSON;不合法一律抛 {@link IllegalArgumentException}(见类注释)。 */
	public static TurnTrace decode(String line, ObjectMapper mapper) {
		JsonNode n;
		try {
			n = mapper.readTree(line);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("轨迹不是合法 JSON:" + e.getMessage(), e);
		}
		if (n == null || !n.isObject()) {
			throw new IllegalArgumentException("轨迹不是 JSON 对象");
		}
		JsonNode schema = n.get("schema");
		if (schema == null || !schema.isInt()) {
			throw new IllegalArgumentException("轨迹缺 schema(或非整数)");
		}
		if (schema.asInt() != TurnTrace.SCHEMA) {
			throw new IllegalArgumentException("未知轨迹 schema " + schema.asInt() + "(本版本只读 " + TurnTrace.SCHEMA + ")");
		}
		exactKeys(n, KEYS, "轨迹");
		List<TurnTrace.CallUsage> usage = new ArrayList<>();
		for (JsonNode c : array(n, "usage", false)) {
			exactKeys(c, CALL_KEYS, "usage[]");
			JsonNode u = c.get("usage");
			LlmUsage lu = null;
			if (!u.isNull()) {
				exactKeys(u, USAGE_KEYS, "usage[].usage");
				lu = new LlmUsage(lng(u, "promptTokens"), lng(u, "completionTokens"), lng(u, "totalTokens"),
						lng(u, "cacheHitTokens"), lng(u, "cacheMissTokens"));
			}
			usage.add(new TurnTrace.CallUsage(str(c, "call", false), lu, str(c, "model", true),
					lng(c, "reasoningChars")));
		}
		JsonNode errsNode = array(n, "repairErrors", true);
		List<String> repairErrors = null;
		if (errsNode != null) {
			repairErrors = new ArrayList<>();
			for (JsonNode e : errsNode) {
				if (!e.isString()) {
					throw new IllegalArgumentException("轨迹 repairErrors[] 非字符串");
				}
				repairErrors.add(e.asString());
			}
		}
		JsonNode post = object(n, "post", false);
		exactKeys(post, POST_KEYS, "post");
		Map<String, Double> attrs = new LinkedHashMap<>();
		JsonNode attrsNode = object(post, "attributes", false);
		for (Map.Entry<String, JsonNode> e : attrsNode.properties()) {
			if (!e.getValue().isNumber()) {
				throw new IllegalArgumentException("轨迹 post.attributes." + e.getKey() + " 非数值");
			}
			attrs.put(e.getKey(), e.getValue().asDouble());
		}
		return new TurnTrace(schema.asInt(), str(n, "saveId", false), (int) lng(n, "turnBefore"),
				str(n, "recordedAt", true), str(n, "commit", false), str(n, "actionId", false), str(n, "path", false),
				(ObjectNode) object(n, "pre", false), (ObjectNode) object(n, "parsed", true),
				str(n, "degradeReason", true), str(n, "streamedNarrative", true), str(n, "promptSha256", true),
				List.copyOf(usage), lng(n, "durMs"), repairErrors == null ? null : List.copyOf(repairErrors),
				new TurnTrace.Post(str(post, "sha256", false), attrs));
	}

	// ── 严格读取 ─────────────────────────────────────────────────────────

	private static void exactKeys(JsonNode n, List<String> expected, String where) {
		if (n == null || !n.isObject()) {
			throw new IllegalArgumentException("轨迹 " + where + " 非对象");
		}
		for (String k : expected) {
			if (!n.has(k)) {
				throw new IllegalArgumentException("轨迹 " + where + " 缺键 " + k);
			}
		}
		Set<String> known = Set.copyOf(expected);
		for (Iterator<String> it = n.propertyNames().iterator(); it.hasNext();) {
			String k = it.next();
			if (!known.contains(k)) {
				throw new IllegalArgumentException("轨迹 " + where + " 出现不认识的键 " + k);
			}
		}
	}

	private static String str(JsonNode n, String k, boolean nullable) {
		JsonNode v = n.get(k);
		if (v.isNull() && nullable) {
			return null;
		}
		if (!v.isString()) {
			throw new IllegalArgumentException("轨迹 " + k + " 非字符串");
		}
		return v.asString();
	}

	private static long lng(JsonNode n, String k) {
		JsonNode v = n.get(k);
		if (!v.isIntegralNumber()) {
			throw new IllegalArgumentException("轨迹 " + k + " 非整数");
		}
		return v.asLong();
	}

	private static JsonNode object(JsonNode n, String k, boolean nullable) {
		JsonNode v = n.get(k);
		if (v.isNull() && nullable) {
			return null;
		}
		if (!v.isObject()) {
			throw new IllegalArgumentException("轨迹 " + k + " 非对象");
		}
		return v;
	}

	private static JsonNode array(JsonNode n, String k, boolean nullable) {
		JsonNode v = n.get(k);
		if (v.isNull() && nullable) {
			return null;
		}
		if (!v.isArray()) {
			throw new IllegalArgumentException("轨迹 " + k + " 非数组");
		}
		return v;
	}
}
