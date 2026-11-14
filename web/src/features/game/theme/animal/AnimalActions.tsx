import type { ActionsProps } from '../contract';
import styles from './animal.module.css';

// 《动物人生》· 决策圈形态(ADR-021 局内皮肤一节 决策 4)。
// 一块细框面板里几行,行与行之间一道 1px 线;每行:编号小字 | 正文 + hint。不画爪印。
//
// **带 tag 的选项在右侧显示小签**(ADR-039 决策 5:自带决策圈的皮肤必须显示 tag)。
// 现在服务端没有给动物人生登记 tag;登记之后这里原样显示,所有 tag 同一种样式,不按内容区分。
// 没带 tag 时该节点不渲染,行也不多出第三栏。
//
// 语义与通用 DecisionCircle 一致:只回传 id、hint 是叙事元数据(ADR-011)。
export function AnimalActions({ actions, disabled, onChoose }: ActionsProps) {
  return (
    <div className={styles.opts}>
      {actions.map((a) => (
        <button
          key={a.id}
          type="button"
          className={a.tag ? `${styles.opt} ${styles.optTagged}` : styles.opt}
          disabled={disabled}
          onClick={() => onChoose(a.id)}
        >
          <span className={styles.optNo}>{a.id}</span>
          <span className={styles.optBody}>
            <span className={styles.optText}>{a.text}</span>
            {a.hint && <span className={styles.optHint}>{a.hint}</span>}
          </span>
          {a.tag && <span className={styles.optTag}>{a.tag}</span>}
        </button>
      ))}
    </div>
  );
}
