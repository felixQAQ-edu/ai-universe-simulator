import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { AttributeAxisMeta, AxisBand } from '../../api';
import { resolveBandLabel } from './bands';
import { StatsPanel } from './StatsPanel';

// #3 行为档区间(后端 init 下发 [{min,max,label}],连续覆盖 [0,100],axisRole 无关)。
const HP_BANDS: AxisBand[] = [
  { min: 0, max: 20, label: '濒危' },
  { min: 21, max: 50, label: '受创' },
  { min: 51, max: 100, label: '充沛' },
];
const KNOWLEDGE_BANDS: AxisBand[] = [
  { min: 0, max: 30, label: '蒙昧' },
  { min: 31, max: 60, label: '初窥' },
  { min: 61, max: 100, label: '深陷' },
];

// 数值面板动态渲染(ADR-008 决策 1 前端消费方 / 设计稿 §6):按 init 下发的数值轴元数据 + 中文名渲染,
// 不写死 hp/san —— 喂末日 → 体力/饥饿,喂规则怪谈 → 体力/理智(确认动态化没写死)。

const CREEPY: AttributeAxisMeta[] = [
  { key: 'hp', displayName: '体力' },
  { key: 'san', displayName: '理智' },
];
const APOCALYPSE: AttributeAxisMeta[] = [
  { key: 'hp', displayName: '体力' },
  { key: 'hunger', displayName: '饥饿' },
];
const CTHULHU: AttributeAxisMeta[] = [
  { key: 'hp', displayName: '体力' },
  { key: 'san', displayName: '理智' },
  { key: 'knowledge', displayName: '禁忌知识' },
];
const CULTIVATION: AttributeAxisMeta[] = [
  { key: 'hp', displayName: '气血' },
  { key: 'mana', displayName: '灵力' },
  { key: 'realm', displayName: '境界' },
];

describe('StatsPanel', () => {
  it('规则怪谈:渲染 体力 / 理智 + 对应值', () => {
    render(<StatsPanel axes={CREEPY} values={{ hp: 80, san: 65 }} />);
    expect(screen.getByText('体力')).toBeInTheDocument();
    expect(screen.getByText('理智')).toBeInTheDocument();
    expect(screen.getByText('80')).toBeInTheDocument();
    expect(screen.getByText('65')).toBeInTheDocument();
    // 没写死成 san —— 末日的「饥饿」此处不应出现。
    expect(screen.queryByText('饥饿')).not.toBeInTheDocument();
  });

  it('末日生存:渲染 体力 / 饥饿(动态化,非写死 hp/san)', () => {
    render(<StatsPanel axes={APOCALYPSE} values={{ hp: 70, hunger: 40 }} />);
    expect(screen.getByText('体力')).toBeInTheDocument();
    expect(screen.getByText('饥饿')).toBeInTheDocument();
    expect(screen.getByText('70')).toBeInTheDocument();
    expect(screen.getByText('40')).toBeInTheDocument();
    // 末日没有「理智」轴。
    expect(screen.queryByText('理智')).not.toBeInTheDocument();
  });

  it('克苏鲁:渲染 体力 / 理智 / 禁忌知识 三轴(动态化通吃三轴,knowledge 不写死)', () => {
    render(<StatsPanel axes={CTHULHU} values={{ hp: 90, san: 70, knowledge: 25 }} />);
    expect(screen.getByText('体力')).toBeInTheDocument();
    expect(screen.getByText('理智')).toBeInTheDocument();
    expect(screen.getByText('禁忌知识')).toBeInTheDocument();
    expect(screen.getByText('90')).toBeInTheDocument();
    expect(screen.getByText('70')).toBeInTheDocument();
    expect(screen.getByText('25')).toBeInTheDocument();
    // 克苏鲁没有末日的「饥饿」轴。
    expect(screen.queryByText('饥饿')).not.toBeInTheDocument();
  });

  it('修仙:渲染 气血 / 灵力 / 境界 三轴(全新数值体系,动态化通吃)', () => {
    render(<StatsPanel axes={CULTIVATION} values={{ hp: 88, mana: 60, realm: 15 }} />);
    expect(screen.getByText('气血')).toBeInTheDocument();
    expect(screen.getByText('灵力')).toBeInTheDocument();
    expect(screen.getByText('境界')).toBeInTheDocument();
    expect(screen.getByText('88')).toBeInTheDocument();
    expect(screen.getByText('60')).toBeInTheDocument();
    expect(screen.getByText('15')).toBeInTheDocument();
    // 修仙没有「理智」「饥饿」轴。
    expect(screen.queryByText('理智')).not.toBeInTheDocument();
    expect(screen.queryByText('饥饿')).not.toBeInTheDocument();
  });

  it('缺失值回落 0,不崩', () => {
    render(<StatsPanel axes={APOCALYPSE} values={{ hp: 50 }} />);
    expect(screen.getByText('饥饿')).toBeInTheDocument();
    expect(screen.getByText('0')).toBeInTheDocument();
  });

  // ── #3 数值行为化:有 bands → 数字旁显示当前档 label;无 bands → 优雅降级只显数字 ──
  it('有行为档:数字旁显示当前档 label(气血 28 · 受创)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '气血', bands: HP_BANDS }];
    render(<StatsPanel axes={axes} values={{ hp: 28 }} />);
    expect(screen.getByText('28')).toBeInTheDocument();
    expect(screen.getByText('受创')).toBeInTheDocument();
  });

  it('行为档随值切换(濒危 / 充沛)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '气血', bands: HP_BANDS }];
    const { rerender } = render(<StatsPanel axes={axes} values={{ hp: 10 }} />);
    expect(screen.getByText('濒危')).toBeInTheDocument();
    rerender(<StatsPanel axes={axes} values={{ hp: 90 }} />);
    expect(screen.getByText('充沛')).toBeInTheDocument();
    expect(screen.queryByText('濒危')).not.toBeInTheDocument();
  });

  it('无 bands 字段:优雅降级,只显数字、无档 label', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力' }]; // 无 bands
    render(<StatsPanel axes={axes} values={{ hp: 28 }} />);
    expect(screen.getByText('28')).toBeInTheDocument();
    // 不应冒出任何档名。
    expect(screen.queryByText('受创')).not.toBeInTheDocument();
  });
});

describe('resolveBandLabel(纯函数,边界 inclusive)', () => {
  it('depletion 区间边界归属', () => {
    expect(resolveBandLabel(20, HP_BANDS)).toBe('濒危');
    expect(resolveBandLabel(21, HP_BANDS)).toBe('受创');
    expect(resolveBandLabel(50, HP_BANDS)).toBe('受创');
    expect(resolveBandLabel(51, HP_BANDS)).toBe('充沛');
  });

  it('accumulation 区间边界归属(同一 min≤v≤max 逻辑,axisRole 无关)', () => {
    expect(resolveBandLabel(30, KNOWLEDGE_BANDS)).toBe('蒙昧');
    expect(resolveBandLabel(31, KNOWLEDGE_BANDS)).toBe('初窥');
    expect(resolveBandLabel(61, KNOWLEDGE_BANDS)).toBe('深陷');
  });

  it('clamp 超界 + 无档→null', () => {
    expect(resolveBandLabel(-5, HP_BANDS)).toBe('濒危');
    expect(resolveBandLabel(999, HP_BANDS)).toBe('充沛');
    expect(resolveBandLabel(50, undefined)).toBeNull();
    expect(resolveBandLabel(50, [])).toBeNull();
  });
});
