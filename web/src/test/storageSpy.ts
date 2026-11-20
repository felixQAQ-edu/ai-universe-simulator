import { vi } from 'vitest';

/**
 * 盯 localStorage.setItem 的 spy,落在方法真正所在的那个对象上。
 *
 * 本机 Node 26 下测试用的是 setup.ts 的内存 polyfill(方法是自有属性);CI 的 Node 22 下是 jsdom 真 Storage,
 * 方法在原型上,而对实例 spyOn 会被 Storage 的具名属性语义吞成一条存储项 —— spy 记不到任何调用、mock 也不生效
 * (ADR-041 刀 5 的 StrictMode 用例因此只在 CI 红)。两种实现都按「谁拥有 setItem 就 spy 谁」处理。
 */
export function spyOnStorageSetItem() {
  const owner: Storage = Object.prototype.hasOwnProperty.call(localStorage, 'setItem')
    ? localStorage
    : (Object.getPrototypeOf(localStorage) as Storage);
  return vi.spyOn(owner, 'setItem');
}
