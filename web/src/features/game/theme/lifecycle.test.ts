import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SkinRuntime } from './lifecycle';

// 统一 teardown(ADR-018 §4.4)。这些用例守的是**行为**:
// 在途定时器不跨 teardown 补发、过期回调自己放弃、重复输入不堆叠。

describe('SkinRuntime', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('受管定时器在 teardown 后绝不补发(放行标准 5:旧 turn 的动效不跨回合冒出来)', () => {
    const runtime = new SkinRuntime();
    const fired = vi.fn();
    runtime.setTimeout(fired, 500);

    runtime.teardown(); // 换了 turn
    vi.advanceTimersByTime(5000);

    expect(fired).not.toHaveBeenCalled();
  });

  it('teardown 之前到点的定时器照常执行(没有误杀)', () => {
    const runtime = new SkinRuntime();
    const fired = vi.fn();
    runtime.setTimeout(fired, 100);

    vi.advanceTimersByTime(150);

    expect(fired).toHaveBeenCalledTimes(1);
  });

  it('过期 token 让异步回调自己放弃(动画 onComplete 回来时发现世界已经变了)', () => {
    const runtime = new SkinRuntime();
    const token = runtime.token;
    expect(runtime.alive(token)).toBe(true);

    runtime.teardown();

    expect(runtime.alive(token)).toBe(false);
  });

  it('忙态锁挡住重复触发(连点不堆叠 timeline),teardown 后自动释放', () => {
    const runtime = new SkinRuntime();
    expect(runtime.tryLock()).toBe(true);
    expect(runtime.tryLock()).toBe(false); // 正忙 → 直接放弃,不排队

    runtime.unlock();
    expect(runtime.tryLock()).toBe(true);

    runtime.teardown(); // 被打断也不会把锁永久留住
    expect(runtime.tryLock()).toBe(true);
  });

  it('注册的清理函数在 teardown 时跑一遍,且只跑一遍', () => {
    const runtime = new SkinRuntime();
    const cleanup = vi.fn();
    runtime.onTeardown(cleanup);

    runtime.teardown();
    runtime.teardown(); // 幂等

    expect(cleanup).toHaveBeenCalledTimes(1);
  });

  it('单个 cleanup 抛错不阻断其余清理(一处坏了不能拖垮整轮 teardown)', () => {
    const runtime = new SkinRuntime();
    const later = vi.fn();
    runtime.onTeardown(() => {
      throw new Error('boom');
    });
    runtime.onTeardown(later);
    const stillCleared = vi.fn();
    runtime.setTimeout(stillCleared, 100);

    expect(() => runtime.teardown()).not.toThrow();
    expect(later).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(500);
    expect(stillCleared).not.toHaveBeenCalled();
  });

  it('teardown 后仍可继续使用(下一 turn 复用同一实例)', () => {
    const runtime = new SkinRuntime();
    runtime.teardown();

    const fired = vi.fn();
    runtime.setTimeout(fired, 100);
    vi.advanceTimersByTime(150);

    expect(fired).toHaveBeenCalledTimes(1);
  });
});
