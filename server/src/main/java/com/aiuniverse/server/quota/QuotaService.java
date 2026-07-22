package com.aiuniverse.server.quota;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aiuniverse.server.llm.LlmProperties;
import com.aiuniverse.server.llm.LlmUsage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link QuotaGate} 的生产实现(ADR-016):双层闸门。
 *
 * <ul>
 *   <li><b>真闸(全局 ¥ 双顶)</b>:日限额 + 月顶;单价<b>只读</b> {@code aiuniverse.llm.providers.<active>.price}
 *       三段(ADR-001 配置表,跟官方价走、改配置不改码),按 usage 块(cacheHit/cacheMiss/output)计价累加。</li>
 *   <li><b>软闸(单键次数)</b>:{init, turn} × {ip, deviceId} 四路日计数,任一键任一路先到阈值即拦;
 *       放行时 +1(拒绝不计)。</li>
 * </ul>
 *
 * <p><b>日界线 = 北京时间</b>(Asia/Shanghai;目标用户在国内,与「明天再来」体感一致,不用 syd 机器时区)。
 * <b>重启语义</b>:日态(计数 + 当日 ¥)纯内存,deploy 清零 = 宽松方向(只多放行不误拦);月累计落盘
 * {@code quota-YYYY-MM.json}(与会话同目录,线上 = /data 卷)跨重启守住,写盘照抄
 * {@link com.aiuniverse.server.persistence.FileSessionStore} 原子写 + best-effort 不抛口径。
 *
 * <p><b>mock 豁免</b>:{@code aiuniverse.llm.active=mock} 时判定全放行、不计数(¥ 记账本就无 usage 块
 * 天然免疫)——两阶段冒烟 mock 段与本地开发不受闸门影响。
 *
 * <p>并发:方法全 synchronized(软启动量级,回合本身秒级,锁竞争可忽略)。
 */
@Service
public class QuotaService implements QuotaGate {

	private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

	static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
	private static final double PER_MILLION = 1_000_000.0;

	private final QuotaProperties props;
	private final LlmProperties llm;
	private final Path dir;
	private final ObjectMapper mapper;
	private final Clock clock;

	// ── 日态(内存,deploy 清零 = 宽松方向)──────────────────────────────
	private String day = "";
	private double daySpentCny = 0;
	/** key = "init|ip:1.2.3.4" / "turn|dev:<uuid>" → 当日计数(guarded by this)。 */
	private final Map<String, Integer> dayCounters = new HashMap<>();

	// ── 月态(落盘跨重启)────────────────────────────────────────────
	private String month = "";
	private double monthSpentCny = 0;

	@Autowired
	public QuotaService(QuotaProperties props, LlmProperties llm,
			@Value("${aiuniverse.session.store-dir:./data}") String storeDir, ObjectMapper mapper) {
		this(props, llm, storeDir, mapper, Clock.system(BEIJING));
	}

	/** 测试用:可注入时钟(跨日/跨月推进)。 */
	public QuotaService(QuotaProperties props, LlmProperties llm, String storeDir, ObjectMapper mapper, Clock clock) {
		this.props = props;
		this.llm = llm;
		this.mapper = mapper;
		this.clock = clock;
		this.dir = Path.of(storeDir).toAbsolutePath().normalize();
		synchronized (this) {
			rollover(); // 启动即回载当月累计
		}
	}

	// ── 判定(入口,LLM 调用前;放行即计数)──────────────────────────────

	@Override
	public synchronized Decision checkInit(ClientKey key) {
		return check("init", key, props.initPerKeyDaily(), "今日新世界名额已满,明天再来");
	}

	@Override
	public synchronized Decision checkTurn(ClientKey key) {
		return check("turn", key, props.turnsPerKeyDaily(), "今日回合名额已满,明天再来");
	}

	private Decision check(String kind, ClientKey key, int perKeyLimit, String softMessage) {
		if (mockActive()) {
			return Decision.ALLOW; // mock 豁免:判定全放行、不计数
		}
		rollover();
		// 真闸:全局 ¥ 双顶(日先于月——日先触顶时给「明天再来」而非「下月再来」)。
		if (daySpentCny >= props.dailyBudgetCny()) {
			return Decision.deny("今日体验名额已满,明天再来");
		}
		if (monthSpentCny >= props.monthlyBudgetCny()) {
			return Decision.deny("本月体验名额已满,下月再来");
		}
		// 软闸:双键四路,谁先撞谁拦;先全查再计数(拒绝不计)。
		String ipKey = key == null || key.ip() == null || key.ip().isBlank() ? null : kind + "|ip:" + key.ip();
		String devKey = key == null || key.deviceId() == null || key.deviceId().isBlank() ? null
				: kind + "|dev:" + key.deviceId();
		if (exceeds(ipKey, perKeyLimit) || exceeds(devKey, perKeyLimit)) {
			return Decision.deny(softMessage);
		}
		bump(ipKey);
		bump(devKey);
		return Decision.ALLOW;
	}

	private boolean exceeds(String counterKey, int limit) {
		return counterKey != null && dayCounters.getOrDefault(counterKey, 0) >= limit;
	}

	private void bump(String counterKey) {
		if (counterKey != null) {
			dayCounters.merge(counterKey, 1, Integer::sum);
		}
	}

	// ── ¥ 记账(usage 收口处旁挂,LLM 调用后)─────────────────────────────

	@Override
	public synchronized void record(LlmUsage usage) {
		if (usage == null) {
			return; // mock / provider 未回 usage 块:天然免疫
		}
		rollover();
		double cost = costCny(usage);
		daySpentCny += cost;
		monthSpentCny += cost;
		persistMonth(); // best-effort,绝不抛(账继续活在内存,同 FileSessionStore.persist 口径)
	}

	/**
	 * 按 active provider 的 price 三段计价(CNY/1M tokens)。缺失字段(-1 口径)保守回退:
	 * cacheMiss 缺失时用 prompt − hit 兜(方言差异),全缺记 0——记账是增值服务,绝不因缺字段抛错。
	 */
	double costCny(LlmUsage usage) {
		LlmProperties.Price price = activePrice();
		if (price == null) {
			return 0; // 无 price 配置的 provider(如 mock 条目缺失):不记账
		}
		long hit = Math.max(usage.cacheHitTokens(), 0);
		long miss = usage.cacheMissTokens() >= 0 ? usage.cacheMissTokens()
				: Math.max(Math.max(usage.promptTokens(), 0) - hit, 0);
		long out = Math.max(usage.completionTokens(), 0);
		return (hit * price.inputCacheHit() + miss * price.inputCacheMiss() + out * price.output()) / PER_MILLION;
	}

	private LlmProperties.Price activePrice() {
		LlmProperties.Provider provider = llm.providers() == null ? null : llm.providers().get(llm.active());
		return provider == null ? null : provider.price();
	}

	private boolean mockActive() {
		return "mock".equals(llm.active());
	}

	// ── 日/月滚动(北京时间)与月累计落盘 ─────────────────────────────────

	private void rollover() {
		// 时钟只当 instant 源消费(不依赖其 zone),北京日界线在这里换算——测试注入假时钟零歧义。
		LocalDate today = clock.instant().atZone(BEIJING).toLocalDate();
		String d = today.toString();
		if (!d.equals(day)) {
			day = d;
			daySpentCny = 0;
			dayCounters.clear();
		}
		String m = YearMonth.from(today).toString();
		if (!m.equals(month)) {
			month = m;
			monthSpentCny = loadMonth(m);
		}
	}

	private Path monthFile(String m) {
		return dir.resolve("quota-" + m + ".json");
	}

	/** 月累计回载:文件不存在 → 0(新月);损坏 → WARN + 0(宽松方向,保留原文件留尸检)。 */
	private double loadMonth(String m) {
		Path file = monthFile(m);
		if (!Files.exists(file)) {
			return 0;
		}
		try {
			JsonNode doc = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
			double spent = doc.path("spentCny").asDouble(0);
			log.info("[quota] 月累计回载 {} = ¥{}", m, spent);
			return spent;
		} catch (Exception e) {
			log.warn("[quota] 月累计文件损坏,按 0 起账(宽松方向,保留原文件留尸检):{} — {}", file, e.toString());
			return 0;
		}
	}

	/** 原子写(照抄 FileSessionStore):tmp + ATOMIC_MOVE,失败回退普通 move;任何异常只记日志。 */
	private void persistMonth() {
		try {
			Files.createDirectories(dir);
			ObjectNode doc = mapper.createObjectNode();
			doc.put("month", month);
			doc.put("spentCny", monthSpentCny);
			Path tmp = dir.resolve("quota-" + month + ".json.tmp");
			Path target = monthFile(month);
			Files.writeString(tmp, mapper.writeValueAsString(doc), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException atomicUnsupported) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			log.error("[quota] 月累计写盘失败(账继续活在内存,重启丢当月增量):{}", e.toString());
		}
	}

	// ── 观测读数(日志/测试)────────────────────────────────────────────

	public synchronized double daySpentCny() {
		rollover();
		return daySpentCny;
	}

	public synchronized double monthSpentCny() {
		rollover();
		return monthSpentCny;
	}
}
