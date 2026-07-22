package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnStateMachine;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.quota.QuotaGate;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-016 init 前置闸门:拒绝 → 429 + 结构化 {@code error{code:quota_exceeded}}、
 * world-gen 零调用;软闸双键从 {@code Fly-Client-IP}/{@code X-Device-Id} 头读取,
 * IP 头缺失回退 {@code getRemoteAddr}。
 */
class GameControllerQuotaTest {

	/** 记录收到的 ClientKey 并按预设拒绝 init 的 stub 闸门。 */
	private static final class RecordingQuota implements QuotaGate {
		final List<ClientKey> initKeys = new ArrayList<>();
		boolean allowInit = false;

		@Override
		public Decision checkInit(ClientKey key) {
			initKeys.add(key);
			return allowInit ? Decision.ALLOW : Decision.deny("今日新世界名额已满,明天再来");
		}

		@Override
		public Decision checkTurn(ClientKey key) {
			return Decision.ALLOW;
		}

		@Override
		public void record(LlmUsage usage) {
		}
	}

	/** 拒绝路径不触达 initService/stateMachine(传 null 即证:被触达会 NPE)。 */
	private GameController controller(RecordingQuota quota) {
		return new GameController(new GameSessionManager(new ObjectMapper()),
				new TurnStateMachine((s, a, sink) -> null), null, quota);
	}

	@Test
	void deniedInitReturns429WithStructuredErrorAndSkipsWorldGen() {
		RecordingQuota quota = new RecordingQuota();
		MockHttpServletRequest http = new MockHttpServletRequest();
		http.addHeader("Fly-Client-IP", "203.0.113.9");
		http.addHeader("X-Device-Id", "dev-42");

		ResponseEntity<?> resp = controller(quota)
				.init(new GameController.InitRequest("rules_creepy", null), http);

		assertThat(resp.getStatusCode().value()).isEqualTo(429);
		@SuppressWarnings("unchecked")
		Map<String, Map<String, String>> body = (Map<String, Map<String, String>>) resp.getBody();
		assertThat(body.get("error").get("code")).isEqualTo("quota_exceeded");
		assertThat(body.get("error").get("message")).contains("明天再来");
		// 头映射:Fly-Client-IP 优先 + X-Device-Id 透传。
		assertThat(quota.initKeys).containsExactly(new QuotaGate.ClientKey("203.0.113.9", "dev-42"));
	}

	@Test
	void missingFlyHeaderFallsBackToRemoteAddr() {
		RecordingQuota quota = new RecordingQuota();
		MockHttpServletRequest http = new MockHttpServletRequest();
		http.setRemoteAddr("10.0.0.7"); // 本地开发/直连:无反代头
		controller(quota).init(new GameController.InitRequest("rules_creepy", null), http);
		assertThat(quota.initKeys).containsExactly(new QuotaGate.ClientKey("10.0.0.7", null));
	}
}
