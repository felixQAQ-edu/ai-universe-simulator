package com.aiuniverse.server.web;

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

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.node.ObjectNode;

/**
 * event-loop 线上端点(规格 §4.1):{@code POST /api/game/{saveId}/turn}。薄适配(ADR-005):
 * 取会话 → 建 {@link SseTurnEventSink} → 在独立线程跑 {@link TurnStateMachine#submitAction}(阻塞含流式)
 * → 完成时 complete。所有业务(守卫/流式/回灌/降级/消毒)在 eventloop 包,本类只搬运。
 *
 * <p>另含一个 dev 端点 {@code POST /api/dev/game/{saveId}/init}:用外部 world JSON 起会话,供
 * 真 key 手动冒烟(本批未起 world-gen,规格 §2 INITIALIZING 留待后续)。
 */
@RestController
public class GameController {

	private final GameSessionManager sessions;
	private final TurnStateMachine stateMachine;
	/** SSE 是阻塞长连接,不能占 Tomcat 容器线程(同 StreamController 骨架口径)。 */
	private final ExecutorService turnExecutor = Executors.newCachedThreadPool();

	public GameController(GameSessionManager sessions, TurnStateMachine stateMachine) {
		this.sessions = sessions;
		this.stateMachine = stateMachine;
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

	@PostMapping("/api/dev/game/{saveId}/init")
	public ResponseEntity<String> devInit(@PathVariable String saveId, @RequestBody ObjectNode world) {
		sessions.create(saveId, world);
		return ResponseEntity.ok("session " + saveId + " 已建立");
	}

	@PreDestroy
	void shutdown() {
		turnExecutor.shutdown();
	}

	/** 玩家 → server 回合请求(规格 §4.1)。Phase 1 只允许选 id。 */
	public record TurnRequest(int turn, @NotBlank String actionId) {
	}
}
