import { useCallback, useEffect, useRef } from 'react';
import gsap from 'gsap';
import type { AmbientProps } from '../contract';
import { isDebug } from '../debug';
import { RULES, reducedMotion } from '../motion';
import { trace } from '../telemetry';
import styles from './rules.module.css';

// 规则怪谈 · 氛围层「值班室冷灯」+ 一级记忆点「灯闪」。
//
// ── 承载表面(第二版,Felix 2026-07-24 二分后重做)────────────────────────────
// 第一版把压暗层压在内容之下,实测:顶部场景图(视口上 47%)透过率 **0**、
// OSD 面板 0.12、正文面板 0.18,全屏平均 0.201,只有 13% 面积(面板间隙细条)真的在暗。
// 「正文不受影响」与「整屏可感」在同一层叠顺序里无解 —— **换表面,不是加强度**:
//
//   主表面 = **顶部场景图**(它就是这个世界的房间;不透明、占屏最大、永远在视线起点)
//          + **氛围层**(间隙与边缘,补全「整个房间」的感觉)
//   轻表面 = OSD 面板,只接受极轻曝光波动(数值面板是仪器,跟着灯走一点点)
//   不动   = **正文与选项**(不改文字 opacity、不加滤镜)——阅读绝对稳定
//
// 编排方式:GSAP 只写**主题根上的 CSS 自定义属性**,各层 CSS 自己消费。
// 于是一条时间线同时驱动场景图/氛围/面板,而氛围层不必去 query 别人的 DOM。
//
// ── 三拍不对称(不做等长两闪)──────────────────────────────────────────────
//   ① 电压下降   220ms 缓慢压暗到 0.88(先让人「觉得哪里不太对」)
//   ② 短促深暗落  70ms 掉到 0.30 并保持 50ms(更短、更深,不与①对称)
//   ③ 恢复偏移   110ms 冲到 1.10 且偏冷偏灰(色温不对),再 6s 慢慢回稳
//
// 动效记账(AGENTS.md §2/§3):持续 1 = 监控呼吸(挂顶部场景图,**替代样板间那块独立
// CCTV 面板**)/ 低频 1 = OSD 扫描线 / 用户触发 1 = 规则点亮 + 选项阶跃;灯闪一次性不占槽。

/** 三拍时间轴(ms),集中在一处便于按体感调。 */
const SAG_MS = 220;
const DROP_MS = 70;
const DROP_HOLD_MS = 50;
const RECOVER_MS = 110;
const SETTLE_MS = 6000;
/** 场景进入后先静一拍,再开始(不是一进门就闪)。 */
const LEAD_IN_MS = 600;
/** 灯闪收尾到放行正文之间的余韵(串行,ADR-018 §4.7)。 */
const AFTERGLOW_MS = 240;

const TRACE = 'rules.flicker';

export function RulesAmbient({
  runtime,
  rootRef,
  generating,
  setRootClass,
  onIntroDone,
}: AmbientProps) {
  /**
   * 入场序列的进度。**用「已开演/已演完」而不是「已排期过」当守卫** —— 这是刀 1 冒烟
   * 「灯闪到底有没有发生」的第二个真因:React StrictMode 在开发模式下会
   * 挂载 → 清理 → 再挂载,中间那次清理会 `runtime.teardown()` 清掉已排期的定时器;
   * 若守卫记的是「排过期了」,第二次挂载就直接跳过 —— **本地开发下入场序列永远不会播**。
   * 记「有没有真的开演」则第二次挂载会重新排期,而 turn 切换不会重排(effect 依赖不变)。
   */
  const intro = useRef<'idle' | 'running' | 'done'>('idle');
  const introReleased = useRef(false);

  const releaseIntro = useCallback(() => {
    if (introReleased.current) return;
    introReleased.current = true;
    onIntroDone();
  }, [onIntroDone]);

  const playFlicker = useCallback(
    (isIntro: boolean) => {
      const root = rootRef.current;
      const suppress = (reason: string) => {
        trace(TRACE, { suppressedReason: reason, state: 'suppressed' });
        if (isIntro) {
          intro.current = 'done'; // 抑制也算「这一局的入场交代过了」,不再重排
          releaseIntro(); // 抑制也要放行正文,绝不把叙事卡住
        }
      };
      if (!root) return suppress('主题根未挂载');
      // reduced-motion:不闪,静态氛围照旧(AGENTS.md §5)。
      if (reducedMotion()) return suppress('reduced-motion');
      // 生成文本期间不触发(放行标准 4);忙态锁挡重复触发,不排队、不堆叠。
      if (generating) return suppress('generating');
      if (!runtime.tryLock()) return suppress('busy(上一次尚未收尾)');

      const token = runtime.token;
      if (isIntro) intro.current = 'running';
      trace(TRACE, { firedAt: now(), state: 'sag', suppressedReason: undefined });
      setRootClass(styles.preBeat); // 监控呼吸暂停(前一拍)
      trace(TRACE, { activeClass: 'preBeat' });

      runtime.onTeardown(() => {
        // 被打断(换 turn / 卸载):变量收回,不留半暗的房间;不补发、不重排。
        clearVars(root);
        setRootClass('');
        if (isIntro) {
          intro.current = 'done';
          releaseIntro();
        }
      });

      runtime.add(() => {
        gsap
          .timeline({
            onComplete: () => {
              if (!runtime.alive(token)) return;
              trace(TRACE, { completedAt: now(), state: 'settle', activeClass: '' });
              setRootClass('');
              runtime.setTimeout(() => {
                if (isIntro) {
                  intro.current = 'done';
                  releaseIntro();
                }
                runtime.unlock();
                trace(TRACE, { state: 'done' });
              }, AFTERGLOW_MS);
            },
          })
          // ① 电压下降(慢)
          .to(root, {
            '--fl-b': 0.88,
            '--fl-light': 0.9,
            duration: SAG_MS / 1000,
            ease: 'power1.inOut',
            onStart: () => trace(TRACE, { state: 'sag' }),
          })
          // ② 短促深暗落(快、深、不与①对称)
          .to(root, {
            '--fl-b': 0.3,
            '--fl-light': 0.12,
            '--fl-black': 0.55,
            '--fl-osd': 0.9,
            duration: DROP_MS / 1000,
            ease: RULES.ease, // 线性:机械、戛然
            onStart: () => trace(TRACE, { state: 'drop' }),
          })
          .to({}, { duration: DROP_HOLD_MS / 1000 })
          // ③ 恢复偏移(亮度过冲 + 色温偏冷偏灰)
          .to(root, {
            '--fl-b': 1.1,
            '--fl-c': 0.94,
            '--fl-s': 0.82,
            '--fl-light': 1.06,
            '--fl-black': 0,
            '--fl-osd': 1,
            duration: RECOVER_MS / 1000,
            ease: 'power2.out',
            onStart: () => trace(TRACE, { state: 'recover' }),
          })
          // 慢慢回稳(余韵在氛围层,不拦正文)
          .to(root, {
            '--fl-b': 1,
            '--fl-c': 1,
            '--fl-s': 1,
            '--fl-light': 1,
            duration: SETTLE_MS / 1000,
            ease: 'power2.out',
          });
      });
    },
    [runtime, rootRef, generating, setRootClass, releaseIntro],
  );

  // 最新的播放函数放 ref,让下面的入场 effect 只依赖 runtime(不因 generating 变化而重排)。
  const playRef = useRef(playFlicker);
  useEffect(() => {
    playRef.current = playFlicker;
  }, [playFlicker]);

  // 入场:场景进入 → 短暂稳定 → 灯闪 → 余韵 → 放行正文逐字(**串行**,ADR-018 §4.7)。
  // 依赖只有 runtime(跨 turn 身份稳定)⇒ 换回合不会重排;StrictMode 重挂载会重排(见 intro 守卫)。
  useEffect(() => {
    if (intro.current !== 'idle') return;
    trace(TRACE, { scheduledAt: now(), state: 'scheduled' }, true);
    runtime.setTimeout(() => playRef.current(true), LEAD_IN_MS);
    // 卸载/换 turn 由 runtime 统一 teardown 收走(§4.4),此处不另写清理。
  }, [runtime]);

  return (
    <>
      <div className={styles.ambient} aria-hidden="true">
        <div className={styles.roomFill} />
        <div className={styles.roomLight} />
        <div className={styles.crt} />
        <div className={styles.vignette} />
        {/* 压暗层只补氛围(间隙与边缘);主表面是顶部场景图,见文件头。 */}
        <div className={styles.blackout} />
      </div>
      {isDebug() && (
        <button type="button" className={styles.debugBtn} onClick={() => playFlicker(false)}>
          重播:灯闪(三拍 + 回稳)
        </button>
      )}
    </>
  );
}

const now = () => Math.round(performance.now());

/** 把这次编排写进根上的自定义属性清掉(GSAP 之外的兜底,teardown 时用)。 */
function clearVars(root: HTMLElement): void {
  for (const v of ['--fl-b', '--fl-c', '--fl-s', '--fl-light', '--fl-black', '--fl-osd']) {
    root.style.removeProperty(v);
  }
}
