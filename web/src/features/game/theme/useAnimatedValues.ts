import { useEffect, useState } from 'react';
import gsap from 'gsap';
import type { SkinBundle } from './contract';
import { reducedMotion } from './motion';

// 数值滚动的**单一真理源**(ADR-018 §4.2 通用层职责)。
//
// 它只做一件事:把「引擎落账的目标值」插值成「此刻屏幕上的值」——`displayValue`。
// 调用方(StatsPanel)拿它当**唯一输入**去查档、取 severity、算条宽、拼可访问文本,
// 于是数字滚过阈值的那一帧,档名与状态灯**同帧**翻转。
//
// 为什么必须在通用层(而不是各皮肤自己滚数字):
//   - 「开始滚就套用目标 severity」= 数字还没到就先报警;
//   - 「滚完再补状态」= 状态迟到;
//   - 「每个主题各做一套同步规则」= 四个世界四种口径,且都得再验一遍。
// 皮肤只提供**时间感**(`skin.valueRoll` 的时长与缓动),不碰同步逻辑。
//
// reduced-motion:不插值,目标值同帧直接生效(数字/档名/灯一起切)。

/**
 * @param target 引擎落账的目标值(key→value)
 * @param bundle 当前皮肤 + runtime;null(未登记世界)→ **原样返回目标值**,旧行为零变化
 * @returns 此刻应当显示的值(**已取整**:取整后的数才是玩家看到的数,查档必须用同一个数)
 */
export function useAnimatedValues(
  target: Record<string, number>,
  bundle: SkinBundle | null,
): Record<string, number> {
  const skin = bundle?.skin ?? null;
  const runtime = bundle?.runtime ?? null;
  const animate = !!skin && !!runtime && !reducedMotion();

  // 依赖用内容签名,避免每次渲染的新对象引用触发重跑。
  const valueKey = signature(target);
  const shapeKey = Object.keys(target).sort().join(',');

  // 一份状态装两样东西:`exact` 是插值中的浮点值(取整只在输出侧,避免累积误差),
  // `shown` 是此刻该显示的整数。放同一个 state 里,免得在渲染期写 ref。
  const [state, setState] = useState(() => ({
    valueKey,
    shapeKey,
    exact: { ...target },
    shown: rounded(target, target),
  }));

  // 渲染期对齐(React 官方「据 prop 变化调整 state」模式,不放进 effect):
  //   · 轴集换了(换局/续局)→ 直接落位,绝不从上一局的数字滚过来;
  //   · 不做动画(未登记皮肤 / reduced-motion)→ 直接落位。
  // 只有「同一组轴、值变了、且要动画」才交给下面的 effect 起插值。
  let current = state;
  if (state.valueKey !== valueKey || state.shapeKey !== shapeKey) {
    const snap = !animate || state.shapeKey !== shapeKey;
    current = snap
      ? { valueKey, shapeKey, exact: { ...target }, shown: rounded(target, target) }
      : { ...state, valueKey, shapeKey };
    setState(current);
  }

  const from = current.exact;

  useEffect(() => {
    if (!animate || !runtime || !skin) return;
    const same = Object.keys(target).every((k) => round(from[k]) === round(target[k]));
    if (same) return;

    const proxy: Record<string, number> = {};
    for (const k of Object.keys(target)) proxy[k] = from[k] ?? target[k];
    // 走统一 runtime:turn 切换 / 卸载由同一个 teardown 收走(§4.4),这里不另起清理。
    // setState 发生在 GSAP 的逐帧回调里(异步),不是 effect 同步体。
    runtime.add(() => {
      gsap.to(proxy, {
        ...target,
        duration: skin.valueRoll.durationMs / 1000,
        ease: skin.valueRoll.ease,
        onUpdate: () =>
          setState((s) => ({ ...s, exact: { ...proxy }, shown: rounded(proxy, target) })),
        onComplete: () =>
          setState((s) => ({ ...s, exact: { ...target }, shown: rounded(target, target) })),
      });
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [valueKey, shapeKey, animate, runtime, skin]);

  return current.shown;
}

const round = (n: number | undefined) => (typeof n === 'number' ? Math.round(n) : 0);

/** 只输出目标里存在的键(换局后旧轴不该残留在面板上)。 */
function rounded(src: Record<string, number>, shape: Record<string, number>): Record<string, number> {
  const out: Record<string, number> = {};
  for (const k of Object.keys(shape)) out[k] = round(src[k] ?? shape[k]);
  return out;
}

function signature(v: Record<string, number>): string {
  return Object.keys(v)
    .sort()
    .map((k) => `${k}:${v[k]}`)
    .join('|');
}
