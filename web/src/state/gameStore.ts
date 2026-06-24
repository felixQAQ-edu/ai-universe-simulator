// 整局游戏状态(Zustand,ADR-003 决策 4)。单局单人,规模小 —— 一份消毒 client world
// + 流式增量(narrative 累加 / delta 落数值&规则面板 / ending 出画面 / error 处置)。
//
// 边界纪律(ADR-003 决策 2):本文件只依赖 ../api 的 provider-agnostic 契约与默认实例,
// 永不 import fetch / EventSource / wx.*。回合流通过注入的 GameApi 驱动,故可在测试里
// 喂 mock api + 合成事件序列断言状态推进。

import { create } from 'zustand';
import type {
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
  /** 可恢复的回合级提示(非法动作 / 忙态),展示后玩家可重选,状态留在 awaiting。 */
  notice: string | null;

  startGame: (archetype: Archetype) => Promise<void>;
  chooseAction: (actionId: string) => void;
  /** 离开/重开时清理在途回合流。 */
  reset: () => void;
}

const INITIAL = {
  status: 'idle' as GameStatus,
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
  notice: null,
};

/** 可恢复的回合错误 code(展示提示、留在 awaiting,不算整局失败)。 */
const RECOVERABLE_TURN_ERRORS = new Set(['illegal_action', 'busy', 'session_not_found']);

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

      async startGame(archetype) {
        if (get().status === 'initializing') return;
        activeStream?.close();
        activeStream = null;
        set({ ...INITIAL, status: 'initializing' });
        try {
          const res = await api.initGame(archetype);
          const attrs = res.world.character?.attributes ?? {};
          set({
            status: 'awaiting',
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
          set({ status: 'initError', errorMessage: msg });
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
