import { afterEach, describe, expect, it, vi } from 'vitest';
import type { EndingPayload, StreamError, TurnDelta } from './contract';
import { GameApiError } from './contract';
import { createH5GameApi } from './h5GameApi';

// api/ 适配层单测:mock fetch / 合成 SSE 字节流,断言 TurnStream 正确分发四类事件、
// init 解析正确、HTTP/网络失败归一为 onError / GameApiError。

const api = createH5GameApi('');

/** 把整段 SSE 文本切成任意字节块(可跨帧/跨行边界),验证 buffer 累积正确。 */
function readerFromChunks(text: string, chunkSize: number) {
  const bytes = new TextEncoder().encode(text);
  let i = 0;
  return {
    getReader() {
      return {
        read() {
          if (i >= bytes.length) return Promise.resolve({ done: true, value: undefined });
          const slice = bytes.slice(i, i + chunkSize);
          i += chunkSize;
          return Promise.resolve({ done: false, value: slice });
        },
      };
    },
  };
}

function sseResponse(text: string, chunkSize = 7) {
  return { ok: true, status: 200, body: readerFromChunks(text, chunkSize) } as unknown as Response;
}

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

afterEach(() => vi.unstubAllGlobals());

describe('initGame', () => {
  it('解析成功响应为 InitResult', async () => {
    const payload = {
      saveId: 's1',
      world: { schemaVersion: '0.3', world: { title: '雨夜便利店' }, rules: [], character: {} },
      openingNarrative: '午夜两点……',
      availableActions: [{ id: 'A', text: '观察', hint: '' }],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, payload)));

    const res = await api.initGame('rules_creepy');
    expect(res.saveId).toBe('s1');
    expect(res.openingNarrative).toBe('午夜两点……');
    expect(res.availableActions[0].id).toBe('A');
    // 单值走旧 wire {archetype}(向后兼容,ADR-013)。
    const fetchMock = vi.mocked(fetch);
    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body).toEqual({ archetype: 'rules_creepy' });
  });

  it('双值(有序,host 在前)→ wire 发 {archetypes:[...]}(ADR-013 融合)', async () => {
    const payload = {
      saveId: 's2',
      world: { schemaVersion: '0.4', world: { title: '识海遗蜕' }, rules: [], character: {} },
      openingNarrative: '识海无垠……',
      availableActions: [{ id: 'A', text: '辨读刻文', hint: '' }],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, payload)));

    const res = await api.initGame(['cultivation', 'rules_creepy']);
    expect(res.saveId).toBe('s2');
    const fetchMock = vi.mocked(fetch);
    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body).toEqual({ archetypes: ['cultivation', 'rules_creepy'] }); // 顺序保持 host 在前
  });

  it('502 → GameApiError,code 取自 body.error.code', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(502, { error: { code: 'world_gen_failed', message: '世界生成失败' } }),
      ),
    );
    await expect(api.initGame('rules_creepy')).rejects.toMatchObject({
      name: 'GameApiError',
      code: 'world_gen_failed',
    });
  });

  it('网络异常 → GameApiError(network)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('boom')));
    const err = await api.initGame('rules_creepy').catch((e) => e);
    expect(err).toBeInstanceOf(GameApiError);
    expect((err as GameApiError).code).toBe('network');
  });

  it('请求头带 X-Device-Id(ADR-016 软闸设备键),经真实 Headers 归一后仍在、非空且跨请求稳定', async () => {
    const payload = {
      saveId: 's1',
      world: { schemaVersion: '0.4', world: { title: 'x' }, rules: [], character: {} },
      openingNarrative: '',
      availableActions: [{ id: 'A', text: '观察', hint: '' }],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, payload)));

    await api.initGame('rules_creepy');
    await api.initGame('rules_creepy');
    const fetchMock = vi.mocked(fetch);
    // 过 new Headers():这正是浏览器发送前对 init.headers 的归一化——比读原始对象字面量更贴近
    // 「真实发出的请求头」(能挡住值变 falsy 被 drop / header 结构重排丢键 / 大小写等一类真回归)。
    // .get 大小写不敏感,与后端 servlet getHeader("X-Device-Id") 同口径。
    const d1 = new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).get('X-Device-Id');
    const d2 = new Headers((fetchMock.mock.calls[1][1] as RequestInit).headers).get('X-Device-Id');
    expect(d1).toBeTruthy();
    expect(d1).not.toBe(''); // 非空(否则后端视作无设备键 → 软闸失效)
    expect(d2).toBe(d1); // 同设备恒定
  });

  it('429(成本闸门)→ GameApiError(quota_exceeded),消息透传', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(429, { error: { code: 'quota_exceeded', message: '今日新世界名额已满,明天再来' } }),
      ),
    );
    await expect(api.initGame('rules_creepy')).rejects.toMatchObject({
      name: 'GameApiError',
      code: 'quota_exceeded',
      message: '今日新世界名额已满,明天再来',
    });
  });
});

describe('listArchetypes', () => {
  it('解析 {archetypes:[...]} → 数组', async () => {
    const payload = {
      archetypes: [
        { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: 'x', vibeTag: '诡异', active: true },
        { archetype: 'cultivation', displayName: '修仙', tagline: null, vibeTag: null, active: false },
      ],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, payload)));

    const list = await api.listArchetypes();
    expect(list).toHaveLength(2);
    expect(list[0]).toMatchObject({ archetype: 'rules_creepy', active: true });
    expect(list[1].active).toBe(false);
  });

  it('异常响应体 → 空数组兜底', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { nope: 1 })));
    expect(await api.listArchetypes()).toEqual([]);
  });

  it('非 2xx → GameApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 } as Response));
    await expect(api.listArchetypes()).rejects.toBeInstanceOf(GameApiError);
  });
});

describe('openTurnStream', () => {
  it('按序分发 narrative(多次)→ delta → ending,且跨 chunk 切分正确', async () => {
    const wire =
      'event: narrative\ndata: {"text":"你听见"}\n\n' +
      'event: narrative\ndata: {"text":"敲玻璃声"}\n\n' +
      'event: delta\ndata: {"turn":1,"status":"ongoing","hp":90,"san":70,"discoveredRules":[{"id":1,"content":"不要回应"}],"availableActions":[{"id":"A","text":"继续","hint":""}]}\n\n' +
      'event: ending\ndata: {"id":"survive_dawn","title":"撑到天亮","description":"你活下来了。"}\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(wire, 5)));

    const narratives: string[] = [];
    let delta: TurnDelta | null = null;
    let ending: EndingPayload | null = null;
    const order: string[] = [];

    await new Promise<void>((resolve) => {
      const stream = api.openTurnStream('s1', 0, 'A');
      stream.onNarrative((t) => {
        narratives.push(t);
        order.push('n');
      });
      stream.onDelta((d) => {
        delta = d;
        order.push('d');
      });
      stream.onEnding((e) => {
        ending = e;
        order.push('e');
      });
      stream.onClose(() => resolve());
    });

    expect(narratives).toEqual(['你听见', '敲玻璃声']);
    // 数值轴(top-level wire 字段)被收进 attributes map(规则怪谈 hp/san)。
    expect(delta).toMatchObject({ turn: 1, attributes: { hp: 90, san: 70 } });
    expect(delta!.discoveredRules[0]).toEqual({ id: 1, content: '不要回应' });
    expect(ending).toMatchObject({ id: 'survive_dawn', title: '撑到天亮' });
    // 时序:叙事先,delta 后,ending 最后。
    expect(order).toEqual(['n', 'n', 'd', 'e']);
  });

  it('末日 delta:top-level hp/hunger 收进 attributes(对 key 无知,不写死 hp/san)', async () => {
    const wire =
      'event: delta\ndata: {"turn":3,"status":"ongoing","hp":70,"hunger":40,"discoveredRules":[],"availableActions":[{"id":"A","text":"搜寻","hint":""}]}\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(wire, 6)));

    let delta: TurnDelta | null = null;
    await new Promise<void>((resolve) => {
      const stream = api.openTurnStream('s1', 2, 'A');
      stream.onDelta((d) => (delta = d));
      stream.onClose(() => resolve());
    });

    expect(delta!.attributes).toEqual({ hp: 70, hunger: 40 });
    // 结构字段不混进 attributes。
    expect(delta!.attributes).not.toHaveProperty('turn');
    expect(delta!.turn).toBe(3);
  });

  it('error 事件 → onError', async () => {
    const wire = 'event: error\ndata: {"code":"illegal_action","message":"该选项不可用"}\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(wire)));

    const errors: StreamError[] = [];
    await new Promise<void>((resolve) => {
      const stream = api.openTurnStream('s1', 0, 'Z');
      stream.onError((e) => errors.push(e));
      stream.onClose(() => resolve());
    });
    expect(errors).toEqual([{ code: 'illegal_action', message: '该选项不可用' }]);
  });

  it('HTTP 404(会话不存在)→ onError(session_not_found) + onClose', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 } as Response));

    const errors: StreamError[] = [];
    let closed = false;
    await new Promise<void>((resolve) => {
      const stream = api.openTurnStream('missing', 0, 'A');
      stream.onError((e) => errors.push(e));
      stream.onClose(() => {
        closed = true;
        resolve();
      });
    });
    expect(errors[0].code).toBe('session_not_found');
    expect(closed).toBe(true);
  });

  it('回合流请求头也带 X-Device-Id(ADR-016,单设备日回合计数)', async () => {
    const wire = 'event: delta\ndata: {"turn":1,"status":"ongoing","hp":90,"discoveredRules":[],"availableActions":[]}\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(wire)));

    await new Promise<void>((resolve) => {
      const stream = api.openTurnStream('s1', 0, 'A');
      stream.onClose(() => resolve());
    });
    // 同 init:过 new Headers() 断言真实归一后 X-Device-Id 仍在(sse.ts 合并 headers 时
    // 附加头先展开、Content-Type/Accept 后覆盖,验设备键未被固定头挤掉、固定头也在)。
    const headers = new Headers((vi.mocked(fetch).mock.calls[0][1] as RequestInit).headers);
    expect(headers.get('X-Device-Id')).toBeTruthy();
    expect(headers.get('X-Device-Id')).not.toBe('');
    expect(headers.get('Content-Type')).toBe('application/json'); // 固定头不被覆盖
    expect(headers.get('Accept')).toBe('text/event-stream');
  });
});
