// 续局状态机(ADR-015 Slice 2 → ADR-034):localStorage 一局一键 → 「我的存档」→ resumeGame(saveId) 恢复;
// 失败一律不删指针(404 标 unavailable,其余条目不变)。mock api 注入,零网络(ADR-003 边界)。
import { beforeEach, describe, expect, it } from 'vitest';
import type { GameApi, InitResult, TurnStream } from '../api';
import { GameApiError } from '../api';
import { createGameStore } from './gameStore';

const LEGACY_KEY = 'aiuniverse.saveId';
const ptr = (id: string) => JSON.parse(localStorage.getItem(`aiuniverse.save.${id}`) ?? 'null');

const RESUME_RESULT: InitResult = {
  saveId: 's-resume',
  world: {
    schemaVersion: '0.4',
    mode: 'single',
    archetypes: ['rules_creepy'],
    world: { title: '雨夜便利店', background: '背景兜底文案', dangerLevel: 'high', tone: 'tone' },
    character: { attributes: { hp: 70, san: 55 }, traits: [], inventory: [] },
    rules: [{ id: 1, content: '不要回应敲玻璃', discovered: true }],
    state: {
      turn: 3,
      status: 'ongoing',
      timeline: '',
      logSummary: '',
      log: [
        { turn: 2, narrative: '旧回合叙事', playerAction: 'A' },
        { turn: 3, narrative: '雨声忽然停了。', playerAction: 'B' },
      ],
    },
    endings: [],
  },
  openingNarrative: '',
  availableActions: [
    { id: 'A', text: '观察', hint: '' },
    { id: 'B', text: '等待', hint: '' },
  ],
  attributes: [
    { key: 'hp', displayName: '体力' },
    { key: 'san', displayName: '理智' },
  ],
};

function makeApi(resume: InitResult | 'fail'): GameApi {
  return {
    async listArchetypes() {
      return { archetypes: [], fusions: [] };
    },
    async initGame() {
      return { ...RESUME_RESULT, saveId: 's-new', openingNarrative: '开场。' };
    },
    async resumeGame() {
      if (resume === 'fail') throw new GameApiError('session_not_found', '存档不存在或已失效');
      return resume;
    },
    openTurnStream(): TurnStream {
      throw new Error('not used');
    },
  };
}

beforeEach(() => {
  localStorage.clear();
});

describe('存档列表初始化与写入', () => {
  it('localStorage 无存档 → 空列表', () => {
    const store = createGameStore(makeApi(RESUME_RESULT));
    expect(store.getState().saves).toEqual([]);
  });

  it('只有旧单槽键 → 迁移成一条,初始即在列表里', () => {
    localStorage.setItem(LEGACY_KEY, 's-old');
    const store = createGameStore(makeApi(RESUME_RESULT));
    expect(store.getState().saves.map((x) => x.saveId)).toEqual(['s-old']);
    expect(localStorage.getItem(LEGACY_KEY)).toBeNull(); // 新键已写好,比对后清
  });

  it('startGame 成功 → 写下自己那一条摘要并进列表', async () => {
    const store = createGameStore(makeApi(RESUME_RESULT), { now: () => 1234 });
    await store.getState().startGame('rules_creepy');
    expect(ptr('s-new')).toEqual({
      saveId: 's-new', title: '雨夜便利店', archetypes: ['rules_creepy'], turn: 3,
      ended: false, lastPlayedAt: 1234, availability: 'available',
    });
    expect(store.getState().saves.map((x) => x.saveId)).toEqual(['s-new']);
  });
});

describe('resumeGame', () => {
  it('成功(ongoing)→ awaiting;散文由 log 末条补位;数值/动作/规则高亮就位', async () => {
    const store = createGameStore(makeApi(RESUME_RESULT));
    await store.getState().resumeGame('s-resume');

    const s = store.getState();
    expect(s.status).toBe('awaiting');
    expect(s.saveId).toBe('s-resume');
    expect(s.turn).toBe(3);
    expect(s.narrative).toBe('雨声忽然停了。');
    expect(s.attributeValues).toEqual({ hp: 70, san: 55 });
    expect(s.availableActions.map((a) => a.id)).toEqual(['A', 'B']);
    expect(s.discoveredRuleIds).toEqual([1]);
    // 一次性续局确认反馈(下一次选动作即散,chooseAction 清 notice)。
    expect(s.notice).toBe('已从上次落笔处接续(第 3 回合)');
  });

  it('log 为空(起局即崩后的续局)→ 散文兜底世界背景', async () => {
    const fresh: InitResult = {
      ...RESUME_RESULT,
      world: {
        ...RESUME_RESULT.world,
        state: { ...RESUME_RESULT.world.state, turn: 0, log: [] },
      },
    };
    const store = createGameStore(makeApi(fresh));
    await store.getState().resumeGame('s-resume');
    expect(store.getState().narrative).toBe('背景兜底文案');
  });

  it('ended 局照样可回看:status ended + 结局画面数据来自 reached 结局', async () => {
    const endedResult: InitResult = {
      ...RESUME_RESULT,
      world: {
        ...RESUME_RESULT.world,
        state: { ...RESUME_RESULT.world.state, status: 'ended' },
        endings: [
          {
            id: 'survive_dawn',
            title: '撑到黎明',
            description: '你活了下来。',
            condition: '',
            reached: true,
          },
        ],
      },
    };
    const store = createGameStore(makeApi(endedResult));
    await store.getState().resumeGame('s-resume');
    const s = store.getState();
    expect(s.status).toBe('ended');
    expect(s.ending).toEqual({ id: 'survive_dawn', title: '撑到黎明', description: '你活了下来。' });
    // 回看结局不显续局 notice(结局画面本身就是确认)。
    expect(s.notice).toBeNull();
  });

  it('失败(404)→ 回到 idle,不留错误挡路;该条**标 unavailable、不删**(ADR-034 已决 6)', async () => {
    localStorage.setItem(LEGACY_KEY, 's-gone'); // 经迁移成为一条
    const store = createGameStore(makeApi('fail'));
    await store.getState().resumeGame('s-gone');

    const s = store.getState();
    expect(s.status).toBe('idle');
    expect(s.errorMessage).toBeNull();
    expect(s.saves.map((x) => [x.saveId, x.availability])).toEqual([['s-gone', 'unavailable']]);
    expect(ptr('s-gone').availability).toBe('unavailable');
  });

  it('空 saveId → no-op', async () => {
    const store = createGameStore(makeApi(RESUME_RESULT));
    await store.getState().resumeGame('');
    expect(store.getState().status).toBe('idle');
  });
});
