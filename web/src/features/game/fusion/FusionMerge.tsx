import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import { BEATS, TOTAL_MS, knead, shatter, type Runtime } from './physics';
import { SHARDS, materialOf, type MatId, type Shard } from './shards';
import type { Rect } from './drag';
import styles from './fusion.module.css';

// 五拍揉合(融合入口的强视觉,ADR-019 §1「入口互噬」)。
//
// **入口的强视觉不得带进游戏内** —— 这一层只活在选择屏、只活 1 秒:
// 玩家停留约 1 秒、任务是宣告「两个世界撞在一起」,故允许强视觉;
// 局内是 50+ 回合的持续阅读,那边是 host 打底、foreign 只低频渗漏(B2-局内)。
//
// 形态:固定定位的一层覆盖,在两张真卡**原位**上各画一张卡面 →
// 挤压 → 各自释放专属材质碎解 → 揉合成世界核 → **停顿一拍** → 展开成新卡。
// 原卡不消耗:被揉碎的是投影不是世界本体,动画期间原卡只是让位(见 ArchetypeSelect)。
//
// 护栏:同时只允许一组融合动画运行 —— 由调用方在动画期间停摆手势(`enabled=false`)保证。

export interface FusionMergeProps {
  /** 承接者(host)与被拖者(foreign)的 archetype id。 */
  host: string;
  foreign: string;
  /** 两张卡在视口里的位置(提交那一刻量出的)。 */
  hostRect: Rect;
  foreignRect: Rect;
  /** 降级:不碎裂、不旋转 —— 重叠 + 短暂停顿 + 新卡淡入(停顿仍保留)。 */
  degraded?: boolean;
  /** 演完(或降级演完)回调:调用方据此插入融合卡并归还手势。 */
  onDone: () => void;
}

function ShardEl({ s, i, mat }: { s: Shard; i: number; mat: MatId }) {
  return (
    <i
      className={`${styles.shard} ${styles[`sh_${s.kind}`]} ${s.alt ? styles.isAlt : ''}`}
      data-i={i}
      data-mat={mat}
      data-kind={s.kind}
      data-arc={s.arc}
      data-branch={s.branch ? '' : undefined}
      data-pair={s.pair ? '' : undefined}
      style={
        {
          left: `${s.x}%`,
          top: `${s.y}%`,
          width: s.w,
          height: s.h,
          opacity: 0,
          '--rot': `${s.rot}deg`,
          ...(s.branch ? { '--branch': `${s.branch}deg` } : null),
          ...(s.pair ? { '--pair': `${s.pair}deg` } : null),
        } as React.CSSProperties
      }
    />
  );
}

export function FusionMerge({
  host,
  foreign,
  hostRect,
  foreignRect,
  degraded = false,
  onDone,
}: FusionMergeProps) {
  const root = useRef<HTMLDivElement>(null);
  const doneRef = useRef(onDone);
  useEffect(() => {
    doneRef.current = onDone;
  }, [onDone]);

  const hostMat = materialOf(host);
  const foreignMat = materialOf(foreign);

  useEffect(() => {
    const el = root.current;
    if (!el) return;
    const ctx = gsap.context(() => {
      // **元素定位走 data 属性,不走 CSS Modules 类名**:类名里少一条规则,
      // 选择器就会退化成 `.undefined` 而两个 div 恰好都能匹配上 —— 静默取到同一个元素
      // (第一版正是这样,四套材质一枚碎片没动却看着像成功,详见 fusion.module.css 顶部注)。
      const q = (sel: string) => el.querySelector<HTMLElement>(sel);
      const cardH = q('[data-role="face"][data-side="host"]');
      const cardF = q('[data-role="face"][data-side="foreign"]');
      const core = q('[data-role="core"]');
      if (!cardH || !cardF || !core) return;

      // 世界核落在两卡中心的中点 —— 两个世界真的是在「它们之间」撞出来的。
      const coreRect = core.getBoundingClientRect();
      const cx = coreRect.left + coreRect.width / 2;
      const cy = coreRect.top + coreRect.height / 2;

      const runtimes = (field: HTMLElement | null): Runtime[] => {
        if (!field) return [];
        const list = Array.from(field.querySelectorAll<HTMLElement>('i[data-kind]'));
        return list.map((node, i) => {
          const r = node.getBoundingClientRect();
          return {
            el: node,
            kind: node.dataset.kind ?? '',
            dx: cx - (r.left + r.width / 2),
            dy: cy - (r.top + r.height / 2),
            i,
            n: list.length,
          };
        });
      };

      const tl = gsap.timeline({ onComplete: () => doneRef.current() });

      // 降级路径(reduced-motion / 未登记材质):重叠 + 停顿 + 淡入,不碎裂不旋转。
      if (degraded) {
        const dx = (hostRect.left - foreignRect.left) / 2;
        const dy = (hostRect.top - foreignRect.top) / 2;
        tl.to(cardF, { x: dx, y: dy, duration: 0.28, ease: 'power2.inOut' }, 0)
          .to(cardH, { x: -dx, y: -dy, duration: 0.28, ease: 'power2.inOut' }, 0)
          .to([cardF, cardH], { opacity: 0.35, duration: 0.22 }, 0.28)
          .to({}, { duration: BEATS.hold / 1000 }) // 停顿仍保留
          .to([cardF, cardH], { opacity: 0, duration: 0.2 }, '>')
          .fromTo(core, { opacity: 0, scale: 0.9 }, { opacity: 1, scale: 1, duration: 0.3 }, '<');
        return;
      }

      const rH = runtimes(q('[data-role="field"][data-side="host"]'));
      const rF = runtimes(q('[data-role="field"][data-side="foreign"]'));

      const s = BEATS.squeeze / 1000;
      // 拍 1 挤压:两张卡被拉向接触中心,压扁、略微扭曲。
      const midX = (hostRect.left + foreignRect.left) / 2;
      const midY = (hostRect.top + foreignRect.top) / 2;
      tl.to(
        cardH,
        {
          x: (midX - hostRect.left) * 0.55,
          y: (midY - hostRect.top) * 0.55,
          scaleX: 0.9,
          scaleY: 1.06,
          skewY: 1.5,
          duration: s,
          ease: 'power3.in',
        },
        0,
      )
        .to(
          cardF,
          {
            x: (midX - foreignRect.left) * 0.55,
            y: (midY - foreignRect.top) * 0.55,
            scaleX: 0.9,
            scaleY: 1.06,
            skewY: -1.5,
            duration: s,
            ease: 'power3.in',
          },
          0,
        )
        // 卡面在碎解拍让位给碎片。
        .to([cardH, cardF], { opacity: 0, duration: (BEATS.shatter / 1000) * 0.6 }, s);

      // 拍 2 碎解:各自释放专属材质,**四套各走各的物理**。
      if (hostMat) shatter(tl, hostMat, rH, BEATS, s);
      if (foreignMat) shatter(tl, foreignMat, rF, BEATS, s);

      // 拍 3 揉合:围绕很小的中心互噬(两侧反向),迅速压缩成世界核。
      const k = s + BEATS.shatter / 1000;
      if (hostMat) knead(tl, hostMat, rH, BEATS, k, 1);
      if (foreignMat) knead(tl, foreignMat, rF, BEATS, k, -1);
      tl.to(
        core,
        { opacity: 1, scale: 1, duration: (BEATS.knead / 1000) * 0.75, ease: 'power2.in' },
        k + (BEATS.knead / 1000) * 0.3,
      );
      tl.to([...rH, ...rF].map((r) => r.el), { opacity: 0, duration: 0.1 }, k + (BEATS.knead / 1000) * 0.92);

      // 拍 4 停顿:世界核停住半拍 —— 新世界诞生前的重量,不能省。
      const h = k + BEATS.knead / 1000;
      tl.to(core, { scale: 0.92, duration: BEATS.hold / 1000, ease: 'none' }, h);

      // 拍 5 展开:不是爆炸,是一张被揉皱的纸重新展开(展开成什么由调用方接手)。
      const u = h + BEATS.hold / 1000;
      tl.to(
        core,
        { scaleX: 1.25, scaleY: 1.1, opacity: 0, duration: BEATS.unfold / 1000, ease: 'power2.out' },
        u,
      );
    }, root);

    // 兜底:GSAP 跑在 rAF 上,页面被切到后台时 timeline 可能永不完成
    // (ADR-018 §4.11 子模式 A:锁永占)。setTimeout 在后台仍跑(只是被钳到 ≥1s),
    // 到点无论动画走到哪都放行 —— onDone 幂等由调用方保证。
    const fallback = window.setTimeout(() => doneRef.current(), TOTAL_MS + 600);
    return () => {
      window.clearTimeout(fallback);
      ctx.revert();
    };
  }, [degraded, hostMat, foreignMat, hostRect, foreignRect]);

  const face = (rect: Rect): React.CSSProperties => ({
    left: rect.left,
    top: rect.top,
    width: rect.width,
    height: rect.height,
  });

  return (
    <div className={styles.stage} ref={root} aria-hidden="true" data-testid="fusion-merge">
      <div className={styles.face} data-role="face" data-side="host" data-mat={hostMat ?? ''} style={face(hostRect)} />
      <div
        className={styles.face}
        data-role="face"
        data-side="foreign"
        data-mat={foreignMat ?? ''}
        style={face(foreignRect)}
      />
      {hostMat && (
        <div className={styles.field} data-role="field" data-side="host" data-mat={hostMat} style={face(hostRect)}>
          {SHARDS[hostMat].map((s, i) => (
            <ShardEl key={i} s={s} i={i} mat={hostMat} />
          ))}
        </div>
      )}
      {foreignMat && (
        <div
          className={styles.field}
          data-role="field"
          data-side="foreign"
          data-mat={foreignMat}
          style={face(foreignRect)}
        >
          {SHARDS[foreignMat].map((s, i) => (
            <ShardEl key={i} s={s} i={i} mat={foreignMat} />
          ))}
        </div>
      )}
      <div
        className={styles.core}
        data-role="core"
        style={{
          left: (hostRect.left + foreignRect.left) / 2 + hostRect.width / 2,
          top: (hostRect.top + foreignRect.top) / 2 + hostRect.height / 2,
        }}
      />
    </div>
  );
}
