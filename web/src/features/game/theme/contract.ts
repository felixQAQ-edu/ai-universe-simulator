import { createContext, useContext } from 'react';
import type { ComponentType, RefObject } from 'react';
import type { AxisSeverity } from '../../../api';
import type { AvailableAction } from '../../../types/schema';
import type { SkinRuntime } from './lifecycle';
import type { SkinId } from './registry';

// 通用层 ↔ 主题层的契约(ADR-018 §4.2)。
//
//   通用层:遍历 axes / 匹配 band / 取 severity / 输出可访问文本  → 产出 {@link AxisView}
//   主题层:只决定**形态**(OSD 分段条 / 灵脉光带 / …)与**视觉状态**
//
// **任何主题组件不得重新解释轴语义**——拿不到 axis 的 key 语义解释权:
// 不得读 key 判断这是什么轴,不得读 label 或数值高低判断危不危险。
// 危险与否只有一个来源:{@link AxisView.severity}(服务端派生,ADR-018 §1 语义产出方原则)。

/**
 * 一根数值轴喂给主题层的**全部**信息。刻意不含 `axisRole`/`lethal`/`bands` 原表——
 * 主题层不需要、也不该拿到能让它自行推断语义的原料。
 */
export interface AxisView {
  /** React key 用;**不得据它分派语义或配色**(那是刀 0 之前 barClass 的老路)。 */
  key: string;
  /** 玩家可见中文名(末日「体力/补给」、修仙「气血/灵力/境界」…)。 */
  displayName: string;
  /** 当前绝对值(已按缺省回落 0)。 */
  value: number;
  /** 进度条宽度百分比(已 clamp 到 0–100)。 */
  percent: number;
  /** 当前行为档 label;无档表 / 未命中 → null(只显数字)。 */
  bandLabel: string | null;
  /** 当前档风险等级;**四种缺省一律 null = 不进危险态**(绝不默认 danger)。 */
  severity: AxisSeverity | null;
  /** 可访问文本(读屏用):「体力 71 · 受创 · 注意」。 */
  a11yText: string;
  /**
   * 这根是不是本世界的**签名轴**({@link WorldSkin.signatureAxisKey} 登记的那根)。
   *
   * **由通用层打标、主题层只读这个 bool** —— 主题层想给签名轴换个形态(修仙:境界改印),
   * 唯一合法的问法是「你是不是签名轴」,**不是**「你的 key 是不是 realm」:后者等于把
   * 「哪根轴特殊」这条配置从注册表复制进主题组件,ADR-018 §5 Q5「锁在注册表内」当场失效,
   * 且第二个消费方一出现就会两处口径不一致(§4.2 主题层不得重新解释轴语义)。
   *
   * 未登记签名轴的世界:全部为 false —— 主题层据此**整齐降级**(修仙:全部走灵脉)。
   */
  signature: boolean;
}

/**
 * 风险等级 → 玩家可见中文短词。**这是「呈现已给定的语义」,不是「判断语义」**——
 * 映射表在通用层单点维护,免得四套皮肤各写一遍(写四遍就会出现四种口径)。
 * null(四种缺省情形)→ null:**不显示任何风险字样,不进危险态**(ADR-018 §2.5)。
 */
export function severityWord(severity: AxisSeverity | null): string | null {
  if (severity === 'danger') return '危险';
  if (severity === 'caution') return '注意';
  if (severity === 'neutral') return '正常';
  return null;
}

/** 氛围层组件收到的东西。 */
export interface AmbientProps {
  /** 唯一 teardown(§4.4):在途 timeline / 定时器一律登记到它。 */
  runtime: SkinRuntime;
  /**
   * 主题根元素。皮肤把**连续量**(亮度/色温/压暗强度这类)写成根上的 CSS 自定义属性,
   * 由各层 CSS 自行消费 —— 这样一次编排可以同时作用于顶部场景图、氛围层与面板,
   * 而**不需要氛围层去 query 别的组件的 DOM**(那会把组件边界捅穿)。
   * 离散状态仍走 {@link setRootClass}。
   */
  rootRef: RefObject<HTMLElement | null>;
  /**
   * 入场序列播完(含余韵)时调用一次。通用层据此**串行**放行正文逐字
   * (ADR-018 §4.7:先看见环境,再进入叙事)。皮肤若被抑制(reduced-motion 等)须**立即**调用,
   * 否则通用层的兜底计时器会替它放行 —— 但那是兜底,不是设计。
   */
  onIntroDone: () => void;
  /**
   * 停表(§4.4):`generating` **或**开场 reveal 打字期为 true ——
   * **正文是禁区**,文本不稳定期低频调度一律停。
   */
  paused: boolean;
  /** 是否正在生成回合文本。一次性记忆点据它拒绝触发(放行标准 4),与 reveal 打字区分开。 */
  generating: boolean;
  /** 当前回合(仅供主题层判断「是否换了回合」,不参与轴语义)。 */
  turn: number;
  /**
   * 给**主题根**挂一个瞬时状态 class(前一拍 / 余韵这类跨越氛围层与顶部画面的状态)。
   * 通用层只负责把字符串拼上去,**class 的含义完全由皮肤定义**;传 '' 清除。
   * 可以是多个 class 用空格连起来(皮肤自己决定怎么组合),通用层不解析。
   */
  setRootClass: (cls: string) => void;
  /**
   * **签名轴向上跨档的次数**(ADR-018 §5 Q5)。通用层按 {@link WorldSkin.signatureAxisKey}
   * 配置的那根轴算好后传入:每次「新档序号 > 旧档序号」加一,下跌 / 平档 / 首次装载**一律不加**。
   *
   * 皮肤据它触发一级记忆点(修仙 = 一声钟鸣):`useEffect` 依赖它,值变了且 > 0 才开演。
   * 未配置签名轴的皮肤恒收到 0 —— 契约里**刻意必填**:每套皮肤都得正面回答「我盯不盯轴」。
   */
  signatureTick: number;
}

/** 决策圈形态组件收到的东西(与通用 DecisionCircle 同签名)。 */
export interface ActionsProps {
  actions: AvailableAction[];
  disabled: boolean;
  onChoose: (id: string) => void;
}

/**
 * **间奏槽**收到的东西(ADR-018 §3 刀 3 段)。
 *
 * 间奏 = 渲染在**正文之后、决策圈之前**的世界专属区块(末日:那台手摇电台)。
 * 位置是语义的一部分:**读完情境 → 间奏响一下 → 做决定** —— 它是决策前的最后一个扰动,
 * 挪到正文之前改的不是位置而是叙事节奏,挪到页面顶部则退化成 UI 条。
 *
 * **可选槽**:皮肤不配 `Interlude` 就整段不渲染(未登记世界与刀 1/2 已登记世界 DOM 零影响)。
 * 刻意做成**通用槽**而非「电台槽」——刀 4 的 Prose 重写、C 批叙事壳层差异化都在这一带。
 */
export interface InterludeProps {
  /** 唯一 teardown(§4.4):在途 timeline / 定时器一律登记到它。 */
  runtime: SkinRuntime;
  /**
   * 停表(§4.4):`generating` **或**开场 reveal 打字期为 true ——
   * **正文是禁区**,文本不稳定期低频调度一律停。
   */
  paused: boolean;
  /** 是否正在生成回合文本(与 reveal 打字区分开)。 */
  generating: boolean;
  /** 当前回合(仅供主题层判断「是否换了回合」)。 */
  turn: number;
}

/**
 * **正文形态槽**收到的东西(ADR-018 刀 4)。
 *
 * 为什么正文也需要一个槽(而不是在通用 `Prose` 里按主题分支):克苏鲁的一级记忆点
 * 「文字异常」**必须作用在正文的字上**,而作用点需要**分片 DOM**(每一小段一个 span)
 * 才能被单点、瞬时地改写。两条硬约束把方案定死了 ——
 *
 * - 通用 `Prose` 里写 `if (skin === 'cthulhu')` = 把世界判定复制进通用层,
 *   ADR-018 §4.1「世界判定只发生一次」当场失效(刀 1 花整整一刀把散在五处的
 *   archetype 分派收编进注册表,那是往回走);
 * - 让氛围层去 `querySelector` 正文的 DOM = 捅穿组件边界(见 {@link AmbientProps.rootRef}
 *   头注释立的同一条),且两边挂载顺序一变就静默失效。
 *
 * 于是照刀 3 `Interlude` 的先例:**真实需求超出现有槽 → 契约小幅扩展一个可选槽 →
 * 其余一律不动 = 抽象正常演化**。不配该槽的世界渲染通用 `Prose`,
 * **DOM 逐字节不变是构造保证,不是「没写错就不会变」**(§4.5.1 对拍照旧成立)。
 */
export interface ProseProps {
  /** 当前该显示的正文(已含开场逐字进度;通用层算,形态层不自己算)。 */
  text: string;
  /** 是否显示打字光标。 */
  caret: boolean;
  /** 唯一 teardown(§4.4):在途 timeline / 定时器 / 监听一律登记到它。 */
  runtime: SkinRuntime;
  /**
   * 主题根。同 {@link AmbientProps.rootRef}:形态层要把连续量交给别的层消费时
   * (克苏鲁:指针漂移量写成根上的自定义属性,由数值形态的 CSS 自行消费),
   * 一律写主题根的自定义属性 —— **不是**去 query 数值面板的 DOM。
   */
  rootRef: RefObject<HTMLElement | null>;
  /**
   * 停表(§4.4):`generating` **或**开场 reveal 打字期为 true。
   * **正文是禁区**且此刻文本本身不稳定 —— React 重渲染会冲掉命令式改动,
   * 更糟的是可能把异常后的文本当成原文记下,故文本不稳定期一切调度必须停。
   */
  paused: boolean;
  /** 是否正在生成回合文本(与 reveal 打字区分开)。 */
  generating: boolean;
  /** 当前回合。形态层据它重建分片(旧节点引用一律作废)。 */
  turn: number;
  /**
   * 签名轴当前档的**风险等级**(服务端派生,ADR-018 §1)。
   *
   * 克苏鲁的异常频率随「越疯越频繁」而变 —— 但**主题层不许读 `san` 这个 key**
   * 去判断「这是理智轴」(§4.2:不得读 key 判断这是什么轴、不得按数值高低判断危不危险)。
   * 合法通道只有这一条:注册表登记「盯哪根轴」({@link WorldSkin.signatureAxisKey}),
   * 通用层把**那根轴的 severity** 交出来,形态层只按 neutral/caution/danger 三档调频率。
   *
   * 未配置签名轴 / 无档表 / 未知取值 → null(**按最低频处理**,同「不确定时不吓玩家」)。
   */
  signatureSeverity: AxisSeverity | null;
}

/** 数值形态组件收到的东西。 */
export interface StatsProps {
  axes: AxisView[];
  runtime: SkinRuntime;
  /**
   * 主题根。与 {@link AmbientProps.rootRef} 同一个元素、同一用途:数值形态若要把**位置或连续量**
   * 交给别的层消费(修仙:境界印把自己的中心写成 `--seal-x/--seal-y`,好让氛围层的金光从**印**
   * 而不是从屏幕中心扩散),一律写主题根上的自定义属性 —— **不是**让氛围层去 query 数值面板的 DOM
   * (那会把组件边界捅穿,且两边挂载顺序一变就静默失效)。
   */
  rootRef: RefObject<HTMLElement | null>;
}

/**
 * 数值滚动的时间感(由皮肤给,**同步逻辑不在皮肤**——见 `useAnimatedValues`)。
 *
 * `durationMs` / `startDelayMs` 允许给**函数**:通用层在**每次起 tween 时**求值。
 * 这条口子是为「时间本身不可信」的世界开的(克苏鲁:每次时长 ×random(0.82–1.22)、
 * 25% 概率额外迟到)——写成常数会让那条时间感在数值滚动上**静默失效**:
 * 常数在模块加载时算一次,一整个会话所有滚动同一时长,而**代码上完全看不出问题**
 * (它「用了自己的常量」)。这正是 ADR-018 §4.12「搬元素要连关系一起搬」的又一处:
 * 样板间靠的是「每次调用重新 random」这个**关系**,不是 `CTHU.dur` 这个**元素**。
 *
 * 给数字 = 每次都一样(规则怪谈 / 修仙 / 末日,行为与刀 1–3 逐字不变)。
 */
export interface ValueRoll {
  durationMs: number | (() => number);
  /** GSAP 缓动名(与该世界的 `--t-ease` 同源)。 */
  ease: string;
  /**
   * 起滚前的固定迟到(ms),**每次求值**。缺省 0。
   * 与 {@link ceremonyDelayMs} 的区别:后者只在签名轴跨档那一次生效(仪式),
   * 前者是该世界**每一次**滚动都带的时间质地(克苏鲁的「偶尔迟到」)。两者相加。
   */
  startDelayMs?: number | (() => number);
  /**
   * **仪式延迟**:签名轴向上跨档的那一回合,数值滚动推迟这么久才起步,好让一级记忆点先响
   * (修仙:钟先鸣,境界与数值随之而变 —— 反过来就成了「数字先跳,钟来配个音」)。缺省 0。
   *
   * 只在**跨档那一次**生效,普通回合照旧立即起滚;`prefers-reduced-motion` 下不插值故天然不延迟。
   * 它是**时间感参数**(皮肤给值),延迟本身由通用层统一施加 —— 数字/档名/状态灯仍由同一个
   * `displayValue` 驱动、同帧翻档(§4.2.1 不下放主题层)。
   */
  ceremonyDelayMs?: number;
}

/** 一套世界皮肤 = 一组形态组件 + 一组 class 令牌。 */
export interface WorldSkin {
  id: SkinId;
  /**
   * 是否有入场序列。true 时通用层**等** {@link AmbientProps.onIntroDone} 再开始正文逐字
   * (串行,ADR-018 §4.7);false 则不等。
   */
  hasIntro: boolean;
  /** 数值滚动时长与缓动(通用层拿它跑插值,主题层不自己做同步)。 */
  valueRoll: ValueRoll;
  /**
   * 一级记忆点所盯的**数值轴 key**(ADR-018 §5 Q5:修仙钟鸣挂 `realm`)。
   * **「盯哪根轴」是主题注册表的按 key 配置,锁在注册表内** —— 通用数值组件永远不认识具体轴,
   * 它只是「按这个 key 算个档序号交出去」;缺省 = 本世界没有轴驱动的记忆点。
   */
  signatureAxisKey?: string;
  /** 主题根 class:色板 token + `--t-dur`/`--t-ease`(成对)+ reduced-motion 覆盖。 */
  screenClass: string;
  /** 停表时挂在主题根上的 class(低频 CSS 动效在其下 `animation-play-state: paused`)。 */
  pausedClass: string;
  /** 顶部氛围图的附加 class(规则怪谈:监控呼吸 = 本屏唯一持续环境动效)。 */
  bannerImgClass: string;
  /**
   * 返回键的形态 class(线 C 导航层)。缺省 '' → 通用降级样式,**照常渲染**:
   * 导航不可缺席,皮肤没配形态只意味着它长得普通,不意味着玩家出不去。
   *
   * ── 为什么是 token 而不是第三个组件槽 ─────────────────────────────────
   * 刀 3(`Interlude`)与刀 4(`Prose`)开槽,是因为**DOM 结构本身必须不同**
   * (电台是正文与决策圈之间的独立区块;文字异常必须作用在分片 span 上)。
   * 返回键不是:四个世界的差异 100% 在 CSS(方角/切角/铆钉/纸条边缘),DOM 都是
   * 同一个 `<button onClick={reset}>`。为它开槽 = 四套皮肤各抄一遍同一个按钮,
   * **那是抽象腐坏的反方向** —— 拿刀 3/4 当先例乱开槽,正是这里要挡住的。
   */
  backClass: string;
  /**
   * 返回键按压过渡时长(ms),**每次按下求值**;缺省 = 不写 `--press-dur`,
   * 行为与不配它的世界逐字相同。
   *
   * 这是**时间感参数**不是世界开关(任何世界都能给个常数),形状与 {@link ValueRoll.durationMs}
   * 同源:皮肤给值、通用层施加。存在理由只有一个 —— 克苏鲁「节奏不可信」要求每次快慢略不同,
   * 而 CSS 做不到「每次不同」(同 `CthuActions` 写 `--press-dur` 的既有先例,三行 JS 不引库)。
   */
  backPressDurMs?: number | (() => number);
  Ambient: ComponentType<AmbientProps>;
  Stats: ComponentType<StatsProps>;
  Actions: ComponentType<ActionsProps>;
  /**
   * **可选**间奏槽(正文之后、决策圈之前)。不配 = 整段不渲染,DOM 上不留任何痕迹 ——
   * 刀 1/2 已登记世界与未登记世界都不受影响(§4.5.1 对拍仍成立)。见 {@link InterludeProps}。
   */
  Interlude?: ComponentType<InterludeProps>;
  /**
   * **可选**正文形态槽。不配 = 渲染通用 `Prose`,DOM 上不留任何痕迹
   * (刀 1/2/3 三世界与其测试零影响)。见 {@link ProseProps}。
   */
  Prose?: ComponentType<ProseProps>;
}

/** 皮肤 + 该局 runtime。两者同生同死,故同一个 context 一起发下去。 */
export interface SkinBundle {
  skin: WorldSkin;
  runtime: SkinRuntime;
}

/**
 * 当前局的皮肤。**缺省 null = 旧实现**——这既是未登记世界的降级路径,
 * 也让所有既有组件测试(不带 Provider 渲染)逐条零回归。
 *
 * 判定只在 `GameScreen.PlayingScreen` 发生一次(ADR-018 §4.1),子组件只消费。
 */
export const SkinContext = createContext<SkinBundle | null>(null);

/** 消费当前皮肤(null = 旧实现)。 */
export function useSkin(): SkinBundle | null {
  return useContext(SkinContext);
}
