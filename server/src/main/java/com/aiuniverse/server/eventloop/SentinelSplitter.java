package com.aiuniverse.server.eventloop;

import java.util.function.Consumer;

/**
 * 哨兵切分器(纯逻辑 · ADR-006 线上协议落地)—— 把单次调用的 token 流切成两路:
 * 哨兵 {@value #SENTINEL} <b>之前</b>全部是叙事散文(逐字下发玩家),哨兵<b>之后</b>全部缓冲为
 * 结构化尾巴(供 {@link TurnReinfuser} 回灌)。规格 §4.3 / §4.4。
 *
 * <p><b>跨 chunk 边界扫描</b>:token 可能在哨兵中间切开(如 {@code "...夜<<<DE"} + {@code "LTA>>>{..."}),
 * 故只要尚未命中完整哨兵,就压住末尾 {@code SENTINEL.length()-1} 个字符不发(它们可能是半截哨兵),
 * 其余安全前缀立即作为叙事增量吐出。命中完整哨兵后,其后所有字符进尾巴。
 *
 * <p><b>不变量</b>(配单测):叙事 == 哨兵前全部;哨兵字不漏进叙事;叙事字不被吞进尾巴;
 * 只切<b>第一个</b>哨兵(其后再现的同串当普通尾巴内容);流结束仍无哨兵 → 全部当叙事、{@link #tail()} 空
 * (交下游降级,规格 §6.6)。
 *
 * <p>与编排彻底解耦:只认一个 {@code Consumer<String>} 叙事增量回调,不碰 SSE / 引擎 / LLM。
 * 非线程安全(单回合单线程消费)。
 */
public final class SentinelSplitter {

	/** ADR-006 哨兵:叙事散文与结构化尾巴的分隔符。 */
	public static final String SENTINEL = "<<<DELTA>>>";

	private final Consumer<String> narrativeSink;
	/** 尚未确定能否安全作为叙事吐出的缓冲(可能含半截哨兵)。 */
	private final StringBuilder pending = new StringBuilder();
	private final StringBuilder tail = new StringBuilder();
	private boolean sentinelSeen = false;

	public SentinelSplitter(Consumer<String> narrativeSink) {
		this.narrativeSink = narrativeSink;
	}

	/** 喂入一个 token(可为任意长度子串,含空串)。 */
	public void accept(String token) {
		if (token == null || token.isEmpty()) {
			return;
		}
		if (sentinelSeen) {
			tail.append(token);
			return;
		}
		pending.append(token);

		int idx = pending.indexOf(SENTINEL);
		if (idx >= 0) {
			// 命中完整哨兵:哨兵前 → 叙事;哨兵后 → 尾巴;切换状态(只切第一个)。
			emitNarrative(pending.substring(0, idx));
			tail.append(pending.substring(idx + SENTINEL.length()));
			pending.setLength(0);
			sentinelSeen = true;
			return;
		}

		// 未命中:压住末尾 (SENTINEL.length()-1) 字符(可能是半截哨兵),其余安全前缀吐出。
		// 任何尚不完整的哨兵其起点必落在被压住的尾段内,故吐出的前缀绝不会劈开哨兵。
		int keep = SENTINEL.length() - 1;
		if (pending.length() > keep) {
			int emitLen = pending.length() - keep;
			emitNarrative(pending.substring(0, emitLen));
			pending.delete(0, emitLen);
		}
	}

	/** 流结束:无哨兵 → 残留 pending 全部当叙事吐出({@link #tail()} 仍空,交下游降级)。 */
	public void end() {
		if (!sentinelSeen && pending.length() > 0) {
			emitNarrative(pending.toString());
			pending.setLength(0);
		}
	}

	public boolean sentinelSeen() {
		return sentinelSeen;
	}

	/** 哨兵后的结构化尾巴(未命中哨兵则为空串)。 */
	public String tail() {
		return tail.toString();
	}

	private void emitNarrative(String s) {
		if (!s.isEmpty()) {
			narrativeSink.accept(s);
		}
	}
}
