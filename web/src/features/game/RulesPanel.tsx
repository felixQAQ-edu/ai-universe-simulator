import type { ClientRule } from '../../api';
import styles from './game.module.css';

// 规则面板:展示玩家可见的规则 content(规则怪谈的《须知》)。
// 注意:isTrue / hiddenLogic 是引擎视角字段,消毒投影里本就没有,这里也绝不渲染。
// discovered 的规则做高亮(玩家已在剧情中验证过它「确实生效」)。
export function RulesPanel({
  rules,
  discoveredIds,
}: {
  rules: ClientRule[];
  discoveredIds: number[];
}) {
  if (rules.length === 0) return null;
  const discovered = new Set(discoveredIds);
  return (
    <section className={styles.rules}>
      <h2 className={styles.rulesTitle}>规则须知</h2>
      <ol className={styles.ruleList}>
        {rules.map((r) => {
          const isFound = discovered.has(r.id);
          return (
            <li
              key={r.id}
              className={`${styles.rule} ${isFound ? styles.ruleDiscovered : ''}`}
            >
              {r.content}
              {isFound && <span className={styles.ruleTag}>已验证</span>}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
