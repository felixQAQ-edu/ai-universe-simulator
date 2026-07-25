import { useState } from 'react';
import type { AttributeAxisMeta } from '../../../api';
import { resolveBandIndex } from '../bands';
import type { WorldSkin } from './contract';

// 签名轴跨档信号(ADR-018 §5 Q5)——**一级记忆点的触发判定,收在通用层单点**。
//
// 职责边界(同 §4.2):
//   通用层(本文件):按皮肤登记的 key 取那根轴的当前档序号、跟上一次比、**只在向上跨档时**加一;
//   主题层:收到「加一了」才决定演什么(钟鸣 / 别的)。
// 通用层因此仍然对轴语义无知 —— 它不知道 `realm` 是境界,只知道注册表说「盯这个 key」。
//
// 五条触发规矩逐条落在下面的实现里:
//   ① 向上跨档才算(newIndex > oldIndex);下跌不鸣;
//   ② init/resume **首次装载不触发**(第一次观察只记下前值);
//   ③ 一次跨多档**只加一次**(序号差多少都只 +1);
//   ④ 无 bands / 未配置签名轴 → 恒 0(静默降级,同 scene.ts 未配图即 null 的先例);
//   ⑤ 轴集换了(换局/续局)→ 重新记前值、不鸣。
//
// **为什么在渲染期派生而不是 useEffect**:数值滚动的仪式延迟要在**同一次提交**里生效
// (effect 晚一拍 = 数字已经开滚,钟再响就成了配音)。用 React 官方「据 props 变化调整 state」
// 模式(同 `useAnimatedValues`):渲染期 setState 会立刻重渲、可幂等重放,StrictMode 安全 ——
// 反过来「渲染期直接改 ref」在 StrictMode 双调用下会把跨档吃掉,正是 ADR-018 §4.9 那一类坑。

/** 签名轴的当前观察结果。`tick` 是给皮肤与数值层用的;其余三项供 `?debug=1` 面板读数二分。 */
export interface SignatureSignal {
  /** 向上跨档的累计次数;0 = 本局至今没跨过(皮肤据它触发记忆点)。 */
  tick: number;
  /** 皮肤登记盯的轴 key;未配置 → null。 */
  axisKey: string | null;
  /** 当前档序号(升序,0 = 最低档);无档表 / 未配置 → null。 */
  index: number | null;
  /** 上一次观察到的档序号 —— 「跨没跨」的另一半读数(debug 面板要能同时看到两个)。 */
  prevIndex: number | null;
}

/**
 * @param skin 当前皮肤(null = 未登记世界 → 恒 0)
 * @param axes init 下发的轴元数据(含 bands)
 * @param values 引擎落账的目标值(**不是**屏幕上插值中的显示值:仪式跟着真实状态走)
 */
export function useSignalCrossing(
  skin: WorldSkin | null,
  axes: AttributeAxisMeta[],
  values: Record<string, number>,
): SignatureSignal {
  const key = skin?.signatureAxisKey;
  const axis = key ? axes.find((a) => a.key === key) : undefined;
  const index = axis ? resolveBandIndex(Number(values[axis.key] ?? 0), axis.bands) : null;
  // 轴集签名:换局 / 续局换了世界 → 前值作废,重新开始观察(不拿上一局的档序号比)。
  const scope = `${key ?? ''}|${axes.map((a) => a.key).join(',')}`;

  const [seen, setSeen] = useState({ scope, index, prevIndex: null as number | null, tick: 0 });

  let current = seen;
  if (seen.scope !== scope) {
    // ⑤ 换局/续局:记下前值即可,不鸣(前值置 null → 下一次变化也不会被当成跨档)。
    current = { scope, index, prevIndex: null, tick: seen.tick };
    setSeen(current);
  } else if (index !== seen.index) {
    // ① 向上才算;② 前值为 null(首次装载 / 无档表)一律不算;③ 跨几档都只 +1。
    const up = index !== null && seen.index !== null && index > seen.index;
    current = { scope, index, prevIndex: seen.index, tick: up ? seen.tick + 1 : seen.tick };
    setSeen(current);
  }

  return { tick: current.tick, axisKey: key ?? null, index: current.index, prevIndex: current.prevIndex };
}
