// 大厅的存档那一块。ADR-034 已决 9 的「我的存档」列表已由 ADR-042 换成一条「继续」+「全部存档(N)」;
// 文件名沿用(ADR-042 决策 12 的基线表按这个名字记「没有条目」那条不变量)。
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CONTINUE_LOST_NOTICE, useContinueNotice } from '../../state/continueNotice';
import { SAVE_RETRY_NOTICE, useGameStore } from '../../state/gameStore';
import { createSavePointers, type SaveSummary } from '../../state/savePointers';
import { screens, useSavesView } from '../../state/screens';
import { ArchetypeSelect } from './ArchetypeSelect';
import { GameScreen } from './GameScreen';

const sum = (i: number, extra: Partial<SaveSummary> = {}): SaveSummary => ({
  saveId: `s${i}`,
  title: `世界${i}`,
  archetypes: ['rules_creepy'],
  turn: i,
  ended: false,
  lastPlayedAt: 100 - i, // i 越小越近
  availability: 'available',
  ...extra,
});

const realResume = useGameStore.getState().resumeGame;

/** 写进存储并刷新(续局失败路径读的是存储,不是内存里的 saves)。 */
function seed(...items: SaveSummary[]) {
  const p = createSavePointers();
  for (const it of items) p.write(it);
  useGameStore.getState().refreshSaves();
}

/** 照真 resumeGame 的 404 分支:离开 idle → 标暂时无法找回 → 回 idle(大厅会卸载再挂上)。 */
const resume404 = vi.fn(async (saveId: string) => {
  useGameStore.setState({ status: 'initializing' });
  await Promise.resolve();
  createSavePointers().patch(saveId, { availability: 'unavailable' });
  useGameStore.getState().refreshSaves();
  useGameStore.setState({ status: 'idle', saveNotice: null });
});

beforeEach(() => {
  localStorage.clear();
  // 目录请求走不到真网络:直接让 loadArchetypes 视为已加载。
  useGameStore.setState({
    archetypes: [
      { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: null, vibeTag: null, experienceMode: 'shenjie', active: true },
    ],
    fusions: [],
    capabilities: { history: false },
    saves: [],
    saveNotice: null,
  });
  useContinueNotice.setState({ text: null });
  useSavesView.setState({ open: false, zone: 'fushi', focus: null });
});
afterEach(() => {
  expect(window.history.state).toBeNull(); // 用例自己退回大厅,不留历史条目
  useGameStore.getState().reset();
  useGameStore.setState({ saves: [], saveNotice: null, resumeGame: realResume });
  resume404.mockClear();
  vi.restoreAllMocks();
});

const contBtn = () => screen.queryByRole('button', { name: /^继续/ });

describe('大厅继续块(ADR-042 决策 4 / 审定 3 / 4)', () => {
  it('没有条目 → 整块不渲染,<main> 子元素只有 header + 分区标签 + 分区面板(ADR-041)', () => {
    const { container } = render(<ArchetypeSelect />);
    expect(contBtn()).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /全部存档/ })).not.toBeInTheDocument();
    const main = container.querySelector('main')!;
    expect(Array.from(main.children).map((c) => c.tagName)).toEqual(['HEADER', 'DIV', 'DIV']);
  });

  it('正对照:有条目 → header 与分区标签之间多一个块', () => {
    seed(sum(1));
    const { container } = render(<ArchetypeSelect />);
    const main = container.querySelector('main')!;
    expect(Array.from(main.children).map((c) => c.tagName)).toEqual(['HEADER', 'DIV', 'DIV', 'DIV']);
  });

  it('继续 = 最近一局没结局、能找回的:跳过已结局与暂时无法找回;副标题写「本设备上次游玩」', async () => {
    seed(sum(1, { ended: true }), sum(2, { availability: 'unavailable' }), sum(3), sum(4));
    const resume = vi.fn(async () => {});
    useGameStore.setState({ resumeGame: resume });
    render(<ArchetypeSelect />);
    const btn = contBtn()!;
    expect(btn).toHaveTextContent('继续');
    expect(btn).toHaveTextContent('世界3');
    expect(btn).toHaveTextContent('规则怪谈');
    expect(btn).toHaveTextContent('本设备上次游玩');
    expect(btn).not.toHaveTextContent('暂时无法找回');
    expect(screen.getByRole('button', { name: '全部存档(4)' })).toBeInTheDocument();
    await userEvent.click(btn);
    expect(resume).toHaveBeenCalledWith('s3');
  });

  it('世界名为空(迁移来的旧指针)→ 继续条沿用占位文案', () => {
    seed(sum(1, { title: '' }));
    render(<ArchetypeSelect />);
    expect(contBtn()).toHaveTextContent('上一局(续上后显示世界名)');
  });

  it('有存档但没有可继续的 → 只剩「全部存档(N)」一行,不加句子(审定 4)', () => {
    seed(sum(1, { ended: true }), sum(2, { availability: 'unavailable' }));
    const { container } = render(<ArchetypeSelect />);
    expect(contBtn()).not.toBeInTheDocument();
    const all = screen.getByRole('button', { name: '全部存档(2)' });
    const block = all.parentElement!;
    expect(block.children).toHaveLength(1);
    expect(container.querySelector('main')!.children[1]).toBe(block);
  });

  it('网络 / 5xx 的可重试提示在块里(继续条下面)', () => {
    seed(sum(1));
    useGameStore.setState({ saveNotice: SAVE_RETRY_NOTICE });
    render(<ArchetypeSelect />);
    const notice = screen.getByText(SAVE_RETRY_NOTICE);
    expect(contBtn()!.compareDocumentPosition(notice) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(notice.parentElement).toBe(contBtn()!.parentElement);
  });

  it('别的页签写了存档键(storage 事件)→ 继续条与 N 跟着刷新', () => {
    render(<ArchetypeSelect />);
    expect(contBtn()).not.toBeInTheDocument();
    createSavePointers().write(sum(3));
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'aiuniverse.save.s3' }));
    });
    expect(contBtn()).toHaveTextContent('世界3');
    expect(screen.getByRole('button', { name: '全部存档(1)' })).toBeInTheDocument();
  });

  it('大厅不再有「回看上局」入口(搬进存档页,ADR-042 决策 6)', () => {
    seed(sum(1));
    useGameStore.setState({ capabilities: { history: true } });
    render(<ArchetypeSelect />);
    expect(screen.queryByRole('button', { name: /回看上局/ })).not.toBeInTheDocument();
  });
});

describe('点了「继续」才发现找不回(审定 3)', () => {
  it('404 → 继续条下说一句,继续条换成下一条;那一局还在全部存档里', async () => {
    seed(sum(1), sum(2));
    useGameStore.setState({ resumeGame: resume404 });
    render(<GameScreen />);
    await userEvent.click(contBtn()!);
    expect(resume404).toHaveBeenCalledWith('s1');
    expect(await screen.findByText(CONTINUE_LOST_NOTICE)).toBeInTheDocument();
    expect(contBtn()).toHaveTextContent('世界2');
    expect(screen.getByRole('button', { name: '全部存档(2)' })).toBeInTheDocument();
  });

  it('网络失败(条目没被标)→ 不说这一句,只有可重试提示', async () => {
    seed(sum(1));
    useGameStore.setState({
      resumeGame: vi.fn(async () => {
        useGameStore.setState({ status: 'initializing' });
        await Promise.resolve();
        useGameStore.setState({ status: 'idle', saveNotice: SAVE_RETRY_NOTICE });
      }),
    });
    render(<GameScreen />);
    await userEvent.click(contBtn()!);
    expect(await screen.findByText(SAVE_RETRY_NOTICE)).toBeInTheDocument();
    expect(screen.queryByText(CONTINUE_LOST_NOTICE)).not.toBeInTheDocument();
  });

  it('这一句活到玩家下一次离开大厅:开局 / 续局离开 idle 即散', async () => {
    seed(sum(1), sum(2));
    useGameStore.setState({ resumeGame: resume404 });
    render(<GameScreen />);
    await userEvent.click(contBtn()!);
    await screen.findByText(CONTINUE_LOST_NOTICE);
    act(() => useGameStore.setState({ status: 'initializing' }));
    act(() => useGameStore.setState({ status: 'idle' }));
    expect(screen.queryByText(CONTINUE_LOST_NOTICE)).not.toBeInTheDocument();
  });
});

describe('「全部存档(N)」(决策 5)', () => {
  it('进存档页取大厅当时显示的区;返回经 popstate 回大厅,焦点回「全部存档(N)」;那一句提示随之散掉', async () => {
    seed(sum(1));
    useContinueNotice.setState({ text: CONTINUE_LOST_NOTICE });
    render(<GameScreen />);
    // 先切到非缺省的区(缺省是浮世,写死 'fushi' 的实现在缺省区上看不出错)
    await userEvent.click(screen.getByRole('tab', { name: '深界' }));
    const zoneShown = screen.getByRole('heading', { name: '选择你的世界' }).closest('[data-zone]')!.getAttribute('data-zone');
    expect(zoneShown).toBe('shen');
    await userEvent.click(screen.getByRole('button', { name: '全部存档(1)' }));
    const h1 = await screen.findByRole('heading', { level: 1, name: '我的存档' });
    expect(h1.closest('[data-zone]')).toHaveAttribute('data-zone', zoneShown!);
    expect(useSavesView.getState().zone).toBe(zoneShown);
    expect(window.history.state).toEqual({ aiuNav: 'saves' });

    await userEvent.click(screen.getByRole('button', { name: '返回' }));
    const all = await screen.findByRole('button', { name: '全部存档(1)' });
    await waitFor(() => expect(all).toHaveFocus());
    expect(window.history.state).toBeNull();
    expect(screen.queryByText(CONTINUE_LOST_NOTICE)).not.toBeInTheDocument();
  });

  it('存档页里把存档全移掉了再回大厅:没有「全部存档」可落,焦点提示照样清掉', async () => {
    seed(sum(1));
    render(<GameScreen />);
    act(() => screens.openSaves('fushi'));
    await screen.findByRole('heading', { level: 1, name: '我的存档' });
    localStorage.clear();
    act(() => useGameStore.getState().refreshSaves());
    await userEvent.click(screen.getByRole('button', { name: '返回' }));
    await screen.findByRole('heading', { name: '选择你的世界' });
    expect(screen.queryByRole('button', { name: /全部存档/ })).not.toBeInTheDocument();
    expect(useSavesView.getState().focus).toBeNull();
  });
});
