// ADR-041 决策 2:大厅偏好的存取。全部 best-effort:读不到当没有,写不进不抛。
import { beforeEach, describe, expect, it } from 'vitest';
import {
  LOBBY_DISCOVERED_KEY,
  LOBBY_REVEALED_KEY,
  LOBBY_ZONE_KEY,
  createLobbyPrefs,
} from './lobbyPrefs';
import { SAVE_KEY_PREFIX, type PointerStorage } from './savePointers';

beforeEach(() => localStorage.clear());

const throwing: PointerStorage = {
  getItem: () => {
    throw new Error('blocked');
  },
  setItem: () => {
    throw new Error('blocked');
  },
  removeItem: () => {},
  key: () => null,
  length: 0,
};

describe('上次停的区', () => {
  it('没有记录 → null;写了再读 → 原值', () => {
    const p = createLobbyPrefs();
    expect(p.readZone()).toBeNull();
    p.writeZone('shen');
    expect(p.readZone()).toBe('shen');
    expect(localStorage.getItem(LOBBY_ZONE_KEY)).toBe('shen');
  });
  it('不认识的值 → null', () => {
    localStorage.setItem(LOBBY_ZONE_KEY, 'shenjie');
    expect(createLobbyPrefs().readZone()).toBeNull();
  });
});

describe('已发现', () => {
  it('按先后追加,同一个键不重复、不挪位置', () => {
    const p = createLobbyPrefs();
    p.addDiscovered('a×b');
    p.addDiscovered('c×d');
    p.addDiscovered('a×b');
    expect(p.readDiscovered()).toEqual(['a×b', 'c×d']);
  });
  it('坏数据(不是 JSON / 不是数组 / 混了非字符串)→ 尽量读出字符串,读不出就空', () => {
    localStorage.setItem(LOBBY_DISCOVERED_KEY, '{oops');
    expect(createLobbyPrefs().readDiscovered()).toEqual([]);
    localStorage.setItem(LOBBY_DISCOVERED_KEY, '{"a":1}');
    expect(createLobbyPrefs().readDiscovered()).toEqual([]);
    localStorage.setItem(LOBBY_DISCOVERED_KEY, '["a×b", 3]');
    expect(createLobbyPrefs().readDiscovered()).toEqual(['a×b']);
  });
});

describe('第三区出现是否已开演', () => {
  it('默认 false;mark 之后 true', () => {
    const p = createLobbyPrefs();
    expect(p.readRevealed()).toBe(false);
    p.markRevealed();
    expect(p.readRevealed()).toBe(true);
    expect(localStorage.getItem(LOBBY_REVEALED_KEY)).toBe('true');
  });
});

describe('存储不可用(隐私模式 / 被禁用)', () => {
  it('读一律当没有,写不抛', () => {
    const p = createLobbyPrefs(() => throwing);
    expect(p.readZone()).toBeNull();
    expect(p.readDiscovered()).toEqual([]);
    expect(p.readRevealed()).toBe(false);
    expect(() => {
      p.writeZone('fushi');
      p.addDiscovered('a×b');
      p.markRevealed();
    }).not.toThrow();
  });
  it('取存储本身就抛 / 为 null → 同上', () => {
    const p = createLobbyPrefs(() => {
      throw new Error('no storage');
    });
    expect(p.readZone()).toBeNull();
    expect(() => p.writeZone('shen')).not.toThrow();
    expect(createLobbyPrefs(() => null).readDiscovered()).toEqual([]);
  });
});

describe('大厅键不碰存档前缀', () => {
  it('三个键都不以存档前缀开头,也不被存档前缀匹配', () => {
    for (const k of [LOBBY_ZONE_KEY, LOBBY_DISCOVERED_KEY, LOBBY_REVEALED_KEY]) {
      expect(k.startsWith(SAVE_KEY_PREFIX)).toBe(false);
      expect(k.includes(SAVE_KEY_PREFIX.replace(/\.$/, ''))).toBe(false);
    }
  });
});
