// 存档指针(ADR-034):本设备上开过的每一局,各一个 localStorage 键。
//
// **第一原则:任何路径都不得在玩家不知情的情况下丢弃一个指针。** saveId 是今天找回一局存档的唯一凭证 ——
// 服务端不删档,但浏览器丢了指针,那一局就再也找不回来。所以本模块**没有删除条目的函数**:
// 只有写入、更新、标记不可用;唯一的 removeItem 是迁移里「比对后清旧单槽键」(那时新键已经写好)。
//
// 存储形态(ADR-034 已决 2):`aiuniverse.save.<saveId>` 一键一局,列表 = 按前缀枚举后汇集。
// 不用「一个数组存全部」:两个页签同时读 → 追加 → 写回会互相覆盖,而 localStorage 没有跨窗口锁。
// 按键存放后,每一次写都只碰自己那一条 saveId 的键。
//
// 本文件是 web/src 里**唯一**碰存档相关 localStorage 键的地方(源码级断言守着,见 savePointers.test.ts)。
// 全部读写 try/catch:storage 不可用(隐私模式 / 配额满 / 被禁用)时一律 best-effort,调用方不会因此崩。

import type { Archetype } from '../types/schema';

export const SAVE_KEY_PREFIX = 'aiuniverse.save.';
/** ADR-015 Slice 2 的旧单槽键;只在迁移里读、比对后清(ADR-034 已决 7)。 */
export const LEGACY_SAVE_ID_KEY = 'aiuniverse.saveId';
/** 选择屏默认展示条数(只管展示,不管保存;ADR-034 已决 4)。 */
export const SAVE_LIST_VISIBLE = 8;

export type SaveAvailability = 'available' | 'unavailable';

export interface SaveSummary {
  saveId: string;
  /** 世界名(`world.title`);迁移来的旧指针在首次续局成功前为空串。 */
  title: string;
  /** 单体一个;融合为 host 在前的有序对。迁移来的旧指针为空数组。 */
  archetypes: Archetype[];
  turn: number;
  ended: boolean;
  /** 本设备上次游玩时间(客户端本地时钟,epoch ms)。不是服务端时间。 */
  lastPlayedAt: number;
  availability: SaveAvailability;
}

export interface SaveListing {
  /** 全部可解析的条目,按本设备上次游玩时间降序。 */
  entries: SaveSummary[];
  /** 解析失败而被跳过的键数(跳过、不删除)。 */
  skipped: number;
}

/** 本模块用到的 Storage 子集(便于测试注入一个会失败的实现)。 */
export type PointerStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key' | 'length'>;

export interface SavePointers {
  list(): SaveListing;
  read(saveId: string): SaveSummary | null;
  /** 写入 / 覆盖自己那一条;成功返回 true。 */
  write(summary: SaveSummary): boolean;
  /** 读 → 合并 → 写回自己那一条;键不存在时什么都不做,返回 false。 */
  patch(saveId: string, patch: Partial<Omit<SaveSummary, 'saveId'>>): boolean;
  /** 旧单槽键 → 新键(ADR-034 已决 7)。幂等。 */
  migrateLegacy(): void;
}

const keyOf = (saveId: string) => SAVE_KEY_PREFIX + saveId;

/** 默认取全局 localStorage;取不到(或访问即抛)返回 null。 */
export function defaultStorage(): PointerStorage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

/** 解析一条摘要;任何字段不合形状即视为解析失败(返回 null),**不修补、不删除**。 */
export function parseSummary(saveId: string, raw: string | null): SaveSummary | null {
  if (raw == null) return null;
  let v: unknown;
  try {
    v = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof v !== 'object' || v === null) return null;
  const o = v as Record<string, unknown>;
  if (o.saveId !== saveId) return null;
  if (typeof o.title !== 'string') return null;
  if (!Array.isArray(o.archetypes) || !o.archetypes.every((a) => typeof a === 'string')) return null;
  if (typeof o.turn !== 'number' || !Number.isFinite(o.turn)) return null;
  if (typeof o.ended !== 'boolean') return null;
  if (typeof o.lastPlayedAt !== 'number' || !Number.isFinite(o.lastPlayedAt)) return null;
  if (o.availability !== 'available' && o.availability !== 'unavailable') return null;
  return {
    saveId,
    title: o.title,
    archetypes: o.archetypes as Archetype[],
    turn: o.turn,
    ended: o.ended,
    lastPlayedAt: o.lastPlayedAt,
    availability: o.availability,
  };
}

export function serializeSummary(s: SaveSummary): string {
  return JSON.stringify({
    saveId: s.saveId,
    title: s.title,
    archetypes: s.archetypes,
    turn: s.turn,
    ended: s.ended,
    lastPlayedAt: s.lastPlayedAt,
    availability: s.availability,
  });
}

export function createSavePointers(
  getStorage: () => PointerStorage | null = defaultStorage,
  now: () => number = () => Date.now(),
): SavePointers {
  const storage = (): PointerStorage | null => {
    try {
      return getStorage();
    } catch {
      return null;
    }
  };

  const read = (saveId: string): SaveSummary | null => {
    const st = storage();
    if (!st) return null;
    try {
      return parseSummary(saveId, st.getItem(keyOf(saveId)));
    } catch {
      return null;
    }
  };

  const write = (summary: SaveSummary): boolean => {
    const st = storage();
    if (!st) return false;
    try {
      st.setItem(keyOf(summary.saveId), serializeSummary(summary));
      return true;
    } catch {
      return false; // 写不进:这一条不出现 / 不更新,当局照常(ADR-034 已知代价 2)
    }
  };

  return {
    list() {
      const st = storage();
      if (!st) return { entries: [], skipped: 0 };
      const ids: string[] = [];
      try {
        for (let i = 0; i < st.length; i++) {
          const k = st.key(i);
          if (k && k.startsWith(SAVE_KEY_PREFIX) && k.length > SAVE_KEY_PREFIX.length) {
            ids.push(k.slice(SAVE_KEY_PREFIX.length));
          }
        }
      } catch {
        return { entries: [], skipped: 0 };
      }
      const entries: SaveSummary[] = [];
      let skipped = 0;
      for (const id of ids) {
        let raw: string | null;
        try {
          raw = st.getItem(keyOf(id));
        } catch {
          raw = null;
        }
        const s = parseSummary(id, raw);
        if (s) entries.push(s);
        else skipped++; // 跳过,不删除(第一原则)
      }
      entries.sort((a, b) => b.lastPlayedAt - a.lastPlayedAt);
      return { entries, skipped };
    },

    read,
    write,

    patch(saveId, p) {
      const cur = read(saveId);
      if (!cur) return false;
      return write({ ...cur, ...p, saveId });
    },

    migrateLegacy() {
      const st = storage();
      if (!st) return;
      let legacy: string | null;
      try {
        legacy = st.getItem(LEGACY_SAVE_ID_KEY);
      } catch {
        return;
      }
      if (!legacy) return;
      // 1. 新键不存在才写;写入失败就停,旧键原样保留。
      let hasNew: boolean;
      try {
        hasNew = st.getItem(keyOf(legacy)) != null;
      } catch {
        return;
      }
      if (!hasNew) {
        const ok = write({
          saveId: legacy,
          title: '',
          archetypes: [],
          turn: 0,
          ended: false,
          lastPlayedAt: now(),
          availability: 'available',
        });
        if (!ok) return;
      }
      // 2. 比对后清:别的页签可能已经把旧键改成了另一局(它的迁移会自己处理那一局)。
      try {
        if (st.getItem(LEGACY_SAVE_ID_KEY) === legacy) st.removeItem(LEGACY_SAVE_ID_KEY);
      } catch {
        /* 清不掉:下次加载再迁一次,幂等 */
      }
    },
  };
}

/**
 * 跨页签刷新显示(ADR-034 已决 8):别的页签写了存档键时回调。只用来刷新,不承担一致性。
 * 返回取消订阅函数;无 window 环境返回 no-op。
 */
export function onSavePointersChanged(cb: () => void): () => void {
  const target = globalThis as unknown as {
    addEventListener?: (t: string, l: (e: StorageEvent) => void) => void;
    removeEventListener?: (t: string, l: (e: StorageEvent) => void) => void;
  };
  if (typeof target.addEventListener !== 'function') return () => {};
  const listener = (e: StorageEvent) => {
    // key 为 null = 整个 storage 被清空,也要刷新。
    if (e.key == null || e.key.startsWith(SAVE_KEY_PREFIX) || e.key === LEGACY_SAVE_ID_KEY) cb();
  };
  target.addEventListener('storage', listener);
  return () => target.removeEventListener?.('storage', listener);
}
