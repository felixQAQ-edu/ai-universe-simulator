package com.aiuniverse.server.web;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnStateMachine;
import com.aiuniverse.server.worldgen.GameInitService;
import com.aiuniverse.server.worldgen.InitResponse;
import com.aiuniverse.server.worldgen.WorldGenException;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 整局闭环线上端点。薄适配(ADR-005),业务在 worldgen / eventloop 包,本类只搬运:
 * <ul>
 *   <li><b>{@code POST /api/game/init}</b>(设计稿 §3,plain POST 无 SSE):跑 world-gen 胖调用 →
 *       播种会话 → 返消毒投影 + openingNarrative + 初始动作;world-gen 救不回 → 5xx ERROR(无会话残留)。</li>
 *   <li><b>{@code POST /api/game/{saveId}/turn}</b>(规格 §4.1):取会话 → 建 {@link SseTurnEventSink}
 *       → 独立线程跑 {@link TurnStateMachine#submitAction}(阻塞含流式)→ 完成时 complete。</li>
 * </ul>
 */
@RestController
public class GameController {

	private final GameSessionManager sessions;
	private final TurnStateMachine stateMachine;
	private final GameInitService initService;
	/** SSE 是阻塞长连接,不能占 Tomcat 容器线程(同 StreamController 骨架口径)。 */
	private final ExecutorService turnExecutor = Executors.newCachedThreadPool();

	public GameController(GameSessionManager sessions, TurnStateMachine stateMachine, GameInitService initService) {
		this.sessions = sessions;
		this.stateMachine = stateMachine;
		this.initService = initService;
	}

	/**
	 * 起一局新世界(INITIALIZING,设计稿 §3):plain POST 阻塞返 JSON。
	 * archetype 非法/未开放 → 400(ADR-008 决策 4);world-gen ERROR → 502 + 重生成提示。
	 */
	@PostMapping("/api/game/init")
	public ResponseEntity<?> init(@Valid @RequestBody InitRequest req) {
		try {
			InitResponse resp = initService.init(req.archetype());
			return ResponseEntity.ok(resp);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(Map.of("error", Map.of("code", "invalid_archetype", "message", e.getMessage())));
		} catch (WorldGenException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(Map.of("error", Map.of("code", "world_gen_failed", "message", e.getMessage())));
		}
	}

	@PostMapping("/api/game/{saveId}/turn")
	public ResponseEntity<SseEmitter> turn(@PathVariable String saveId, @Valid @RequestBody TurnRequest req) {
		GameSession session = sessions.get(saveId);
		if (session == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
		SseEmitter emitter = new SseEmitter(120_000L);
		SseTurnEventSink sink = new SseTurnEventSink(emitter);
		turnExecutor.execute(() -> {
			try {
				stateMachine.submitAction(session, req.actionId(), sink);
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			}
		});
		return ResponseEntity.ok(emitter);
	}

	@PreDestroy
	void shutdown() {
		turnExecutor.shutdown();
	}

	/** 起局请求(设计稿 §3)。{@code archetype} ∈ §三.4 枚举,init 校验已激活(ADR-008 决策 4)。 */
	public record InitRequest(@NotBlank String archetype) {
	}

	/** 玩家 → server 回合请求(规格 §4.1)。Phase 1 只允许选 id。 */
	public record TurnRequest(int turn, @NotBlank String actionId) {
	}
}
