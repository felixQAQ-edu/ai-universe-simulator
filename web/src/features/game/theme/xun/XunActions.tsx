import type { ActionsProps } from '../contract';
import styles from './xun.module.css';

// 《寻常》· 决策圈形态「几行字」(ADR-020「《寻常》局内皮肤」一节决策 4)。
// 一行一个选项,行间一道很淡的线,没有框;两栏:编号 | 正文 + hint。按下去字慢慢变淡(600ms,CSS)。
//
// 语义与通用 DecisionCircle 一致:只回传 id、hint 是叙事元数据(ADR-011)。
// ADR-039 的 `tag` 本世界没有登记、服务端不会下发,这里不渲染。
export function XunActions({ actions, disabled, onChoose }: ActionsProps) {
  return (
    <div className={styles.opts}>
      {actions.map((a) => (
        <button
          key={a.id}
          type="button"
          className={styles.opt}
          disabled={disabled}
          onClick={() => onChoose(a.id)}
        >
          <span className={styles.optNo}>{a.id}</span>
          <span className={styles.optBody}>
            <span>{a.text}</span>
            {a.hint && <span className={styles.optHint}>{a.hint}</span>}
          </span>
        </button>
      ))}
    </div>
  );
}
