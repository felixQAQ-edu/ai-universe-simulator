// ADR-034 存档指针:存储形态、上限、迁移、解析失败、源码级边界。
// 第一原则:任何路径都不得在玩家不知情的情况下丢弃一个指针 —— 下面每条都在钉「没有被删」。
import { beforeEach, describe, expect, it } from 'vitest';
import {
  LEGACY_SAVE_ID_KEY,
  SAVE_KEY_PREFIX,
  createSavePointers,
  type PointerStorage,
  type SaveSummary,
} from './savePointers';

const sum = (saveId: string, lastPlayedAt = 1, extra: Partial<SaveSummary> = {}): SaveSummary => ({
  saveId,
  title: `世界-${saveId}`,
  archetypes: ['rules_creepy'],
  turn: 1,
  ended: false,
  lastPlayedAt,
  availability: 'available',
  ...extra,
});

const saveKeys = () =>
  Array.from({ length: localStorage.length }, (_, i) => localStorage.key(i)!)
    .filter((k) => k.startsWith(SAVE_KEY_PREFIX))
    .sort();

/** 包一层:可指定哪些写入会抛(模拟配额满 / 被禁用)。 */
function failingStorage(failSet: (key: string) => boolean): PointerStorage {
  return {
    getItem: (k) => localStorage.getItem(k),
    setItem: (k, v) => {
      if (failSet(k)) throw new Error('QuotaExceededError');
      localStorage.setItem(k, v);
    },
    removeItem: (k) => localStorage.removeItem(k),
    key: (i) => localStorage.key(i),
    get length() {
      return localStorage.length;
    },
  };
}

beforeEach(() => localStorage.clear());

describe('一局一键(ADR-034 已决 2)', () => {
  it('写入与枚举:按本设备上次游玩时间降序', () => {
    const p = createSavePointers();
    p.write(sum('a', 10));
    p.write(sum('b', 30));
    p.write(sum('c', 20));
    expect(p.list().entries.map((e) => e.saveId)).toEqual(['b', 'c', 'a']);
    expect(saveKeys()).toEqual(['aiuniverse.save.a', 'aiuniverse.save.b', 'aiuniverse.save.c']);
  });

  it('patch 只碰自己那一条;键不存在时什么都不写', () => {
    const p = createSavePointers();
    p.write(sum('a'));
    p.write(sum('b'));
    const b = localStorage.getItem('aiuniverse.save.b');
    expect(p.patch('a', { turn: 7 })).toBe(true);
    expect(p.read('a')!.turn).toBe(7);
    expect(localStorage.getItem('aiuniverse.save.b')).toBe(b);
    expect(p.patch('nope', { turn: 1 })).toBe(false);
    expect(saveKeys()).toEqual(['aiuniverse.save.a', 'aiuniverse.save.b']);
  });

  it('超过 8 条:枚举结果是全部,没有任何一条被删除', () => {
    const p = createSavePointers();
    for (let i = 0; i < 12; i++) p.write(sum(`s${i}`, i));
    const listing = p.list();
    expect(listing.entries).toHaveLength(12);
    expect(saveKeys()).toHaveLength(12);
  });

  it('storage 不可用 → 空列表、写入返回 false、不抛', () => {
    const p = createSavePointers(() => {
      throw new Error('SecurityError');
    });
    expect(p.list()).toEqual({ entries: [], skipped: 0 });
    expect(p.write(sum('a'))).toBe(false);
    expect(() => p.migrateLegacy()).not.toThrow();
  });
});

describe('解析失败的键:跳过、不删除、汇报计数(ADR-034 已知代价 5)', () => {
  it('坏 JSON / 缺字段 / saveId 与键不符 → skipped,键原样还在', () => {
    const p = createSavePointers();
    p.write(sum('good'));
    localStorage.setItem('aiuniverse.save.bad-json', '{not json');
    localStorage.setItem('aiuniverse.save.missing', JSON.stringify({ saveId: 'missing', title: 'x' }));
    localStorage.setItem('aiuniverse.save.mismatch', JSON.stringify(sum('other')));
    localStorage.setItem('aiuniverse.deviceId', 'dev-1'); // 不是存档键:不计入、不碰

    const listing = p.list();
    expect(listing.entries.map((e) => e.saveId)).toEqual(['good']);
    expect(listing.skipped).toBe(3);
    expect(localStorage.getItem('aiuniverse.save.bad-json')).toBe('{not json');
    expect(saveKeys()).toHaveLength(4);
    expect(localStorage.getItem('aiuniverse.deviceId')).toBe('dev-1');
  });
});

describe('旧单槽迁移(ADR-034 已决 7)', () => {
  it('新键不存在 → 写入新键,再比对后清旧键', () => {
    localStorage.setItem(LEGACY_SAVE_ID_KEY, 'old');
    createSavePointers(undefined, () => 99).migrateLegacy();
    expect(JSON.parse(localStorage.getItem('aiuniverse.save.old')!)).toMatchObject({
      saveId: 'old',
      lastPlayedAt: 99,
      availability: 'available',
    });
    expect(localStorage.getItem(LEGACY_SAVE_ID_KEY)).toBeNull();
  });

  it('新键写入失败 → 停,旧键保留', () => {
    localStorage.setItem(LEGACY_SAVE_ID_KEY, 'old');
    createSavePointers(() => failingStorage((k) => k.startsWith(SAVE_KEY_PREFIX))).migrateLegacy();
    expect(localStorage.getItem(LEGACY_SAVE_ID_KEY)).toBe('old');
    expect(saveKeys()).toEqual([]);
  });

  it('旧键的值在写新键期间已被别的页签改掉 → 不清旧键', () => {
    localStorage.setItem(LEGACY_SAVE_ID_KEY, 'old');
    // 写新键的那一刻,另一个页签把旧单槽改成了另一局(旧版本前端)。
    const racing: PointerStorage = {
      ...failingStorage(() => false),
      setItem: (k, v) => {
        localStorage.setItem(k, v);
        if (k === 'aiuniverse.save.old') localStorage.setItem(LEGACY_SAVE_ID_KEY, 'other-tab');
      },
      key: (i) => localStorage.key(i),
      get length() {
        return localStorage.length;
      },
    };
    createSavePointers(() => racing).migrateLegacy();
    expect(localStorage.getItem(LEGACY_SAVE_ID_KEY)).toBe('other-tab');
    expect(localStorage.getItem('aiuniverse.save.old')).not.toBeNull();
  });

  it('新键已存在 → 不覆盖它,只比对后清旧键', () => {
    localStorage.setItem(LEGACY_SAVE_ID_KEY, 'old');
    localStorage.setItem('aiuniverse.save.old', JSON.stringify(sum('old', 5, { turn: 9 })));
    createSavePointers().migrateLegacy();
    expect(JSON.parse(localStorage.getItem('aiuniverse.save.old')!).turn).toBe(9);
    expect(localStorage.getItem(LEGACY_SAVE_ID_KEY)).toBeNull();
  });

  it('重复执行幂等(两个页签各跑一次)', () => {
    localStorage.setItem(LEGACY_SAVE_ID_KEY, 'old');
    let t = 1;
    const p = createSavePointers(undefined, () => t++);
    p.migrateLegacy();
    const once = localStorage.getItem('aiuniverse.save.old');
    p.migrateLegacy();
    createSavePointers(undefined, () => 1000).migrateLegacy();
    expect(localStorage.getItem('aiuniverse.save.old')).toBe(once);
    expect(saveKeys()).toEqual(['aiuniverse.save.old']);
    expect(localStorage.getItem(LEGACY_SAVE_ID_KEY)).toBeNull();
  });
});

describe('源码级边界:只有存档指针模块碰存档相关的 localStorage 键', () => {
  // 用 Vite 原生 ?raw 读源码(ADR-022 刀 1 先例;node:fs 在 jsdom 下解析不出路径)。
  const sources = import.meta.glob('/src/**/*.{ts,tsx}', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>;
  const prod = Object.entries(sources).filter(
    ([path]) => !/\.test\.tsx?$/.test(path) && !path.startsWith('/src/test/'),
  );

  it('先证明真的遍历到了源码(空集上的断言是假绿灯)', () => {
    expect(prod.length).toBeGreaterThan(30);
    expect(prod.some(([p]) => p === '/src/state/savePointers.ts')).toBe(true);
    expect(prod.some(([p]) => p === '/src/state/gameStore.ts')).toBe(true);
  });

  it('存档键字面量只出现在 savePointers.ts', () => {
    const offenders = prod
      .filter(([, src]) => src.includes('aiuniverse.save'))
      .map(([p]) => p);
    expect(offenders).toEqual(['/src/state/savePointers.ts']);
  });

  it('调用 localStorage 的只有 savePointers.ts 与(设备键的)api/deviceId.ts', () => {
    const callers = prod
      .filter(([, src]) => /localStorage\s*[?.]/.test(src))
      .map(([p]) => p)
      .sort();
    expect(callers).toEqual(['/src/api/deviceId.ts', '/src/state/savePointers.ts']);
  });
});
