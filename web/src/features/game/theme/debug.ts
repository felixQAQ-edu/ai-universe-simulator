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

/** 本次会话是否处于 debug 模式(读一次,之后不再解析 URL)。 */
export function isDebug(): boolean {
  if (cached !== null) return cached;
  try {
    cached =
      typeof window !== 'undefined' &&
      new URLSearchParams(window.location.search).get('debug') === '1';
  } catch {
    cached = false;
  }
  return cached;
}

/** 仅供测试:清掉缓存,让下一次 {@link isDebug} 重新解析。 */
export function resetDebugCache(): void {
  cached = null;
}
