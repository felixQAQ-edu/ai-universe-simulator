import { useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import gsap from 'gsap';
import { ArchetypeCard, FusionCard } from '../src/features/game/ArchetypeSelect';
import styles from '../src/features/game/game.module.css';
import kf from './keyframes.module.css';
import { Seal } from './Seal';
import { CATALOG, COMBO, FOREIGN, HOST, LIFTED, POSE, noop } from './keyframeData';

// 《验讫》短运动稿(刀 2b,dev-only):① 松手 → ② 停一拍 → ③ 焦印烧出来 → ④ 同一块卡面转成 ⑤。
//
// 只动 transform / opacity / mask / CSS 自定义属性(mask 本身也由自定义属性驱动),不碰布局。
// 画面层与静态关键帧完全同源(同一套 .fused / .surface / Seal / .reveal);静态帧取各变量的缺省值,
// 这里由一条 GSAP 时间线推这些变量。
//
// 打开:npm run dev → /dev/yanqi-keyframes.html?play=1[&speed=0.25][&streak=0][&reduced=1]
// 录制:加 rec=1 —— 不显示控件、不自动播放,页面挂 window.__kf.seek(ms) 供逐帧定格。

/** 每一拍的起止(毫秒,原速)。理由见交付说明;原速验收后再调。 */
const BEATS = {
  settle: [0, 420], // ①→②:摆正、靠拢(减速落定)
  hold: [420, 760], // ② 停一拍(极轻的下压,不是冻住)
  align: [760, 940], // ② 收进修仙卡位
  merge: [900, 1180], // 两张卡收成一面:融合面在同一个盒子里淡入(克苏鲁单独盖住卡位的时间压到最短)
  corner: [1000, 1380], // 焦印:重角先冒出来(闷烧)—— 与收拢重叠,融合面一成形上面已经有焦痕
  edges: [1360, 2100], // 细边顺着重角往外烧;右边中部烧蚀在前沿经过时出现
  green: [1980, 2380], // 冷绿最后一点点透出来
  reveal: [2560, 3600], // 成品卡从焦印右下方显露、向外推进;焦印同时退去
  text: [3520, 3880], // 卡面基本成形后,文字才清楚出现(与显露的收尾重叠,不留一张没字的空卡)
  streak: [3700, 4220], // 宽斜光带(开关)
  restore: [4100, 4500], // 其余卡恢复
  end: [4500, 5700], // ⑤ 静止 ≥ 1 秒
} as const;

const REDUCED = { fade: [0, 380], end: [380, 1600] } as const;

/** 卡上 CSS 动画在挂载后统一拨到这个时刻再走(入场动画已走完,与静态帧的冻结点一致)。 */
const CSS_BASE = 2000;
/** 标题轮换一周 5.2s;「验讫」驻留在 16%–55%。文字出现那一刻要落在驻留段开头稍后一点。 */
const TITLE_CYCLE = 5200;
const TITLE_TARGET = 936; // 18%

const s = (ms: number) => ms / 1000;
const span = ([a, b]: readonly [number, number]) => ({ at: s(a), dur: s(b - a) });

function readParams() {
  const q = new URLSearchParams(location.search);
  const reducedQuery = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
  return {
    speed: q.get('speed') === '0.25' ? 0.25 : 1,
    streak: q.get('streak') !== '0',
    reduced: q.get('reduced') === '1' || reducedQuery,
    rec: q.get('rec') === '1',
  };
}

declare global {
  interface Window {
    __kf?: { duration: number; seek: (ms: number) => void };
  }
}

export function YanqiMotion() {
  const [init] = useState(readParams);
  const [run, setRun] = useState(0);
  const [speed, setSpeed] = useState(init.speed);
  const [streak, setStreak] = useState(init.streak);

  const bar: CSSProperties = {
    position: 'fixed',
    left: 0,
    right: 0,
    bottom: 0,
    zIndex: 100,
    display: 'flex',
    gap: 8,
    justifyContent: 'center',
    padding: '10px 16px calc(10px + env(safe-area-inset-bottom))',
    background: 'rgba(10, 9, 14, 0.85)',
  };
  const btn: CSSProperties = { font: '14px system-ui', padding: '8px 12px', borderRadius: 8, border: '1px solid #444', background: '#1b1722', color: '#ece9f1' };

  return (
    <>
      <Stage key={run} speed={speed} streak={streak} reduced={init.reduced} rec={init.rec} />
      {!init.rec && (
        <div style={bar}>
          <button type="button" style={btn} onClick={() => setRun((n) => n + 1)}>
            重播
          </button>
          <button
            type="button"
            style={btn}
            onClick={() => {
              setSpeed((v) => (v === 1 ? 0.25 : 1));
              setRun((n) => n + 1);
            }}
          >
            {speed === 1 ? '原速' : '¼ 速'}
          </button>
          <button
            type="button"
            style={btn}
            onClick={() => {
              setStreak((v) => !v);
              setRun((n) => n + 1);
            }}
          >
            光带:{streak ? '开' : '关'}
          </button>
        </div>
      )}
    </>
  );
}

function Stage({ speed, streak, reduced, rec }: { speed: number; streak: boolean; reduced: boolean; rec: boolean }) {
  const foreignRef = useRef<HTMLDivElement>(null);
  const hostCardRef = useRef<HTMLDivElement>(null);
  const fusedRef = useRef<HTMLDivElement>(null);
  const surfaceRef = useRef<HTMLDivElement>(null);
  const revealRef = useRef<HTMLDivElement>(null);
  const othersRef = useRef<HTMLDivElement[]>([]);

  // 负的 animation-delay:让「文字出现那一刻」标题轮换正好落在「验讫」驻留段。
  // 挂载后 CSS 动画被拨到 CSS_BASE,所以那一刻的局部时间 = -delay + CSS_BASE + text 起点。
  const titleDelay = useMemo(() => {
    const textAt = BEATS.text[0];
    const local = (((TITLE_TARGET - CSS_BASE - textAt) % TITLE_CYCLE) + TITLE_CYCLE) % TITLE_CYCLE;
    return -local;
  }, []);

  useLayoutEffect(() => {
    const f = foreignRef.current;
    const hostCard = hostCardRef.current;
    const fused = fusedRef.current;
    const surface = surfaceRef.current;
    const reveal = revealRef.current;
    if (!f || !hostCard || !fused || !surface || !reveal) return;

    // 卡上的 CSS 动画(云气、蠕动、标题轮换……)统一拨到 CSS_BASE,与时间线同速。
    const cssAnims = document.getAnimations();
    cssAnims.forEach((a) => {
      a.currentTime = CSS_BASE;
      a.playbackRate = speed;
    });

    const fr = f.getBoundingClientRect();
    const hr = hostCard.getBoundingClientRect();
    const H = hr.height;
    const toHost = hr.top - fr.top;
    const others = othersRef.current.filter(Boolean);

    const ctx = gsap.context(() => {
      const tl = gsap.timeline({ paused: true });

      if (reduced) {
        // reduced-motion:① → ⑤ 一次短交叉淡化;不演焦印,不碎不转。
        gsap.set(f, { x: POSE[1].dx, y: toHost + POSE[1].dyH * H, rotation: POSE[1].rot });
        gsap.set(surface, { opacity: 0 });
        gsap.set(fused, { opacity: 0 });
        gsap.set(reveal, { '--kf-reveal-full': 1, '--kf-text': 1, '--kf-streak': streak ? 1 : 0 });
        const fade = span(REDUCED.fade);
        tl.to(f, { opacity: 0, duration: fade.dur * 0.6, ease: 'sine.out' }, fade.at)
          .to(fused, { opacity: 1, duration: fade.dur, ease: 'sine.inOut' }, fade.at)
          .set(hostCard, { opacity: 0 }, fade.at + fade.dur)
          .to({}, { duration: s(REDUCED.end[1] - REDUCED.end[0]) }, s(REDUCED.end[0]));
      } else {
        // 起点 = ① 的姿态(松手那一刻)。
        gsap.set(f, { x: POSE[1].dx, y: toHost + POSE[1].dyH * H, rotation: POSE[1].rot, scale: 1 });
        gsap.set(fused, {
          opacity: 0,
          '--kf-s-corner': 0,
          '--kf-s-breach': 0,
          '--kf-s-top': 0,
          '--kf-s-left': 0,
          '--kf-s-right': 0,
          '--kf-s-bottom': 0,
          '--kf-green-on': 0,
          '--kf-burn-on': 1,
        });
        gsap.set(reveal, {
          '--kf-rk1': 0.6,
          '--kf-rk2': 0.6,
          '--kf-rk3': 0.6,
          '--kf-ra1': 0,
          '--kf-ra2': 0,
          '--kf-ra3': 0,
          '--kf-reveal-full': 0,
          '--kf-text': 0,
          '--kf-streak': 0,
        });

        // ①→② 摆正、靠拢;其余卡同时淡下去。
        const st = span(BEATS.settle);
        tl.to(f, { x: POSE[2].dx, y: toHost + POSE[2].dyH * H, rotation: 0, duration: st.dur, ease: 'power2.out' }, st.at);
        tl.to(others, { opacity: 0.22, duration: s(360), ease: 'power1.out' }, st.at);

        // ② 停一拍:极轻的下压(scale 0.994),停住但不冻住。
        const hd = span(BEATS.hold);
        tl.to(f, { scale: 0.994, duration: hd.dur, ease: 'sine.inOut' }, hd.at);

        // 收进修仙卡位:完全对齐后,两张卡与融合面才在同一个盒子里 —— 交叉淡化时只有一道边。
        const al = span(BEATS.align);
        tl.to(f, { y: toHost, scale: 1, duration: al.dur, ease: 'power2.inOut' }, al.at);
        // 收成一面:融合面先在克苏鲁卡**底下**整片就位(被拖者层级在上,这时看不见),
        // 再让克苏鲁卡在上面溶掉 —— 真正的交叉溶解,中间不会透出页面底色。
        // (第一版是融合面在底下淡入、卡在收尾时一下隐藏:看上去就是一帧硬切。)
        const mg = span(BEATS.merge);
        tl.set(fused, { opacity: 1 }, mg.at);
        tl.to(f, { opacity: 0, duration: mg.dur, ease: 'sine.inOut' }, mg.at);
        tl.set(hostCard, { opacity: 0 }, mg.at + mg.dur);

        // 焦印:重角先冒,两臂随之伸出一小截。缓动用先快后慢(sine.out):融合面一成形,重角就已经看得见 ——
        // 第一版用先慢后快(sine.in),1200–1500ms 那段融合面上只有一个淡点,接近一块空石条。
        const co = span(BEATS.corner);
        tl.to(fused, { '--kf-s-corner': 1, duration: co.dur, ease: 'sine.out' }, co.at);
        tl.to(fused, { '--kf-s-top': 0.2, '--kf-s-left': 0.2, duration: co.dur, ease: 'sine.out' }, co.at);

        // 细边顺着重角往外烧:上 / 左先,右 / 下接着(右边从上往下,经过中部时烧蚀那团洇开)。
        const ed = span(BEATS.edges);
        tl.to(fused, { '--kf-s-top': 1, duration: ed.dur * 0.62, ease: 'power1.inOut' }, ed.at);
        tl.to(fused, { '--kf-s-left': 1, duration: ed.dur * 0.62, ease: 'power1.inOut' }, ed.at + ed.dur * 0.05);
        tl.to(fused, { '--kf-s-right': 1, duration: ed.dur * 0.5, ease: 'power1.inOut' }, ed.at + ed.dur * 0.45);
        tl.to(fused, { '--kf-s-bottom': 1, duration: ed.dur * 0.5, ease: 'power1.inOut' }, ed.at + ed.dur * 0.5);
        tl.to(fused, { '--kf-s-breach': 1, duration: ed.dur * 0.3, ease: 'sine.out' }, ed.at + ed.dur * 0.62);

        // 冷绿最后一点点透出来。
        const gr = span(BEATS.green);
        tl.to(fused, { '--kf-green-on': 1, duration: gr.dur, ease: 'sine.inOut' }, gr.at);

        // 揭晓:成品卡从焦印右下方显露、向外推进;冷绿先没,焦褐随后退去。
        // 三团错开起步:焦印右下那团(k1)最先,往右下(k2)、再往右上(k3)接力 —— 一起放大的话,
        // 最大、最远的那团会先冒出来,读成右上角一块墨渍(第一版就是这样)。
        // 每团先慢后快:先走到 ④ 的静态状态(k=1),再推开到整张;最后一层整片补满,再无遗漏的角。
        const rv = span(BEATS.reveal);
        tl.to(fused, { '--kf-green-on': 0, duration: rv.dur * 0.15, ease: 'sine.out' }, rv.at);
        (
          [
            [1, 0],
            [2, 0.15],
            [3, 0.3],
          ] as const
        ).forEach(([i, lag]) => {
          const at = rv.at + rv.dur * lag;
          const first = rv.dur * 0.4;
          tl.to(reveal, { [`--kf-ra${i}`]: 1, duration: rv.dur * 0.25, ease: 'sine.inOut' }, at);
          tl.to(reveal, { [`--kf-rk${i}`]: 1, duration: first, ease: 'sine.inOut' }, at);
          tl.to(reveal, { [`--kf-rk${i}`]: 7, duration: rv.at + rv.dur - (at + first), ease: 'power1.in' }, at + first);
        });
        tl.to(fused, { '--kf-burn-on': 0, duration: rv.dur * 0.65, ease: 'sine.inOut' }, rv.at + rv.dur * 0.15);
        tl.to(reveal, { '--kf-reveal-full': 1, duration: rv.dur * 0.35, ease: 'sine.in' }, rv.at + rv.dur * 0.65);
        tl.set(surface, { opacity: 0 }, rv.at + rv.dur);

        // 文字:卡面基本成形后才出现;光带(开关)随后。
        const tx = span(BEATS.text);
        tl.to(reveal, { '--kf-text': 1, duration: tx.dur, ease: 'sine.out' }, tx.at);
        if (streak) {
          const sk = span(BEATS.streak);
          tl.to(reveal, { '--kf-streak': 1, duration: sk.dur, ease: 'sine.inOut' }, sk.at);
        }

        // 其余卡恢复;⑤ 静止 ≥ 1 秒。
        const rs = span(BEATS.restore);
        tl.to(others, { opacity: 1, duration: rs.dur, ease: 'sine.inOut' }, rs.at);
        tl.to({}, { duration: s(BEATS.end[1] - BEATS.end[0]) }, s(BEATS.end[0]));
      }

      if (rec) {
        cssAnims.forEach((a) => a.pause());
        window.__kf = {
          duration: tl.duration() * 1000,
          seek: (ms) => {
            tl.seek(ms / 1000, false);
            cssAnims.forEach((a) => {
              a.currentTime = CSS_BASE + ms;
            });
          },
        };
        window.__kf.seek(0);
      } else {
        tl.timeScale(speed).play(0);
      }
    });

    document.documentElement.dataset.kfReady = '1';
    return () => {
      ctx.revert();
      delete window.__kf;
    };
  }, [speed, streak, reduced, rec]);

  let other = 0;
  const cards = CATALOG.map((a) => {
    if (a.archetype === FOREIGN) {
      return (
        <div key={a.archetype} ref={foreignRef} data-kf-slot="foreign" className={kf.lifted}>
          <ArchetypeCard summary={a} onChoose={noop} drag={reduced ? undefined : LIFTED} />
        </div>
      );
    }
    if (a.archetype === HOST) {
      return (
        <div key={a.archetype} data-kf-slot="host" className={kf.slot}>
          <div ref={hostCardRef}>
            <ArchetypeCard summary={a} onChoose={noop} />
          </div>
          <div ref={fusedRef} className={kf.fused} data-kf="fused">
            <div ref={surfaceRef} className={kf.surface}>
              {!reduced && <Seal />}
            </div>
            <div
              ref={revealRef}
              className={kf.reveal}
              data-kf="reveal"
              style={{ '--kf-title-delay': `${titleDelay}ms` } as CSSProperties}
            >
              <FusionCard combo={COMBO} onChoose={noop} />
            </div>
          </div>
        </div>
      );
    }
    const i = other++;
    return (
      <div
        key={a.archetype}
        ref={(el) => {
          if (el) othersRef.current[i] = el;
        }}
      >
        <ArchetypeCard summary={a} onChoose={noop} />
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
