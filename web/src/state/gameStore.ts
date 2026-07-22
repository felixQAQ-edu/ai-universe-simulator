// 整局游戏状态(Zustand,ADR-003 决策 4)。单局单人,规模小 —— 一份消毒 client world
// + 流式增量(narrative 累加 / delta 落数值&规则面板 / ending 出画面 / error 处置)。
//
// 边界纪律(ADR-003 决策 2):本文件只依赖 ../api 的 provider-agnostic 契约与默认实例,
// 永不 import fetch / EventSource / wx.*。回合流通过注入的 GameApi 驱动,故可在测试里
// 喂 mock api + 合成事件序列断言状态推进。

import { create } from 'zustand';
import type {
  ArchetypeSummary,
  AttributeAxisMeta,
  ClientWorld,
  DiscoveredRule,
  EndingPayload,
  GameApi,
  TurnStream,
} from '../api';
import { GameApiError, gameApi } from '../api';
import type { Archetype, AvailableAction } from '../types/schema';

/**
 * 整局状态(对应设计稿/规格的相位投影到客户端):
 * - idle        起始,未开局
 * - initializing world-gen 进行中(init POST 在途)
 * - initError    world-gen 救不回 / 网络失败 → 出「重新生成」
 * - awaiting     世界就绪,等玩家选动作(含开场 reveal 后)
 * - generating   回合流进行中(叙事逐字流入)
 * - ended        命中结局
 */
export type GameStatus =
  | 'idle'
  | 'initializing'
  | 'initError'
  | 'awaiting'
  | 'generating'
  | 'ended';

export interface GameState {
  status: GameStatus;
  /** 选择屏目录(可选世界 + 未开放占位);来自 GET /api/archetypes。 */
  archetypes: ArchetypeSummary[];
  archetypesLoading: boolean;
  /** 选择屏目录加载失败提示(可重试)。 */
  archetypesError: string | null;
  /**
   * 最近一次选中的 archetype(initError「重新生成」据此重试同一模式)。
   * ADR-013 混合模式:融合世界为有序数组(host 在前),重试原样重发双值。
   */
  lastArchetype: Archetype | Archetype[] | null;
  saveId: string | null;
  world: ClientWorld | null;
  /** 当前散文区文本:开场为整段(前端 reveal 动画演绎),回合为逐字累加的实时流。 */
  narrative: string;
  /** 开场散文整段(transient,仅供开场 client-side reveal;不随回合改变)。 */
  openingNarrative: string;
  turn: number;
  /** 本模式数值轴元数据(key + 中文名,顺序即面板顺序);来自 init,静态不随回合变(ADR-008 多模式)。 */
  attributeAxes: AttributeAxisMeta[];
  /** 各数值轴当前绝对值(key→value);init 由 world.character.attributes、回合由 delta.attributes 更新。 */
  attributeValues: Record<string, number>;
  /** 全部玩家可见规则(content),来自消毒 world;discovered 高亮据 discoveredRuleIds。 */
  discoveredRuleIds: number[];
  availableActions: AvailableAction[];
  ending: EndingPayload | null;
  /** 不可恢复失败信息(init 阶段)。 */
  errorMessage: string | null;
  /**
   * init 失败归一 code(来自 GameApiError.code):`quota_exceeded`(成本闸门 429,ADR-016)
   * vs `world_gen_failed`/网络等真失败——错误屏据此切标题(配额拦截不是「失败」)。
   */
  errorCode: string | null;
  /** 可恢复的回合级提示(非法动作 / 忙态),展示后玩家可重选,状态留在 awaiting。 */
  notice: string | null;

  /** localStorage 里可续的上局 saveId(ADR-015 Slice 2;无则选择屏不显「继续上局」入口)。 */
  resumableSaveId: string | null;

  /** 拉取选择屏目录(选择屏 mount 时调用)。失败置 archetypesError,可重试。 */
  loadArchetypes: () => Promise<void>;
  /** 开局:单 archetype = 单体;有序数组(host 在前)= 融合世界(ADR-013)。 */
  startGame: (archetypes: Archetype | Archetype[]) => Promise<void>;
  /**
   * 续上局(ADR-015 Slice 2):经 api.resumeGame 恢复会话状态。散文区由 world.state.log 末条补位
   * (openingNarrative 不落盘);ended 局照样可回看结局。失败(404/损坏/网络)→ 静默清 saveId
   * 回到选择屏,不弹错误挡路。
   */
  resumeGame: () => Promise<void>;
  chooseAction: (actionId: string) => void;
  /** 离开/重开时清理在途回合流,回到选择屏(保留已拉取的目录)。 */
  reset: () => void;
}

// ── 续局 saveId 持久化(ADR-015 Slice 2)────────────────────────────────
// 本刀是 web/src 首次引入 localStorage:纯展示层状态(记住上一局的 saveId),不新立抽象;
// 全部读写走下面三个 helper 并 try/catch(隐私模式/无 storage 环境优雅降级为「无续局入口」)。
const SAVE_ID_KEY = 'aiuniverse.saveId';

function readSavedId(): string | null {
  try {
    return globalThis.localStorage?.getItem(SAVE_ID_KEY) ?? null;
  } catch {
    return null;
  }
}

function writeSavedId(saveId: string): void {
  try {
    globalThis.localStorage?.setItem(SAVE_ID_KEY, saveId);
  } catch {
    /* 写不进就没有续局入口,不影响本局 */
  }
}

function clearSavedId(): void {
  try {
    globalThis.localStorage?.removeItem(SAVE_ID_KEY);
  } catch {
    /* 同上 */
  }
}

const INITIAL = {
  status: 'idle' as GameStatus,
  lastArchetype: null as Archetype | Archetype[] | null,
  saveId: null,
  world: null,
  narrative: '',
  openingNarrative: '',
  turn: 0,
  attributeAxes: [] as AttributeAxisMeta[],
  attributeValues: {} as Record<string, number>,
  discoveredRuleIds: [] as number[],
  availableActions: [] as AvailableAction[],
  ending: null,
  errorMessage: null,
  errorCode: null,
  notice: null,
};

/**
 * 可恢复的回合错误 code(展示提示、留在 awaiting,不算整局失败)。
 * quota_exceeded(ADR-016):守卫 0 在相位 CAS 之前拒绝,会话服务端停留 AWAITING——
 * 次日额度恢复同一局可续,故按可恢复处理(notice 展示「明天再来」文案)。
 */
const RECOVERABLE_TURN_ERRORS = new Set(['illegal_action', 'busy', 'session_not_found', 'quota_exceeded']);

/**
 * 用注入的 GameApi 建一个 store。生产用默认 gameApi;测试传 mock api + 合成 TurnStream。
 */
export function createGameStore(api: GameApi) {
  return create<GameState>((set, get) => {
    // 当前在途回合流(用于 reset / 防止过期流写回)。
    let activeStream: TurnStream | null = null;

    const discoveredIds = (rules: DiscoveredRule[]) => rules.map((r) => r.id);

    return {
      ...INITIAL,
      // 目录状态在 INITIAL 之外维护 —— reset/startGame 不应清掉已拉取的可选世界列表。
      archetypes: [] as ArchetypeSummary[],
      archetypesLoading: false,
      archetypesError: null,
      // 同样在 INITIAL 之外:reset(换个世界)不该抹掉「继续上局」入口。
      resumableSaveId: readSavedId(),

      async loadArchetypes() {
        if (get().archetypesLoading || get().archetypes.length > 0) return;
        set({ archetypesLoading: true, archetypesError: null });
        try {
          const list = await api.listArchetypes();
          set({ archetypes: list, archetypesLoading: false });
        } catch {
          set({ archetypesLoading: false, archetypesError: '世界列表加载失败,请重试' });
        }
      },

      async startGame(archetypes) {
        if (get().status === 'initializing') return;
        activeStream?.close();
        activeStream = null;
        set({ ...INITIAL, status: 'initializing', lastArchetype: archetypes });
        try {
          const res = await api.initGame(archetypes);
          const attrs = res.world.character?.attributes ?? {};
          writeSavedId(res.saveId); // 起局成功即记住,起局即崩也能续(与后端 init 后写盘对齐)
          set({
            status: 'awaiting',
            resumableSaveId: res.saveId,
            saveId: res.saveId,
            world: res.world,
            openingNarrative: res.openingNarrative,
            narrative: res.openingNarrative,
            turn: res.world.state?.turn ?? 0,
            attributeAxes: res.attributes ?? [],
            attributeValues: { ...attrs },
            discoveredRuleIds: res.world.rules.filter((r) => r.discovered).map((r) => r.id),
            availableActions: res.availableActions,
            ending: null,
            errorMessage: null,
            notice: null,
          });
        } catch (e) {
          const msg = e instanceof GameApiError ? e.message : '世界生成失败,请重新生成';
          const code = e instanceof GameApiError ? e.code : null;
          set({ status: 'initError', errorMessage: msg, errorCode: code });
        }
      },

      async resumeGame() {
        const saveId = get().resumableSaveId;
        if (!saveId || get().status === 'initializing') return;
        activeStream?.close();
        activeStream = null;
        set({ ...INITIAL, status: 'initializing' });
        try {
          const res = await api.resumeGame(saveId);
          const attrs = res.world.character?.attributes ?? {};
          const log = res.world.state?.log ?? [];
          // 续局散文补位:log 末条叙事(openingNarrative 不落盘)→ 兜底世界背景。
          const narrative = (log.length > 0 ? log[log.length - 1].narrative : '') || res.world.world?.background || '';
          const ended = res.world.state?.status === 'ended';
          const reached = ended ? res.world.endings.find((e) => e.reached) : undefined;
          set({
            status: ended ? 'ended' : 'awaiting',
            saveId: res.saveId,
            world: res.world,
            openingNarrative: '',
            narrative,
            turn: res.world.state?.turn ?? 0,
            attributeAxes: res.attributes ?? [],
            attributeValues: { ...attrs },
            discoveredRuleIds: res.world.rules.filter((r) => r.discovered).map((r) => r.id),
            availableActions: res.availableActions,
            ending: reached
              ? { id: reached.id, title: reached.title, description: reached.description ?? '' }
              : null,
            errorMessage: null,
            // 一次性续局确认反馈(非预警):让玩家知道接上了、从哪接的;下一次选动作即散
            // (chooseAction 清 notice),不加常驻 UI。
            notice: ended ? null : `已从上次落笔处接续(第 ${res.world.state?.turn ?? 0} 回合)`,
          });
        } catch {
          // 续局失败(404/损坏/网络):静默清 saveId 回到正常起局,不弹错误挡路。
          clearSavedId();
          set({ ...INITIAL, status: 'idle', resumableSaveId: null });
        }
      },

      chooseAction(actionId) {
        const { status, saveId, turn, availableActions } = get();
        if (status !== 'awaiting' || !saveId) return;
        if (!availableActions.some((a) => a.id === actionId)) {
          set({ notice: '该选项已失效,请重新选择' });
          return;
        }

        // 新回合:清空散文区(改为实时流)、清提示,进 generating。
        set({ status: 'generating', narrative: '', notice: null });

        const stream = api.openTurnStream(saveId, turn, actionId);
        activeStream = stream;
        let ended = false;

        const stale = () => activeStream !== stream;

        stream.onNarrative((textDelta) => {
          if (stale()) return;
          set((s) => ({ narrative: s.narrative + textDelta }));
        });

        stream.onDelta((delta) => {
          if (stale()) return;
          set({
            turn: delta.turn,
            attributeValues: delta.attributes,
            discoveredRuleIds: discoveredIds(delta.discoveredRules),
            availableActions: delta.availableActions,
          });
        });

        stream.onEnding((ending) => {
          if (stale()) return;
          ended = true;
          set({ ending, status: 'ended' });
        });

        stream.onError((err) => {
          if (stale()) return;
          if (RECOVERABLE_TURN_ERRORS.has(err.code)) {
            // 可恢复:复用未变的散文/动作,回到 awaiting + 提示。
            set({ status: 'awaiting', notice: err.message });
          } else {
            set({ status: 'awaiting', notice: err.message || '本回合处理失败,请重试' });
          }
        });

        stream.onClose(() => {
          if (stale()) return;
          activeStream = null;
          // 流自然结束:若已 ended 保持;否则回 awaiting(delta 已应用,可继续)。
          set((s) => (s.status === 'generating' && !ended ? { ...s, status: 'awaiting' } : s));
        });
      },

      reset() {
        activeStream?.close();
        activeStream = null;
        set({ ...INITIAL });
      },
    };
  });
}

/** 生产单例(组件用它)。 */
export const useGameStore = createGameStore(gameApi);
