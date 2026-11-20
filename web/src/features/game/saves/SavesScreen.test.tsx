// ADR-042 刀 4:存档页(结构、两组、行、「回看上局」、提示行、区令牌、焦点、进出)。
// 直打生产 store;history 探测与历史页在 fetch 层 mock(= api 层之下)。
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useGameStore } from '../../../state/gameStore';
import { useHistoryStore } from '../../../state/historyStore';
import { createSavePointers, type SaveSummary } from '../../../state/savePointers';
import { screens, useSavesView } from '../../../state/screens';
import { GameScreen } from '../GameScreen';
import { UNTITLED_SAVE } from '../saveText';
import { SavesScreen } from './SavesScreen';

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

function mockFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const body = url.includes('/history')
        ? { saveId: 'x', entries: [{ kind: 'event', turn: 0, narrative: '开场', playerAction: null }], nextAfterTurn: null }
        : { archetypes: [], fusions: [], capabilities: { history: true } };
      return { ok: true, status: 200, json: () => Promise.resolve(body) } as unknown as Response;
    }),
  );
}

beforeEach(() => {
  localStorage.clear();
  mockFetch();
  useGameStore.setState({
    archetypes: [
      { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: null, vibeTag: null, experienceMode: 'shenjie', active: true },
    ],
    fusions: [],
    capabilities: { history: false },
    saves: [],
    saveNotice: null,
  });
  useHistoryStore.setState({ visible: {}, viewing: null });
  useSavesView.setState({ open: false, zone: 'fushi', focus: null });
});

afterEach(() => {
  vi.unstubAllGlobals();
  useHistoryStore.getState().close();
  useGameStore.getState().reset();
  useGameStore.setState({ saves: [], saveNotice: null, resumeGame: realResume });
  useSavesView.setState({ open: false, zone: 'fushi', focus: null });
});

/** 直接挂存档页(不经 screens:不压历史,深度 0)。 */
function renderSaves(saves: SaveSummary[], zone: 'fushi' | 'shen' | 'rong' = 'fushi') {
  useGameStore.setState({ saves });
  useSavesView.setState({ open: true, zone, focus: null });
  return render(<SavesScreen />);
}

describe('结构与文案(决策 2 / 4 / 8)', () => {
  it('返回 + 标题 + 说明句;两组各带数量,组内按本设备上次游玩降序', () => {
    renderSaves([sum(1), sum(2, { ended: true }), sum(3), sum(4, { ended: true }), sum(5, { ended: true })]);
    expect(screen.getByRole('button', { name: '返回' })).toHaveTextContent('‹ 返回');
    expect(screen.getByRole('heading', { level: 1, name: '我的存档' })).toBeInTheDocument();
    expect(screen.getByText('移除只是从这台设备上拿掉,移除后在这里就找不回来了。')).toBeInTheDocument();

    const ongoing = screen.getByRole('region', { name: '进行中2' });
    const ended = screen.getByRole('region', { name: '已结局3' });
    expect(within(ongoing).getAllByRole('listitem').map((li) => li.textContent?.slice(0, 3))).toEqual(['世界1', '世界3']);
    expect(within(ended).getAllByRole('listitem').map((li) => li.textContent?.slice(0, 3))).toEqual(['世界2', '世界4', '世界5']);
  });

  it('组空了组头照样显示 0,且没有列表', () => {
    renderSaves([sum(1)]);
    const ended = screen.getByRole('region', { name: '已结局0' });
    expect(within(ended).queryByRole('list')).not.toBeInTheDocument();
  });

  it('暂时无法找回的行:留在原位,显示「暂时无法找回 · 点此重试」;副标题写「本设备上次游玩」', () => {
    renderSaves([sum(1), sum(2, { availability: 'unavailable' }), sum(3)]);
    const rows = within(screen.getByRole('region', { name: '进行中3' })).getAllByRole('button');
    expect(rows.map((b) => b.textContent?.slice(0, 3))).toEqual(['世界1', '世界2', '世界3']);
    expect(rows[1]).toHaveTextContent('暂时无法找回 · 点此重试');
    expect(rows[1]).toHaveTextContent('本设备上次游玩');
    expect(rows[1]).toHaveTextContent('规则怪谈');
    expect(rows[0]).not.toHaveTextContent('暂时无法找回');
  });

  it('世界名为空(迁移来的旧指针)→ 占位文案沿用', () => {
    renderSaves([sum(1, { title: '' })]);
    expect(screen.getByRole('button', { name: new RegExp(UNTITLED_SAVE.replace(/[()]/g, '\\$&')) })).toBeInTheDocument();
  });

  it('根节点挂进来前大厅所在的区(取同一套页面令牌),不挂气氛层', () => {
    const { container } = renderSaves([sum(1)], 'rong');
    const root = container.firstElementChild as HTMLElement;
    expect(root.dataset.zone).toBe('rong');
    expect(root.className).toMatch(/zoneRong/);
    expect(root.querySelector('[data-testid="lobby-ambient"]')).toBeNull();
    expect(root.children).toHaveLength(1); // 只有 <main>
  });

  it('saveNotice(网络 / 5xx 可重试)显示在说明句下面', () => {
    useGameStore.setState({ saveNotice: '存档暂时没能载入,稍后再试' });
    renderSaves([sum(1)]);
    const sub = screen.getByText(/移除只是从这台设备上拿掉/);
    const notice = screen.getByText('存档暂时没能载入,稍后再试');
    expect(sub.compareDocumentPosition(notice) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('别的页签写了存档键(storage 事件)→ 列表刷新', () => {
    renderSaves([]);
    createSavePointers().write(sum(7));
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'aiuniverse.save.s7' }));
    });
    expect(screen.getByRole('region', { name: '进行中1' })).toHaveTextContent('世界7');
  });
});

describe('点开一行(决策 5 / 审定 5)', () => {
  it('续上了 → 关存档页', async () => {
    const resume = vi.fn(async () => {
      useGameStore.setState({ status: 'awaiting' });
    });
    useGameStore.setState({ resumeGame: resume });
    renderSaves([sum(1), sum(2)]);
    await userEvent.click(screen.getByRole('button', { name: /世界2/ }));
    expect(resume).toHaveBeenCalledWith('s2');
    expect(useSavesView.getState().open).toBe(false);
  });

  it('续不上(仍 idle)→ 留在存档页,提示在这一页上', async () => {
    const resume = vi.fn(async () => {
      useGameStore.setState({ status: 'idle', saveNotice: '存档暂时没能载入,稍后再试' });
    });
    useGameStore.setState({ resumeGame: resume });
    renderSaves([sum(1)]);
    await userEvent.click(screen.getByRole('button', { name: /世界1/ }));
    expect(useSavesView.getState().open).toBe(true);
    expect(screen.getByText('存档暂时没能载入,稍后再试')).toBeInTheDocument();
  });
});

describe('「回看上局」(决策 6)', () => {
  it('capabilities.history 为 false → 不探测、没有入口', async () => {
    renderSaves([sum(1)]);
    await act(async () => {});
    expect(screen.queryByRole('button', { name: /回看上局/ })).not.toBeInTheDocument();
    expect(vi.mocked(fetch)).not.toHaveBeenCalled();
  });

  it('探测成功 → 入口在说明句下、进行中组上,小字逐字;只跟随最近一条', async () => {
    useGameStore.setState({ capabilities: { history: true } });
    renderSaves([sum(1), sum(2)]);
    const entry = await screen.findByRole('button', { name: /回看上局/ });
    expect(entry).toHaveTextContent('看看最近这一局已经发生过什么');
    const calls = vi.mocked(fetch).mock.calls.map((c) => String(c[0]));
    expect(calls).toEqual(['/api/game/s1/history']);
    const group = screen.getByRole('region', { name: '进行中2' });
    expect(entry.compareDocumentPosition(group) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('已探过(大厅发起)→ 存档页不重发', async () => {
    useGameStore.setState({ capabilities: { history: true } });
    useHistoryStore.setState({ visible: { s1: true } });
    renderSaves([sum(1)]);
    expect(screen.getByRole('button', { name: /回看上局/ })).toBeInTheDocument();
    await act(async () => {});
    expect(vi.mocked(fetch)).not.toHaveBeenCalled();
  });
});

describe('进出与焦点(决策 5,经真实浏览器历史)', () => {
  it('进存档页 → 焦点在标题;「‹ 返回」经 popstate 回大厅,压的那一条被退掉', async () => {
    useGameStore.setState({ saves: [sum(1)] });
    render(<GameScreen />);
    act(() => screens.openSaves('shen'));
    expect(window.history.state).toEqual({ aiuNav: 'saves' });
    const h1 = screen.getByRole('heading', { level: 1, name: '我的存档' });
    expect(h1).toHaveFocus();
    expect(h1.closest('[data-zone]')).toHaveAttribute('data-zone', 'shen');

    await userEvent.click(screen.getByRole('button', { name: '返回' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: '选择你的世界' })).toBeInTheDocument());
    expect(window.history.state).toBeNull();
    expect(useSavesView.getState().open).toBe(false);
  });

  it('存档页 →「回看上局」→ 历史页「返回我的存档」→ 回存档页,焦点回入口;再返回回大厅', async () => {
    useGameStore.setState({ saves: [sum(1)], capabilities: { history: true } });
    useHistoryStore.setState({ visible: { s1: true } });
    render(<GameScreen />);
    act(() => screens.openSaves('fushi'));

    await userEvent.click(screen.getByRole('button', { name: /回看上局/ }));
    expect(window.history.state).toEqual({ aiuNav: 'history' });
    await screen.findByRole('heading', { name: '这一局的故事' });

    await userEvent.click(screen.getByRole('button', { name: '返回我的存档' }));
    const entry = await screen.findByRole('button', { name: /回看上局/ });
    expect(entry).toHaveFocus();
    expect(window.history.state).toEqual({ aiuNav: 'saves' });
    expect(useSavesView.getState().focus).toBeNull(); // 一次性提示已清

    await userEvent.click(screen.getByRole('button', { name: '返回' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: '选择你的世界' })).toBeInTheDocument());
    expect(window.history.state).toBeNull();
  });

  it('从存档页点开一局 → 进游戏;压的那一条被吃掉,popstate 不再关任何东西', async () => {
    const resume = vi.fn(async () => {
      useGameStore.setState({ status: 'awaiting' });
    });
    useGameStore.setState({ saves: [sum(1)], resumeGame: resume });
    render(<GameScreen />);
    act(() => screens.openSaves('fushi'));
    await userEvent.click(screen.getByRole('button', { name: /世界1/ }));
    await waitFor(() => expect(window.history.state).toBeNull());
    expect(useSavesView.getState().open).toBe(false);
    expect(useGameStore.getState().status).toBe('awaiting');
  });
});
