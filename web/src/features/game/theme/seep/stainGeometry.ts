import { type SeepCorner, type SeepSurface, type StainSpec } from './registry';

// 墨洇的几何(ADR-043 决策 3 / 6):形状、按角定位与挖空行框的测量。组件见 `SeepStain.tsx`。

export const PATH =
  'M0 100 V34 Q7 27 13 33 Q19 40 15 49 Q27 45 31 56 Q43 52 44 64 Q57 61 58 74 Q70 72 73 84 Q85 82 88 92 Q95 92 100 100 Z';
export const DOTS: readonly (readonly [number, number, number])[] = [
  [24, 38, 2.2],
  [52, 58, 1.6],
  [80, 77, 1.9],
];
/** mask 比层四边各外扩这么多(水线描边会略出框;不外扩会被 mask 区域本身切掉)。 */
export const MASK_BLEED = 8;

export interface Hole {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface StainGeometry {
  w: number;
  h: number;
  holes: Hole[];
}

/** 本层所在承载框里全部文字行框(层坐标,外扩 pad、向外取整)。框不可见 → null。 */
export function measureHoles(layer: Element, pad: number): StainGeometry | null {
  const host = layer.parentElement;
  if (!host) return null;
  const lr = layer.getBoundingClientRect();
  if (!(lr.width > 0 && lr.height > 0)) return null;
  const holes: Hole[] = [];
  const walker = document.createTreeWalker(host, NodeFilter.SHOW_TEXT);
  const range = document.createRange();
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (!n.nodeValue || !n.nodeValue.trim()) continue;
    if (n.parentElement?.closest('[data-seep]')) continue;
    range.selectNodeContents(n);
    for (const r of Array.from(range.getClientRects())) {
      if (!(r.width > 0 && r.height > 0)) continue;
      const x = Math.floor(r.left - lr.left - pad);
      const y = Math.floor(r.top - lr.top - pad);
      holes.push({
        x,
        y,
        w: Math.ceil(r.right - lr.left + pad) - x,
        h: Math.ceil(r.bottom - lr.top + pad) - y,
      });
    }
  }
  range.detach?.();
  return { w: lr.width, h: lr.height, holes };
}

export const sameGeometry = (a: StainGeometry | null, b: StainGeometry | null) =>
  a === b ||
  (!!a &&
    !!b &&
    a.w === b.w &&
    a.h === b.h &&
    a.holes.length === b.holes.length &&
    a.holes.every((h, i) => {
      const o = b.holes[i];
      return h.x === o.x && h.y === o.y && h.w === o.w && h.h === o.h;
    }));

/** 变化来自渗入层自己的节点(墨渍层重画 mask、印落下)→ 不重量,免得自己触发自己。 */
export function isOwn(node: Node): boolean {
  const el = node instanceof Element ? node : node.parentElement;
  return !!el?.closest('[data-seep]');
}

export interface CornerState {
  key: string;
  corner: SeepCorner;
  w: number;
  h: number;
  on: boolean;
}

/**
 * 某档下本承载框的每个角:在这一档出现的 → 该档尺寸、显;不出现的 → 留在 DOM 里、透明,
 * 尺寸取离当前档最近的、登记了它的那一档(先往上找)—— 这样它将来淡入时从对的尺寸开始(同样张 `display:block; opacity:0`)。
 */
export function cornerStates(stain: StainSpec, level: number, surface: SeepSurface): CornerState[] {
  const cur = Math.min(level, stain.levels.length - 1);
  const keys: SeepCorner[] = [];
  for (const lv of stain.levels)
    for (const c of lv.corners) if (c.surface === surface && !keys.includes(c.corner)) keys.push(c.corner);
  const order = [
    ...stain.levels.slice(cur).map((_, i) => cur + i),
    ...stain.levels.slice(0, cur).map((_, i) => cur - 1 - i),
  ];
  return keys.map((corner) => {
    for (const i of order) {
      const hit = stain.levels[i].corners.find((c) => c.surface === surface && c.corner === corner);
      if (hit) return { key: `${surface}-${corner}`, corner, w: hit.w, h: hit.h, on: i === cur };
    }
    throw new Error('unreachable');
  });
}

export function anchor(corner: SeepCorner, w: number, h: number): [number, number] {
  return [corner.endsWith('r') ? w : 0, corner.startsWith('b') ? h : 0];
}

/** 样张的翻转(bl 原样 / br scaleX(-1) / tl scaleY(-1)),叠上尺寸;原点 = 贴着的那个角。 */
export function shape(corner: SeepCorner, w: number, h: number): string {
  const sx = (corner.endsWith('r') ? -w : w) / 100;
  const sy = (corner.startsWith('t') ? -h : h) / 100;
  return `scale(${sx}, ${sy}) translate(0px, -100px)`;
}

