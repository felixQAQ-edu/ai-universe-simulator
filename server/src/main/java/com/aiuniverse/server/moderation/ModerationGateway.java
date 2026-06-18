package com.aiuniverse.server.moderation;

/**
 * 内容安全审核网关接缝(CONTEXT §三.7、ADR-002 划出的服务边界)。所有进出 LLM 的文本都该过这道关。
 *
 * <p>方案未定 —— 由尚未撰写的 ADR-004 决定审核 API 选型、兜底过滤、以及审核点究竟放在
 * 「prompt 入参 / 完整生成文本 / 流式缓冲」哪一层。本接口先把边界划干净,实现走 no-op 占位。
 */
public interface ModerationGateway {

	/**
	 * 审核一段文本。放行则原样(或净化后)返回;阻断策略由 ADR-004 定(抛异常或返回兜底文案)。
	 */
	String review(String text);
}
