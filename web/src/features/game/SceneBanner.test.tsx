import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SceneBanner } from './SceneBanner';

// #8 顶部氛围带组件:状态栏渲染现成字段 + 据 archetype 选图 + 图缺失优雅降级不塌。

describe('SceneBanner', () => {
  it('渲染顶部状态栏:回合 / 危险度中文 / 标题 / tone', () => {
    render(
      <SceneBanner
        archetype="rules_creepy"
        turn={3}
        dangerLevel="high"
        title="雨夜便利店"
        tone="潮湿而不安"
      />,
    );
    expect(screen.getByText('第 3 回合 · 危险度 高')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '雨夜便利店' })).toBeInTheDocument();
    expect(screen.getByText('潮湿而不安')).toBeInTheDocument();
  });

  it('已配图世界:渲染底图层(background-image 指向对应 webp)', () => {
    const { container } = render(
      <SceneBanner
        archetype="cthulhu"
        turn={1}
        dangerLevel="extreme"
        title="旧日低语"
        tone="不可名状"
      />,
    );
    const img = container.querySelector('[aria-hidden="true"][style]') as HTMLElement | null;
    expect(img).not.toBeNull();
    expect(img!.style.backgroundImage).toContain('/scenes/cthulhu.webp');
    // 危险度也走中文映射。
    expect(screen.getByText(/极危/)).toBeInTheDocument();
  });

  it('未配图 / 未知 archetype:不渲染底图层,但状态栏照常渲染(降级不塌)', () => {
    const { container } = render(
      <SceneBanner
        archetype={undefined}
        turn={0}
        dangerLevel="low"
        title="无名之地"
        tone="平静"
      />,
    );
    // 无 background-image 的图层(唯一带 inline style 的元素)。
    const styled = container.querySelector('[style*="background-image"]');
    expect(styled).toBeNull();
    // 状态栏仍在,布局不塌。
    expect(screen.getByText('第 0 回合 · 危险度 低')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '无名之地' })).toBeInTheDocument();
  });
});
