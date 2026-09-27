import { create } from 'zustand';
import { historyApi } from '../api';
import type { HistoryApi, HistoryEntry } from '../api';

// 「回看上局」的全部状态(ADR-025 刀 3)。**独立于 gameStore** —— 回合流程(init/turn/resume/
// 世代守卫)一行不改:历史是只读旁路,与回合流程不共享任何状态,只读 gameStore 的
// `resumableSaveId` 作为「上局是哪一局」的真相源(由调用方传入,本 store 不订阅 gameStore)。
//
// 两件事分开:
//   1. **入口探测**(probe):选择屏据它决定「回看上局」显不显示。
//      缓存只记「入口已确认可见」—— 历史一旦有了不会消失;**失败不缓存**,下次进选择屏再探;
//      同一 saveId 在途时不重复发请求(StrictMode 双挂载 / 重渲染都只发一次)。
//      **不缓存任何历史内容**。
//   2. **历史页**(open/close/loadMore):每次进页都重新读第一页,不复用探测拿到的数据。
//
// 平台 IO 只经 api/ 注入(ADR-003)。

export type HistoryPhase = 'loading' | 'error' | 'ready';
export type MorePhase = 'idle' | 'loading' | 'error';

export interface HistoryStoreState {
  /** 已确认入口可见的 saveId(只存「是」,不存「否」)。 */
  visible: Record<string, true>;
  /** 正在查看历史的 saveId;null = 不在历史页。 */
  viewing: string | null;
  phase: HistoryPhase;
  entries: HistoryEntry[];
  nextAfterTurn: number | null;
  more: MorePhase;

  /** 对上局 saveId 探测一次(已确认可见 / 在途中 → 不发请求)。 */
  probe(saveId: string): void;
  open(saveId: string): void;
  close(): void;
  /** 首屏失败后「再试一次」。 */
  retry(): void;
  /** 「加载更多」/ 翻页失败后的「再试一次」。 */
  loadMore(): void;
}

/**
 * 显示判据(口径 B):200 且(第一页至少一条 **event**,或还有下一页)。
 * ⚠️ 按事件数不按条目数:无事件的导入档,第一页是一条 before_recording 缺口。
 */
export function probeShowsEntry(entries: readonly HistoryEntry[], nextAfterTurn: number | null): boolean {
  return entries.some((e) => e.kind === 'event') || nextAfterTurn !== null;
}

/**
 * 翻页拼接(ADR-025 刀 3 追加,校勘读代码时发现):后端按回合号切页,一段连续缺口会被切成两段、分在两页返回 ——
 * 直接拼接会让同一段缺口显示两次(导入老档两行「更早的回合没有留下记录」;写失败 97–102 → 97–99 + 100–102)。
 * 已加载列表的末条与新页首条是**同 reason** 的缺口且**首尾相接**(新.fromTurn === 旧.toTurn + 1)→ 合并成一条。
 */
export function appendPage(loaded: readonly HistoryEntry[], next: readonly HistoryEntry[]): HistoryEntry[] {
  const last = loaded[loaded.length - 1];
  const first = next[0];
  if (
    last?.kind === 'gap' &&
    first?.kind === 'gap' &&
    last.reason === first.reason &&
    first.fromTurn === last.toTurn + 1
  ) {
    return [...loaded.slice(0, -1), { ...last, toTurn: first.toTurn }, ...next.slice(1)];
  }
  return [...loaded, ...next];
}

export function createHistoryStore(api: HistoryApi) {
  return create<HistoryStoreState>((set, get) => {
    /** 在途探测(非响应式;只为去重)。完成即删 —— 失败因此不会被记住。 */
    const probing = new Set<string>();
    /** 页世代:每次 open / close 自增,迟到的响应一律丢弃(离开后再回来,旧请求不许写进新页)。 */
    let epoch = 0;

    const loadFirst = async (saveId: string) => {
      const mine = ++epoch;
      set({ viewing: saveId, phase: 'loading', entries: [], nextAfterTurn: null, more: 'idle' });
      try {
        const page = await api.readHistory(saveId, null);
        if (mine !== epoch) return;
        set({ phase: 'ready', entries: page.entries, nextAfterTurn: page.nextAfterTurn });
      } catch {
        if (mine !== epoch) return;
        set({ phase: 'error' });
      }
    };

    return {
      visible: {},
      viewing: null,
      phase: 'loading',
      entries: [],
      nextAfterTurn: null,
      more: 'idle',

      probe(saveId) {
        if (get().visible[saveId] || probing.has(saveId)) return;
        probing.add(saveId);
        void api
          .readHistory(saveId, null)
          .then((page) => {
            if (probeShowsEntry(page.entries, page.nextAfterTurn)) {
              set((s) => ({ visible: { ...s.visible, [saveId]: true } }));
            }
          })
          .catch(() => {
            // 501 / 404 / 503 / 网络失败:入口不出现,不报错,不影响「继续上局」。
          })
          .finally(() => probing.delete(saveId));
      },

      open(saveId) {
        void loadFirst(saveId);
      },

      close() {
        epoch++;
        set({ viewing: null, phase: 'loading', entries: [], nextAfterTurn: null, more: 'idle' });
      },

      retry() {
        const saveId = get().viewing;
        if (saveId) void loadFirst(saveId);
      },

      loadMore() {
        const { viewing: saveId, nextAfterTurn, more, phase } = get();
        if (!saveId || nextAfterTurn === null || more === 'loading' || phase !== 'ready') return;
        const mine = epoch;
        set({ more: 'loading' });
        void api
          .readHistory(saveId, nextAfterTurn)
          .then((page) => {
            if (mine !== epoch) return;
            set((s) => ({
              entries: appendPage(s.entries, page.entries),
              nextAfterTurn: page.nextAfterTurn,
              more: 'idle',
            }));
          })
          .catch(() => {
            if (mine !== epoch) return;
            // 已加载的全部记录保留;只把底部换成失败提示 + 再试一次,整页不进错误态。
            set({ more: 'error' });
          });
      },
    };
  });
}

export const useHistoryStore = createHistoryStore(historyApi);
