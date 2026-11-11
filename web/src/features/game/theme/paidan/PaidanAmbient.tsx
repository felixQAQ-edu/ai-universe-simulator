import { useCallback, useEffect, useRef, useState } from 'react';
import type { AmbientProps } from '../contract';
import { isDebug } from '../debug';
import { trace } from '../telemetry';
import styles from './paidan.module.css';

// 《派单》· 氛围层:夜里的底色 + 一级记忆点「义眼投影浮字」(ADR-036 Slice G-2)。
//
// ── 槽位(AGENTS.md Motion Constraints §2)──────────────────────────────────
//   持续     = 积水反光 —— **不在本文件**,是挂在顶部场景图上的纯 CSS(skins.tsx `bannerImgClass`);
//   低频偶发 = 本文件的浮字(一次性:浮出 → 停留 → 淡出,不循环);
//   两者不是同一个槽,无需互斥。
//
// ── 触发 ─────────────────────────────────────────────────────────────────
// 画像(`signatureAxisKey: 'profile'`)**向上**跨档 → 通用层 `useSignalCrossing` 给 `signatureTick` + 1。
// 这发生在**第 N 回合落账**那一刻(回合流的 delta 到达);服务端订正三的同一句话在**第 N+1 回合**叙事开头出现。
// 两层都留(Felix 2026-11-11 裁定 3)。续局首次装载不触发(通用层规矩 ②)。
//
// **不按 `generating` 抑制**:delta 恰好落在 generating 的尾巴上,按它拒绝 = 这句话永远不出现
// (修仙钟鸣踩过同一处,见 XianAmbient 头注释)。
//
// ── 形态 ─────────────────────────────────────────────────────────────────
// 视口固定层上缘(裁定 2:跨档时玩家多半已滚到选项处,顶图不在视口里)、`pointer-events: none`、
// 停留约 2.8 秒后自动淡出(裁定补充 2)。reduced-motion 下照常出现与消失,只是不位移不淡入淡出(CSS 侧)。
// 忙态锁:浮字在场时再来一次 tick 直接忽略,不排队、不叠加(AGENTS.md §5)。
// 换回合 / 卸载由 runtime 统一 teardown,浮字立刻收起,**不补播**。

/** 浮出后停留多久开始淡出(ms)。默认值,Felix 看过再调。 */
export const HUD_DWELL_MS = 2800;
/** 淡出时长(与 CSS `.hudOff` 的 transition 同值)。 */
export const HUD_FADE_MS = 900;
export const HUD_LINE = '已为你优化时限。';

const TRACE = 'paidan.hud';

type Phase = 'idle' | 'on' | 'off';

export function PaidanAmbient({ runtime, signatureTick, setRootClass }: AmbientProps) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [debugMode, setDebugMode] = useState<'' | 'fast' | 'still'>('');

  const playHud = useCallback(() => {
    // 忙态锁:上一次还没收尾 → 忽略(不排队、不堆叠)。
    if (!runtime.tryLock()) {
      trace(TRACE, { suppressedReason: 'busy(上一次尚未收尾)', state: 'suppressed' });
      return;
    }
    trace(TRACE, { scheduledAt: now(), firedAt: now(), state: 'on', suppressedReason: undefined }, true);
    setPhase('on');
    runtime.onTeardown(() => setPhase('idle'));

    runtime.setTimeout(() => {
      setPhase('off');
      trace(TRACE, { state: 'off' });
    }, HUD_DWELL_MS);

    runtime.setTimeout(() => {
      setPhase('idle');
      runtime.unlock();
      trace(TRACE, { completedAt: now(), state: 'done' });
    }, HUD_DWELL_MS + HUD_FADE_MS);
  }, [runtime]);

  // 触发口:签名轴向上跨档。tick 首次装载恒为 0;用 ref 持有最新 playHud,依赖只挂 tick。
  const playRef = useRef(playHud);
  useEffect(() => {
    playRef.current = playHud;
  }, [playHud]);

  useEffect(() => {
    if (signatureTick === 0) return;
    playRef.current();
  }, [signatureTick]);

  // debug 开关写的是主题根上的瞬时 class(倍速 / 静帧),只影响积水反光的展示,不碰任何数据。
  useEffect(() => {
    if (!debugMode) return;
    setRootClass(debugMode === 'fast' ? styles.debugFast : styles.debugStill);
    return () => setRootClass('');
  }, [debugMode, setRootClass]);

  const hudClass = [
    styles.hud,
    phase === 'on' ? styles.hudOn : '',
    phase === 'off' ? styles.hudOff : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <>
      <div className={styles.ambient} aria-hidden="true" />
      {/* 浮字常驻 DOM、靠 class 显隐 —— 这样「浮出」才有过渡可走。读屏用 status 播一次。 */}
      <div className={hudClass} role="status" aria-live="polite" data-phase={phase}>
        {phase !== 'idle' && (
          <div className={styles.hudPlate}>
            <span className={styles.hudTag}>义眼 · 派单</span>
            {HUD_LINE}
          </div>
        )}
      </div>
      {isDebug() && (
        <>
          <button type="button" className={styles.debugBtn} onClick={() => playHud()}>
            重播:义眼投影浮字「{HUD_LINE}」
          </button>
          <button
            type="button"
            className={styles.debugBtn}
            onClick={() => setDebugMode((m) => (m === 'fast' ? '' : 'fast'))}
          >
            积水反光:{debugMode === 'fast' ? '4 倍速(再点恢复)' : '正常速度(点一下 4 倍速)'}
          </button>
          <button
            type="button"
            className={styles.debugBtn}
            onClick={() => setDebugMode((m) => (m === 'still' ? '' : 'still'))}
          >
            积水反光:{debugMode === 'still' ? '静帧(= reduced-motion 的样子,再点恢复)' : '在动(点一下看静帧)'}
          </button>
        </>
      )}
    </>
  );
}

const now = () => Math.round(performance.now());
