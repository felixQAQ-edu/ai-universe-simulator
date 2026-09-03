package com.aiuniverse.server.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 整局闭环线上端点。薄适配(ADR-005),业务在 worldgen / eventloop 包,本类只搬运:
 * <ul>
 *   <li><b>{@code POST /api/game/init}</b>(设计稿 §3,plain POST 无 SSE):跑 world-gen 胖调用 →
 *       播种会话 → 返消毒投影 + openingNarrative + 初始动作;world-gen 救不回 → 5xx ERROR(无会话残留)。</li>
 *   <li><b>{@code POST /api/game/{saveId}/turn}</b>(规格 §4.1):取会话 → 守卫 1 合法性 →
 *       {@link TurnAdmission 并发准入}(占到名额才领线程)→ 池线程上跑
 *       {@link TurnStateMachine#submitAction}(阻塞含流式)→ 完成时 complete。
 *       三条容器线程上的拒绝走 HTTP 而非 SSE:<b>SSE 路线必须先领一个线程才能说那句话,
 *       而线程正是要省的东西</b>(ADR-022 立字 8)。</li>
 * </ul>
 */
@RestController
public class GameController {

	private final GameSessionManager sessions;
	private final TurnStateMachine stateMachine;
	private final GameInitService initService;
	private final QuotaGate quota;
	/**
	 * 回合并发准入(ADR-022)。SSE 是阻塞长连接,不能占 Tomcat 容器线程——而池<b>归它所有</b>
	 * (形态 (d)):本类手边<b>没有线程池可以绕过准入</b>,那是已知代价 2 的缓解——
	 * 从「一条断言拦着别绕过去」升级为「手边根本没有那个东西可绕」,
	 * 同「让忘记归还无法被写出来」的手法(<b>取消那个步骤本身,不是更小心地执行它</b>)。
	 * 由一条源码级断言钉住(它扫的是本文件里那两个类型名的字面量,故正文里也不许出现)。
	 */
	private final TurnAdmission admission;

	public GameController(GameSessionManager sessions, TurnStateMachine stateMachine, GameInitService initService,
			QuotaGate quota, TurnAdmission admission) {
		this.sessions = sessions;
		this.stateMachine = stateMachine;
		this.initService = initService;
		this.quota = quota;
		this.admission = admission;
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

	/**
	 * 推进一回合(规格 §4.1)。<b>ADR-016 守卫顺序立字的新宿主</b>(自 ADR-022 立字 6 起,
	 * 原宿主 {@code TurnStateMachine} 类 javadoc)——拒绝链,顺序不可换:
	 *
	 * <pre>
	 * ├─ session == null           → 404 {error:{code:"session_not_found"}}      ← 容器线程,零名额
	 * ├─ 守卫 1 合法性             → 400 {error:{code:"illegal_action", …}}      ← 容器线程,零名额
	 * ├─ 准入 submit()             → 503 {error:{code:"server_at_capacity", …}}  ← 容器线程,零线程 + WARN
	 * ├──────────────【交接:名额已占,此后在池线程上】──────────────
	 * ├─ 守卫 0 配额               → SSE event:error code=quota_exceeded         ← 相位零触碰
	 * ├─ 守卫 2 忙态 CAS           → SSE event:error code=busy
	 * └─ TurnExecutor 跑整回合     → SSE narrative/delta/ending
	 * </pre>
	 *
	 * <p><b>合法性在准入之前</b>(立字 7):它零副作用、不占名额、不需要归还——让一个必然被拒的请求
	 * 先占一个名额再还回来,是白白让真玩家少一个位子。<b>连带非法动作不再消耗配额额度</b>
	 * (立字 6,显式裁定非静默副产品)。
	 *
	 * <p><b>准入拒绝相位零触碰</b>:它发生在 CAS 之前、甚至在池线程之前,服务端从头到尾没碰过这局;
	 * 而一个<b>通过准入但被配额拒绝</b>的请求确实占了一个名额(μs 级)——那是刻意的,
	 * <b>配额在名额之内跑</b>(立字 3)。
	 *
	 * <p>⚠️ 三条拒绝都<b>显式</b> {@code contentType(APPLICATION_JSON)}:前端请求头带
	 * {@code Accept: text/event-stream}(`sse.ts`),不显式指定则内容协商有返 <b>406</b> 的风险
	 * ——那会让玩家看到的不是我们写的文案,而是一个空壳错误(已知代价 8,由 MockMvc 断言钉住)。
	 */
	@PostMapping("/api/game/{saveId}/turn")
	public ResponseEntity<?> turn(@PathVariable String saveId, @Valid @RequestBody TurnRequest req,
			HttpServletRequest http) {
		GameSession session = sessions.get(saveId);
		if (session == null) {
			// ⚠️ 只发 code,不发 message(ADR-022 闸 A 裁定):404「这个档没了」是**状态码本身就能说清**
			// 的情形,文案归前端兜底表——那句「点『返回』重新开始」是刀 1.5 被真机逐字证伪一次后才定下来的,
			// 且它引用的是前端控件名。此处若带上 message,`h5GameApi` 的 `err?.message ?? fallback.message`
			// 会让它压过兜底表,**静默撤销刀 1.5**(前端测试全绿)。code 与 message 各自独立兜底,
			// 这条混合是刀 1 优先级链显式允许的。
			return jsonError(HttpStatus.NOT_FOUND, "session_not_found", null);
		}
		// 守卫 1(前移,ADR-022 立字 5/7):确定性、零副作用、不占准入名额。
		if (!session.hasAction(req.actionId())) {
			return jsonError(HttpStatus.BAD_REQUEST, "illegal_action", "无效的行动:" + req.actionId());
		}
		QuotaGate.ClientKey client = clientKey(http); // 头在容器线程读定,不跨线程摸 request
		SseEmitter emitter = new SseEmitter(120_000L);
		SseTurnEventSink sink = new SseTurnEventSink(emitter);
		boolean admitted = admission.submit(saveId, () -> {
			try {
				stateMachine.submitAction(session, req.actionId(), sink, client);
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			}
		});
		if (!admitted) {
			// ⚠️ 文案只有服务端知道(「此刻挤 / 过几秒」),故 message 归服务端发,与 404 相反
			// ——那不是两个特例,是同一条判据的两侧(ADR-022 闸 A 立字)。
			// ⚠️ 不带 Retry-After:那等于发一张自动重试许可证,而齐步重试正是过载时最坏的形态。
			return jsonError(HttpStatus.SERVICE_UNAVAILABLE, "server_at_capacity",
					"此刻同时进行的回合太多,请过几秒再点一次");
		}
		return ResponseEntity.ok(emitter);
	}

	/**
	 * 回合路径的结构化拒绝体:<b>一种形状 {@code {error:{code, message?}}}、三个状态</b>。
	 *
	 * <p><b>{@code message} 何时出现是有规则的,不是特例</b>(ADR-022 闸 A 立字):
	 * <b>状态码本身就能说清的情形,文案归前端兜底表;只有服务端知道的细节,文案归服务端。</b>
	 * 故 404 只发 code(前端那句引用了控件名的话是文案真理源),而 {@code server_at_capacity}
	 * (只有服务端知道此刻挤)与 {@code illegal_action}(只有服务端知道是哪个动作)自带文案。
	 */
	private static ResponseEntity<?> jsonError(HttpStatus status, String code, String message) {
		Map<String, String> err = message == null ? Map.of("code", code) : Map.of("code", code, "message", message);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", err));
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
