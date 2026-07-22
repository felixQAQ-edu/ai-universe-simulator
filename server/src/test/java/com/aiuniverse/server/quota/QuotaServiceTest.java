package com.aiuniverse.server.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aiuniverse.server.llm.LlmProperties;
import com.aiuniverse.server.llm.LlmUsage;
import com.aiuniverse.server.quota.QuotaGate.ClientKey;
import com.aiuniverse.server.quota.QuotaGate.Decision;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-016 成本闸门:¥ 记账精度(price 三段)/ 四路软闸阈值 / 北京时间跨日重置 /
 * 月累计落盘与回载 / mock 豁免 / 缺字段容错。
 */
class QuotaServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@TempDir
	Path tmp;

	/** 可推进的测试时钟(默认 2026-07-22 12:00 北京时间)。 */
	private static final class MutableClock extends Clock {
		volatile Instant instant = Instant.parse("2026-07-22T04:00:00Z"); // 北京 12:00

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this; // QuotaService 只消费 instant(),北京区换算在服务内做
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}

	private static LlmProperties llm(String active) {
		// DeepSeek V4-Flash 官方价(2026-07-20 查证):miss ¥1.0 / hit ¥0.02 / output ¥2.0(CNY/1M)。
		return new LlmProperties(active, Map.of("deepseek-v4-flash",
				new LlmProperties.Provider("DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash",
						"DEEPSEEK_API_KEY", false, 1_000_000L,
						new LlmProperties.Price(1.0, 0.02, 2.0))));
	}

	private QuotaService service(QuotaProperties props, String active, MutableClock clock) {
		return new QuotaService(props, llm(active), tmp.toString(), mapper, clock);
	}

	private static QuotaProperties props(double daily, double monthly, int initLimit, int turnLimit) {
		return new QuotaProperties(daily, monthly, initLimit, turnLimit);
	}

	// ── ¥ 记账精度(price 三段)──────────────────────────────────────────

	@Test
	void recordAccruesCnyByThreePriceSegments() {
		QuotaService q = service(props(6, 175, 10, 300), "deepseek-v4-flash", new MutableClock());
		// 线上真实形态:hit 2048 / miss 2100 / completion 500。
		q.record(new LlmUsage(4148, 500, 4648, 2048, 2100));
		double expected = (2048 * 0.02 + 2100 * 1.0 + 500 * 2.0) / 1_000_000.0;
		assertThat(q.daySpentCny()).isEqualTo(expected);
		assertThat(q.monthSpentCny()).isEqualTo(expected);
	}

	@Test
	void missingCacheMissFallsBackToPromptMinusHit() {
		QuotaService q = service(props(6, 175, 10, 300), "deepseek-v4-flash", new MutableClock());
		// 方言缺 cacheMiss(-1):miss = prompt(1000) − hit(300) = 700。
		q.record(new LlmUsage(1000, 200, -1, 300, -1));
		assertThat(q.monthSpentCny()).isEqualTo((300 * 0.02 + 700 * 1.0 + 200 * 2.0) / 1_000_000.0);
	}

	@Test
	void allMissingFieldsRecordZeroWithoutThrowing() {
		QuotaService q = service(props(6, 175, 10, 300), "deepseek-v4-flash", new MutableClock());
		q.record(new LlmUsage(-1, -1, -1, -1, -1));
		q.record(null); // mock 天然免疫
		assertThat(q.monthSpentCny()).isZero();
	}

	// ── 软闸四路(谁先撞谁拦;放行即计数、拒绝不计)──────────────────────────

	@Test
	void initPerIpLimitBlocksAtThresholdAndOtherIpUnaffected() {
		QuotaService q = service(props(6, 175, 2, 300), "deepseek-v4-flash", new MutableClock());
		ClientKey a = new ClientKey("1.2.3.4", null);
		assertThat(q.checkInit(a).allowed()).isTrue();
		assertThat(q.checkInit(a).allowed()).isTrue();
		Decision third = q.checkInit(a);
		assertThat(third.allowed()).isFalse();
		assertThat(third.message()).contains("明天再来");
		assertThat(q.checkInit(new ClientKey("5.6.7.8", null)).allowed()).isTrue(); // 另一 IP 不受累
	}

	@Test
	void deviceKeyBlocksEvenWhenIpRotates() {
		QuotaService q = service(props(6, 175, 2, 300), "deepseek-v4-flash", new MutableClock());
		assertThat(q.checkInit(new ClientKey("1.1.1.1", "dev-A")).allowed()).isTrue();
		assertThat(q.checkInit(new ClientKey("2.2.2.2", "dev-A")).allowed()).isTrue();
		// IP 轮换但 deviceId 撞顶:谁先撞谁拦。
		assertThat(q.checkInit(new ClientKey("3.3.3.3", "dev-A")).allowed()).isFalse();
	}

	@Test
	void turnLimitIsIndependentFromInitLimit() {
		QuotaService q = service(props(6, 175, 1, 2), "deepseek-v4-flash", new MutableClock());
		ClientKey k = new ClientKey("1.2.3.4", null);
		assertThat(q.checkInit(k).allowed()).isTrue();
		assertThat(q.checkInit(k).allowed()).isFalse(); // init 顶 1
		assertThat(q.checkTurn(k).allowed()).isTrue(); // turn 独立计数
		assertThat(q.checkTurn(k).allowed()).isTrue();
		assertThat(q.checkTurn(k).allowed()).isFalse(); // turn 顶 2
	}

	@Test
	void nullOrBlankKeyPassesSoftGate() {
		QuotaService q = service(props(6, 175, 1, 1), "deepseek-v4-flash", new MutableClock());
		assertThat(q.checkInit(null).allowed()).isTrue();
		assertThat(q.checkInit(null).allowed()).isTrue(); // 无键不计数、不拦(全局 ¥ 闸仍在)
		assertThat(q.checkInit(new ClientKey("", null)).allowed()).isTrue();
	}

	// ── 真闸(全局 ¥ 双顶)───────────────────────────────────────────────

	@Test
	void dailyBudgetBlocksBothInitAndTurn() {
		QuotaService q = service(props(0.001, 175, 10, 300), "deepseek-v4-flash", new MutableClock());
		q.record(new LlmUsage(0, 1000, 1000, 0, 0)); // 1000 output = ¥0.002 ≥ 日限 0.001
		assertThat(q.checkInit(new ClientKey("1.2.3.4", null)).allowed()).isFalse();
		Decision turn = q.checkTurn(new ClientKey("1.2.3.4", null));
		assertThat(turn.allowed()).isFalse();
		assertThat(turn.message()).contains("今日");
	}

	@Test
	void monthlyCapOutlivesDayRollover() {
		MutableClock clock = new MutableClock();
		QuotaService q = service(props(6, 0.001, 10, 300), "deepseek-v4-flash", clock);
		q.record(new LlmUsage(0, 1000, 1000, 0, 0)); // ¥0.002 ≥ 月顶 0.001
		assertThat(q.checkInit(new ClientKey("1.2.3.4", null)).allowed()).isFalse();
		clock.instant = clock.instant.plus(Duration.ofDays(1)); // 跨日:日额度重置,月顶仍拦
		Decision d = q.checkInit(new ClientKey("1.2.3.4", null));
		assertThat(d.allowed()).isFalse();
		assertThat(d.message()).contains("本月");
	}

	// ── 北京时间跨日重置 ─────────────────────────────────────────────────

	@Test
	void dayBoundaryIsBeijingMidnightNotUtc() {
		MutableClock clock = new MutableClock();
		clock.instant = Instant.parse("2026-07-22T15:59:00Z"); // 北京 23:59
		QuotaService q = service(props(6, 175, 1, 300), "deepseek-v4-flash", clock);
		ClientKey k = new ClientKey("1.2.3.4", null);
		assertThat(q.checkInit(k).allowed()).isTrue();
		assertThat(q.checkInit(k).allowed()).isFalse(); // 当日撞顶

		clock.instant = Instant.parse("2026-07-22T16:01:00Z"); // 仍是 UTC 7-22,但北京已过 0 点
		assertThat(q.checkInit(k).allowed()).isTrue(); // 北京跨日 → 计数重置
	}

	@Test
	void daySpendResetsOnRolloverWhileMonthAccrues() {
		MutableClock clock = new MutableClock();
		QuotaService q = service(props(6, 175, 10, 300), "deepseek-v4-flash", clock);
		q.record(new LlmUsage(0, 1000, 1000, 0, 0));
		assertThat(q.daySpentCny()).isGreaterThan(0);
		clock.instant = clock.instant.plus(Duration.ofDays(1));
		assertThat(q.daySpentCny()).isZero(); // 日归零
		assertThat(q.monthSpentCny()).isGreaterThan(0); // 月累计不动
	}

	// ── 月累计落盘与回载(跨重启守住)───────────────────────────────────────

	@Test
	void monthlySpendSurvivesRestartViaAtomicFile() {
		MutableClock clock = new MutableClock();
		QuotaService q1 = service(props(6, 175, 10, 300), "deepseek-v4-flash", clock);
		q1.record(new LlmUsage(0, 1000, 1000, 0, 0));
		double spent = q1.monthSpentCny();
		assertThat(tmp.resolve("quota-2026-07.json")).exists();
		assertThat(tmp.resolve("quota-2026-07.json.tmp")).doesNotExist(); // 原子写不留 tmp

		QuotaService q2 = service(props(6, 175, 10, 300), "deepseek-v4-flash", clock); // 模拟重启
		assertThat(q2.monthSpentCny()).isEqualTo(spent);
	}

	@Test
	void newMonthStartsFreshLedger() {
		MutableClock clock = new MutableClock();
		QuotaService q = service(props(6, 0.001, 10, 300), "deepseek-v4-flash", clock);
		q.record(new LlmUsage(0, 1000, 1000, 0, 0));
		assertThat(q.checkInit(new ClientKey("1.2.3.4", null)).allowed()).isFalse(); // 月顶拦
		clock.instant = Instant.parse("2026-08-01T04:00:00Z"); // 新月
		assertThat(q.monthSpentCny()).isZero();
		assertThat(q.checkInit(new ClientKey("1.2.3.4", null)).allowed()).isTrue();
	}

	// ── mock 豁免 ──────────────────────────────────────────────────────

	@Test
	void mockActiveExemptsCountingAndGates() {
		QuotaService q = service(props(0, 0, 0, 0), "mock", new MutableClock()); // 全阈值 0 也放行
		ClientKey k = new ClientKey("1.2.3.4", "dev-A");
		assertThat(q.checkInit(k).allowed()).isTrue();
		assertThat(q.checkTurn(k).allowed()).isTrue();
	}
}
