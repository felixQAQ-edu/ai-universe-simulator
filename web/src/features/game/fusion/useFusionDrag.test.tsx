import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useFusionDrag } from './useFusionDrag';

// 手势**接线**的钉子:判据本身在 drag.test.ts,这里钉「指针事件进来之后发生了什么」。
//
// 取证边界(如实立字):jsdom 没有手指、没有布局、没有惯性滚动 —— 体感六条只能真机回答
// (线 B1 同一口径)。这里能证明的是接线通:抓起 / 滚动优先 / 方向语义 / 忙态停摆 / 卸载清理。

/** 两张卡上下排开;getBoundingClientRect 是 jsdom 恒 0 的,逐张替死。 */
const RECTS: Record<string, { left: number; top: number; width: number; height: number }> = {
  a: { left: 16, top: 0, width: 343, height: 92 },
  b: { left: 16, top: 100, width: 343, height: 92 },
};

function stubRect(el: HTMLElement, id: string) {
  const r = RECTS[id];
  el.getBoundingClientRect = () =>
    ({ ...r, right: r.left + r.width, bottom: r.top + r.height, x: r.left, y: r.top, toJSON: () => ({}) }) as DOMRect;
}

function Harness({
  onCommit,
  onReject,
  canFuse = () => true,
  enabled = true,
}: {
  onCommit: (...args: unknown[]) => void;
  onReject?: (host: string, foreign: string) => void;
  canFuse?: (host: string, foreign: string) => boolean;
  enabled?: boolean;
}) {
  const drag = useFusionDrag({ canFuse, onCommit, onReject, enabled });
  return (
    <div>
      {(['a', 'b'] as const).map((id) => (
        <button
          key={id}
          data-testid={id}
          ref={(el) => {
            if (el) stubRect(el, id);
            drag.registerCard(id)(el);
          }}
          onPointerDown={(e) => drag.onPointerDown(id, e)}
        >
          {id}
        </button>
      ))}
      <p data-testid="view">{`${drag.view.draggingId ?? '-'}/${drag.view.targetId ?? '-'}/${drag.view.valid}/${drag.view.armed}`}</p>
    </div>
  );
}

/** 抓起 a(长按到点)→ 拖到 b 上(中心落进 b)→ 松手。 */
function dragAOntoB() {
  const a = screen.getByTestId('a');
  fireEvent.pointerDown(a, { pointerId: 1, clientX: 100, clientY: 40 });
  act(() => void vi.advanceTimersByTime(200)); // 过长按 180ms → 抓起
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: 150 }); // 位移 +110 → 中心落进 b
  act(() => void vi.advanceTimersByTime(400)); // 停留过 dwell 300ms
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: 150 });
  fireEvent.pointerUp(window, { pointerId: 1 });
}

afterEach(() => {
  vi.useRealTimers();
});

describe('抓起与提交', () => {
  it('长按到点即抓起,拖到合法目标上松手 → 提交(**被拖者=foreign,承接者=host**)', () => {
    vi.useFakeTimers();
    const onCommit = vi.fn();
    render(<Harness onCommit={onCommit} />);
    dragAOntoB();
    // 拖的是 a、落在 b 上 → host=b、foreign=a(有序双值 host 在前,ADR-013)
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit.mock.calls[0].slice(0, 2)).toEqual(['b', 'a']);
    // 第三参 = 两张卡此刻的视口位置(揉合动画在真卡原位上作画;量位置的能力只手势层有)。
    expect(onCommit.mock.calls[0][2]).toEqual({
      host: RECTS.b,
      foreign: RECTS.a,
    });
  });

  it('非法组合:落在目标上松手也不提交(玩家拖不出一个后端 400 的组合)', () => {
    vi.useFakeTimers();
    const onCommit = vi.fn();
    render(<Harness onCommit={onCommit} canFuse={() => false} />);
    dragAOntoB();
    expect(onCommit).not.toHaveBeenCalled();
    expect(screen.getByTestId('view')).toHaveTextContent('-/-/false/false'); // 松手后回到待命
  });

  it('非法目标上松手 → onReject(给一句提示的机会;**松手才报**,不是路过就念)', () => {
    vi.useFakeTimers();
    const onReject = vi.fn();
    render(<Harness onCommit={vi.fn()} onReject={onReject} canFuse={() => false} />);
    dragAOntoB();
    expect(onReject).toHaveBeenCalledExactlyOnceWith('b', 'a');
  });

  it('抓起后归位:卡片不残留 transform / zIndex(否则下一次拖拽从歪的位置起手)', () => {
    vi.useFakeTimers();
    render(<Harness onCommit={vi.fn()} />);
    dragAOntoB();
    act(() => void vi.advanceTimersByTime(400)); // 归位过渡 + 清理
    const a = screen.getByTestId('a');
    expect(a.style.transform).toBe('');
    expect(a.style.zIndex).toBe('');
  });
});

describe('滚动优先(玩家在这一屏的默认手势是上下滑)', () => {
  it('长按未到点就纵向甩动 → 判为滚动,不抓起', () => {
    vi.useFakeTimers();
    const onCommit = vi.fn();
    render(<Harness onCommit={onCommit} />);
    const a = screen.getByTestId('a');
    fireEvent.pointerDown(a, { pointerId: 1, clientX: 100, clientY: 40 });
    act(() => void vi.advanceTimersByTime(60));
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: 90 }); // 纵向 50px
    act(() => void vi.advanceTimersByTime(600)); // 长按计时已被取消,再久也不抓起
    expect(screen.getByTestId('view')).toHaveTextContent('-/-/false/false');
    fireEvent.pointerUp(window, { pointerId: 1 });
    expect(onCommit).not.toHaveBeenCalled();
  });

  it('**判为滚动后不许中途改判**:同一次触摸再横向甩也不抓起(一次触摸只判一次)', () => {
    vi.useFakeTimers();
    render(<Harness onCommit={vi.fn()} />);
    const a = screen.getByTestId('a');
    fireEvent.pointerDown(a, { pointerId: 1, clientX: 100, clientY: 40 });
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: 90 }); // 先判滚动
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 160, clientY: 92 }); // 再横向:不得抓起
    act(() => void vi.advanceTimersByTime(600));
    expect(screen.getByTestId('view')).toHaveTextContent('-/-/false/false');
  });
});

describe('护栏与清理', () => {
  // 变异验证(ADR-018 §4.13)记两笔:
  //  · 抽掉「判为滚动后不许改判」的 abandoned 标 → 那条用例当场变红;
  //  · **enabled 有两道闸**(pointerdown 一道、beginDrag 一道),只抽一道**仍是绿的** ——
  //    忠实变异必须两道一起抽,那时才变红。只抽一道就宣称"验过"是假绿。
  it('enabled=false(揉合动画进行中)→ 手势整体停摆:同时只允许一组融合动画运行', () => {
    vi.useFakeTimers();
    const onCommit = vi.fn();
    render(<Harness onCommit={onCommit} enabled={false} />);
    dragAOntoB();
    expect(onCommit).not.toHaveBeenCalled();
    expect(screen.getByTestId('view')).toHaveTextContent('-/-/false/false');
  });

  it('unmount 摘干净全局监听(留一条在途 = 下一屏被幽灵手势打扰)', () => {
    vi.useFakeTimers();
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = render(<Harness onCommit={vi.fn()} />);
    fireEvent.pointerDown(screen.getByTestId('a'), { pointerId: 1, clientX: 100, clientY: 40 });
    const added = add.mock.calls.filter(([t]) => t.startsWith('pointer')).length;
    expect(added).toBeGreaterThan(0);
    unmount();
    const removed = remove.mock.calls.filter(([t]) => t.startsWith('pointer')).length;
    expect(removed).toBe(added);
    add.mockRestore();
    remove.mockRestore();
  });
});
