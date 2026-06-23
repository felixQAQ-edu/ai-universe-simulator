import styles from './game.module.css';
import { useTypewriter } from './useTypewriter';

// 散文区。reveal=true 时做 client-side 逐字 reveal(开场,ADR-007);
// reveal=false 时直接渲染(回合实时流本就逐字到达,无需再假装)。
export function Prose({ text, reveal }: { text: string; reveal: boolean }) {
  const { shown, done } = useTypewriter(text, reveal);
  const body = reveal ? shown : text;
  const showCaret = reveal ? !done : false;
  return (
    <div className={styles.prose}>
      {body}
      {showCaret && <span className={styles.caret}>▍</span>}
    </div>
  );
}
