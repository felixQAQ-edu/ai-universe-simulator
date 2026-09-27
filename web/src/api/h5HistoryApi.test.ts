import { afterEach, describe, expect, it, vi } from 'vitest';
import { GameApiError } from './contract';
import { createH5HistoryApi } from './h5HistoryApi';

// 叙事历史适配层(ADR-025 刀 3):对齐刀 2 的 wire,错误归一为 GameApiError(code 不上屏)。

const api = createH5HistoryApi('');

function json(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

afterEach(() => vi.unstubAllGlobals());

describe('readHistory', () => {
  it('首页不带 afterTurn;解析事件与缺口', async () => {
    const f = vi.fn().mockResolvedValue(
      json(200, {
        saveId: 's',
        source: 'import',
        status: 'ongoing',
        sessionTurn: 3,
        fromTurn: 0,
        toTurn: 99,
        nextAfterTurn: null,
        entries: [
          { kind: 'gap', reason: 'before_recording', fromTurn: 0, toTurn: 1 },
          { kind: 'event', turn: 2, narrative: '雨', playerAction: 'B' },
          { kind: 'weird' },
        ],
      }),
    );
    vi.stubGlobal('fetch', f);
    const p = await api.readHistory('s');
    expect(f.mock.calls[0][0]).toBe('/api/game/s/history');
    expect(p.nextAfterTurn).toBeNull();
    expect(p.entries).toEqual([
      { kind: 'gap', reason: 'before_recording', fromTurn: 0, toTurn: 1 },
      { kind: 'event', turn: 2, narrative: '雨', playerAction: 'B' },
    ]);
  });

  it('翻页带 afterTurn', async () => {
    const f = vi.fn().mockResolvedValue(json(200, { entries: [], nextAfterTurn: 199 }));
    vi.stubGlobal('fetch', f);
    const p = await api.readHistory('s', 99);
    expect(f.mock.calls[0][0]).toBe('/api/game/s/history?afterTurn=99');
    expect(p.nextAfterTurn).toBe(199);
  });

  it.each([
    [501, { error: { code: 'history_unavailable' } }, 'history_unavailable'],
    [404, { error: { code: 'session_not_found' } }, 'session_not_found'],
    [503, { error: { code: 'history_read_failed' } }, 'history_read_failed'],
    [503, null, 'history_read_failed'],
    [502, null, 'http_502'],
  ])('HTTP %i → GameApiError(%s)', async (status, body, code) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(status, body)));
    await expect(api.readHistory('s')).rejects.toMatchObject({ code });
  });

  it('网络失败 → network', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('boom')));
    const err = await api.readHistory('s').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(GameApiError);
    expect((err as GameApiError).code).toBe('network');
  });

  it('形状不对 → bad_response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(200, { nope: 1 })));
    await expect(api.readHistory('s')).rejects.toMatchObject({ code: 'bad_response' });
  });
});
