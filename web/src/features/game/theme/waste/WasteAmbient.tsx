import { useCallback, useEffect, useRef } from 'react';
import gsap from 'gsap';
import type { AmbientProps } from '../contract';
import { isDebug } from '../debug';
import { WASTE, reducedMotion } from '../motion';
import { trace } from '../telemetry';
import styles from './waste.module.css';

// 末日 · 氛围层「灰烬纪元」:风沙(持续)+ 远光(低频,与电台共用一个槽)。
//
// ── 动效预算(AGENTS.md §2 / §2.1)────────────────────────────────────────
//   持续 1 = 风沙横飘(纯 CSS,22 粒)
//   低频 1 = **电台 / 远光两种表现,互斥共用一个槽**：
//            互斥由**代码保证** —— 两边都先抢 `runtime.tryLock()`,电台持锁期间
//            (含 10s 余韵)远光整次跳过,反之亦然。不是「概率上很少同时」。
//   用户触发 1 = 铁皮告示按压 + 数值滚动
//   本世界**没有一级记忆点的入场序列**(记忆点是电台,属状态/环境事件)→ `hasIntro: false`。
//
// ── 远光是环境器,不是主角(样板间定调)────────────────────────────────
// 三种行为都**先亮风沙、再隐约见光源** —— 顺序反过来就成了「车灯照过来」,
// 那是事件;这里要的是「远处有什么东西经过,你没看清」。

/** 风沙分布(22 粒:纵位 % / 尺寸 px / 起始延迟 s / 时长 s / 透明度)。
 *  **写死不随机**:渲染期 `Math.random()` 不纯(重渲会让沙粒瞬移,eslint 也拦)。 */
const DUST = [
  { top: 4, w: 3.5, h: 1.6, delay: 0, dur: 9, opacity: 0.24 },
  { top: 11, w: 2.4, h: 1.1, delay: 4.2, dur: 13, opacity: 0.16 },
  { top: 17, w: 4.6, h: 2.1, delay: 1.5, dur: 7, opacity: 0.34 },
  { top: 23, w: 2.0, h: 1.0, delay: 8.1, dur: 15, opacity: 0.13 },
  { top: 29, w: 3.8, h: 1.7, delay: 2.7, dur: 10, opacity: 0.28 },
  { top: 34, w: 2.8, h: 1.3, delay: 6.4, dur: 12, opacity: 0.19 },
  { top: 39, w: 5.0, h: 2.3, delay: 0.8, dur: 6.5, opacity: 0.4 },
  { top: 44, w: 2.2, h: 1.1, delay: 9.6, dur: 14, opacity: 0.15 },
  { top: 48, w: 3.2, h: 1.5, delay: 3.4, dur: 11, opacity: 0.22 },
  { top: 53, w: 4.2, h: 1.9, delay: 7.2, dur: 8, opacity: 0.31 },
  { top: 57, w: 2.6, h: 1.2, delay: 1.1, dur: 16, opacity: 0.14 },
  { top: 62, w: 3.6, h: 1.6, delay: 5.5, dur: 9.5, opacity: 0.26 },
  { top: 66, w: 2.1, h: 1.0, delay: 8.8, dur: 13.5, opacity: 0.17 },
  { top: 70, w: 4.8, h: 2.2, delay: 2.1, dur: 7.5, opacity: 0.37 },
  { top: 74, w: 2.9, h: 1.4, delay: 6.9, dur: 12.5, opacity: 0.2 },
  { top: 78, w: 3.4, h: 1.5, delay: 0.4, dur: 10.5, opacity: 0.25 },
  { top: 82, w: 2.3, h: 1.1, delay: 4.8, dur: 15.5, opacity: 0.12 },
  { top: 86, w: 4.4, h: 2.0, delay: 9.1, dur: 6.8, opacity: 0.33 },
  { top: 90, w: 2.7, h: 1.3, delay: 3.0, dur: 11.5, opacity: 0.18 },
  { top: 93, w: 3.9, h: 1.8, delay: 7.7, dur: 8.5, opacity: 0.29 },
  { top: 96, w: 2.5, h: 1.2, delay: 1.9, dur: 14.5, opacity: 0.15 },
  { top: 99, w: 3.1, h: 1.4, delay: 5.9, dur: 10, opacity: 0.21 },
];

/** 远光调度步长:约为电台的三分之一频率(远光是配角,别抢戏)。 */
const FAR_GAP_MIN_MS = 26_000;
const FAR_GAP_SPAN_MS = 34_000;
const FAR_CHANCE = 0.4;
/**
 * **兜底放锁**(ms):最长的一支远光时间线约 8s,这里给足余量。
 *
 * 为什么非有不可 —— 冒烟取证抓到的真 bug:远光只在 GSAP `onComplete` 里放锁,而**页面转入
 * 后台时 rAF 冻结、timeline 永不完成**,锁就被永久占住 → 与它共用槽位的**电台从此静默**
 * (直到换回合 teardown 才恢复)。调度侧的 `!document.hidden` 只能防「隐藏时开演」,
 * 防不住「开演后被隐藏」。故再挂一道 `runtime.setTimeout` 兜底 —— 计时器在后台仍会跑
 * (只是被钳到 ≥1s),锁一定放得掉。同刀 2 钟鸣 `RELEASE_MS` 的口径。
 */
const FAR_RELEASE_MS = 12_000;

const TRACE = 'waste.farlight';

export function WasteAmbient({ runtime, rootRef, paused }: AmbientProps) {
  const glowRef = useRef<HTMLDivElement | null>(null);
  const lightRef = useRef<HTMLDivElement | null>(null);
  // 最新值放 ref、**在 effect 里写**(渲染期写 ref 不纯);调度链只排一次,
  // 不因 paused / 回调重建而重排 —— 重排会打乱「随机步长」这件事本身。
  const pausedRef = useRef(paused);
  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);
  const playRef = useRef<() => void>(() => {});

  const farEvent = useCallback(() => {
    const glow = glowRef.current;
    const light = lightRef.current;
    if (!glow || !light) return;
    if (reducedMotion()) return trace(TRACE, { suppressedReason: 'reduced-motion' });
    // **互斥闸(代码保证)**:电台正在播(含余韵)就整次跳过 —— 远光与电台永不同时。
    if (!runtime.tryLock()) return trace(TRACE, { suppressedReason: 'busy(电台正在播)' });

    const token = runtime.token;
    trace(TRACE, { firedAt: Math.round(performance.now()), state: 'far' }, true);
    // 放锁只做一次:正常收尾与兜底谁先到都行,第二次调用是 no-op。
    let released = false;
    const done = (reason: 'complete' | 'fallback') => {
      if (released) return;
      released = true;
      if (runtime.alive(token)) {
        trace(TRACE, {
          completedAt: Math.round(performance.now()),
          state: reason === 'fallback' ? 'far(兜底放锁)' : 'far',
        });
      }
      runtime.unlock();
    };
    runtime.setTimeout(() => done('fallback'), FAR_RELEASE_MS);

    runtime.add(() => {
      const kind = Math.random();
      const tl = gsap.timeline({ onComplete: () => done('complete') });
      if (kind < 0.34) {
        // 行为 1:只有风沙亮起来,始终没看清光源
        tl.to(glow, { opacity: 0.5, duration: 2.2, ease: WASTE.easeStart }).to(
          glow,
          { opacity: 0, duration: 3, ease: WASTE.ease },
          '+=0.6',
        );
      } else if (kind < 0.67) {
        // 行为 2:风沙先亮,光源远远横向经过
        tl.to(glow, { opacity: 0.4, duration: 1.6, ease: WASTE.easeStart })
          .fromTo(light, { x: 0, opacity: 0.1 }, { opacity: 0.45, duration: 1.2 }, '<0.8')
          .to(light, { x: -38, duration: 5.5, ease: 'none' }, '<')
          .to(light, { opacity: 0.1, duration: 1.4 }, '>-1.2')
          .to(glow, { opacity: 0, duration: 2.4 }, '<')
          .set(light, { x: 0 });
      } else {
        // 行为 3:风沙先亮,光源靠近后停住,再熄灭
        tl.to(glow, { opacity: 0.45, duration: 1.8, ease: WASTE.easeStart })
          .to(light, { opacity: 0.55, scale: 1.5, duration: 2.6, ease: WASTE.easeStart }, '<0.6')
          .to(light, { opacity: 0.55, duration: 1.4 })
          .to(light, { opacity: 0.1, scale: 1, duration: 0.5, ease: 'power3.in' })
          .to(glow, { opacity: 0, duration: 2 }, '<');
      }
    });

    // 被打断(换回合 / 卸载):光收回、锁放掉,不补播。
    runtime.onTeardown(() => {
      gsap.set([glow, light], { clearProps: 'all' });
    });
  }, [runtime]);

  useEffect(() => {
    playRef.current = farEvent;
  }, [farEvent]);

  useEffect(() => {
    const loop = () => {
      runtime.setTimeout(
        () => {
          if (!pausedRef.current && !document.hidden && Math.random() < FAR_CHANCE) {
            playRef.current();
          }
          loop();
        },
        FAR_GAP_MIN_MS + Math.random() * FAR_GAP_SPAN_MS,
      );
    };
    loop();
    // 计时器与 timeline 全登记在 runtime 上,统一 teardown 收走(§4.4)。
  }, [runtime]);

  // 本世界不占用入场序列(记忆点是电台,不是进门那一下)——`hasIntro: false`,
  // 故此处不调 onIntroDone、也不需要 rootRef 上的连续量编排。
  void rootRef;

  return (
    <>
      <div className={styles.ambient} aria-hidden="true">
        <div className={styles.wasteFill} />
        <div className={styles.farGlow} ref={glowRef} />
        <div className={styles.farLight} ref={lightRef} />
        {DUST.map((d, i) => (
          <span
            key={i}
            className={styles.dust}
            style={{
              top: `${d.top}%`,
              width: d.w,
              height: d.h,
              opacity: d.opacity,
              animationDelay: `${d.delay}s`,
              animationDuration: `${d.dur}s`,
            }}
          />
        ))}
        <div className={styles.grain} />
        <div className={styles.vignette} />
      </div>
      {isDebug() && (
        <button type="button" className={styles.debugBtn} onClick={farEvent}>
          触发一次远光
        </button>
      )}
    </>
  );
}
