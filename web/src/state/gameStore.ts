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
  FusionCombo,
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
  /**
   * 已登记的融合组合(ADR-019 只读投影,与目录同一次请求)。选择屏拖拽入口据它判定
   * 「拖 A 到 B 上」是否合法 —— 合法性真相源在后端 registry,前端不自备组合表。
   * 空表(老后端 / 加载失败)= 一律判无效组合:拖不出融合,但选择屏照常可用。
   */
  fusions: FusionCombo[];
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
 *
 * server_at_capacity / service_unavailable(ADR-022 裁定 4):前者是刀 2 的并发准入拒绝
 * (拒绝发生在池线程之前,**会话相位零触碰**),后者是 5xx 的状态兜底(部署窗口 / 网关故障)——
 * 两者都不动服务端状态,玩家原地再点一次即可,故可恢复。
 * ⚠️ server_at_capacity 在刀 1 上线时后端**还不会发**,这是**有意的前端先容错**
 * (同 fusions 空表兜底的先例);漏登记会让下一个读这张清单的人以为它不可恢复。
 * ⚠️ 诚实记:今天两个分支的可见行为几乎相同(下面 onError 都回 awaiting + notice,
 * 差别只在 message 为空时的兜底文案),故「加进集合」主要是**声明意图**而非改变行为。
 */
const RECOVERABLE_TURN_ERRORS = new Set([
  'illegal_action',
  'busy',
  'session_not_found',
  'quota_exceeded',
  'server_at_capacity',
  'service_unavailable',
]);

/**
 * 用注入的 GameApi 建一个 store。生产用默认 gameApi;测试传 mock api + 合成 TurnStream。
 */
export function createGameStore(api: GameApi) {
  return create<GameState>((set, get) => {
    // 当前在途回合流(用于 reset / 防止过期流写回)。
    let activeStream: TurnStream | null = null;

    // ── 在途 init/resume 的世代守卫 ────────────────────────────────────
    // 与下面 chooseAction 里的 `stale()` 是**同一个模式、同一个洞**:异步结果回来时,
    // 玩家可能已经不在那个上下文里了。回合流那条早就补了(activeStream 比对),
    // init 与 resume 这两条一直没有 —— 它们的 promise 无人可关:`reset()` 关得掉流,
    // 关不掉 `await api.initGame(...)`,fetch 也没带 AbortSignal。
    //
    // 后果不是「不完美」而是**背叛**:玩家取消/返回后,那次 world-gen(线上首局实测 ~120s)
    // 照样落地,把他扔进他刚拒绝的世界,并 writeSavedId 冲掉他原来那局的存档指针。
    // 故每次进入 init/resume 领一个世代号,回来时不符即**整段丢弃**(不 set、不写 saveId)。
    //
    // 服务端那次调用**拦不住**(plain POST 阻塞跑完 + GameSessionManager.create 无条件写盘),
    // 这里守的是「客户端不被过期结果改写」,不是「让服务端停下」——两件事,别混。
    let epoch = 0;
    const nextEpoch = () => ++epoch;
    const staleEpoch = (mine: number) => mine !== epoch;

    const discoveredIds = (rules: DiscoveredRule[]) => rules.map((r) => r.id);

    // ── 确认死档即清指针(ADR-022 刀 1.5)──────────────────────────────
    // 回合拿到 `session_not_found` = 服务端说这个档没了(`GameSessionManager` 启动即
    // `reloadFromStore()` 把盘上全部档载进内存,故「内存里没有」≈「盘上也没有或载不动」)。
    // 不清的话,玩家返回后选择屏**还在拿一个已经死掉的存档招手** —— 那个按钮本身就是个谎,
    // 再好的文案也解释不了点了为什么没用(刀 1 的 404 文案正是栽在这里)。
    //
    // ⚠️ 这不是新规矩:`resumeGame` 的 catch 今天就在做「404 → 清指针」,ADR-015 Slice 2 的
    // 既有规则本就是「存档确认不可用即清」;本刀只是让**另一个同样确凿的死亡信号**走这条既有规则。
    // 与线 C「退出不弃局」**不打架**:那条管玩家**主动返回**(触发者是人),`reset()` 照旧不清;
    // 这条管**服务端说这个档没了**(触发者是事实)。
    //
    // ⚠️ **localStorage 必须比对后清,不能无条件清**。多页签:页签 1 在玩存档 A、页签 2 开了新局 B
    // (localStorage 已是 B),页签 1 点选项拿到 404 → 无条件清会**抹掉活着的 B 的指针**。
    // 这正是 `resumeGame` 那句注释已经写着的形状:「拿一次过期失败去删一个跟它无关的存档」。
    //
    // 两半刻意不同条件,因为它们的作用域不同:
    //   · `resumableSaveId`(本页签的内存视图,恒等于本局 saveId)—— **无条件清**:
    //     A 死了是确凿事实,本页签的「继续上局」不该再指向它;
    //   · localStorage(**跨页签共享**)—— **仅当它还是这个死档时才清**:别的页签可能已写进新局。
    const forgetDeadSave = (deadId: string) => {
      if (readSavedId() === deadId) clearSavedId();
      set({ resumableSaveId: null });
    };

    return {
      ...INITIAL,
      // 目录状态在 INITIAL 之外维护 —— reset/startGame 不应清掉已拉取的可选世界列表。
      archetypes: [] as ArchetypeSummary[],
      fusions: [] as FusionCombo[],
      archetypesLoading: false,
      archetypesError: null,
      // 同样在 INITIAL 之外:reset(换个世界)不该抹掉「继续上局」入口。
      resumableSaveId: readSavedId(),

      async loadArchetypes() {
        if (get().archetypesLoading || get().archetypes.length > 0) return;
        set({ archetypesLoading: true, archetypesError: null });
        try {
          const catalog = await api.listArchetypes();
          set({ archetypes: catalog.archetypes, fusions: catalog.fusions, archetypesLoading: false });
        } catch {
          set({ archetypesLoading: false, archetypesError: '世界列表加载失败,请重试' });
        }
      },

      async startGame(archetypes) {
        if (get().status === 'initializing') return;
        activeStream?.close();
        activeStream = null;
        const mine = nextEpoch();
        set({ ...INITIAL, status: 'initializing', lastArchetype: archetypes });
        try {
          const res = await api.initGame(archetypes);
          if (staleEpoch(mine)) return; // 玩家已取消/返回:丢弃这次世界,连 saveId 都不记
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
          if (staleEpoch(mine)) return; // 已取消:失败也不该把玩家拽进错误屏
          set({ status: 'initError', errorMessage: msg, errorCode: code });
        }
      },

      async resumeGame() {
        const saveId = get().resumableSaveId;
        if (!saveId || get().status === 'initializing') return;
        activeStream?.close();
        activeStream = null;
        const mine = nextEpoch();
        set({ ...INITIAL, status: 'initializing' });
        try {
          const res = await api.resumeGame(saveId);
          if (staleEpoch(mine)) return; // 同 startGame:续局在途时返回,不得被拽回去
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
          // 已取消:**尤其不能清 saveId** —— 玩家可能已经开了新局,
          // 这时清的是那一局的指针(拿一次过期失败去删一个跟它无关的存档)。
          if (staleEpoch(mine)) return;
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
          if (err.code === 'session_not_found') forgetDeadSave(saveId);
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
        // 世代 +1 = 作废在途的 init/resume(「取消」与「游戏中返回」都经这里)。
        // `resumableSaveId` 与目录两表(`archetypes`/`fusions`)刻意在 INITIAL 之外 —— 返回不弃局:
        // 存档指针与已拉取的目录都留着,回到选择屏即见「继续上局」(ADR-015 Slice 2 的机制原样复用)。
        nextEpoch();
        set({ ...INITIAL });
      },
    };
  });
}

/** 生产单例(组件用它)。 */
export const useGameStore = createGameStore(gameApi);
