import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import type { ArchetypeSummary } from '../src/api';
import { ArchetypeCard, FusionCard, type CardDragBinding } from '../src/features/game/ArchetypeSelect';
import styles from '../src/features/game/game.module.css';
import kf from './keyframes.module.css';

// 《验讫》五帧的画面本身(入口与说明见 yanqi-keyframes.tsx)。

/** 选择屏目录:与后端 ArchetypeRegistry.listForSelection() 的顺序与文案一致(2026-10-20 抄)。 */
const CATALOG: ArchetypeSummary[] = [
  { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: '一纸诡异守则,真假混杂。读懂它,或者付出代价。', vibeTag: '诡异 · 高危', active: true },
  { archetype: 'apocalypse', displayName: '末日生存', tagline: '废土求生,饥饿是另一个敌人。撑过下一个夜晚。', vibeTag: '荒凉 · 绝境', active: true },
  { archetype: 'cthulhu', displayName: '克苏鲁', tagline: '凝视深渊,深渊回以低语。知道得越多,离疯狂越近。', vibeTag: '深渊 · 疯狂', active: true },
  { archetype: 'cultivation', displayName: '修仙', tagline: '逆天改命,踏上仙途。一念成圣,一念成魔。', vibeTag: '缥缈 · 仙途', active: true },
  { archetype: 'life_sim', displayName: '人生模拟', tagline: '一生只有一次,而它大部分时候都很寻常。', vibeTag: '平常 · 一生', active: true },
  { archetype: 'animal_life', displayName: '动物人生', tagline: '熟悉的一切变了，你学会的规则还作数吗？', vibeTag: '视角错位 · 误读回收', active: true },
  { archetype: 'cyberpunk', displayName: '赛博朋克', tagline: null, vibeTag: null, active: false },
];

const HOST = 'cultivation';
const FOREIGN = 'cthulhu';

type Frame = 1 | 2 | 3 | 4 | 5;

/**
 * 被拖者相对「正对修仙卡位」的偏移。dy 以卡高 H 为单位(在修仙卡位之下的量),
 * 取值的唯一约束:两个标题都露在外面。
 */
const POSE: Record<1 | 2, { dyH: number; dx: number; rot: number }> = {
  1: { dyH: 0.56, dx: 14, rot: 3.5 }, // 交接:斜压,压到修仙标题行之下
  2: { dyH: 0.5, dx: 0, rot: 0 }, // 靠拢:摆正,只差一个标题行
};

const noop = () => {};
/** 真卡的「抓起」样式令牌(.cardDragging:层级 + 投影),手势一概不接。 */
const LIFTED: CardDragBinding = {
  ref: noop,
  onPointerDown: noop,
  shouldSwallowClick: () => false,
  state: 'dragging',
};

function readParams() {
  const q = new URLSearchParams(location.search);
  const n = Number(q.get('frame'));
  const frame = ([1, 2, 3, 4, 5].includes(n) ? n : 1) as Frame;
  const variant = q.get('v') === 'B' ? 'B' : 'A';
  const t = Number(q.get('t') ?? 2000);
  return { frame, variant, t: Number.isFinite(t) ? t : 2000 } as const;
}

export function YanqiKeyframes() {
  const { frame, variant, t } = readParams();
  const foreignRef = useRef<HTMLDivElement>(null);
  const hostRef = useRef<HTMLDivElement>(null);
  const [pose, setPose] = useState<string | undefined>(undefined);

  // 量出两张卡的真实间距与卡高,再摆被拖者(不写死像素:卡高随字体与视口变)。
  useLayoutEffect(() => {
    if (frame !== 1 && frame !== 2) return;
    const f = foreignRef.current?.getBoundingClientRect();
    const h = hostRef.current?.getBoundingClientRect();
    if (!f || !h) return;
    const p = POSE[frame];
    const dy = h.top - f.top + p.dyH * h.height;
    setPose(`translate(${p.dx}px, ${dy}px) rotate(${p.rot}deg)`);
  }, [frame]);

  // 所有 CSS 动画停在 t 毫秒(含伪元素上的)。
  useLayoutEffect(() => {
    const freeze = () =>
      document.getAnimations().forEach((a) => {
        a.pause();
        a.currentTime = t;
      });
    freeze();
    const id = requestAnimationFrame(() => {
      freeze();
      document.documentElement.dataset.kfReady = '1';
    });
    return () => cancelAnimationFrame(id);
  }, [t, pose]);

  const cards: ReactNode[] = CATALOG.map((a) => {
    const card = (
      <ArchetypeCard summary={a} onChoose={noop} drag={a.archetype === FOREIGN && frame <= 2 ? LIFTED : undefined} />
    );

    if (a.archetype === FOREIGN) {
      // ①② 被拖者压在修仙上;③ 起已收进石面,只留空位。
      const moving = frame <= 2;
      return (
        <div
          key={a.archetype}
          ref={foreignRef}
          data-kf-slot="foreign"
          className={moving ? kf.lifted : kf.hidden}
          style={moving ? { transform: pose, visibility: pose ? undefined : 'hidden' } : undefined}
        >
          {card}
        </div>
      );
    }

    if (a.archetype === HOST) {
      if (frame === 5) {
        // ⑤ 真融合卡在同一卡位显形(cardFusionYanqi 原样)。
        return (
          <div key={a.archetype} ref={hostRef} data-kf-slot="host" className={kf.slot}>
            <FusionCard combo={`${HOST}×${FOREIGN}`} onChoose={noop} />
          </div>
        );
      }
      const stone = frame === 3 || frame === 4;
      return (
        <div key={a.archetype} ref={hostRef} data-kf-slot="host" className={kf.slot}>
          {/* ③④:真修仙卡只当尺寸占位(visibility 隐藏),石面叠在同一位置、同一尺寸。 */}
          <div className={stone ? kf.hidden : undefined}>{card}</div>
          {stone && (
            <div className={[kf.stone, frame === 3 && variant === 'B' ? kf.stoneB : ''].join(' ')} data-kf="stone">
              <div
                className={[
                  kf.seal,
                  frame === 3 && variant === 'B' ? kf.sealB : '',
                  frame === 4 ? kf.sealFading : '',
                ].join(' ')}
                data-kf="seal"
              />
            </div>
          )}
        </div>
      );
    }

    return (
      <div key={a.archetype} className={kf.dim}>
        {card}
      </div>
    );
  });

  return (
    <main className={styles.screen}>
      <header className={styles.selectHeader}>
        <p className={styles.phase}>AI Universe Simulator</p>
        <h1 className={styles.title}>选择你的世界</h1>
        <p className={styles.muted}>每一个世界都由 AI 即时生成,真假难辨,不可回头。</p>
      </header>
      <div className={styles.cardList}>{cards}</div>
    </main>
  );
}
