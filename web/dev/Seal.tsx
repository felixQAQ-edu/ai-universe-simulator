import kf from './keyframes.module.css';

// 焦印轮廓(③④ 用;dev-only)。
//
// 一方印烙进石面留下的**焦痕**,不是一个方框:
// - 四条边各自断续、粗细不均,**重量压在左上角**,右下角次之;另两角细到几乎断开;
// - 每条边是**按粗细节点画出的多边形**(不是描边),断口前一律收细到无;外缘带一点不规则起伏 —— 烧出来的边不直;
// - 每条边画四层(芯 / 中 / 两层晕,越外越淡),叠出焦褐的自然软边 —— 不用任何滤镜;
// - 两个重角各有一团焦褐向外洇开(径向渐变),**半径够不到印心**,印心保持底色;
// - 冷绿只有一小段,贴在上边**内缘**、靠近重角,不绕圈。
// 焦褐层与冷绿层分成两张 SVG:焦褐用 multiply 烙进下面的颜色,冷绿保持本色(否则会被压成墨绿)。
//
// 几何在 0–100 的 viewBox 里,印边大致落在 10–90;显示尺寸由 --kf-seal-size 决定。

/** 沿一条边的粗细节点:[位置 0–1, 宽度(viewBox 单位)];宽度 0 = 断口。相邻节点之间线性过渡,
 *  所以每段都是**收细到无**再断开 —— 没有齐头的短划(齐头短划一出现就像虚线框)。 */
type Knot = [t: number, w: number];

const SIDES: { from: [number, number]; to: [number, number]; out: [number, number]; knots: Knot[] }[] = [
  // 上边:左上重角起,往右收细,中右断开,末尾一截细线。
  { from: [10, 11], to: [90, 9], out: [0, -1], knots: [[-0.07, 0.5], [0, 6.5], [0.08, 6], [0.3, 3], [0.5, 2.16], [0.62, 1.08], [0.68, 0], [0.76, 0], [0.82, 1.49], [0.94, 1.76], [1, 0.4]] },
  // 左边:左上重角起,往下收细,下段断开,底部一截细线。
  { from: [10, 11], to: [11, 90], out: [-1, 0], knots: [[-0.07, 0.5], [0, 6.5], [0.1, 5.5], [0.32, 3.51], [0.52, 1.89], [0.6, 0.94], [0.66, 0], [0.75, 0], [0.82, 1.35], [1, 1.76]] },
  // 右边:上段细,中间断开,下段向右下角加重。
  { from: [90, 9], to: [89, 89], out: [1, 0], knots: [[0, 0.5], [0.25, 1.89], [0.42, 1.22], [0.48, 0], [0.58, 0], [0.64, 1.49], [0.84, 3.78], [1, 4.6], [1.06, 0.4]] },
  // 下边:近左下断开,中段细,向右下角加重。
  { from: [11, 90], to: [89, 89], out: [0, 1], knots: [[0, 1.35], [0.08, 0], [0.22, 0], [0.3, 1.49], [0.6, 2.56], [0.84, 3.2], [1, 5], [1.06, 0.4]] },
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
 * 层 = 宽度倍率 + 附加宽度:芯(1×)、中(1.7×)、晕(2.8×);三层叠起来就是一条**自然软边**,不靠模糊滤镜。
 */
function sidePaths(sideIndex: number, scale: number, extra: number): string[] {
  const { from, to, out, knots } = SIDES[sideIndex];
  const paths: string[] = [];
  let inner: string[] = [];
  let outer: string[] = [];
  const flush = () => {
    if (inner.length > 1) paths.push(`M${inner.join('L')}L${outer.reverse().join('L')}Z`);
    inner = [];
    outer = [];
  };
  // 节点可以略超出 0–1:重角处两条边各自越过角点一点再收尖,角上就不会留下齐头的切口。
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
    const j = jitter(sideIndex, i) * Math.min(1.2, base / 2.5);
    const wi = w * 0.32;
    const wo = w * 0.68 + j;
    inner.push(`${(x - out[0] * wi).toFixed(2)},${(y - out[1] * wi).toFixed(2)}`);
    outer.push(`${(x + out[0] * wo).toFixed(2)},${(y + out[1] * wo).toFixed(2)}`);
  }
  flush();
  return paths;
}

const LAYERS = [
  { key: 'h2', scale: 3.4, extra: 2.6, alpha: 'calc(var(--kf-burn-halo) * 0.5)' },
  { key: 'h1', scale: 2.4, extra: 1.6, alpha: 'var(--kf-burn-halo)' },
  { key: 'mid', scale: 1.6, extra: 0.7, alpha: 'var(--kf-burn-mid)' },
  { key: 'core', scale: 1, extra: 0, alpha: 'var(--kf-burn-core)' },
].map((l) => ({ ...l, paths: SIDES.flatMap((_, i) => sidePaths(i, l.scale, l.extra)) }));

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
          {/* 焦褐洇开:左上重(大)、右下次之(小);半径够不到印心(50,50)。 */}
          <ellipse cx="13" cy="13" rx="26" ry="22" fill="url(#kf-scorch)" />
          <ellipse cx="87" cy="87" rx="18" ry="16" fill="url(#kf-scorch)" />
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
