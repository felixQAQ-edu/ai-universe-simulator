package com.aiuniverse.server.worldgen;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aiuniverse.server.engine.GameSchemas;
import com.aiuniverse.server.engine.LooseJson;
import com.aiuniverse.server.llm.ChatRequest;
import com.aiuniverse.server.llm.LlmClient;
import com.aiuniverse.server.llm.LlmException;
import com.aiuniverse.server.llm.StreamSegmentDeadline;
import com.aiuniverse.server.llm.UsageCapture;
import com.aiuniverse.server.quota.QuotaGate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * INITIALIZING 胖调用(设计稿 §2/§4、ADR-007)——一发 world-gen 产出完整世界根对象,
 * {@code validateWorld} 校验,失败一次修复,仍败 → 整局 {@link WorldGenException ERROR}。
 *
 * <p><b>线上口径(异于回合)</b>:全程 {@code response_format: json_object}、纯 JSON、无哨兵。
 * 故无叙事回灌问题(narrative/openingNarrative 本就是 JSON 字段),解析直接 {@link LooseJson} 即可。
 *
 * <p><b>复用零改</b>:{@link GameSchemas#validateWorld}(第一批移植 + 8 golden accept-parity)、
 * {@link LooseJson}(容错解析)原样复用,本类只编排「胖调用 + 解析校验 + 一次修复 + ERROR」。
 *
 * <p><b>与回合降级的对照</b>:回合修复用尽 → 保守 no-op(守一局 ongoing);world-gen 修复用尽 →
 * ERROR(无前态可守,干净重来)。两套口径刻意不同(设计稿 §4)。
 */
@Service
public class WorldGenService {

	private static final Logger log = LoggerFactory.getLogger(WorldGenService.class);

	private final LlmClient llm;
	private final WorldGenPromptBuilder prompts;
	private final ObjectMapper mapper;
	private final QuotaGate quota;
	/**
	 * 段时限与 {@code durMs} 锚点共用的时钟(ADR-030 决策 4 / 已决 1)。注入而非内联读时钟——
	 * 房规同 {@code EventLoopService} / {@code QuotaService}:真实耗时是变量,不注入就测不了。
	 */
	private final Clock clock;
	/** 流式段总时长上界(ADR-030 决策 4 / 已决 2):主调用与修复调用各自一段。 */
	private final long segmentDeadlineMs;

	/** 无闸门形态(ADR-016 之前行为;既有测试调用点零改)。 */
	public WorldGenService(LlmClient llm, WorldGenPromptBuilder prompts, ObjectMapper mapper) {
		this(llm, prompts, mapper, QuotaGate.NOOP);
	}

	/** 缺省时限 + 系统时钟(既有构造调用点零改,同 {@code QuotaGate.NOOP} 的接缝形态)。 */
	public WorldGenService(LlmClient llm, WorldGenPromptBuilder prompts, ObjectMapper mapper, QuotaGate quota) {
		this(llm, prompts, mapper, quota, Clock.systemUTC(), WorldGenProperties.DEFAULT_SEGMENT_DEADLINE_MS);
	}

	/** 生产装配:时限取 {@code aiuniverse.world-gen.segment-deadline-ms}(env 可覆盖)。 */
	@Autowired
	public WorldGenService(LlmClient llm, WorldGenPromptBuilder prompts, ObjectMapper mapper, QuotaGate quota,
			WorldGenProperties props) {
		this(llm, prompts, mapper, quota, Clock.systemUTC(), props.segmentDeadlineMs());
	}

	/** 全参形态(测试注入假时钟与时限)。 */
	public WorldGenService(LlmClient llm, WorldGenPromptBuilder prompts, ObjectMapper mapper, QuotaGate quota,
			Clock clock, long segmentDeadlineMs) {
		this.llm = llm;
		this.prompts = prompts;
		this.mapper = mapper;
		this.quota = quota;
		this.clock = clock;
		this.segmentDeadlineMs = segmentDeadlineMs;
	}

	/**
	 * 跑一发 world-gen 胖调用,返回<b>已过 {@code validateWorld} 校验</b>的完整世界根对象
	 * (含 isTrue/hiddenLogic、availableActions、openingNarrative 等模型产出原貌;消毒/提取在播种层)。
	 *
	 * @param archetype Phase 1 固定 {@code rules_creepy}(透传以备多模式)
	 * @return 校验通过的世界根 {@link ObjectNode}
	 * @throws WorldGenException 调用失败,或一次修复后仍未过校验(整局 ERROR)
	 */
	public ObjectNode generate(String archetype) {
		return generate(List.of(archetype));
	}

	/**
	 * 跑一发 world-gen 胖调用(ADR-013:<b>有序 archetype 列表,host 在前</b>)。长度 1 → 单体、
	 * 长度 2 → 融合世界({@code mode:"hybrid"} + {@code archetypes:[两个]},内联融合、保 json_object 无哨兵)。
	 * 校验/修复/ERROR 管线与单体完全一致(融合不加失败面,守 ADR-007)。
	 */
	public ObjectNode generate(List<String> archetypes) {
		// ── world-gen 总耗时锚点(ADR-030 已决 1):成功、失败两个终点各一行 INFO,零行为改动。
		// 消费方写死:段时限 180 s 的复核(已决 2「先宽后收,收紧以 durMs 读数为依据」)。不打客户端 IP。
		long startedAtMs = clock.millis();
		boolean[] repaired = {false};
		try {
			ObjectNode world = generateOnce(archetypes, repaired);
			log.info("[world-gen] archetypes={} 成功 durMs={} repaired={}", archetypes,
					clock.millis() - startedAtMs, repaired[0]);
			return world;
		} catch (RuntimeException e) {
			// cause= 取 cause 链最内层的消息:WorldGenException 的 message 是给玩家的固定文案,
			// 真正的原因(段超时 / 网络 / 上游非 200)在 cause 里。不取它,日志分不开这几种失败,
			// ADR-030 重新审视条件第 1 条(识别误掐)就无从执行。只进日志,502 body 不变。
			log.info("[world-gen] archetypes={} 失败 durMs={} repaired={} reason={} cause={}", archetypes,
					clock.millis() - startedAtMs, repaired[0], e.getMessage(), rootCauseMessage(e));
			throw e;
		}
	}

	/** cause 链最内层的消息;没有 cause 就是 {@code e} 本身(防自引用环)。 */
	private static String rootCauseMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage();
	}

	private ObjectNode generateOnce(List<String> archetypes, boolean[] repaired) {
		String prompt = prompts.buildWorldPrompt(archetypes);
		String raw = call(prompt); // 主调用(开 json_object)

		List<String> errors = new ArrayList<>();
		ObjectNode parsed = tryParse(raw, errors);
		if (parsed != null) {
			return EndingOrder.apply(archetypes, parsed);
		}

		// 一次修复(设计稿 §4.3):带校验错误回喂「只回修正后的完整 world JSON」,同样开 json_object。
		log.warn("[world-gen] archetypes={} 首次产出未过校验({} 条),触发一次修复:{}", archetypes, errors.size(), errors);
		String repairPrompt = prompts.buildRepairPrompt(archetypes, raw, errors);
		repaired[0] = true;
		String raw2 = call(repairPrompt);

		List<String> errors2 = new ArrayList<>();
		ObjectNode parsed2 = tryParse(raw2, errors2);
		if (parsed2 != null) {
			return EndingOrder.apply(archetypes, parsed2);
		}

		// 修复仍败 → 整局 ERROR(设计稿 §4.4:无前态可守,不进半残 PLAYING)。
		log.error("[world-gen] 修复后仍未过校验({} 条),整局 ERROR:{}", errors2.size(), errors2);
		throw new WorldGenException("世界生成失败,请重新生成");
	}

	/**
	 * 胖调用:累积流式 token 成整串(world-gen 不逐字流给玩家,叙事随 init 一次性下发)。开 json_object。
	 *
	 * <p>每次调用一段流式时限(ADR-030 决策 4):主调用与修复调用各自独立计时。守卫在 {@link UsageCapture}
	 * 里层(ADR-024 立字 3);过线抛 {@link LlmException},落进下面既有的 catch → {@link WorldGenException}
	 * → 502,且 {@code GameInitService} 里 generate 在建 session 之前,故不建 session。
	 * ⚠️ 彻底静默(再无 token)不在保护范围内(ADR-030 已决 0),不许为此加看门线程。
	 */
	private String call(String prompt) {
		StringBuilder buf = new StringBuilder();
		UsageCapture usage = new UsageCapture(
				StreamSegmentDeadline.guard(buf::append, clock, segmentDeadlineMs, "本次开局作废"));
		try {
			llm.streamChat(new ChatRequest(prompt, true), usage);
		} catch (LlmException e) {
			// 调用本身失败(网络/非 200/缺 key/流中断)→ 无可修复内容 → 直接 ERROR(干净重来)。
			throw new WorldGenException("世界生成调用失败,请重新生成", e);
		}
		// usage 收口(ADR-016):INFO 观测 + ¥ 记账旁挂;无 usage 块(mock 等)静默跳过、天然免疫。
		if (usage.usage() != null) {
			log.info("[world-gen] usage {}", usage.logLine());
		}
		quota.record(usage.usage());
		return buf.toString();
	}

	/** {@link LooseJson} 解析 + {@code validateWorld};通过返回节点,否则把错误写入 {@code out} 返 null。 */
	private ObjectNode tryParse(String raw, List<String> out) {
		JsonNode parsed;
		try {
			parsed = LooseJson.parse(raw, mapper);
		} catch (LlmException e) {
			out.add("产出非合法 JSON:" + e.getMessage());
			return null;
		}
		if (!parsed.isObject()) {
			out.add("产出顶层非 JSON 对象");
			return null;
		}
		List<String> errors = GameSchemas.validateWorld(parsed);
		if (!errors.isEmpty()) {
			out.addAll(errors);
			return null;
		}
		return (ObjectNode) parsed;
	}
}
