package com.aiuniverse.server.eventloop;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aiuniverse.server.persistence.TurnTrace;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-031 刀 5a · <b>回归夹具</b>:由线上原始轨迹转换而来、可以进仓库的那一份(测试侧,W-10)。
 *
 * <p><b>字段是白名单,不是黑名单</b>:只保留档 1 回放({@link TraceReplayer})实际读取的字段 ——
 * {@code saveId}(已替换)/ {@code commit} / {@code actionId} / {@code path} / {@code pre} / {@code parsed} /
 * {@code streamedNarrative} / {@code post}。原始轨迹里的其余字段({@code recordedAt} / {@code usage}(含 model 与思考字符数)/
 * {@code durMs} / {@code repairErrors} / {@code degradeReason} / {@code promptSha256} / {@code turnBefore})一律不写出。
 * 原始轨迹将来多一个字段,本格式<b>不会</b>自动带上它 —— 那正是白名单的意义。
 *
 * <p>解码严格:缺键、多键、类型不对一律抛(同 {@code TurnTraceCodec})。
 */
final class TraceFixture {

	/** 夹具格式版本。 */
	static final int SCHEMA = 1;

	/** 白名单(顶层,键序即编码顺序)。改它 = 改夹具格式,须同步 ADR-031 已决 W-11 的字段清单。 */
	static final List<String> KEYS = List.of("fixtureSchema", "saveId", "commit", "actionId", "path", "pre",
			"parsed", "streamedNarrative", "post");
	static final List<String> POST_KEYS = List.of("sha256", "attributes");

	/** 替换后的存档标识前缀;后缀由运行转换工具的人给(不由原 saveId 派生,避免可反查)。 */
	static final String SAVE_ID_PREFIX = "fixture-";

	private TraceFixture() {
	}

	/** 由原始轨迹取白名单字段,saveId 换成 {@code fixtureSaveId}。其余字段在这里就被丢掉,不进入任何输出。 */
	static String encode(TurnTrace t, String fixtureSaveId, ObjectMapper mapper) {
		ObjectNode o = mapper.createObjectNode();
		o.put("fixtureSchema", SCHEMA);
		o.put("saveId", fixtureSaveId);
		o.put("commit", t.commit());
		o.put("actionId", t.actionId());
		o.put("path", t.path());
		o.set("pre", t.pre().deepCopy());
		o.set("parsed", t.parsed() == null ? o.nullNode() : t.parsed().deepCopy());
		o.put("streamedNarrative", t.streamedNarrative());
		ObjectNode post = o.putObject("post");
		post.put("sha256", t.post().sha256());
		ObjectNode attrs = post.putObject("attributes");
		t.post().attributes().forEach(attrs::put);
		return mapper.writeValueAsString(o);
	}

	/**
	 * 解码为回放器能吃的 {@link TurnTrace}。白名单之外的字段填占位(回放器不读它们):
	 * {@code usage} 空、{@code durMs} -1、其余可空字段 {@code null};{@code turnBefore} 取自 {@code pre.state.turn}。
	 */
	static TurnTrace decode(String line, ObjectMapper mapper) {
		JsonNode n;
		try {
			n = mapper.readTree(line);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("夹具不是合法 JSON:" + e.getMessage(), e);
		}
		exactKeys(n, KEYS, "夹具");
		if (!n.get("fixtureSchema").isInt() || n.get("fixtureSchema").asInt() != SCHEMA) {
			throw new IllegalArgumentException("未知夹具 fixtureSchema " + n.get("fixtureSchema"));
		}
		JsonNode post = n.get("post");
		exactKeys(post, POST_KEYS, "夹具 post");
		if (!post.get("attributes").isObject()) {
			throw new IllegalArgumentException("夹具 post.attributes 非对象");
		}
		Map<String, Double> attrs = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> e : post.get("attributes").properties()) {
			if (!e.getValue().isNumber()) {
				throw new IllegalArgumentException("夹具 post.attributes." + e.getKey() + " 非数值");
			}
			attrs.put(e.getKey(), e.getValue().asDouble());
		}
		if (!n.get("pre").isObject()) {
			throw new IllegalArgumentException("夹具 pre 非对象");
		}
		ObjectNode pre = (ObjectNode) n.get("pre");
		JsonNode parsed = n.get("parsed");
		if (!parsed.isNull() && !parsed.isObject()) {
			throw new IllegalArgumentException("夹具 parsed 非对象");
		}
		return new TurnTrace(TurnTrace.SCHEMA, str(n, "saveId", false), pre.path("state").path("turn").asInt(-1),
				null, str(n, "commit", false), str(n, "actionId", false), str(n, "path", false), pre,
				parsed.isNull() ? null : (ObjectNode) parsed, null, str(n, "streamedNarrative", true), null,
				List.of(), -1, null, new TurnTrace.Post(str(post, "sha256", false), attrs));
	}

	private static void exactKeys(JsonNode n, List<String> expected, String where) {
		if (n == null || !n.isObject()) {
			throw new IllegalArgumentException(where + " 非对象");
		}
		for (String k : expected) {
			if (!n.has(k)) {
				throw new IllegalArgumentException(where + " 缺键 " + k);
			}
		}
		Set<String> known = Set.copyOf(expected);
		for (Iterator<String> it = n.propertyNames().iterator(); it.hasNext();) {
			String k = it.next();
			if (!known.contains(k)) {
				throw new IllegalArgumentException(where + " 出现不认识的键 " + k);
			}
		}
	}

	private static String str(JsonNode n, String k, boolean nullable) {
		JsonNode v = n.get(k);
		if (v.isNull() && nullable) {
			return null;
		}
		if (!v.isString()) {
			throw new IllegalArgumentException("夹具 " + k + " 非字符串");
		}
		return v.asString();
	}
}
