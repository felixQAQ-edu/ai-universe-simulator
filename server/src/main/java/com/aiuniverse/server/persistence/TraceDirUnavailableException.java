package com.aiuniverse.server.persistence;

/**
 * 回合轨迹目录不可创建 / 不可统计(ADR-031 刀 3 补)。
 *
 * <p>与 web 根断言失败({@link IllegalStateException},保密防线、照旧拒启)是<b>两类</b>启动失败,
 * 用不同的异常类型区分,不靠消息字符串判断:装配处({@link TraceSinkConfig})只接住本类型,
 * 打一条 ERROR 后改装 {@link TraceSink#NOOP},服务照常启动 —— 轨迹的任何失败绝不拖累游戏。
 */
public class TraceDirUnavailableException extends RuntimeException {

	public TraceDirUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
