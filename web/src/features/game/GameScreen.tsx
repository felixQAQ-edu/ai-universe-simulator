import { useGameStore } from '../../state/gameStore';
import { ArchetypeSelect } from './ArchetypeSelect';
import { DecisionCircle } from './DecisionCircle';
import { EndingScreen } from './EndingScreen';
import { Prose } from './Prose';
import { RulesPanel } from './RulesPanel';
import { SceneBanner } from './SceneBanner';
import { StatsPanel } from './StatsPanel';
import styles from './game.module.css';

/**
 * 整局流程容器:选择屏(选世界)→ 开局(init→loading→开场 reveal)→ 回合循环 →
 * 结局画面;world-gen ERROR 出「重新生成」。只读 store,不碰 api/ 与平台 IO。
 */
export function GameScreen() {
  const status = useGameStore((s) => s.status);

  if (status === 'idle') return <ArchetypeSelect />;
  if (status === 'initializing') return <LoadingScreen />;
  if (status === 'initError') return <InitErrorScreen />;
  return <PlayingScreen />;
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

function InitErrorScreen() {
  const errorMessage = useGameStore((s) => s.errorMessage);
  const lastArchetype = useGameStore((s) => s.lastArchetype);
  const startGame = useGameStore((s) => s.startGame);
  const reset = useGameStore((s) => s.reset);
  return (
    <main className={styles.screen}>
      <div className={styles.centered}>
        <h1 className={styles.title}>世界生成失败</h1>
        <p className={styles.muted}>{errorMessage ?? '请重新生成。'}</p>
        <button
          type="button"
          className={styles.primaryBtn}
          disabled={!lastArchetype}
          onClick={() => lastArchetype && startGame(lastArchetype)}
        >
          重新生成
        </button>
        <button type="button" className={styles.linkBtn} onClick={reset}>
          换个世界
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
      <SceneBanner
        archetypes={world.archetypes}
        turn={turn}
        dangerLevel={world.world.dangerLevel}
        title={world.world.title}
        tone={world.world.tone}
      />

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
