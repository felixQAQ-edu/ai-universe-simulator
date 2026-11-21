import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { stainLevelSpec, type SeepSurface, type StainSpec } from './registry';
import {
  DOTS,
  MASK_BLEED,
  PATH,
  anchor,
  cornerStates,
  isOwn,
  measureHoles,
  sameGeometry,
  shape,
  type StainGeometry,
} from './stainGeometry';
import styles from './seep.module.css';

// 墨洇(ADR-043 决策 3 / 6):一个承载框一层 SVG,画该档该出现的角落水渍。
//
// **避让(方案 M3,Felix 补裁 9 + 审定 3)**:层里带一个 `<mask>`,白底 + 框内每个文字行框(外扩 holePadPx)一块黑矩形,
// 墨渍的上色区域与任何文字行框的交集恒为空。行框读自**本层所在的承载框**的文本节点 —— 数值区那一层读的是宿主形态
// 组件(XianStats)的文本:这是 ADR-018「不 query 别的组件 DOM」在**只读几何**上的破口,Felix 审定 3 已认可、记入已知代价。
// 渗入层自己的节点(`[data-seep]`:墨渍层、验讫印)不计入行框。
//
// **过渡**:档变(上或下)→ 不透明度、显隐与尺寸走登记的 2400ms;尺寸用 `transform: scale()`,不动画化宽高(Motion §6)。
// 位置(贴哪个角)用外层 `<g>` 的 translate 承载、**不过渡** —— 框尺寸变了(换行、旋转屏)墨渍直接贴过去,不会「漂」过去。
// mask 在层的像素坐标里、不随墨渍缩放,过渡途中挖空照样成立。
// 首次装载 / 续局直接到位(挂载后两帧才放开过渡);reduced-motion 下一律不过渡(CSS)。

export function SeepStain({
  stain,
  level,
  surface,
}: {
  stain: StainSpec;
  /** 墨洇所盯那根轴的当前档序号(通用层算);null → 不画。 */
  level: number | null;
  surface: SeepSurface;
}) {
  const ref = useRef<SVGSVGElement>(null);
  const [geo, setGeo] = useState<StainGeometry | null>(null);
  const [settled, setSettled] = useState(false);
  const maskId = `seep-mask-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const spec = stainLevelSpec(stain, level);
  const visible = !!spec;
  const pad = stain.holePadPx;

  // 量:挂载即量一次(layout effect,首帧就带着挖空画出来);之后框尺寸、框内文字、字体任一变化 → rAF 合并重量。
  useLayoutEffect(() => {
    const layer = ref.current;
    const host = layer?.parentElement;
    if (!visible || !layer || !host) return;
    let raf = 0;
    let alive = true;
    const run = () => {
      raf = 0;
      if (!alive) return;
      const next = measureHoles(layer, pad);
      setGeo((prev) => (sameGeometry(prev, next) ? prev : next));
    };
    const schedule = () => {
      if (!raf && alive) raf = requestAnimationFrame(run);
    };
    run();
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    ro?.observe(host);
    const mo = new MutationObserver((records) => {
      if (records.some((r) => !isOwn(r.target))) schedule();
    });
    mo.observe(host, {
      subtree: true,
      childList: true,
      characterData: true,
      attributes: true,
      attributeFilter: ['class', 'style'],
    });
    const fonts = typeof document !== 'undefined' ? document.fonts : undefined;
    fonts?.ready?.then(schedule, () => {});
    fonts?.addEventListener?.('loadingdone', schedule);
    return () => {
      alive = false;
      if (raf) cancelAnimationFrame(raf);
      ro?.disconnect();
      mo.disconnect();
      fonts?.removeEventListener?.('loadingdone', schedule);
    };
  }, [visible, pad]);

  // 首次画出后两帧才放开过渡:首次装载与续局直接到位。
  const drawn = !!geo;
  useEffect(() => {
    if (!drawn || settled) return;
    let r2 = 0;
    const r1 = requestAnimationFrame(() => {
      r2 = requestAnimationFrame(() => setSettled(true));
    });
    return () => {
      cancelAnimationFrame(r1);
      if (r2) cancelAnimationFrame(r2);
    };
  }, [drawn, settled]);

  if (!spec || level === null) return null;
  const corners = geo ? cornerStates(stain, level, surface) : [];
  const style = {
    '--fo': spec.fillOpacity,
    '--so': spec.strokeOpacity,
    '--seep-ms': `${stain.transitionMs}ms`,
    '--seep-ink': stain.color,
  } as React.CSSProperties;

  return (
    <svg
      ref={ref}
      className={settled ? styles.layer : `${styles.layer} ${styles.still}`}
      style={style}
      data-seep="stain"
      aria-hidden="true"
      focusable="false"
    >
      {geo && (
        <>
          <defs>
            <mask
              id={maskId}
              maskUnits="userSpaceOnUse"
              x={-MASK_BLEED}
              y={-MASK_BLEED}
              width={geo.w + MASK_BLEED * 2}
              height={geo.h + MASK_BLEED * 2}
            >
              <rect
                x={-MASK_BLEED}
                y={-MASK_BLEED}
                width={geo.w + MASK_BLEED * 2}
                height={geo.h + MASK_BLEED * 2}
                fill="#fff"
              />
              {geo.holes.map((b, i) => (
                <rect key={i} x={b.x} y={b.y} width={b.w} height={b.h} fill="#000" />
              ))}
            </mask>
          </defs>
          <g mask={`url(#${maskId})`}>
            {corners.map((c) => {
              const [ax, ay] = anchor(c.corner, geo.w, geo.h);
              return (
                <g key={c.key} transform={`translate(${ax} ${ay})`}>
                  <g
                    className={styles.stain}
                    data-corner={c.key}
                    data-on={c.on ? '1' : '0'}
                    style={{ opacity: c.on ? 1 : 0, transform: shape(c.corner, c.w, c.h) }}
                  >
                    <path d={PATH} />
                    {DOTS.map(([cx, cy, r]) => (
                      <circle key={`${cx}-${cy}`} cx={cx} cy={cy} r={r} />
                    ))}
                  </g>
                </g>
              );
            })}
          </g>
        </>
      )}
    </svg>
  );
}
