import { useCallback, useEffect, useRef } from 'react';
import gsap from 'gsap';
import type { AmbientProps } from '../contract';
import { isDebug } from '../debug';
import { RULES, reducedMotion } from '../motion';
import styles from './rules.module.css';

// 规则怪谈 · 氛围层「值班室冷灯」(样板间冻结成果移植)。
//
// 动效记账(AGENTS.md Motion Constraints §2/§3):
//   持续环境(1) = 监控画面呼吸 —— 挂在**已有的顶部氛围图**上(SceneBanner 即本世界的「监控画面」)。
//                 **它替代样板间那块独立 CCTV 面板**:不新增 DOM 概念、不新增第二个持续动效。
//   低频偶发(1) = OSD 扫描线(RulesStats,14s 单程,纯 CSS)。
//   一次性     = 入场灯闪(本文件;主记忆点,不占低频槽)。
//   用户触发(1) = 规则点亮脉冲 / 选项阶跃反馈(既有)。
//
// 压暗层的位置是**硬要求**:`.blackout` 在氛围层内、z-index 低于内容 —— 灯闪压暗
// **只作用于氛围层与室内光层,正文文字层全程不参与**(Felix 2026-07-24 裁定条件 a)。
// 之所以仍然「整屏在闪」,是因为本主题的面板底色是半透明的:背景在暗,字不暗。

export function RulesAmbient({ runtime, generating, setRootClass }: AmbientProps) {
  const blackout = useRef<HTMLDivElement>(null);
  /** 室内冷光层(灯闪时被压暗的「光源」)。 */
  const light = useRef<HTMLDivElement>(null);
  /** 入场灯闪整局只放一次;turn 切换时 runtime 会被 teardown,但**绝不补发**(放行标准 5)。 */
  const entered = useRef(false);

  const playFlicker = useCallback(() => {
    const b = blackout.current;
    const t = light.current;
    // reduced-motion:直接不放(静态确认由常亮的室内冷光与静态噪点承担)。
    if (!b || !t || reducedMotion()) return;
    // 生成文本期间不触发(放行标准 4);忙态锁挡住重复触发,不排队、不堆叠。
    if (generating || !runtime.tryLock()) return;

    const token = runtime.token;
    runtime.add(() => {
      // 前一拍:监控呼吸暂停 + 室内光压暗一线(0.5s)。
      setRootClass(styles.preBeat);
      gsap.to(t, { opacity: 0.85, duration: 0.4, ease: RULES.ease });
    });

    runtime.setTimeout(() => {
      setRootClass('');
      runtime.add(() => {
        gsap
          .timeline({
            onComplete: () => {
              if (!runtime.alive(token)) return;
              // 余韵:监控画面曝光偏移保持 10s,然后 0.15s 干脆复原(结束干脆 = RULES 时间感)。
              setRootClass(styles.afterFlicker);
              runtime.setTimeout(() => {
                setRootClass('');
                runtime.unlock();
              }, 10000);
            },
          })
          .set(b, { opacity: 0 })
          .to(b, { opacity: 0.6, duration: 0.05 }, 0.15)
          .to(t, { opacity: 0.15, duration: 0.05 }, '<')
          .to(b, { opacity: 0.08, duration: 0.07 })
          .to(t, { opacity: 1, duration: 0.07 }, '<')
          .to(b, { opacity: 0.45, duration: 0.05 })
          .to(t, { opacity: 0.25, duration: 0.05 }, '<')
          .to(b, { opacity: 0, duration: 0.6, ease: 'power2.out' })
          .to(t, { opacity: 1, duration: 0.6 }, '<');
      });
    }, 500);
  }, [runtime, generating, setRootClass]);

  // 入场灯闪:进入这个世界时放**一次**(Felix 2026-07-24 裁定 Q2 = 入场即放,
  // 与开场逐字 reveal 重叠;压暗不碰正文文字层)。整局只此一次,turn 切换不重放、不补发。
  useEffect(() => {
    if (entered.current) return;
    entered.current = true;
    runtime.setTimeout(playFlicker, 450);
    // 卸载/换 turn 由 runtime 统一 teardown 收走(§4.4),此处不另写清理。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <>
      <div className={styles.ambient} aria-hidden="true">
        <div className={styles.roomFill} />
        <div className={styles.roomLight} ref={light} />
        <div className={styles.crt} />
        <div className={styles.vignette} />
        {/* 压暗层:在氛围层内、内容之下 —— 正文文字永不参与压暗。 */}
        <div className={styles.blackout} ref={blackout} />
      </div>
      {isDebug() && (
        <button type="button" className={styles.debugBtn} onClick={playFlicker}>
          重播:入场灯闪(含前一拍 + 余韵)
        </button>
      )}
    </>
  );
}
