import type { AttributeAxisMeta } from '../../api';
import styles from './game.module.css';

// 数值面板:按 init 下发的数值轴元数据动态渲染(ADR-008 多模式)——key + 中文名 + 当前绝对值。
// 对数值 key 无知:规则怪谈 体力/理智、末日 体力/饥饿,同一组件通吃。0–100,配进度条直观化。
// 守 ADR-003 边界:纯展示层,无平台 IO;只渲染消毒后的数值,永不接触 isTrue/hiddenLogic。
export function StatsPanel({
  axes,
  values,
}: {
  axes: AttributeAxisMeta[];
  values: Record<string, number>;
}) {
  return (
    <div className={styles.stats}>
      {axes.map((axis) => {
        const value = Number(values[axis.key] ?? 0);
        return (
          <div className={styles.stat} key={axis.key}>
            <span className={styles.statKey}>{axis.displayName}</span>
            <span className={styles.statVal}>{value}</span>
            <div className={styles.bar}>
              <div
                className={`${styles.barFill} ${barClass(axis.key)}`}
                style={{ width: `${clamp(value)}%` }}
              />
            </div>
          </div>
        );
      })}
    </div>
  );
}

/** 数值轴 key → 进度条配色(已知轴专属色,未知轴回落中性色)。 */
function barClass(key: string): string {
  if (key === 'hp') return styles.barHp;
  if (key === 'san') return styles.barSan;
  if (key === 'hunger') return styles.barHunger;
  return styles.barDefault;
}

const clamp = (n: number) => Math.max(0, Math.min(100, n));
