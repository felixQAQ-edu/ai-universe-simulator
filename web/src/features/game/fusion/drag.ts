// 融合入口拖拽手势的**纯逻辑**(无 DOM、无平台 IO、无 React)。
//
// 判据整体来自线 B1 原型(2026-08-03 真机六条全过、结论 (a) 顺手),阈值沿用那一轮的实测值 ——
// 原型页与 `src/proto/` 随本刀删除,这里是它唯一的去处(ADR-018 §6 立的到期日)。
//
// 术语:被拖者 = foreign,承接者 = host(ADR-019:拖 A 到 B 上 = 把 A 揉入 B;
// 动作本身表达语义,提交时直接生成有序双值 `[host, foreign]`,不新增字段)。

import type { FusionCombo } from '../../../api';

/** 手势阈值。B1 真机实测值,本刀原样沿用(改动须回真机重验,桌面测不准)。 */
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
 * - `scroll` 位移超容差且不是横向 → 玩家要滚页面,取消长按;
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
 * 多张命中取交叠最大的那张。
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

/** 组合键 `host×foreign`(方向敏感;与后端 registry / 封面 / 卡文案同键)。 */
export function fusionKey(host: string, foreign: string): string {
  return `${host}×${foreign}`;
}

/**
 * 这一对能不能揉(**合法性真相源 = 后端 `fusions` 只读投影**,ADR-019)。
 * 原型那版「任意两个已激活世界都可揉」是原型口径,**不进生产** ——
 * 否则玩家能拖出一个后端 400 的组合。
 *
 * @param fusions 后端下发的已登记组合(空表 → 一律 false:拖不出融合,但选择屏照常可用)
 * @param host    承接者(被拖到的那张)
 * @param foreign 被拖者
 */
export function isFusionAllowed(
  fusions: readonly FusionCombo[],
  host: string,
  foreign: string,
): boolean {
  if (host === foreign) return false;
  return fusions.some((f) => f.host === host && f.foreign === foreign);
}
