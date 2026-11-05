import { forwardRef, useId, type CSSProperties } from 'react';
import { INK_LAYERS, MARK_BOX_SCALE, MARK_PAD, blotVar, burnMask, strokePaths, surfaceBackground, type FusionEmblem } from './emblem';
import css from './emblem.module.css';

// 融合面 + 标志的画面层(ADR-019 订正 §2 / §3;刀 2c-2)。**通用**:只按传进来的登记数据画,不认识任何组合。
//
// 盖在宿主卡位那一个盒子上(位置、尺寸、卡边由 FusionMerge 在首帧绘制之前量好写上);
// 揭晓时显露的是**真实的成品卡**(它压在这一层上面、带一道边界遮罩),不是这里画的示意图。
// 运动全部由根节点上的 CSS 自定义属性驱动(笔画进度 --em-s*、焦褐团 --em-b*、点缀 --em-accent、
// 整枚退去 --em-ink),由 FusionMerge 的时间线推:只重绘、不布局。
// 焦褐层与点缀层分成两张 SVG:焦褐用 multiply 烙进下面的颜色,点缀保持本色(否则会被压暗)。

const VIEWBOX = `${-MARK_PAD} ${-MARK_PAD} ${100 + 2 * MARK_PAD} ${100 + 2 * MARK_PAD}`;

export const EmblemOverlay = forwardRef<HTMLDivElement, { emblem: FusionEmblem }>(function EmblemOverlay({ emblem }, ref) {
  const uid = useId().replace(/:/g, '');
  const { surface, mark } = emblem;
  const [hostEnd, seamFrom, seamTo, foreignFrom] = surface.seam;
  const mask = burnMask(mark.strokes);

  const rootVars = {
    '--em-angle': `${surface.angle}deg`,
    '--em-host-end': `${hostEnd}%`,
    '--em-seam-from': `${seamFrom}%`,
    '--em-seam-to': `${seamTo}%`,
    '--em-foreign-from': `${foreignFrom}%`,
    '--em-seam-tex': surface.seamTexture,
    '--em-side-tex': surface.sideTexture,
    '--em-mark-box': `${mark.size * MARK_BOX_SCALE}px`,
    '--em-mark-rot': `${mark.rotate}deg`,
    '--em-accent-alpha': mark.accent.alpha,
  } as CSSProperties;

  const burnStyle = {
    maskImage: mask.image,
    WebkitMaskImage: mask.image,
    maskPosition: mask.position,
    WebkitMaskPosition: mask.position,
    maskSize: mask.size,
    WebkitMaskSize: mask.size,
  } as CSSProperties;

  const { accent } = mark;
  return (
    <div ref={ref} className={css.overlay} style={rootVars} aria-hidden="true" data-fusion-emblem="">
      <div className={css.surface} data-emblem-surface="" style={{ background: surfaceBackground(surface) }}>
        <div className={`${css.mark} ${css.burn}`} data-emblem-mark="" style={burnStyle}>
          <svg viewBox={VIEWBOX} overflow="visible">
            <defs>
              <radialGradient id={`${uid}-scorch`}>
                <stop offset="0" stopColor={mark.scorch.color} stopOpacity={mark.scorch.alpha} />
                <stop offset="0.55" stopColor={mark.scorch.color} stopOpacity={mark.scorch.alpha * 0.4} />
                <stop offset="1" stopColor={mark.scorch.color} stopOpacity={0} />
              </radialGradient>
            </defs>
            {mark.blots.map((b, i) => (
              <ellipse
                key={i}
                cx={b.center[0]}
                cy={b.center[1]}
                rx={b.radius[0]}
                ry={b.radius[1]}
                fill={`url(#${uid}-scorch)`}
                style={{ opacity: `var(${blotVar(i)}, 1)` }}
              />
            ))}
            {mark.strokes.map((s, i) => (
              <g key={i}>
                {INK_LAYERS.flatMap((l) =>
                  strokePaths(s, i, l.scale, l.extra, l.core).map((d, k) => (
                    <path key={`${l.key}${k}`} d={d} fill={mark.ink.color} fillOpacity={l.core ? mark.ink.core : mark.ink.halo} />
                  )),
                )}
              </g>
            ))}
          </svg>
        </div>
        <div className={`${css.mark} ${css.accent}`}>
          <svg viewBox={VIEWBOX} overflow="visible">
            <defs>
              <linearGradient id={`${uid}-accent`} x1="0" x2="1">
                {accent.stops.map(([o, a]) => (
                  <stop key={o} offset={o} stopColor={accent.color} stopOpacity={a} />
                ))}
              </linearGradient>
            </defs>
            <path
              d={`M${accent.from[0]},${accent.from[1]} L${accent.to[0]},${accent.to[1]}`}
              stroke={`url(#${uid}-accent)`}
              strokeWidth={accent.width}
              strokeLinecap="round"
              fill="none"
            />
          </svg>
        </div>
      </div>
    </div>
  );
});
