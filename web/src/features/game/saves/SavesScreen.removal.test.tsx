// ADR-042 刀 5:存档页的移除与撤销(假时钟;真 store、真存储,只看存储里那条键在不在)。
import { StrictMode } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useGameStore } from '../../../state/gameStore';
import { useHistoryStore } from '../../../state/historyStore';
import { lobbyPrefs } from '../../../state/lobbyPrefs';
import { createSavePointers, SAVE_KEY_PREFIX, type SaveSummary } from '../../../state/savePointers';
import { screens, useSavesView } from '../../../state/screens';
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

const stored = (id: string) => localStorage.getItem(SAVE_KEY_PREFIX + id) !== null;
const toast = () => screen.getByTestId('removal-toast');
const status = () => screen.getByRole('status');
const removeBtn = (title: string) => screen.getByRole('button', { name: `从这台设备移除《${title}》` });
const openBtn = (title: string) => screen.getByRole('button', { name: new RegExp(`^${title}`) });
const tick = (ms: number) => act(() => vi.advanceTimersByTime(ms));
/** 指针点击(detail ≥ 1);fireEvent.click 缺省 detail 0 = 键盘 / 读屏。 */
const pointerClick = (el: Element) => fireEvent.click(el, { detail: 1 });
const keyboardClick = (el: Element) => fireEvent.click(el, { detail: 0 });

function seed(...items: SaveSummary[]) {
  const p = createSavePointers();
  for (const it of items) p.write(it);
  useGameStore.getState().refreshSaves();
}

function renderPage(strict = false) {
  useSavesView.setState({ open: true, zone: 'fushi', focus: null });
  return render(strict ? <StrictMode><SavesScreen /></StrictMode> : <SavesScreen />);
}

beforeEach(() => {
  vi.useFakeTimers();
  localStorage.clear();
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
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  useSavesView.setState({ open: false, zone: 'fushi', focus: null });
  useGameStore.setState({ saves: [] });
});

describe('移除 → 5 秒 → 删(决策 3)', () => {
  it('行立刻消失,存储里还在;撤销条说《世界名》;4999ms 还在,5000ms 才删', () => {
    seed(sum(1), sum(2), sum(3));
    renderPage();
    expect(status()).toHaveTextContent(''); // 播报区先于内容存在
    pointerClick(removeBtn('世界2'));
    expect(screen.queryByRole('button', { name: /^世界2/ })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: '进行中2' })).toBeInTheDocument();
    expect(stored('s2')).toBe(true);
    expect(status()).toHaveTextContent('已从这台设备移除《世界2》');
    expect(within(toast()).getByRole('button', { name: '撤销' })).toBeInTheDocument();
    expect(status()).not.toContainElement(within(toast()).getByRole('button', { name: '撤销' })); // 撤销在播报区外

    tick(4999);
    expect(stored('s2')).toBe(true);
    tick(1);
    expect(stored('s2')).toBe(false);
    expect(stored('s1')).toBe(true);
    expect(status()).toHaveTextContent('');
    expect(within(toast()).queryByRole('button', { name: '撤销' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^世界2/ })).not.toBeInTheDocument();
  });

  it('撤销:行按原排序回来,焦点到放回的那一行,存储从头到尾没动', () => {
    seed(sum(1), sum(2), sum(3));
    renderPage();
    pointerClick(removeBtn('世界2'));
    tick(3000);
    pointerClick(within(toast()).getByRole('button', { name: '撤销' }));
    const rows = within(screen.getByRole('region', { name: '进行中3' })).getAllByRole('listitem');
    expect(rows.map((r) => r.textContent?.slice(0, 3))).toEqual(['世界1', '世界2', '世界3']);
    expect(openBtn('世界2')).toHaveFocus();
    expect(status()).toHaveTextContent('');
    tick(60_000);
    expect(stored('s2')).toBe(true);
  });

  it('连续移除并成一批,计时从头;文案「N 局存档」(审定 1)', () => {
    seed(sum(1), sum(2), sum(3));
    renderPage();
    pointerClick(removeBtn('世界1'));
    tick(3000);
    pointerClick(removeBtn('世界3'));
    expect(status()).toHaveTextContent('已从这台设备移除 2 局存档');
    tick(2000);
    expect(stored('s1')).toBe(true);
    tick(3000);
    expect(stored('s1')).toBe(false);
    expect(stored('s3')).toBe(false);
    expect(stored('s2')).toBe(true);
  });

  it('全部移除:已结局组显示着的全部行(含暂时无法找回的)进一批;「N 局已结局的存档」;按钮随组空而消失', () => {
    seed(sum(1), sum(2, { ended: true }), sum(3, { ended: true, availability: 'unavailable' }));
    renderPage();
    pointerClick(screen.getByRole('button', { name: '全部移除' }));
    expect(screen.getByRole('region', { name: '已结局0' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '全部移除' })).not.toBeInTheDocument();
    expect(status()).toHaveTextContent('已从这台设备移除 2 局已结局的存档');
    tick(5000);
    expect([stored('s1'), stored('s2'), stored('s3')]).toEqual([true, false, false]);
  });

  it('进行中组没有「全部移除」;已结局组为空时也没有', () => {
    seed(sum(1));
    renderPage();
    expect(screen.queryByRole('button', { name: '全部移除' })).not.toBeInTheDocument();
  });

  it('世界名为空:撤销条说「这一局」(审定 2),移除按钮的可访问名同样', () => {
    seed(sum(1, { title: '' }));
    renderPage();
    pointerClick(screen.getByRole('button', { name: '从这台设备移除这一局' }));
    expect(status()).toHaveTextContent('已从这台设备移除这一局');
  });

  it('撤销条开着时列表底部让出高度;细线每加一条从头再走(换一个元素)', () => {
    seed(sum(1), sum(2));
    const { container } = renderPage();
    const main = container.querySelector('main')!;
    const plain = main.className;
    pointerClick(removeBtn('世界1'));
    expect(main.className).not.toBe(plain);
    const line1 = toast().querySelector('i');
    expect(line1).not.toBeNull();
    pointerClick(removeBtn('世界2'));
    expect(toast().querySelector('i')).not.toBe(line1);
    tick(5000);
    expect(main.className).toBe(plain);
    expect(toast().querySelector('i')).toBeNull();
  });
});

describe('提前结束一律不删(决策 3)', () => {
  it('切后台(visibilitychange → hidden):行回来、没有撤销条,之后也不删', () => {
    seed(sum(1));
    renderPage();
    pointerClick(removeBtn('世界1'));
    const vis = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    vis.mockRestore();
    expect(openBtn('世界1')).toBeInTheDocument();
    expect(status()).toHaveTextContent('');
    tick(60_000);
    expect(stored('s1')).toBe(true);
  });

  it('页面可见性变成 visible 不算(只有 hidden 才作废)', () => {
    seed(sum(1));
    renderPage();
    pointerClick(removeBtn('世界1'));
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    tick(5000);
    expect(stored('s1')).toBe(false);
  });

  it('pagehide(关页 / 进 bfcache):同样作废', () => {
    seed(sum(1));
    renderPage();
    pointerClick(removeBtn('世界1'));
    act(() => {
      window.dispatchEvent(new Event('pagehide'));
    });
    tick(60_000);
    expect(stored('s1')).toBe(true);
  });

  it('离开存档页(卸载):不删', () => {
    seed(sum(1));
    const { unmount } = renderPage();
    pointerClick(removeBtn('世界1'));
    unmount();
    tick(60_000);
    expect(stored('s1')).toBe(true);
  });

  it('窗口期间别的页签续了这一局(摘要变了):到点比对不等,不删', () => {
    seed(sum(1));
    renderPage();
    pointerClick(removeBtn('世界1'));
    createSavePointers().write(sum(1, { turn: 9, lastPlayedAt: 999 }));
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: SAVE_KEY_PREFIX + 's1' }));
    });
    tick(5000);
    expect(stored('s1')).toBe(true);
    expect(openBtn('世界1')).toHaveTextContent('第 9 回合'); // 窗口一结束它就以新摘要回到画面
  });
});

describe('暂停(决策 3 / 10)', () => {
  it('指针悬停在撤销条上:暂停,细线同步停;离开后按剩余时间继续', () => {
    seed(sum(1));
    renderPage();
    pointerClick(removeBtn('世界1'));
    tick(2000);
    fireEvent.mouseEnter(toast());
    expect(toast().querySelector('i')).toHaveAttribute('data-paused', 'true');
    tick(60_000);
    expect(stored('s1')).toBe(true);
    fireEvent.mouseLeave(toast());
    expect(toast().querySelector('i')).not.toHaveAttribute('data-paused');
    tick(2999);
    expect(stored('s1')).toBe(true);
    tick(1);
    expect(stored('s1')).toBe(false);
  });
});

describe('焦点分流(决策 10 / 审定 8)', () => {
  it('键盘 / 读屏移除 → 焦点到「撤销」,在那里计时停;Tab 走开后剩余时间继续', () => {
    seed(sum(1), sum(2));
    renderPage();
    keyboardClick(removeBtn('世界1'));
    const undo = within(toast()).getByRole('button', { name: '撤销' });
    expect(undo).toHaveFocus();
    tick(60_000);
    expect(stored('s1')).toBe(true);
    act(() => removeBtn('世界2').focus());
    tick(5000);
    expect(stored('s1')).toBe(false);
  });

  it('指针移除 → 焦点不跳到撤销条:同组下一行的「移除」;没有下一行 → 上一行;组空了 → 组头', () => {
    seed(sum(1), sum(2), sum(3));
    renderPage();
    pointerClick(removeBtn('世界2'));
    expect(removeBtn('世界3')).toHaveFocus();
    pointerClick(removeBtn('世界3'));
    expect(removeBtn('世界1')).toHaveFocus();
    pointerClick(removeBtn('世界1'));
    expect(screen.getByRole('heading', { level: 2, name: '进行中0' })).toHaveFocus();
  });

  it('全部移除:键盘 → 撤销;指针 → 已结局组头', () => {
    seed(sum(1, { ended: true }));
    renderPage();
    keyboardClick(screen.getByRole('button', { name: '全部移除' }));
    expect(within(toast()).getByRole('button', { name: '撤销' })).toHaveFocus();
    keyboardClick(within(toast()).getByRole('button', { name: '撤销' }));
    expect(openBtn('世界1')).toHaveFocus();
    pointerClick(screen.getByRole('button', { name: '全部移除' }));
    expect(screen.getByRole('heading', { level: 2, name: '已结局0' })).toHaveFocus();
  });
});

describe('「回看上局」跟随画面上的最近一条(决策 6)', () => {
  it('最近一局在待删批里 → 入口指向下一条;撤销后指回原来那条', () => {
    seed(sum(1), sum(2));
    useGameStore.setState({ capabilities: { history: true } });
    useHistoryStore.setState({ visible: { s1: true, s2: true } });
    const openHistory = vi.spyOn(screens, 'openHistory').mockImplementation(() => {});
    renderPage();
    pointerClick(removeBtn('世界1'));
    fireEvent.click(screen.getByRole('button', { name: /回看上局/ }));
    expect(openHistory).toHaveBeenLastCalledWith('s2');
    pointerClick(within(toast()).getByRole('button', { name: '撤销' }));
    fireEvent.click(screen.getByRole('button', { name: /回看上局/ }));
    expect(openHistory).toHaveBeenLastCalledWith('s1');
  });
});

describe('融合存档(决策 7 / 审定 6)', () => {
  it('到点删一局融合存档之前,先把组合落进「已发现」;撤销了就不落', () => {
    seed(sum(1, { archetypes: ['cultivation', 'rules_creepy'] }), sum(2, { archetypes: ['rules_creepy', 'apocalypse'] }));
    renderPage();
    pointerClick(removeBtn('世界2'));
    pointerClick(within(toast()).getByRole('button', { name: '撤销' }));
    expect(lobbyPrefs.readDiscovered()).toEqual([]);
    pointerClick(removeBtn('世界1'));
    tick(5000);
    expect(stored('s1')).toBe(false);
    expect(lobbyPrefs.readDiscovered()).toEqual(['cultivation×rules_creepy']);
  });
});

describe('StrictMode(决策 3:计时器只在点击里起)', () => {
  it('开发期双挂载下移除一条,到点只提交一次;挂载本身不提交', () => {
    seed(sum(1), sum(2));
    const commit = vi.spyOn(useGameStore.getState(), 'commitSaveRemoval');
    renderPage(true);
    tick(60_000);
    expect(commit).not.toHaveBeenCalled();
    pointerClick(removeBtn('世界1'));
    tick(5000);
    expect(commit).toHaveBeenCalledTimes(1);
    expect(stored('s1')).toBe(false);
    expect(stored('s2')).toBe(true);
  });
});
