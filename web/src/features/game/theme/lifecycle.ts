import { useEffect, useState } from 'react';
import gsap from 'gsap';

// 皮肤生命周期(ADR-018 §4.4):**组件 unmount / 主题切换 / turn 切换走同一个 teardown**。
//
// 为什么必须同一个:三套近似清理逻辑 = 三处各自漏一点,且漏在最难复现的路径上
// (「连玩十个 turn 后旧动画补发」正是这种漏)。这里只有一个 `teardown()`,三条路径都调它。
//
// teardown 内容(AGENTS.md Motion Constraints §5):kill 全部在途 timeline(`ctx.revert()`)、
// 清全部受管 timeout、跑注册的 cleanup(listener 等)、释放 busy 锁。

/**
 * 一个皮肤实例的全部在途副作用。**不要直接 new**——用 {@link useSkinRuntime}。
 *
 * 所有会留下痕迹的东西都必须经它登记:GSAP 走 {@link add}(自动进 `gsap.context`)、
 * 定时器走 {@link setTimeout}、其它走 {@link onTeardown}。绕开它 = 漏清理。
 *
 * 对象标识**跨 turn 稳定**(避免无谓重渲染),`teardown()` 后可继续使用(下一 turn 复用同一实例)。
 */
export class SkinRuntime {
  private ctx: gsap.Context | null = null;
  private timers = new Set<number>();
  private cleanups: (() => void)[] = [];
  /** 忙态锁:重复输入不得堆叠 timeline(同一触发再来一次直接忽略)。 */
  private busy = false;
  /** 本轮(上次 teardown 之后)的世代号;异步回调据它判断自己是否已过期。 */
  private generation = 0;

  /** 在 GSAP context 内执行(其中创建的 tween/timeline 由 `ctx.revert()` 统一 kill)。 */
  add(fn: () => void): void {
    if (!this.ctx) this.ctx = gsap.context(() => {});
    this.ctx.add(fn);
  }

  /** 受管 setTimeout:teardown 时一律清掉(绝不让旧 turn 的回调补发)。 */
  setTimeout(fn: () => void, ms: number): void {
    const gen = this.generation;
    const id = window.setTimeout(() => {
      this.timers.delete(id);
      if (gen === this.generation) fn();
    }, ms);
    this.timers.add(id);
  }

  /** 注册任意清理函数(事件监听、observer…),teardown 时执行。 */
  onTeardown(fn: () => void): void {
    this.cleanups.push(fn);
  }

  /** 尝试占用忙态锁;已忙 → false(调用方直接返回,不排队、不堆叠)。 */
  tryLock(): boolean {
    if (this.busy) return false;
    this.busy = true;
    return true;
  }

  /** 释放忙态锁。 */
  unlock(): void {
    this.busy = false;
  }

  /** 当前世代号(异步回调保存它,回来时比对,过期即放弃 —— 不补发)。 */
  get token(): number {
    return this.generation;
  }

  /** 该 token 是否仍有效(未被 teardown 过)。 */
  alive(token: number): boolean {
    return token === this.generation;
  }

  /** **唯一的清理入口**:unmount / 主题切换 / turn 切换三条路径都调它。幂等、可重复使用。 */
  teardown(): void {
    this.generation += 1;
    this.timers.forEach((id) => window.clearTimeout(id));
    this.timers.clear();
    for (const fn of this.cleanups.splice(0)) {
      try {
        fn();
      } catch {
        /* 单个 cleanup 出错不得阻断其余清理 */
      }
    }
    this.ctx?.revert();
    this.ctx = null;
    this.busy = false;
  }
}

/**
 * 取皮肤 runtime。`skinKey` 或 `turn` 一变即 `teardown()`(旧 turn 的在途灯闪与定时器绝不跨 turn 补发),
 * unmount 同样 `teardown()` —— **同一个函数,三条路径**。
 */
export function useSkinRuntime(skinKey: string, turn: number): SkinRuntime {
  // 惰性初始化的 useState:实例标识跨渲染稳定,且不在渲染期读 ref。
  const [runtime] = useState(() => new SkinRuntime());

  useEffect(() => () => runtime.teardown(), [runtime, skinKey, turn]);

  return runtime;
}
