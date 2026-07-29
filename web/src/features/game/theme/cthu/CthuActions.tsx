import type { PointerEvent } from 'react';
import type { ActionsProps } from '../contract';
import styles from './cthu.module.css';

// 克苏鲁 · 决策圈形态「调查记录纸条」(样板间冻结成果移植)。
// 微倾斜 + 四角圆角不均 + 左上钉孔 + **潮湿撕裂底边**(纸被海水泡胀、纤维松开)。
//
// **形态与阴影同源**(ADR-018 §4.10):底边是 `clip-path` 撕出来的,故投影一律走
// `filter: drop-shadow()` —— `box-shadow` 画的永远是矩形,会在撕裂线下方露出一条直角影子,
// **形态当场破功**(刀 2.5 的八边玉印踩过同一条)。
//
// ── 按压时长每次微随机 = 本世界时间感的入口(CTHU「节奏不可信」)────────────
// 四种反馈并列看:规则怪谈 = 阶跃(开关)/ 修仙 = 缓出发光(气机泛起)/
// 末日 = 下沉带迟滞(机械件的静摩擦)/ 克苏鲁 = **每次快慢略不同**(要连按几次才察觉)。
// CSS 做不到「每次不同」,故按下时写一次 `--press-dur` —— 三行 JS,**不引库**
// (AGENTS.md §7:白名单是许可不是义务,为一个按压反馈引整个 Motion 是纯债)。
//
// 语义与通用 DecisionCircle 完全一致:只回传 id、hint 是叙事元数据(ADR-011)。

/** 每次按压的过渡时长:0.4s × random(0.82–1.22),与 `motion.ts` 的 CTHU.dur 同口径。 */
function randomPressDur(el: HTMLElement): void {
  el.style.setProperty('--press-dur', `${(0.4 * (0.82 + Math.random() * 0.4)).toFixed(3)}s`);
}

export function CthuActions({ actions, disabled, onChoose }: ActionsProps) {
  const onPointerDown = (e: PointerEvent<HTMLButtonElement>) => randomPressDur(e.currentTarget);
  return (
    <div className={styles.notes}>
      {actions.map((a) => (
        <button
          key={a.id}
          type="button"
          className={styles.note}
          disabled={disabled}
          onPointerDown={onPointerDown}
          onClick={() => onChoose(a.id)}
        >
          <span className={styles.noteText}>{a.text}</span>
          {/* 定性风险提示(ADR-011):缺失时不渲染、不占位。 */}
          {a.hint && <span className={styles.noteHint}>{a.hint}</span>}
        </button>
      ))}
    </div>
  );
}
