// 软闸设备键(ADR-016):localStorage 里生成一次的 UUID,独立于 saveId,随 init/turn
// 请求头 `X-Device-Id` 带上,供服务端单设备日计数(双键之一,缓解 CGNAT 共享 IP 误伤)。
//
// 立字(ADR-016 已知代价 3):防君子不防脚本——localStorage 一清即新身份;真正的墙是
// 服务端全局 ¥ 双顶。storage 不可用(隐私模式等)时退化为进程内随机 id(本次会话稳定,
// 不跨刷新;服务端缺头也只是该键不计,IP 键仍在)。

const DEVICE_ID_KEY = 'aiuniverse.deviceId';

let cached: string | null = null;

/** 取(或首次生成并持久化)设备 id。同一次页面会话内恒定。 */
export function getDeviceId(): string {
  if (cached) return cached;
  try {
    const stored = globalThis.localStorage?.getItem(DEVICE_ID_KEY);
    if (stored) {
      cached = stored;
      return stored;
    }
    const fresh = generateId();
    globalThis.localStorage?.setItem(DEVICE_ID_KEY, fresh);
    cached = fresh;
    return fresh;
  } catch {
    cached = generateId();
    return cached;
  }
}

function generateId(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c?.randomUUID) return c.randomUUID();
  return `dev-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
}
