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
 */
public record InitResponse(String saveId, JsonNode world, String openingNarrative, JsonNode availableActions) {
}
