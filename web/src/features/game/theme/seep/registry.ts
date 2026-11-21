import type { AttributeAxisMeta, AxisSeverity } from '../../../../api';
import { resolveBandIndex, resolveSeverity } from '../../bands';
import type { SkinId } from '../registry';

// 融合局内渗入层的登记表(ADR-043)。
//
// 融合局照旧用宿主皮肤,外来世界只「渗」进一两样东西。渗入项**按组合登记**(ADR-043 审定 1 修订 ADR-019 §2 局内侧):
// `registry.ts` 的 `FUSIONS` 某一项可选写 `seep: <id>`,这里给出该 id 的参数。三种原语是通用的 ——
//   stain(墨洇):按某根轴的当前档序号,在数值区 / 正文框的角上画水渍;
//   stamp(压印):挂在宿主签名轴旁,签名轴每次向上跨档时压一次(宿主须声明 `signatureAccessory`);
//   bleed(字洇):正文里偶尔一个字晕开成墨,频率按某根轴当前档的 severity(宿主不得有 Prose 槽、低频槽须为空)。
// 组合只登记**数据**(颜色、档、文案、时长、频率)—— 这些数就是 ADR-019 §4 第 5 条要求的「偏离必须记账」。
// 加第四种原语须另立 ADR,不在登记项里塞自定义组件。
//
// **没登记 = 构造上不存在**:`resolveWorldTheme` 只给登记了的组合带 `seep` 键,通用层的三处挂点都以它为前提。
// feature gate:删掉 `FUSIONS` 那一项的 `seep`,该组合立刻回到纯 host 呈现。
//
// 纯数据 + 纯函数,无平台 IO(守 ADR-003)。

export type SeepId = 'yanqi';

export type SeepCorner = 'tl' | 'tr' | 'bl' | 'br';
export type SeepSurface = 'stats' | 'prose';

/** 一块角落水渍在某一档的样子。 */
export interface StainCornerSpec {
  surface: SeepSurface;
  corner: SeepCorner;
  /** 尺寸(CSS px)。同一角在更高档变大时,实现用 `scale()` 从它第一次出现时的尺寸放大过去(不动画化宽高)。 */
  w: number;
  h: number;
}

export interface StainLevelSpec {
  fillOpacity: number;
  strokeOpacity: number;
  corners: readonly StainCornerSpec[];
}

export interface StainSpec {
  /** 盯哪根轴(按 key 配置,锁在登记表里;渗入组件只收档序号)。 */
  axisKey: string;
  color: string;
  /** 跨档(上或下)时不透明度、显隐、尺寸的过渡时长。首次装载不过渡。 */
  transitionMs: number;
  /** 避让:文字行框外扩多少再挖空(ADR-043 决策 6)。 */
  holePadPx: number;
  /** 按档序号(0 起)给;超出取最后一档。 */
  levels: readonly StainLevelSpec[];
}

export interface StampSpec {
  /** 只挂宿主签名轴:跟它的向上跨档(tick)与当前档序号(index)走,不另登记轴。 */
  on: 'hostSignature';
  /** 玩家可见文案。 */
  text: string;
  /** 跨档成立后多久开始落下(ms)。《验讫》= 钟鸣 +550ms 鸣、再 1100ms(审定 4)。 */
  afterCrossingMs: number;
  pressMs: number;
  fromScale: number;
  /** 印中心相对挂点(= 签名轴那方印的框)的位置。 */
  center: { x: string; y: string };
}

export interface BleedWindow {
  meanMs: number;
  minMs: number;
  maxMs: number;
}

export interface BleedSpec {
  /** 盯哪根轴的 severity(按 key 配置,锁在登记表里)。 */
  axisKey: string;
  color: string;
  inMs: number;
  holdMs: number;
  outMs: number;
  /** severity → 指数分布均值与截断。不给的档(含 neutral)= 不出现。 */
  schedule: Partial<Record<'caution' | 'danger', BleedWindow>>;
}

export interface SeepEntry {
  id: SeepId;
  /** 必须等于该组合解析出的宿主皮肤 id;不等即当作没登记(见 {@link resolveSeep})。 */
  hostSkin: SkinId;
  stain?: StainSpec;
  stamp?: StampSpec;
  bleed?: BleedSpec;
}

/** 《验讫》的墨绿(样张 `#184034`)。 */
const YANQI_INK = '#184034';

export const SEEPS: Partial<Record<SeepId, SeepEntry>> = {
  // 《验讫》(cultivation × cthulhu):数值全部取自 docs/fusion-seep-samples/ 两份样张源码(ADR-043 Felix 已定 2–4)。
  yanqi: {
    id: 'yanqi',
    hostSkin: 'cultivation',
    stain: {
      axisKey: 'knowledge', // 窥天:未觉 / 见格 / 知渔
      color: YANQI_INK,
      transitionMs: 2400,
      holePadPx: 2,
      levels: [
        {
          fillOpacity: 0.1,
          strokeOpacity: 0.22,
          corners: [{ surface: 'stats', corner: 'bl', w: 64, h: 56 }],
        },
        {
          fillOpacity: 0.15,
          strokeOpacity: 0.32,
          corners: [
            { surface: 'stats', corner: 'bl', w: 64, h: 56 },
            { surface: 'stats', corner: 'br', w: 52, h: 46 },
            { surface: 'prose', corner: 'tl', w: 56, h: 48 },
          ],
        },
        {
          fillOpacity: 0.21,
          strokeOpacity: 0.42,
          corners: [
            { surface: 'stats', corner: 'bl', w: 96, h: 84 },
            { surface: 'stats', corner: 'br', w: 76, h: 66 },
            { surface: 'prose', corner: 'tl', w: 72, h: 62 },
            { surface: 'prose', corner: 'br', w: 84, h: 70 },
          ],
        },
      ],
    },
    stamp: {
      on: 'hostSignature', // 宿主修仙的签名轴 = 境界
      text: '验讫',
      afterCrossingMs: 1650,
      pressMs: 260,
      fromScale: 1.35,
      center: { x: '97%', y: '69%' },
    },
    bleed: {
      axisKey: 'san', // 清明:澄澈 / 起雾 / 将失
      color: YANQI_INK,
      inMs: 400,
      holdMs: 600,
      outMs: 500,
      schedule: {
        caution: { meanMs: 75_000, minMs: 30_000, maxMs: 150_000 },
        danger: { meanMs: 40_000, minMs: 20_000, maxMs: 90_000 },
      },
    },
  },
};

/**
 * 宿主低频槽为空的皮肤(字洇只许登记在这些宿主上,ADR-043 决策 8 / ADR-019 §4 第 3 条)。
 * 修仙:见 `xian/xian.module.css` 头注释「低频 0 = 腾空」。**宿主将来加低频效果时,这张表必须一起改**,
 * 并另立 ADR 定字洇与宿主低频的共锁。
 */
export const LOW_FREQ_EMPTY_HOSTS: ReadonlySet<SkinId> = new Set<SkinId>(['cultivation']);

/**
 * 据世界判定结果取渗入项。登记的宿主与本局实际宿主皮肤对不上 → null(只降级,不崩;同 `SKINS` 是 Partial 的口径)。
 */
export function resolveSeep(seepId: SeepId | undefined, hostSkin: SkinId | null): SeepEntry | null {
  if (!seepId || !hostSkin) return null;
  const entry = SEEPS[seepId];
  if (!entry || entry.hostSkin !== hostSkin) return null;
  return entry;
}

/** 通用层算给渗入组件的信号。渗入组件只收这些,不读轴 key、不判档(ADR-018 §4.2)。 */
export interface SeepSignals {
  /** 墨洇所盯那根轴的当前档序号;没登记墨洇 / 找不到轴 / 无档表 → null(不画墨渍)。 */
  stainLevel: number | null;
  /** 字洇所盯那根轴的当前档 severity;没登记 / 缺省 → null(按不出现处理)。 */
  bleedSeverity: AxisSeverity | null;
}

const NO_SIGNALS: SeepSignals = { stainLevel: null, bleedSeverity: null };

/**
 * 按登记的 key 取轴、算档序号与 severity(与 `useSignalCrossing` 同一取法)。
 * 跟**引擎落账的目标值**走,不跟屏幕上插值中的显示值 —— 「世界渗进来多少」是状态事实。
 */
export function seepSignals(
  seep: SeepEntry | null,
  axes: readonly AttributeAxisMeta[],
  values: Record<string, number>,
): SeepSignals {
  if (!seep) return NO_SIGNALS;
  const pick = (key: string | undefined) => (key ? axes.find((a) => a.key === key) : undefined);
  const stainAxis = pick(seep.stain?.axisKey);
  const bleedAxis = pick(seep.bleed?.axisKey);
  return {
    stainLevel: stainAxis ? resolveBandIndex(Number(values[stainAxis.key] ?? 0), stainAxis.bands) : null,
    bleedSeverity: bleedAxis
      ? resolveSeverity(Number(values[bleedAxis.key] ?? 0), bleedAxis.bands)
      : null,
  };
}

/** 档序号 → 这一档的参数(超出取最后一档;null / 负数 → null)。 */
export function stainLevelSpec(stain: StainSpec, level: number | null): StainLevelSpec | null {
  if (level === null || level < 0 || stain.levels.length === 0) return null;
  return stain.levels[Math.min(level, stain.levels.length - 1)];
}

/** 这条登记要不要正文形态:登了字洇,或墨洇有落在正文上的角。 */
export function seepWantsProse(seep: SeepEntry | null): boolean {
  if (!seep) return false;
  return !!seep.bleed || !!seep.stain?.levels.some((l) => l.corners.some((c) => c.surface === 'prose'));
}
