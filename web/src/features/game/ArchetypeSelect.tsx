import { useCallback, useEffect, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { useGameStore } from '../../state/gameStore';
import type { ArchetypeSummary } from '../../api';
import { fusionKey, isFusionAllowed } from './fusion/drag';
import { useFusionDrag } from './fusion/useFusionDrag';
import { cardTheme } from './theme/registry';
import { reducedMotion } from './theme/motion';
import type { Archetype } from '../../types/schema';
import styles from './game.module.css';

// 世界选择第一屏(产品门面,ADR-008 决策 4)。玩家进游戏 → 看见可玩世界 → 选一个 →
// startGame(archetype) → 复用 Phase 1 已验证整局闭环。
//
// 守 ADR-003 边界:纯展示 + store 调用,无平台 IO(目录经 store.loadArchetypes → api/ 适配层),
// 永不渲染 isTrue/hiddenLogic(选择屏本就只有玩家可见的 displayName/tagline/vibeTag)。
//
// ── 融合入口 = 把一张卡拖到另一张上(ADR-019;线 B1 真机验通)────────────────
// **被拖者 = foreign,承接者 = host** —— 动作本身表达语义,松手直接生成有序双值
// `[host, foreign]` 喂 init(ADR-013 已立 host 在前),不新增字段。
// 合法组合来自后端 `fusions` 只读投影(ADR-019),前端不自备组合表 ——
// 否则玩家能拖出一个后端 400 的组合。
//
// **ADR-013 决策 4 的「误入手势」(依次长按两张卡)本轮退役**(理由见 ADR-019 §7):
// 拖拽抓起 180ms 是长按 600ms 的**真子集**,两者并存只能靠「让长按永不触发」——
// 那是「长按已死但代码还留着」的最坏形态。**渗漏卡的形态与 CSS 保留**,
// 改由拖拽揉合产出(复用的是形式,变化的是解释)。
//
// 卡片氛围 class 由**单一主题注册表**给出(ADR-018 §4.1);未登记世界 → '' 中性卡。

export function ArchetypeSelect() {
  const archetypes = useGameStore((s) => s.archetypes);
  const fusions = useGameStore((s) => s.fusions);
  const loading = useGameStore((s) => s.archetypesLoading);
  const error = useGameStore((s) => s.archetypesError);
  const loadArchetypes = useGameStore((s) => s.loadArchetypes);
  const startGame = useGameStore((s) => s.startGame);
  const resumableSaveId = useGameStore((s) => s.resumableSaveId);
  const resumeGame = useGameStore((s) => s.resumeGame);

  /** 已揉出的融合组合键(纯组件 state、零持久化 —— 离开选择屏即遗忘,回来要重新拖)。 */
  const [fused, setFused] = useState<readonly string[]>([]);
  /** 无效组合的一句提示(不弹 Toast;下一次成功手势即散)。 */
  const [rejectNote, setRejectNote] = useState<string | null>(null);

  useEffect(() => {
    void loadArchetypes();
  }, [loadArchetypes]);

  const canFuse = useCallback(
    (host: string, foreign: string) => isFusionAllowed(fusions, host, foreign),
    [fusions],
  );

  const onCommit = useCallback((host: string, foreign: string) => {
    const key = fusionKey(host, foreign);
    setRejectNote(null);
    setFused((f) => (f.includes(key) ? f : [...f, key]));
  }, []);

  // 落在无效目标上松手 → 一句提示,不挡路(排斥回弹由手势层做,不弹 Toast)。
  // 在**松手**时报而不是悬停时报:悬停即报 = 路过一张卡就被念一句。
  const onReject = useCallback(() => setRejectNote('两个世界尚无法彼此容纳。'), []);

  const drag = useFusionDrag({ canFuse, onCommit, onReject, reduced: reducedMotion() });

  const active = archetypes.filter((a) => a.active);
  const locked = archetypes.filter((a) => !a.active);

  const dragStateOf = (id: string): CardDragState => {
    if (drag.view.draggingId === id) return 'dragging';
    if (drag.view.targetId === id) return drag.view.valid ? 'valid' : 'invalid';
    return null;
  };

  return (
    <main className={styles.screen}>
      <header className={styles.selectHeader}>
        <p className={styles.phase}>AI Universe Simulator</p>
        <h1 className={styles.title}>选择你的世界</h1>
        <p className={styles.muted}>每一个世界都由 AI 即时生成,真假难辨,不可回头。</p>
        {rejectNote && <p className={styles.fusionNote}>{rejectNote}</p>}
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
              drag={{
                ref: drag.registerCard(a.archetype),
                onPointerDown: (e) => drag.onPointerDown(a.archetype, e),
                shouldSwallowClick: () => drag.shouldSwallowClick(a.archetype),
                state: dragStateOf(a.archetype),
              }}
            />
          ))}
          {fused.map((key) => (
            <FusionCard key={key} combo={key} onChoose={() => startGame(pairOf(key))} />
          ))}
          {locked.map((a) => (
            <ArchetypeCard key={a.archetype} summary={a} onChoose={() => startGame(a.archetype)} />
          ))}
        </div>
      )}
    </main>
  );
}

/** 组合键 → 有序双值(host 在前,ADR-012/013)。 */
function pairOf(key: string): Archetype[] {
  return key.split('×') as Archetype[];
}

/** 卡片在拖拽中的角色(纯样式令牌;差异只在样式故不开组件槽,ADR-018 §4.17)。 */
export type CardDragState = 'dragging' | 'valid' | 'invalid' | null;

export interface CardDragBinding {
  ref: (el: HTMLElement | null) => void;
  onPointerDown: (e: ReactPointerEvent<HTMLElement>) => void;
  /** 抓起后那一次 click 必须吞掉(否则松手即进世界)。 */
  shouldSwallowClick: () => boolean;
  state: CardDragState;
}

/** 单张氛围卡片(纯展示)。已激活=可点钩子卡;未激活=灰显「敬请期待」。导出供组件测试。 */
export function ArchetypeCard({
  summary,
  onChoose,
  drag,
}: {
  summary: ArchetypeSummary;
  onChoose: () => void;
  /** 融合拖拽接线;缺省=不可拖(未开放卡、纯展示用例)。 */
  drag?: CardDragBinding;
}) {
  const { archetype, displayName, tagline, vibeTag, active } = summary;

  const handleClick = () => {
    // 抓起过 → 吞掉随后的 click,不进入世界(单击语义不变)。
    if (drag?.shouldSwallowClick()) return;
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
      ref={drag?.ref}
      className={[
        styles.card,
        cardTheme(archetype).cardClass,
        drag ? styles.cardDraggable : '',
        drag?.state ? DRAG_CLASS[drag.state] : '',
      ]
        .filter(Boolean)
        .join(' ')}
      onClick={handleClick}
      onPointerDown={drag?.onPointerDown}
      onContextMenu={(e) => drag && e.preventDefault()}
    >
      <div className={styles.cardTop}>
        <h2 className={styles.cardTitle}>{displayName}</h2>
        {vibeTag && <span className={styles.cardTag}>{vibeTag}</span>}
      </div>
      {tagline && <p className={styles.cardTagline}>{tagline}</p>}
    </button>
  );
}

const DRAG_CLASS: Record<NonNullable<CardDragState>, string> = {
  dragging: styles.cardDragging,
  valid: styles.cardTargetValid,
  invalid: styles.cardTargetInvalid,
};

/**
 * per-combo 融合卡**文案**(ADR-014 参数化;展示层配置,不入后端)。
 * 卡片氛围 class 不在这里 —— 它与单体卡同源,由主题注册表按组合键给出(ADR-018 §4.1)。
 */
const FUSION_CARDS: Record<
  string,
  { titles: [string, string, string]; ariaLabel: string; tagline: string }
> = {
  'cultivation×rules_creepy': {
    titles: ['修仙', '规则怪谈', '识海遗蜕'],
    ariaLabel: '识海遗蜕(融合世界)',
    tagline: '两个世界之间,有什么渗了过来。',
  },
  'rules_creepy×apocalypse': {
    titles: ['规则怪谈', '末日生存', '缺页的人防工程'],
    ariaLabel: '缺页的人防工程(融合世界)',
    tagline: '缺的那几页,和消失的人对得上号。',
  },
};

/**
 * 融合卡(揉合的产物;ADR-013 决策 4 立的渗漏卡形态原样保留,ADR-019 只换触发方式):
 * 视觉异常的「多出来的一张卡」——两世界氛围互噬,标题在「世界A/世界B/融合定稿名」间
 * 闪烁撕裂浮现(三层叠放,CSS 轮换 opacity,不引动画库)。点击 → 发该组合有序双值 init。
 * 导出供组件测试。
 */
export function FusionCard({ combo, onChoose }: { combo: string; onChoose: () => void }) {
  const card = FUSION_CARDS[combo];
  if (!card) return null; // 未配文案的组合不渲染(登记齐组合表 + 卡文案再上)
  return (
    <button
      type="button"
      className={`${styles.card} ${cardTheme(combo).cardClass}`}
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
