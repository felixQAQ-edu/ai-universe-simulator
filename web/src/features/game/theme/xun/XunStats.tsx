import { useCallback, useEffect, useLayoutEffect, useRef, type CSSProperties } from 'react';
import type { AxisView, StatsProps, StatsTimeline } from '../contract';
import { isDebug } from '../debug';
import type { SkinRuntime } from '../lifecycle';
import { reducedMotion } from '../motion';
import { justMarked, ticksFor } from './ticks';
import {
  BAND_MS,
  BAR_GAP_MS,
  BAR_STAGGER_MS,
  COUNT_FADE_MS,
  COUNT_MS,
  EASE_OUT_CUBIC,
  ENTER_MS,
  GHOST_FADE_MS,
  GHOST_HOLD_MS,
  KEPT_DELAY_MS,
  KEPT_MS,
  SHORT_TICK,
  TICK_MS,
} from './timing';
import styles from './xun.module.css';

// 《寻常》· 数值形态「四行 + 一行刻痕」(ADR-020「《寻常》局内皮肤」一节决策 3 / 5)。
//
//   刻痕行   每回合一道 1px 短竖痕,越早越淡;服务端标记过的回合(ADR-040 markedTurns)那一道是长的、不变淡
//   四行     轴名 | 条(2px 实线 + 1px 带四处缺口的残线)| 档名(宋体)| 数字
//
// **主题层不认轴**(ADR-018 §4.2):四行按 axes 顺序排,不读 key;颜色只读 `axis.severity`。
// **单一驱动量**(§4.2.1):数字、实线长度、档名、条色都从通用层插值中的显示值派生,本组件不滚数字。
// 刻痕只吃 `timeline`(通用层只给声明了 `statsTimeline` 的皮肤),不读牵挂的数值、不判档。
//
// ── 槽位(AGENTS.md Motion Constraints §2;Felix 2026-11-15 审定 2)────────────────────────
//   持续     = 无。
//   低频偶发 = **一个槽,两种表现**(§2.1):入场序列(挂载时一次)与一级记忆点「留下的那一年」。
//              互斥由代码保证:入场只在挂载时排;记忆点只在回合往前走的那次渲染判定 ——
//              回合一换,通用层的 runtime 先 teardown(取消在途的入场动画、直接到终态),之后本组件才判记忆点。
//   用户触发 = **一个槽**:回合落地的回应 = 数值滚动(数字 + 实线,通用层驱动)+ 残痕 + 档名换字
//              + 新刻痕淡入 / 旧刻痕降一级。选项 / 返回键按压在点击时、落地之前,不同时。
//
// ── 实现手法 ─────────────────────────────────────────────────────────────────────
// 一次性的效果都用浏览器自带的 Web Animations(`element.animate`),只动 `opacity` 与 `transform`(§6);
// 每个动画登记到 runtime 的 teardown(换回合 / 卸载即取消、落到静态终态)。不引库、不用 GSAP 时间线。
// 减弱动效或环境没有 `animate`(jsdom)→ 一律不动画,静态样式就是结果。

const NO_TIMELINE: StatsTimeline = { turn: 0, markedTurns: [] };

/** 起一个一次性动画并登记到 runtime;不能动(减弱动效 / 没有 animate)→ null。 */
function play(
  runtime: SkinRuntime,
  el: Element | null | undefined,
  keyframes: Keyframe[],
  options: KeyframeAnimationOptions,
): Animation | null {
  if (!el || reducedMotion() || typeof el.animate !== 'function') return null;
  const a = el.animate(keyframes, options);
  runtime.onTeardown(() => a.cancel());
  return a;
}

export function XunStats({ axes, runtime, timeline }: StatsProps) {
  const tl = timeline ?? NO_TIMELINE;
  const ticks = ticksFor(tl);
  const panelRef = useRef<HTMLDivElement | null>(null);

  // ── 入场:刻痕从左到右逐道出现 → 120ms 后四根条依次画出来。数字与档名第一帧就是当前值(审定 3)。
  // 已标记的刻痕一开始就是长的(首次装载 / 续局不播长出来)。
  const playEntry = useCallback((): Animation[] => {
    const root = panelRef.current;
    if (!root) return [];
    const out: (Animation | null)[] = [];
    const tickEls = [...root.querySelectorAll<HTMLElement>('[data-tick]')];
    tickEls.forEach((el, j) => {
      out.push(
        play(runtime, el, [{ opacity: 0 }, { opacity: Number(el.dataset.opacity ?? 1) }], {
          duration: COUNT_FADE_MS,
          delay: j * COUNT_MS,
          easing: 'ease-out',
          fill: 'backwards',
        }),
      );
    });
    const tickDone = tickEls.length * COUNT_MS;
    root.querySelectorAll<HTMLElement>('[data-fill]').forEach((el, k) => {
      out.push(
        play(runtime, el, [{ transform: 'scaleX(0)' }, { transform: el.style.transform || 'scaleX(1)' }], {
          duration: ENTER_MS,
          delay: tickDone + BAR_GAP_MS + k * BAR_STAGGER_MS,
          easing: EASE_OUT_CUBIC,
          fill: 'backwards',
        }),
      );
    });
    return out.filter((a): a is Animation => a !== null);
  }, [runtime]);

  useEffect(() => {
    const anims = playEntry();
    return () => anims.forEach((a) => a.cancel());
  }, [playEntry]);

  // ── 回合落地:新刻痕淡入;若服务端刚把这一回合标记了 → 一级记忆点「留下的那一年」。
  const playKept = useCallback(
    (el: Element | null | undefined) =>
      play(runtime, el, [{ transform: SHORT_TICK }, { transform: 'scaleY(1)' }], {
        duration: KEPT_MS,
        delay: KEPT_DELAY_MS,
        easing: 'ease-out',
        fill: 'backwards',
      }),
    [runtime],
  );
  const prevTimeline = useRef<StatsTimeline | null>(null);
  useEffect(() => {
    const prev = prevTimeline.current;
    prevTimeline.current = tl;
    if (!prev || tl.turn <= prev.turn) return;
    const tickEls = panelRef.current?.querySelectorAll('[data-tick]');
    const last = tickEls?.[tickEls.length - 1];
    play(runtime, last, [{ opacity: 0 }, { opacity: 1 }], { duration: TICK_MS, easing: 'ease-out', fill: 'backwards' });
    if (justMarked(prev, tl)) playKept(last);
  }, [tl, runtime, playKept]);

  // 调试重播(`?debug=1`;审定 5:两段都在本组件里,钮也放这里)。
  const replayKept = () => {
    const root = panelRef.current;
    const kept = root?.querySelectorAll('[data-tick][data-kept="true"]');
    const all = root?.querySelectorAll('[data-tick]');
    playKept(kept?.length ? kept[kept.length - 1] : all?.[all.length - 1]);
  };

  return (
    <div className={styles.panel} ref={panelRef}>
      <div className={styles.years} aria-hidden="true" style={{ '--n': Math.max(1, ticks.length) } as CSSProperties}>
        {ticks.map((t, i) => (
          <i
            key={t.turn}
            className={t.kept ? `${styles.tick} ${styles.kept}` : styles.tick}
            data-tick=""
            data-kept={t.kept ? 'true' : 'false'}
            data-opacity={t.opacity}
            style={{ '--i': i, opacity: t.opacity } as CSSProperties}
          />
        ))}
      </div>
      <div className={styles.words}>
        {axes.map((axis) => (
          <Row key={axis.key} axis={axis} runtime={runtime} />
        ))}
      </div>
      {isDebug() && (
        <div className={styles.debugRow}>
          <button type="button" className={styles.debugBtn} onClick={() => playEntry()}>
            重播:入场
          </button>
          <button type="button" className={styles.debugBtn} onClick={replayKept}>
            重播:留下的那一年
          </button>
        </div>
      )}
    </div>
  );
}

function Row({ axis, runtime }: { axis: AxisView; runtime: SkinRuntime }) {
  const state =
    axis.severity === 'danger' ? styles.danger : axis.severity === 'caution' ? styles.caution : '';
  const p = axis.percent / 100;

  // ── 残痕(勘察 5):数值开始下降的那一帧,把「降之前」那一截画成 --muted 实线、压在实线下面,
  // 停 300ms 再 1400ms 淡掉。画成 0 → from(而不是 to → from):被实线盖住的部分看不见,露出来的就是退回去的那一截。
  // 记的是上一帧的显示值(同 QiriStats 的 prev),不插值、不另起驱动量。上涨不留残痕,有残痕就收掉。
  const ghostRef = useRef<HTMLElement | null>(null);
  const prevPercent = useRef(axis.percent);
  const ghost = useRef<Animation | null>(null);
  useEffect(() => {
    const from = prevPercent.current;
    prevPercent.current = axis.percent;
    if (axis.percent > from) {
      ghost.current?.cancel();
      ghost.current = null;
      return;
    }
    if (axis.percent === from || ghost.current?.playState === 'running') return;
    const s = `scaleX(${from / 100})`;
    ghost.current = play(
      runtime,
      ghostRef.current,
      [
        { opacity: 1, transform: s },
        { opacity: 1, transform: s, offset: GHOST_HOLD_MS / (GHOST_HOLD_MS + GHOST_FADE_MS), easing: 'ease-in' },
        { opacity: 0, transform: s },
      ],
      { duration: GHOST_HOLD_MS + GHOST_FADE_MS },
    );
  }, [axis.percent, runtime]);

  return (
    // 整行念一遍(通用层给的 a11yText,含风险词);里面的格子对读屏是图的一部分,不念两遍。
    <div className={state ? `${styles.word} ${state}` : styles.word} role="img" aria-label={axis.a11yText} data-word="">
      <span className={styles.wName}>{axis.displayName}</span>
      <span className={styles.bar}>
        <i className={styles.barRest} />
        <i className={styles.barGhost} ref={ghostRef} data-ghost="" />
        <i className={styles.barFill} data-fill="" style={{ transform: `scaleX(${p})` }} />
      </span>
      <span className={styles.wBand} data-band="">
        <b />
        <BandText label={axis.bandLabel ?? ''} runtime={runtime} />
      </span>
      <span className={styles.wNum}>{Math.round(axis.value)}</span>
    </div>
  );
}

/**
 * 档名换字:350ms 淡出 → 换字 → 350ms 淡回(样张 `setBand`)。小方块与条色由 severity 同帧换,只有这段字慢半拍。
 * 字由本组件命令式写进 span(React 不给它子节点):淡出途中屏上必须还是旧字,而 `bandLabel` 已经是新的了。
 * 淡出途中又换了档(一次滚动跨两档)→ 不另起,换字时读最新那个。首帧、减弱动效、不能动 → 直接换。
 */
function BandText({ label, runtime }: { label: string; runtime: SkinRuntime }) {
  const ref = useRef<HTMLSpanElement | null>(null);
  const latest = useRef(label);
  const fading = useRef<Animation | null>(null);
  const mounted = useRef(false);

  useLayoutEffect(() => {
    const el = ref.current;
    latest.current = label;
    if (!el) return;
    if (!mounted.current) {
      mounted.current = true;
      el.textContent = label;
      return;
    }
    if (fading.current || el.textContent === label) return;
    const out = play(runtime, el, [{ opacity: 1 }, { opacity: 0 }], {
      duration: BAND_MS / 2,
      easing: 'ease-in-out',
      fill: 'forwards',
    });
    if (!out) {
      el.textContent = label;
      return;
    }
    fading.current = out;
    out.onfinish = () => {
      fading.current = null;
      el.textContent = latest.current;
      play(runtime, el, [{ opacity: 0 }, { opacity: 1 }], { duration: BAND_MS / 2, easing: 'ease-in-out' });
      out.cancel(); // 去掉 forwards 的残留;新起的淡入在上层,不闪
    };
    // 被 teardown 取消(换回合 / 卸载):直接落到最新的字。
    out.oncancel = () => {
      if (fading.current !== out) return;
      fading.current = null;
      el.textContent = latest.current;
    };
  }, [label, runtime]);

  return <span ref={ref} data-band-text="" />;
}
