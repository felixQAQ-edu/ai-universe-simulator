import gsap from 'gsap';
import type { MatId } from './shards';

// 四套碎解物理(融合入口,ADR-019 §3)——**这是本轮最重要的移植内容**。
//
// **四套不共用运动曲线。** 共用曲线在代码上完全看不出问题,但玩家看到的主要是**过程**,
// 形态只是结果 —— 碎法一样,材质差异就只剩「碎片长得不同」。
// (与 ADR-018 §4.12「搬元素要连关系一起搬」是同一条教训的正面应用:
//  末日与修仙的 ease 恰好都是 power2.out,直接复用就会让两个世界的数值滚动是同一条曲线。)
//
//   规则   = 被裁切:沿直线切断,断口整齐,档位化位移、steps 阶跃、**零旋转**
//   末日   = 崩落:自重压垮,**一律向下**、加速下坠,大块先塌、微尘后扬
//   克苏鲁 = 被拖走:同一股暗流把碎片沿同向抽走,**黏连者滞后牵动**
//   修仙   = 化散:**光丝先起**(托举介质)、玉片后散(主体),越靠近核越淡

/** 五拍时长(ms)。定案 B 合计 1000ms;**停顿一拍不可省 —— 那是新世界诞生前的重量**。 */
export interface BeatMs {
  squeeze: number;
  shatter: number;
  knead: number;
  hold: number;
  unfold: number;
}

export const BEATS: BeatMs = { squeeze: 200, shatter: 180, knead: 260, hold: 140, unfold: 220 };

export const TOTAL_MS = BEATS.squeeze + BEATS.shatter + BEATS.knead + BEATS.hold + BEATS.unfold;

/** 一枚碎片在动画中的运行时数据(位移按真实 DOM 量出,不估算)。 */
export interface Runtime {
  el: HTMLElement;
  kind: string;
  /** 指向世界核的位移(px)。 */
  dx: number;
  dy: number;
  /** 该碎片在本材质内的序号,用于成组延迟。 */
  i: number;
  n: number;
}

/** 档位化(规则怪谈专用:位移不取连续值)。 */
const q = (v: number, step: number) => Math.round(v / step) * step;

/** 碎解拍:各材质离开卡面的方式。 */
export function shatter(
  tl: gsap.core.Timeline,
  id: MatId,
  rs: readonly Runtime[],
  b: BeatMs,
  at: number,
): void {
  const d = b.shatter / 1000;
  rs.forEach((r) => {
    const { el, i, n, kind } = r;
    const t = i / Math.max(1, n - 1);
    if (id === 'rules') {
      /* 被裁切:切口张开一点点就停住 —— 原地停顿感,零旋转 */
      tl.fromTo(
        el,
        { opacity: 0, x: 0, y: 0 },
        {
          opacity: 1,
          x: q(gsap.utils.random(-14, 14), 7), // 位移取档位,不连续
          y: q(gsap.utils.random(-10, 10), 5),
          duration: d,
          ease: 'steps(2)', // 阶跃:机械
        },
        at + t * d * 0.25,
      );
    } else if (id === 'waste') {
      /* 崩落:大块先塌、微尘后扬,全部带重力 */
      const heavy = kind === 'plate';
      tl.fromTo(
        el,
        { opacity: 0, x: 0, y: 0 },
        {
          opacity: 1,
          x: gsap.utils.random(-16, 16),
          y: gsap.utils.random(10, 30) * (heavy ? 1.25 : 0.8), // 一律向下
          rotate: `+=${gsap.utils.random(-70, 70)}`,
          duration: d * (heavy ? 0.85 : 1.15),
          ease: 'power2.in', // 加速下坠
        },
        at + (heavy ? 0 : d * 0.3),
      );
    } else if (id === 'cthulhu') {
      /* 被拖走:所有碎片沿同一方向(-28°)被抽走,黏连的两条跟随更慢 */
      const drag = el.hasAttribute('data-pair') ? 0.62 : 1;
      const ang = (-28 * Math.PI) / 180;
      const len = gsap.utils.random(18, 34) * drag;
      tl.fromTo(
        el,
        { opacity: 0, x: 0, y: 0 },
        {
          opacity: 1,
          x: Math.cos(ang) * len,
          y: Math.sin(ang) * len,
          duration: d * (1 + (1 - drag) * 0.5),
          ease: 'sine.out', // 被流体带走,没有硬起停
        },
        at + t * d * 0.4,
      );
    } else {
      /* 化散:光丝先起(托举),玉片后散(主体) */
      const isThread = kind === 'thread';
      tl.fromTo(
        el,
        { opacity: 0, x: 0, y: 0, scale: 1 },
        {
          opacity: isThread ? 0.42 : 1, // 光丝为介质,不与玉片抢
          x: gsap.utils.random(-12, 12),
          y: gsap.utils.random(-22, -8) * (isThread ? 1.15 : 0.8), // 上浮
          duration: d * 1.1,
          ease: 'power1.out', // 慢、无冲击
        },
        at + (isThread ? 0 : d * 0.35), // 光丝先,玉片后
      );
      if (kind === 'plate') {
        tl.to(el, { filter: 'brightness(1.08)', duration: d * 0.5 }, at + d * 0.35);
      }
    }
  });
}

/** 揉合拍:两种碎片围绕很小的中心互噬一次,压缩成世界核。`spin` ±1 = 两侧反向。 */
export function knead(
  tl: gsap.core.Timeline,
  id: MatId,
  rs: readonly Runtime[],
  b: BeatMs,
  at: number,
  spin: number,
): void {
  const d = b.knead / 1000;
  rs.forEach((r) => {
    const { el, dx, dy, i, n, kind } = r;
    const t = i / Math.max(1, n - 1);
    const common = { x: dx, y: dy, duration: d };
    if (id === 'rules') {
      /* 档位化收拢:分段阶跃到位,不做弧线 */
      tl.to(el, { ...common, rotate: 0, scale: 0.5, ease: 'steps(4)', opacity: 0.9 }, at + t * d * 0.12);
    } else if (id === 'waste') {
      /* 有重量:先被甩低,再被拽入核心 */
      tl.to(
        el,
        {
          ...common,
          rotate: `+=${spin * gsap.utils.random(60, 150)}`,
          scale: kind === 'plate' ? 0.45 : 0.6,
          ease: 'power3.in',
          opacity: 0.9,
        },
        at + t * d * 0.18,
      );
    } else if (id === 'cthulhu') {
      /* 被拖走:走弧线切入(先顺流,再被卷入),黏连者滞后牵动 */
      const lag = el.hasAttribute('data-pair') ? d * 0.22 : 0;
      tl.to(
        el,
        {
          ...common,
          duration: d + lag,
          rotate: `+=${spin * gsap.utils.random(20, 60)}`,
          scale: 0.55,
          ease: 'sine.inOut',
          opacity: 0.75,
        },
        at + t * d * 0.2,
      );
    } else {
      /* 化散:不是被吸进去,是散着散着才聚拢;越靠近越淡 */
      const isThread = kind === 'thread';
      tl.to(
        el,
        {
          ...common,
          scale: isThread ? 0.35 : 0.5,
          opacity: isThread ? 0.25 : 0.55, // 光丝先淡出,玉片留到最后
          ease: 'power1.inOut',
          duration: d * (isThread ? 0.82 : 1),
        },
        at + t * d * 0.16,
      );
    }
  });
}
