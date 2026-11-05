// 融合标志的**登记数据类型**与通用几何(ADR-019 订正 §2 / §3;刀 2c-2)。
//
// 一份标志 = 纯数据:融合面配色、标志轮廓(各段笔画与烧出顺序)、冷色点缀段、揭晓边界方向。
// **怎么演只有一套**(FusionMerge 的标志路径,节拍见 plan.ts):交接 → 停顿 → 标志 → 揭晓 → 落位。
// 本文件与 EmblemOverlay / FusionMerge 一样是通用代码:**不认识任何组合 key**,只读传进来的数据。
// 某一对组合想要超出这套语法的专属动作 = 有意破例,须单独记 ADR(订正 §2「破例条款」)。
//
// 坐标约定:标志几何在 0–100 的方格里(印边大致落在 10–90),显示尺寸 = `mark.size` px。
// 渲染时 viewBox 四周各留 20(-20–120)、盒子同比放大到 1.4 倍:晕与越角的部分都在盒子里,
// 盒子上的 CSS mask 不会把它们裁掉(沿用 dev 稿 f056b75 的做法:烧出来用 HTML 外层的 CSS mask,
// 不用 SVG <mask> 里的 transform —— 后者每帧触发一次 SVG 布局)。

/** 二维点(标志方格坐标,0–100)。 */
export type Point = readonly [x: number, y: number];

/**
 * 沿一条笔画的粗细节点:[位置 0–1, 宽度(方格单位)];宽度 0 = 断口。相邻节点之间线性过渡,
 * 所以每段都**收细到无**再断开(没有齐头短划 —— 一出现就像虚线框)。
 * 节点可以略超出 0–1:重角处两条边各自越过角点一点再收尖。
 */
export type Knot = readonly [t: number, w: number];

/** 一个时间窗,以所在段(重角段 / 烧边段)的比例计:[起, 止],0–1。 */
export type Window = readonly [from: number, to: number];

/** 标志的一条笔画(一条边)。 */
export interface EmblemStroke {
  from: Point;
  to: Point;
  /** 外法线(单位向量):芯的外缘起伏与「往外」的那一侧。 */
  out: Point;
  knots: readonly Knot[];
  /**
   * 烧出遮罩带:贴在标志盒的哪一侧(带宽 = 盒的 {@link BURN_BAND}),前沿沿 `angle` 推进
   * (CSS linear-gradient 角度:90 = 从左往右,180 = 从上往下)。
   */
  band: { side: 'top' | 'left' | 'right' | 'bottom'; angle: number };
  /** 重角段里先烧出的比例(0 = 不参与重角段)。 */
  lead: number;
  /** 在烧边段里从 `lead` 烧到 1 的时间窗,与缓动(GSAP 缓动名)。 */
  burn: Window;
  ease: string;
}

/** 焦褐洇开的一团(径向渐变椭圆)。 */
export interface EmblemBlot {
  center: Point;
  radius: Point;
  /** 'corner' = 随重角段一起冒出;否则是烧边段里的时间窗。 */
  window: 'corner' | Window;
  ease: string;
}

/** 冷色点缀段(只一小段,不绕圈):最后透出、揭晓一开始就退。 */
export interface EmblemAccent {
  from: Point;
  to: Point;
  color: string;
  /** 线宽(方格单位)。 */
  width: number;
  /** 整体不透明度。 */
  alpha: number;
  /** 沿线的渐变:[位置 0–1, 不透明度]。 */
  stops: readonly (readonly [offset: number, opacity: number])[];
}

/** 一份融合标志登记。 */
export interface FusionEmblem {
  /** 融合面:宿主卡位上那块交界(两张原卡的颜色沿一条斜缝过渡到交界色)。 */
  surface: {
    /** 斜缝角度(CSS linear-gradient,deg)。 */
    angle: number;
    /** 渐变色标:[颜色, 位置 %],宿主一侧在前。 */
    stops: readonly (readonly [color: string, at: number])[];
    /** 交界带在渐变线上的位置(%):宿主色止、交界起、交界止、外来色起。 */
    seam: readonly [hostEnd: number, seamFrom: number, seamTo: number, foreignFrom: number];
    /** 交界带里的质地(CSS background 图层串;只显示在交界带内)。 */
    seamTexture: string;
    /** 两侧原卡各自留的质地(CSS background 图层串;只显示在交界带外)。 */
    sideTexture: string;
  };
  /** 标志本体:烙在融合面中心。 */
  mark: {
    /** 显示尺寸(px)。 */
    size: number;
    /** 转角(deg)。 */
    rotate: number;
    /** 笔画色与芯 / 晕的不透明度(晕叠六层,不抖动)。 */
    ink: { color: string; core: number; halo: number };
    /** 焦褐洇开的颜色与浓度。 */
    scorch: { color: string; alpha: number };
    /** 按烧出顺序排列(下标即 CSS 变量序号)。 */
    strokes: readonly EmblemStroke[];
    blots: readonly EmblemBlot[];
    accent: EmblemAccent;
  };
  /**
   * 揭晓边界:两个半平面取并集。主边界角度 `angle`,第二条再转 `kink` 度、落后 `lag`%,
   * 边界带一个折角但始终是一条连续的线;软边 `soft`%;位置从 `from`% 推到 `to`%。
   */
  reveal: { angle: number; kink: number; lag: number; soft: number; from: number; to: number };
}

/** 烧出遮罩带的宽度(标志盒的比例,恰好罩住一条边的芯与晕)。 */
export const BURN_BAND = 34;
/** 烧出前沿的软边(遮罩带长度的比例)。 */
export const BURN_SOFT = 12;
/** viewBox 四周的留白与盒子放大倍数(见文件头)。 */
export const MARK_PAD = 20;
export const MARK_BOX_SCALE = (100 + 2 * MARK_PAD) / 100;

// ── 笔画几何(纯函数;通用,不读组合)───────────────────────────────────────

const STEPS = 40;

function widthAt(knots: readonly Knot[], t: number): number {
  for (let i = 1; i < knots.length; i++) {
    const [t0, w0] = knots[i - 1];
    const [t1, w1] = knots[i];
    if (t <= t1) return w0 + ((w1 - w0) * (t - t0)) / (t1 - t0 || 1);
  }
  return knots[knots.length - 1][1];
}

/** 外缘起伏:固定的伪随机(同一输入永远同一形状)。只抖外缘 —— 印压下去那一面直,烧开那一面不直。 */
const jitter = (stroke: number, i: number) =>
  Math.sin(stroke * 12.9 + i * 0.55) * 0.5 + Math.sin(i * 0.23 + stroke * 3.1) * 0.4;

/**
 * 一条笔画在某一「层」上的多边形(每段连续非零的部分一个)。
 * 层 = 宽度倍率 + 附加宽度;rough 只给芯(外缘起伏),晕一律不抖。
 */
export function strokePaths(stroke: EmblemStroke, index: number, scale: number, extra: number, rough: boolean): string[] {
  const { from, to, out, knots } = stroke;
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
    const j = rough ? jitter(index, i) * Math.min(1.2, base / 2.5) : 0;
    const wi = w * 0.32;
    const wo = w * 0.68 + j;
    inner.push(`${(x - out[0] * wi).toFixed(2)},${(y - out[1] * wi).toFixed(2)}`);
    outer.push(`${(x + out[0] * wo).toFixed(2)},${(y + out[1] * wo).toFixed(2)}`);
  }
  flush();
  return paths;
}

/** 芯(带外缘起伏)+ 六层同心晕(不抖、极淡、逐层加宽):叠出来是连续的渐淡,看不出层。 */
export const INK_LAYERS = [
  ...[3.8, 3.3, 2.8, 2.3, 1.9, 1.5].map((scale, i) => ({ key: `h${i}`, scale, extra: 0.4 + (5 - i) * 0.4, core: false })),
  { key: 'core', scale: 1, extra: 0, core: true },
] as const;

// ── CSS 串(纯函数;通用)──────────────────────────────────────────────────

/** 笔画 i 的烧出进度变量(0–1)。 */
export const strokeVar = (i: number) => `--em-s${i}`;
/** 焦褐团 i 的显现变量(0–1)。 */
export const blotVar = (i: number) => `--em-b${i}`;

const BAND_POS = { top: 'top', left: 'left', right: 'right', bottom: 'bottom' } as const;
const bandSize = (side: EmblemStroke['band']['side']) =>
  side === 'top' || side === 'bottom' ? `100% ${BURN_BAND}%` : `${BURN_BAND}% 100%`;

/**
 * 焦褐层的 CSS mask:每条笔画一条遮罩带,带里一道沿笔画推进的软前沿(位置 = 该笔画进度变量,
 * 缺省 1 = 整条都在);中间再铺一块常亮(重角洇进来的尾巴落在这里,由焦褐团自己的不透明度管)。
 * 只重绘、不布局。
 */
export function burnMask(strokes: readonly EmblemStroke[]): { image: string; position: string; size: string } {
  const front = (i: number) => `calc(var(${strokeVar(i)}, 1) * ${100 + BURN_SOFT}% - ${BURN_SOFT}%)`;
  const image = strokes.map(
    (s, i) => `linear-gradient(${s.band.angle}deg, #000 ${front(i)}, transparent calc(${front(i)} + ${BURN_SOFT}%))`,
  );
  return {
    image: [...image, 'linear-gradient(#000, #000)'].join(', '),
    position: [...strokes.map((s) => BAND_POS[s.band.side]), 'center'].join(', '),
    size: [...strokes.map((s) => bandSize(s.band.side)), `${BURN_BAND}% ${BURN_BAND}%`].join(', '),
  };
}

/** 融合面底色(斜缝渐变)。 */
export function surfaceBackground(surface: FusionEmblem['surface']): string {
  return `linear-gradient(${surface.angle}deg, ${surface.stops.map(([c, at]) => `${c} ${at}%`).join(', ')})`;
}

/**
 * 揭晓边界的 CSS mask(作用在**真实的成品卡**上;位置变量 `--em-front`,收尾整片补满 `--em-full`)。
 * 两个半平面取并集(mask 默认 add)。
 */
export function revealMask(reveal: FusionEmblem['reveal']): string {
  const { angle, kink, lag, soft } = reveal;
  return [
    `linear-gradient(${angle}deg, #000 calc(var(--em-front) - ${soft}%), transparent var(--em-front))`,
    `linear-gradient(${angle - kink}deg, #000 calc(var(--em-front) - ${lag + soft}%), transparent calc(var(--em-front) - ${lag}%))`,
    'linear-gradient(rgba(0, 0, 0, var(--em-full)), rgba(0, 0, 0, var(--em-full)))',
  ].join(', ');
}
