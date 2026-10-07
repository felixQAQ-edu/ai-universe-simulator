package com.aiuniverse.server.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.aiuniverse.server.llm.LlmUsage;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 一回合的执行轨迹(ADR-031 §1.2;已决 W-2 / W-8 / W-9)。字段与 ADR 表逐项对应,<b>以 ADR 为准</b>。
 *
 * <p><b>保密级别等于存档</b>(ADR-031 已知代价 1):{@link #pre} 是视图 1 全量,含 {@code isTrue} / {@code hiddenLogic}。
 * 本类型<b>不得出现在任何出网路径上</b>(CONTEXT §三.9;出网一律走 {@code toClientState})。
 *
 * <p>刀 1 只有采集与接缝,没有任何落点(默认装配 {@link TraceSink#NOOP})。不收的字段(档 2 的
 * {@code rawNarrative} / {@code rawTail} / 修复尾巴,挂账于刀 4;prompt 全文,W-9 只存哈希)刻意不在这里。
 *
 * @param schema            轨迹格式版本({@link #SCHEMA})
 * @param saveId            存档
 * @param turnBefore        回合前的引擎回合号
 * @param recordedAt        回合结束时刻(ISO-8601,与 {@code durMs} 终点<b>同一次</b>时钟读数,不多读一次时钟);
 *                          落地之后、读到终点之前就抛出的极端情形为 {@code null}
 * @param commit            代码版本({@code build-info.properties} 的 {@code build.commit};读不到为 {@code unknown})
 * @param actionId          本回合玩家所选动作
 * @param path              {@code settled} / {@code degraded}
 * @param pre               回合前状态 = {@code SessionDocument.encode} 在 {@code execute} 开头的副本(W-2 全量)
 * @param parsed            settled:{@code clampClosingVigorFloor} 改写<b>之后</b>、{@code apply} 之前的节点副本;degraded 为 {@code null}
 * @param degradeReason     degraded:{@code stream_interrupted} / {@code no_structured_tail} / {@code repair_failed};settled 为 {@code null}
 * @param streamedNarrative degraded:传给 {@code applyNoOp} 的已流出叙事(不含离开叙事 —— 那段由编排确定地重算,ADR §三 档 1);settled 为 {@code null}
 * @param promptSha256      主调用 prompt 的 sha256(W-9:不存全文,同版本由 {@code pre} + {@code actionId} 重渲染核对)
 * @param usage             每次模型调用一条(主调用 / 修复);无 usage 块的调用({@code usage == null})照样记 model 与思考字符数
 * @param durMs             回合总耗时(与 per-turn INFO / 降级 WARN 同一数);未读到终点为 -1
 * @param repairErrors      触发修复时回喂模型的校验错误清单;未触发修复为 {@code null}
 * @param post              落账后摘要(比对目标)
 */
public record TurnTrace(
		int schema,
		String saveId,
		int turnBefore,
		String recordedAt,
		String commit,
		String actionId,
		String path,
		ObjectNode pre,
		ObjectNode parsed,
		String degradeReason,
		String streamedNarrative,
		String promptSha256,
		List<CallUsage> usage,
		long durMs,
		List<String> repairErrors,
		Post post) {

	/** 轨迹格式版本。 */
	public static final int SCHEMA = 1;

	public static final String PATH_SETTLED = "settled";
	public static final String PATH_DEGRADED = "degraded";

	/** 一次模型调用的诊断(W-8)。 */
	public record CallUsage(String call, LlmUsage usage, String model, long reasoningChars) {
	}

	/**
	 * 落账后摘要(ADR-031 §1.2):{@code sha256} = 去掉 {@code phaseHint} 的快照文档;{@code attributes} = 各轴落账值。
	 * {@code phaseHint} 必须排除:它记的是 persist 那一刻的相位(正常回合 SETTLING、主调用流中断降级 GENERATING),与落账无关。
	 */
	public record Post(String sha256, Map<String, Double> attributes) {
	}

	/** 摘要用的快照文档字节:{@code doc} 去掉 {@code phaseHint} 后序列化(副本,不改入参)。回放(刀 2)须走同一函数。 */
	public static String postSha256(ObjectNode doc, ObjectMapper mapper) {
		ObjectNode copy = doc.deepCopy();
		copy.remove("phaseHint");
		return sha256Hex(mapper.writeValueAsString(copy));
	}

	public static String sha256Hex(String text) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("JDK 缺 SHA-256", e); // JDK 规范保证存在,不会发生
		}
	}
}
