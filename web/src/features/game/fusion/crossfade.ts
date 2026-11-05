// 融合入口揉合动作的时长与 FLIP 量取(FusionMerge 的数据侧;拆出来是为了组件文件只导出组件)。

/** 三段交叉淡化(ms,ADR-019 订正 §7)。 */
export const CROSSFADE = {
  out: [0, 160],
  fade: [120, 380],
  text: [320, 460],
} as const;

/**
 * 落位:成品卡往下移一个卡位(ms)。约 107px(卡高 93 + 卡间缝 13.6,480 宽实测)走 280ms,
 * 减速收尾(power2.out)—— 读得出「它落到了宿主下面」,又不拖住紧接着的点按。
 */
export const SETTLE_MS = 280;
/** reduced-motion:不移动,只把宿主 / 被拖卡淡回来(ms)。 */
export const SETTLE_REDUCED_MS = 160;

export function totalMs(reduced: boolean): number {
  return CROSSFADE.text[1] + (reduced ? SETTLE_REDUCED_MS : SETTLE_MS);
}

/** 卡列里每个子元素在插入成品卡**之前**的 offsetTop(FLIP 的 First)。 */
export type ListSnapshot = ReadonlyMap<Element, number>;

export function snapshotList(list: HTMLElement): ListSnapshot {
  const m = new Map<Element, number>();
  for (const el of Array.from(list.children)) m.set(el, (el as HTMLElement).offsetTop);
  return m;
}
