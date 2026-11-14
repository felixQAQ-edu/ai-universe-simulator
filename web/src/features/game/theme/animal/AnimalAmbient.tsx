import { useCallback, useEffect, useLayoutEffect, useRef } from 'react';
import type { AmbientProps } from '../contract';
import { isDebug } from '../debug';
import { reducedMotion } from '../motion';
import { trace } from '../telemetry';
import styles from './animal.module.css';

// 《动物人生》· 氛围层:一块底色 + 一级记忆点「门缝光」的驱动(ADR-021 局内皮肤一节 决策 3)。
//
// ── 槽位(AGENTS.md Motion Constraints §2)──────────────────────────────────
//   持续     = 无(局内不加持续环境动效);
//   低频偶发 = 无(入场是一次性的,不占槽,同规则怪谈灯闪的记法);
//   用户触发 = 数值滚动与光带(同一个驱动量)+ 选项 / 返回键按压,不在本文件。
//   都是本世界局内首个,无可替代(§3)。
//
// ── 入场:进局时光照进来一次 ───────────────────────────────────────────────
//   暗 500ms(每行光带被纸色遮片盖住,门缝为灰)→ 门缝亮起,遮片从门那头往里退开 900ms、ease-out
//   → 铺到各行当前数值处停住 → 放行正文。数字不滚(装载时数值直接落位)。
// 不挂任何轴:本皮肤不登记 signatureAxisKey,收到的 signatureTick 恒为 0,这里不读它。
//
// 托管照规则怪谈(`RulesAmbient`):effect 只依赖 runtime(跨回合稳定)⇒ PlayingScreen 每挂载一次播一次 ——
// **新开局与续局都播**,换回合不重播。减弱动效 / 挂载时正在生成 → 不播,立即放行正文。
//
// ── 入场的驱动在这里,样子不在这里 ─────────────────────────────────────────
// 遮片与门缝画在数值面板(`AnimalStats`)。本文件只在主题根上挂 / 摘 `closed` class,
// `animal.module.css` 的 `.closed .veil` / `.closed .gap` 负责样子与过渡(同 ADR-038 G-2 的接法乙,契约零改动)。
// 两边都不 query 对方的 DOM。
//
// ── 首帧必须是暗的 ───────────────────────────────────────────────────────
// 规则怪谈的灯闪从「正常」开始,在 useEffect(首帧画完之后)里起步没有问题;这里从「暗」开始,
// 若照抄会先亮一帧再暗。所以用 **useLayoutEffect** 挂 `closed`:React 在浏览器画第一帧之前同步重渲。
//
// ── 清理 ─────────────────────────────────────────────────────────────────
// 定时器全登记到 runtime;换回合 / 卸载由同一个 teardown 收走,不补播。根 class 按「皮肤 + 回合」作用域,
// 入场途中换了回合即作废(立刻是亮着的终态)。**不登记 onTeardown 去放行正文**:StrictMode 的
// 挂载 → 清理 → 再挂载会在开演的同一刻 teardown,那样开发模式下正文就不等入场了;
// 真被打断时回合已经 > 0,正文本来就不等,另有 PlayingScreen 的 2.5s 兜底。

/** 进局先暗多久(ms)。样张原值。 */
export const INTRO_DARK_MS = 500;
/** 光铺进来多久(ms;与 CSS `.veil` 的 transition 同值)。走完才放行正文、解锁。 */
export const INTRO_SPREAD_MS = 900;

const TRACE = 'animal.intro';

export function AnimalAmbient({ runtime, generating, setRootClass, onIntroDone }: AmbientProps) {
  /**
   * 入场的进度。守卫看的是「有没有演完 / 被交代过」,不是「有没有开演过」:
   * StrictMode 双挂载时第一次的定时器会被 teardown 清掉,第二次挂载必须能重新起。
   */
  const introDone = useRef(false);
  const released = useRef(false);

  const release = useCallback(() => {
    if (released.current) return;
    released.current = true;
    onIntroDone();
  }, [onIntroDone]);

  const play = useCallback(
    (isIntro: boolean) => {
      const suppress = (reason: string) => {
        trace(TRACE, { suppressedReason: reason, state: 'suppressed' }, isIntro);
        if (isIntro) {
          introDone.current = true; // 抑制也算这一局的入场交代过了
          release(); // 抑制也要放行正文,绝不把叙事卡住
        }
      };
      // 减弱动效:不播,首帧就是亮着的终态(AGENTS.md §5)。
      if (reducedMotion()) return suppress('reduced-motion');
      if (isIntro && generating) return suppress('generating');
      if (!runtime.tryLock()) return suppress('busy(上一次尚未收尾)');

      trace(TRACE, { scheduledAt: now(), firedAt: now(), state: 'dark', activeClass: 'closed', suppressedReason: undefined }, true);
      setRootClass(styles.closed);

      runtime.setTimeout(() => {
        // 摘掉 class:门缝直接亮起,遮片按 CSS 的 900ms ease-out 向左端收拢。
        setRootClass('');
        trace(TRACE, { state: 'spread', activeClass: '' });
      }, INTRO_DARK_MS);

      runtime.setTimeout(() => {
        if (isIntro) {
          introDone.current = true;
          release();
        }
        runtime.unlock();
        trace(TRACE, { completedAt: now(), state: 'done' });
      }, INTRO_DARK_MS + INTRO_SPREAD_MS);
    },
    [runtime, generating, setRootClass, release],
  );

  // 最新的播放函数放 ref,让入场 effect 只依赖 runtime(不因 generating 变化而重排)。
  const playRef = useRef(play);
  useEffect(() => {
    playRef.current = play;
  }, [play]);

  // 入场。useLayoutEffect:首帧画出来之前就挂上 `closed`(见文件头)。
  // 依赖只有 runtime ⇒ 换回合不重排;StrictMode 重挂载会重排(守卫只挡「已演完」)。
  useLayoutEffect(() => {
    if (introDone.current) return;
    playRef.current(true);
  }, [runtime]);

  return (
    <>
      <div className={styles.ambient} aria-hidden="true" />
      {isDebug() && (
        <button type="button" className={styles.debugBtn} onClick={() => play(false)}>
          重播:光照进来
        </button>
      )}
    </>
  );
}

const now = () => Math.round(performance.now());
