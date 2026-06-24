import { useGameStore } from '../../state/gameStore';
import { DecisionCircle } from './DecisionCircle';
import { EndingScreen } from './EndingScreen';
import { Prose } from './Prose';
import { RulesPanel } from './RulesPanel';
import { StatsPanel } from './StatsPanel';
import styles from './game.module.css';

const ARCHETYPE = 'rules_creepy' as const; // Phase 1 固定单模式(规则怪谈)。

/**
 * 整局流程容器(brief 实现切分 4):开局(init→loading→开场 reveal)→ 回合循环 →
 * 结局画面;world-gen ERROR 出「重新生成」。只读 store,不碰 api/ 与平台 IO。
 */
export function GameScreen() {
  const status = useGameStore((s) => s.status);
  const startGame = useGameStore((s) => s.startGame);

  if (status === 'idle') return <StartScreen onStart={() => startGame(ARCHETYPE)} />;
  if (status === 'initializing') return <LoadingScreen />;
  if (status === 'initError') return <InitErrorScreen onRetry={() => startGame(ARCHETYPE)} />;
  return <PlayingScreen />;
}

function StartScreen({ onStart }: { onStart: () => void }) {
  return (
    <main className={styles.screen}>
      <div className={styles.centered}>
        <p className={styles.phase}>AI Universe Simulator</p>
        <h1 className={styles.title}>规则怪谈</h1>
        <p className={styles.muted}>
          午夜降临,你将踏入一个由规则编织、真假难辨的世界。
          <br />
          每一次抉择都不可回头。
        </p>
        <button type="button" className={styles.primaryBtn} onClick={onStart}>
          进入世界
        </button>
      </div>
    </main>
  );
}

function LoadingScreen() {
  return (
    <main className={styles.screen}>
      <div className={styles.centered}>
        <div className={styles.spinner} />
        <p className={styles.muted}>世界正在生成……</p>
      </div>
    </main>
  );
}

function InitErrorScreen({ onRetry }: { onRetry: () => void }) {
  const errorMessage = useGameStore((s) => s.errorMessage);
  return (
    <main className={styles.screen}>
      <div className={styles.centered}>
        <h1 className={styles.title}>世界生成失败</h1>
        <p className={styles.muted}>{errorMessage ?? '请重新生成。'}</p>
        <button type="button" className={styles.primaryBtn} onClick={onRetry}>
          重新生成
        </button>
      </div>
    </main>
  );
}

function PlayingScreen() {
  const world = useGameStore((s) => s.world);
  const status = useGameStore((s) => s.status);
  const narrative = useGameStore((s) => s.narrative);
  const turn = useGameStore((s) => s.turn);
  const attributeAxes = useGameStore((s) => s.attributeAxes);
  const attributeValues = useGameStore((s) => s.attributeValues);
  const discoveredRuleIds = useGameStore((s) => s.discoveredRuleIds);
  const availableActions = useGameStore((s) => s.availableActions);
  const ending = useGameStore((s) => s.ending);
  const notice = useGameStore((s) => s.notice);
  const chooseAction = useGameStore((s) => s.chooseAction);
  const reset = useGameStore((s) => s.reset);

  if (!world) return null;

  // 开场 reveal:仅当还没行动过(turn 0)时对开场叙事做 client-side 逐字动画。
  const isOpening = turn === 0;

  return (
    <main className={styles.screen}>
      <header className={styles.header}>
        <p className={styles.phase}>第 {turn} 回合 · 危险度 {world.world.dangerLevel}</p>
        <h1 className={styles.title}>{world.world.title}</h1>
        <p className={styles.tone}>{world.world.tone}</p>
      </header>

      <StatsPanel axes={attributeAxes} values={attributeValues} />

      <Prose text={narrative} reveal={isOpening} />

      {notice && <div className={styles.notice}>{notice}</div>}

      {status === 'ended' && ending ? (
        <EndingScreen ending={ending} onRestart={reset} />
      ) : (
        <DecisionCircle
          actions={availableActions}
          disabled={status === 'generating'}
          onChoose={chooseAction}
        />
      )}

      <RulesPanel rules={world.rules} discoveredIds={discoveredRuleIds} />
    </main>
  );
}
