import type { EndingPayload } from '../../api';
import styles from './game.module.css';

// 结局画面(ADR-006 `ending` 事件)。展示短标题 + 整句描述,提供「再来一局」。
export function EndingScreen({
  ending,
  onRestart,
}: {
  ending: EndingPayload;
  onRestart: () => void;
}) {
  return (
    <section className={styles.ending}>
      <p className={styles.endingLabel}>结局</p>
      <h2 className={styles.endingTitle}>{ending.title}</h2>
      {ending.description && <p className={styles.endingDesc}>{ending.description}</p>}
      <button type="button" className={styles.primaryBtn} onClick={onRestart}>
        再来一局
      </button>
    </section>
  );
}
