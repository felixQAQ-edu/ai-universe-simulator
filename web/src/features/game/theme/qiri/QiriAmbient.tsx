import { useCallback, useEffect, useRef } from 'react';
import type { AmbientProps } from '../contract';
import { isDebug } from '../debug';
import { trace } from '../telemetry';
import styles from './qiri.module.css';

// 《七日开张》· 氛围层:一块底色 + 一级记忆点「灯笼」的驱动(ADR-038 Slice G-2 决策 3)。
//
// ── 槽位(AGENTS.md Motion Constraints §2)──────────────────────────────────
//   持续     = 无(局内不加持续环境动效,顶图不冒热气);
//   低频偶发 = 灯笼(一次性:亮 → 停 → 熄,不循环)。本世界局内首个,无可替代(§3);
//   用户触发 = 数值滚动与珠子(同一个驱动量)+ 选项 / 返回键按压,不在本文件。
//
// ── 灯笼的驱动在这里,样子不在这里 ─────────────────────────────────────────
// 灯笼画在数值面板头(`QiriStats`)。本文件只在主题根上挂 / 摘 `lanternOn` class,
// `qiri.module.css` 的 `.lanternOn .lanternBody` 负责填色与过渡(Felix 2026-11-13 审定 1:接法乙,契约零改动;
// 同修仙「氛围层写主题根 class,形态层的 CSS 自己响应」)。两边都不 query 对方的 DOM。
//
// ── 触发 ─────────────────────────────────────────────────────────────────
// 熟客(`signatureAxisKey: 'regulars'`)**向上**跨档 → 通用层 `useSignalCrossing` 给 `signatureTick` + 1。
// 判定只此一处;本皮肤不读熟客的数值。续局首次装载不触发(通用层规矩 ②)。
// **不按 `generating` 抑制**:跨档由回合流 delta 落账给出,delta 恰好落在 generating 的尾巴上
// (修仙钟鸣、《派单》浮字踩过同一处)。
// 先后:灯先亮,数字与珠子 600ms 后才动(`valueRoll.ceremonyDelayMs`,通用层施加)。
//
// 忙态锁:亮着(含熄灭过渡)时再来一次直接忽略,不排队(AGENTS.md §5)。
// 换回合 / 卸载由 runtime 统一 teardown;根 class 按「皮肤 + 回合」作用域,换回合即作废,不补播。
// 减弱动效:照常亮和熄,只是没有过渡(CSS 侧)。

/** 亮多久再熄(ms)。Felix 定。 */
export const LANTERN_HOLD_MS = 2800;
/** 熄灭过渡(与 CSS `.lanternBody` 的 transition 同值);过完才解锁。 */
export const LANTERN_FADE_MS = 900;

const TRACE = 'qiri.lantern';

export function QiriAmbient({ runtime, signatureTick, setRootClass }: AmbientProps) {
  const light = useCallback(() => {
    if (!runtime.tryLock()) {
      trace(TRACE, { suppressedReason: 'busy(上一次尚未收尾)', state: 'suppressed' });
      return;
    }
    trace(TRACE, { scheduledAt: now(), firedAt: now(), state: 'on', suppressedReason: undefined }, true);
    // 不另登记 teardown 去摘 class:根 class 自带「皮肤 + 回合」作用域,换回合即作废(GameScreen)。
    setRootClass(styles.lanternOn);

    runtime.setTimeout(() => {
      setRootClass('');
      trace(TRACE, { state: 'off' });
    }, LANTERN_HOLD_MS);

    runtime.setTimeout(() => {
      runtime.unlock();
      trace(TRACE, { completedAt: now(), state: 'done' });
    }, LANTERN_HOLD_MS + LANTERN_FADE_MS);
  }, [runtime, setRootClass]);

  // 触发口:签名轴向上跨档。tick 首次装载恒为 0;用 ref 持有最新 light,依赖只挂 tick。
  const lightRef = useRef(light);
  useEffect(() => {
    lightRef.current = light;
  }, [light]);

  useEffect(() => {
    if (signatureTick === 0) return;
    lightRef.current();
  }, [signatureTick]);

  return (
    <>
      <div className={styles.ambient} aria-hidden="true" />
      {isDebug() && (
        <button type="button" className={styles.debugBtn} onClick={() => light()}>
          重播:灯笼亮一下
        </button>
      )}
    </>
  );
}

const now = () => Math.round(performance.now());
