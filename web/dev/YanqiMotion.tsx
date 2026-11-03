import { useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
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
  cardText: [760, 900], // 两张原卡的文字先退(赶在融合面变实之前),不从半透明的融合面底下透出来
  merge: [760, 1060], // 两张卡一起收成融合面:融合面盖在两张卡上面淡入,浅色接修仙、深青接克苏鲁
  tuck: [760, 1180], // 同时克苏鲁在融合面底下慢慢收进卡位(露在卡位下面的那截缩回去)
  corner: [880, 1160], // 焦印:重角在融合面成形的过程中就开始冒
  edges: [1080, 2100], // 细边顺着重角往外烧;右边中部烧蚀在前沿经过时出现
  green: [1980, 2380], // 冷绿最后一点点透出来
  reveal: [2560, 3600], // 成品卡从焦印下沿起,一条边界向上推过整张;焦印同时退去
  text: [3150, 3510], // 边界推过约八成时文字就开始浮现,卡面铺满时字已在,不留没字的空卡
  streak: [3700, 4220], // 宽斜光带(开关)
  restore: [4100, 4500], // 其余卡恢复
  end: [4500, 11700], // ⑤ 之后再留 7.2 秒:看得到标题停满一个循环、在 RELEASE 处接进正常轮换
} as const;

/** reduced-motion:克苏鲁先退(0–160)→ 成品卡底色盖过修仙(120–380)→ 文字(320–460);两套文字从不同屏。 */
const REDUCED = { out: [0, 160], fade: [120, 380], text: [320, 460], end: [460, 1700] } as const;

/** 卡上 CSS 动画在挂载后统一拨到这个时刻再走(入场动画已走完,与静态帧的冻结点一致)。 */
const CSS_BASE = 2000;
/** 标题轮换一周 5.2s;「验讫」驻留在 16%–55%(832–2860ms)。 */
const TITLE_CYCLE = 5200;
/**
 * 新生融合卡「先停后转」(Felix 选定 A):文字出现起,标题停在「验讫」满一个循环(5.2s),
 * 之后才启动卡片自己的轮换。轮换的起点用负的 animation-delay 设在验讫驻留段里(18% = 936ms):
 * 停住时动画暂停在 currentTime 0(= 局部 936ms,验讫 1、另两层 0),到 RELEASE 原地开播 ——
 * 停与转是同一个动画的同一帧,衔接点上不会空一帧,也不会跳。
 */
const TITLE_START = 936;
const RELEASE = BEATS.text[0] + TITLE_CYCLE;

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
    __kf?: { duration: number; release?: number; seek: (ms: number) => void };
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

  useLayoutEffect(() => {
    const f = foreignRef.current;
    const hostCard = hostCardRef.current;
    const fused = fusedRef.current;
    const surface = surfaceRef.current;
    const reveal = revealRef.current;
    if (!f || !hostCard || !fused || !surface || !reveal) return;

    // 卡上的 CSS 动画(云气、蠕动……)统一拨到 CSS_BASE,与时间线同速。
    // 成品卡的三层标题单独管:暂停在 currentTime 0(验讫),到 RELEASE 才开播(见 TITLE_START)。
    const all = document.getAnimations();
    const isTitle = (a: Animation) =>
      ((a.effect as KeyframeEffect | null)?.target as Element | null)?.closest('[data-kf="reveal"] h2') != null;
    const titleAnims = all.filter(isTitle);
    const cssAnims = all.filter((a) => !isTitle(a));
    cssAnims.forEach((a) => {
      a.currentTime = CSS_BASE;
      a.playbackRate = speed;
    });
    titleAnims.forEach((a) => {
      a.pause();
      a.currentTime = 0;
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
        gsap.set(reveal, { '--kf-reveal-full': 1, '--kf-text': 0, '--kf-streak': streak ? 1 : 0 });
        // 先让斜压着的克苏鲁卡退掉(否则它错开的边在淡化中途和卡位叠成两道),再交叉淡化成品卡底色,
        // 最后才出文字 —— 中途不会有两套文字叠在一起。
        const out = span(REDUCED.out);
        const fade = span(REDUCED.fade);
        const text = span(REDUCED.text);
        tl.to(f, { opacity: 0, duration: out.dur, ease: 'sine.out' }, out.at)
          .to(fused, { opacity: 1, duration: fade.dur, ease: 'sine.inOut' }, fade.at)
          .set(hostCard, { opacity: 0 }, fade.at + fade.dur)
          .to(reveal, { '--kf-text': 1, duration: text.dur, ease: 'sine.out' }, text.at)
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
          '--kf-front-p': '22%',
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

        // 两张卡一起收成融合面(第二轮):融合面盖在两张卡**上面**淡入(层级高于被拖者),
        // 它的浅色在上、深青在下,正好接住 ② 里修仙露在上面、克苏鲁压在下半的样子;
        // 克苏鲁同时在它底下慢慢上移收进卡位,露在卡位下面的那一截缩回去、随之淡掉。
        // 收得比融合面成形慢(sine.in):融合面没成形之前,修仙那条浅色一直还露着 ——
        // 第一版是克苏鲁先整张盖住卡位再溶掉,中间有约 200ms 只剩克苏鲁一张卡。
        // 两张原卡的文字先退:融合面半透明时,底下的「克苏鲁」「修仙」会透出来一下(第二轮约 1000ms)。
        // 文字门控是 dev 样式里的 --kf-card-text(只作用于这两张卡的文字层),卡片样式未改。
        // 起值要显式给 1:变量未设时 GSAP 把它读成 0,整段一开始两张卡就没了字(试过)。
        const ct = span(BEATS.cardText);
        gsap.set([f, hostCard], { '--kf-card-text': 1 });
        tl.to([f, hostCard], { '--kf-card-text': 0, duration: ct.dur, ease: 'sine.in' }, ct.at);
        const mg = span(BEATS.merge);
        const tk = span(BEATS.tuck);
        tl.to(fused, { opacity: 1, duration: mg.dur, ease: 'sine.inOut' }, mg.at);
        tl.to(f, { y: toHost, scale: 1, duration: tk.dur, ease: 'sine.in' }, tk.at);
        tl.to(f, { opacity: 0, duration: tk.dur * 0.45, ease: 'sine.in' }, tk.at + tk.dur * 0.55);
        tl.set(hostCard, { opacity: 0 }, tk.at + tk.dur);

        // 焦印:重角先冒,两臂随之伸出一小截。缓动用先快后慢(sine.out):融合面一成形,重角就已经看得见 ——
        // 第一版用先慢后快(sine.in),1200–1500ms 那段融合面上只有一个淡点,接近一块空石条。
        const co = span(BEATS.corner);
        tl.to(fused, { '--kf-s-corner': 1, duration: co.dur, ease: 'sine.out' }, co.at);
        tl.to(fused, { '--kf-s-top': 0.3, '--kf-s-left': 0.3, duration: co.dur, ease: 'sine.out' }, co.at);

        // 细边顺着重角往外烧:上 / 左先,右 / 下接着(右边从上往下,经过中部时烧蚀那团洇开)。
        const ed = span(BEATS.edges);
        // 上 / 左先快后慢(power1.out):融合面一成形,两条细边已经在往外走,不留「只有一个点」的空档。
        tl.to(fused, { '--kf-s-top': 1, duration: ed.dur * 0.62, ease: 'power1.out' }, ed.at);
        tl.to(fused, { '--kf-s-left': 1, duration: ed.dur * 0.62, ease: 'power1.out' }, ed.at + ed.dur * 0.05);
        tl.to(fused, { '--kf-s-right': 1, duration: ed.dur * 0.5, ease: 'power1.inOut' }, ed.at + ed.dur * 0.45);
        tl.to(fused, { '--kf-s-bottom': 1, duration: ed.dur * 0.5, ease: 'power1.inOut' }, ed.at + ed.dur * 0.5);
        tl.to(fused, { '--kf-s-breach': 1, duration: ed.dur * 0.3, ease: 'sine.out' }, ed.at + ed.dur * 0.62);

        // 冷绿最后一点点透出来。
        const gr = span(BEATS.green);
        tl.to(fused, { '--kf-green-on': 1, duration: gr.dur, ease: 'sine.inOut' }, gr.at);

        // 揭晓(第二轮):一条边界从焦印下沿起向上推过整张(见 .revealFront),焦印在边界经过时退去。
        // 起点 22% 落在焦印下沿之下 —— 那里本就是克苏鲁的深青,显露起步时几乎看不出;
        // 推到 118% 时两条半平面都已越过整张。先慢后快再收(power1.inOut):起步像是从印底下渗出来,
        // 中段是清楚的推进,不是成团鼓胀(第一版三团各自放大,读成墨渍 / 烟雾)。
        const rv = span(BEATS.reveal);
        tl.to(fused, { '--kf-green-on': 0, duration: rv.dur * 0.15, ease: 'sine.out' }, rv.at);
        tl.to(reveal, { '--kf-front-p': '118%', duration: rv.dur, ease: 'power1.inOut' }, rv.at);
        tl.to(fused, { '--kf-burn-on': 0, duration: rv.dur * 0.65, ease: 'sine.inOut' }, rv.at + rv.dur * 0.15);
        // 边界到 118% 时两条半平面都已全实;整片补满只在收尾当一道保险(不提前淡,否则最后那个角是糊过去的,不是推过去的)。
        tl.set(reveal, { '--kf-reveal-full': 1 }, rv.at + rv.dur);
        tl.set(surface, { opacity: 0 }, rv.at + rv.dur);

        // 文字:卡面基本成形后才出现;光带(开关)随后。
        const tx = span(BEATS.text);
        tl.to(reveal, { '--kf-text': 1, duration: tx.dur, ease: 'sine.out' }, tx.at);
        if (streak) {
          const sk = span(BEATS.streak);
          tl.to(reveal, { '--kf-streak': 1, duration: sk.dur, ease: 'sine.inOut' }, sk.at);
        }

        // 标题停满一个循环后,原地接进卡片自己的轮换。
        tl.call(() => titleAnims.forEach((a) => a.play()), [], s(RELEASE));

        // 其余卡恢复;⑤ 静止 ≥ 1 秒。
        const rs = span(BEATS.restore);
        tl.to(others, { opacity: 1, duration: rs.dur, ease: 'sine.inOut' }, rs.at);
        tl.to({}, { duration: s(BEATS.end[1] - BEATS.end[0]) }, s(BEATS.end[0]));
      }

      if (rec) {
        cssAnims.forEach((a) => a.pause());
        // 定格录制时 tl.call 不负责开播(seek 直接按 RELEASE 换算标题的 currentTime)。
        window.__kf = {
          duration: tl.duration() * 1000,
          release: RELEASE,
          seek: (ms) => {
            tl.seek(ms / 1000, false);
            cssAnims.forEach((a) => {
              a.currentTime = CSS_BASE + ms;
            });
            titleAnims.forEach((a) => {
              a.pause();
              a.currentTime = Math.max(0, ms - RELEASE);
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
        <div key={a.archetype} ref={foreignRef} data-kf-slot="foreign" className={`${kf.lifted} ${kf.cardText}`}>
          <ArchetypeCard summary={a} onChoose={noop} drag={reduced ? undefined : LIFTED} />
        </div>
      );
    }
    if (a.archetype === HOST) {
      return (
        <div key={a.archetype} data-kf-slot="host" className={kf.slot}>
          <div ref={hostCardRef} className={kf.cardText}>
            <ArchetypeCard summary={a} onChoose={noop} />
          </div>
          {/* 融合面层级高于被拖者(lifted 30):收拢时它盖在两张卡上面淡入。 */}
          <div ref={fusedRef} className={kf.fused} data-kf="fused" style={{ zIndex: 31 }}>
            <div ref={surfaceRef} className={kf.surface}>
              {!reduced && <Seal />}
            </div>
            <div
              ref={revealRef}
              className={reduced ? kf.reveal : `${kf.reveal} ${kf.revealFront}`}
              data-kf="reveal"
              style={{ '--kf-title-delay': `${-TITLE_START}ms` } as CSSProperties}
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
