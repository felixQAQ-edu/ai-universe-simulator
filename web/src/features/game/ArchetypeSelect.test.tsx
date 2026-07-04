import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ArchetypeSummary } from '../../api';
import { ArchetypeCard, FusionCard } from './ArchetypeSelect';
import { nextFusionStage } from './fusion';

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

// ── 融合入口 = 渗漏卡 + 误入手势(ADR-013 决策 4)──

const CULTIVATION: ArchetypeSummary = {
  archetype: 'cultivation',
  displayName: '修仙',
  tagline: '逆天改命,踏上仙途。',
  vibeTag: '缥缈 · 仙途',
  active: true,
};

describe('误入手势(长按)', () => {
  it('长按(≥600ms)触发 onLongPress,且随后的 click 不触发 onChoose(不误入局)', () => {
    vi.useFakeTimers();
    const onChoose = vi.fn();
    const onLongPress = vi.fn();
    render(<ArchetypeCard summary={CULTIVATION} onChoose={onChoose} onLongPress={onLongPress} />);
    const card = screen.getByRole('button');

    fireEvent.pointerDown(card);
    vi.advanceTimersByTime(650);
    fireEvent.pointerUp(card);
    fireEvent.click(card); // 长按释放后浏览器仍会派发 click —— 必须被吞掉

    expect(onLongPress).toHaveBeenCalledTimes(1);
    expect(onChoose).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it('短按(<600ms)不触发 onLongPress,click 照常进入世界(单击零回归)', () => {
    vi.useFakeTimers();
    const onChoose = vi.fn();
    const onLongPress = vi.fn();
    render(<ArchetypeCard summary={CULTIVATION} onChoose={onChoose} onLongPress={onLongPress} />);
    const card = screen.getByRole('button');

    fireEvent.pointerDown(card);
    vi.advanceTimersByTime(200);
    fireEvent.pointerUp(card);
    fireEvent.click(card);

    expect(onLongPress).not.toHaveBeenCalled();
    expect(onChoose).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });

  it('状态机:依次长按 修仙 → 规则怪谈 才渗出;顺序不对不推进;渗出后保持', () => {
    // 正序:idle --修仙--> armed --规则怪谈--> revealed。
    expect(nextFusionStage('idle', 'cultivation')).toBe('armed');
    expect(nextFusionStage('armed', 'rules_creepy')).toBe('revealed');
    // 顺序不对:先规则怪谈不推进;armed 后按其它卡不推进(修仙重按保持 armed)。
    expect(nextFusionStage('idle', 'rules_creepy')).toBe('idle');
    expect(nextFusionStage('armed', 'cthulhu')).toBe('armed');
    expect(nextFusionStage('armed', 'cultivation')).toBe('armed');
    // 渗出后保持(不因再长按回退)。
    expect(nextFusionStage('revealed', 'cultivation')).toBe('revealed');
    expect(nextFusionStage('revealed', 'apocalypse')).toBe('revealed');
  });
});

describe('FusionCard(渗漏卡)', () => {
  it('渲染三层撕裂标题(修仙/规则怪谈/识海遗蜕)+ 渗漏标签,点击触发 onChoose(→ 双值 init)', () => {
    const onChoose = vi.fn();
    render(<FusionCard onChoose={onChoose} />);
    // 三层标题都在(CSS 轮换浮现;可及名是「识海遗蜕(融合世界)」)。
    expect(screen.getByText('修仙')).toBeInTheDocument();
    expect(screen.getByText('规则怪谈')).toBeInTheDocument();
    expect(screen.getByText('识海遗蜕')).toBeInTheDocument();
    expect(screen.getByText('渗漏 · 勿入')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '识海遗蜕(融合世界)' }));
    expect(onChoose).toHaveBeenCalledTimes(1);
  });
});
