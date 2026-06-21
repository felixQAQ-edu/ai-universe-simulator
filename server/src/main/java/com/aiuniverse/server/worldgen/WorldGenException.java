package com.aiuniverse.server.worldgen;

/**
 * world-gen 救不回 → <b>整局 ERROR</b>(非 no-op 降级)。设计稿 §1/§4、ADR-007:world-gen 失败
 * <b>没有可保的前态</b>(尚无游戏),不像回合降级要守一局 ongoing —— 故不 no-op、不进半残 PLAYING,
 * 直接干净失败 + 提示用户「重新生成」。
 *
 * <p>由 {@code WorldGenService} 在「调用失败」或「修复后仍未过校验」时抛出;{@code GameController}
 * 捕获后返 5xx + {@code {error:{code,message}}}(不创建会话 → 无半残残留)。message 只放给用户/日志
 * 看的干净中文,绝不拼进 key / 校验细节泄给前端。
 */
public class WorldGenException extends RuntimeException {

	public WorldGenException(String message) {
		super(message);
	}

	public WorldGenException(String message, Throwable cause) {
		super(message, cause);
	}
}
