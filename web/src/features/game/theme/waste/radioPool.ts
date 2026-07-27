// 末日 · 电台内容池(Cowork B 批冻结成果,逐条移植)。
//
// ── 分层纪律(本刀立的架构线)────────────────────────────────────────────
// 电台拆四层,**以后扩内容不动调度**:
//   ① 本文件 = **内容定义 + 类别语义**(纯数据 + 纯函数,零 React、零计时器)
//   ② `radioDraw.ts` = 抽取约束(shuffle bag / 同层不连播 / 异常层冷却)
//   ③ `useRadio.ts`  = 调度与播出状态机(计时器、忙态、与远光互斥)
//   ④ `WasteRadio.tsx` = 呈现层(DOM 与样式)
// 加一条电台词 = 只动本文件的数组;调频节奏要改 = 只动 ③。
//
// ── 内容设计(B 批验收结论,不要改成「都很惨」)──────────────────────────
// 四层 11 条:**普通生活 4 / 疲惫求生 3 / 信号残缺 2 / 异常·过时 2**。
// 异常只占少数,**普通与疲惫撑起真实感** —— 全是诡异内容会变成鬼故事电台,
// 而末日的底色是「日子还在过,只是过不下去了」。长度刻意不齐。

/** 内容层:决定出现频率与相互约束(异常层最稀有、独立冷却)。 */
export type RadioLayer = 'life' | 'tired' | 'broken' | 'odd';

/** 信号强度档:0 弱 / 1 中 / 2 强。决定信号格显示与文字不透明度。 */
export type SignalLevel = 0 | 1 | 2;

export interface RadioMsg {
  layer: RadioLayer;
  sig: SignalLevel;
  text: string;
}

/** 内容池(B 批冻结)。**改内容只动这个数组**,调度与呈现一律不动。 */
export const RADIO_POOL: readonly RadioMsg[] = [
  { layer: 'life', sig: 2, text: '……本台重播:旧城百货周年庆,全场八折……八折……' },
  { layer: 'life', sig: 1, text: '……明天多云转晴,西北风三到四级,适宜晾晒……' },
  { layer: 'life', sig: 2, text: '……妈,我到了,一切都好,别回信,电池要省着用……' },
  { layer: 'life', sig: 1, text: '……面粉两袋、盐一包、火柴……火柴要防潮的……' },
  { layer: 'tired', sig: 1, text: '……第 41 天,水还够,就是想听见个人声……' },
  { layer: 'tired', sig: 2, text: '……谁有青霉素,南桥用汽油换,白天来,别晚上来……' },
  { layer: 'tired', sig: 1, text: '……走了,门没锁,炉子上留了半锅豆子……' },
  { layer: 'broken', sig: 0, text: '……如果听到……请……三次……' },
  { layer: 'broken', sig: 0, text: '……北面……不,别走北面……' },
  { layer: 'odd', sig: 1, text: '……七号避难所照常开放,物资充足,今天是三月八日……' },
  { layer: 'odd', sig: 0, text: '……别在夜里……别……' },
];

/** 信号格(按 {@link SignalLevel} 取)。 */
export const SIGNAL_BARS = ['▂▂▁▂', '▃▄▂▃', '▅▆▄▅'] as const;
/** 文字不透明度(信号越弱越淡 —— 弱信号是**读起来费劲**,不是字变小)。 */
export const SIGNAL_OPACITY = [0.6, 0.75, 0.9] as const;

/** 空闲态文案(没有信号时电台屏上显示的东西)。 */
export const RADIO_IDLE = '── 无信号 ──';
/** 余韵态文案(播完之后那十秒的底噪)。 */
export const RADIO_HISS = '‥ ‥ ‥';

// ── 信号退化(B 批验证轮·项四)────────────────────────────────────────
// 目标体感是「**上次好像没听全**」,不是「又播这条了」:首播尽量完整,
// 重播必变(中间切入 / 一小段被静电吞掉 / 尾部截断 / 省略号缩短之一)。

type Mutator = (s: string, rand: () => number) => string;

const MUTATORS: readonly Mutator[] = [
  // 省略号缩短:最轻的一档,首播也可能用
  (s) => s.replace('……', '…'),
  // 中间切入:开头那截没赶上
  (s, r) => '……' + s.slice(Math.floor(s.length * (0.25 + r() * 0.2))),
  // 静电吞字:中间一小段被吞掉
  (s, r) => {
    const i = Math.floor(s.length * (0.35 + r() * 0.3));
    return s.slice(0, i) + '……' + s.slice(i + 2 + Math.floor(r() * 2));
  },
  // 尾部截断:后半句没了
  (s, r) => s.slice(0, Math.floor(s.length * (0.6 + r() * 0.25))) + '……',
];

/**
 * 按「是否重播」与信号强度做退化。纯函数:随机源由调用方注入,便于测试钉死。
 *
 * @param again 这条是否播过(重播**必变**)
 * @param rand  随机源(缺省 `Math.random`;测试传定值)
 */
export function degrade(msg: RadioMsg, again: boolean, rand: () => number = Math.random): string {
  let t = msg.text;
  if (again) t = MUTATORS[Math.floor(rand() * MUTATORS.length)](t, rand);
  else if (rand() < 0.35) t = MUTATORS[0](t, rand);
  // 弱信号本身也吃字(与重播退化叠加:最惨的一条会既被切头又被截尾)
  if (msg.sig === 0 && rand() < 0.5) t = MUTATORS[3](t, rand);
  return t;
}

/** 播出停留时长(ms):按文字长度给,短句不至于一闪而过、长句不至于读不完。 */
export function holdMs(text: string): number {
  return Math.min(3800, Math.max(2200, 2000 + text.length * 45));
}
