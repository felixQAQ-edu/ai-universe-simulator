package com.aiuniverse.server.eventloop;

import tools.jackson.databind.node.ObjectNode;

/**
 * 单回合产出的命名事件 sink(规格 §4.2)。这是控制面/服务层与传输层之间的承重接缝(ADR-005):
 * {@link EventLoopService} / {@link TurnStateMachine} 只认本接口,web 层用薄适配把它桥到
 * {@code SseEmitter}(见 {@code web/SseTurnEventSink}),核心对 SSE/Flux 无感知。
 *
 * <p><b>消毒纪律(规格 §1)</b>:经由 {@link #delta}/{@link #ending} 出网的状态<b>必须</b>已过
 * {@code Engine.toClientState()} 消毒投影(不含 {@code isTrue}/{@code hiddenLogic})。本接口本身不消毒,
 * 由调用方(服务层)保证——单测对出网事件断言无隐藏字段。
 */
public interface TurnEventSink {

	/** 叙事 token 增量,逐字追加到散文区(可被多次调用)。 */
	void narrative(String text);

	/** 流末一次性的消毒状态变化(turn/status/hp/san/discovered rules/availableActions)。 */
	void delta(ObjectNode sanitizedDelta);

	/** 结局画面数据({@code id}/{@code title}/{@code description},已消毒)。 */
	void ending(ObjectNode sanitizedEnding);

	/** 非法动作 / 忙态 / 不可恢复失败。{@code code} 供前端分支,{@code message} 中文、不泄敏感串。 */
	void error(String code, String message);
}
