// 线 B1 原型 · 融合入口拖拽手势的**纯逻辑**(无 DOM、无平台 IO、无 React)。
//
// 这一刀只验一个假设:**在纵向可滚动的世界列表里,拖拽手势是否可用**。
// 判定逻辑全部收在本文件,让阈值与判据可被单测钉死 —— 真机冒烟只需回答体感,
// 不必靠「跑一跑看看」来推断代码在做什么(ADR-018 §4.14 观测工具本身需要被验证的同族纪律)。
//
// 术语:被拖者 = foreign,承接者 = host(Felix 的融合入口设计:拖 A 到 B 上 = 把 A 揉入 B)。

/** 可调阈值。真机上由原型页的调参面板改,不必重新部署即可试手感。 */
export interface DragTunables {
  /** 长按多久算「抓起」(ms)。太短=想滚动却抓起卡;太长=拖拽迟钝。 */
  longPressMs: number;
  /** 长按计时期间允许的手指抖动(px)。超过它 = 判为滚动意图,取消长按。 */
  moveTolerancePx: number;
  /** 横向位移达到多少 px 直接进入拖拽(不必等长按)。纵向不走这条,留给滚动。 */
  dirLockPx: number;
  /** 进入可提交态所需的重叠比(拖动卡与目标卡的交叠面积 / 卡面积)。 */
  overlapRatio: number;
  /** 进入可提交态所需的磁吸区停留时长(ms)。与重叠比是「或」关系。 */
  dwellMs: number;
  /** 屏幕上下边缘多宽的带触发自动滚动(px)。 */
  edgePx: number;
  /** 自动滚动最大速度(px/s,在最贴边处取到)。 */
  maxScrollPxPerSec: number;
}

export const DEFAULT_TUNABLES: DragTunables = {
  longPressMs: 180,
  moveTolerancePx: 10,
  dirLockPx: 12,
  overlapRatio: 0.62,
  dwellMs: 300,
  edgePx: 90,
  maxScrollPxPerSec: 900,
};

/**
 * 按住期间每次移动的判定(长按计时尚未走完时):
 * - `drag`  横向意图明确 → 立刻抓起(CSS `touch-action: pan-y` 已挡住原生横向滚动,不抢手势);
 * - `scroll` 位移超容差且不是横向 → 玩家要滚页面,取消长按(**这条是本刀成败的关键**);
 * - `none`  还看不出来,继续等长按计时。
 */
export type PressVerdict = 'none' | 'drag' | 'scroll';

export function classifyMove(dx: number, dy: number, t: DragTunables): PressVerdict {
  const ax = Math.abs(dx);
  const ay = Math.abs(dy);
  if (ax >= t.dirLockPx && ax > ay) return 'drag';
  if (Math.hypot(dx, dy) > t.moveTolerancePx) return 'scroll';
  return 'none';
}

export interface Rect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export function rectCenter(r: Rect): { x: number; y: number } {
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}

export function rectContains(r: Rect, x: number, y: number): boolean {
  return x >= r.left && x <= r.left + r.width && y >= r.top && y <= r.top + r.height;
}

/** 交叠面积占 `a`(拖动卡)面积的比例,0–1。 */
export function overlapRatio(a: Rect, b: Rect): number {
  const w = Math.min(a.left + a.width, b.left + b.width) - Math.max(a.left, b.left);
  const h = Math.min(a.top + a.height, b.top + b.height) - Math.max(a.top, b.top);
  if (w <= 0 || h <= 0) return 0;
  const area = a.width * a.height;
  if (area <= 0) return 0;
  return (w * h) / area;
}

export interface Candidate {
  id: string;
  rect: Rect;
}

/**
 * 命中哪张目标卡:**以拖动卡的中心点**落在谁身上为准(不是手指位置)——
 * 手指落点因抓握姿势而异,卡中心是玩家眼睛看到的东西,判定与视觉一致才不别扭。
 * 多张命中(卡片间距为负时不会发生,留作稳健)取交叠最大的那张。
 */
export function findTarget(dragged: Rect, candidates: readonly Candidate[]): string | null {
  const c = rectCenter(dragged);
  let best: string | null = null;
  let bestOverlap = -1;
  for (const cand of candidates) {
    if (!rectContains(cand.rect, c.x, c.y)) continue;
    const o = overlapRatio(dragged, cand.rect);
    if (o > bestOverlap) {
      bestOverlap = o;
      best = cand.id;
    }
  }
  return best;
}

/** 可提交态判据:交叠够深 **或** 在磁吸区停够久。 */
export function isArmed(overlap: number, dwellMs: number, t: DragTunables): boolean {
  return overlap >= t.overlapRatio || dwellMs >= t.dwellMs;
}

/**
 * 边缘自动滚动速度(px/s;负=向上)。越贴边越快,线性 ramp,边缘带外为 0。
 * 这是移动端拖拽最难做对的部分:太慢够不着屏幕外的卡,太快一下冲过头。
 */
export function autoScrollVelocity(clientY: number, viewportH: number, t: DragTunables): number {
  if (t.edgePx <= 0) return 0;
  if (clientY < t.edgePx) {
    const k = Math.min(1, (t.edgePx - clientY) / t.edgePx);
    return -t.maxScrollPxPerSec * k;
  }
  const fromBottom = viewportH - clientY;
  if (fromBottom < t.edgePx) {
    const k = Math.min(1, (t.edgePx - fromBottom) / t.edgePx);
    return t.maxScrollPxPerSec * k;
  }
  return 0;
}

// ── 原型世界表(硬编码,不接 registry / 不调后端)────────────────────────────
// 本刀刻意不走 `GET /api/archetypes`:原型页零网络调用,临时 app 上不需要 key、
// 不吃 ADR-016 的额度,也不会因后端不可用而测不成手势。
// 卡片氛围 class 仍复用生产的主题注册表,好让卡片尺寸/密度与真实列表一致 —— 滚动冲突
// 只有在**真实几何**下才测得准。

export interface ProtoWorld {
  id: string;
  displayName: string;
  vibeTag: string;
  tagline: string;
  active: boolean;
}

export const PROTO_WORLDS: readonly ProtoWorld[] = [
  {
    id: 'rules_creepy',
    displayName: '规则怪谈',
    vibeTag: '高危 · 真假守则',
    tagline: '墙上贴着守则,有几条是假的。',
    active: true,
  },
  {
    id: 'apocalypse',
    displayName: '末日生存',
    vibeTag: '极危 · 断粮',
    tagline: '水和罐头会先于希望耗尽。',
    active: true,
  },
  {
    id: 'cthulhu',
    displayName: '克苏鲁',
    vibeTag: '极危 · 禁忌知识',
    tagline: '你读懂的每一页,都在读你。',
    active: true,
  },
  {
    id: 'cultivation',
    displayName: '修仙',
    vibeTag: '中危 · 逆天改命',
    tagline: '境界越高,天看得越清楚。',
    active: true,
  },
  { id: 'life_sim', displayName: '人生模拟', vibeTag: '', tagline: '', active: false },
  { id: 'cyberpunk', displayName: '赛博朋克', vibeTag: '', tagline: '', active: false },
];

/**
 * 加长列表(真机验收第 3 条「目标卡在屏幕外」用):六张卡在 812px 视口下只滚得动约 160px,
 * 边缘自动滚动几乎无从考验。世界库 backlog 规划到 14 个世界,列表迟早会长——
 * 用**同一批世界的副本**加长(id 带 `~n` 后缀,组合判定与文案一律看**基名**),
 * 不假造新世界、不改卡片几何,只是把列表拉长到该有的样子。
 */
export function baseOf(id: string): string {
  const i = id.indexOf('~');
  return i < 0 ? id : id.slice(0, i);
}

export function protoWorlds(long: boolean): readonly ProtoWorld[] {
  if (!long) return PROTO_WORLDS;
  const extra = PROTO_WORLDS.map((w) => ({ ...w, id: `${w.id}~2` }));
  return [...PROTO_WORLDS, ...extra];
}

const ACTIVE_IDS = new Set(PROTO_WORLDS.filter((w) => w.active).map((w) => w.id));

/**
 * 合法组合表(**原型口径**):任意两个已激活世界都可揉,方向决定 host。
 * 与后端 `FUSION_COMBOS` 现只登记两对**刻意不同** —— 本刀验的是手势,不是融合内容;
 * Felix 的设计前提正是「四套碎解材质覆盖六对组合」,原型按那个前提给出六对,
 * 才能同时试到「合法吸附」与「无效排斥」两条路径(未激活世界即无效目标)。
 */
export function comboAllowed(foreignId: string, hostId: string): boolean {
  const a = baseOf(foreignId);
  const b = baseOf(hostId);
  if (a === b) return false; // 同一个世界揉自己:副本也算同一个
  return ACTIVE_IDS.has(a) && ACTIVE_IDS.has(b);
}

const NAME_BY_ID = new Map(PROTO_WORLDS.map((w) => [w.id, w.displayName]));

/** 提交时打印的那一行(本刀提交后**只打印文本**,不生成世界、不调 init)。 */
export function commitLine(foreignId: string, hostId: string): string {
  const fid = baseOf(foreignId);
  const hid = baseOf(hostId);
  const a = NAME_BY_ID.get(fid) ?? fid;
  const b = NAME_BY_ID.get(hid) ?? hid;
  return `将「${a}」揉入「${b}」 · host=${hid} foreign=${fid}`;
}
