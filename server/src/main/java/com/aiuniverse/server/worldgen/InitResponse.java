package com.aiuniverse.server.worldgen;

import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/game/init} 的成功响应(设计稿 §3,plain POST 无 SSE)。
 *
 * @param saveId           新存档 id(后续 {@code POST /api/game/{saveId}/turn} 用)
 * @param world            <b>消毒投影</b>(设计稿 §5,经 {@code Engine.toClientState()},剥
 *                         {@code isTrue}/{@code hiddenLogic}/{@code isCorrect}/{@code groundTruth})
 * @param openingNarrative 开场散文整段(transient,不入持久化 state;前端可做 client-side reveal 动画)
 * @param availableActions 初始决策圈({@code {id,text,hint}},无隐藏字段)
 * @param attributes       本模式数值轴元数据 {@code [{key,displayName}]}(ADR-008 决策 1 前端消费方):
 *                         前端据此渲染数值面板项 + 中文名(末日「体力/饥饿」/ 规则怪谈「体力/理智」),
 *                         顺序即面板呈现顺序;值由 world.character.attributes 与每回合 delta 提供。
 */
public record InitResponse(String saveId, JsonNode world, String openingNarrative,
		JsonNode availableActions, JsonNode attributes) {
}
