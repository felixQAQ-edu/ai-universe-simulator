package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnPhase;
import com.aiuniverse.server.eventloop.TurnStateMachine;
import com.aiuniverse.server.eventloop.TurnResult;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.quota.QuotaGate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 回合路径在<b>容器线程上</b>的三条拒绝(ADR-022 §2 拒绝链):404 / 守卫 1 合法性 / 准入。
 * 三条都<b>零线程、零名额、相位零触碰</b>,走 HTTP 不走 SSE
 * (<b>SSE 路线必须先领一个线程才能说那句话,而线程正是要省的东西</b>,立字 8)。
 *
 * <p><b>本类是 ADR-016 守卫顺序立字的新宿主侧断言</b>(原宿主 {@code TurnStateMachine} 类 javadoc
 * + 那里的 {@code quotaGuardRunsBeforeLegalityGuard})。顺序已按 ADR-022 立字 6 <b>反转</b>:
 * 合法性跑在配额之前,连带非法动作不再消耗配额额度——<b>显式裁定,不是搬家的静默副产品</b>。
 *
 * <p>内容协商(已知代价 8)另由 {@code GameControllerTurnContentTypeTest} 走 MockMvc 真链路验:
 * 本类断言的是「我们设了 JSON 这个头」,<b>那证明不了 Spring 不会返 406</b>——两件事。
 */
class GameControllerTurnGuardsTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 记录 checkTurn 是否被调用过(立字 4:被池拒绝的玩家不掉额度)。 */
	private static final class CountingQuota implements QuotaGate {
		final AtomicInteger turnChecks = new AtomicInteger();

		@Override public Decision checkInit(ClientKey key) { return Decision.ALLOW; }

		@Override
		public Decision checkTurn(ClientKey key) {
			turnChecks.incrementAndGet();
			return Decision.ALLOW;
		}

		@Override public void record(LlmUsage usage) { }
	}

	private ObjectNode world() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		return world;
	}

	private ArrayNode actions() {
		ArrayNode actions = mapper.createArrayNode();
		actions.addObject().put("id", "A").put("text", "查看告示");
		return actions;
	}

	/** 建一个含 save-1 的 manager(A 合法、Z 非法)。 */
	private GameSessionManager managerWithSession() {
		GameSessionManager manager = new GameSessionManager(mapper);
		manager.create("save-1", world(), actions(), Set.of(), Map.of(), Set.of());
		return manager;
	}

	private static MockHttpServletRequest request() {
		MockHttpServletRequest http = new MockHttpServletRequest();
		http.setRemoteAddr("10.0.0.7");
		return http;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> errorOf(ResponseEntity<?> resp) {
		return ((Map<String, Map<String, String>>) resp.getBody()).get("error");
	}

	// ── 404:会话不存在 ───────────────────────────────────────────────────

	/**
	 * ⚠️ <b>本条守的是「刀 2 不许静默撤销刀 1.5」</b>(ADR-022 闸 A)。
	 * 404 body <b>只带 code、不带 message</b>:玩家可见文案「这一局的存档已经找不到了,点『返回』重新开始」
	 * 是刀 1.5 被真机<b>逐字证伪一次之后</b>才定下来的,真理源在前端兜底表(它引用的是前端控件名)。
	 * 若此处带上 message,{@code h5GameApi} 的 {@code err?.message ?? fallback.message} 会让它
	 * <b>压过兜底表</b>,而前端测试<b>全绿</b>——一次看不见的撤销。
	 *
	 * <p>规则(不是特例):<b>状态码本身就能说清的情形,文案归前端兜底表;只有服务端知道的细节,
	 * 文案归服务端</b>。对照 {@code server_at_capacity}(只有服务端知道此刻挤)与
	 * {@code illegal_action}(只有服务端知道是哪个动作)——两者都自带文案。
	 */
	@Test
	void missingSessionReturns404WithCodeOnlyBody() {
		CountingQuota quota = new CountingQuota();
		GameController c = new GameController(new GameSessionManager(mapper),
				new TurnStateMachine((s, a, sink) -> new TurnResult(false)), null, quota,
				new TurnAdmission(8, Runnable::run));

		ResponseEntity<?> resp = c.turn("nope", new GameController.TurnRequest(0, "A"), request());

		assertThat(resp.getStatusCode().value()).isEqualTo(404);
		assertThat(errorOf(resp)).containsExactly(Map.entry("code", "session_not_found"));
		assertThat(errorOf(resp)).as("带上 message 会压过刀 1.5 的兜底文案").doesNotContainKey("message");
		assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
	}

	// ── 守卫 1:合法性(自 TurnStateMachineTest 搬家而来)──────────────────

	/**
	 * 原 {@code TurnStateMachineTest#illegalActionEmitsErrorAndDoesNotCallExecutor} 的新宿主。
	 * 断言意图不变(非法动作 → 不提交任何工作),形态随前移改为 400 + 结构化 body。
	 */
	@Test
	void illegalActionReturns400AndSubmitsNothing() {
		CountingQuota quota = new CountingQuota();
		List<Runnable> submitted = new ArrayList<>();
		GameController c = new GameController(managerWithSession(),
				new TurnStateMachine((s, a, sink) -> new TurnResult(false)), null, quota,
				new TurnAdmission(8, submitted::add));

		ResponseEntity<?> resp = c.turn("save-1", new GameController.TurnRequest(0, "Z"), request());

		assertThat(resp.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(errorOf(resp).get("code")).isEqualTo("illegal_action");
		assertThat(errorOf(resp).get("message")).contains("Z");
		assertThat(submitted).as("非法动作零提交、零名额").isEmpty();
		assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
	}

	/**
	 * <b>ADR-022 立字 6 的可验证形态</b>:守卫顺序已反转,非法动作<b>不再消耗配额额度</b>。
	 * (原顺序下 {@code checkTurn} 会先被调一次;这条断言就是那次反转的钉子。)
	 */
	@Test
	void illegalActionDoesNotConsumeQuota() {
		CountingQuota quota = new CountingQuota();
		// ⚠️ 配额闸装进**状态机**:回合配额(守卫 0)的宿主是它,不是 controller
		// (controller 的 quota 只服务 init)。第一版传错了地方 → 计数恒 0 → 恒绿的假探针,
		// 由下一条用例的控制组当场抓出。
		GameController c = new GameController(managerWithSession(),
				new TurnStateMachine((s, a, sink) -> new TurnResult(false),
						com.aiuniverse.server.persistence.SessionStore.NOOP, quota),
				null, quota, new TurnAdmission(8, Runnable::run)); // 同线程跑,控制组才跑得起来

		// 对照:合法动作会真的消耗一次配额 —— 没有这句,「0」可能只是因为闸压根没接上。
		c.turn("save-1", new GameController.TurnRequest(0, "A"), request());
		assertThat(quota.turnChecks).as("控制组:合法动作确实查过配额").hasValue(1);
		quota.turnChecks.set(0);

		c.turn("save-1", new GameController.TurnRequest(0, "Z"), request());

		assertThat(quota.turnChecks).as("玩家什么都没得到就不该掉额度(立字 4 同源)").hasValue(0);
	}

	// ── 准入拒绝 ─────────────────────────────────────────────────────────

	@Test
	void admissionRejectionReturns503WithServerAtCapacity() {
		CountingQuota quota = new CountingQuota();
		// 容量 1 且提交后不跑(名额不还)→ 第二发必被拒。
		TurnAdmission admission = new TurnAdmission(1, r -> { });
		GameController c = new GameController(managerWithSession(),
				new TurnStateMachine((s, a, sink) -> new TurnResult(false)), null, quota, admission);

		assertThat(c.turn("save-1", new GameController.TurnRequest(0, "A"), request())
				.getStatusCode().value()).isEqualTo(200);
		ResponseEntity<?> second = c.turn("save-1", new GameController.TurnRequest(0, "A"), request());

		assertThat(second.getStatusCode().value()).isEqualTo(503);
		assertThat(errorOf(second).get("code")).isEqualTo("server_at_capacity");
		// 文案定稿(裁定 4):秒级预期 + 明确的下一步;全句不出现任何日级词。
		assertThat(errorOf(second).get("message")).isEqualTo("此刻同时进行的回合太多,请过几秒再点一次");
		assertThat(errorOf(second).get("message")).doesNotContain("明天").doesNotContain("今日")
				.doesNotContain("名额");
		assertThat(second.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		// 不带 Retry-After:带上等于发一张自动重试许可证,而齐步重试正是过载时最坏的形态。
		assertThat(second.getHeaders().getFirst("Retry-After")).isNull();
	}

	/**
	 * 立字 4 的可验证形态:被池拒绝的玩家<b>不掉额度</b>——他什么都没得到,秒级重试就能成功。
	 *
	 * <p>⚠️ <b>这条必须用真并发,不能用「收下但不跑」的停车执行器</b>(第一版就是那么写的,
	 * 被 M1 变异当场抓出来):任务不跑,配额本来就不会被查,<b>摘掉准入它照样绿</b>
	 * ——一个看起来在守、其实没在看的探针(ADR-018 §4.14)。
	 * 真并发下第一发<b>已经查过一次配额</b>并卡在任务体里占着名额,故「第二发查了没有」
	 * 才真正区分得开「被准入挡住」与「跑进去了」。
	 */
	@Test
	void admissionRejectionDoesNotConsumeQuota() throws Exception {
		CountingQuota quota = new CountingQuota();
		BlockingExecutor executor = new BlockingExecutor();
		GameController c = new GameController(managerWithSession(),
				new TurnStateMachine((s, a, sink) -> executor.park(),
						com.aiuniverse.server.persistence.SessionStore.NOOP, quota),
				null, quota, new TurnAdmission(1, executor));

		c.turn("save-1", new GameController.TurnRequest(0, "A"), request()); // 占住唯一名额并卡住
		assertThat(executor.entered.await(2, TimeUnit.SECONDS)).as("第一发已进入任务体").isTrue();
		assertThat(quota.turnChecks).as("控制组:跑进去的那一发确实查过配额").hasValue(1);

		c.turn("save-1", new GameController.TurnRequest(0, "A"), request()); // 被准入拒

		// ⚠️ 主判据用**同步**的提交计数:配额是在异步线程里查的,turn() 一返回就断言它
		//    是一个竞态绿(M1b 变异下实测仍绿 —— 那时线程还没起来)。提交计数在调用线程上加。
		assertThat(executor.submissions).as("被拒的那一发零提交").hasValue(1);
		assertThat(quota.turnChecks).as("故也零配额调用").hasValue(1);
		executor.release();
	}

	/**
	 * 准入拒绝<b>相位零触碰</b>:它发生在 CAS 之前、甚至在池线程之前,服务端从头到尾没碰过这局。
	 * (守卫 2 在池线程里,这条自动成立——<b>但要有断言在看</b>。)
	 *
	 * <p>⚠️ 同上,用真并发 + <b>第二个存档</b>:名额被 save-1 占着,save-2 被拒;
	 * 摘掉准入则 save-2 会跑起来、相位进 GENERATING —— 那时这条才红得起来。
	 */
	@Test
	void admissionRejectionLeavesPhaseUntouched() throws Exception {
		GameSessionManager manager = managerWithSession();
		manager.create("save-2", world(), actions(), Set.of(), Map.of(), Set.of());
		BlockingExecutor executor = new BlockingExecutor();
		GameController c = new GameController(manager,
				new TurnStateMachine((s, a, sink) -> executor.park()), null, new CountingQuota(),
				new TurnAdmission(1, executor));

		c.turn("save-1", new GameController.TurnRequest(0, "A"), request()); // 占住唯一名额并卡住
		assertThat(executor.entered.await(2, TimeUnit.SECONDS)).isTrue();
		c.turn("save-2", new GameController.TurnRequest(0, "A"), request()); // 被准入拒

		assertThat(manager.get("save-2").phase()).hasValue(TurnPhase.AWAITING_ACTION);
		executor.release();
	}

	/** 真线程跑任务,并卡在任务体里(名额与相位都真的被占住),测试末尾放行。 */
	private static final class BlockingExecutor implements java.util.concurrent.Executor {
		final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch hold = new CountDownLatch(1);

		/** 提交计数在**调用线程**上加 —— 无竞态,故可以在 turn() 返回后立刻断言。 */
		final AtomicInteger submissions = new AtomicInteger();

		@Override
		public void execute(Runnable command) {
			submissions.incrementAndGet();
			Thread.ofPlatform().daemon().start(() -> command.run());
		}

		/** 由 TurnExecutor stub 调用:进入后卡住。 */
		TurnResult park() {
			entered.countDown();
			try {
				hold.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new TurnResult(false);
		}

		void release() {
			hold.countDown();
		}
	}

	/**
	 * 准入通过后配额拒绝<b>仍在名额之内、走池线程</b>(守卫 0 留在原地的证据;配额在名额之内跑,立字 3)。
	 *
	 * <p>可观测判据 = <b>HTTP 侧 200(没被容器线程拦下)+ 配额确实被查过 + executor 零调用</b>。
	 * ⚠️ 不去断言「SSE 里出现了 quota_exceeded 帧」:sink 由 controller 内部自建、单元层拿不到,
	 * 硬凑只会写出一个看着在守其实没在看的断言;那一段由
	 * {@code TurnStateMachineTest#quotaGuardStillRunsBeforeCasAndSeesClientKey} 覆盖。
	 */
	@Test
	void quotaDenialAfterAdmissionRunsOnPoolThreadNotHttp() {
		AtomicInteger executorCalls = new AtomicInteger();
		AtomicInteger turnChecks = new AtomicInteger();
		QuotaGate denying = new QuotaGate() {
			@Override public Decision checkInit(ClientKey key) { return Decision.ALLOW; }
			@Override public Decision checkTurn(ClientKey key) {
				turnChecks.incrementAndGet();
				return Decision.deny("今日回合名额已满,明天再来");
			}
			@Override public void record(LlmUsage usage) { }
		};
		// Runnable::run = 同线程跑任务,故 submitAction 在 turn() 返回前已执行完。
		GameController c = new GameController(managerWithSession(),
				new TurnStateMachine((s, a, sink) -> {
					executorCalls.incrementAndGet();
					return new TurnResult(false);
				}, com.aiuniverse.server.persistence.SessionStore.NOOP, denying),
				null, denying, new TurnAdmission(8, Runnable::run));

		ResponseEntity<?> resp = c.turn("save-1", new GameController.TurnRequest(0, "A"), request());

		assertThat(resp.getStatusCode().value()).as("HTTP 侧照常 200 开流,配额错误走 SSE").isEqualTo(200);
		assertThat(turnChecks).as("配额在名额之内跑过一次").hasValue(1);
		assertThat(executorCalls).as("守卫 0 拒绝 → executor 零调用").hasValue(0);
	}

	/**
	 * ⚠️ <b>本条钉的不是产品行为,是刀 3 冒烟那条「必须用两个存档」的理由。</b>
	 * 单存档连点时第二发<b>先撞忙态守卫</b>(per-save CAS,守卫 2),<b>根本到不了准入</b>
	 * ——照单存档去冒烟会拿着 {@code busy} 以为准入验过了,那是 ADR-018 §4.18
	 * 「观测指标要与被测对象匹配」的原样重演。没有这条用例,那句叮嘱只是清单里的一行字。
	 *
	 * <p>判据 = <b>名额充足时第二发拿到的是 200(池内 busy)而不是 503(准入拒绝)</b>,
	 * 且 executor 零调用(CAS 失败)。「那一帧确实是 busy」由
	 * {@code TurnStateMachineTest#busyPhaseRejectsSecondSubmitWithoutCallingExecutor} 覆盖,
	 * 两条合起来才是完整链条。
	 */
	@Test
	void sameSaveSecondSubmitHitsBusyNotCapacity() {
		AtomicInteger executorCalls = new AtomicInteger();
		GameSessionManager manager = managerWithSession();
		manager.get("save-1").phase().set(com.aiuniverse.server.eventloop.TurnPhase.GENERATING);
		GameController c = new GameController(manager,
				new TurnStateMachine((s, a, sink) -> {
					executorCalls.incrementAndGet();
					return new TurnResult(false);
				}), null, new CountingQuota(), new TurnAdmission(8, Runnable::run)); // 名额充足

		ResponseEntity<?> resp = c.turn("save-1", new GameController.TurnRequest(0, "A"), request());

		assertThat(resp.getStatusCode().value())
				.as("忙态不是 HTTP 拒绝:它在池线程里走 SSE,与 503 是两条路").isEqualTo(200);
		assertThat(executorCalls).as("CAS 失败 → executor 零调用").hasValue(0);
	}
}
