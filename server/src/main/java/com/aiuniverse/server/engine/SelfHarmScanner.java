package com.aiuniverse.server.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 自伤方法词扫描 —— ADR-004 刀 3 的流末遥测判据。
 *
 * <p><b>它是一个纯函数,不是一层机制。</b>与 {@link LeakDetector} 同包并列(两个检测器住一起本身就是可读的信号),
 * <b>但刻意不与它共用宿主</b>:{@code LEAK_TOKENS} 不只是检测词表,它同时是 {@link Engine#toClientState()}
 * 的<b>递归剥除清单</b>;把方法词混进那张表,失效方式是「把『割腕』当字段名从 JSON 里静默剥掉」。
 * ADR-004 §五 那句「与 LEAK_TOKENS 完全同构,故宿主现成」<b>只看了形状没看消费方,已就地订正</b>。
 *
 * <p><b>语义口径:事后遥测,不是防线。</b>主防线是 prompt 侧的【绝境的写法 · 硬约束】(生成<b>之前</b>,
 * 五处骨架逐字节同串,由 {@code ContentSafetyPromptLockstepTest} 守)。本扫描器的唯一用途是
 * <b>把命中回喂那份硬禁的负面清单</b>——它值得存在的理由仅此一条。
 * <b>⚠️ 本类当前不接线</b>:不在 {@code Engine.apply} / {@code EventLoopService.settle} 里被调用,
 * 接不接 WARN 由基线语料的误报读数决定(ADR-004 §五)。
 *
 * <h2>判据:抓的是「可以照做的方法」,不是「角色想死」</h2>
 * 定稿禁的是「不展开具体<b>手段、剂量、器具、部位、操作顺序</b>或可复现流程」,故:
 * <ul>
 *   <li><b>不收事件与后果词</b>(自杀 / 自尽 / 轻生 / 了断)——
 *       ADR-010 冒烟里 AI 自给过的选项「闭目等死」<b>没有方法,按本判据正确地不命中</b>。</li>
 *   <li><b>不收他杀描写词</b>(割喉 / 颈动脉 / 动脉)—— 丧尸咬断颈动脉是末日与克苏鲁的日常笔法。</li>
 *   <li><b>不收单独的器具与物品名</b>(刀片 / 绳索 / 安眠药)—— 末日搜刮药箱、恐怖布景绳索都是正常内容;
 *       器具要连着动作才构成方法。</li>
 *   <li><b>⚠️ 绝不收 自爆 / 自焚 / 散功 / 燃烧修为</b> —— prompt 定稿<b>逐字显式许可</b>
 *       「在虚构世界内部主动承担牺牲、禁术、污染、散功、燃烧修为等代价」。
 *       收它们不是误报,是<b>词表与主防线正面自相矛盾</b>。</li>
 * </ul>
 *
 * <p><b>留下一个词的判据(Felix 2026-09-07 说死)</b>:
 * 「<b>在这四个已上线世界的正常笔法里几乎没有理由出现</b>」,<b>而不是</b>「它常用于自伤」——
 * 后者是词自身的属性,前者才是这个项目的判据。据此砍掉了 上吊(「上吊的房客」是规则怪谈标准桥段)、
 * 服毒(修仙服毒试药)、跳楼 / 卧轨(末日有铁轨),以及 投缳(几乎不会命中的词不是保护,是噪音)。
 *
 * <h2>⚠️ 匹配边界:中文字面精确子串,仅此而已</h2>
 * 不做大小写归一(纯中文,{@code toLowerCase} 在这张表上是<b>恒等变换</b>)、不做繁简转换、不做分词、
 * 不做同义扩展。<b>变体 / 谐音 / 拼音 / 繁体 / 拆字一律绕过</b>(ADR-004 已知代价 2)。
 * 在遥测语境下这可接受——<b>它本来就不是防线</b>——但<b>不得被当成防线引用</b>。
 */
public final class SelfHarmScanner {

	/**
	 * 自伤方法词表(5 条,Felix 2026-09-07 定稿)。逐词理由:
	 * <ul>
	 *   <li>{@code 吞下整瓶} —— 剂量 + 动作;正常叙事没有理由出现。最干净的一条。</li>
	 *   <li>{@code 手腕内侧} —— 部位<b>精确化</b>才构成操作细节(对照:单说「手腕」是常见描写,故不收)。</li>
	 *   <li>{@code 割腕} —— 中文里几乎专指自伤动作(割别人不这么说);最短的「部位 + 动作」形式。</li>
	 *   <li>{@code 割脉} —— 同上,更少见。</li>
	 *   <li>{@code 自缢} —— 「自」在词内自指。<b>⚠️ 本表风险最高的一条</b>:恐怖场景描写他人死因
	 *       (「梁上自缢」)会命中。<b>刀 4 语料若打中本表,第一个删的就是它。</b></li>
	 * </ul>
	 */
	public static final List<String> METHOD_TOKENS =
			List.of("吞下整瓶", "手腕内侧", "割腕", "割脉", "自缢");

	private SelfHarmScanner() {
	}

	/**
	 * 扫一段玩家可见文本,返回命中的方法词证据(空 = 干净)。
	 *
	 * <p>调用方决定喂哪几段——接线时 {@code narrative} 与 {@code availableActions[].text}/{@code .hint}
	 * 都该喂(⚠️ {@link LeakDetector} 今天<b>只扫 narrative、不扫选项文本</b>,那是一条既有缺口,不在本刀范围)。
	 *
	 * @param playerVisibleText 玩家可见文本;可为 null(视作空串)
	 */
	public static List<String> scan(String playerVisibleText) {
		List<String> hits = new ArrayList<>();
		String text = playerVisibleText == null ? "" : playerVisibleText;
		for (String tok : METHOD_TOKENS) {
			if (text.contains(tok)) {
				hits.add("出现自伤方法词 '" + tok + "'");
			}
		}
		return hits;
	}
}
