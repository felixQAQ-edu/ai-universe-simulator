package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

/**
 * ADR-036 Slice C ·《派单》world-gen 指令槽。钉:
 * <ol>
 * <li>既有六个单体世界与三个融合组合的 world-gen 提示词,除末尾随机种子行外逐字节不变
 *     (快照取自 Slice C 之前的 {@code main} {@code 3e72099},见 {@code golden/world-gen-prompts/});</li>
 * <li>cyberpunk 的提示词含规则数量、来源前缀、7 条结局池、优先序与世界名行;种子池两种开局身份都有;</li>
 * <li>禁用词(卡面名除外)不出现在给模型的正向文字里 —— 只允许出现在「【世界名】」一条与「【禁用词 · 本世界】」一段;</li>
 * <li>lockstep:{@code prompts/world-gen.md} 逐字收录指令槽全文与六条种子。</li>
 * </ol>
 *
 * <p><b>快照怎么更新</b>:若以后有意改动既有世界的 world-gen 提示词,在改动前的提交上把
 * {@code buildWorldPrompt(...)} 截到最后一个「场景种子:」之前的部分写回对应文件 —— 快照只该随有意的改动变。
 */
class PaidanWorldGenTest {

	private static final String SEED_MARK = "场景种子:";

	private final WorldGenPromptBuilder builder = new WorldGenPromptBuilder(new ArchetypeRegistry());

	/** 快照文件名 → 有序 archetype 列表。 */
	private static final Map<String, List<String>> SNAPSHOTS = new LinkedHashMap<>();
	static {
		for (String id : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim", "animal_life")) {
			SNAPSHOTS.put(id, List.of(id));
		}
		SNAPSHOTS.put("cultivation_x_rules_creepy", List.of("cultivation", "rules_creepy"));
		SNAPSHOTS.put("rules_creepy_x_apocalypse", List.of("rules_creepy", "apocalypse"));
		SNAPSHOTS.put("cultivation_x_cthulhu", List.of("cultivation", "cthulhu"));
	}

	private static final String[] BANNED = { "赛博", "反乌托邦", "巨型企业", "资本", "剥削", "打工人", "骑手", "众包",
			"联合", "串联", "罢单", "维权", "讨薪", "投诉", "上访", "集体停工", "拆迁", "清退", "整治", "驱赶", "暂住",
			"户籍", "执法", "%" };

	// ① 既有世界除种子行外逐字节不变 ─────────────────────────────────────

	@Test
	void existingWorldGenPromptsAreByteIdenticalExceptTheSeedLine() throws IOException {
		assertThat(SNAPSHOTS).hasSize(9);
		for (Map.Entry<String, List<String>> e : SNAPSHOTS.entrySet()) {
			String expected = resource("golden/world-gen-prompts/" + e.getKey() + ".txt");
			for (int i = 0; i < 5; i++) { // 种子随机:多抽几次,前缀都不变
				String prompt = builder.buildWorldPrompt(e.getValue());
				int cut = prompt.lastIndexOf(SEED_MARK);
				assertThat(prompt.substring(0, cut)).as(e.getKey()).isEqualTo(expected);
				assertThat(prompt.substring(cut + SEED_MARK.length())).as(e.getKey() + " 种子行").isNotBlank()
						.doesNotContain("\n");
			}
		}
	}

	// ② cyberpunk 的提示词内容 ────────────────────────────────────────────

	@Test
	void paidanPromptCarriesRulesEndingsAndWorldName() {
		String p = builder.buildWorldPrompt("cyberpunk");
		// 骨架派生槽:结局条数 7。
		assertThat(p).contains("- endings:7 个,").contains("endings 恰好 7 条");
		// 规则数量与来源前缀。
		assertThat(p).contains("【规则】6–8 条,真(isTrue:true)至少 3 条,假(isTrue:false)至少 2 条")
				.contains("「【服务守则】」(平台条款口吻)或「【群里说】」(跑腿员口耳相传的口吻),两种来源各至少 2 条")
				.contains("至少一条真规则与画像有关")
				.contains("每条假规则的 hiddenLogic 写一个可识破的迹象")
				.contains("群里流传一个工号,所有人的时限都照他算,没人见过他收工");
		// 结局池 7 条:id · 标题 · 极性按决策 7。
		assertThat(p).contains("1) rent_paid · 交上了 · success:第三夜收工时,体力大于 0,余钱 ≥ 80。")
				.contains("2) kept_the_pace · 卡点的人 · success:第三夜收工时,体力大于 0,余钱 ≥ 60 且画像 ≤ 40。")
				.contains("3) passed_it_on · 把话传下去 · success:体力大于 0;已识破至少 2 条以「【群里说】」开头、"
						+ "且确实不成立的规则;已在叙事中把其中至少一条当面告诉了另一个跑腿员;余钱 ≥ 50。")
				.contains("4) collapsed_in_stairwell · 倒在楼道里 · failure:体力归零。")
				.contains("5) dispatch_stopped · 停派 · failure:评分归零。")
				.contains("6) became_benchmark · 成了标杆 · failure:condition 写「画像 ≥ 95」")
				.contains("「已为你优化时限。」被推给一个新来的跑腿员")
				.contains("7) almost · 差一点 · neutral:第三夜收工时,以上结局都不满足。")
				.contains("好结局重叠时的优先序:把话传下去 > 卡点的人 > 交上了 > 差一点。");
		// 世界名行与模式标签。
		assertThat(p).contains("这个世界叫《派单》:world.title 必须恰为「派单」")
				.contains("提示词里的模式名「赛博朋克」只是分类标签,玩家可见的任何字段");
		// 开局与初值。
		assertThat(p).contains("头盔显示屏上弹出第一单,房租还差一截")
				.contains("体力 70–85;余钱 15–30;评分 50–70;画像:新号 5–15,老号 40–50。");
		// Slice C 订正后的 worldview。
		assertThat(p).contains("三个夜班之后要交房租").doesNotContain("月底要交房租")
				.contains("正文不要复述「被看见、被学会、被替代」这类设计用语,只写具体发生的事。");
	}

	@Test
	void paidanSeedPoolHasBothStartingIdentities() {
		Set<String> seeds = paidanSeeds();
		assertThat(seeds).hasSize(6);
		assertThat(seeds.stream().filter(s -> s.contains("开局身份:新号"))).hasSize(3);
		assertThat(seeds.stream().filter(s -> s.contains("开局身份:老号"))).hasSize(3);
		assertThat(seeds).allSatisfy(s -> assertThat(s).matches(".*危险等级 (low|medium|high|extreme)$"));
	}

	// ③ 禁用词不出现在正向文字里 ──────────────────────────────────────────

	@Test
	void bannedWordsAppearOnlyInTheWorldNameLineAndTheBannedList() {
		for (String seed : paidanSeeds()) {
			assertNoBanned("种子:" + seed, seed);
		}
		String p = builder.buildWorldPrompt("cyberpunk");
		String positive = positiveText(p);
		assertNoBanned("《派单》world-gen 提示词正向文字", positive);
		// 反向对照:剔掉的两处确实是禁用词所在(证明上面的「没有」不是因为什么都没剔或剔多了)。
		assertThat(p).contains("· 行业称谓:骑手、众包(只用「跑腿员」);");
		assertThat(positive).contains("【规则】").contains("【结局池】").contains("【题材边界】").contains("【开局】");
	}

	// ④ lockstep:prompts/world-gen.md 逐字收录 ────────────────────────────

	@Test
	void worldGenMdCarriesTheDirectiveAndSeedsVerbatim() throws IOException {
		String md = Files.readString(repoRoot().resolve("prompts/world-gen.md"));
		String directive = WorldGenPromptBuilder.PAIDAN_WORLD_GEN_DIRECTIVE.strip();
		assertThat(md).contains("```\n" + directive + "\n```");
		for (String seed : paidanSeeds()) {
			assertThat(md).as("种子").contains(seed);
		}
	}

	// ── helpers ─────────────────────────────────────────────────────────

	/** 剔掉「【世界名】」一条、「【禁用词 · 本世界】」一段、模式块标题与种子区的「模式:」行,余下即正向文字。 */
	private static String positiveText(String prompt) {
		List<String> kept = new ArrayList<>();
		boolean inBannedBlock = false;
		for (String line : prompt.split("\n", -1)) {
			if (line.startsWith("- 【禁用词 · 本世界】")) {
				inBannedBlock = true;
				continue;
			}
			if (inBannedBlock && line.startsWith("  · ")) {
				continue;
			}
			inBannedBlock = false;
			if (line.startsWith("- 【世界名】") || line.equals("【本模式 · 赛博朋克】")
					|| line.startsWith("模式:赛博朋克(单体,archetype=cyberpunk)")) {
				continue;
			}
			kept.add(line);
		}
		return String.join("\n", kept);
	}

	private static void assertNoBanned(String where, String text) {
		for (String w : BANNED) {
			assertThat(text).as(where + " 含禁用词「" + w + "」").doesNotContain(w);
		}
	}

	/** 反复抽种子,收齐 cyberpunk 种子池(6 条;抽 300 次漏掉任何一条的概率可忽略)。 */
	private Set<String> paidanSeeds() {
		Set<String> seeds = new LinkedHashSet<>();
		for (int i = 0; i < 300; i++) {
			String p = builder.buildWorldPrompt("cyberpunk");
			seeds.add(p.substring(p.lastIndexOf(SEED_MARK) + SEED_MARK.length()));
		}
		return seeds;
	}

	private static String resource(String path) throws IOException {
		try (InputStream in = PaidanWorldGenTest.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static Path repoRoot() {
		Path p = Path.of("").toAbsolutePath();
		while (p != null && !Files.exists(p.resolve("prompts/world-gen.md"))) {
			p = p.getParent();
		}
		assertThat(p).as("找不到仓库根(prompts/world-gen.md)").isNotNull();
		return p;
	}
}
