import game from '../../game.module.css';
import type { SeepEntry, SeepSignals } from './registry';
import { SeepStain } from './SeepStain';
import styles from './seep.module.css';

// 渗入层的正文形态(ADR-043 决策 5):**只在宿主没有 Prose 槽时**由 `PlayingScreen` 选用(修仙没有)。
// 复用通用 `.prose`(同 CthuProse),多挂一个渗入 class;正文两角的墨洇画在这里(决策 3)。
// 字洇成墨在刀 5 接进来;本刀的正文 = 通用 Prose 的文字与光标,原样不动。

export function SeepProse({
  text,
  caret,
  seep,
  signals,
}: {
  text: string;
  caret: boolean;
  seep: SeepEntry;
  signals: SeepSignals;
}) {
  return (
    <div className={`${game.prose} ${styles.prose}`}>
      {seep.stain && <SeepStain stain={seep.stain} level={signals.stainLevel} surface="prose" />}
      {text}
      {caret && <span className={game.caret}>▍</span>}
    </div>
  );
}
