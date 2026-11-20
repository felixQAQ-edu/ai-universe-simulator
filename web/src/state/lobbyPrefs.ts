// 大厅偏好(ADR-041 决策 2):上次停的区、已发现的融合组合、第三区出现是否已开演。
//
// 三个键都只在本机浏览器里,全部读写 try/catch —— 读不到就当没有(新玩家、没发现、没看过),
// 写不进就算了(best-effort,同 ADR-034 存档指针的口径)。
//
// 键名刻意不含存档指针的前缀(见 savePointers.ts 的 SAVE_KEY_PREFIX):源码级断言只许那一个文件出现它,
// 连注释里写出来都会被它抓到。
// 本文件是 web/src 里**唯一**碰大厅键的地方(同一条断言的白名单里有它)。

import type { PointerStorage } from './savePointers';

export const LOBBY_ZONE_KEY = 'aiuniverse.lobby.zone';
export const LOBBY_DISCOVERED_KEY = 'aiuniverse.lobby.discovered';
export const LOBBY_REVEALED_KEY = 'aiuniverse.lobby.revealed';

/** 大厅的三个区(前端 id,同样张的 `data-zone`;深界与渗界拼音相同,所以不用全拼)。 */
export type LobbyZone = 'fushi' | 'shen' | 'rong';

const ZONES: readonly LobbyZone[] = ['fushi', 'shen', 'rong'];

export interface LobbyPrefs {
  /** 上次停的区;没有记录、读不到或值不认识 → null。 */
  readZone(): LobbyZone | null;
  writeZone(zone: LobbyZone): void;
  /** 浏览器里记下的已发现组合键,按发现先后;读不到 → 空数组。 */
  readDiscovered(): string[];
  /** 追加一个组合键(已有则不动、不挪位置)。 */
  addDiscovered(key: string): void;
  /** 第三区出现是否已开演过(ADR-018 §4.9:记「已开演」,不记「已排期」)。读不到 → false。 */
  readRevealed(): boolean;
  markRevealed(): void;
}

function defaultLobbyStorage(): PointerStorage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

export function createLobbyPrefs(getStorage: () => PointerStorage | null = defaultLobbyStorage): LobbyPrefs {
  const read = (key: string): string | null => {
    try {
      return getStorage()?.getItem(key) ?? null;
    } catch {
      return null;
    }
  };
  const write = (key: string, value: string) => {
    try {
      getStorage()?.setItem(key, value);
    } catch {
      // best-effort:写不进就算了
    }
  };
  const readDiscovered = (): string[] => {
    const raw = read(LOBBY_DISCOVERED_KEY);
    if (raw == null) return [];
    try {
      const v: unknown = JSON.parse(raw);
      return Array.isArray(v) ? v.filter((k): k is string => typeof k === 'string') : [];
    } catch {
      return [];
    }
  };
  return {
    readZone() {
      const v = read(LOBBY_ZONE_KEY);
      return ZONES.includes(v as LobbyZone) ? (v as LobbyZone) : null;
    },
    writeZone(zone) {
      write(LOBBY_ZONE_KEY, zone);
    },
    readDiscovered,
    addDiscovered(key) {
      const list = readDiscovered();
      if (list.includes(key)) return;
      write(LOBBY_DISCOVERED_KEY, JSON.stringify([...list, key]));
    },
    readRevealed() {
      return read(LOBBY_REVEALED_KEY) === 'true';
    },
    markRevealed() {
      write(LOBBY_REVEALED_KEY, 'true');
    },
  };
}

/** 默认实例(全局 localStorage)。 */
export const lobbyPrefs = createLobbyPrefs();

/** 别的页签改了大厅键(或清空了 storage)→ 回调。返回取消订阅。 */
export function onLobbyPrefsChanged(cb: () => void): () => void {
  const target = globalThis as unknown as {
    addEventListener?: (t: string, l: (e: StorageEvent) => void) => void;
    removeEventListener?: (t: string, l: (e: StorageEvent) => void) => void;
  };
  if (typeof target.addEventListener !== 'function') return () => {};
  const listener = (e: StorageEvent) => {
    if (e.key == null || e.key === LOBBY_DISCOVERED_KEY || e.key === LOBBY_REVEALED_KEY) cb();
  };
  target.addEventListener('storage', listener);
  return () => target.removeEventListener?.('storage', listener);
}
