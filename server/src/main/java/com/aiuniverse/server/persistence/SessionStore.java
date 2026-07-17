package com.aiuniverse.server.persistence;

import java.util.List;

import com.aiuniverse.server.eventloop.GameSession;

/**
 * 会话落盘接缝(ADR-015 §最终决策 C3:每 saveId 一个 JSON,不引数据库)。
 * 消费方({@code GameSessionManager} 写盘时机 init 后 / {@code TurnStateMachine} 临界区尾部)
 * 只依赖本接口;文件形态在 {@link FileSessionStore}。测试可用 lambda / 内存替身,
 * 现有单参构造走 {@link #NOOP}(= Slice 2 之前的纯内存行为,零回归)。
 */
public interface SessionStore {

	/**
	 * 把一条会话的当前完整状态写盘(<b>best-effort,绝不抛</b>:写失败记响亮 ERROR、局面继续活在内存——
	 * 一次磁盘故障不该杀死正在进行的回合;代价 = 该回合丢续局,符合「盘上永远是最后一个完整回合」口径)。
	 */
	void persist(GameSession session);

	/**
	 * 启动回载:扫描落盘目录,把每个可读档还原为会话(经 {@code Engine.restore} + 轴集重派生,
	 * phase 按 status 重置)。单文件损坏/拒载 → 跳过 + WARN + 保留原文件(留尸检),不阻断启动。
	 */
	List<GameSession> loadAll();

	/** 纯内存 no-op(Slice 2 之前行为;测试与旧构造签名用)。 */
	SessionStore NOOP = new SessionStore() {
		@Override
		public void persist(GameSession session) {
		}

		@Override
		public List<GameSession> loadAll() {
			return List.of();
		}
	};
}
