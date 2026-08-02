// debug 入口(ADR-018 §5 Q7):**仅 `?debug=1`**。
// 长按标题刻意不做 —— 该手势已被选择屏的融合渗漏占用(两处语义冲突)。
//
// 纪律:`debug` **不写入任何持久状态**;debug 控件**不得改变生产数据**,
// 只允许触发前端展示效果与查看调度状态。
//
// 边界(ADR-003):`window.location` 是本仓库唯一一处平台对象读取,**收进本文件单点**——
// Taro 迁移时本模块直接降级为 `() => false`,调用方零改。eslint 硬线(fetch/EventSource/
// WebSocket/XMLHttpRequest/wx.*)不涉及它,但仍按同一「收进一处」的纪律办。

let cached: boolean | null = null;

/**
 * 读一个 query 参数(不缓存)。**本仓库读取 `window.location` 的唯一实现**——
 * 新增读取需求一律经这里,不要在别处再开一个 `window.location`(Taro 迁移时只降级本文件)。
 */
export function queryFlag(name: string): string | null {
  try {
    if (typeof window === 'undefined') return null;
    return new URLSearchParams(window.location.search).get(name);
  } catch {
    return null;
  }
}

/** 本次会话是否处于 debug 模式(读一次,之后不再解析 URL)。 */
export function isDebug(): boolean {
  if (cached !== null) return cached;
  cached = queryFlag('debug') === '1';
  return cached;
}

/** 仅供测试:清掉缓存,让下一次 {@link isDebug} 重新解析。 */
export function resetDebugCache(): void {
  cached = null;
}
