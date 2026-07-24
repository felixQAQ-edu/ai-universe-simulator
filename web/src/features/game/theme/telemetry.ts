// 效果时序遥测(ADR-018 §5 Q7 debug 面板的数据源)。
//
// 为什么需要它:刀 1 冒烟出现过「一级记忆点到底有没有发生」无法判断的情况 ——
// 对签名动作,这已经低于验收线。凭感觉调强度是错的做法;先能读到
// **排期 / 触发 / 完成 / 被谁抑制**,才能二分。
//
// 纪律:只记时间戳与原因字符串,**不参与任何生产逻辑**、不写持久状态;
// 只有 `?debug=1` 会把它显示出来(记录本身恒开,成本是几个字段的赋值)。

export interface EffectTrace {
  /** 效果名(如 `rules.flicker`)。 */
  name: string;
  /** 排期时刻(performance.now(),ms)。 */
  scheduledAt?: number;
  /** 真正开始播的时刻。 */
  firedAt?: number;
  /** 播完(含余韵交棒)的时刻。 */
  completedAt?: number;
  /** 未播的原因(reduced-motion / generating / busy / 已播过 …)。 */
  suppressedReason?: string;
  /** 当前阶段(scheduled / sag / drop / recover / settle / done)。 */
  state?: string;
  /** 主题根上的瞬时 class(前一拍之类)。 */
  activeClass?: string;
}

const traces = new Map<string, EffectTrace>();
const listeners = new Set<() => void>();
/** 快照引用**只在有变更时**换新 —— `useSyncExternalStore` 要求 getSnapshot 稳定,否则无限重渲。 */
let snapshot: EffectTrace[] = [];

/** 记一笔(合并进已有记录)。传 `reset: true` 从头开始一轮新的。 */
export function trace(name: string, patch: Partial<EffectTrace>, reset = false): void {
  const prev = reset ? undefined : traces.get(name);
  traces.set(name, { ...(prev ?? { name }), ...patch, name });
  snapshot = [...traces.values()];
  listeners.forEach((l) => l());
}

/** 读全部记录(DebugPanel 用)。同一状态下返回同一引用。 */
export function readTraces(): EffectTrace[] {
  return snapshot;
}

/** 订阅变更(仅 debug 面板使用);返回退订函数。 */
export function subscribeTraces(fn: () => void): () => void {
  listeners.add(fn);
  return () => void listeners.delete(fn);
}

/** 仅供测试:清空。 */
export function resetTraces(): void {
  traces.clear();
  snapshot = [];
}
