// 存档页的撤销窗口(ADR-042 决策 3 / 7 / 10)。
//
// 「移除」先只把行从画面上拿掉、记进待删批;**删除只可能发生在计时器到点的那一个回调里**。
// 撤销、切后台(visibilitychange → hidden / pagehide)、离开存档页(卸载)都只是作废待删批 ——
// 它们本来就删不了,所以不需要「记得别删」。没有任何「关页前赶紧删」的逻辑。
//
// 连续移除并成一批,计时从头再来(B2);悬停或焦点在撤销条内时暂停,离开后按剩余时间继续。
// 计时器只在点击处理里起,不在 effect 里起(StrictMode 双挂载不会起两个);effect 只管订阅与卸载清理。
//
// 到点时先把要删的融合存档的组合落进「已发现」(决策 7:移除说的是存档,不是分区),再交给 store 的提交动作,
// 由它逐条比对后删(多页签下变过的那条不删)。提交动作在本文件里只出现一次(源码级断言锁死)。

import { useEffect, useState } from 'react';
import { onPageHidden } from '../../../api';
import { useGameStore } from '../../../state/gameStore';
import { lobbyPrefs } from '../../../state/lobbyPrefs';
import type { SaveSummary } from '../../../state/savePointers';
import { fusionKey } from '../fusion/drag';

export const REMOVAL_WINDOW_MS = 5000;

/** `one` = 恰好一次单删;`endedAll` = 恰好一次「全部移除」;其余并批都是 `mixed`(审定 1)。 */
export type BatchKind = 'one' | 'endedAll' | 'mixed';

export interface PendingBatch {
  entries: readonly SaveSummary[];
  kind: BatchKind;
  /** 每次加进新条目 +1:细线据它从头再走(换 key 重挂动画)。 */
  version: number;
}

/** 暂停的来由:悬停与焦点各自独立,两者都撤了才继续。 */
export type PauseReason = 'hover' | 'focus';

export interface RemovalTimer {
  now(): number;
  set(fn: () => void, ms: number): unknown;
  clear(handle: unknown): void;
}

// 调用时才取全局计时器(不在导入时绑定),测试的假时钟因此生效。
const defaultTimer: RemovalTimer = {
  now: () => Date.now(),
  set: (fn, ms) => setTimeout(fn, ms),
  clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
};

export interface RemovalWindow {
  /** 加进待删批(单删 = `one`,全部移除 = `endedAll`;已有待删时并批为 `mixed`),计时从头。 */
  add(entries: readonly SaveSummary[], kind: 'one' | 'endedAll'): void;
  /** 整批作废,返回被放回的条目(没有待删 → 空)。不写存储。 */
  undo(): readonly SaveSummary[];
  /** 切后台 / 关页:同撤销,不删。 */
  cancel(): void;
  pause(reason: PauseReason): void;
  resume(reason: PauseReason): void;
  /** 卸载:清计时器、丢掉待删批,不删、不再回调。 */
  dispose(): void;
}

/**
 * 纯控制器(不依赖 React,便于假时钟测)。`onChange` 在待删批或暂停态变化时回调;`commit` 只在到点时调一次。
 */
export function createRemovalWindow(
  onChange: (batch: PendingBatch | null, paused: boolean) => void,
  commit: (entries: readonly SaveSummary[]) => void,
  timer: RemovalTimer = defaultTimer,
): RemovalWindow {
  let batch: PendingBatch | null = null;
  let handle: unknown = null;
  let startedAt = 0;
  let remaining = REMOVAL_WINDOW_MS;
  let version = 0;
  const pauses = new Set<PauseReason>();

  const emit = () => onChange(batch, pauses.size > 0);
  const clearHandle = () => {
    if (handle !== null) {
      timer.clear(handle);
      handle = null;
    }
  };
  const run = () => {
    if (!batch || pauses.size > 0 || handle !== null) return;
    startedAt = timer.now();
    handle = timer.set(fire, Math.max(0, remaining));
  };
  /** 批结束(到点 / 撤销 / 作废):暂停来由一并清掉 —— 撤销条消失时 mouseleave / blur 未必会到。 */
  const end = () => {
    clearHandle();
    batch = null;
    remaining = REMOVAL_WINDOW_MS;
    pauses.clear();
  };
  function fire() {
    handle = null;
    const entries = batch?.entries ?? [];
    end();
    emit();
    if (entries.length > 0) commit(entries);
  }

  return {
    add(entries, kind) {
      if (entries.length === 0) return;
      clearHandle();
      batch = batch
        ? { entries: [...batch.entries, ...entries], kind: 'mixed', version: ++version }
        : { entries: [...entries], kind, version: ++version };
      remaining = REMOVAL_WINDOW_MS;
      run();
      emit();
    },
    undo() {
      const entries = batch?.entries ?? [];
      if (!batch) return entries;
      end();
      emit();
      return entries;
    },
    cancel() {
      if (!batch) return;
      end();
      emit();
    },
    pause(reason) {
      if (pauses.has(reason)) return;
      pauses.add(reason);
      if (handle !== null) {
        clearHandle();
        remaining -= timer.now() - startedAt;
      }
      emit();
    },
    resume(reason) {
      if (!pauses.delete(reason)) return;
      run();
      emit();
    },
    dispose() {
      clearHandle();
      batch = null;
      pauses.clear();
    },
  };
}

/** 到点提交:先保住渗界(决策 7),再交给 store 比对后删。 */
function commitRemoval(entries: readonly SaveSummary[]) {
  for (const e of entries) {
    if (e.archetypes.length === 2) lobbyPrefs.addDiscovered(fusionKey(e.archetypes[0], e.archetypes[1]));
  }
  useGameStore.getState().commitSaveRemoval(entries);
}

/** 存档页用的 hook:一页一个窗口;切后台作废;卸载作废。 */
export function useRemovalWindow(timer?: RemovalTimer) {
  const [view, setView] = useState<{ batch: PendingBatch | null; paused: boolean }>({ batch: null, paused: false });
  // 一页一个控制器(惰性初始化,只建一次;setView 是稳定的)。
  const [ctl] = useState(() =>
    createRemovalWindow((batch, paused) => setView({ batch, paused }), commitRemoval, timer),
  );
  useEffect(() => {
    const off = onPageHidden(() => ctl.cancel());
    return () => {
      off();
      ctl.dispose();
    };
  }, [ctl]);
  return { ...view, ctl };
}
