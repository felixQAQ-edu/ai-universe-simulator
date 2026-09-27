import { describe, expect, it, vi } from 'vitest';
import type { HistoryApi, HistoryEntry, HistoryPage } from '../api';
import { GameApiError } from '../api';
import { createHistoryStore, probeShowsEntry } from './historyStore';

// ADR-025 刀 3:入口探测判据 / 缓存 / 历史页读取与翻页。mock api 层,不依赖真后端。

const ev = (turn: number, narrative = `叙事${turn}`): HistoryEntry => ({
  kind: 'event',
  turn,
  narrative,
  playerAction: turn === 0 ? null : 'A',
});
const gap = (
  reason: 'before_recording' | 'write_failed',
  fromTurn: number,
  toTurn: number,
): HistoryEntry => ({ kind: 'gap', reason, fromTurn, toTurn });
const page = (entries: HistoryEntry[], nextAfterTurn: number | null = null): HistoryPage => ({
  saveId: 's',
  entries,
  nextAfterTurn,
});

/** 一个可控的 api:按调用顺序依次给出结果(值 = resolve,Error = reject)。 */
function scripted(...results: Array<HistoryPage | Error>) {
  const readHistory = vi.fn<HistoryApi['readHistory']>();
  for (const r of results) {
    if (r instanceof Error) readHistory.mockRejectedValueOnce(r);
    else readHistory.mockResolvedValueOnce(r);
  }
  return { api: { readHistory } as HistoryApi, readHistory };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('入口显示判据(口径 B)', () => {
  it('第一页含事件 → 显示', async () => {
    const { api } = scripted(page([ev(0), ev(1)]));
    const store = createHistoryStore(api);
    store.getState().probe('s');
    await flush();
    expect(store.getState().visible.s).toBe(true);
  });

  it('无事件的导入档(第一页只有一条 before_recording 缺口、无下一页)→ 隐藏', async () => {
    const { api } = scripted(page([gap('before_recording', 0, 5)]));
    const store = createHistoryStore(api);
    store.getState().probe('s');
    await flush();
    expect(store.getState().visible.s).toBeUndefined();
  });

  it('第一页无事件但有下一页 → 显示', async () => {
    const { api } = scripted(page([gap('before_recording', 0, 99)], 99));
    const store = createHistoryStore(api);
    store.getState().probe('s');
    await flush();
    expect(store.getState().visible.s).toBe(true);
  });

  it.each([
    ['501 history_unavailable', new GameApiError('history_unavailable', 'HTTP 501')],
    ['404 session_not_found', new GameApiError('session_not_found', 'HTTP 404')],
    ['503 history_read_failed', new GameApiError('history_read_failed', 'HTTP 503')],
    ['网络失败', new GameApiError('network', 'x')],
  ])('%s → 隐藏,不抛', async (_label, err) => {
    const { api } = scripted(err);
    const store = createHistoryStore(api);
    store.getState().probe('s');
    await flush();
    expect(store.getState().visible.s).toBeUndefined();
  });

  it('纯函数:空页无下一页 → 否', () => {
    expect(probeShowsEntry([], null)).toBe(false);
  });
});

describe('入口探测缓存(口径 C)', () => {
  it('在途中重复调用只发一次请求(同一次渲染 / 重渲染 / StrictMode)', async () => {
    const { api, readHistory } = scripted(page([ev(0)]));
    const store = createHistoryStore(api);
    store.getState().probe('s');
    store.getState().probe('s');
    store.getState().probe('s');
    await flush();
    expect(readHistory).toHaveBeenCalledTimes(1);
  });

  it('同 saveId 已确认可见 → 缓存命中,不再请求', async () => {
    const { api, readHistory } = scripted(page([ev(0)]));
    const store = createHistoryStore(api);
    store.getState().probe('s');
    await flush();
    store.getState().probe('s');
    await flush();
    expect(readHistory).toHaveBeenCalledTimes(1);
  });

  it('换 saveId → 重新请求', async () => {
    const { api, readHistory } = scripted(page([ev(0)]), page([ev(0)]));
    const store = createHistoryStore(api);
    store.getState().probe('a');
    await flush();
    store.getState().probe('b');
    await flush();
    expect(readHistory).toHaveBeenCalledTimes(2);
    expect(readHistory.mock.calls.map((c) => c[0])).toEqual(['a', 'b']);
  });

  it('失败不缓存:下次再探测会重新请求,且成功后显示', async () => {
    const { api, readHistory } = scripted(new GameApiError('history_read_failed', 'x'), page([ev(0)]));
    const store = createHistoryStore(api);
    store.getState().probe('s');
    await flush();
    store.getState().probe('s');
    await flush();
    expect(readHistory).toHaveBeenCalledTimes(2);
    expect(store.getState().visible.s).toBe(true);
  });
});

describe('历史页(口径 E/G/H)', () => {
  it('每次进页都重新读第一页,不复用探测拿到的数据', async () => {
    const { api, readHistory } = scripted(page([ev(0, '探测时的')]), page([ev(0, '进页时的')]));
    const store = createHistoryStore(api);
    store.getState().probe('s');
    await flush();
    store.getState().open('s');
    await flush();
    expect(readHistory).toHaveBeenCalledTimes(2);
    expect(readHistory).toHaveBeenLastCalledWith('s', null);
    const first = store.getState().entries[0];
    expect(first.kind === 'event' && first.narrative).toBe('进页时的');
  });

  it('加载更多按 nextAfterTurn 取下一页并追加;到头后 nextAfterTurn 为 null', async () => {
    const { api, readHistory } = scripted(page([ev(0), ev(99)], 99), page([ev(100)]));
    const store = createHistoryStore(api);
    store.getState().open('s');
    await flush();
    store.getState().loadMore();
    await flush();
    expect(readHistory).toHaveBeenLastCalledWith('s', 99);
    expect(store.getState().entries.map((e) => (e.kind === 'event' ? e.turn : -1))).toEqual([0, 99, 100]);
    expect(store.getState().nextAfterTurn).toBeNull();
  });

  it('翻页失败:保留已加载的全部记录,整页不进错误态', async () => {
    const { api } = scripted(page([ev(0), ev(1)], 99), new GameApiError('history_read_failed', 'x'));
    const store = createHistoryStore(api);
    store.getState().open('s');
    await flush();
    store.getState().loadMore();
    await flush();
    const s = store.getState();
    expect(s.entries).toHaveLength(2);
    expect(s.phase).toBe('ready');
    expect(s.more).toBe('error');
  });

  it('首屏失败 → error;再试一次重新读第一页', async () => {
    const { api, readHistory } = scripted(new GameApiError('network', 'x'), page([ev(0)]));
    const store = createHistoryStore(api);
    store.getState().open('s');
    await flush();
    expect(store.getState().phase).toBe('error');
    store.getState().retry();
    await flush();
    expect(readHistory).toHaveBeenCalledTimes(2);
    expect(store.getState().phase).toBe('ready');
  });

  it('离开后迟到的响应不写回(不会把旧局内容塞进下一次进页)', async () => {
    let resolve!: (p: HistoryPage) => void;
    const readHistory = vi.fn<HistoryApi['readHistory']>(
      () => new Promise<HistoryPage>((r) => (resolve = r)),
    );
    const store = createHistoryStore({ readHistory });
    store.getState().open('s');
    store.getState().close();
    resolve(page([ev(0)]));
    await flush();
    expect(store.getState().viewing).toBeNull();
    expect(store.getState().entries).toEqual([]);
  });
});
