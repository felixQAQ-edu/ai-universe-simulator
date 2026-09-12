import { beforeEach, describe, expect, it } from 'vitest';
import type { ClientWorld, GameApi, InitResult, StreamError, TurnStream } from '../api';
import { GameApiError } from '../api';
import { createGameStore } from './gameStore';

/**
 * 游标落后之后的重新同步(ADR-023 块 5)。
 *
 * <p>服务端的 409 只做了一半的事——挡住「再点一次推进第二次」;
 * **另一半是让玩家看到他错过的那一回合**,而那一半在这里:收到 `turn_stale` 就拉一次
 * `/state`(读服务端内存现值,`state.log` 保留末 4 条,断流那一回合的 narrative 仍在)。
 */

class FakeTurnStream implements TurnStream {
  private err: Array<(e: StreamError) => void> = [];
  closed = false;
  onNarrative() {}
  onDelta() {}
  onEnding() {}
  onError(cb: (e: StreamError) => void) {
    this.err.push(cb);
  }
  onClose() {}
  close() {
    this.closed = true;
  }
  fireError(e: StreamError) {
    this.err.forEach((c) => c(e));
  }
}

const world = (turn: number, status: 'ongoing' | 'ended', reachedEnding = false): ClientWorld => ({
  schemaVersion: '0.4',
  mode: 'single' as const,
  archetypes: ['rules_creepy'],
  world: { title: '雨夜便利店', background: 'bg', dangerLevel: 'high', tone: 'tone' },
  character: { attributes: { hp: 55, san: 40 }, traits: [], inventory: [] },
  rules: [{ id: 1, content: '不要回应敲玻璃', discovered: true }],
  state: {
    turn,
    status,
    timeline: '',
    logSummary: '',
    log: [{ turn, narrative: '你错过的那一回合:玻璃上多了一道裂痕。', playerAction: 'A' }],
  },
  endings: reachedEnding
    ? [{ id: 'survive_dawn', title: '活到天亮', description: '你撑过去了。', condition: '', reached: true }]
    : [],
});

const INIT: InitResult = {
  saveId: 's1',
  world: world(0, 'ongoing'),
  openingNarrative: '午夜两点,你被困在便利店。',
  availableActions: [
    { id: 'A', text: '观察', hint: '' },
    { id: 'B', text: '等待', hint: '' },
  ],
  attributes: [{ key: 'hp', displayName: '体力' }, { key: 'san', displayName: '理智' }],
};

/** resumeGame 可控:返回服务端「已经走到第 1 回合」的那一份,或按 deferred 卡住 / 抛错。 */
function makeApi(opts: { resume: 'ahead' | 'ended' | 'fail' | 'deferred' }) {
  let stream: FakeTurnStream | null = null;
  let release: (() => void) | null = null;
  let resumeCalls = 0;
  const api: GameApi = {
    async listArchetypes() {
      return { archetypes: [], fusions: [] };
    },
    async initGame() {
      return INIT;
    },
    async resumeGame() {
      resumeCalls += 1;
      if (opts.resume === 'fail') throw new GameApiError('network', '网络错误');
      if (opts.resume === 'deferred') {
        await new Promise<void>((r) => {
          release = r;
        });
      }
      const ended = opts.resume === 'ended';
      return {
        saveId: 's1',
        world: world(1, ended ? 'ended' : 'ongoing', ended),
        openingNarrative: '',
        availableActions: ended ? [] : [{ id: 'A', text: '查看裂痕', hint: '' }],
        attributes: INIT.attributes,
      };
    },
    openTurnStream() {
      stream = new FakeTurnStream();
      return stream;
    },
  };
  return { api, stream: () => stream!, release: () => release?.(), calls: () => resumeCalls };
}

/** 起一局并选一个动作,然后让流报 code。 */
async function playUntilError(api: GameApi, stream: () => FakeTurnStream, code: string) {
  const store = createGameStore(api);
  await store.getState().startGame('rules_creepy');
  store.getState().chooseAction('A');
  stream().fireError({ code, message: '(文案由兜底表决定,本测试不断言它)' });
  return store;
}

beforeEach(() => {
  globalThis.localStorage?.clear();
});

describe('turn_stale 之后的重新同步', () => {
  it('把服务端现值补回来:回合号 / 数值 / 选项 / 我们错过的那一段叙事', async () => {
    const { api, stream } = makeApi({ resume: 'ahead' });
    const store = await playUntilError(api, stream, 'turn_stale');
    await new Promise((r) => setTimeout(r, 0));

    const s = store.getState();
    expect(s.turn).toBe(1);
    expect(s.status).toBe('awaiting');
    expect(s.availableActions.map((a) => a.id)).toEqual(['A']);
    expect(s.availableActions[0].text).toBe('查看裂痕');
    expect(s.attributeValues).toEqual({ hp: 55, san: 40 });
    expect(s.discoveredRuleIds).toEqual([1]);
    // 这一条是本刀的全部意义:那一回合在服务端发生过,而玩家原本永远读不到它。
    expect(s.narrative).toContain('玻璃上多了一道裂痕');
  });

  /**
   * ⚠️ **本条是 ADR-023 立字 3 的前端那一半** —— 服务端把 ENDED 判成 stale,
   * 是为了让玩家在结局那回合断流之后**有一个出口**;出口真正兑现在这里:
   * 拉回来的那份 `status: ended` 把他送进结局屏。
   * 没有这一步,服务端那一格只是换了个 code,玩家照样卡着。
   */
  it('局已结束 → 把玩家送进结局屏(他在结局那回合断流,原本永远出不去)', async () => {
    const { api, stream } = makeApi({ resume: 'ended' });
    const store = await playUntilError(api, stream, 'turn_stale');
    await new Promise((r) => setTimeout(r, 0));

    const s = store.getState();
    expect(s.status).toBe('ended');
    expect(s.ending?.id).toBe('survive_dawn');
    expect(s.ending?.title).toBe('活到天亮');
  });

  /**
   * ⚠️ **世代守卫** —— 线 C 补掉的那个洞不许原样再来一遍:异步结果回来时,玩家可能已经
   * 返回 / 开了新局。这里复用回合流那一条(`activeStream` 比对),`reset()` 之后回来的那份**整段丢弃**。
   */
  it('拉取在途时玩家已返回 → 整段丢弃,不把他拽回那一局', async () => {
    const { api, stream, release } = makeApi({ resume: 'deferred' });
    const store = await playUntilError(api, stream, 'turn_stale');

    store.getState().reset(); // 玩家点了返回
    release();
    await new Promise((r) => setTimeout(r, 0));

    const s = store.getState();
    expect(s.turn).toBe(0);
    expect(s.status).not.toBe('awaiting');
    expect(s.narrative).not.toContain('玻璃上多了一道裂痕');
  });

  /**
   * ⚠️ **失败路径一律不清 saveId**:清档是 `session_not_found` 的专属动作。
   * 拉取失败可能只是网络抖一下,而这一局在服务端**是活的** —— 拿一次拉取失败去删一个
   * 活着的存档,正是 `resumeGame` 那句注释警告过的形状。
   *
   * <p>下面的 `session_not_found` 是**控制组**:没有它,「指针还在」可能只是因为清档逻辑压根没接上。
   */
  it('拉取失败 → 原地不动,尤其不清存档指针(对照:session_not_found 会清)', async () => {
    const { api, stream } = makeApi({ resume: 'fail' });
    const store = await playUntilError(api, stream, 'turn_stale');
    await new Promise((r) => setTimeout(r, 0));

    expect(globalThis.localStorage.getItem('aiuniverse.saveId')).toBe('s1');
    expect(store.getState().resumableSaveId).toBe('s1');

    const dead = makeApi({ resume: 'fail' });
    const store2 = await playUntilError(dead.api, dead.stream, 'session_not_found');
    expect(globalThis.localStorage.getItem('aiuniverse.saveId')).toBeNull();
    expect(store2.getState().resumableSaveId).toBeNull();
  });

  /**
   * (f) **不提示,只刷新**:`turn_stale` 不得把服务端那条错误变成一句给玩家看的话。
   *
   * <p>理由(ADR-023):**玩家没做错任何事,而问题已经被自动修好了。
   * 对一个已被修复的情况报错,本身就是一句不准的话** —— 那正是这一刀的题目。
   *
   * <p>⚠️ **必须在「先有 notice」的初态上测,否则 `null → null` 恒绿**(与服务端那边
   * `0 == 0` 让任何写法都绿是同一个形状)。而**那个初态今天的真实流程产生不出来** ——
   * `chooseAction` 开头就 `notice: null`,409 紧接着回来,中间没有任何人能写进一条提示;
   * 故这里用 `setState` **人为造**出来,**标出来免得下一个人以为它是可达状态**。
   *
   * <p>⚠️ 另断言 `status` 回到 `awaiting`:早退会把玩家留在 `generating`,
   * 而拉取失败时那就是**永久转圈**。
   */
  it('turn_stale 不提示:不把服务端那句错误写成 notice(且 status 回 awaiting)', async () => {
    const { api, stream } = makeApi({ resume: 'deferred' }); // 卡住,单看 onError 那一步
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');
    store.getState().chooseAction('A');
    store.setState({ notice: '上一条旧提示' }); // 人为初态,见上
    stream().fireError({ code: 'turn_stale', message: '请求失败(HTTP 409)' });

    const s = store.getState();
    expect(s.notice).not.toBe('请求失败(HTTP 409)');
    expect(s.notice).toBe('上一条旧提示'); // onError 这一步不碰它(清由 resync 成功时做,见下一条)
    expect(s.status).toBe('awaiting');
  });

  /**
   * (g) resync 成功 → **旧提示被清**:画面整个换了(叙事 / 数值 / 选项全是新的一回合),
   * 旧提示描述的是一个**不复存在的屏**。
   *
   * <p>⚠️ 同样建在人为的「先有 notice」初态上 —— 没有它,这条会在
   * `null → null` 上恒绿(去掉 `notice: null` 也照样过)。
   */
  it('resync 成功后旧提示被清(它描述的是一个不复存在的屏)', async () => {
    const { api, stream } = makeApi({ resume: 'ahead' });
    const store = createGameStore(api);
    await store.getState().startGame('rules_creepy');
    store.getState().chooseAction('A');
    store.setState({ notice: '上一条旧提示' });
    stream().fireError({ code: 'turn_stale', message: '请求失败(HTTP 409)' });
    await new Promise((r) => setTimeout(r, 0));

    expect(store.getState().notice).toBeNull();
    expect(store.getState().turn).toBe(1); // 控制组:确实换屏了,不是「什么都没发生所以 notice 没变」
  });

  it('别的可恢复错误不触发拉取(busy 只是「稍候再点」,状态没变)', async () => {
    const { api, stream, calls } = makeApi({ resume: 'ahead' });
    await playUntilError(api, stream, 'busy');
    await new Promise((r) => setTimeout(r, 0));

    expect(calls()).toBe(0);
  });
});
