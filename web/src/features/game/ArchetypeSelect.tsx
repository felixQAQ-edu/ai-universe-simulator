import { useEffect, useRef, useState } from 'react';
import { useGameStore } from '../../state/gameStore';
import type { ArchetypeSummary } from '../../api';
import { FUSION_PAIR, nextFusionStage, type FusionStage } from './fusion';
import styles from './game.module.css';

// 世界选择第一屏(产品门面,ADR-008 决策 4)。玩家进游戏 → 看见可玩世界 → 选一个 →
// startGame(archetype) → 复用 Phase 1 已验证整局闭环。
//
// 守 ADR-003 边界:纯展示 + store 调用,无平台 IO(目录经 store.loadArchetypes → api/ 适配层),
// 永不渲染 isTrue/hiddenLogic(选择屏本就只有玩家可见的 displayName/tagline/vibeTag)。
//
// ── 融合入口 = 渗漏卡 + 误入手势(ADR-013 决策 4,round 1 彩蛋)──
// 融合卡初始不显;玩家【长按】修仙卡、再【长按】规则怪谈卡(依次,像摩挲卡片察觉异样)→
// 融合卡从卡列中「渗」出(纯 CSS 渗透动画)。单击进入各世界完全不变(零回归)。
// 纯组件 state、零持久化 —— 每次进选择屏靠手势重新触发,不记忆。
// 点融合卡 → startGame(["cultivation","rules_creepy"])(有序双值,host=修仙在前,接 ADR-013 init)。

/** archetype id → 卡片氛围色调 class(展示层决定,不入后端)。未知 id 回落中性。 */
function vibeClass(archetype: string): string {
  if (archetype === 'rules_creepy') return styles.cardCreepy;
  if (archetype === 'apocalypse') return styles.cardApocalypse;
  if (archetype === 'cthulhu') return styles.cardCthulhu;
  if (archetype === 'cultivation') return styles.cardCultivation;
  return '';
}

export function ArchetypeSelect() {
  const archetypes = useGameStore((s) => s.archetypes);
  const loading = useGameStore((s) => s.archetypesLoading);
  const error = useGameStore((s) => s.archetypesError);
  const loadArchetypes = useGameStore((s) => s.loadArchetypes);
  const startGame = useGameStore((s) => s.startGame);

  // 误入手势(纯组件 state,零持久化 —— 离开选择屏即遗忘,回来要重新「误入」)。
  const [fusionStage, setFusionStage] = useState<FusionStage>('idle');

  useEffect(() => {
    void loadArchetypes();
  }, [loadArchetypes]);

  const active = archetypes.filter((a) => a.active);
  const locked = archetypes.filter((a) => !a.active);

  return (
    <main className={styles.screen}>
      <header className={styles.selectHeader}>
        <p className={styles.phase}>AI Universe Simulator</p>
        <h1 className={styles.title}>选择你的世界</h1>
        <p className={styles.muted}>每一个世界都由 AI 即时生成,真假难辨,不可回头。</p>
      </header>

      {loading && archetypes.length === 0 ? (
        <div className={styles.centered}>
          <div className={styles.spinner} />
          <p className={styles.muted}>正在载入世界……</p>
        </div>
      ) : error && archetypes.length === 0 ? (
        <div className={styles.centered}>
          <p className={styles.muted}>{error}</p>
          <button type="button" className={styles.primaryBtn} onClick={() => void loadArchetypes()}>
            重试
          </button>
        </div>
      ) : (
        <div className={styles.cardList}>
          {active.map((a) => (
            <ArchetypeCard
              key={a.archetype}
              summary={a}
              onChoose={() => startGame(a.archetype)}
              onLongPress={() => setFusionStage((s) => nextFusionStage(s, a.archetype))}
            />
          ))}
          {fusionStage === 'revealed' && <FusionCard onChoose={() => startGame(FUSION_PAIR)} />}
          {locked.map((a) => (
            <ArchetypeCard key={a.archetype} summary={a} onChoose={() => startGame(a.archetype)} />
          ))}
        </div>
      )}
    </main>
  );
}

/** 长按判定时长(ms):短于它=单击进入(照旧),长于它=摩挲卡片(误入手势)。 */
const LONG_PRESS_MS = 600;

/** 单张氛围卡片(纯展示)。已激活=可点钩子卡;未激活=灰显「敬请期待」。导出供组件测试。 */
export function ArchetypeCard({
  summary,
  onChoose,
  onLongPress,
}: {
  summary: ArchetypeSummary;
  onChoose: () => void;
  /** 长按回调(误入手势,ADR-013);缺省=无手势语义。长按后释放的 click 不触发 onChoose。 */
  onLongPress?: () => void;
}) {
  const { archetype, displayName, tagline, vibeTag, active } = summary;
  const timer = useRef<number | null>(null);
  const longPressFired = useRef(false);

  const startPress = () => {
    if (!onLongPress) return;
    longPressFired.current = false;
    timer.current = window.setTimeout(() => {
      longPressFired.current = true;
      onLongPress();
    }, LONG_PRESS_MS);
  };
  const cancelPress = () => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  };
  const handleClick = () => {
    // 长按已触发手势 → 吞掉随后的 click,不进入世界(单击语义不变)。
    if (longPressFired.current) {
      longPressFired.current = false;
      return;
    }
    onChoose();
  };

  if (!active) {
    return (
      <div className={`${styles.card} ${styles.cardLocked}`} aria-disabled="true">
        <div className={styles.cardTop}>
          <h2 className={styles.cardTitle}>{displayName}</h2>
          <span className={styles.cardSoon}>敬请期待</span>
        </div>
      </div>
    );
  }

  return (
    <button
      type="button"
      className={`${styles.card} ${vibeClass(archetype)}`}
      onClick={handleClick}
      onPointerDown={startPress}
      onPointerUp={cancelPress}
      onPointerLeave={cancelPress}
      onPointerCancel={cancelPress}
      onContextMenu={(e) => onLongPress && e.preventDefault()}
    >
      <div className={styles.cardTop}>
        <h2 className={styles.cardTitle}>{displayName}</h2>
        {vibeTag && <span className={styles.cardTag}>{vibeTag}</span>}
      </div>
      {tagline && <p className={styles.cardTagline}>{tagline}</p>}
    </button>
  );
}

/**
 * 渗漏卡(融合入口,ADR-013 决策 4):视觉异常的第五张卡 —— 修仙青光 × 规则怪谈诡异冷蓝互噬,
 * 标题在「修仙/规则怪谈/识海遗蜕」间闪烁撕裂浮现(三层叠放,CSS 轮换 opacity,不引动画库)。
 * 点击 → 发双值 init(修仙×规则怪谈)。导出供组件测试。
 */
export function FusionCard({ onChoose }: { onChoose: () => void }) {
  return (
    <button
      type="button"
      className={`${styles.card} ${styles.cardFusion}`}
      onClick={onChoose}
      aria-label="识海遗蜕(融合世界)"
    >
      <div className={styles.cardTop}>
        <h2 className={`${styles.cardTitle} ${styles.fusionTitle}`} aria-hidden="true">
          {/* 三层标题叠放,CSS 轮换浮现(撕裂闪烁);aria 语义由按钮 label 承载。 */}
          <span className={styles.fusionTitleA}>修仙</span>
          <span className={styles.fusionTitleB}>规则怪谈</span>
          <span className={styles.fusionTitleC}>识海遗蜕</span>
        </h2>
        <span className={styles.cardTag}>渗漏 · 勿入</span>
      </div>
      <p className={styles.cardTagline}>两个世界之间,有什么渗了过来。</p>
    </button>
  );
}
