package com.aiuniverse.server.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnStateMachine;
import com.aiuniverse.server.quota.QuotaGate;
import com.aiuniverse.server.worldgen.GameInitService;
import com.aiuniverse.server.worldgen.InitResponse;
import com.aiuniverse.server.worldgen.WorldGenException;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
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
	private final QuotaGate quota;
	/** SSE 是阻塞长连接,不能占 Tomcat 容器线程(同 StreamController 骨架口径)。 */
	private final ExecutorService turnExecutor = Executors.newCachedThreadPool();

	public GameController(GameSessionManager sessions, TurnStateMachine stateMachine, GameInitService initService,
			QuotaGate quota) {
		this.sessions = sessions;
		this.stateMachine = stateMachine;
		this.initService = initService;
		this.quota = quota;
	}

	/**
	 * 起一局新世界(INITIALIZING,设计稿 §3):plain POST 阻塞返 JSON。
	 * 成本闸门前置(ADR-016):拒绝 → 429 + 结构化 error,world-gen <b>零调用</b>(拒绝成本 ≈0);
	 * archetype 非法/未开放 → 400(ADR-008 决策 4);world-gen ERROR → 502 + 重生成提示。
	 */
	@PostMapping("/api/game/init")
	public ResponseEntity<?> init(@Valid @RequestBody InitRequest req, HttpServletRequest http) {
		QuotaGate.Decision decision = quota.checkInit(clientKey(http));
		if (!decision.allowed()) {
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
					.body(Map.of("error", Map.of("code", "quota_exceeded", "message", decision.message())));
		}
		try {
			InitResponse resp = initService.init(req.resolved());
			return ResponseEntity.ok(resp);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(Map.of("error", Map.of("code", "invalid_archetype", "message", e.getMessage())));
		} catch (WorldGenException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(Map.of("error", Map.of("code", "world_gen_failed", "message", e.getMessage())));
		}
	}

	/**
	 * 续局查询(ADR-015 Slice 2):把内存表(含启动回载)里的会话状态一次性下发给前端。
	 * 响应复用 {@code InitResponse} 形态(openingNarrative 恒空;world = 消毒视图 3);
	 * 不存在 → 404(前端静默清 saveId 回正常起局)。
	 */
	@GetMapping("/api/game/{saveId}/state")
	public ResponseEntity<?> state(@PathVariable String saveId) {
		InitResponse resp = initService.resume(saveId);
		if (resp == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Map.of("error", Map.of("code", "session_not_found", "message", "存档不存在或已失效")));
		}
		return ResponseEntity.ok(resp);
	}

	@PostMapping("/api/game/{saveId}/turn")
	public ResponseEntity<SseEmitter> turn(@PathVariable String saveId, @Valid @RequestBody TurnRequest req,
			HttpServletRequest http) {
		GameSession session = sessions.get(saveId);
		if (session == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
		QuotaGate.ClientKey client = clientKey(http); // 头在容器线程读定,不跨线程摸 request
		SseEmitter emitter = new SseEmitter(120_000L);
		SseTurnEventSink sink = new SseTurnEventSink(emitter);
		turnExecutor.execute(() -> {
			try {
				stateMachine.submitAction(session, req.actionId(), sink, client);
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

	/**
	 * 软闸双键(ADR-016 §2):ip 读 {@code Fly-Client-IP} 头(经 Fly 反代;{@code getRemoteAddr}
	 * 只见内网地址,勘察已证),缺失(本地开发/直连)回退 {@code getRemoteAddr};deviceId 读
	 * {@code X-Device-Id} 头(前端 localStorage UUID),可缺失——缺哪个键哪路不计。
	 */
	private static QuotaGate.ClientKey clientKey(HttpServletRequest http) {
		String ip = http.getHeader("Fly-Client-IP");
		if (ip == null || ip.isBlank()) {
			ip = http.getRemoteAddr();
		}
		return new QuotaGate.ClientKey(ip, http.getHeader("X-Device-Id"));
	}

	/**
	 * 起局请求(设计稿 §3;ADR-013 扩为收有序双值)。<b>两形态并存、向后兼容</b>:
	 * <ul>
	 *   <li>单体:{@code {"archetype":"rules_creepy"}}(旧前端 / 单模式);</li>
	 *   <li>融合:{@code {"archetypes":["cultivation","rules_creepy"]}}(host 在前,ADR-013)。</li>
	 * </ul>
	 * {@link #resolved()} 规范化为有序列表(archetypes 优先,否则 archetype 单元素);空 → init 抛非法 → 400。
	 * archetype 合法性 / 已激活 / 融合组合登记均由 {@code GameInitService} 校验(ADR-008 决策 4 / ADR-013)。
	 */
	public record InitRequest(String archetype, List<String> archetypes) {

		/** 规范化为有序 archetype 列表(host 在前):archetypes 非空则用它,否则单 archetype 包成单元素,均空 → 空表。 */
		public List<String> resolved() {
			if (archetypes != null && !archetypes.isEmpty()) {
				return List.copyOf(archetypes);
			}
			if (archetype != null && !archetype.isBlank()) {
				return List.of(archetype);
			}
			return List.of();
		}
	}

	/** 玩家 → server 回合请求(规格 §4.1)。Phase 1 只允许选 id。 */
	public record TurnRequest(int turn, @NotBlank String actionId) {
	}
}
