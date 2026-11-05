import { pairOf } from './drag';

// 融合卡落位(ADR-019 订正 §10 待定项,Felix 2026-11-05 裁定选 A):
// **成品卡插在宿主卡的紧下方**。同一宿主再揉出第二张:新的那张紧贴宿主,旧的依次往下。
// 顺序只在本次会话内有效 —— `fused` 本来就是组件 state、零持久化(离开选择屏即遗忘)。

/** 卡列里的一格:单体世界卡,或一张融合卡。 */
export type CardSlot = { kind: 'world'; id: string } | { kind: 'fusion'; key: string };

/**
 * 揉出 `key` 之后的融合卡序列(**新的在前**)。
 * 已有同一组合 → 挪到最前、不重复(同一组合再揉一次,它回到紧贴宿主的位置)。
 */
export function placeFused(prev: readonly string[], key: string): string[] {
  return [key, ...prev.filter((k) => k !== key)];
}

/**
 * 已开放卡与融合卡交织成的卡列:每张已开放卡后面紧跟以它为宿主的融合卡(新的在上)。
 * 宿主不在已开放卡里的融合卡(理论上不会有)排在已开放卡之后,不丢。
 */
export function cardOrder(active: readonly string[], fused: readonly string[]): CardSlot[] {
  const hostOf = (key: string) => pairOf(key)[0];
  const out: CardSlot[] = [];
  for (const id of active) {
    out.push({ kind: 'world', id });
    for (const key of fused) if (hostOf(key) === id) out.push({ kind: 'fusion', key });
  }
  for (const key of fused) if (!active.includes(hostOf(key))) out.push({ kind: 'fusion', key });
  return out;
}
