// api/ 适配层「对上暴露」的 provider-agnostic 契约(ADR-003 决策 2/3)。
//
// 这是逻辑/状态层唯一被允许依赖的网络抽象:它们只见这里的接口与类型,
// 永不见 fetch / EventSource / wx.*(平台 IO 收进 api/ 内部实现,迁移时只换它)。
// Phase 4 换 WebSocket 只新增一个 TurnStream 实现,本文件与逻辑层一行不改。
//
// 类型口径对齐已实测定型的 wire(ADR-006 回合 SSE / ADR-007 init plain POST):
//   - init  : POST /api/game/init {archetype} → InitResult / 失败 GameApiError
//   - 回合  : POST /api/game/{saveId}/turn {turn,actionId} → TurnStream 四类事件
// 真理之源是 docs/CONTEXT.md §二(schema v0.2)与 server 端 InitResponse / TurnEventSink。

import type {
  Archetype,
  AvailableAction,
  Character,
  Ending,
  GameState,
  GameStatus,
  Mode,
  Rule,
  World,
} from '../types/schema';

/** 消毒后的规则(剥 isTrue/hiddenLogic,只留玩家可见的 content 与 discovered)。 */
export type ClientRule = Pick<Rule, 'id' | 'content' | 'discovered'>;

/**
 * 客户端消毒投影(server `Engine.toClientState()` 的产物,CONTEXT §三.9 视图 3)。
 * 与持久化 GameWorld 同构,但 rules 已剥隐藏字段 —— 客户端永不接收 isTrue/hiddenLogic。
 */
export interface ClientWorld {
  schemaVersion: string;
  mode: Mode;
  archetypes: Archetype[];
  world: World;
  character: Character;
  rules: ClientRule[];
  state: GameState;
  endings: Ending[];
}

/**
 * 选择屏一张卡片的摘要(后端 `GET /api/archetypes` 下发,ADR-008 决策 4)。
 * 已激活(active=true)可选、带钩子/标签;已知未开放(active=false)灰显「敬请期待」、不可点。
 */
export interface ArchetypeSummary {
  archetype: Archetype;
  displayName: string;
  /** 一句话钩子(可选;未激活占位为 null/缺省)。 */
  tagline: string | null;
  /** 氛围/危险短标签(可选;未激活占位为 null/缺省)。 */
  vibeTag: string | null;
  active: boolean;
}

/**
 * 一个行为档的显式值区间(#3 数值行为化,后端 init 下发)。{@link min}/{@link max} 是 inclusive 闭区间,
 * axisRole 无关——前端只需 `min ≤ value ≤ max` 即可解析当前档(无须懂 depletion/accumulation);各档连续、
 * 覆盖整个值域。`narrationHint` 不下发(仅服务端注入 prompt)。
 */
export interface AxisBand {
  min: number;
  max: number;
  /** 档名(玩家可见中文短词,如「濒危」「灵力枯竭」「深陷」)。 */
  label: string;
  /**
   * 该档的风险等级(ADR-018 severity 契约)。**由服务端派生,前端只渲染、绝不自行判断危不危险**——
   * 按区间匹配当前档是数据匹配(合法),判断该档危险与否是语义判断(须在服务端完成)。
   * 可选:老响应可能没有;缺省 / 未知值一律安全降级(见 `features/game/bands.ts`)。
   */
  severity?: AxisSeverity;
}

/**
 * 数值档的风险等级(ADR-018)。`neutral`=无风险语义 / `caution`=预警 / `danger`=危险。
 * 前端四套数值主题**只呈现风险等级,不猜测数值好坏**。
 */
export type AxisSeverity = 'neutral' | 'caution' | 'danger';

/**
 * 本模式一个数值轴的展示元数据(ADR-008 决策 1 前端消费方)。后端 init 下发 [{key,displayName,bands?}],
 * 前端据此渲染数值面板项 + 中文名(末日 体力/饥饿、规则怪谈 体力/理智);值由 attributes map 提供。
 * {@link bands} 可选(#3):有则前端在数字旁显示当前档 label(如「气血 28 · 气血枯竭」),无则只显数字。
 */
export interface AttributeAxisMeta {
  key: string;
  displayName: string;
  /** 行为档区间表(#3,可选);缺省 = 该轴无档,前端只显数字。 */
  bands?: AxisBand[];
}

/** init 成功结果(ADR-007:plain POST 一次性下发,openingNarrative 是 transient 字段)。 */
export interface InitResult {
  saveId: string;
  world: ClientWorld;
  /** 开场散文整段(后端不流式;逐字 vibe 由前端 client-side reveal 动画补,ADR-007 §1)。 */
  openingNarrative: string;
  /** 初始决策圈(2–4 个,只能选 id)。 */
  availableActions: AvailableAction[];
  /** 本模式数值轴元数据(顺序即面板渲染顺序);ADR-008 多模式动态面板。 */
  attributes: AttributeAxisMeta[];
}

/** 回合 delta 里随发现规则下发的轻量条目(消毒后只有 id + content)。 */
export interface DiscoveredRule {
  id: number;
  content: string;
}

/**
 * 回合流末一次性的消毒状态变化(ADR-006 §4.2 `delta` 事件)。
 * `attributes` 是引擎落账后各数值轴的绝对新值(CONTEXT §三.8,key→value;规则怪谈 {hp,san}、末日 {hp,hunger}),
 * discoveredRules 是当前完整已发现集(非增量)。
 *
 * <p>线上 wire 里各数值轴是 top-level 字段(turn/status/discoveredRules/availableActions 之外的数值键);
 * api 适配层(h5GameApi)负责把它们收进 {@link TurnDelta.attributes} map,逻辑层只见 provider-agnostic 形态。
 */
export interface TurnDelta {
  turn: number;
  status: GameStatus;
  attributes: Record<string, number>;
  discoveredRules: DiscoveredRule[];
  availableActions: AvailableAction[];
}

/** 结局画面数据(ADR-006 §4.2 `ending` 事件,已消毒)。 */
export interface EndingPayload {
  id: string;
  title: string;
  description: string;
}

/** 错误事件(非法动作 / 忙态 / 不可恢复失败)。code 供前端分支,message 中文可直接展示。 */
export interface StreamError {
  code: string;
  message: string;
}

/**
 * provider-agnostic 回合流(ADR-003 决策 3 的核心资产)。逻辑层只见这四类语义事件
 * + 最小生命周期,永不见底层传输(H5 = fetch+SSE;Phase 4 小程序 = WebSocket)。
 *
 * 调用方应在拿到本对象后<b>同步</b>注册回调;实现会在 microtask 后才真正发起请求,
 * 保证注册早于任何事件分发(无竞态)。
 */
export interface TurnStream {
  /** 叙事 token 增量,逐字追加到散文区(可被多次调用)。 */
  onNarrative(cb: (textDelta: string) => void): void;
  /** 流末一次性的消毒状态变化。 */
  onDelta(cb: (delta: TurnDelta) => void): void;
  /** 命中结局(在 delta 之后)。 */
  onEnding(cb: (ending: EndingPayload) => void): void;
  /** 非法动作 / 不可恢复失败(含 HTTP/网络层失败,由实现归一为本事件)。 */
  onError(cb: (err: StreamError) => void): void;
  /** 流结束(正常完成或出错或被 close,只触发一次)—— 逻辑层据此退出 generating 态。 */
  onClose(cb: () => void): void;
  /** 调用方主动取消(如离开页面)。幂等。 */
  close(): void;
}

/** api/ 适配层在网络/协议层失败时抛出的归一错误(逻辑层据 code 分支,不接触 HTTP 细节)。 */
export class GameApiError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = 'GameApiError';
    this.code = code;
  }
}

/**
 * 整局闭环的 provider-agnostic 接口(对上暴露)。H5 实现见 h5GameApi.ts;
 * 逻辑/状态层只依赖本接口,Phase 4 换实现不动它们。
 */
export interface GameApi {
  /**
   * 取可选世界目录(选择屏第一屏,ADR-008 决策 4)。已激活在前 + 已知未开放占位在后。
   * @throws GameApiError 网络/协议失败(选择屏据此出重试)。
   */
  listArchetypes(): Promise<ArchetypeSummary[]>;

  /**
   * 起一局新世界(INITIALIZING,ADR-007)。阻塞直到 world-gen 完成。
   * <p>ADR-013 混合模式:入参可为单 archetype(单体,行为不变)或<b>有序数组(host 在前)</b>——
   * 长度 2 = 融合世界(round 1 彩蛋 修仙×规则怪谈)。wire 形态由实现归一(单值 {archetype} /
   * 双值 {archetypes:[...]}),逻辑层只见本签名。
   * @throws GameApiError world-gen 救不回(502)/ 网络失败 —— 整局 ERROR,前端出「重新生成」。
   */
  initGame(archetypes: Archetype | readonly Archetype[]): Promise<InitResult>;

  /**
   * 续局查询(ADR-015 Slice 2:GET /api/game/{saveId}/state)。响应复用 InitResult 形态
   * (openingNarrative 恒空串——transient 字段不落盘;续局散文由 world.state.log 末条补位)。
   * @throws GameApiError 存档不存在/已失效(code=session_not_found,404)或网络失败——
   *         调用方据此静默清 saveId 回正常起局。
   */
  resumeGame(saveId: string): Promise<InitResult>;

  /**
   * 开一条回合流(ADR-006)。立即返回 TurnStream(请求在 microtask 后发起),
   * 调用方同步注册回调即可。
   */
  openTurnStream(saveId: string, turn: number, actionId: string): TurnStream;
}
