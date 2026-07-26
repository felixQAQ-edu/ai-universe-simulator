import { useCallback, useEffect, useRef } from 'react';
import gsap from 'gsap';
import type { AmbientProps } from '../contract';
import { isDebug } from '../debug';
import { XIAN, reducedMotion } from '../motion';
import { trace } from '../telemetry';
import styles from './xian.module.css';

// 修仙 · 天穹层「金光流云」+ 一级记忆点「一声钟鸣」。
//
// ── 钟鸣与灯闪的结构差异(本刀最要紧的一点)────────────────────────────────
// 灯闪是**入场序列**:进门就播,与开场叙事串行(ADR-018 §4.7)。
// 钟鸣是**状态事件**:玩家选了「闭关冲击境界」,几秒后世界回话 —— 它挂在
// **签名轴向上跨档**上(§5 Q5),由通用层 `useSignalCrossing` 判定后经 `signatureTick` 传进来。
// 因此本皮肤 `hasIntro: false`(不占开场),也**不按 `generating` 抑制**:
// delta(数值)恰恰落在 generating 的尾巴上,按 generating 拒绝 = 这口钟永远不响。
//
// ── 承载面(F-019 教训直接适用:先回答「作用在哪个面、会被什么遮住」)────────
//   主面 = **顶部场景图**(内容层里唯一不透明、占屏最大、在视线起点的面)
//   补面 = **天穹层**(视口边缘与面板间隙,补全「整片天亮了一下」)+ 那一圈扩散金光
//   轻面 = 灵脉面板(受照微光)
//   不动 = **正文与选项**(不加滤镜、不改 opacity)—— 阅读绝对稳定,已实测全程不变
// 编排方式同刀 1:GSAP 只写**主题根上的 CSS 自定义属性**,各承载面自己消费,
// 氛围层不去 query 别人的 DOM。
//
// ── 时间轴(样板间校准基准,生产按「值到达」重锚)────────────────────────
//   t=0(玩家按下)玉简墨色确认 —— 在 `XianActions` 里,不在这条时间线上:
//       生产的一个回合要跑好几秒,「输入已接收」的确认必须在按下那一刻给,
//       而不是等世界回话(这是样板间单击即完成、生产隔着一次 LLM 调用的结构差异)。
//   以下重锚到**新数值到达**(= 跨档判定成立)那一刻:
//   +0        排期,记探针
//   +300ms    环境收住(天穹与灵脉一起屏住呼吸)
//   +550ms    钟鸣:**沿境界印的轮廓先亮一圈**(RIM_LEAD_S),再向上扩散进云海(明显阶段约 2s)
//   +1100ms   境界与数值**才**开始变化(由通用层的 `ceremonyDelayMs` 施加,见 skins.tsx)
//   之后      云层被推开的痕迹缓慢回落约 9s(余韵;不加长、不叠粒子爆发、不留持续发光)
const STILL_MS = 300;
const RING_MS = 550;
/** 明显阶段收尾、放开忙态锁的时刻(余韵仍在跑,但不再阻止下一次)。 */
const RELEASE_MS = 2600;
/** 余韵回落(秒):云层痕迹恢复,长尾缓动。 */
const AFTERGLOW_S = 9;
/** 印边先亮的那一拍(秒):波纹自印的轮廓荡开,而不是从中心炸开(Felix 2026-07-26)。 */
const RIM_LEAD_S = 0.18;

const TRACE = 'xian.bell';

/**
 * 金色微粒的分布(12 粒:横位 % / 直径 px / 起始延迟 s / 单程时长 s / 透明度)。
 * **写死不随机**:一是渲染期调 `Math.random()` 不纯(重渲会让微粒瞬移,eslint 也拦);
 * 二是手挑一组疏密不匀的数比随机更可控 —— 随机常给出「三粒挤在一起」的坏排布。
 */
const DOTS = [
  { left: 6, size: 2.4, delay: 0, dur: 21, opacity: 0.3 },
  { left: 17, size: 3.6, delay: 7.5, dur: 16, opacity: 0.42 },
  { left: 23, size: 2.0, delay: 3.2, dur: 25, opacity: 0.24 },
  { left: 34, size: 4.1, delay: 11.4, dur: 18, opacity: 0.5 },
  { left: 41, size: 2.7, delay: 1.8, dur: 23, opacity: 0.28 },
  { left: 49, size: 3.2, delay: 9.1, dur: 15, opacity: 0.36 },
  { left: 58, size: 2.2, delay: 5.6, dur: 26, opacity: 0.22 },
  { left: 66, size: 3.9, delay: 2.4, dur: 19, opacity: 0.46 },
  { left: 73, size: 2.5, delay: 10.2, dur: 22, opacity: 0.31 },
  { left: 81, size: 3.4, delay: 6.3, dur: 17, opacity: 0.4 },
  { left: 88, size: 2.1, delay: 4.1, dur: 24, opacity: 0.26 },
  { left: 95, size: 3.0, delay: 8.7, dur: 20, opacity: 0.34 },
];

export function XianAmbient({ runtime, rootRef, signatureTick, setRootClass }: AmbientProps) {
  const skyRef = useRef<HTMLDivElement | null>(null);
  const haloRef = useRef<HTMLDivElement | null>(null);

  const playBell = useCallback(() => {
    const root = rootRef.current;
    const suppress = (reason: string) => trace(TRACE, { suppressedReason: reason, state: 'suppressed' });
    if (!root) return suppress('主题根未挂载');
    // reduced-motion:不鸣,静态氛围与数值照常(数值也不会被推迟 —— 通用层此时不插值)。
    if (reducedMotion()) return suppress('reduced-motion');
    // 忙态锁:连点 / 一回合内重复触发直接忽略,**不排队、不堆叠 timeline**(AGENTS.md §5)。
    if (!runtime.tryLock()) return suppress('busy(上一次尚未收尾)');

    const token = runtime.token;
    trace(TRACE, { scheduledAt: now(), state: 'scheduled', suppressedReason: undefined }, true);

    runtime.onTeardown(() => {
      // 被打断(换 turn / 卸载):金光收回、天穹归位、状态 class 清掉。
      // **不补播**——仪式感不以状态滞后为代价(§5 Q5)。
      clearVars(root);
      setRootClass('');
      if (haloRef.current) haloRef.current.style.display = 'none';
    });

    // ① 环境收住(前一拍):天穹与灵脉屏息,玩家先觉得「要发生什么」。
    runtime.setTimeout(() => {
      setRootClass(styles.stilled);
      trace(TRACE, { state: 'still', activeClass: 'stilled' });
    }, STILL_MS);

    // ② 钟鸣
    runtime.setTimeout(() => {
      setRootClass(''); // 收住解除:云流恢复,同时钟响
      trace(TRACE, { firedAt: now(), state: 'ring', activeClass: '' });
      const halo = haloRef.current;
      const sky = skyRef.current;

      runtime.add(() => {
        // 那一圈扩散金光(氛围层内,不越过内容层)。**比印边晚一拍起**:
        // 先看见印的轮廓亮起,波纹才荡开 —— 不是从一个方块的中心炸开。
        if (halo) {
          gsap
            .timeline({ delay: RIM_LEAD_S })
            .set(halo, { display: 'block' })
            .fromTo(
              halo,
              { scale: 0.35, opacity: 0 },
              { scale: 1.05, opacity: 0.85, duration: XIAN.dur(0.4), ease: XIAN.ease },
            )
            .to(halo, { scale: 1.9, opacity: 0, duration: XIAN.dur(1.1), ease: XIAN.ease }, '>-0.05')
            .set(halo, { display: 'none' });
        }

        // 金光泛照:一条时间线同时驱动境界印 / 顶部场景图 / 天穹 / 灵脉面板。
        // 顺序是**先印后天**(Felix 2026-07-26):沿印的轮廓先亮一圈,再向上扩散进云海。
        gsap
          .timeline({
            onComplete: () => {
              if (!runtime.alive(token)) return;
              trace(TRACE, { completedAt: now(), state: 'settle' });
            },
          })
          // ① 印边先亮(短、快、只作用在印的轮廓上)
          .to(root, {
            '--bell-seal': 1,
            duration: RIM_LEAD_S,
            ease: XIAN.ease,
            onStart: () => trace(TRACE, { state: 'rim' }),
          })
          // ② 光自印处向上扩散进云海
          .to(root, {
            '--bell-b': 1.32,
            '--bell-s': 1.14,
            '--bell-warm': 0.3,
            '--bell-wash': 0.55,
            '--bell-panel': 1,
            duration: XIAN.dur(0.35),
            ease: XIAN.ease,
          })
          // ③ 长尾回落(印边与天光一起收)
          .to(root, {
            '--bell-b': 1,
            '--bell-s': 1,
            '--bell-warm': 0,
            '--bell-wash': 0,
            '--bell-panel': 0,
            '--bell-seal': 0,
            duration: XIAN.dur(1.5),
            ease: XIAN.easeLong,
          });

        // 余韵:云层被推开的痕迹,长尾回落(不加长、不叠新东西)
        if (sky) {
          gsap
            .timeline()
            .to(sky, { y: -8, opacity: 0.92, duration: XIAN.dur(1.4), ease: XIAN.ease })
            .to(sky, { y: 0, opacity: 1, duration: AFTERGLOW_S, ease: XIAN.easeLong });
        }
      });
    }, RING_MS);

    // ③ 明显阶段收尾:放开忙态锁(余韵继续跑,但不再挡下一次)
    runtime.setTimeout(() => {
      runtime.unlock();
      trace(TRACE, { state: 'done' });
    }, RELEASE_MS);
  }, [runtime, rootRef, setRootClass]);

  // 触发口:**签名轴向上跨档**(§5 Q5)。tick 由通用层判定后传入 ——
  // 首次装载恒为 0(init/resume 不误触发),下跌 / 同档不加,一次跨多档只加一次。
  const playRef = useRef(playBell);
  useEffect(() => {
    playRef.current = playBell;
  }, [playBell]);

  useEffect(() => {
    if (signatureTick === 0) return;
    playRef.current();
    // 在途 timeline 与定时器由 runtime 统一 teardown 收走(§4.4),此处不另写清理。
  }, [signatureTick]);

  return (
    <>
      <div className={styles.ambient} aria-hidden="true">
        <div className={styles.skyFill} />
        <div className={styles.sky} ref={skyRef}>
          <div className={`${styles.cloud} ${styles.c1}`} />
          <div className={`${styles.cloud} ${styles.c2}`} />
          <div className={`${styles.cloud} ${styles.c3}`} />
          {DOTS.map((d, i) => (
            <span
              key={i}
              className={styles.dot}
              style={{
                left: `${d.left}%`,
                width: d.size,
                height: d.size,
                opacity: d.opacity,
                animationDelay: `${d.delay}s`,
                animationDuration: `${d.dur}s`,
              }}
            />
          ))}
        </div>
        <div className={styles.skyWash} />
        <div className={styles.halo} ref={haloRef} />
      </div>
      {isDebug() && (
        <button type="button" className={styles.debugBtn} onClick={() => playBell()}>
          重播:钟鸣(收住 → 金光 → 余韵)
        </button>
      )}
    </>
  );
}

const now = () => Math.round(performance.now());

/** 把这次编排写进根上的自定义属性清掉(GSAP 之外的兜底,teardown 时用)。 */
function clearVars(root: HTMLElement): void {
  for (const v of ['--bell-b', '--bell-s', '--bell-warm', '--bell-wash', '--bell-panel']) {
    root.style.removeProperty(v);
  }
}
