import { afterEach, describe, expect, it } from 'vitest';
import { isDebug, resetDebugCache } from './debug';

// debug 入口(ADR-018 §5 Q7):仅 `?debug=1`;普通路径一律关。
// 长按标题刻意不做(手势已被选择屏的融合渗漏占用)。

afterEach(() => {
  window.history.replaceState({}, '', '/');
  resetDebugCache();
});

describe('isDebug', () => {
  it('普通路径:关(生产玩家看不到任何调试控件)', () => {
    window.history.replaceState({}, '', '/');
    resetDebugCache();
    expect(isDebug()).toBe(false);
  });

  it('?debug=1:开', () => {
    window.history.replaceState({}, '', '/?debug=1');
    resetDebugCache();
    expect(isDebug()).toBe(true);
  });

  it('其它取值不当作开(不做「只要带 debug 参数就开」的宽松解析)', () => {
    window.history.replaceState({}, '', '/?debug=0');
    resetDebugCache();
    expect(isDebug()).toBe(false);
  });

  it('读一次即缓存:debug 不随后续 URL 变化翻转,也不写任何持久状态', () => {
    window.history.replaceState({}, '', '/?debug=1');
    resetDebugCache();
    expect(isDebug()).toBe(true);

    window.history.replaceState({}, '', '/'); // 之后改 URL 不影响本次会话
    expect(isDebug()).toBe(true);
    expect(globalThis.localStorage?.getItem('debug')).toBeNull();
  });
});
