import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { ArchetypeCard, FusionCard } from '../src/features/game/ArchetypeSelect';
import styles from '../src/features/game/game.module.css';
import kf from './keyframes.module.css';
import { Seal } from './Seal';
import { CATALOG, FOREIGN, HOST, LIFTED, POSE, noop } from './keyframeData';

// 《验讫》五帧的画面本身(入口与说明见 yanqi-keyframes.tsx)。

type Frame = 1 | 2 | 3 | 4 | 5;

function readParams() {
  const q = new URLSearchParams(location.search);
  const n = Number(q.get('frame'));
  const frame = ([1, 2, 3, 4, 5].includes(n) ? n : 1) as Frame;
  const t = Number(q.get('t') ?? 2000);
  return { frame, t: Number.isFinite(t) ? t : 2000 } as const;
}

export function YanqiKeyframes() {
  const { frame, t } = readParams();
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
      const fused = frame === 3 || frame === 4;
      return (
        <div key={a.archetype} ref={hostRef} data-kf-slot="host" className={kf.slot}>
          {/* ③④:真修仙卡只当尺寸占位(visibility 隐藏),融合面叠在同一位置、同一尺寸。 */}
          <div className={fused ? kf.hidden : undefined}>{card}</div>
          {fused && (
            <div className={kf.fused} data-kf="fused">
              <div className={kf.surface}>
                <Seal fading={frame === 4} />
              </div>
              {/* ④:⑤ 那张真成品卡在最终位置、最终尺寸,由不规则遮罩露出一部分;文字与光带不显。 */}
              {frame === 4 && (
                <div className={kf.reveal} data-kf="reveal">
                  <FusionCard combo={`${HOST}×${FOREIGN}`} onChoose={noop} />
                </div>
              )}
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
