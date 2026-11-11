import type { ActionsProps } from '../contract';
import styles from './paidan.module.css';

// 《派单》· 决策圈形态「接单卡」(ADR-036 Slice G-2)。
// 青细框、方角、编号用评分青;按压 = 接单确认(青框亮起、轻压,用户触发槽)。
//
// 语义与通用 DecisionCircle 完全一致:只回传 id、hint 是叙事元数据(ADR-011),
// 主题层只换形态、不改交互语义。
export function PaidanActions({ actions, disabled, onChoose }: ActionsProps) {
  return (
    <div className={styles.orders}>
      {actions.map((a) => (
        <button
          key={a.id}
          type="button"
          className={styles.order}
          disabled={disabled}
          onClick={() => onChoose(a.id)}
        >
          <span className={styles.orderNo}>{a.id}.</span>
          <span className={styles.orderBody}>
            <span className={styles.orderText}>{a.text}</span>
            {/* 定性风险提示(ADR-011):缺失时不渲染、不占位。 */}
            {a.hint && <span className={styles.orderHint}>{a.hint}</span>}
          </span>
        </button>
      ))}
    </div>
  );
}
