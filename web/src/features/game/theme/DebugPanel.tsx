import { useSyncExternalStore } from 'react';
import type { AttributeAxisMeta } from '../../../api';
import { resolveBandIndex, resolveBandLabel, resolveSeverity } from '../bands';
import { isDebug } from './debug';
import type { WorldTheme } from './registry';
import { readTraces, subscribeTraces } from './telemetry';
import type { SignatureSignal } from './useSignalCrossing';
import styles from './debug.module.css';

// debug 面板(ADR-018 §5 Q7):**仅 `?debug=1` 可见,普通路径连一个像素都不渲染**。
// 只读:主题登记 / 停表状态 / 每根轴当前档与 severity / **效果时序遥测**。
// 不写任何持久状态、不改任何生产数据(皮肤自己的重播控件由各皮肤在自己的氛围层里给)。
//
// 时序遥测是刀 1 冒烟的教训:一级记忆点「到底有没有发生」当时无法判断,只能凭感觉猜。
// 现在能直接读到 排期 / 触发 / 完成 / 被谁抑制 —— 二分,而不是改强度。
export function DebugPanel({
  theme,
  axes,
  values,
  paused,
  signature,
}: {
  theme: WorldTheme;
  axes: AttributeAxisMeta[];
  values: Record<string, number>;
  paused: boolean;
  /** 签名轴跨档读数(ADR-018 §5 Q5);未接线 / 未配置签名轴时不显这一行。 */
  signature?: SignatureSignal;
}) {
  const debug = isDebug();
  // 外部可变存储走 useSyncExternalStore(快照引用只在有变更时换新,见 telemetry.ts)。
  const traces = useSyncExternalStore(subscribeTraces, readTraces);

  if (!debug) return null;
  return (
    <aside className={styles.panel}>
      <p className={styles.line}>
        <b>DEBUG</b> · 世界键 {theme.key || '(未登记)'} · 皮肤 {theme.skin ?? '旧实现'} · 封面{' '}
        {theme.sceneUrl ?? '无'}
      </p>
      <p className={styles.line}>低频调度:{paused ? '停表(正文不稳定期)' : '运行'}</p>
      {signature?.axisKey && (
        <p className={styles.line}>
          签名轴 {signature.axisKey} · 上一次档 {idx(signature.prevIndex)} → 当前档{' '}
          {idx(signature.index)} · 判定{' '}
          {crossing(signature.prevIndex, signature.index)} · 向上跨档累计 {signature.tick} 次
        </p>
      )}
      {axes.map((axis) => {
        const v = Number(values[axis.key] ?? 0);
        return (
          <p className={styles.line} key={axis.key}>
            {axis.displayName}({axis.key}) = {v} · 档 {resolveBandLabel(v, axis.bands) ?? '—'}(序号{' '}
            {idx(resolveBandIndex(v, axis.bands))}) · severity{' '}
            {resolveSeverity(v, axis.bands) ?? 'null(安全降级)'}
          </p>
        );
      })}
      {traces.map((t) => (
        <p className={styles.line} key={t.name}>
          {t.name} · scheduled {ms(t.scheduledAt)} · fired {ms(t.firedAt)} · completed{' '}
          {ms(t.completedAt)} · state {t.state ?? '—'} · class {t.activeClass || '—'} · suppressed{' '}
          {t.suppressedReason ?? '—'}
        </p>
      ))}
    </aside>
  );
}

const ms = (v?: number) => (typeof v === 'number' ? `${v}ms` : '—');

const idx = (v: number | null) => (typeof v === 'number' ? String(v) : '—');

/** 跨档判定的人话版(读数二分用:能直接看到「为什么没鸣」)。 */
function crossing(prev: number | null, now: number | null): string {
  if (prev === null || now === null) return '不判(无前值 / 无档表)';
  if (now > prev) return '向上跨档 ✔';
  if (now < prev) return '下跌(不鸣)';
  return '同档(不鸣)';
}
