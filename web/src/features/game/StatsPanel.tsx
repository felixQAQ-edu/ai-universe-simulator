import type { AttributeAxisMeta } from '../../api';
import { resolveBandLabel, resolveSeverity } from './bands';
import { severityWord, useSkin, type AxisView } from './theme/contract';
import styles from './game.module.css';

// 数值面板 = **通用层**(ADR-018 §4.2)。职责严格限于:
//   遍历 axes → 匹配当前 band → 取 severity → 输出可访问文本 → 交给形态组件渲染。
// 对数值 key 无知(ADR-008 多模式):规则怪谈 体力/理智、末日 体力/补给、修仙 气血/灵力/境界,同一层通吃。
//
// **形态**由主题层决定(登记了皮肤 → 该世界的形态;未登记 → 下面的 LegacyStats,
// 与刀 1 之前逐字一致 = feature gate 的降级路径)。
// 守 ADR-003 边界:纯展示层,无平台 IO;只渲染消毒后的数值,永不接触 isTrue/hiddenLogic。
export function StatsPanel({
  axes,
  values,
}: {
  axes: AttributeAxisMeta[];
  values: Record<string, number>;
}) {
  const bundle = useSkin();
  const views = axes.map((axis) => toAxisView(axis, values));
  if (!bundle) return <LegacyStats axes={views} />;
  const Form = bundle.skin.Stats;
  return <Form axes={views} runtime={bundle.runtime} />;
}

/** 通用层的全部计算:一根轴 → 主题层能用的一切(不多给一分语义原料)。 */
function toAxisView(axis: AttributeAxisMeta, values: Record<string, number>): AxisView {
  const value = Number(values[axis.key] ?? 0);
  const bandLabel = resolveBandLabel(value, axis.bands);
  const severity = resolveSeverity(value, axis.bands);
  const risk = severityWord(severity);
  return {
    key: axis.key,
    displayName: axis.displayName,
    value,
    percent: clamp(value),
    bandLabel,
    severity,
    a11yText: [axis.displayName, String(value), bandLabel, risk].filter(Boolean).join(' · '),
  };
}

/**
 * 旧形态(未登记皮肤的世界走这条,刀 1 之前的样子逐字保留)。
 * 注意 barClass 是**按轴 key 硬编码配色**——它正是 ADR-018 §4.2 要被 severity 取代的东西,
 * 但只在这条**旧路径**上留存(登记了皮肤的世界不经过它);刀 2–4 各世界登记后随之退场。
 */
function LegacyStats({ axes }: { axes: AxisView[] }) {
  return (
    <div className={styles.stats}>
      {axes.map((axis) => (
        <div className={styles.stat} key={axis.key}>
          <span className={styles.statKey}>{axis.displayName}</span>
          <span className={styles.statValRow}>
            <span className={styles.statVal}>{axis.value}</span>
            {axis.bandLabel && <span className={styles.statBand}>{axis.bandLabel}</span>}
          </span>
          <div className={styles.bar}>
            <div
              className={`${styles.barFill} ${barClass(axis.key)}`}
              style={{ width: `${axis.percent}%` }}
            />
          </div>
        </div>
      ))}
    </div>
  );
}

/** 数值轴 key → 进度条配色(已知轴专属色,未知轴回落中性色)。**仅旧形态使用**。 */
function barClass(key: string): string {
  if (key === 'hp') return styles.barHp;
  if (key === 'san') return styles.barSan;
  if (key === 'hunger') return styles.barHunger;
  if (key === 'knowledge') return styles.barKnowledge;
  if (key === 'mana') return styles.barMana;
  if (key === 'realm') return styles.barRealm;
  return styles.barDefault;
}

const clamp = (n: number) => Math.max(0, Math.min(100, n));
