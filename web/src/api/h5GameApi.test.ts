import { afterEach, describe, expect, it, vi } from 'vitest';
// `?raw` = Vite 原生的「按原文读进来」,用于本文件末尾那条源码级断言。
// ⚠️ 不走 node:fs + import.meta.url:在 jsdom env 下模块 URL 不是 file: scheme,
//    `fileURLToPath` 会抛 ——「fs 读得到」与「这里解析得出路径」是两件事,已实测踩过。
import sseSource from './sse.ts?raw';
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

/**
 * 非 2xx 的回合响应桩。`body` 是**原文字符串**(sse.ts 读的是 `resp.text()`,不是 `.json()`)——
 * 省略即空 body,对应网关/默认 /error 那族**裸状态码**。
 */
function errorResponse(status: number, body = '') {
  return {
    ok: false,
    status,
    text: () => Promise.resolve(body),
  } as unknown as Response;
}

/** 跑一次回合流,收集 onError 收到的全部 StreamError(流结束才 resolve)。 */
async function collectTurnErrors(resp: Response): Promise<StreamError[]> {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(resp));
  const errors: StreamError[] = [];
  await new Promise<void>((resolve) => {
    const stream = api.openTurnStream('s1', 0, 'A');
    stream.onError((e) => errors.push(e));
    stream.onClose(() => resolve());
  });
  return errors;
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
  it('解析 {archetypes:[...], fusions:[...]} → 两张表(ADR-019:目录与组合表同一次请求)', async () => {
    const payload = {
      archetypes: [
        { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: 'x', vibeTag: '诡异', active: true },
        { archetype: 'cultivation', displayName: '修仙', tagline: null, vibeTag: null, active: false },
      ],
      fusions: [{ host: 'cultivation', foreign: 'rules_creepy', key: 'cultivation×rules_creepy' }],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, payload)));

    const { archetypes: list, fusions } = await api.listArchetypes();
    expect(list).toHaveLength(2);
    expect(list[0]).toMatchObject({ archetype: 'rules_creepy', active: true });
    expect(list[1].active).toBe(false);
    expect(fusions).toEqual([{ host: 'cultivation', foreign: 'rules_creepy', key: 'cultivation×rules_creepy' }]);
  });

  it('异常响应体 → 两张表各自空兜底', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { nope: 1 })));
    expect(await api.listArchetypes()).toEqual({ archetypes: [], fusions: [] });
  });

  it('老后端(有世界表、无 fusions 字段)→ 空组合表,拖不出融合但选择屏照常可用', async () => {
    const payload = {
      archetypes: [
        { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: 'x', vibeTag: '诡异', active: true },
      ],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    const catalog = await api.listArchetypes();
    expect(catalog.archetypes).toHaveLength(1);
    expect(catalog.fusions).toEqual([]);
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

  // 载荷原为 illegal_action;ADR-022 刀 2 后该 code 不再走 SSE(守卫 1 前移到 controller),
  // 故换成仍活的 busy —— 断言意图(SSE error 帧 → onError)一个字未变。
  it('error 事件 → onError', async () => {
    const wire = 'event: error\ndata: {"code":"busy","message":"上一回合仍在结算,请稍候"}\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(wire)));

    const errors: StreamError[] = [];
    await new Promise<void>((resolve) => {
      const stream = api.openTurnStream('s1', 0, 'Z');
      stream.onError((e) => errors.push(e));
      stream.onClose(() => resolve());
    });
    expect(errors).toEqual([{ code: 'busy', message: '上一回合仍在结算,请稍候' }]);
  });

  // 桩补了 text:—— 一个没有 .text() 的东西不是 Response,而本刀起 sse.ts 要读 body 原文。
  // ⚠️ 补桩最经典的失效方式是把一条还在守的测试变成永远绿的,故变异验证在下面钉住:
  //    摘掉 404 兜底,这条必须变红(已实跑确认)。
  it('HTTP 404(会话不存在)→ onError(session_not_found) + onClose', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(404)));

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

// ── 回合流的 HTTP 错误语义映射(ADR-022 刀 1,立字 8/9/10)────────────────
//
// 本刀把「HTTP 状态 → 业务 code」从 sse.ts 搬回这一层。sse.ts 自此只交
// 「状态码 + body 原文」,故下面全部经**公开 API**(openTurnStream → onError)断言,
// 与映射住在哪一层无关 —— 换句话说,这些用例在搬家前后都该是绿的,
// 它们守的是**行为**;守「映射不许搬回去」的是本文件最后那条源码级断言。
describe('回合流 HTTP 错误 → StreamError 的优先级链', () => {
  it('body 带 error.code → code 与 message 都取 body(后端主动发的码)', async () => {
    const body = JSON.stringify({
      error: { code: 'server_at_capacity', message: '此刻同时进行的回合太多,请过几秒再点一次' },
    });
    const errors = await collectTurnErrors(errorResponse(503, body));
    expect(errors).toEqual([
      { code: 'server_at_capacity', message: '此刻同时进行的回合太多,请过几秒再点一次' },
    ]);
  });

  // ⚠️ 本条是「body 优先压过状态兜底」的看门人:503 有两种读法,
  //    带 body = 刀 2 的准入拒绝(过几秒再来),裸 = 网关/默认 /error(稍后再试)。
  //    摘掉 body 优先那一步,上面那条必红 —— 变异已实跑确认。
  it('503 裸 body → service_unavailable(与上一条同状态码,读法不同)', async () => {
    const errors = await collectTurnErrors(errorResponse(503));
    expect(errors).toEqual([{ code: 'service_unavailable', message: '服务暂时不可用,请稍后再试' }]);
  });

  it('502 裸 body → service_unavailable(网关那族,不写「过几秒」)', async () => {
    const errors = await collectTurnErrors(errorResponse(502));
    expect(errors[0].code).toBe('service_unavailable');
    expect(errors[0].message).toBe('服务暂时不可用,请稍后再试');
    // 时长不知道就不给数字:「过几秒」是 server_at_capacity 的专属(ADR-022 裁定 4)。
    expect(errors[0].message).not.toContain('过几秒');
  });

  it('5xx 判据是 status >= 500 而非枚举(504 同样命中,不留缝)', async () => {
    const errors = await collectTurnErrors(errorResponse(504));
    expect(errors[0].code).toBe('service_unavailable');
  });

  it('body 非 JSON(反代的 HTML 错误页)→ 退状态兜底,不抛', async () => {
    const errors = await collectTurnErrors(errorResponse(502, '<html>Bad Gateway</html>'));
    expect(errors).toEqual([{ code: 'service_unavailable', message: '服务暂时不可用,请稍后再试' }]);
  });

  it('404 裸 body → session_not_found + 一句玩家做得到的话', async () => {
    const errors = await collectTurnErrors(errorResponse(404));
    expect(errors[0].code).toBe('session_not_found');
    // 文案里指的动作真实存在:BackButton 的「返回」(按钮上的字就是这两个);
    // 回合屏上没有第二个出口(「重新生成」只在 initError 屏、「取消」只在 loading 屏)。
    expect(errors[0].message).toBe('这一局的存档已经找不到了,点『返回』重新开始');
    expect(errors[0].message).not.toContain('HTTP');
    // ⚠️ 刀 1.5 回归闸:原措辞让玩家「返回后点『继续上局』」,而那个按钮点下去必然 404
    // 且静默消失(冒烟逐字证伪)。指针现在随死档一并清掉 → 那个按钮压根不该在,
    // 文案更不该指向它。**不许退回去。**
    expect(errors[0].message).not.toContain('继续上局');
  });

  it('code 取自 body、message 缺失 → message 退状态兜底(两者各自独立兜底)', async () => {
    const errors = await collectTurnErrors(errorResponse(503, JSON.stringify({ error: { code: 'x' } })));
    expect(errors[0]).toEqual({ code: 'x', message: '服务暂时不可用,请稍后再试' });
  });

  it('未覆盖状态(418)→ http_418 最后兜底仍在', async () => {
    const errors = await collectTurnErrors(errorResponse(418));
    expect(errors).toEqual([{ code: 'http_418', message: '请求失败(HTTP 418)' }]);
  });

  // 立字 10:409 → busy 是死映射(服务端全仓零处 409/CONFLICT),本刀删除。
  // 若有人复活它,这条会红 —— 409 是 4xx 非 404,该落最后兜底。
  it('409 不再映射成 busy(死映射已删,落最后兜底)', async () => {
    const errors = await collectTurnErrors(errorResponse(409));
    expect(errors[0].code).toBe('http_409');
  });

  it('fetch reject → transport/network,code 与文案与本刀之前逐字相同', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Failed to fetch')));
    const errors: StreamError[] = [];
    await new Promise<void>((resolve) => {
      const stream = api.openTurnStream('s1', 0, 'A');
      stream.onError((e) => errors.push(e));
      stream.onClose(() => resolve());
    });
    expect(errors).toEqual([{ code: 'network', message: 'Failed to fetch' }]);
  });

  it('2xx 但无可读流 → transport/no_body,行为不变', async () => {
    const errors = await collectTurnErrors({ ok: true, status: 200, body: null } as unknown as Response);
    expect(errors).toEqual([{ code: 'no_body', message: '响应无可读流' }]);
  });

  // resp.text() 抛(body 已被消费 / 拿到的不是规规矩矩的 Response)→ 当空 body 走状态兜底。
  // ⚠️ 若不吞这个异常,它会落进外层 .catch 被报成一条 network —— **不是静默,是说假话**。
  it('读 body 原文失败 → 按空 body 兜底,而不是谎报成 network', async () => {
    const errors = await collectTurnErrors({
      ok: false,
      status: 502,
      text: () => Promise.reject(new Error('body already consumed')),
    } as unknown as Response);
    expect(errors[0].code).toBe('service_unavailable');
  });
});

// ── 映射不许搬回 sse.ts(ADR-022 立字 10 的看门人)──────────────────────
//
// ⚠️ 这条**必须读源码**,不能靠行为断言:若有人把 httpErrorCode 加回 sse.ts、
// 而这一层原样透传,**公开 API 的可见行为可以完全一样**,上面那一整个 describe 照样全绿。
// 「语义映射住在哪一层」是行为测试**在原理上看不见**的东西。
// 形状照 ADR-021 刀 1(族层)/ 刀 2(EventLoopService 不得含 "vigor")的源码级断言先例。
describe('sse.ts 的边界(源码级断言)', () => {
  const source = sseSource;

  it('自陈的边界与实现一致:不出现任何由 HTTP 状态翻译而来的业务 code', () => {
    // 反例逐条列出而非正则模糊匹配 —— 变红时要一眼看出是哪一个溜回去了。
    // ⚠️ 它对**注释**同样敏感(误报方向 = 偏严),这是有意的:
    //    这几个词若出现在本模块里,不管在代码还是注释,都说明那条边界又开始模糊了。
    for (const code of ['session_not_found', 'service_unavailable', 'server_at_capacity', 'http_']) {
      expect(source).not.toContain(code);
    }
    // busy 单独一条:它是 409 死映射的残骸,也是最可能被「顺手补回来」的那个。
    expect(source).not.toMatch(/'busy'|"busy"/);
  });

  // ⚠️ 下面两条是**字面串比对,对格式敏感**:类型声明若被 prettier / 手动重排(换行、
  //    分号改逗号、加空格),断言会变红,而**那是格式原因不是语义原因**。
  //    偏严的方向是有意的(宁可误报),但**它哪天变红,第一步先看是不是排版** ——
  //    ⚠️ **不许靠放宽断言来「修」它**:放宽一次,它就再也挡不住真正要挡的那件事
  //    (「http 分支长出了一个 code 字段」)。排版变了就同步改这两行字面串。
  it('构造保证仍在:http 失败分支只交状态码与 body 原文,没有 code 字段', () => {
    expect(source).toContain("kind: 'http'; status: number; body: string");
    // transport 的 reason 是闭合两值联合 —— 加一个业务码必须先在这里加宽,review 里看得见。
    expect(source).toContain("reason: 'network' | 'no_body'");
  });
});
