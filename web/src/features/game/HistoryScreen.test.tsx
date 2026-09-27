import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useGameStore } from '../../state/gameStore';
import { useHistoryStore } from '../../state/historyStore';
import { GameScreen } from './GameScreen';

// ADR-025 刀 3 · 屏级接线:选择屏「回看上局」入口 + 只读历史页。直打生产 store,
// 在 fetch 层 mock(= api 层之下),不依赖真后端。判据逻辑(按事件数 / nextAfterTurn / 缓存)
// 在 historyStore.test 里单独钉,这里只钉接线与文案。

type Reply = { status: number; body: unknown } | 'network';
let historyReplies: Reply[] = [];
/** 每条用例一个新 saveId —— 生产 store 是模块级单例,共用 saveId 会让用例之间互相污染。 */
let seq = 0;
let SID = 's-0';
let historyCalls: string[] = [];

function mockFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.includes('/history')) {
        historyCalls.push(url);
        const r = historyReplies.shift() ?? { status: 503, body: null };
        if (r === 'network') throw new TypeError('offline');
        return {
          ok: r.status >= 200 && r.status < 300,
          status: r.status,
          json: () => Promise.resolve(r.body),
        } as unknown as Response;
      }
      return {
        ok: true,
        status: 200,
        json: () => Promise.resolve({ archetypes: [], fusions: [] }),
      } as unknown as Response;
    }),
  );
}

const ok = (entries: unknown[], nextAfterTurn: number | null = null): Reply => ({
  status: 200,
  body: { saveId: 's-1', entries, nextAfterTurn },
});
const ev = (turn: number, narrative: string, playerAction: string | null = 'B') => ({
  kind: 'event',
  turn,
  narrative,
  playerAction,
});

beforeEach(() => {
  SID = `s-${++seq}`;
  historyReplies = [];
  historyCalls = [];
  mockFetch();
  useHistoryStore.setState({ visible: {}, viewing: null, entries: [], nextAfterTurn: null, more: 'idle', phase: 'loading' });
});

afterEach(() => {
  vi.unstubAllGlobals();
  useHistoryStore.getState().close();
  useGameStore.getState().reset();
  useGameStore.setState({ resumableSaveId: null });
});

const entryName = /回看上局/;

async function settle() {
  // 探测是 fire-and-forget;给在途 promise 链走完的机会。
  await new Promise((r) => setTimeout(r, 0));
  await new Promise((r) => setTimeout(r, 0));
}

describe('选择屏入口(口径 A/B/C/D)', () => {
  it('无上局 saveId → 不探测、无入口', async () => {
    render(<GameScreen />);
    await settle();
    expect(historyCalls).toEqual([]);
    expect(screen.queryByRole('button', { name: entryName })).not.toBeInTheDocument();
  });

  it('探测成功 → 入口出现在「继续上局」下面,是真 button,文案逐字', async () => {
    useGameStore.setState({ resumableSaveId: SID });
    historyReplies = [ok([ev(0, '开场叙事', null)])];
    render(<GameScreen />);
    const entry = await screen.findByRole('button', { name: entryName });
    expect(entry).toHaveTextContent('回看上局');
    expect(entry).toHaveTextContent('看看这一局已经发生过什么');
    const resume = screen.getByRole('button', { name: /继续上局/ });
    // 放在它下面:DOM 顺序 resume → entry。
    expect(resume.compareDocumentPosition(entry) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(historyCalls).toEqual([`/api/game/${SID}/history`]);
  });

  it.each([
    ['501', { status: 501, body: { error: { code: 'history_unavailable' } } } as Reply],
    ['404', { status: 404, body: { error: { code: 'session_not_found' } } } as Reply],
    ['503', { status: 503, body: { error: { code: 'history_read_failed' } } } as Reply],
    ['网络失败', 'network' as Reply],
  ])('探测 %s → 无入口、不报错、「继续上局」仍在', async (_l, reply) => {
    useGameStore.setState({ resumableSaveId: SID });
    historyReplies = [reply];
    render(<GameScreen />);
    await settle();
    expect(historyCalls).toHaveLength(1);
    expect(screen.queryByRole('button', { name: entryName })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /继续上局/ })).toBeInTheDocument();
    expect(screen.queryByText(/HTTP|501|404|503|history|请求失败|错误/)).not.toBeInTheDocument();
  });

  it('重渲染不重复发探测;重新进入选择屏命中缓存不再请求', async () => {
    useGameStore.setState({ resumableSaveId: SID });
    historyReplies = [ok([ev(0, '开场叙事', null)])];
    const { rerender, unmount } = render(<GameScreen />);
    rerender(<GameScreen />);
    await screen.findByRole('button', { name: entryName });
    unmount();
    render(<GameScreen />);
    expect(screen.getByRole('button', { name: entryName })).toBeInTheDocument();
    await settle();
    expect(historyCalls).toHaveLength(1);
  });
});

describe('历史页(口径 E/F/G/H/I)', () => {
  /** 直接进页(不经入口探测):页面行为与入口探测解耦,「进页重新读第一页」由 historyStore.test 单独钉。 */
  async function openPage(pageReply: Reply) {
    historyReplies = [pageReply];
    useHistoryStore.getState().open(SID);
    render(<GameScreen />);
  }

  it('点入口 → 进入历史页(接线)', async () => {
    useGameStore.setState({ resumableSaveId: SID });
    historyReplies = [ok([ev(0, '开场', null)]), ok([ev(0, '开场', null)])];
    render(<GameScreen />);
    await userEvent.click(await screen.findByRole('button', { name: entryName }));
    expect(await screen.findByRole('heading', { name: '这一局的故事' })).toBeInTheDocument();
  });

  it('标题是 heading;开场 / 第 N 回合;不显示玩家动作', async () => {
    await openPage(ok([ev(0, '雨夜开场', null), ev(1, '你推开了门', 'C')]));
    expect(await screen.findByRole('heading', { name: '这一局的故事' })).toBeInTheDocument();
    expect(await screen.findByText('雨夜开场')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '开场' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '第 1 回合' })).toBeInTheDocument();
    expect(screen.queryByText(/选了|^C$/)).not.toBeInTheDocument();
  });

  it('缺口标记可被读屏念出(role=note),只标回合号', async () => {
    await openPage(
      ok([
        { kind: 'gap', reason: 'before_recording', fromTurn: 0, toTurn: 4 },
        ev(5, '第五回合的事'),
        { kind: 'gap', reason: 'write_failed', fromTurn: 6, toTurn: 7 },
        ev(8, '第八回合的事'),
      ]),
    );
    const notes = await screen.findAllByRole('note');
    expect(notes.map((n) => n.textContent)).toEqual(['更早的回合没有留下记录', '第 6–7 回合没有留下记录']);
  });

  it('nextAfterTurn 为 null → 无「加载更多」;有 → 点击取下一页并追加', async () => {
    await openPage(ok([ev(0, '开场', null)], 99));
    historyReplies.push(ok([ev(100, '第一百回合')]));
    await userEvent.click(await screen.findByRole('button', { name: '加载更多' }));
    expect(await screen.findByText('第一百回合')).toBeInTheDocument();
    expect(historyCalls.at(-1)).toBe(`/api/game/${SID}/history?afterTurn=99`);
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument();
  });

  it('首屏失败:「这一局的记录暂时没能载入」+「再试一次」', async () => {
    await openPage({ status: 503, body: { error: { code: 'history_read_failed' } } });
    expect(await screen.findByText('这一局的记录暂时没能载入')).toBeInTheDocument();
    historyReplies.push(ok([ev(0, '重试后的开场', null)]));
    await userEvent.click(screen.getByRole('button', { name: '再试一次' }));
    expect(await screen.findByText('重试后的开场')).toBeInTheDocument();
    expect(screen.queryByText(/HTTP|503|history/)).not.toBeInTheDocument();
  });

  it('翻页失败:文案与首屏失败不同,也有「再试一次」', async () => {
    await openPage(ok([ev(0, '已加载的开场', null)], 99));
    historyReplies.push('network');
    await userEvent.click(await screen.findByRole('button', { name: '加载更多' }));
    expect(await screen.findByText('还有一些回合暂时没能载入')).toBeInTheDocument();
    expect(screen.queryByText('这一局的记录暂时没能载入')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '再试一次' })).toBeInTheDocument();
    expect(screen.queryByText(/network|offline|错误/)).not.toBeInTheDocument();
  });

  it('「返回」回选择屏(复用 BackButton,无皮肤)', async () => {
    await openPage(ok([ev(0, '开场', null)]));
    await screen.findByRole('heading', { name: '这一局的故事' });
    await userEvent.click(screen.getByRole('button', { name: '返回世界选择' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: '选择你的世界' })).toBeInTheDocument());
    expect(useGameStore.getState().status).toBe('idle');
  });
});
