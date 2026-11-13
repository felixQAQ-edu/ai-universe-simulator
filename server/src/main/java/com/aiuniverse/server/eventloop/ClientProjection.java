package com.aiuniverse.server.eventloop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;

import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 下发给前端的两项<b>展示层派生</b>(ADR-039):时段标签 {@code timeLabel} 与选项 {@code tag}。
 * 三处下发共用这里:{@code GameInitService.init} / {@code GameInitService.resume} / {@code EventLoopService.buildDelta}。
 *
 * <p><b>只属于视图 3</b>(CONTEXT §三.9)。两项都由已落盘的东西现算({@code state.turn}、{@code world.archetypes}、
 * 存档里的选项文字),<b>自己不落盘、不进引擎、不进提示词</b>,所以旧存档与旧局续局也拿得到,{@code schemaVersion} 不动。
 *
 * <p><b>剥前缀只发生在 {@link #actions} 这一个函数里</b>,而且改的是副本:{@code session.currentActions}、落盘文档、
 * 喂模型的选项文字一个字都不动 —— {@link QiriActionGuard#prefixOf} 读的是存档里的文字,ADR-031 回放的提示词哈希也依赖它。
 *
 * <p>两张登记表都<b>只查单体局</b>(同 {@code TurnPromptBuilder.SINGLE_TURN_CLOCK});融合局与未登记的世界两项都不带。
 * 两项都是展示文字,不是枚举:前端原样显示,不比较内容(ADR-018 §4.2)。
 */
public final class ClientProjection {

	private ClientProjection() {
	}

	/** archetype → 「回合序号 n(= {@code state.turn} + 1)→ 时段标签」。《派单》要显示「第几夜」时往这里加一条。 */
	static final Map<String, IntFunction<String>> TIME_LABELS = Map.of(
			"time_travel", ClientProjection::qiriTimeLabel);

	/**
	 * archetype →(选项前缀 → tag 文字),内层按登记顺序匹配。
	 * <b>给一个世界登记 tag 时,必须同时确认它实际使用的决策圈组件会显示 tag</b>(带皮肤的世界各有自己的决策圈,ADR-039 决策 5)。
	 */
	static final Map<String, Map<String, String>> ACTION_TAGS = Map.of(
			"time_travel", qiriTags());

	private static Map<String, String> qiriTags() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put(QiriActionGuard.ODD_IDEA, "怪点子");
		m.put(QiriActionGuard.SMOOTH_OVER, "打圆场");
		return m;
	}

	private static final String QIRI_DAYS = "一二三四五六七";
	private static final String[] QIRI_PARTS = { "早市", "午市", "晚市" };

	/**
	 * 《七日开张》时段标签(Felix 2026-11-13 定稿,逐字):第 1–21 回合「第{d}日 · {时段}」(d 用汉字,时段 早市 / 午市 / 晚市,
	 * 间隔号左右各一个半角空格);第 22 回合「夜集 · 开张」;第 23 回合起「夜集 · 收摊」。
	 * 第几日 / 时段只从 {@link QiriCalendar} 取,与提示词时段行同一份换算;文字与提示词行不同。
	 */
	static String qiriTimeLabel(int n) {
		QiriCalendar cal = QiriCalendar.of(n);
		return switch (cal.stage()) {
			case DAY -> "第" + QIRI_DAYS.charAt(cal.day() - 1) + "日 · " + QIRI_PARTS[cal.part()];
			case NIGHT_MARKET -> "夜集 · 开张";
			case AFTER -> "夜集 · 收摊";
		};
	}

	/**
	 * 本局此刻的时段标签;没有则返回 {@code null}(调用方据此<b>不写这个键</b>,而不是写 null)。
	 * 描述的是<b>玩家正要打的那一回合</b>:n = {@code state.turn} + 1,与提示词注入口径相同。
	 * 已结束的局没有「正要打的那一回合」,不给。
	 */
	public static String timeLabel(Engine engine) {
		String archetype = singleArchetype(engine);
		IntFunction<String> fn = archetype == null ? null : TIME_LABELS.get(archetype);
		if (fn == null || "ended".equals(engine.status())) {
			return null;
		}
		return fn.apply(engine.turn() + 1);
	}

	/**
	 * 下发用的选项:<b>总是返回新对象,绝不改 {@code stored}</b>;{@code stored} 为 null 返回空数组。
	 * <ul>
	 * <li>选项里已有的 {@code tag} 键一律删掉(所有世界):tag 只来自服务端登记表,模型自己写的不下发;</li>
	 * <li>本局登记了 tag 表、且 {@code text} 以某个前缀开头(字面精确、必须在开头,与 {@link QiriActionGuard#prefixOf} 同判):
	 * {@code text} 去掉前缀与紧随其后的空白,{@code tag} 置为表里的文字;</li>
	 * <li>去掉前缀后没有字了:不算命中,{@code text} 原样、不带 {@code tag};</li>
	 * <li>其余:{@code text} 原样、不带 {@code tag}。</li>
	 * </ul>
	 */
	public static ArrayNode actions(Engine engine, ArrayNode stored) {
		if (stored == null) {
			return JsonNodeFactory.instance.arrayNode();
		}
		ArrayNode out = stored.deepCopy();
		String archetype = singleArchetype(engine);
		Map<String, String> tags = archetype == null ? null : ACTION_TAGS.get(archetype);
		for (JsonNode node : out) {
			if (!(node instanceof ObjectNode action)) {
				continue;
			}
			action.remove("tag");
			JsonNode textNode = action.get("text");
			if (tags == null || textNode == null || !textNode.isString()) {
				continue;
			}
			String text = textNode.asString();
			for (Map.Entry<String, String> e : tags.entrySet()) {
				if (text.startsWith(e.getKey())) {
					String rest = text.substring(e.getKey().length()).stripLeading();
					if (!rest.isEmpty()) {
						action.put("text", rest);
						action.put("tag", e.getValue());
					}
					break;
				}
			}
		}
		return out;
	}

	/** 单体局返回它的 archetype;融合局(或 archetypes 异常)返回 null。 */
	private static String singleArchetype(Engine engine) {
		JsonNode archetypes = engine.world().path("archetypes");
		return archetypes.size() == 1 ? archetypes.path(0).asString(null) : null;
	}
}
