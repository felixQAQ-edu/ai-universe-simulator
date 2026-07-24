import { useCallback, useMemo, useState } from 'react';
import { useGameStore } from '../../state/gameStore';
import { ArchetypeSelect } from './ArchetypeSelect';
import { DecisionCircle } from './DecisionCircle';
import { EndingScreen } from './EndingScreen';
import { Prose } from './Prose';
import { RulesPanel } from './RulesPanel';
import { SceneBanner } from './SceneBanner';
import { StatsPanel } from './StatsPanel';
import { SkinContext } from './theme/contract';
import { DebugPanel } from './theme/DebugPanel';
import { useSkinRuntime } from './theme/lifecycle';
import { resolveWorldTheme } from './theme/registry';
import { SKINS } from './theme/skins';
import { useTypewriter } from './useTypewriter';
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
  const errorCode = useGameStore((s) => s.errorCode);
  const lastArchetype = useGameStore((s) => s.lastArchetype);
  const startGame = useGameStore((s) => s.startGame);
  const reset = useGameStore((s) => s.reset);
  // 成本闸门拦截(ADR-016)不是「失败」——标题与副标题(「今日名额已满」)口径一致;
  // world-gen 救不回 / 网络等真失败仍报「世界生成失败」。
  const title = errorCode === 'quota_exceeded' ? '今日名额已满' : '世界生成失败';
  return (
    <main className={styles.screen}>
      <div className={styles.centered}>
        <h1 className={styles.title}>{title}</h1>
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

  // 开场 reveal:仅当还没行动过(turn 0)时对开场叙事做 client-side 逐字动画。
  // 打字进度在这里算(而非 Prose 内),因为它同时是低频动效的停表信号(ADR-018 §4.4)。
  const isOpening = turn === 0;
  const { shown, done } = useTypewriter(narrative, isOpening && !!world);

  // ── 本项目唯一的世界判定点(ADR-018 §4.1)────────────────────────────
  // 子组件一律消费这次判定的结果(封面 / 皮肤 / 卡片氛围),不得各自再判 archetype。
  const theme = resolveWorldTheme(world?.archetypes);
  const skin = (theme.skin ? SKINS[theme.skin] : null) ?? null;
  // runtime 的 key 含皮肤与回合:两者任一变化即走**同一个** teardown(§4.4)。
  const runtime = useSkinRuntime(theme.skin ?? '', turn);
  const bundle = useMemo(() => (skin ? { skin, runtime } : null), [skin, runtime]);

  // 皮肤挂在主题根上的瞬时状态 class(前一拍 / 余韵)。含义由皮肤定义,这里只负责拼字符串。
  // 状态连同「它属于哪个皮肤+回合」一起存 —— 换 turn / 换皮肤即自动作废(渲染期比对,不用 effect
  // 补一次 setState),免得 teardown 掐掉余韵计时后状态 class 永久留在根上。
  const scope = `${theme.skin ?? ''}|${turn}`;
  const [rootState, setRootState] = useState({ scope, cls: '' });
  const rootClass = rootState.scope === scope ? rootState.cls : '';
  const setRootClass = useCallback((cls: string) => setRootState({ scope, cls }), [scope]);

  if (!world) return null;

  const revealing = isOpening && !done;
  // 停表:回合流生成中 / 开场逐字未完 —— 正文不稳定期,低频调度一律停。
  const generating = status === 'generating';
  const paused = generating || revealing;
  const Ambient = skin?.Ambient;
  const Actions = skin?.Actions ?? DecisionCircle;
  const skinClasses = skin
    ? `${skin.screenClass} ${paused ? skin.pausedClass : ''} ${rootClass}`
    : '';

  return (
    <SkinContext.Provider value={bundle}>
      <main className={`${styles.screen} ${skinClasses}`}>
        {Ambient && (
          <Ambient
            runtime={runtime}
            paused={paused}
            generating={generating}
            turn={turn}
            setRootClass={setRootClass}
          />
        )}

        <SceneBanner
          sceneUrl={theme.sceneUrl}
          turn={turn}
          dangerLevel={world.world.dangerLevel}
          title={world.world.title}
          tone={world.world.tone}
        />

        <StatsPanel axes={attributeAxes} values={attributeValues} />

        <Prose text={isOpening ? shown : narrative} caret={revealing} />

        {notice && <div className={styles.notice}>{notice}</div>}

        {status === 'ended' && ending ? (
          <EndingScreen ending={ending} onRestart={reset} />
        ) : (
          <Actions
            actions={availableActions}
            disabled={status === 'generating'}
            onChoose={chooseAction}
          />
        )}

        <RulesPanel rules={world.rules} discoveredIds={discoveredRuleIds} />

        <DebugPanel theme={theme} axes={attributeAxes} values={attributeValues} paused={paused} />
      </main>
    </SkinContext.Provider>
  );
}
