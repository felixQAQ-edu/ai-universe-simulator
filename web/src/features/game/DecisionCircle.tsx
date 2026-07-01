import type { AvailableAction } from '../../types/schema';
import styles from './game.module.css';

// 决策圈:2–4 个可选行动,玩家只能选 id(不开自由文本,CONTEXT 术语表「决策圈」)。
export function DecisionCircle({
  actions,
  disabled,
  onChoose,
}: {
  actions: AvailableAction[];
  disabled: boolean;
  onChoose: (id: string) => void;
}) {
  return (
    <div className={styles.actions}>
      {actions.map((a) => (
        <button
          key={a.id}
          type="button"
          className={styles.action}
          disabled={disabled}
          onClick={() => onChoose(a.id)}
        >
          <span className={styles.actionMain}>
            <span className={styles.actionId}>{a.id}.</span>
            <span className={styles.actionText}>{a.text}</span>
          </span>
          {/* 定性风险提示(ADR-011):选项文字下方独立小字,缺失时不渲染、不占位。纯叙事元数据,非成功率。 */}
          {a.hint && <span className={styles.actionHint}>{a.hint}</span>}
        </button>
      ))}
    </div>
  );
}
