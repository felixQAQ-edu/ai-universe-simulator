import type { FusionEmblem } from '../../fusion/emblem';

// 《验讫》(修仙 × 克苏鲁,host = 修仙)的标志登记:**石化交界上烧出来的焦印**(ADR-019 订正 §3)。
// 第一份登记。纯数据,怎么演由 FusionMerge 的唯一一套标志时间线决定。
// 画面与数值从 dev 稿移植:`claude/brave-noether-ebefe4` @ `fc1c4f8` 的 Seal.tsx / keyframes.module.css /
// YanqiMotion.tsx(那个分支不合并,只移植数据)。
//
// - 印里**无字**;**一个重角**(左上),两臂很快收细;
// - **右边中部一处烧蚀**(鼓起、中间烧穿);其余边细而连续,两处收细到无的断口;
// - **冷绿只在内缘一小段**(贴上边内缘、靠重角,不绕圈);
// - 62px,转 −4°,落在融合面中心;融合面是一条 168° 斜缝:上浅(修仙)→ 石色 → 下深青(克苏鲁)。

export const YANQI_EMBLEM: FusionEmblem = {
  surface: {
    // 168° 是按卡的宽高比调出来的(448×93):离 180° 越远越接近左右分区;168° 时横纵两向对斜缝的贡献大致相等。
    angle: 168,
    stops: [
      ['#f6f8f6', 0], // 修仙底色(取自 .cardCultivation)
      ['#e6ede8', 29],
      ['#b0ad9f', 41], // 交界处的石色
      ['#8a887d', 59],
      ['#122a2a', 71], // 克苏鲁底色(取自 .cardCthulhu)
      ['#07121a', 100],
    ],
    seam: [29, 41, 59, 71],
    // 石化带的质地:几团大尺度的明 / 暗斑(是石头的色斑,不是颗粒)。
    seamTexture: [
      'radial-gradient(28% 70% at 38% 44%, rgba(255, 255, 250, 0.16), transparent 70%)',
      'radial-gradient(24% 60% at 62% 40%, rgba(40, 40, 34, 0.14), transparent 70%)',
      'radial-gradient(20% 55% at 28% 76%, rgba(60, 58, 50, 0.12), transparent 70%)',
    ].join(', '),
    // 修仙一侧白云气(左上、上沿),克苏鲁一侧幽绿暗光 + 深渊暗角(右下、下沿)。
    sideTexture: [
      'radial-gradient(36% 80% at 12% 24%, rgba(255, 255, 255, 0.85), transparent 70%)',
      'radial-gradient(30% 60% at 46% 8%, rgba(255, 255, 255, 0.6), transparent 72%)',
      'radial-gradient(26% 80% at 80% 84%, rgba(90, 175, 140, 0.2), transparent 68%)',
      'radial-gradient(60% 120% at 92% 104%, rgba(0, 8, 10, 0.5), transparent 70%)',
    ].join(', '),
  },
  mark: {
    size: 62,
    rotate: -4,
    ink: { color: 'rgb(58, 34, 20)', core: 0.8, halo: 0.09 },
    scorch: { color: 'rgb(104, 64, 32)', alpha: 0.6 },
    // 烧出顺序:上 / 左先(重角起,先快后慢),右 / 下接着(右边从上往下,经过中部时烧蚀那团洇开)。
    strokes: [
      {
        // 上边:重角起,很快收细,近右上一处断口。
        from: [10, 11],
        to: [90, 9],
        out: [0, -1],
        knots: [[-0.06, 0.5], [0, 5.2], [0.08, 3.4], [0.22, 1.9], [0.5, 1.5], [0.76, 1.4], [0.81, 0], [0.87, 0], [0.92, 1.3], [1, 1.2]],
        band: { side: 'top', angle: 90 },
        lead: 0.3,
        burn: [0, 0.62],
        ease: 'power1.out',
      },
      {
        // 左边:重角起,很快收细,下段一处断口。
        from: [10, 11],
        to: [11, 90],
        out: [-1, 0],
        knots: [[-0.06, 0.5], [0, 5.2], [0.09, 3.2], [0.24, 1.8], [0.58, 1.4], [0.64, 0], [0.71, 0], [0.77, 1.3], [1, 1.3]],
        band: { side: 'left', angle: 180 },
        lead: 0.3,
        burn: [0.05, 0.67],
        ease: 'power1.out',
      },
      {
        // 右边:细而连续,中部一处烧蚀 —— 鼓起、中间烧穿。
        from: [90, 9],
        to: [89, 89],
        out: [1, 0],
        knots: [[-0.02, 0.4], [0, 1.3], [0.3, 1.4], [0.4, 3.2], [0.46, 4.4], [0.49, 0], [0.54, 0], [0.57, 3.8], [0.64, 2.4], [0.74, 1.4], [1, 1.3], [1.02, 0.4]],
        band: { side: 'right', angle: 180 },
        lead: 0,
        burn: [0.45, 0.95],
        ease: 'power1.inOut',
      },
      {
        // 下边:细而连续,往右下角渐细(那一角在深青里)。
        from: [11, 90],
        to: [89, 89],
        out: [0, 1],
        knots: [[-0.02, 0.4], [0, 1.3], [0.55, 1.4], [1, 1.1], [1.02, 0.4]],
        band: { side: 'bottom', angle: 90 },
        lead: 0,
        burn: [0.5, 1],
        ease: 'power1.inOut',
      },
    ],
    // 焦褐洇开:重角一团(大)、右边烧蚀处一团(偏外);半径都够不到印心(50,50)。
    blots: [
      { center: [11, 12], radius: [28, 25], window: 'corner', ease: 'sine.out' },
      { center: [94, 51], radius: [17, 22], window: [0.62, 0.92], ease: 'sine.out' },
    ],
    // 冷绿:上边内缘、靠左上重角的一小段(约 1/4 边长);62px 印上线宽约 1.5px。
    accent: {
      from: [17, 15.6],
      to: [42, 14.9],
      color: 'rgb(126, 196, 166)',
      width: 2.4,
      alpha: 0.8,
      stops: [
        [0, 0],
        [0.3, 1],
        [1, 0],
      ],
    },
  },
  // 主边界 348°(平行于斜缝,从下往上推),第二条再转 16°、落后 9%;软边 10%;从焦印下沿之下(22%)推到 118%。
  reveal: { angle: 348, kink: 16, lag: 9, soft: 10, from: 22, to: 118 },
};
