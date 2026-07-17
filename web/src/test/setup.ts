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
