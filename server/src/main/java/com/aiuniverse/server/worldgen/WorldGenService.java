package com.aiuniverse.server.worldgen;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
		ObjectNode parsed = tryParse(archetypes, raw, errors);
		if (parsed != null) {
			return FixedWorldTitle.apply(archetypes, prompts.registry(), EndingOrder.apply(archetypes, parsed));
		}

		// 一次修复(设计稿 §4.3):带校验错误回喂「只回修正后的完整 world JSON」,同样开 json_object。
		log.warn("[world-gen] archetypes={} 首次产出未过校验({} 条),触发一次修复:{}", archetypes, errors.size(), errors);
		String repairPrompt = prompts.buildRepairPrompt(archetypes, raw, errors);
		repaired[0] = true;
		String raw2 = call(repairPrompt);

		List<String> errors2 = new ArrayList<>();
		ObjectNode parsed2 = tryParse(archetypes, raw2, errors2);
		if (parsed2 != null) {
			return FixedWorldTitle.apply(archetypes, prompts.registry(), EndingOrder.apply(archetypes, parsed2));
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

	/**
	 * {@link LooseJson} 解析 + {@code validateWorld} + 世界专属检查({@link #worldSpecificErrors});
	 * 通过返回节点,否则把错误写入 {@code out} 返 null。
	 */
	private ObjectNode tryParse(List<String> archetypes, String raw, List<String> out) {
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
		List<String> specific = worldSpecificErrors(archetypes, parsed);
		if (!specific.isEmpty()) {
			out.addAll(specific);
			return null;
		}
		return (ObjectNode) parsed;
	}

	/**
	 * 世界专属的 world-gen 检查,在通用 {@code validateWorld} 通过之后跑;命中即视为校验失败、走现有的一次修复。
	 * 通用校验器一字不改(ADR-008 校验器对 archetype 无知;ADR-009 {@code isTrue} 可选),其他世界在这里永远返回空表。
	 *
	 * <p>《派单》(ADR-036「订正(2026-11-10 二)」订正 2):每条规则都必须带布尔 {@code isTrue} ——
	 * 结局核对数「已识破的假【群里说】」要靠它,冒烟里 3 局有 2 局整表缺失。只对单体《派单》生效。
	 *
	 * <p>按 archetype 分派(ADR-038 勘察 7):单体 {@code cyberpunk} → 《派单》原有检查,原样不动;
	 * 单体 {@code time_travel} → 《七日开张》三项({@link #qiriErrors});其余(含任何融合局)→ 空表。
	 */
	static List<String> worldSpecificErrors(List<String> archetypes, JsonNode world) {
		if (archetypes.equals(List.of("cyberpunk"))) {
			return paidanErrors(world);
		}
		if (archetypes.equals(List.of("time_travel"))) {
			return qiriErrors(world);
		}
		return List.of();
	}

	/** 《派单》(ADR-036 订正二 / 三):每条规则带布尔 isTrue + 系统台词只在「成了标杆」的 description。 */
	private static List<String> paidanErrors(JsonNode world) {
		List<String> errors = new ArrayList<>();
		JsonNode rules = world.path("rules");
		for (int i = 0; i < rules.size(); i++) {
			if (!rules.get(i).path("isTrue").isBoolean()) {
				errors.add("rules[" + i + "].isTrue: 本世界每条规则都必须带布尔 isTrue(真规则 true、假规则 false)");
			}
		}
		systemLineErrors(world, errors);
		return errors;
	}

	/** 《七日开张》四个固定结局 id(ADR-038 决策 4 / 审定 11),world-gen 检查要求全部在场。 */
	static final List<String> QIRI_ENDING_IDS = List.of("night_market_top", "odd_idea_king", "open_as_usual",
			"taoist_summoned");

	/** 《七日开张》真、假规矩各自的下限(ADR-038 决策 5 / 审定 7)。 */
	static final int QIRI_MIN_EACH_TRUTH = 3;

	/**
	 * 《七日开张》世界专属检查(ADR-038 决策 5 / 审定 7),三项:
	 * <ol>
	 * <li>每条规则的 {@code isTrue} 必须是布尔(照搬《派单》订正二);</li>
	 * <li>四个固定结局 id 都在 —— 结局核对与引擎 {@code pickFailureEnding} 都要落到它们(勘察 13);</li>
	 * <li>{@code isTrue:true} 与 {@code isTrue:false} 各 ≥ 3 条。</li>
	 * </ol>
	 * <b>不查</b>恰好 8 条、4 真 4 假(提示词里仍要求):修复仍败 = 整局 ERROR,凑数要求不值得让玩家开不了局(已知代价 8)。
	 * 「历史不作数」那条是措辞判断,不做程序检查。
	 */
	private static List<String> qiriErrors(JsonNode world) {
		List<String> errors = new ArrayList<>();
		JsonNode rules = world.path("rules");
		int trueCount = 0;
		int falseCount = 0;
		for (int i = 0; i < rules.size(); i++) {
			JsonNode isTrue = rules.get(i).path("isTrue");
			if (!isTrue.isBoolean()) {
				errors.add("rules[" + i + "].isTrue: 本世界每条规矩都必须带布尔 isTrue(真规矩 true、假规矩 false)");
			} else if (isTrue.asBoolean()) {
				trueCount++;
			} else {
				falseCount++;
			}
		}
		if (trueCount < QIRI_MIN_EACH_TRUTH) {
			errors.add("rules: isTrue:true 的规矩只有 " + trueCount + " 条,本世界至少 " + QIRI_MIN_EACH_TRUTH + " 条(要求恰好 8 条、4 真 4 假)");
		}
		if (falseCount < QIRI_MIN_EACH_TRUTH) {
			errors.add("rules: isTrue:false 的规矩只有 " + falseCount + " 条,本世界至少 " + QIRI_MIN_EACH_TRUTH + " 条(要求恰好 8 条、4 真 4 假)");
		}
		Set<String> ids = new HashSet<>();
		world.path("endings").forEach(e -> ids.add(e.path("id").asString("")));
		for (String id : QIRI_ENDING_IDS) {
			if (!ids.contains(id)) {
				errors.add("endings: 缺少固定结局 " + id + "(本世界 endings 恰好 4 条:" + String.join(" / ", QIRI_ENDING_IDS) + ")");
			}
		}
		return errors;
	}

	/** 《派单》唯一的系统台词(ADR-036 决策 8)。 */
	static final String PAIDAN_SYSTEM_LINE = "已为你优化时限";

	/**
	 * ADR-036「订正(2026-11-10 三)」订正 2:系统台词只允许出现在 {@code became_benchmark} 的 {@code description} 里。
	 * 查玩家可见的文字:world 的 title / background / tone、rules[].content、openingNarrative、availableActions[].text / hint,
	 * 以及其他结局的 title / condition / description(和「成了标杆」的 title / condition)。
	 * {@code hiddenLogic} 不查(玩家不可见,规则的真实判定可能需要提到它)。匹配不带句号,改了标点也算。
	 */
	private static void systemLineErrors(JsonNode world, List<String> errors) {
		for (String f : List.of("title", "background", "tone")) {
			checkSystemLine("world." + f, world.path("world").path(f), errors);
		}
		JsonNode rules = world.path("rules");
		for (int i = 0; i < rules.size(); i++) {
			checkSystemLine("rules[" + i + "].content", rules.get(i).path("content"), errors);
		}
		checkSystemLine("openingNarrative", world.path("openingNarrative"), errors);
		JsonNode actions = world.path("availableActions");
		for (int i = 0; i < actions.size(); i++) {
			checkSystemLine("availableActions[" + i + "].text", actions.get(i).path("text"), errors);
			checkSystemLine("availableActions[" + i + "].hint", actions.get(i).path("hint"), errors);
		}
		JsonNode endings = world.path("endings");
		for (int i = 0; i < endings.size(); i++) {
			JsonNode e = endings.get(i);
			boolean benchmark = "became_benchmark".equals(e.path("id").asString(""));
			for (String f : List.of("title", "condition", "description")) {
				if (benchmark && "description".equals(f)) {
					continue;
				}
				checkSystemLine("endings[" + i + "]." + f, e.path(f), errors);
			}
		}
	}

	private static void checkSystemLine(String path, JsonNode node, List<String> errors) {
		if (node.isString() && node.asString().contains(PAIDAN_SYSTEM_LINE)) {
			errors.add(path + ": 「已为你优化时限。」只允许写在 became_benchmark 的 description 里,这里不得出现");
		}
	}
}
