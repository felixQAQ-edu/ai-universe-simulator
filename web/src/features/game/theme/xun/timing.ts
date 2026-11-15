// 《寻常》· 动效参数(ADR-020「《寻常》局内皮肤」一节决策 3 / 5)。数值照 docs/xun-g2-samples/xun-motion.html 的默认值(Felix 定稿)。
// 单独一个文件:组件文件只导出组件(react-refresh),测试与组件共用这一份。

/** 回合落地:新刻痕淡入、旧刻痕降一级的时长(ms;旧刻痕走 CSS 过渡,同值)。 */
export const TICK_MS = 900;
/** 入场:刻痕逐道出现的间隔与每道的淡入时长(ms)。 */
export const COUNT_MS = 28;
export const COUNT_FADE_MS = 240;
/** 入场:刻痕出完到第一根条起画的空档、行与行的错开、每根条画多久(ms)。 */
export const BAR_GAP_MS = 120;
export const BAR_STAGGER_MS = 90;
export const ENTER_MS = 1100;
/** 记忆点:新刻痕出现后多久开始长、长多久(ms)。 */
export const KEPT_DELAY_MS = Math.round(TICK_MS * 0.6);
export const KEPT_MS = 600;
/** 残痕:从滚动开始算停多久再淡、淡多久(ms)。 */
export const GHOST_HOLD_MS = 300;
export const GHOST_FADE_MS = 1400;
/** 档名换字:淡出、淡入各一半(ms)。 */
export const BAND_MS = 700;
/** = 演示里的 1 − (1 − x)³(easeOutCubic),同 `valueRoll` 的 power2.out。 */
export const EASE_OUT_CUBIC = 'cubic-bezier(0.33, 1, 0.68, 1)';
/** 短刻痕 7px:元素本身 12px,按比例压(长出来只动 transform)。 */
export const SHORT_TICK = `scaleY(${7 / 12})`;
