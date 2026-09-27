import { useHistoryStore } from '../../state/historyStore';
import { BackButton } from './BackButton';
import { gapLines, turnHeading } from './historyText';
import styles from './game.module.css';

// 「这一局的故事」只读历史页(ADR-025 刀 3)。
//
// · **无皮肤**:不进主题注册表、不挂 SkinContext —— BackButton 在无皮肤上下文下照常渲染(只是长得普通);
// · 只展示叙事,不展示玩家动作(库里只有选项编号,「选了 A」对玩家无意义);
// · 缺口只标回合号,不伪造内容;
// · 不出现任何系统语言(历史数据 / 迁移 / 请求失败 / 错误码 / HTTP 状态)。
// 每次进页都重新读第一页(由 store.open 保证,不复用入口探测的数据)。

export function HistoryScreen() {
  const phase = useHistoryStore((s) => s.phase);
  const entries = useHistoryStore((s) => s.entries);
  const nextAfterTurn = useHistoryStore((s) => s.nextAfterTurn);
  const more = useHistoryStore((s) => s.more);
  const close = useHistoryStore((s) => s.close);
  const retry = useHistoryStore((s) => s.retry);
  const loadMore = useHistoryStore((s) => s.loadMore);

  return (
    <main className={styles.screen}>
      <div className={styles.historyNav}>
        <BackButton onBack={close} />
      </div>
      <h1 className={styles.title}>这一局的故事</h1>

      {phase === 'loading' ? (
        <div className={styles.centered}>
          <div className={styles.spinner} />
        </div>
      ) : phase === 'error' && entries.length === 0 ? (
        <div className={styles.centered}>
          <p className={styles.muted}>这一局的记录暂时没能载入</p>
          <button type="button" className={styles.primaryBtn} onClick={retry}>
            再试一次
          </button>
        </div>
      ) : (
        <>
          <ol className={styles.historyList}>
            {entries.map((e) =>
              e.kind === 'event' ? (
                <li key={`e${e.turn}`} className={styles.historyEvent}>
                  <h2 className={styles.historyTurn}>{turnHeading(e.turn)}</h2>
                  <p className={styles.historyProse}>{e.narrative}</p>
                </li>
              ) : (
                gapLines(e).map((line) => (
                  <li key={`g${e.fromTurn}-${line}`} className={styles.historyGap} role="note">
                    {line}
                  </li>
                ))
              ),
            )}
          </ol>
          {nextAfterTurn !== null &&
            (more === 'error' ? (
              <div className={styles.historyMore}>
                <p className={styles.muted}>还有一些回合暂时没能载入</p>
                <button type="button" className={styles.linkBtn} onClick={loadMore}>
                  再试一次
                </button>
              </div>
            ) : (
              <div className={styles.historyMore}>
                <button
                  type="button"
                  className={styles.linkBtn}
                  onClick={loadMore}
                  disabled={more === 'loading'}
                >
                  加载更多
                </button>
              </div>
            ))}
        </>
      )}
    </main>
  );
}
