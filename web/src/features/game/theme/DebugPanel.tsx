import type { AttributeAxisMeta } from '../../../api';
import { resolveBandLabel, resolveSeverity } from '../bands';
import { isDebug } from './debug';
import type { WorldTheme } from './registry';
import styles from './debug.module.css';

// debug 面板(ADR-018 §5 Q7):**仅 `?debug=1` 可见,普通路径连一个像素都不渲染**。
// 只读:展示这一局解析出的主题登记、停表状态、以及每根轴当前档与 severity ——
// **不写任何持久状态、不改任何生产数据**(纯查看;皮肤自己的重播控件由各皮肤在自己的氛围层里给)。
export function DebugPanel({
  theme,
  axes,
  values,
  paused,
}: {
  theme: WorldTheme;
  axes: AttributeAxisMeta[];
  values: Record<string, number>;
  paused: boolean;
}) {
  if (!isDebug()) return null;
  return (
    <aside className={styles.panel}>
      <p className={styles.line}>
        <b>DEBUG</b> · 世界键 {theme.key || '(未登记)'} · 皮肤 {theme.skin ?? '旧实现'} · 封面{' '}
        {theme.sceneUrl ?? '无'}
      </p>
      <p className={styles.line}>低频调度:{paused ? '停表(正文不稳定期)' : '运行'}</p>
      {axes.map((axis) => {
        const v = Number(values[axis.key] ?? 0);
        return (
          <p className={styles.line} key={axis.key}>
            {axis.displayName}({axis.key}) = {v} · 档 {resolveBandLabel(v, axis.bands) ?? '—'} ·
            severity {resolveSeverity(v, axis.bands) ?? 'null(安全降级)'}
          </p>
        );
      })}
    </aside>
  );
}
