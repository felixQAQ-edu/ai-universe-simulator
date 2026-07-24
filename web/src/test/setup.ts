import '@testing-library/jest-dom/vitest';

// jsdom 29 不再自带 localStorage(Node 自身的实验性 getter 又是 undefined)——
// 给测试环境补一个内存实现,让续局(ADR-015 Slice 2)的 saveId 持久化可测。
if (typeof globalThis.localStorage === 'undefined' || globalThis.localStorage == null) {
  const backing = new Map<string, string>();
  const memoryStorage = {
    getItem: (k: string) => (backing.has(k) ? backing.get(k)! : null),
    setItem: (k: string, v: string) => void backing.set(k, String(v)),
    removeItem: (k: string) => void backing.delete(k),
    clear: () => backing.clear(),
    key: (i: number) => [...backing.keys()][i] ?? null,
    get length() {
      return backing.size;
    },
  } as Storage;
  Object.defineProperty(globalThis, 'localStorage', { value: memoryStorage, configurable: true });
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', { value: memoryStorage, configurable: true });
  }
}

// jsdom 不实现 matchMedia —— 而 `theme/motion.ts` 的 reducedMotion() 闸(以及未来所有
// 媒体查询驱动的降级)要读它。补一个最小实现(同上面 localStorage polyfill 的先例):
// **默认全部不匹配**(= 不减弱动效),需要测「reduced-motion 下不闪」的用例自行改写返回值。
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string) =>
      ({
        matches: false,
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  });
}
