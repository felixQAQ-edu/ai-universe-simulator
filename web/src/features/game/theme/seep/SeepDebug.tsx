import { useState } from 'react';
import type { AttributeAxisMeta } from '../../../../api';
import { isDebug } from '../debug';
import type { SeepEntry, SeepSignals } from './registry';
import styles from './seep.module.css';

// 渗入层的调试控件(ADR-043 挂账项,Felix 2026-11-21 要求现在做掉):**仅 `?debug=1`**,供真机看三件渗入效果。
// 纪律同 debug.ts:不写任何持久状态、不改生产数据 —— 三个按钮只改**这一层自己的展示输入**:
//   · 窥天切档:覆盖墨洇吃到的档序号(数值区与正文两层墨渍同步),**不改 store 里的数值**,面板上的窥天数字不动;
//   · 重压印:给验讫印的 tick 加一 —— 走的是真实路径(藏起 → afterCrossingMs → 压下),境界在初境时印本来就不在,按了也不出现;
//   · 触发字洇:让正文立刻演一次字洇(不论清明在哪档),仍守逐字期间不演、reduced-motion 下不演。
// 覆盖从第一次点「窥天切档」起生效,之后跟随实际值要刷新页面(调试期够用,不另做「还原」)。
// 非 debug 下三个状态恒为初值,下游拿到的输入与没有本文件时逐值相同。

export interface SeepDebug {
  /** 墨洇吃到的信号(覆盖了档序号时换成覆盖值)。 */
  signals: SeepSignals;
  /** 加到验讫印 tick 上的调试次数。 */
  stampNonce: number;
  /** 正文字洇的手动触发计数(变了 = 立刻演一次)。 */
  bleedNonce: number;
  /** 按钮行;非 debug / 未登记渗入时为 null。 */
  controls: React.ReactNode;
}

export function useSeepDebug(
  seep: SeepEntry | null,
  axes: readonly AttributeAxisMeta[],
  signals: SeepSignals,
): SeepDebug {
  const [stainOverride, setStainOverride] = useState<number | null>(null);
  const [stampNonce, setStampNonce] = useState(0);
  const [bleedNonce, setBleedNonce] = useState(0);

  const stainAxis = seep?.stain ? axes.find((a) => a.key === seep.stain!.axisKey) : undefined;
  const bands = stainAxis?.bands ?? [];
  const level = stainOverride ?? signals.stainLevel;
  const shownSignals =
    stainOverride === null ? signals : { ...signals, stainLevel: stainOverride };

  const controls =
    isDebug() && seep ? (
      <div className={styles.debugRow} data-seep="debug">
        {seep.stain && bands.length > 0 && (
          <button
            type="button"
            className={styles.debugBtn}
            onClick={() => setStainOverride(((level ?? -1) + 1) % bands.length)}
          >
            {stainAxis!.displayName}切档:{bandName(bands, level)} → {bandName(bands, ((level ?? -1) + 1) % bands.length)}
          </button>
        )}
        {seep.stamp && (
          <button type="button" className={styles.debugBtn} onClick={() => setStampNonce((n) => n + 1)}>
            重压印
          </button>
        )}
        {seep.bleed && (
          <button type="button" className={styles.debugBtn} onClick={() => setBleedNonce((n) => n + 1)}>
            触发字洇
          </button>
        )}
      </div>
    ) : null;

  return { signals: shownSignals, stampNonce, bleedNonce, controls };
}

function bandName(bands: AttributeAxisMeta['bands'], i: number | null): string {
  return (i !== null && bands?.[i]?.label) || '—';
}
