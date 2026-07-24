import { createContext, useContext } from 'react';
import type { ComponentType } from 'react';
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
   */
  setRootClass: (cls: string) => void;
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

/** 一套世界皮肤 = 一组形态组件 + 一组 class 令牌。 */
export interface WorldSkin {
  id: SkinId;
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
