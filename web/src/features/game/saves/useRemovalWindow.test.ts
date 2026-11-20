// ADR-042 决策 3:撤销窗口的计时语义(纯控制器,注入假时钟)。
import { describe, expect, it, vi } from 'vitest';
import type { SaveSummary } from '../../../state/savePointers';
import { createRemovalWindow, REMOVAL_WINDOW_MS, type PendingBatch, type RemovalTimer } from './useRemovalWindow';

const s = (i: number): SaveSummary => ({
  saveId: `s${i}`,
  title: `世界${i}`,
  archetypes: ['rules_creepy'],
  turn: i,
  ended: false,
  lastPlayedAt: i,
  availability: 'available',
});

/** 手动推进的时钟 + 计时器(同一时刻只会有一个计时器在走)。 */
function fakeTimer() {
  let t = 0;
  const pending = new Map<number, { at: number; fn: () => void }>();
  let id = 0;
  const timer: RemovalTimer = {
    now: () => t,
    set: (fn, ms) => {
      pending.set(++id, { at: t + ms, fn });
      return id;
    },
    clear: (h) => void pending.delete(h as number),
  };
  const advance = (ms: number) => {
    const end = t + ms;
    for (;;) {
      const due = [...pending.entries()].filter(([, p]) => p.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      pending.delete(due[0]);
      t = due[1].at;
      due[1].fn();
    }
    t = end;
  };
  return { timer, advance, live: () => pending.size };
}

function setup() {
  const ft = fakeTimer();
  const commit = vi.fn();
  const changes: Array<{ batch: PendingBatch | null; paused: boolean }> = [];
  const w = createRemovalWindow((batch, paused) => changes.push({ batch, paused }), commit, ft.timer);
  return { ...ft, commit, changes, w };
}

describe('撤销窗口(决策 3)', () => {
  it('到点才提交:4999ms 不删,5000ms 提交一次,带的是加进来的那几条', () => {
    const { w, advance, commit } = setup();
    w.add([s(1)], 'one');
    advance(REMOVAL_WINDOW_MS - 1);
    expect(commit).not.toHaveBeenCalled();
    advance(1);
    expect(commit).toHaveBeenCalledTimes(1);
    expect(commit).toHaveBeenCalledWith([s(1)]);
  });

  it('连续移除并成一批,计时从头再来(B2)', () => {
    const { w, advance, commit, changes } = setup();
    w.add([s(1)], 'one');
    advance(3000);
    w.add([s(2)], 'one');
    expect(changes.at(-1)!.batch!.kind).toBe('mixed');
    advance(2000); // 第一条的 5 秒到了,但计时已从头
    expect(commit).not.toHaveBeenCalled();
    advance(3000);
    expect(commit).toHaveBeenCalledWith([s(1), s(2)]);
  });

  it('批的种类:单删 one、全部移除 endedAll,任何并批都是 mixed;version 每加一次 +1', () => {
    const { w, changes } = setup();
    w.add([s(1), s(2)], 'endedAll');
    expect(changes.at(-1)!.batch).toMatchObject({ kind: 'endedAll' });
    const v = changes.at(-1)!.batch!.version;
    w.add([s(3)], 'one');
    expect(changes.at(-1)!.batch).toMatchObject({ kind: 'mixed', version: v + 1 });
    w.undo();
    w.add([s(4)], 'one');
    expect(changes.at(-1)!.batch!.kind).toBe('one');
  });

  it('撤销:整批放回、永不提交、计时器清掉', () => {
    const { w, advance, commit, live } = setup();
    w.add([s(1)], 'one');
    w.add([s(2)], 'one');
    expect(w.undo()).toEqual([s(1), s(2)]);
    expect(live()).toBe(0);
    advance(60_000);
    expect(commit).not.toHaveBeenCalled();
    expect(w.undo()).toEqual([]); // 没有待删时撤销是空操作
  });

  it('作废(切后台 / 关页)与卸载:永不提交', () => {
    const a = setup();
    a.w.add([s(1)], 'one');
    a.w.cancel();
    a.advance(60_000);
    expect(a.commit).not.toHaveBeenCalled();

    const b = setup();
    b.w.add([s(1)], 'one');
    const n = b.changes.length;
    b.w.dispose();
    b.advance(60_000);
    expect(b.commit).not.toHaveBeenCalled();
    expect(b.changes).toHaveLength(n); // 卸载后不再回调
  });

  it('暂停:悬停期间不走;离开后按剩余时间继续,不从头', () => {
    const { w, advance, commit, changes } = setup();
    w.add([s(1)], 'one');
    advance(2000);
    w.pause('hover');
    expect(changes.at(-1)!.paused).toBe(true);
    advance(60_000);
    expect(commit).not.toHaveBeenCalled();
    w.resume('hover');
    advance(2999);
    expect(commit).not.toHaveBeenCalled();
    advance(1);
    expect(commit).toHaveBeenCalledTimes(1);
  });

  it('悬停与焦点各自独立:两者都撤了才继续', () => {
    const { w, advance, commit } = setup();
    w.add([s(1)], 'one');
    w.pause('hover');
    w.pause('focus');
    w.resume('hover');
    advance(60_000);
    expect(commit).not.toHaveBeenCalled();
    w.resume('focus');
    advance(REMOVAL_WINDOW_MS);
    expect(commit).toHaveBeenCalledTimes(1);
  });

  it('暂停中又移除一条:剩余时间回到满 5 秒,仍停着', () => {
    const { w, advance, commit } = setup();
    w.add([s(1)], 'one');
    advance(4000);
    w.pause('focus');
    w.add([s(2)], 'one');
    advance(60_000);
    expect(commit).not.toHaveBeenCalled();
    w.resume('focus');
    advance(REMOVAL_WINDOW_MS - 1);
    expect(commit).not.toHaveBeenCalled();
    advance(1);
    expect(commit).toHaveBeenCalledWith([s(1), s(2)]);
  });

  it('批结束时暂停来由一并清掉(撤销条消失时 mouseleave / blur 未必会到)', () => {
    const { w, advance, commit } = setup();
    w.add([s(1)], 'one');
    w.pause('hover');
    w.undo();
    w.add([s(2)], 'one'); // 新的一批不该被上一批残留的悬停卡住
    advance(REMOVAL_WINDOW_MS);
    expect(commit).toHaveBeenCalledWith([s(2)]);
  });
});
