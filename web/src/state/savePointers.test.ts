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

describe('removeIfUnchanged:唯一的删除函数,比对后删(ADR-042 决策 2)', () => {
  it('摘要七个字段全等 → 删掉这一条,别的不动', () => {
    const p = createSavePointers();
    p.write(sum('a'));
    p.write(sum('b'));
    expect(p.removeIfUnchanged(sum('a'))).toBe(true);
    expect(saveKeys()).toEqual(['aiuniverse.save.b']);
  });

  it.each([
    ['title', { title: '改过' }],
    ['archetypes', { archetypes: ['cultivation'] }],
    ['archetypes 顺序', { archetypes: ['rules_creepy', 'apocalypse'] }],
    ['turn', { turn: 2 }],
    ['ended', { ended: true }],
    ['lastPlayedAt', { lastPlayedAt: 2 }],
    ['availability', { availability: 'unavailable' }],
  ] as Array<[string, Partial<SaveSummary>]>)('存储里的 %s 变了 → 不删', (_, change) => {
    const p = createSavePointers();
    p.write({ ...sum('a'), ...change });
    expect(p.removeIfUnchanged(sum('a'))).toBe(false);
    expect(saveKeys()).toEqual(['aiuniverse.save.a']);
  });

  it('融合有序对反过来也算变了', () => {
    const p = createSavePointers();
    p.write(sum('f', 1, { archetypes: ['cultivation', 'cthulhu'] }));
    expect(p.removeIfUnchanged(sum('f', 1, { archetypes: ['cthulhu', 'cultivation'] }))).toBe(false);
    expect(saveKeys()).toEqual(['aiuniverse.save.f']);
  });

  it('键不在 / 解析失败 → 不删、返回 false', () => {
    const p = createSavePointers();
    expect(p.removeIfUnchanged(sum('nope'))).toBe(false);
    localStorage.setItem('aiuniverse.save.bad', '{not json');
    expect(p.removeIfUnchanged(sum('bad'))).toBe(false);
    expect(localStorage.getItem('aiuniverse.save.bad')).toBe('{not json');
  });

  it('storage 删不掉(抛错)→ 返回 false,不崩', () => {
    const throwing: PointerStorage = {
      ...failingStorage(() => false),
      removeItem: () => {
        throw new Error('SecurityError');
      },
    };
    const p = createSavePointers(() => throwing);
    p.write(sum('a'));
    expect(p.removeIfUnchanged(sum('a'))).toBe(false);
    expect(saveKeys()).toEqual(['aiuniverse.save.a']);
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

  it('调用 localStorage 的只有 savePointers.ts、(设备键的)api/deviceId.ts 与(大厅键的)state/lobbyPrefs.ts', () => {
    const callers = prod
      .filter(([, src]) => /localStorage\s*[?.]/.test(src))
      .map(([p]) => p)
      .sort();
    // ADR-041 决策 2:大厅偏好(上次停的区 / 已发现 / 已开演)另有一个模块,键名不含存档前缀(见上一条)。
    expect(callers).toEqual(['/src/api/deviceId.ts', '/src/state/lobbyPrefs.ts', '/src/state/savePointers.ts']);
  });
});

describe('源码级边界:删除指针只有一条调用链(ADR-042 决策 2)', () => {
  const sources = import.meta.glob('/src/**/*.{ts,tsx}', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>;
  const prod = Object.entries(sources).filter(
    ([path]) => !/\.test\.tsx?$/.test(path) && !path.startsWith('/src/test/'),
  );
  const src = (p: string) => prod.find(([q]) => q === p)?.[1] ?? '';
  const count = (text: string, needle: string) => text.split(needle).length - 1;
  const filesWith = (needle: string) =>
    prod
      .filter(([, s]) => s.includes(needle))
      .map(([p]) => p)
      .sort();

  it('先证明遍历到了这几个文件(空集上的断言是假绿灯)', () => {
    expect(src('/src/state/savePointers.ts')).toContain('removeIfUnchanged');
    expect(src('/src/state/gameStore.ts')).toContain('commitSaveRemoval');
  });

  it('savePointers.ts 里 removeItem( 恰好两处:迁移清旧键 + removeIfUnchanged', () => {
    expect(count(src('/src/state/savePointers.ts'), 'removeItem(')).toBe(2);
  });

  it('removeIfUnchanged 只出现在 savePointers.ts(定义)与 gameStore.ts(commitSaveRemoval 里一次)', () => {
    expect(filesWith('removeIfUnchanged')).toEqual(['/src/state/gameStore.ts', '/src/state/savePointers.ts']);
    expect(count(src('/src/state/gameStore.ts'), '.removeIfUnchanged(')).toBe(1);
  });

  it('commitSaveRemoval 只出现在 gameStore.ts(定义)与存档页撤销窗口(到点那一处)', () => {
    expect(filesWith('commitSaveRemoval')).toEqual(['/src/state/gameStore.ts']);
  });
});
