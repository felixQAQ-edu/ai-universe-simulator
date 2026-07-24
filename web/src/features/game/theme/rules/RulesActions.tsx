import type { ActionsProps } from '../contract';
import styles from './rules.module.css';

// 规则怪谈 · 决策圈形态「值班登记表条目」(样板间冻结成果移植)。
// 方角零圆角 + 等宽编号列 + 竖分隔线 + 行尾 □(按下变 ■);反馈是**阶跃**、无缓动
// (`--t-dur`/`--t-ease` = 0.09s/linear,规则怪谈的时间感:准时、机械、戛然而止)。
//
// 语义与通用 DecisionCircle 完全一致:只回传 id、hint 是叙事元数据(ADR-011),
// 主题层只换形态、不改交互语义。

export function RulesActions({ actions, disabled, onChoose }: ActionsProps) {
  return (
    <div className={styles.duties}>
      {actions.map((a) => (
        <button
          key={a.id}
          type="button"
          className={styles.duty}
          disabled={disabled}
          onClick={() => onChoose(a.id)}
        >
          <span className={styles.dutyNo}>{a.id}</span>
          <span className={styles.dutyBody}>
            <span className={styles.dutyText}>{a.text}</span>
            {/* 定性风险提示(ADR-011):缺失时不渲染、不占位。 */}
            {a.hint && <span className={styles.dutyHint}>{a.hint}</span>}
          </span>
          <span className={styles.dutyBox} aria-hidden="true" />
        </button>
      ))}
    </div>
  );
}
