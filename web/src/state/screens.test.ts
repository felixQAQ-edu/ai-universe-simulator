// ADR-042 决策 5:存档页 / 历史页的进出与浏览器历史对得上。
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createBrowserNavHistory, type NavHistory, type NavTag } from '../api';
import { createScreens, useSavesView } from './screens';

/** 内存里的浏览器历史:back() 同步触发 pop(真浏览器是异步,行为一样)。 */
function fakeNav() {
  const stack: Array<NavTag | null> = [null];
  let pop: (() => void) | null = null;
  const nav: NavHistory = {
    push: (t) => void stack.push(t),
    back: () => {
      if (stack.length > 1) {
        stack.pop();
        pop?.();
      } else stack.length = 0; // 退到站外
    },
    onPop: (cb) => {
      pop = cb;
      return () => (pop = null);
    },
    clearStaleMarker: () => {},
  };
  /** 模拟手机 / 浏览器返回键。 */
  const pressBack = () => nav.back();
  return { nav, stack, pressBack };
}

function fakeHistory() {
  let viewing: string | null = null;
  return {
    ctl: { isOpen: () => viewing !== null, open: (id: string) => void (viewing = id), close: () => void (viewing = null) },
    viewing: () => viewing,
  };
}

beforeEach(() => useSavesView.setState({ open: false, zone: 'fushi' }));

describe('存档页 / 历史页的返回键(ADR-042 决策 5)', () => {
  it('打开存档页压一条;手机返回键 = 关存档页,不离站', () => {
    const { nav, stack, pressBack } = fakeNav();
    const s = createScreens(nav, fakeHistory().ctl);
    s.install();
    s.openSaves('shen');
    expect(useSavesView.getState()).toEqual({ open: true, zone: 'shen' });
    expect(stack).toEqual([null, 'saves']);
    pressBack();
    expect(useSavesView.getState().open).toBe(false);
    expect(stack).toEqual([null]);
  });

  it('页面里的「返回」走 back():与返回键同一条路', () => {
    const { nav, stack } = fakeNav();
    const s = createScreens(nav, fakeHistory().ctl);
    s.install();
    s.openSaves('fushi');
    s.goBack();
    expect(useSavesView.getState().open).toBe(false);
    expect(stack).toEqual([null]);
  });

  it('存档页 → 回看上局 → 返回回存档页 → 返回回大厅', () => {
    const { nav, stack, pressBack } = fakeNav();
    const h = fakeHistory();
    const s = createScreens(nav, h.ctl);
    s.install();
    s.openSaves('rong');
    s.openHistory('sid');
    expect(h.viewing()).toBe('sid');
    expect(stack).toEqual([null, 'saves', 'history']);
    pressBack();
    expect(h.viewing()).toBeNull();
    expect(useSavesView.getState().open).toBe(true);
    s.goBack();
    expect(useSavesView.getState().open).toBe(false);
    expect(stack).toEqual([null]);
  });

  it('从存档页点开一局:关存档页、吃掉那条历史,自己发的 pop 不再关别的', () => {
    const { nav, stack } = fakeNav();
    const h = fakeHistory();
    const s = createScreens(nav, h.ctl);
    s.install();
    s.openSaves('fushi');
    s.leaveSavesForGame();
    expect(useSavesView.getState().open).toBe(false);
    expect(stack).toEqual([null]);
    // 之后再开存档页、再按返回,计数仍对得上
    s.openSaves('fushi');
    s.goBack();
    expect(stack).toEqual([null]);
  });

  it('自己发的 pop 晚到(真浏览器是异步):期间又打开了存档页,也不能把它关掉', () => {
    const stack: Array<NavTag | null> = [null];
    let pop: (() => void) | null = null;
    const pending: Array<() => void> = [];
    const nav: NavHistory = {
      push: (t) => void stack.push(t),
      back: () => {
        stack.pop();
        pending.push(() => pop?.());
      },
      onPop: (cb) => {
        pop = cb;
        return () => (pop = null);
      },
      clearStaleMarker: () => {},
    };
    const s = createScreens(nav, fakeHistory().ctl);
    s.install();
    s.openSaves('fushi');
    s.leaveSavesForGame(); // 点开一局:pop 还没到
    s.openSaves('shen'); // 续局失败很快回到大厅,玩家又点了「全部存档」
    pending.splice(0).forEach((f) => f()); // 这时那一次 pop 才到
    expect(useSavesView.getState()).toEqual({ open: true, zone: 'shen' });
  });

  it('没压过历史(深度 0)时「返回」直接关页,不调 back() 离站', () => {
    const { nav, stack } = fakeNav();
    const h = fakeHistory();
    const s = createScreens(nav, h.ctl);
    s.install();
    h.ctl.open('sid'); // 例如测试直接设了 historyStore
    s.goBack();
    expect(h.viewing()).toBeNull();
    expect(stack).toEqual([null]);
  });

  it('存档页已开时再点「全部存档」不重复压', () => {
    const { nav, stack } = fakeNav();
    const s = createScreens(nav, fakeHistory().ctl);
    s.install();
    s.openSaves('fushi');
    s.openSaves('fushi');
    expect(stack).toEqual([null, 'saves']);
  });
});

describe('浏览器适配器(jsdom 真 history)', () => {
  afterEach(() => window.history.replaceState(null, ''));

  it('push 带本站标记、不改地址;clearStaleMarker 把它换掉;back 触发 onPop', async () => {
    const nav = createBrowserNavHistory();
    const href = window.location.href;
    nav.push('saves');
    expect(window.history.state).toEqual({ aiuNav: 'saves' });
    expect(window.location.href).toBe(href);
    const popped = new Promise<void>((r) => {
      const off = nav.onPop(() => {
        off();
        r();
      });
    });
    nav.back();
    await popped;
    nav.push('history');
    nav.clearStaleMarker();
    expect(window.history.state).toBeNull();
  });
});
