import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ArchetypeSummary } from '../../api';
import { ArchetypeCard } from './ArchetypeSelect';

// 选择屏卡片(ADR-008 决策 4):可玩卡渲染钩子/标签 + 点击触发 onChoose(→ startGame);
// 未开放卡灰显「敬请期待」、不可点。store 接线(loadArchetypes/startGame/lastArchetype)
// 已由 gameStore.test 覆盖,这里只钉纯展示 + 点击语义。

const APOCALYPSE: ArchetypeSummary = {
  archetype: 'apocalypse',
  displayName: '末日生存',
  tagline: '废土求生,饥饿是另一个敌人。',
  vibeTag: '荒凉 · 绝境',
  active: true,
};
const CTHULHU: ArchetypeSummary = {
  archetype: 'cthulhu',
  displayName: '克苏鲁',
  tagline: '凝视深渊,深渊回以低语。知道得越多,离疯狂越近。',
  vibeTag: '深渊 · 疯狂',
  active: true,
};
const LOCKED: ArchetypeSummary = {
  archetype: 'life_sim',
  displayName: '人生模拟',
  tagline: null,
  vibeTag: null,
  active: false,
};

describe('ArchetypeCard', () => {
  it('可玩卡:渲染名称/钩子/标签,点击触发 onChoose', () => {
    const onChoose = vi.fn();
    render(<ArchetypeCard summary={APOCALYPSE} onChoose={onChoose} />);
    expect(screen.getByText('末日生存')).toBeInTheDocument();
    expect(screen.getByText('废土求生,饥饿是另一个敌人。')).toBeInTheDocument();
    expect(screen.getByText('荒凉 · 绝境')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button'));
    expect(onChoose).toHaveBeenCalledTimes(1);
  });

  it('克苏鲁可玩卡:加世界自动进目录,渲染钩子/标签 + 点击触发 onChoose', () => {
    const onChoose = vi.fn();
    render(<ArchetypeCard summary={CTHULHU} onChoose={onChoose} />);
    expect(screen.getByText('克苏鲁')).toBeInTheDocument();
    expect(screen.getByText('深渊 · 疯狂')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button'));
    expect(onChoose).toHaveBeenCalledTimes(1);
  });

  it('未开放卡:灰显「敬请期待」,无可点按钮', () => {
    const onChoose = vi.fn();
    render(<ArchetypeCard summary={LOCKED} onChoose={onChoose} />);
    expect(screen.getByText('人生模拟')).toBeInTheDocument();
    expect(screen.getByText('敬请期待')).toBeInTheDocument();
    // 占位不是 button(不可点)。
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
