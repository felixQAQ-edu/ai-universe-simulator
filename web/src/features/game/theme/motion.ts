import gsap from 'gsap';

// 四世界时间感常量(样板间冻结成果整体搬入,ADR-018 §4.3)。
// 同样 600ms,四个世界走四条不同的曲线 —— **`--t-dur` 与 `--t-ease` 必须成对**:
// 只换时长不换缓动 = 四世界只是快慢不同,不是时间感不同,那不叫「每个世界有自己的视觉语言」。
//
// JS 侧(GSAP)从本文件取值;CSS 侧对应的 `--t-dur` / `--t-ease` 定义在各主题根 class 上
// (含 `prefers-reduced-motion` 覆盖值)。两侧必须同源同口径。
//
// 刀 1 只有 RULES 被消费;XIAN / CTHU / WASTE 随刀 2–4 各自接线时启用
// (整体搬入而非逐刀补,避免四套时间感被拆成四处各写一遍)。

/** 修仙:慢、从容、余韵长(缓出为主,尾巴拖得久)。 */
export const XIAN = {
  ease: 'power2.out',
  easeLong: 'sine.inOut', // 长余韵回落
  dur: (base: number) => base * 1.35,
};

/** 规则怪谈:准时、机械、戛然而止(线性/阶跃,结束干脆)。 */
export const RULES = {
  ease: 'none',
  easeSnap: 'steps(1)',
  dur: (base: number) => base, // 精确,不加不减
};

/** 克苏鲁:节奏不可信(同一动作时长有微小随机偏移,偶尔迟到)。 */
export const CTHU = {
  ease: 'sine.inOut',
  dur: (base: number) => base * gsap.utils.random(0.82, 1.22),
  delay: () => (Math.random() < 0.25 ? gsap.utils.random(0.05, 0.3) : 0),
};

/** 末日:迟钝、磨损(启动有迟滞,过程可能轻微卡顿)。 */
export const WASTE = {
  ease: 'power2.out',
  easeStart: 'power1.in',
  /**
   * **数值滚动专用**(刀 3 补):样板间的表盘指针跑的是**两段补间 + 启动 lag**
   * (`delay: lag()` → `easeStart` 走前 62% → `ease` 收尾),而通用层的数值滚动
   * (`useAnimatedValues`)刻意只吃**一个** ease —— 同步逻辑单点、不给主题层自己滚数字的口子。
   * 于是这里给一条**单 ease 的等效近似**:`inOut` 的慢起步替代 lag + `power1.in`,
   * 慢收尾替代 `power2.out` 收束。**若直接复用 `ease`,末日与修仙的数值滚动会是同一条曲线**
   * (两者的 `ease` 都是 `power2.out`),四世界就少了一条时间感。
   */
  easeRoll: 'power2.inOut',
  dur: (base: number) => base * gsap.utils.random(1.0, 1.15),
  lag: () => gsap.utils.random(0.1, 0.22), // 启动迟滞
};

/**
 * 偏好减弱动效闸(AGENTS.md Motion Constraints §5):**所有 JS 驱动的效果入口先过这道闸**
 * (CSS 侧走 `@media (prefers-reduced-motion: reduce)`)。
 * jsdom 不实现 matchMedia —— 测试环境由 `src/test/setup.ts` 补 polyfill(同 localStorage 先例);
 * 运行时缺失一律按「不减弱」处理之外再兜一层 try,绝不因此抛错阻断渲染。
 */
export function reducedMotion(): boolean {
  try {
    return (
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    );
  } catch {
    return false;
  }
}
