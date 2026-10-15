// ADR-034 多存档:store 侧接线(开局写 / 回合落地更新 / 续局错误分类 / 多页签 / 写入失败)。
// 第一原则:任何路径都不得在玩家不知情的情况下丢弃一个指针。
import { beforeEach, describe, expect, it } from 'vitest';
import type { GameApi, InitResult, StreamError, TurnDelta, EndingPayload, TurnStream } from '../api';
import { GameApiError } from '../api';
import { SAVE_RETRY_NOTICE, createGameStore } from './gameStore';
import { createSavePointers, type PointerStorage } from './savePointers';

const result = (saveId: string, title = '雨夜便利店', turn = 0): InitResult => ({
  saveId,
  world: {
    schemaVersion: '0.4',
    mode: 'single',
    archetypes: ['rules_creepy'],
    world: { title, background: '背景', dangerLevel: 'high', tone: 't' },
    character: { attributes: { hp: 100 }, traits: [], inventory: [] },
    rules: [],
    state: { turn, status: 'ongoing', timeline: '', logSummary: '', log: [] },
    endings: [],
  },
  openingNarrative: '开场。',
  availableActions: [{ id: 'A', text: '观察', hint: '' }],
  attributes: [{ key: 'hp', displayName: '体力' }],
});

class Stream implements TurnStream {
  d: Array<(x: TurnDelta) => void> = [];
  e: Array<(x: EndingPayload) => void> = [];
  r: Array<(x: StreamError) => void> = [];
  c: Array<() => void> = [];
  onNarrative() {}
  onDelta(cb: (x: TurnDelta) => void) {
    this.d.push(cb);
  }
  onEnding(cb: (x: EndingPayload) => void) {
    this.e.push(cb);
  }
  onError(cb: (x: StreamError) => void) {
    this.r.push(cb);
  }
  onClose(cb: () => void) {
    this.c.push(cb);
  }
  close() {}
}

/** 可编排的 api:init 按队列回结果(可延迟放行),resume 按队列回结果或抛错。 */
function scriptedApi() {
  const initQueue: Array<{ res: InitResult; gate?: Promise<void> }> = [];
  const resumeQueue: Array<InitResult | GameApiError> = [];
  let last: Stream | null = null;
  const api: GameApi = {
    async listArchetypes() {
      return { archetypes: [], fusions: [] };
    },
    async initGame() {
      const next = initQueue.shift()!;
      if (next.gate) await next.gate;
      return next.res;
    },
    async resumeGame() {
      const next = resumeQueue.shift()!;
      if (next instanceof GameApiError) throw next;
      return next;
    },
    openTurnStream() {
      last = new Stream();
      return last;
    },
  };
  return { api, initQueue, resumeQueue, stream: () => last! };
}

const raw = (id: string) => localStorage.getItem(`aiuniverse.save.${id}`);
const ptr = (id: string) => JSON.parse(raw(id) ?? 'null');

beforeEach(() => localStorage.clear());

describe('存储形态的依据:两个页签共享同一个 storage', () => {
  it('两个 store 实例交错开局 → 两条都在(一个数组存全部会互相覆盖)', async () => {
    const a = scriptedApi();
    const b = scriptedApi();
    let releaseA!: () => void;
    a.initQueue.push({ res: result('sA', '页签一的世界'), gate: new Promise((r) => (releaseA = r)) });
    b.initQueue.push({ res: result('sB', '页签二的世界') });
    const tab1 = createGameStore(a.api);
    const tab2 = createGameStore(b.api);

    const p1 = tab1.getState().startGame('rules_creepy'); // 页签一先开、后落地
    await tab2.getState().startGame('rules_creepy'); // 页签二后开、先落地
    releaseA();
    await p1;

    expect(ptr('sA').title).toBe('页签一的世界');
    expect(ptr('sB').title).toBe('页签二的世界');
    tab2.getState().refreshSaves(); // storage 事件只用来刷新显示
    expect(tab2.getState().saves.map((s) => s.saveId).sort()).toEqual(['sA', 'sB']);
    expect(tab1.getState().saves.map((s) => s.saveId).sort()).toEqual(['sA', 'sB']);
  });
});

describe('回合落地更新摘要', () => {
  it('delta → 回合号与本设备上次游玩时间;ending → 已结局', async () => {
    const { api, initQueue, stream } = scriptedApi();
    initQueue.push({ res: result('s1') });
    let t = 100;
    const store = createGameStore(api, { now: () => t });
    await store.getState().startGame('rules_creepy');
    expect(ptr('s1')).toMatchObject({ turn: 0, ended: false, lastPlayedAt: 100 });

    t = 200;
    store.getState().chooseAction('A');
    stream().d.forEach((cb) =>
      cb({ turn: 1, status: 'ongoing', attributes: { hp: 90 }, discoveredRules: [], availableActions: [{ id: 'A', text: 'x', hint: '' }] }),
    );
    expect(ptr('s1')).toMatchObject({ turn: 1, ended: false, lastPlayedAt: 200 });

    t = 300;
    stream().e.forEach((cb) => cb({ id: 'e', title: '终', description: '' }));
    expect(ptr('s1')).toMatchObject({ turn: 1, ended: true, lastPlayedAt: 300 });
    expect(store.getState().saves[0]).toMatchObject({ saveId: 's1', ended: true });
  });
});

describe('续局错误分类(ADR-034 已决 6)', () => {
  function seeded() {
    const p = createSavePointers();
    p.write({
      saveId: 's1', title: '旧名', archetypes: ['rules_creepy'], turn: 4,
      ended: false, lastPlayedAt: 1, availability: 'available',
    });
    return raw('s1');
  }

  it('404 → 标 unavailable、不删除;之后重试成功恢复为 available 并校正摘要', async () => {
    seeded();
    const { api, resumeQueue } = scriptedApi();
    resumeQueue.push(new GameApiError('session_not_found', '存档不存在'));
    resumeQueue.push(result('s1', '校正后的世界名', 6));
    const store = createGameStore(api, { now: () => 777 });

    await store.getState().resumeGame('s1');
    expect(ptr('s1')).toMatchObject({ availability: 'unavailable', title: '旧名', turn: 4 });
    expect(store.getState().saves.map((s) => s.availability)).toEqual(['unavailable']);
    expect(store.getState().status).toBe('idle');

    await store.getState().resumeGame('s1'); // 仍可点击重试
    expect(ptr('s1')).toMatchObject({
      availability: 'available', title: '校正后的世界名', turn: 6, lastPlayedAt: 777,
    });
    expect(store.getState().status).toBe('awaiting');
  });

  it.each([
    ['网络错误', new GameApiError('network', '网络不可用')],
    ['503', new GameApiError('server_at_capacity', '此刻太挤')],
    ['其他 5xx', new GameApiError('resume_failed', '续局失败')],
  ])('%s → 条目逐字节不变,只提示可重试', async (_l, err) => {
    const before = seeded();
    const { api, resumeQueue } = scriptedApi();
    resumeQueue.push(err);
    const store = createGameStore(api);

    await store.getState().resumeGame('s1');
    expect(raw('s1')).toBe(before);
    expect(store.getState().saves.map((s) => s.availability)).toEqual(['available']);
    expect(store.getState().saveNotice).toBe(SAVE_RETRY_NOTICE);
    expect(store.getState().status).toBe('idle');
  });

  it('世代过期(续局在途时返回)→ 什么都不写,404 也不标', async () => {
    const before = seeded();
    let reject!: (e: unknown) => void;
    const { api } = scriptedApi();
    api.resumeGame = () => new Promise((_res, rej) => (reject = rej));
    const store = createGameStore(api);
    const pending = store.getState().resumeGame('s1');
    store.getState().reset();
    reject(new GameApiError('session_not_found', '存档不存在'));
    await pending;
    expect(raw('s1')).toBe(before);
    expect(store.getState().saveNotice).toBeNull();
  });
});

describe('localStorage 写入失败:best-effort,当局照常(ADR-034 已知代价 2)', () => {
  it('开局写不进 → 照常进游戏,列表里就是没有这一条', async () => {
    const broken: PointerStorage = {
      getItem: (k) => localStorage.getItem(k),
      setItem: () => {
        throw new Error('QuotaExceededError');
      },
      removeItem: (k) => localStorage.removeItem(k),
      key: (i) => localStorage.key(i),
      get length() {
        return localStorage.length;
      },
    };
    const { api, initQueue } = scriptedApi();
    initQueue.push({ res: result('s1') });
    const store = createGameStore(api, { pointers: createSavePointers(() => broken) });
    await store.getState().startGame('rules_creepy');
    expect(store.getState().status).toBe('awaiting');
    expect(store.getState().saves).toEqual([]);
  });
});
