import styles from './game.module.css';

// 数值面板:hp/san 绝对值(引擎落账,CONTEXT §三.8),配进度条直观化。0–100。
export function StatsPanel({ hp, san }: { hp: number; san: number }) {
  return (
    <div className={styles.stats}>
      <div className={styles.stat}>
        <span className={styles.statKey}>HP 生命</span>
        <span className={styles.statVal}>{hp}</span>
        <div className={styles.bar}>
          <div className={`${styles.barFill} ${styles.barHp}`} style={{ width: `${clamp(hp)}%` }} />
        </div>
      </div>
      <div className={styles.stat}>
        <span className={styles.statKey}>SAN 理智</span>
        <span className={styles.statVal}>{san}</span>
        <div className={styles.bar}>
          <div className={`${styles.barFill} ${styles.barSan}`} style={{ width: `${clamp(san)}%` }} />
        </div>
      </div>
    </div>
  );
}

const clamp = (n: number) => Math.max(0, Math.min(100, n));
