// ADR-044 刀 2 · 有小标题时的四个显示点:存档页条目、移除按钮可访问名、撤销条、大厅「继续」行(格式照待审 3)。
// 没有小标题的形态由 SavesNoSubtitleDom.test 与改动前的 DOM 逐字节对拍。
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useGameStore } from '../../../state/gameStore';
import { createSavePointers, type SaveSummary } from '../../../state/savePointers';
import { useSavesView } from '../../../state/screens';
import { ArchetypeSelect } from '../ArchetypeSelect';
import { removalMessage, saveName, shownSubtitle } from '../saveText';
import { SavesScreen } from './SavesScreen';
import sv from './saves.module.css';

const sum = (i: number, extra: Partial<SaveSummary> = {}): SaveSummary => ({
  saveId: `s${i}`,
  title: '派单',
  archetypes: ['cyberpunk'],
  turn: i,
  ended: false,
  lastPlayedAt: 100 - i,
  availability: 'available',
  ...extra,
});

function seed(...items: SaveSummary[]) {
  const p = createSavePointers();
  for (const it of items) p.write(it);
  useGameStore.getState().refreshSaves();
}

beforeEach(() => {
  localStorage.clear();
  useGameStore.setState({
    archetypes: [
      { archetype: 'cyberpunk', displayName: '赛博朋克', tagline: null, vibeTag: null, experienceMode: 'shenjie', active: true },
    ],
    fusions: [],
    capabilities: { history: false },
    saves: [],
    saveNotice: null,
  });
});
afterEach(() => {
  vi.useRealTimers();
  useSavesView.setState({ open: false, zone: 'fushi', focus: null });
  useGameStore.setState({ saves: [] });
});

describe('文案(saveText)', () => {
  it('「固定名 · 小标题」;没有小标题 = 世界名;世界名为空时一律不带小标题', () => {
    expect(saveName({ title: '派单', subtitle: '九楼的第一单' })).toBe('派单 · 九楼的第一单');
    expect(saveName({ title: '派单' })).toBe('派单');
    expect(saveName({ title: '派单', subtitle: '' })).toBe('派单');
    expect(shownSubtitle({ title: '', subtitle: '九楼的第一单' })).toBeNull();
  });

  it('撤销条:单删写《派单 · 小标题》;并批与全部移除不变', () => {
    expect(removalMessage({ entries: [sum(1, { subtitle: '九楼的第一单' })], kind: 'one' })).toBe(
      '已从这台设备移除《派单 · 九楼的第一单》',
    );
    expect(removalMessage({ entries: [sum(1, { subtitle: 'x' }), sum(2)], kind: 'mixed' })).toBe('已从这台设备移除 2 局存档');
  });
});

describe('存档页', () => {
  function renderPage() {
    useSavesView.setState({ open: true, zone: 'fushi', focus: null });
    return render(<SavesScreen />);
  }

  it('条目:世界名后接「 · 小标题」,小标题单独一个元素;整行单行省略;副标题行不变', () => {
    seed(sum(1, { subtitle: '九楼的第一单' }));
    renderPage();
    const sub = screen.getByTestId('save-subtitle');
    expect(sub.textContent).toBe(' · 九楼的第一单');
    expect(sub.className).toBe(sv.svSubtitle);
    const title = sub.parentElement!;
    expect(title.className).toBe(`${sv.svTitle} ${sv.svTitleOneLine}`);
    expect(title.textContent).toBe('派单 · 九楼的第一单');
    expect(title.nextElementSibling!.textContent).toMatch(/^赛博朋克 · 第 1 回合 · 本设备上次游玩 /);
  });

  it('移除按钮可访问名与撤销条同形:《派单 · 小标题》', () => {
    vi.useFakeTimers();
    seed(sum(1, { subtitle: '九楼的第一单' }), sum(2));
    renderPage();
    const btn = screen.getByRole('button', { name: '从这台设备移除《派单 · 九楼的第一单》' });
    // 没有小标题的那一条照旧。
    expect(screen.getByRole('button', { name: '从这台设备移除《派单》' })).toBeInTheDocument();
    fireEvent.click(btn, { detail: 1 });
    expect(within(screen.getByTestId('removal-toast')).getByRole('status')).toHaveTextContent(
      '已从这台设备移除《派单 · 九楼的第一单》',
    );
  });

  it('世界名为空(迁移指针)即便带了小标题也只显示占位文案', () => {
    seed(sum(1, { title: '', subtitle: '九楼的第一单' }));
    renderPage();
    expect(screen.queryByTestId('save-subtitle')).toBeNull();
    expect(screen.getByRole('button', { name: '从这台设备移除这一局' })).toBeInTheDocument();
  });
});

describe('大厅「继续」行', () => {
  it('有小标题:继续条写「派单 · 小标题」,单独一个元素', () => {
    seed(sum(1, { subtitle: '九楼的第一单' }));
    render(<ArchetypeSelect />);
    const cont = screen.getByRole('button', { name: /^继续/ });
    const sub = within(cont).getByTestId('save-subtitle');
    expect(sub.textContent).toBe(' · 九楼的第一单');
    expect(sub.parentElement!.textContent).toBe('派单 · 九楼的第一单');
    expect(sub.parentElement!.className).toBe(`${sv.svTitle} ${sv.svTitleOneLine}`);
  });

  it('没有小标题:没有那个元素,也没有单行省略类', () => {
    seed(sum(1));
    render(<ArchetypeSelect />);
    const cont = screen.getByRole('button', { name: /^继续/ });
    expect(within(cont).queryByTestId('save-subtitle')).toBeNull();
    expect(within(cont).getByText('派单').className).toBe(sv.svTitle);
  });
});
