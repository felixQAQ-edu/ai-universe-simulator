package com.aiuniverse.server.quota;

import com.aiuniverse.server.llm.LlmUsage;

/**
 * 成本闸门接缝(ADR-016)。三个动作:init 判定 / turn 判定(均在 LLM 调用<b>之前</b>,
 * 被刷时单次拒绝成本 ≈0)+ ¥ 记账(旁挂在 usage 收口处,LLM 调用之后)。
 *
 * <p>接缝形态照抄 {@link com.aiuniverse.server.persistence.SessionStore} 先例:
 * 消费方({@code GameController} / {@code TurnStateMachine} / {@code EventLoopService} /
 * {@code WorldGenService})旧构造委托 {@link #NOOP}(现有测试调用点零改),
 * {@code @Autowired} 构造收真实现 {@link QuotaService}。
 */
public interface QuotaGate {

	/**
	 * 软闸双键(ADR-016 §2:谁先撞谁拦):ip = {@code Fly-Client-IP} 头(缺失回退
	 * {@code getRemoteAddr});deviceId = 前端 localStorage UUID({@code X-Device-Id} 头,
	 * 可缺失——缺哪个键哪路不计,不拒)。
	 */
	record ClientKey(String ip, String deviceId) {
	}

	/** 判定结果;拒绝时 {@code message} 为玩家可见中文文案。 */
	record Decision(boolean allowed, String message) {
		public static final Decision ALLOW = new Decision(true, "");

		public static Decision deny(String message) {
			return new Decision(false, message);
		}
	}

	/** init 入口判定(全局 ¥ 双顶 + 单键日 init 局数);放行即计数。{@code key} 可为 null(只查全局闸)。 */
	Decision checkInit(ClientKey key);

	/** turn 守卫 0 判定(全局 ¥ 双顶 + 单键日回合数);放行即计数。{@code key} 可为 null(只查全局闸)。 */
	Decision checkTurn(ClientKey key);

	/** ¥ 记账(usage 收口处旁挂):null(mock / provider 未回 usage 块)静默跳过——mock 天然免疫。 */
	void record(LlmUsage usage);

	/** 全放行、不记账(测试 / 未接线消费方的默认)。 */
	QuotaGate NOOP = new QuotaGate() {
		@Override
		public Decision checkInit(ClientKey key) {
			return Decision.ALLOW;
		}

		@Override
		public Decision checkTurn(ClientKey key) {
			return Decision.ALLOW;
		}

		@Override
		public void record(LlmUsage usage) {
		}
	};
}
