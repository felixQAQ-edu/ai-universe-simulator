// 四套碎解材质(融合入口,ADR-019 §3)。样板间冻结成果整体搬入。
//
// **成本模型**:四套材质覆盖六对组合,不为任何一对单独设计 ——
// 资产随世界数 N 增长、覆盖组合 O(N²),这正是 F-016 要的形状。
// 加一个新世界 = 加一套材质 + 一套物理,自动获得与所有既有世界的组合表现。
//
// 成败标准:碎解瞬间**一眼可分辨** —— 故四套在「轮廓 / 边缘 / 尺寸分布 / 运动倾向」
// 四个维度上刻意拉开,而不只是换颜色。本文件只产出碎片的**静态描述**,
// 「怎么碎」(过程)在 physics.ts —— 玩家看到的主要是过程,形态只是结果。
//
// 固定种子:每次渲染形态一致(同一个世界碎起来是同一副样子,不是每次随机)。

export type ShardKind = 'plate' | 'strip' | 'grit' | 'fiber' | 'wisp' | 'thread';

/** 碎解材质 id(四个基础世界各一套)。 */
export type MatId = 'rules' | 'waste' | 'cthulhu' | 'xian';

export interface Shard {
  kind: ShardKind;
  /** 卡面内百分比定位。 */
  x: number;
  y: number;
  w: number;
  h: number;
  rot: number;
  op: number;
  /** 末日:更暗的杂色颗粒。 */
  alt?: boolean;
  /** 克苏鲁纤维:单次分叉角度(上限一次,不成网)。 */
  branch?: number;
  /** 克苏鲁纤维:与另一条黏连时第二条的相对角度(只在两条之间)。 */
  pair?: number;
  /** 修仙:弧度档位(浅弧,1 最平 3 最弯)。 */
  arc?: 1 | 2 | 3;
}

/** 固定种子 PRNG(mulberry32):形态每次一致。 */
function rng(seed: number): () => number {
  let s = seed;
  return () => {
    s |= 0;
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const spread = (r: () => number, n: number, fn: (r: () => number, i: number) => Shard): Shard[] =>
  Array.from({ length: n }, (_, i) => fn(r, i));

/* ── 规则怪谈:硬边直角空心框 + 淡填充 + 中段切口截面 ──────────────
   直角、等宽、尺寸档位少(像被裁切而不是被打碎)—— 机械、规整、陈旧。 */
const rulesShards: Shard[] = (() => {
  const r = rng(1071);
  const plates = spread(r, 9, (r) => ({
    kind: 'plate' as const,
    x: 8 + r() * 84,
    y: 10 + r() * 80,
    w: [14, 20, 26, 34][Math.floor(r() * 4)],
    h: [8, 10, 14][Math.floor(r() * 3)],
    rot: (Math.floor(r() * 5) - 2) * 4, // 只取 ±8° 的档位,不连续
    op: 0.55 + r() * 0.35,
  }));
  const strips = spread(r, 7, (r) => ({
    kind: 'strip' as const,
    x: 4 + r() * 88,
    y: 8 + r() * 84,
    w: 26 + r() * 34,
    h: 2,
    rot: r() < 0.5 ? 0 : 90,
    op: 0.4 + r() * 0.4,
  }));
  return [...plates, ...strips];
})();

/* ── 末日生存:多角不规则块 + 微尘 ───────────────────────────────
   尺寸跨度极大(大块 + 大量微尘)—— 重、下坠、粗糙。 */
const wasteShards: Shard[] = (() => {
  const r = rng(4702);
  const chunks = spread(r, 7, (r) => ({
    kind: 'plate' as const,
    x: 10 + r() * 80,
    y: 12 + r() * 76,
    w: 12 + r() * 22,
    h: 9 + r() * 16,
    rot: r() * 360,
    op: 0.6 + r() * 0.35,
  }));
  const grit = spread(r, 26, (r) => ({
    kind: 'grit' as const,
    x: 4 + r() * 92,
    y: 6 + r() * 88,
    w: 1.5 + r() * 3,
    h: 1.5 + r() * 3,
    rot: r() * 360,
    op: 0.35 + r() * 0.5,
    alt: r() < 0.4,
  }));
  return [...chunks, ...grit];
})();

/* ── 克苏鲁:无形墨雾团为主 + 附着弯曲湿纤维 ─────────────────────
   无硬边、极端细长、方向趋同(像被水流拖拽)—— 湿、粘、飘。
   上限严格:分叉 ≤1 次、黏连仅两条、不成网 —— 一旦读成藻类或血管,「不可名状」就减分。 */
const cthulhuShards: Shard[] = (() => {
  const r = rng(3310);
  const mist = spread(r, 9, (r) => ({
    kind: 'wisp' as const,
    x: 8 + r() * 78,
    y: 10 + r() * 74,
    w: 26 + r() * 52,
    h: 14 + r() * 30,
    rot: -30 + r() * 30,
    op: 0.14 + r() * 0.24,
  }));
  const fibers = spread(r, 13, (r, i) => {
    const s: Shard = {
      kind: 'fiber',
      x: 6 + r() * 88,
      y: 8 + r() * 84,
      w: 26 + r() * 54,
      h: 2 + r() * 3,
      rot: -36 + r() * 30, // 方向趋同:被同一股水流拖着
      op: 0.28 + r() * 0.4,
    };
    if (i === 2 || i === 6 || i === 10) s.branch = 14 + r() * 12; // 仅一次分叉
    if (i === 4 || i === 9) s.pair = 8 + r() * 10; // 黏连只在两条之间
    return s;
  });
  return [...mist, ...fibers];
})();

/* ── 修仙:浅弧实心薄玉片 + 柔性光丝 + 云气 ──────────────────────
   薄而透、光丝是托举的介质而非主体、整体上浮 —— 轻、透、缓。
   刻意少:化散的东西数量少才显得轻;满屏小片会读成「碎屑」而非「化光」。 */
const xianShards: Shard[] = (() => {
  const r = rng(8815);
  const clouds = spread(r, 3, (r) => ({
    kind: 'wisp' as const,
    x: 10 + r() * 70,
    y: 16 + r() * 62,
    w: 44 + r() * 40,
    h: 14 + r() * 14,
    rot: -8 + r() * 16,
    op: 0.2 + r() * 0.16,
  }));
  const jade = spread(r, 5, (r, i) => ({
    kind: 'plate' as const,
    x: 14 + r() * 70,
    y: 14 + r() * 68,
    w: 22 + r() * 24, // 少而略大:看得清弧与收尖
    h: 7 + r() * 6,
    rot: -16 + r() * 32,
    op: 0.34 + r() * 0.26,
    arc: ((i % 3) + 1) as 1 | 2 | 3,
  }));
  const threads = spread(r, 5, (r) => ({
    kind: 'thread' as const,
    x: 8 + r() * 82,
    y: 10 + r() * 78,
    w: 26 + r() * 40,
    h: 2,
    rot: -34 + r() * 68,
    op: 0.22 + r() * 0.18, // 介质,不与玉片抢光
    arc: 2 as const,
  }));
  return [...clouds, ...jade, ...threads];
})();

export const SHARDS: Readonly<Record<MatId, readonly Shard[]>> = {
  rules: rulesShards,
  waste: wasteShards,
  cthulhu: cthulhuShards,
  xian: xianShards,
};

/**
 * 世界 → 碎解材质。**四套覆盖六对组合**(ADR-019 §2:否决 per-combo 特供)。
 * 未登记世界 → null:该侧不出碎片,揉合照常走(优雅降级,同 scene.ts 未配图的先例)。
 */
const MATERIAL_BY_ARCHETYPE: Readonly<Record<string, MatId>> = {
  rules_creepy: 'rules',
  apocalypse: 'waste',
  cthulhu: 'cthulhu',
  cultivation: 'xian',
};

export function materialOf(archetype: string): MatId | null {
  return MATERIAL_BY_ARCHETYPE[archetype] ?? null;
}
