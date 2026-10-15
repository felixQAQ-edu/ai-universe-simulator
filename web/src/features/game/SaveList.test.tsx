// ADR-034 已决 9:选择屏「我的存档」区块。
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useGameStore } from '../../state/gameStore';
import { createSavePointers, type SaveSummary } from '../../state/savePointers';
import { ArchetypeSelect } from './ArchetypeSelect';

const sum = (i: number, extra: Partial<SaveSummary> = {}): SaveSummary => ({
  saveId: `s${i}`,
  title: `世界${i}`,
  archetypes: ['rules_creepy'],
  turn: i,
  ended: false,
  lastPlayedAt: i,
  availability: 'available',
  ...extra,
});

beforeEach(() => {
  localStorage.clear();
  // 目录请求走不到真网络:直接让 loadArchetypes 视为已加载。
  useGameStore.setState({
    archetypes: [
      { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: null, vibeTag: null, active: true },
    ],
    fusions: [],
    capabilities: { history: false },
    saves: [],
    saveNotice: null,
  });
});
afterEach(() => {
  useGameStore.setState({ saves: [], saveNotice: null });
  vi.restoreAllMocks();
});

describe('「我的存档」区块', () => {
  it('没有条目 → 整个区块不渲染,<main> 子元素与改动前一样只有 header + 卡片列表', () => {
    const { container } = render(<ArchetypeSelect />);
    expect(screen.queryByRole('region', { name: '我的存档' })).not.toBeInTheDocument();
    const main = container.querySelector('main')!;
    expect(Array.from(main.children).map((c) => c.tagName)).toEqual(['HEADER', 'DIV']);
  });

  it('超过 8 条 → 只展示最近 8 条 +「查看全部」;展开后全部可见,存储里一条没少', () => {
    const p = createSavePointers();
    for (let i = 1; i <= 12; i++) p.write(sum(i));
    useGameStore.getState().refreshSaves();
    render(<ArchetypeSelect />);

    const region = screen.getByRole('region', { name: '我的存档' });
    expect(within(region).getAllByRole('listitem')).toHaveLength(8);
    expect(within(region).getByText('世界12')).toBeInTheDocument(); // 最近的在前
    expect(within(region).queryByText('世界4')).not.toBeInTheDocument();
    fireEvent.click(within(region).getByRole('button', { name: '查看全部(12)' }));
    expect(within(region).getAllByRole('listitem')).toHaveLength(12);
    expect(p.list().entries).toHaveLength(12);
  });

  it('unavailable 条目显示「暂时无法找回」且仍可点击重试;副标题写「本设备上次游玩」', () => {
    useGameStore.setState({ saves: [sum(1, { availability: 'unavailable' })] });
    const resume = vi.fn();
    useGameStore.setState({ resumeGame: resume });
    render(<ArchetypeSelect />);
    const btn = screen.getByRole('button', { name: /世界1/ });
    expect(btn).toHaveTextContent('暂时无法找回');
    expect(btn).toHaveTextContent('本设备上次游玩');
    expect(btn).toHaveTextContent('规则怪谈');
    fireEvent.click(btn);
    expect(resume).toHaveBeenCalledWith('s1');
  });

  it('capabilities.history 为 false → 有存档也没有「回看上局」入口', () => {
    useGameStore.setState({ saves: [sum(1)], capabilities: { history: false } });
    render(<ArchetypeSelect />);
    expect(screen.queryByRole('button', { name: /回看上局/ })).not.toBeInTheDocument();
  });

  it('别的页签写了存档键(storage 事件)→ 列表刷新显示', () => {
    render(<ArchetypeSelect />);
    expect(screen.queryByRole('region', { name: '我的存档' })).not.toBeInTheDocument();
    createSavePointers().write(sum(3));
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'aiuniverse.save.s3' }));
    });
    expect(screen.getByRole('region', { name: '我的存档' })).toHaveTextContent('世界3');
  });
});
