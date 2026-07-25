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

/** 数值形态组件收到的东西。 */
export interface StatsProps {
  axes: AxisView[];
  runtime: SkinRuntime;
}

/** 数值滚动的时间感(由皮肤给,**同步逻辑不在皮肤**——见 `useAnimatedValues`)。 */
export interface ValueRoll {
  durationMs: number;
  /** GSAP 缓动名(与该世界的 `--t-ease` 同源)。 */
  ease: string;
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
  Ambient: ComponentType<AmbientProps>;
  Stats: ComponentType<StatsProps>;
  Actions: ComponentType<ActionsProps>;
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
