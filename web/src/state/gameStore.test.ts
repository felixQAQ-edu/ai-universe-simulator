import { describe, expect, it, vi } from 'vitest';
import type {
  ArchetypeSummary,
  EndingPayload,
  GameApi,
  InitResult,
  StreamError,
  TurnDelta,
  TurnStream,
} from '../api';
import { GameApiError } from '../api';
import { createGameStore } from './gameStore';

// 状态层单测:喂合成事件序列,断言状态推进(含 ending / error 分支)。
// 用可控的 FakeTurnStream 注入 GameApi —— 不打任何网络,确定性、零成本。

/** 可控回合流:记录注册的回调,测试侧主动 fire。 */
class FakeTurnStream implements TurnStream {
  private nar: Array<(t: string) => void> = [];
  private dlt: Array<(d: TurnDelta) => void> = [];
  private end: Array<(e: EndingPayload) => void> = [];
  private err: Array<(e: StreamError) => void> = [];
  private cls: Array<() => void> = [];
  closed = false;

  onNarrative(cb: (t: string) => void) {
    this.nar.push(cb);
  }
  onDelta(cb: (d: TurnDelta) => void) {
    this.dlt.push(cb);
  }
  onEnding(cb: (e: EndingPayload) => void) {
    this.end.push(cb);
  }
  onError(cb: (e: StreamError) => void) {
    this.err.push(cb);
  }
  onClose(cb: () => void) {
    this.cls.push(cb);
  }
  close() {
    this.closed = true;
  }

  fireNarrative(t: string) {
    this.nar.forEach((c) => c(t));
  }
  fireDelta(d: TurnDelta) {
    this.dlt.forEach((c) => c(d));
  }
  fireEnding(e: EndingPayload) {
    this.end.forEach((c) => c(e));
  }
  fireError(e: StreamError) {
    this.err.forEach((c) => c(e));
  }
  fireClose() {
    this.cls.forEach((c) => c());
  }
}

const INIT_RESULT: InitResult = {
  saveId: 's1',
  world: {
    schemaVersion: '0.3',
    mode: 'single',
    archetypes: ['rules_creepy'],
    world: { title: '雨夜便利店', background: 'bg', dangerLevel: 'high', tone: 'tone' },
    character: { attributes: { hp: 100, san: 100 }, traits: [], inventory: [] },
    rules: [{ id: 1, content: '不要回应敲玻璃', discovered: false }],
    state: { turn: 0, status: 'ongoing', timeline: '', logSummary: '', log: [] },
    endings: [],
  },
  openingNarrative: '午夜两点,你被困在便利店。',
  availableActions: [
    { id: 'A', text: '观察', hint: '' },
    { id: 'B', text: '等待', hint: '' },
  ],
  attributes: [
    { key: 'hp', displayName: '体力' },
    { key: 'san', displayName: '理智' },
  ],
};

const ARCHETYPE_LIST: ArchetypeSummary[] = [
  { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: '一纸诡异守则', vibeTag: '诡异 · 高危', active: true },
  { archetype: 'apocalypse', displayName: '末日生存', tagline: '废土求生', vibeTag: '荒凉 · 绝境', active: true },
  { archetype: 'cultivation', displayName: '修仙', tagline: null, vibeTag: null, active: false },
];

/** 建一个 mock GameApi;initBehavior 控制成功/失败;openTurnStream 返回可控流。 */
function makeApi(initBehavior: 'ok' | 'fail' = 'ok', listBehavior: 'ok' | 'fail' = 'ok') {
  let lastStream: FakeTurnStream | null = null;
  const api: GameApi = {
    async listArchetypes() {
      if (listBehavior === 'fail') throw new GameApiError('archetypes_failed', '世界列表加载失败');
      return ARCHETYPE_LIST;
    },
    async initGame() {
      if (initBehavior === 'fail') throw new GameApiError('world_gen_failed', '世界生成失败');
      return INIT_RESULT;
    },
    async resumeGame() {
      throw new GameApiError('session_not_found', '存档不存在或已失效');
    },
    openTurnStream() {
      lastStream = new FakeTurnStream();
      return lastStream;
    },
  };
  return { api, stream: () => lastStream! };
}

describe('startGame', () => {
  it('成功 → awaiting,world/数值/动作/开场叙事就位', async () => {
    const { api } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');

    const s = store.getState();
    expect(s.status).toBe('awaiting');
    expect(s.saveId).toBe('s1');
    expect(s.attributeAxes.map((a) => a.displayName)).toEqual(['体力', '理智']);
    expect(s.attributeValues.hp).toBe(100);
    expect(s.attributeValues.san).toBe(100);
    expect(s.narrative).toBe('午夜两点,你被困在便利店。');
    expect(s.availableActions.map((a) => a.id)).toEqual(['A', 'B']);
    expect(s.world?.rules[0].content).toBe('不要回应敲玻璃');
  });

  it('失败 → initError + errorMessage(不进半残 playing)', async () => {
    const { api } = makeApi('fail');
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');

    const s = store.getState();
    expect(s.status).toBe('initError');
    expect(s.errorMessage).toContain('世界生成失败');
    expect(s.world).toBeNull();
  });

  it('融合双值(ADR-013):有序数组原样传给 api,lastArchetype 记数组供重试', async () => {
    const { api } = makeApi('ok');
    const initSpy = vi.spyOn(api, 'initGame');
    const store = createGameStore(api);
    await store.getState().startGame(['cultivation', 'rules_creepy']);

    // api 收到的就是有序双值(host 在前),不被拆散/重排。
    expect(initSpy).toHaveBeenCalledWith(['cultivation', 'rules_creepy']);
    // lastArchetype 记数组 —— initError「重新生成」原样重发双值。
    expect(store.getState().lastArchetype).toEqual(['cultivation', 'rules_creepy']);
    expect(store.getState().status).toBe('awaiting');
  });
});

describe('chooseAction', () => {
  it('happy:generating → 叙事累加 → delta 落数值/动作 → close 回 awaiting', async () => {
    const { api, stream } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');

    store.getState().chooseAction('A');
    expect(store.getState().status).toBe('generating');
    expect(store.getState().narrative).toBe(''); // 新回合清空散文区

    const st = stream();
    st.fireNarrative('你伸手');
    st.fireNarrative('推开门。');
    expect(store.getState().narrative).toBe('你伸手推开门。');

    st.fireDelta({
      turn: 1,
      status: 'ongoing',
      attributes: { hp: 80, san: 65 },
      discoveredRules: [{ id: 1, content: '不要回应敲玻璃' }],
      availableActions: [{ id: 'A', text: '继续', hint: '' }],
    });
    expect(store.getState().attributeValues.hp).toBe(80);
    expect(store.getState().attributeValues.san).toBe(65);
    expect(store.getState().turn).toBe(1);
    expect(store.getState().discoveredRuleIds).toEqual([1]);
    expect(store.getState().availableActions[0].text).toBe('继续');

    st.fireClose();
    expect(store.getState().status).toBe('awaiting');
  });

  it('命中结局:delta(ended) + ending → status ended,ending 就位', async () => {
    const { api, stream } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');

    store.getState().chooseAction('A');
    const st = stream();
    st.fireDelta({
      turn: 1,
      status: 'ended',
      attributes: { hp: 0, san: 20 },
      discoveredRules: [],
      availableActions: [],
    });
    st.fireEnding({ id: 'died', title: '殒命便利店', description: '你倒在了货架旁。' });
    st.fireClose();

    const s = store.getState();
    expect(s.status).toBe('ended');
    expect(s.ending).toEqual({ id: 'died', title: '殒命便利店', description: '你倒在了货架旁。' });
  });

  it('可恢复错误(illegal_action)→ 回 awaiting + notice', async () => {
    const { api, stream } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');

    store.getState().chooseAction('A');
    stream().fireError({ code: 'illegal_action', message: '该选项不可用' });
    stream().fireClose();

    const s = store.getState();
    expect(s.status).toBe('awaiting');
    expect(s.notice).toBe('该选项不可用');
  });

  it('成本闸门(quota_exceeded,ADR-016)→ 回 awaiting + notice,散文/动作不丢', async () => {
    const { api, stream } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');
    const actionsBefore = store.getState().availableActions;

    store.getState().chooseAction('A');
    stream().fireError({ code: 'quota_exceeded', message: '今日回合名额已满,明天再来' });
    stream().fireClose();

    const s = store.getState();
    expect(s.status).toBe('awaiting'); // 守卫 0 相位零触碰:次日可续,不算整局失败
    expect(s.notice).toBe('今日回合名额已满,明天再来');
    expect(s.availableActions).toEqual(actionsBefore); // 决策圈原样保留
  });

  it('守卫:非 awaiting 态不开流', async () => {
    const { api, stream } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');
    store.getState().chooseAction('A'); // 进 generating
    store.getState().chooseAction('B'); // 应被忽略
    // 第二次没开新流(stream() 仍是第一条,未 close)。
    expect(stream().closed).toBe(false);
    expect(store.getState().status).toBe('generating');
  });

  it('守卫:非法 actionId → notice,不开流', async () => {
    const { api } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');
    store.getState().chooseAction('Z'); // 不在 availableActions
    expect(store.getState().status).toBe('awaiting');
    expect(store.getState().notice).toContain('已失效');
  });
});

describe('loadArchetypes', () => {
  it('成功 → 目录就位(可选在前 + 占位在后)', async () => {
    const { api } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().loadArchetypes();
    const s = store.getState();
    expect(s.archetypes.map((a) => a.archetype)).toEqual(['rules_creepy', 'apocalypse', 'cultivation']);
    expect(s.archetypes.filter((a) => a.active)).toHaveLength(2);
    expect(s.archetypesError).toBeNull();
  });

  it('失败 → archetypesError,可重试', async () => {
    const { api } = makeApi('ok', 'fail');
    const store = createGameStore(api);
    await store.getState().loadArchetypes();
    expect(store.getState().archetypesError).toContain('世界列表');
    expect(store.getState().archetypes).toHaveLength(0);
  });

  it('reset 后保留已拉取的目录(回选择屏不重拉)', async () => {
    const { api } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().loadArchetypes();
    await store.getState().startGame('apocalypse');
    store.getState().reset();
    expect(store.getState().status).toBe('idle');
    expect(store.getState().archetypes).toHaveLength(3); // 目录未被清
  });
});

describe('startGame', () => {
  it('记录 lastArchetype(供 initError 重试同一模式)', async () => {
    const { api } = makeApi('fail');
    const store = createGameStore(api);
    await store.getState().startGame('apocalypse');
    expect(store.getState().status).toBe('initError');
    expect(store.getState().lastArchetype).toBe('apocalypse');
  });
});

describe('reset', () => {
  it('回到 idle 初始态', async () => {
    const { api } = makeApi('ok');
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');
    store.getState().reset();
    expect(store.getState().status).toBe('idle');
    expect(store.getState().world).toBeNull();
  });
});
