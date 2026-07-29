import type { AmbientProps } from '../contract';
import styles from './cthu.module.css';

// 克苏鲁 · 氛围层「深栖墨绿」:孢子上浮(持续)+ 静态扭曲层。
//
// ── 本层**不含**任何调度器 ─────────────────────────────────────────────
// 低频槽(文字异常 / 读数损坏)整个住在 `useAnomaly`,由正文形态组件持有 ——
// 因为异常要改的是**正文的字**,调度器住在正文那边才能「只碰自己组件的 DOM」。
// 孢子暂停由那边写主题根上的 `--cthu-spore-play`,本层的 CSS 只是消费它:
// 这正是 `contract.ts` 里 rootRef 那条口径(连续量走自定义属性,不跨组件 query DOM)。
//
// ── 动效预算 ─────────────────────────────────────────────────────────
//   持续 1 = 孢子上浮(孢子暂停是它自身的调制,不是第二个效果);
//   低频 1 = 文字异常 / 读数损坏,互斥共用(见 `useAnomaly.ts`);
//   用户触发 1 = 纸条按压 + 数值滚动。
// **新增即付账**(AGENTS.md §3):扭曲层相对样板间**降级为静态**(不再摆动),
// 低位持续滤镜感整个移除 —— 异常改为字符级单点事件。
//
// 本世界**没有入场序列**(`hasIntro: false`):异常是侵入,不该有起点 ——
// 「玩家意识到时它已经发生了」正是这个记忆点与钟鸣/灯闪的结构差别。

/** 孢子分布(16 粒:横位 % / 尺寸 px / 起始延迟 s / 时长 s / 透明度)。
 *  **写死不随机**:渲染期 `Math.random()` 不纯(重渲会让孢子瞬移,eslint 也拦)。 */
const SPORES = [
  { left: 6, size: 2.4, delay: 0, dur: 22, op: 0.28 },
  { left: 14, size: 1.6, delay: 7.5, dur: 30, op: 0.17 },
  { left: 21, size: 3.4, delay: 2.2, dur: 18, op: 0.4 },
  { left: 29, size: 1.9, delay: 12.1, dur: 27, op: 0.2 },
  { left: 36, size: 2.8, delay: 4.6, dur: 24, op: 0.32 },
  { left: 43, size: 1.5, delay: 15.4, dur: 33, op: 0.15 },
  { left: 49, size: 3.8, delay: 1.2, dur: 17, op: 0.44 },
  { left: 55, size: 2.1, delay: 9.8, dur: 29, op: 0.22 },
  { left: 62, size: 2.6, delay: 5.3, dur: 21, op: 0.3 },
  { left: 68, size: 1.7, delay: 13.7, dur: 31, op: 0.18 },
  { left: 74, size: 3.1, delay: 3.4, dur: 19, op: 0.36 },
  { left: 80, size: 2.0, delay: 11.2, dur: 26, op: 0.21 },
  { left: 85, size: 2.9, delay: 6.7, dur: 23, op: 0.33 },
  { left: 90, size: 1.6, delay: 14.9, dur: 34, op: 0.16 },
  { left: 94, size: 3.3, delay: 0.8, dur: 20, op: 0.38 },
  { left: 98, size: 2.2, delay: 8.6, dur: 28, op: 0.24 },
];

export function CthuAmbient({ onIntroDone, signatureTick }: AmbientProps) {
  // `hasIntro: false`,故通用层不等我们;仍显式回调一次,免得日后有人把 hasIntro 翻开
  // 却忘了这里没人放行(叙事永远不该被动画卡住)。
  onIntroDone();
  // **我盯轴,但盯的不是跨档事件**:克苏鲁按签名轴当前档的 severity 调异常频率
  // (走 `ProseProps.signatureSeverity`),向上跨档次数与本世界无关 —— 契约要求每套皮肤
  // 正面回答「我盯不盯轴」,这就是本世界的回答。
  void signatureTick;

  return (
    <div className={styles.ambient} aria-hidden="true">
      <div className={styles.deepFill} />
      <div className={styles.warp} />
      {SPORES.map((s, i) => (
        <span
          key={i}
          className={styles.spore}
          style={{
            left: `${s.left}%`,
            width: s.size,
            height: s.size,
            opacity: s.op,
            animationDelay: `${s.delay}s`,
            animationDuration: `${s.dur}s`,
          }}
        />
      ))}
      <div className={styles.vignette} />
    </div>
  );
}
