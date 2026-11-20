// 浏览器历史适配器(ADR-042 决策 5)。`window.history` 是平台 API,按 ADR-003 只在 api/ 里碰;
// 逻辑 / 状态层经 state/screens.ts 使用,永远看不到 popstate 事件本身。
//
// 只做三件事:压一条本站标记的历史、退一步、在退回时回调。**不改地址**(pushState 的 url 参数不传),
// 所以刷新、分享、收藏都还是进站那个地址。小程序没有浏览器历史:迁移时换一个空实现即可(ADR-003 只换适配层)。

/** 本站压进历史的标记(存在 history.state 里;刷新后用它认出「死」条目)。 */
export type NavTag = 'saves' | 'history';

export interface NavHistory {
  push(tag: NavTag): void;
  back(): void;
  /** 浏览器 / 手机返回键(或 back())退到上一条时回调;返回取消订阅。 */
  onPop(cb: () => void): () => void;
  /** 启动时:当前条目若带本站标记(上次在存档页里刷新),换成不带标记的,免得被当成「还在存档页」。条目本身删不掉。 */
  clearStaleMarker(): void;
}

const MARK = 'aiuNav';

export function createBrowserNavHistory(): NavHistory {
  const h = (): History | null => {
    try {
      return typeof window !== 'undefined' ? window.history : null;
    } catch {
      return null;
    }
  };
  return {
    push(tag) {
      try {
        h()?.pushState({ [MARK]: tag }, '');
      } catch {
        /* 压不进:返回键照旧离站,页面内的返回按钮仍可用(screens.ts 按深度 0 处理) */
      }
    },
    back() {
      try {
        h()?.back();
      } catch {
        /* ignore */
      }
    },
    onPop(cb) {
      if (typeof window === 'undefined') return () => {};
      const listener = () => cb();
      window.addEventListener('popstate', listener);
      return () => window.removeEventListener('popstate', listener);
    },
    clearStaleMarker() {
      try {
        const cur = h();
        const st = cur?.state as Record<string, unknown> | null | undefined;
        if (st && typeof st === 'object' && MARK in st) cur?.replaceState(null, '');
      } catch {
        /* ignore */
      }
    },
  };
}

export const navHistory = createBrowserNavHistory();
