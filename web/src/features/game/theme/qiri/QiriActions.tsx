import type { ActionsProps } from '../contract';
import styles from './qiri.module.css';

// 《七日开张》· 决策圈形态「细框条目」(ADR-038 Slice G-2 决策 4)。
// 三栏:编号(1px 方框里)| 正文 + hint | 小签。
//
// **带 tag 的选项在右上显示小签**(ADR-039 决策 5 立的验收项:自带决策圈的世界必须显示 tag)。
// tag 是服务端下发的展示文字,原样显示;所有 tag 同一种样式,不按内容区分。没带 tag 时该节点不渲染。
//
// 语义与通用 DecisionCircle 一致:只回传 id、hint 是叙事元数据(ADR-011)。
export function QiriActions({ actions, disabled, onChoose }: ActionsProps) {
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
            <span className={styles.optText}>{a.text}</span>
            {a.hint && <span className={styles.optHint}>{a.hint}</span>}
          </span>
          {a.tag && <span className={styles.optTag}>{a.tag}</span>}
        </button>
      ))}
    </div>
  );
}
