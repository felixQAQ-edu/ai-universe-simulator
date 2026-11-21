import { useEffect, useRef, useState } from 'react';
import game from '../../game.module.css';
import type { SkinRuntime } from '../lifecycle';
import { reducedMotion } from '../motion';
import { trace } from '../telemetry';
import { bleedWindow, hanOffsets, nextBleedDelayMs } from './bleedSchedule';
import type { BleedSpec, SeepEntry, SeepSignals } from './registry';
import { SeepStain } from './SeepStain';
import styles from './seep.module.css';

// 渗入层的正文形态(ADR-043 决策 5):**只在宿主没有 Prose 槽时**由 `PlayingScreen` 选用(修仙没有)。
// 复用通用 `.prose`(同 CthuProse),多挂一个渗入 class;正文两角的墨洇画在这里(决策 3)。
//
// **字洇成墨**(低频槽;宿主修仙低频为空,审定 8):正文里偶尔一个汉字变成墨色、字后一团淡墨晕开,停一下再收回。
// - 载体(方案 B2):有在途的一次时渲染「前段 + <span>字</span> + 后段」,否则就是一段文本 ——
//   **只换色不换字**,不改字宽、不加 aria,复制 / 选择 / 读屏连续(ADR-019 §4 第 1 条的补充约束,Felix 审定 1)。
// - 时序照样张的类名序列原样:plain → on(400ms 晕开)→ 停 600ms → off(500ms 收墨)→ +50ms 回 plain(字色 400ms 回原色)→ 复原成一段文本。
// - 调度:只在清明 caution / danger;caution 均值 75s [30, 150]、danger 均值 40s [20, 90](审定 6);首次至少 20s。
// - **一屏最多一个**(状态只有一个槽);**只在逐字结束后**(`paused` 期间不调度、不在途:一变 paused 立即撤销);
//   **reduced-motion 下不出现**(审定 7)。
// - 选字:正文中的汉字,优先当前视口内的(读自己组件的字框),不连续两次同一字位;视口内一个都没有 → 这次作罢、另排下一次。
// - 计时走 `runtime.setTimeout`(换回合 / 卸载作废)+ 本次 effect 的作废标记;回调写 state 前都查(F-041)。不持宿主的锁。

const TRACE = 'seep.bleed';
const now = () => Math.round(performance.now());
const PICK_TRIES = 12;

type BleedPhase = 'pre' | 'on' | 'off' | 'plain';
interface Bleed {
  offset: number;
  length: number;
  phase: BleedPhase;
}

export function SeepProse({
  text,
  caret,
  seep,
  signals,
  paused,
  runtime,
}: {
  text: string;
  caret: boolean;
  seep: SeepEntry;
  signals: SeepSignals;
  /** 正文不稳定期(生成中 / 开场逐字未完)。 */
  paused: boolean;
  runtime: SkinRuntime;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [bleed, setBleed] = useState<Bleed | null>(null);
  const lastOffset = useRef(-1);
  const spec = seep.bleed;
  const severity = signals.bleedSeverity;

  useEffect(() => {
    const win = spec ? bleedWindow(spec, severity) : null;
    if (!spec || !win || paused || !text) return;
    if (reducedMotion()) {
      trace(TRACE, { suppressedReason: 'reduced-motion', state: 'suppressed' }, true);
      return;
    }
    const token = runtime.token;
    let dead = false;
    let raf = 0;
    const live = () => !dead && runtime.alive(token);
    const after = (ms: number, fn: () => void) => runtime.setTimeout(() => live() && fn(), ms);

    const schedule = (first: boolean) => {
      const d = nextBleedDelayMs(win, first);
      trace(TRACE, { scheduledAt: now() + Math.round(d), state: 'scheduled', suppressedReason: undefined }, true);
      after(d, fire);
    };

    const fire = () => {
      const pick = pickOffset(ref.current, text, lastOffset.current);
      if (!pick) {
        trace(TRACE, { suppressedReason: '视口内没有可选的字', state: 'skipped' });
        schedule(false);
        return;
      }
      lastOffset.current = pick.offset;
      trace(TRACE, { firedAt: now(), state: 'in' });
      setBleed({ ...pick, phase: 'pre' });
      // 先让「原色」那一帧落地,再切 on,过渡才有起点(样张靠 reflow,这里靠两帧)。
      raf = requestAnimationFrame(() => {
        raf = requestAnimationFrame(() => {
          raf = 0;
          if (!live()) return;
          setBleed((b) => (b ? { ...b, phase: 'on' } : b));
          runSequence(spec);
        });
      });
    };

    const runSequence = (s: BleedSpec) => {
      after(s.inMs + s.holdMs, () => {
        trace(TRACE, { state: 'out' });
        setBleed((b) => (b ? { ...b, phase: 'off' } : b));
        after(s.outMs + 50, () => {
          setBleed((b) => (b ? { ...b, phase: 'plain' } : b));
          // 字色回原色走 .bleed 的 inMs 过渡(样张同),走完再复原成一段文本。
          after(s.inMs + 50, () => {
            setBleed(null);
            trace(TRACE, { completedAt: now(), state: 'done' });
            schedule(false);
          });
        });
      });
    };

    const stop = () => {
      dead = true;
      if (raf) cancelAnimationFrame(raf);
      setBleed(null);
    };
    // 换回合 / 卸载(runtime teardown)与 effect 重跑(paused / 文本 / 档变)走同一个撤销:复原成一段文本、不留半截。
    runtime.onTeardown(stop);
    schedule(true);
    return stop;
  }, [spec, severity, paused, text, runtime]);

  const shown = bleed && text.slice(bleed.offset, bleed.offset + bleed.length) ? bleed : null;
  return (
    <div ref={ref} className={`${game.prose} ${styles.prose}`}>
      {seep.stain && <SeepStain stain={seep.stain} level={signals.stainLevel} surface="prose" />}
      {shown ? (
        <>
          {text.slice(0, shown.offset)}
          <span
            className={bleedClass(shown.phase)}
            style={
              spec
                ? ({ '--bin': `${spec.inMs}ms`, '--bout': `${spec.outMs}ms` } as React.CSSProperties)
                : undefined
            }
            data-seep-bleed={shown.phase}
          >
            {text.slice(shown.offset, shown.offset + shown.length)}
          </span>
          {text.slice(shown.offset + shown.length)}
        </>
      ) : (
        text
      )}
      {caret && <span className={game.caret}>▍</span>}
    </div>
  );
}

function bleedClass(phase: BleedPhase): string {
  if (phase === 'on') return `${styles.bleed} ${styles.bleedOn}`;
  if (phase === 'off') return `${styles.bleed} ${styles.bleedOff}`;
  return `${styles.bleed} ${styles.bleedPlain}`;
}

/** 选一个字:正文里的汉字,优先当前视口内的,不与上一次同位。读的是自己组件的字框。 */
function pickOffset(
  root: HTMLElement | null,
  text: string,
  last: number,
): { offset: number; length: number } | null {
  const all = hanOffsets(text).filter((h) => h.offset !== last);
  if (!root || all.length === 0) return null;
  const node = [...root.childNodes].find((n) => n.nodeType === Node.TEXT_NODE && n.nodeValue === text);
  for (let i = 0; i < PICK_TRIES && all.length > 0; i++) {
    const [h] = all.splice(Math.floor(Math.random() * all.length), 1);
    if (!node) return h; // 文本节点对不上(理论上不会)→ 不测视口
    const r = document.createRange();
    r.setStart(node, h.offset);
    r.setEnd(node, h.offset + h.length);
    if (typeof r.getBoundingClientRect !== 'function') return h; // 无排版环境(测试)
    const b = r.getBoundingClientRect();
    if (b.width > 0 && b.top >= 0 && b.bottom <= window.innerHeight) return h;
  }
  return null;
}
