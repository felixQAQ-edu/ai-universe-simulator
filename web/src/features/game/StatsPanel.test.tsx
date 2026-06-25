import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { AttributeAxisMeta } from '../../api';
import { StatsPanel } from './StatsPanel';

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

  it('缺失值回落 0,不崩', () => {
    render(<StatsPanel axes={APOCALYPSE} values={{ hp: 50 }} />);
    expect(screen.getByText('饥饿')).toBeInTheDocument();
    expect(screen.getByText('0')).toBeInTheDocument();
  });
});
