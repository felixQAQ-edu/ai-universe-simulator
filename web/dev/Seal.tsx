import kf from './keyframes.module.css';

// 焦印轮廓(③④ 用;dev-only)。
//
// 一方印烙进石面留下的**焦痕**,读起来是「一条被烧坏的方印轮廓」,不是方框、不是角标:
// - **只有一个重角**(左上):角上是一团烧透的焦褐,两臂很快收细 —— 两臂都长而粗就成了取景框角标;
// - **右边中部一处烧蚀**:那一段鼓起、中间烧穿一个小口,焦褐向石面洇开;
// - 其余边细而连续,只有两处收细到无的断口;右下那一角落在深青里,焦褐几乎被吞掉(本来就是)。
// - 每条边是按粗细节点画出的多边形(不是描边),断口前一律收细到无;只有芯的外缘有一点起伏;
// - 软边 = 芯外面几层**不抖动、极淡**的同心晕叠出来的渐淡,不用任何滤镜
//   (上一轮每层都抖、层数少,几层错开的边读成了折纸);
// - 焦褐洇开(径向渐变)只在重角和烧蚀处,半径够不到印心,印心保持底色;
// - 冷绿只有一小段,贴在上边内缘、靠重角,不绕圈。
// 焦褐层与冷绿层分成两张 SVG:焦褐用 multiply 烙进下面的颜色,冷绿保持本色(否则会被压成墨绿)。
//
// 几何在 0–100 的 viewBox 里,印边大致落在 10–90;显示尺寸由 --kf-seal-size 决定。

/** 沿一条边的粗细节点:[位置 0–1, 宽度(viewBox 单位)];宽度 0 = 断口。相邻节点之间线性过渡,
 *  所以每段都是**收细到无**再断开 —— 没有齐头的短划(齐头短划一出现就像虚线框)。
 *  节点可以略超出 0–1:重角处两条边各自越过角点一点再收尖,角上不留齐头切口。 */
type Knot = [t: number, w: number];

const SIDES: { from: [number, number]; to: [number, number]; out: [number, number]; knots: Knot[] }[] = [
  // 上边:重角起,很快收细,近右上一处断口。
  { from: [10, 11], to: [90, 9], out: [0, -1], knots: [[-0.06, 0.5], [0, 5.2], [0.08, 3.4], [0.22, 1.9], [0.5, 1.5], [0.76, 1.4], [0.81, 0], [0.87, 0], [0.92, 1.3], [1, 1.2]] },
  // 左边:重角起,很快收细,下段一处断口。
  { from: [10, 11], to: [11, 90], out: [-1, 0], knots: [[-0.06, 0.5], [0, 5.2], [0.09, 3.2], [0.24, 1.8], [0.58, 1.4], [0.64, 0], [0.71, 0], [0.77, 1.3], [1, 1.3]] },
  // 右边:细而连续,中部一处烧蚀 —— 鼓起、中间烧穿。
  { from: [90, 9], to: [89, 89], out: [1, 0], knots: [[-0.02, 0.4], [0, 1.3], [0.3, 1.4], [0.4, 3.2], [0.46, 4.4], [0.49, 0], [0.54, 0], [0.57, 3.8], [0.64, 2.4], [0.74, 1.4], [1, 1.3], [1.02, 0.4]] },
  // 下边:细而连续,往右下角渐细(那一角在深青里)。
  { from: [11, 90], to: [89, 89], out: [0, 1], knots: [[-0.02, 0.4], [0, 1.3], [0.55, 1.4], [1, 1.1], [1.02, 0.4]] },
];

const STEPS = 40;

function widthAt(knots: Knot[], t: number) {
  for (let i = 1; i < knots.length; i++) {
    const [t0, w0] = knots[i - 1];
    const [t1, w1] = knots[i];
    if (t <= t1) return w0 + ((w1 - w0) * (t - t0)) / (t1 - t0 || 1);
  }
  return knots[knots.length - 1][1];
}

/** 外缘起伏:固定的伪随机(同一输入永远同一形状)。只抖外缘 —— 印压下去那一面直,烧开那一面不直。 */
const jitter = (side: number, i: number) => Math.sin(side * 12.9 + i * 0.55) * 0.5 + Math.sin(i * 0.23 + side * 3.1) * 0.4;

/**
 * 一条边在某一「层」上的多边形(每段连续非零的部分一个)。
 * 层 = 宽度倍率 + 附加宽度;rough 只给芯(外缘起伏),晕一律不抖。
 */
function sidePaths(sideIndex: number, scale: number, extra: number, rough: boolean): string[] {
  const { from, to, out, knots } = SIDES[sideIndex];
  const paths: string[] = [];
  let inner: string[] = [];
  let outer: string[] = [];
  const flush = () => {
    if (inner.length > 1) paths.push(`M${inner.join('L')}L${outer.reverse().join('L')}Z`);
    inner = [];
    outer = [];
  };
  const t0 = knots[0][0];
  const t1 = knots[knots.length - 1][0];
  for (let i = 0; i <= STEPS; i++) {
    const t = t0 + ((t1 - t0) * i) / STEPS;
    const base = widthAt(knots, t);
    if (base <= 0.05) {
      flush();
      continue;
    }
    const w = base * scale + extra * Math.min(1, base);
    const x = from[0] + (to[0] - from[0]) * t;
    const y = from[1] + (to[1] - from[1]) * t;
    const j = rough ? jitter(sideIndex, i) * Math.min(1.2, base / 2.5) : 0;
    const wi = w * 0.32;
    const wo = w * 0.68 + j;
    inner.push(`${(x - out[0] * wi).toFixed(2)},${(y - out[1] * wi).toFixed(2)}`);
    outer.push(`${(x + out[0] * wo).toFixed(2)},${(y + out[1] * wo).toFixed(2)}`);
  }
  flush();
  return paths;
}

/** 芯(带外缘起伏)+ 六层同心晕(不抖、极淡、逐层加宽):叠出来是连续的渐淡,看不出层。 */
const LAYERS = [
  ...[3.8, 3.3, 2.8, 2.3, 1.9, 1.5].map((scale, i) => ({
    key: `h${i}`,
    scale,
    extra: 0.4 + (5 - i) * 0.4,
    rough: false,
    alpha: 'var(--kf-burn-halo)',
  })),
  { key: 'core', scale: 1, extra: 0, rough: true, alpha: 'var(--kf-burn-core)' },
].map((l) => ({ ...l, paths: SIDES.flatMap((_, i) => sidePaths(i, l.scale, l.extra, l.rough)) }));

export function Seal({ fading = false }: { fading?: boolean }) {
  const cls = (layer: string) => [kf.seal, layer, fading ? kf.sealFading : ''].join(' ');
  return (
    <>
      <div className={cls(kf.sealBurn)} data-kf="seal" aria-hidden="true">
        <svg viewBox="0 0 100 100" overflow="visible">
          <defs>
            <radialGradient id="kf-scorch">
              <stop offset="0" style={{ stopColor: 'var(--kf-scorch)', stopOpacity: 'var(--kf-scorch-core)' }} />
              <stop offset="0.55" style={{ stopColor: 'var(--kf-scorch)', stopOpacity: 'calc(var(--kf-scorch-core) * 0.4)' }} />
              <stop offset="1" style={{ stopColor: 'var(--kf-scorch)', stopOpacity: 0 }} />
            </radialGradient>
          </defs>
          {/* 焦褐洇开:重角一团(大)、右边烧蚀处一团(偏外);半径都够不到印心(50,50)。 */}
          <ellipse cx="11" cy="12" rx="28" ry="25" fill="url(#kf-scorch)" />
          <ellipse cx="94" cy="51" rx="17" ry="22" fill="url(#kf-scorch)" />
          {LAYERS.map((l) =>
            l.paths.map((d, i) => <path key={`${l.key}${i}`} d={d} style={{ fill: 'var(--kf-burn)', fillOpacity: l.alpha }} />),
          )}
        </svg>
      </div>
      <div className={cls(kf.sealGreen)} aria-hidden="true">
        <svg viewBox="0 0 100 100" overflow="visible">
          <defs>
            <linearGradient id="kf-green" x1="0" x2="1">
              <stop offset="0" style={{ stopColor: 'var(--kf-green)', stopOpacity: 0 }} />
              <stop offset="0.3" style={{ stopColor: 'var(--kf-green)', stopOpacity: 1 }} />
              <stop offset="1" style={{ stopColor: 'var(--kf-green)', stopOpacity: 0 }} />
            </linearGradient>
          </defs>
          {/* 冷绿:上边内缘、靠左上重角的一小段(约 1/4 边长),不绕圈。 */}
          <path d="M17,15.6 L42,14.9" stroke="url(#kf-green)" strokeLinecap="round" fill="none" style={{ strokeWidth: 'var(--kf-green-w)' }} />
        </svg>
      </div>
    </>
  );
}
