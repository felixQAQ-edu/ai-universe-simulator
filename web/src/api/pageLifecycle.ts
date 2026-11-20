// 页面切后台 / 被卸下的通知(ADR-042 决策 3)。document / window 的生命周期事件是平台 API,
// 按 ADR-003 收在 api/;逻辑层只拿到「页面不可见了」这一件事。小程序迁移时换成 onHide。
//
// 两个事件都接:visibilitychange → hidden 覆盖切页签 / 切后台 / 锁屏;pagehide 覆盖关页与进 bfcache
// (有的浏览器关页时不发 visibilitychange)。同一次离开两个都到,订阅方须幂等。

export function onPageHidden(cb: () => void): () => void {
  if (typeof document === 'undefined' || typeof window === 'undefined') return () => {};
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') cb();
  };
  document.addEventListener('visibilitychange', onVisibility);
  window.addEventListener('pagehide', cb);
  return () => {
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('pagehide', cb);
  };
}
