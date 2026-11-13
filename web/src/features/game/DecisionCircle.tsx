import type { ClientAction } from '../../api';
import styles from './game.module.css';

// 决策圈:2–4 个可选行动,玩家只能选 id(不开自由文本,CONTEXT 术语表「决策圈」)。
export function DecisionCircle({
  actions,
  disabled,
  onChoose,
}: {
  actions: ClientAction[];
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
            {/* 选项小签(ADR-039):服务端下发的展示文字,原样显示;所有 tag 同一种样式,不按内容区分。
                没带 tag 时这个节点不渲染,DOM 与加这一行之前逐字节相同(DecisionCircle.test 有基线)。 */}
            {a.tag && <span className={styles.actionTag}>{a.tag}</span>}
            <span className={styles.actionText}>{a.text}</span>
          </span>
          {/* 定性风险提示(ADR-011):选项文字下方独立小字,缺失时不渲染、不占位。纯叙事元数据,非成功率。 */}
          {a.hint && <span className={styles.actionHint}>{a.hint}</span>}
        </button>
      ))}
    </div>
  );
}
