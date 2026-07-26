import { useEffect, useRef } from 'react';
import type { AxisView, StatsProps } from '../contract';
import { severityWord } from '../contract';
import styles from './xian.module.css';

// 修仙 · 数值形态「灵脉 + 境界印」(刀 2 移植样板间灵脉,刀 2.5 按缺陷打磨 + 加境界印)。
//
// ── 刀 2.5 的由来(记明,免得被读成范围膨胀)──────────────────────────────
// 灵脉是在样板间「简单标题 + 假数据 + 三轴」环境下验收的;进生产顶着写实场景图之后,
// 三根等宽细柱 + 纯白卡片读起来像**健康 App 面板**接在修仙画面下面。
// 这与 F-019 同类:**孤立验收通过、真实页面里塌**。故按缺陷修,且只修数值区。
//
// ── 与规则怪谈 OSD 是两种形态,不是换配色 ─────────────────────────────────
// 那边横向分段、等宽读数、状态灯(仪表盘);这边竖着走的光带自下而上蓄起(脉),
// 签名轴另起一形(印)。
//
// ── 主题层不得重新解释轴语义(ADR-018 §4.2,本刀第三次实例化)──────────────
//   · 危不危险 → 只认 `axis.severity`(服务端派生);样板间那版 `value < 35` 已在刀 2 换掉。
//   · 哪根轴特殊 → 只认 `axis.signature`(**通用层按注册表配置打的标**)。
//     **绝不写 `axis.key === 'realm'`** —— 那等于把「哪根轴特殊」从注册表复制进主题组件,
//     ADR-018 §5 Q5「锁在注册表内」当场失效。未登记签名轴的世界:全部 false → 整齐走灵脉。
//
// **数值滚动不在本文件**:`axis.value` 已是通用层插值后的当前显示值,档名与 severity 由
// 同一个值派生 —— 数字滚过阈值那一帧三者同帧翻转(§4.2.1)。

export function XianStats({ axes, rootRef }: StatsProps) {
  const sealRef = useRef<HTMLDivElement | null>(null);

  // 把境界印的中心写成主题根上的 `--seal-x/--seal-y`,好让**钟鸣的金光从印扩散**
  // 而不是从屏幕中心(「波纹从有意义的位置发出」)。走根上的自定义属性而不是让氛围层
  // 去 query 本组件的 DOM —— 后者会捅穿组件边界,且两边挂载顺序一变就静默失效。
  // 印不存在(无签名轴)→ 不写,氛围层回落到自己的缺省原点。
  // 依赖只有主题根与轴数:**刻意不随数值重跑** —— 数值每帧都在滚,印的位置却不动,
  // 每帧量一次 rect 等于每帧强制一次布局重算,白付。位置真正会变的是滚动与视口尺寸,
  // 那两条各自挂在事件上(rAF 合并,一帧最多量一次)。
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    let queued = false;
    const write = () => {
      queued = false;
      const el = sealRef.current;
      if (!el) return;
      const r = el.getBoundingClientRect();
      root.style.setProperty('--seal-x', `${Math.round(r.left + r.width / 2)}px`);
      root.style.setProperty('--seal-y', `${Math.round(r.top + r.height / 2)}px`);
    };
    const schedule = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(write);
    };
    write();
    window.addEventListener('scroll', schedule, { passive: true });
    window.addEventListener('resize', schedule, { passive: true });
    return () => {
      window.removeEventListener('scroll', schedule);
      window.removeEventListener('resize', schedule);
      root.style.removeProperty('--seal-x');
      root.style.removeProperty('--seal-y');
    };
  }, [rootRef, axes.length]);

  return (
    <div className={styles.lingmai}>
      {axes.map((axis) =>
        axis.signature ? (
          <RealmSeal key={axis.key} axis={axis} sealRef={sealRef} />
        ) : (
          <MaiColumn key={axis.key} axis={axis} />
        ),
      )}
    </div>
  );
}

/** 视觉状态:**只认 severity**,不认 key、不认 label、不认数值高低。 */
function severityClass(axis: AxisView): string {
  if (axis.severity === 'danger') return styles.danger;
  if (axis.severity === 'caution') return styles.caution;
  return '';
}

/**
 * 风险字样只在 caution / danger 露面:常态不加字是**呈现选择**(这片天该是静的),
 * 不是语义判断 —— 完整语义仍由通用层写进 a11yText 交给读屏。
 */
const riskWord = (axis: AxisView) =>
  axis.severity === 'neutral' ? null : severityWord(axis.severity);

/**
 * 签名轴的形态:**境界印**(八边玉印)。
 *
 * **层级来自形态差异(流动 vs 凝结),不是面积差异**(Felix 2026-07-26 改口径:
 * 第一版把印做成占右侧整块宽度的矩形卡,读起来是「被放大的信息卡」而不是印 ——
 * 面积是最粗糙的层级手段)。故:体量收到与灵脉相近、切八角去掉矩形卡感、
 * 垂直居中收束**不与灵脉顶对齐**(左边流动、右边凝结,印是稳定的锚点)。
 *
 * 文字权重三档拉开:眉题「境界」最轻 → **档名是主视觉** → 数字降一档。
 *
 * 仍然消费 severity:境界是 accumulation 且 `perilAtHigh=false` → 服务端派生恒 neutral,
 * 所以这条路径实际上永不染红 —— 但**代码不为此假设写死**(换个世界把签名轴挂到别的轴上,
 * 印照样该按服务端给的等级变色;主题层无权假定「签名轴一定安全」)。
 *
 * 配色刻意用**深金/古铜**而非朱砂红:红在本界面已被 severity 的 danger 占用,
 * 一个恒 neutral 的印染成红色会读成警报。
 */
function RealmSeal({
  axis,
  sealRef,
}: {
  axis: AxisView;
  sealRef: React.RefObject<HTMLDivElement | null>;
}) {
  const risk = riskWord(axis);
  const cls = [styles.seal, severityClass(axis)].filter(Boolean).join(' ');
  return (
    <div className={cls} aria-label={axis.a11yText} ref={sealRef}>
      {/* 印面:八角内切一层,与外层的印边之间留出一道极细的边(不用 border —— clip-path 会切掉它) */}
      <div className={styles.sealFace} aria-hidden="true">
        {/* 印泥深浅 = 当前值(仍保留量的读法,只是让位给档名) */}
        <div className={styles.sealFill} style={{ height: `${axis.percent}%` }} />
        {/* 极淡内圈:玉印的一线光,不上纹样 */}
        <div className={styles.sealRing} />
      </div>
      <div className={styles.sealBody}>
        <span className={styles.sealName}>{axis.displayName}</span>
        {axis.bandLabel && <span className={styles.sealBand}>{axis.bandLabel}</span>}
        <span className={styles.sealNum}>{axis.value}</span>
        {risk && <span className={styles.maiRisk}>{risk}</span>}
      </div>
    </div>
  );
}

/** 非签名轴的形态:灵脉竖向光带(丹田在下,脉结在上)。 */
function MaiColumn({ axis }: { axis: AxisView }) {
  const risk = riskWord(axis);
  const cls = [styles.mai, severityClass(axis)].filter(Boolean).join(' ');
  return (
    <div className={cls} aria-label={axis.a11yText}>
      <span className={styles.maiName}>{axis.displayName}</span>
      <div className={styles.maiTrack}>
        <div className={styles.maiFill} style={{ height: `${axis.percent}%` }} />
      </div>
      <span className={styles.maiNum}>{axis.value}</span>
      {axis.bandLabel && <span className={styles.maiBand}>{axis.bandLabel}</span>}
      {risk && <span className={styles.maiRisk}>{risk}</span>}
    </div>
  );
}
