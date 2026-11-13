import { useLayoutEffect, useRef } from 'react';
import { severityWord, type AxisView, type StatsProps } from '../contract';
import { reducedMotion } from '../motion';
import { LOWER_ON_DY, UPPER_ON_DY, UPPER_TOP, lowerTop, rodsFor } from './abacus';
import styles from './qiri.module.css';

// 《七日开张》· 数值形态「九档算盘」(ADR-038 Slice G-2 决策 2)。
//
//   面板头   左 = 时辰牌(timeLabel;没有这个字段时不渲染该节点),右 = 灯笼
//   轴名     按 axes 顺序,直接用 displayName
//   算盘     每根轴一组,每组三档(百 / 十 / 个),每档上一下四
//   读数     数字 / 档名 / 状态词
//
// **主题层不认轴**(ADR-018 §4.2):不读 key 判断哪根是摊本,颜色只读 `axis.severity`。
// **单一驱动量**(§4.2.1):珠子只从 `axis.value` 派生 —— 它是通用层插值中的显示值(已取整),
// 数字滚到几,珠子就摆成几;本组件不另起驱动量,也不等滚完再落珠。
//
// ── 灯笼(一级记忆点)的样子在这里,驱动不在这里 ───────────────────────────
// 这里只画一个静态的线稿灯笼。「亮」由 `QiriAmbient` 在主题根上挂 `lanternOn` class,
// `qiri.module.css` 里 `.lanternOn .lanternBody` 响应(Felix 2026-11-13 审定 1:接法乙)。
// 本组件不收跨档信号、不读熟客的数值。
//
// ── 珠子的手感(Felix 定,照 docs/qiri-g2-samples/qiri-abacus-motion.html 的 moveBead)──
// 一颗珠子走 160ms:0.55 处到位、0.78 处反向弹回 1.5px、1 处归位。动的时候目标又变了,从当前位置接着走。
// 用浏览器自带的 Web Animations,只动 transform;减弱动效或环境没有 `animate`(jsdom)→ 直接落位。
// 用户触发槽:与数值滚动同一个驱动量,算一个(AGENTS.md Motion Constraints §2)。
export const BEAD_MS = 160;
export const BEAD_BACK_PX = 1.5;

export function QiriStats({ axes, timeLabel }: StatsProps) {
  return (
    <div className={styles.ledgerPanel}>
      <div className={styles.panelHead}>
        {/* 没有标签时这个节点不渲染;占位的空 span 只为把灯笼留在右侧。 */}
        {timeLabel ? <span className={styles.hourTag}>{timeLabel}</span> : <span />}
        <span className={styles.lantern} aria-hidden="true" data-lantern="">
          <svg viewBox="0 0 15 24" fill="none" stroke="currentColor" strokeWidth="1">
            <path d="M7.5 0v3M4 3.5h7M7.5 20.5V24M5 20.5h5" />
            <path className={styles.lanternBody} d="M4 5.5h7l3 3.5v7l-3 3.5H4L1 16V9z" />
            <path d="M7.5 5.5v14" />
          </svg>
        </span>
      </div>
      <div className={styles.names} style={columns(axes.length)}>
        {axes.map((axis) => (
          <span key={axis.key}>{axis.displayName}</span>
        ))}
      </div>
      {/* 珠子是数字的另一种画法,读屏不重复念(数字在下面那一格的 aria-label 里)。 */}
      <div className={styles.abacus} style={columns(axes.length)} aria-hidden="true">
        {axes.map((axis) => (
          <Group key={axis.key} axis={axis} />
        ))}
      </div>
      <div className={styles.readout} style={columns(axes.length)}>
        {axes.map((axis) => (
          <Cell key={axis.key} axis={axis} />
        ))}
      </div>
    </div>
  );
}

/** 轴数多少都等分一行(本世界是三根;不把「3」写死在形态里)。 */
const columns = (n: number) => ({ gridTemplateColumns: `repeat(${Math.max(1, n)}, 1fr)` });

function Group({ axis }: { axis: AxisView }) {
  const rods = rodsFor(axis.value);
  // danger → 该组算上的珠子转朱红;caution 珠子不变色(只有状态词变)。
  const cls = axis.severity === 'danger' ? `${styles.group} ${styles.danger}` : styles.group;
  return (
    <div className={cls} data-group="">
      {rods.map((rod, i) => (
        <div className={styles.rod} key={i} data-rod="">
          <Bead on={rod.upper} upper top={UPPER_TOP} />
          {[0, 1, 2, 3].map((j) => (
            <Bead key={j} on={j < rod.lower} upper={false} top={lowerTop(j)} />
          ))}
        </div>
      ))}
    </div>
  );
}

function Bead({ on, upper, top }: { on: boolean; upper: boolean; top: number }) {
  const ref = useRef<HTMLElement | null>(null);
  const target = on ? (upper ? UPPER_ON_DY : LOWER_ON_DY) : 0;
  // 上一次的落点与在途动画。首帧 prev = target → 不动(续局 / 装载时珠子直接在位)。
  const prev = useRef(target);
  const anim = useRef<Animation | null>(null);

  useLayoutEffect(() => {
    const el = ref.current;
    const from = prev.current;
    prev.current = target;
    if (!el || from === target) return;
    const running = anim.current;
    anim.current = null;
    if (reducedMotion() || typeof el.animate !== 'function') {
      running?.cancel();
      return; // 内联 transform 已是目标位置:直接落位
    }
    // 在途 → 从此刻的实际位置接着走(计算样式里含动画效果);不在途 → 从上一次的落点走。
    const cur = running ? currentY(el, from) : from;
    running?.cancel();
    if (cur === target) return;
    const back = target - Math.sign(target - cur) * BEAD_BACK_PX;
    anim.current = el.animate(
      [
        { transform: `translateY(${cur}px)`, easing: 'cubic-bezier(0.5, 0, 1, 0.7)' },
        { transform: `translateY(${target}px)`, offset: 0.55, easing: 'ease-out' },
        { transform: `translateY(${back}px)`, offset: 0.78, easing: 'ease-in' },
        { transform: `translateY(${target}px)` },
      ],
      { duration: BEAD_MS },
    );
  }, [target]);

  // 卸载时收掉在途动画(AGENTS.md §5)。
  useLayoutEffect(() => () => anim.current?.cancel(), []);

  return (
    <i
      ref={ref}
      className={on ? `${styles.bead} ${styles.beadOn}` : styles.bead}
      data-bead={upper ? 'upper' : 'lower'}
      data-on={on ? 'true' : 'false'}
      style={{ top: `${top}px`, transform: `translateY(${target}px)` }}
    />
  );
}

/** 读元素此刻的 translateY(含在途动画);读不到就用给定的回落值。 */
function currentY(el: HTMLElement, fallback: number): number {
  try {
    const t = getComputedStyle(el).transform;
    if (!t || t === 'none') return 0;
    return new DOMMatrixReadOnly(t).m42;
  } catch {
    return fallback;
  }
}

function Cell({ axis }: { axis: AxisView }) {
  const cls = [
    styles.cell,
    axis.severity === 'caution' ? styles.caution : '',
    axis.severity === 'danger' ? styles.danger : '',
  ]
    .filter(Boolean)
    .join(' ');
  return (
    <div className={cls} aria-label={axis.a11yText} data-severity={axis.severity ?? ''}>
      <span className={styles.cellNum}>{Math.round(axis.value)}</span>
      {/* 无档表时也占一行高度,三格底边对齐。 */}
      <span className={styles.cellBand}>{axis.bandLabel ?? ''}</span>
      {/* 状态词只呈现通用层给的风险等级;severity 为 null → 空串,不画小方点(CSS :not(:empty))。 */}
      <span className={styles.cellRisk}>{severityWord(axis.severity) ?? ''}</span>
    </div>
  );
}
