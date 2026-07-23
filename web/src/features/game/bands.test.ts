import { describe, expect, it } from 'vitest';
import type { AxisBand } from '../../api';
import { resolveBand, resolveBandLabel, resolveSeverity } from './bands';

// ADR-018 刀 0 · 前端侧只做「按区间匹配当前档 + 读服务端给的 severity」,不判危险。
// 重点是四种缺省一律安全降级(不附加危险态),绝不默认 danger、绝不回退旧启发式。

/** 致命 depletion(体力/气血):最低档 danger、次低 caution、顶档 neutral。 */
const HP_BANDS: AxisBand[] = [
  { min: 0, max: 20, label: '濒危', severity: 'danger' },
  { min: 21, max: 50, label: '受创', severity: 'caution' },
  { min: 51, max: 100, label: '充沛', severity: 'neutral' },
];
/** 双刃 accumulation(禁忌知识):最高档 danger。 */
const KNOWLEDGE_BANDS: AxisBand[] = [
  { min: 0, max: 30, label: '蒙昧', severity: 'neutral' },
  { min: 31, max: 60, label: '初窥', severity: 'caution' },
  { min: 61, max: 100, label: '深陷', severity: 'danger' },
];
/** 纯成长 accumulation(境界):高位也只是 neutral。 */
const REALM_BANDS: AxisBand[] = [
  { min: 0, max: 30, label: '初境', severity: 'neutral' },
  { min: 31, max: 60, label: '小成', severity: 'neutral' },
  { min: 61, max: 100, label: '高深', severity: 'neutral' },
];

describe('resolveBand / resolveBandLabel', () => {
  it('按 inclusive 区间匹配当前档(边界归属)', () => {
    expect(resolveBandLabel(0, HP_BANDS)).toBe('濒危');
    expect(resolveBandLabel(20, HP_BANDS)).toBe('濒危');
    expect(resolveBandLabel(21, HP_BANDS)).toBe('受创');
    expect(resolveBandLabel(100, HP_BANDS)).toBe('充沛');
  });

  it('越界值 clamp 到 [0,100] 再匹配', () => {
    expect(resolveBandLabel(-5, HP_BANDS)).toBe('濒危');
    expect(resolveBandLabel(999, HP_BANDS)).toBe('充沛');
  });

  it('无档表 → null(只显数字)', () => {
    expect(resolveBand(50, undefined)).toBeNull();
    expect(resolveBandLabel(50, [])).toBeNull();
  });
});

describe('resolveSeverity · 正常映射', () => {
  it('致命 depletion:低位 danger、中位 caution、高位 neutral', () => {
    expect(resolveSeverity(8, HP_BANDS)).toBe('danger');
    expect(resolveSeverity(35, HP_BANDS)).toBe('caution');
    expect(resolveSeverity(90, HP_BANDS)).toBe('neutral');
  });

  it('双刃 accumulation:高位 danger(方向与 depletion 相反)', () => {
    expect(resolveSeverity(80, KNOWLEDGE_BANDS)).toBe('danger');
    expect(resolveSeverity(45, KNOWLEDGE_BANDS)).toBe('caution');
    expect(resolveSeverity(5, KNOWLEDGE_BANDS)).toBe('neutral');
  });

  it('纯成长 accumulation:高位仍 neutral —— 前端不因「值高」自行推断危险', () => {
    expect(resolveSeverity(95, REALM_BANDS)).toBe('neutral');
    expect(resolveSeverity(5, REALM_BANDS)).toBe('neutral');
  });
});

describe('resolveSeverity · 四种缺省一律安全降级(null = 不进危险态)', () => {
  it('① 无 bands', () => {
    expect(resolveSeverity(3, undefined)).toBeNull();
    expect(resolveSeverity(3, [])).toBeNull();
  });

  it('② 值找不到所属 band(区间有洞的畸形数据)', () => {
    const holed: AxisBand[] = [
      { min: 0, max: 20, label: '濒危', severity: 'danger' },
      { min: 60, max: 100, label: '充沛', severity: 'neutral' },
    ];
    expect(resolveSeverity(40, holed)).toBeNull();
    // 数字与 label 侧同样静默降级,不塌不报错。
    expect(resolveBandLabel(40, holed)).toBeNull();
  });

  it('③ 老数据没有 severity 字段', () => {
    const legacy: AxisBand[] = [
      { min: 0, max: 20, label: '濒危' },
      { min: 21, max: 100, label: '尚可' },
    ];
    expect(resolveSeverity(5, legacy)).toBeNull();
    // label 仍照常显示 —— 降级只关掉危险态,不关掉信息。
    expect(resolveBandLabel(5, legacy)).toBe('濒危');
  });

  it('④ 未知 severity 取值(未来新增等级 / 脏数据)', () => {
    const future = [
      { min: 0, max: 20, label: '濒危', severity: 'catastrophic' },
      { min: 21, max: 100, label: '充沛', severity: 'neutral' },
    ] as unknown as AxisBand[];
    expect(resolveSeverity(5, future)).toBeNull();
    expect(resolveBandLabel(5, future)).toBe('濒危');
  });

  it('绝不默认 danger:任何降级路径都不返回 danger', () => {
    const fallbacks = [
      resolveSeverity(50, undefined),
      resolveSeverity(50, []),
      resolveSeverity(50, [{ min: 0, max: 10, label: '窄档' }]),
    ];
    expect(fallbacks.every((s) => s === null)).toBe(true);
  });
});
