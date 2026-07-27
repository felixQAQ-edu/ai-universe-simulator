import type { ActionsProps } from '../contract';
import styles from './waste.module.css';

// 末日 · 决策圈形态「铁皮告示」(样板间冻结成果移植)。
// 四角铆钉 + 磨损切角 + 按压**下沉**(不是发光:铁皮被按下去,不会亮)。
//
// 三种反馈并列看:规则怪谈=阶跃(开关)/ 修仙=缓出发光(气机泛起)/ 末日=**下沉带迟滞**
// (`--t-ease` 的 `power2.out` + 启动迟滞,机械件动起来总要先克服一点静摩擦)。
//
// **形态与阴影同源**(ADR-018 §4.10):告示牌用 `clip-path` 切了角,故投影一律走
// `filter: drop-shadow()` —— `box-shadow` 画的是矩形,会在切角外露出直角阴影。
//
// 语义与通用 DecisionCircle 完全一致:只回传 id、hint 是叙事元数据(ADR-011)。

export function WasteActions({ actions, disabled, onChoose }: ActionsProps) {
  return (
    <div className={styles.notices}>
      {actions.map((a) => (
        <button
          key={a.id}
          type="button"
          className={styles.notice}
          disabled={disabled}
          onClick={() => onChoose(a.id)}
        >
          <span className={styles.noticeBody}>
            <span className={styles.noticeText}>{a.text}</span>
            {/* 定性风险提示(ADR-011):缺失时不渲染、不占位。 */}
            {a.hint && <span className={styles.noticeHint}>{a.hint}</span>}
          </span>
        </button>
      ))}
    </div>
  );
}
