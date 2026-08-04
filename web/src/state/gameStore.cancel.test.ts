// 在途 init/resume 的**世代守卫**(线 C 导航层)。
//
// 守的不是「返回键」这个控件,而是一个原本就存在的洞:**异步结果回来时,玩家可能已经
// 不在那个上下文里了**。同一个洞有三条路径,`chooseAction` 的回合流早就补了
// (`activeStream` 比对,见 gameStore.test.ts),`startGame` / `resumeGame` 这两条一直没有——
// 本文件补的是剩下的三分之二。
//
// 为什么它是「取消/返回」能否成立的前提:world-gen 线上首局实测 ~120s,期间玩家点了取消,
// 若无守卫,那次生成照样落地把他扔进他刚拒绝的世界,并 writeSavedId 冲掉原存档指针 ——
// 一个看起来有效、实际会背叛用户的控件比没有这个控件更糟。
//
// 注:守卫只保证**客户端不被过期结果改写**;服务端那次调用拦不住(plain POST 阻塞跑完 +
// GameSessionManager.create 无条件写盘),孤儿档已入 backlog,不在本轮修。

import { beforeEach, describe, expect, it } from 'vitest';
import type { GameApi, InitResult, TurnStream } from '../api';
import { GameApiError } from '../api';
import { createGameStore } from './gameStore';

const SAVE_ID_KEY = 'aiuniverse.saveId';

const RESULT: InitResult = {
  saveId: 's-late',
  world: {
    schemaVersion: '0.4',
    mode: 'single',
    archetypes: ['rules_creepy'],
    world: { title: '雨夜便利店', background: '背景', dangerLevel: 'high', tone: 'tone' },
    character: { attributes: { hp: 100, san: 100 }, traits: [], inventory: [] },
    rules: [],
    state: { turn: 0, status: 'ongoing', timeline: '', logSummary: '', log: [] },
    endings: [],
  },
  openingNarrative: '开场。',
  availableActions: [{ id: 'A', text: '观察', hint: '' }],
  attributes: [{ key: 'hp', displayName: '体力' }],
};

/** 手动放行的 init/resume:模拟「生成很久」这段窗口,让测试能在其间点取消。 */
function deferredApi() {
  let releaseInit: (() => void) | null = null;
  let releaseResume: ((mode: 'ok' | 'fail') => void) | null = null;
  const api: GameApi = {
    async listArchetypes() {
      return { archetypes: [], fusions: [] };
    },
    initGame() {
      return new Promise<InitResult>((resolve) => {
        releaseInit = () => resolve(RESULT);
      });
    },
    resumeGame() {
      return new Promise<InitResult>((resolve, reject) => {
        releaseResume = (mode) =>
          mode === 'ok'
            ? resolve({ ...RESULT, saveId: 's-resumed' })
            : reject(new GameApiError('session_not_found', '存档不存在'));
      });
    },
    openTurnStream(): TurnStream {
      throw new Error('not used');
    },
  };
  return {
    api,
    releaseInit: () => releaseInit?.(),
    releaseResume: (mode: 'ok' | 'fail' = 'ok') => releaseResume?.(mode),
  };
}

beforeEach(() => {
  localStorage.clear();
});

describe('世代守卫 · startGame', () => {
  it('生成期间取消 → 迟到的世界被整段丢弃(不进游戏、不写 saveId)', async () => {
    const { api, releaseInit } = deferredApi();
    const store = createGameStore(api);

    const pending = store.getState().startGame('rules_creepy');
    expect(store.getState().status).toBe('initializing');

    store.getState().reset(); // ← 取消
    expect(store.getState().status).toBe('idle');

    releaseInit();
    await pending;

    const s = store.getState();
    expect(s.status).toBe('idle'); // 没有被拽回那个世界
    expect(s.world).toBeNull();
    expect(s.saveId).toBeNull();
    expect(localStorage.getItem(SAVE_ID_KEY)).toBeNull(); // 存档指针未被冲掉
    expect(s.resumableSaveId).toBeNull();
  });

  it('取消后原存档指针保住:迟到的 init 不覆盖玩家已有的续局入口', async () => {
    localStorage.setItem(SAVE_ID_KEY, 's-old');
    const { api, releaseInit } = deferredApi();
    const store = createGameStore(api);

    const pending = store.getState().startGame('rules_creepy');
    store.getState().reset();
    releaseInit();
    await pending;

    expect(localStorage.getItem(SAVE_ID_KEY)).toBe('s-old');
    expect(store.getState().resumableSaveId).toBe('s-old');
  });

  it('取消后失败也不弹错误屏(玩家已经不在那儿了)', async () => {
    let reject: ((e: unknown) => void) | null = null;
    const api: GameApi = {
      async listArchetypes() {
        return { archetypes: [], fusions: [] };
      },
      initGame() {
        return new Promise<InitResult>((_res, rej) => {
          reject = rej;
        });
      },
      async resumeGame() {
        return RESULT;
      },
      openTurnStream(): TurnStream {
        throw new Error('not used');
      },
    };
    const store = createGameStore(api);
    const pending = store.getState().startGame('rules_creepy');
    store.getState().reset();
    reject!(new GameApiError('world_gen_failed', '世界生成失败'));
    await pending;

    expect(store.getState().status).toBe('idle');
    expect(store.getState().errorMessage).toBeNull();
  });

  it('未取消 → 照常进游戏(守卫不误伤正常路径)', async () => {
    const { api, releaseInit } = deferredApi();
    const store = createGameStore(api);
    const pending = store.getState().startGame('rules_creepy');
    releaseInit();
    await pending;

    const s = store.getState();
    expect(s.status).toBe('awaiting');
    expect(s.saveId).toBe('s-late');
    expect(localStorage.getItem(SAVE_ID_KEY)).toBe('s-late');
  });
});

describe('世代守卫 · resumeGame(同形状的洞)', () => {
  it('续局期间返回 → 迟到的续局被丢弃', async () => {
    localStorage.setItem(SAVE_ID_KEY, 's-old');
    const { api, releaseResume } = deferredApi();
    const store = createGameStore(api);

    const pending = store.getState().resumeGame();
    store.getState().reset();
    releaseResume('ok');
    await pending;

    expect(store.getState().status).toBe('idle');
    expect(store.getState().world).toBeNull();
  });

  it('返回后迟到的续局失败**不得清 saveId**:玩家可能已开新局,清的是新局的指针', async () => {
    localStorage.setItem(SAVE_ID_KEY, 's-old');
    const { api, releaseResume } = deferredApi();
    const store = createGameStore(api);

    const pending = store.getState().resumeGame();
    store.getState().reset();
    releaseResume('fail');
    await pending;

    expect(localStorage.getItem(SAVE_ID_KEY)).toBe('s-old');
    expect(store.getState().resumableSaveId).toBe('s-old');
  });

  it('未返回时续局失败 → 仍按原口径静默清档(守卫不改既有行为)', async () => {
    localStorage.setItem(SAVE_ID_KEY, 's-gone');
    const { api, releaseResume } = deferredApi();
    const store = createGameStore(api);

    const pending = store.getState().resumeGame();
    releaseResume('fail');
    await pending;

    expect(store.getState().status).toBe('idle');
    expect(localStorage.getItem(SAVE_ID_KEY)).toBeNull();
  });
});

describe('reset 保住续局入口(返回不弃局)', () => {
  it('游戏中返回 → 回选择屏,resumableSaveId 与 localStorage 都还在', async () => {
    const { api, releaseInit } = deferredApi();
    const store = createGameStore(api);
    const pending = store.getState().startGame('rules_creepy');
    releaseInit();
    await pending;
    expect(store.getState().status).toBe('awaiting');

    store.getState().reset();

    const s = store.getState();
    expect(s.status).toBe('idle');
    expect(s.resumableSaveId).toBe('s-late');
    expect(localStorage.getItem(SAVE_ID_KEY)).toBe('s-late');
  });
});
