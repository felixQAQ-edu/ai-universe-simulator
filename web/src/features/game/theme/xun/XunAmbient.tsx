import styles from './xun.module.css';

// 《寻常》· 氛围层:只有一块底色(ADR-020「《寻常》局内皮肤」一节决策 4)。
//
// 桌面端 `.screen` 只有 640px 宽,两侧也该是这个世界的底色(同 PaidanAmbient / QiriAmbient)。
// **没有任何动画、不驱动任何记忆点**:本世界的一级记忆点「留下的那一年」在数值形态里(`XunStats`,
// 信号与作用点都在数值块,见该节已知代价 3)。`hasIntro: false`,所以也不调 `onIntroDone`。
export function XunAmbient() {
  return <div className={styles.ambient} aria-hidden="true" />;
}
