import { useEffect, useRef, useState } from 'react';
import { useGameStore } from '../../state/gameStore';
import type { ArchetypeSummary } from '../../api';
import { FUSION_GESTURES, INITIAL_FUSION_STAGES, nextFusionStages, type FusionStages } from './fusion';
import styles from './game.module.css';

// 世界选择第一屏(产品门面,ADR-008 决策 4)。玩家进游戏 → 看见可玩世界 → 选一个 →
// startGame(archetype) → 复用 Phase 1 已验证整局闭环。
//
// 守 ADR-003 边界:纯展示 + store 调用,无平台 IO(目录经 store.loadArchetypes → api/ 适配层),
// 永不渲染 isTrue/hiddenLogic(选择屏本就只有玩家可见的 displayName/tagline/vibeTag)。
//
// ── 融合入口 = 渗漏卡 + 误入手势(ADR-013 决策 4;ADR-014 泛化为组合表)──
// 融合卡初始不显;玩家依次【长按】某组合的两张卡(像摩挲卡片察觉异样)→ 该组合的渗漏卡
// 从卡列中「渗」出(纯 CSS 渗透动画)。单击进入各世界完全不变(零回归)。
// 手势组合表在 fusion.ts(per-combo 独立状态机,交叉序列不误触发);纯组件 state、零持久化。
// 点融合卡 → startGame(该组合有序双值,host 在前,接 ADR-013 init)。

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
  const resumableSaveId = useGameStore((s) => s.resumableSaveId);
  const resumeGame = useGameStore((s) => s.resumeGame);

  // 误入手势(纯组件 state,零持久化 —— 离开选择屏即遗忘,回来要重新「误入」)。
  const [fusionStages, setFusionStages] = useState<FusionStages>(INITIAL_FUSION_STAGES);

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

      {/* 续局入口(ADR-015 Slice 2):localStorage 有上局 saveId 才显;失败由 store 静默清 saveId 回到本屏。 */}
      {resumableSaveId && (
        <button type="button" className={styles.resumeBtn} onClick={() => void resumeGame()}>
          <span className={styles.resumeTitle}>继续上局</span>
          <span className={styles.resumeHint}>世界线仍在,从上次落笔处接续</span>
        </button>
      )}

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
              onLongPress={() => setFusionStages((s) => nextFusionStages(s, a.archetype))}
            />
          ))}
          {FUSION_GESTURES.filter((g) => fusionStages[g.key] === 'revealed').map((g) => (
            <FusionCard key={g.key} combo={g.key} onChoose={() => startGame(g.pair)} />
          ))}
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

/** per-combo 渗漏卡文案与主题(ADR-014 参数化;展示层配置,不入后端)。 */
const FUSION_CARDS: Record<
  string,
  { titles: [string, string, string]; ariaLabel: string; tagline: string; theme: string }
> = {
  'cultivation×rules_creepy': {
    titles: ['修仙', '规则怪谈', '识海遗蜕'],
    ariaLabel: '识海遗蜕(融合世界)',
    tagline: '两个世界之间,有什么渗了过来。',
    theme: styles.cardFusion, // 修仙青白仙气 × 怪谈冷蓝互噬
  },
  'rules_creepy×apocalypse': {
    titles: ['规则怪谈', '末日生存', '缺页的人防工程'],
    ariaLabel: '缺页的人防工程(融合世界)',
    tagline: '缺的那几页,和消失的人对得上号。',
    theme: styles.cardFusionRenfang, // 怪谈冷蓝 × 末日锈土互噬
  },
};

/**
 * 渗漏卡(融合入口,ADR-013 决策 4;ADR-014 参数化):视觉异常的「多出来的一张卡」——
 * 两世界氛围互噬,标题在「世界A/世界B/融合定稿名」间闪烁撕裂浮现(三层叠放,CSS 轮换
 * opacity,不引动画库)。点击 → 发该组合有序双值 init。导出供组件测试。
 */
export function FusionCard({ combo, onChoose }: { combo: string; onChoose: () => void }) {
  const card = FUSION_CARDS[combo];
  if (!card) return null; // 未配文案的组合不渲染(登记齐组合表 + 卡文案再上)
  return (
    <button
      type="button"
      className={`${styles.card} ${card.theme}`}
      onClick={onChoose}
      aria-label={card.ariaLabel}
    >
      <div className={styles.cardTop}>
        <h2 className={`${styles.cardTitle} ${styles.fusionTitle}`} aria-hidden="true">
          {/* 三层标题叠放,CSS 轮换浮现(撕裂闪烁);aria 语义由按钮 label 承载。 */}
          <span className={styles.fusionTitleA}>{card.titles[0]}</span>
          <span className={styles.fusionTitleB}>{card.titles[1]}</span>
          <span className={styles.fusionTitleC}>{card.titles[2]}</span>
        </h2>
        <span className={styles.cardTag}>渗漏 · 勿入</span>
      </div>
      <p className={styles.cardTagline}>{card.tagline}</p>
    </button>
  );
}
