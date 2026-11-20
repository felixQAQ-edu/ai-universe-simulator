// ADR-041 三区大厅:分区标签、切区、按区分卡、成品卡去处(刀 3)。
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ArchetypeSummary } from '../../api';
import type { Archetype } from '../../types/schema';
import { useGameStore } from '../../state/gameStore';
import { LOBBY_DISCOVERED_KEY, LOBBY_ZONE_KEY } from '../../state/lobbyPrefs';
import type { SaveSummary } from '../../state/savePointers';
import { ArchetypeSelect } from './ArchetypeSelect';
import styles from './game.module.css';

const card = (archetype: Archetype, displayName: string, experienceMode: 'fushi' | 'shenjie', active = true): ArchetypeSummary => ({
  archetype,
  displayName,
  tagline: null,
  vibeTag: null,
  experienceMode,
  active,
});

const save = (saveId: string, archetypes: string[]): SaveSummary =>
  ({ saveId, title: saveId, archetypes, turn: 2, ended: false, lastPlayedAt: 1, availability: 'available' }) as SaveSummary;

beforeEach(() => {
  localStorage.clear();
  useGameStore.setState({
    archetypes: [
      card('rules_creepy', '规则怪谈', 'shenjie'),
      card('cthulhu', '克苏鲁', 'shenjie'),
      card('cultivation', '修仙', 'shenjie'),
      card('life_sim', '寻常', 'fushi'),
      card('time_travel', '穿越古代', 'fushi'),
    ],
    fusions: [
      { host: 'cultivation', foreign: 'cthulhu', key: 'cultivation×cthulhu' },
      { host: 'cultivation', foreign: 'rules_creepy', key: 'cultivation×rules_creepy' },
    ],
    capabilities: { history: false },
    saves: [],
    saveNotice: null,
  });
});
afterEach(() => {
  useGameStore.setState({ saves: [] });
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const tab = (name: string) => screen.getByRole('tab', { name });
const panel = () => screen.getByRole('tabpanel');
/** 当前面板里的卡名(融合卡取 aria-label 里的真名)。 */
const cardNames = () =>
  within(panel())
    .queryAllByRole('button')
    .map((b) => b.getAttribute('aria-label')?.replace('(融合世界)', '') ?? b.querySelector('h2')!.textContent!);
const zoneOf = (container: HTMLElement) => container.querySelector('[data-zone]')!.getAttribute('data-zone');

describe('进门落在哪一区(审定 4)', () => {
  it('没有记录 → 浮世;只有浮世卡,浮世卡不可拖', () => {
    const { container } = render(<ArchetypeSelect />);
    expect(zoneOf(container)).toBe('fushi');
    expect(tab('浮世')).toHaveAttribute('aria-selected', 'true');
    expect(cardNames()).toEqual(['寻常', '穿越古代']);
    for (const b of within(panel()).getAllByRole('button')) expect(b).not.toHaveClass(styles.cardDraggable);
    expect(screen.getByText('每一个世界都由 AI 即时生成,烟火寻常,慢慢过活。')).toBeInTheDocument();
  });

  it('系统深色也一样落在浮世(区决定明暗,不看系统偏好)', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: q.includes('dark'), media: q, addEventListener() {}, removeEventListener() {} }) as unknown as MediaQueryList,
    );
    const { container } = render(<ArchetypeSelect />);
    expect(zoneOf(container)).toBe('fushi');
  });

  it('记的是深界 → 深界;记的是渗界但什么都没发现 → 浮世;不认识的值 → 浮世', () => {
    localStorage.setItem(LOBBY_ZONE_KEY, 'shen');
    const a = render(<ArchetypeSelect />);
    expect(zoneOf(a.container)).toBe('shen');
    a.unmount();
    localStorage.setItem(LOBBY_ZONE_KEY, 'rong');
    const b = render(<ArchetypeSelect />);
    expect(zoneOf(b.container)).toBe('fushi');
    b.unmount();
    localStorage.setItem(LOBBY_ZONE_KEY, 'shenjie');
    expect(zoneOf(render(<ArchetypeSelect />).container)).toBe('fushi');
  });
});

describe('标签结构与键盘(决策 3 / 审定 7)', () => {
  it('tablist 里两个标签(渗界未发现时不渲染);面板由选中标签命名;roving tabindex', () => {
    render(<ArchetypeSelect />);
    const list = screen.getByRole('tablist', { name: '分区' });
    expect(within(list).getAllByRole('tab').map((t) => t.textContent)).toEqual(['浮世', '深界']);
    expect(tab('浮世').id).toBe('lobby-tab-fushi');
    expect(tab('深界').id).toBe('lobby-tab-shen');
    expect(panel()).toHaveAttribute('aria-labelledby', 'lobby-tab-fushi');
    expect(tab('浮世')).toHaveAttribute('tabindex', '0');
    expect(tab('深界')).toHaveAttribute('tabindex', '-1');
    expect(tab('浮世')).not.toHaveAttribute('aria-controls');
  });

  it('←/→ 只移焦点、循环,不切区;Home / End 到两端', () => {
    useGameStore.setState({ saves: [save('s', ['cultivation', 'cthulhu'])] });
    render(<ArchetypeSelect />);
    tab('浮世').focus();
    fireEvent.keyDown(tab('浮世'), { key: 'ArrowRight' });
    expect(document.activeElement).toBe(tab('深界'));
    expect(tab('浮世')).toHaveAttribute('aria-selected', 'true'); // 没切
    fireEvent.keyDown(tab('深界'), { key: 'ArrowRight' });
    expect(document.activeElement).toBe(tab('渗界'));
    fireEvent.keyDown(tab('渗界'), { key: 'ArrowRight' });
    expect(document.activeElement).toBe(tab('浮世')); // 循环
    fireEvent.keyDown(tab('浮世'), { key: 'ArrowLeft' });
    expect(document.activeElement).toBe(tab('渗界'));
    fireEvent.keyDown(tab('渗界'), { key: 'Home' });
    expect(document.activeElement).toBe(tab('浮世'));
    fireEvent.keyDown(tab('浮世'), { key: 'End' });
    expect(document.activeElement).toBe(tab('渗界'));
    expect(tab('浮世')).toHaveAttribute('aria-selected', 'true');
  });

  it('其他键不拦(Tab 照常离开标签条)', () => {
    render(<ArchetypeSelect />);
    const ev = fireEvent.keyDown(tab('浮世'), { key: 'Tab' });
    expect(ev).toBe(true); // 没有 preventDefault
  });
});

describe('切区(决策 5)', () => {
  it('激活深界 → 选中态、面板、副标题、暗示句换过去;深界卡可拖;记住这一区', () => {
    const { container } = render(<ArchetypeSelect />);
    fireEvent.click(tab('深界'));
    expect(zoneOf(container)).toBe('shen');
    expect(tab('深界')).toHaveAttribute('aria-selected', 'true');
    expect(tab('深界')).toHaveAttribute('tabindex', '0');
    expect(panel()).toHaveAttribute('aria-labelledby', 'lobby-tab-shen');
    expect(cardNames()).toEqual(['规则怪谈', '克苏鲁', '修仙']);
    for (const b of within(panel()).getAllByRole('button')) expect(b).toHaveClass(styles.cardDraggable);
    expect(screen.getByText('每一个世界都由 AI 即时生成,真假难辨,不可回头。')).toBeInTheDocument();
    expect(screen.getByText('别把两个世界放得太近。')).toBeInTheDocument();
    expect(localStorage.getItem(LOBBY_ZONE_KEY)).toBe('shen');
  });

  it('暗示句只在深界', () => {
    render(<ArchetypeSelect />);
    expect(screen.queryByText('别把两个世界放得太近。')).not.toBeInTheDocument();
  });

  it('换区即整列重挂(卡片入场重播,审定 3)', () => {
    render(<ArchetypeSelect />);
    const before = within(panel()).getAllByRole('button')[0].parentElement!;
    fireEvent.click(tab('深界'));
    fireEvent.click(tab('浮世'));
    const after = within(panel()).getAllByRole('button')[0].parentElement!;
    expect(after).not.toBe(before);
    expect(after).toHaveClass(styles.cardList);
  });

  it('点已选中的标签不写存储', () => {
    render(<ArchetypeSelect />);
    fireEvent.click(tab('浮世'));
    expect(localStorage.getItem(LOBBY_ZONE_KEY)).toBeNull();
  });
});

describe('渗界(决策 2 / 审定 6)', () => {
  it('存档里玩过融合局 → 渗界标签出现;渗界只放已发现的融合卡;还有没发现的 → 底句', () => {
    useGameStore.setState({ saves: [save('s', ['cultivation', 'cthulhu'])] });
    render(<ArchetypeSelect />);
    fireEvent.click(tab('渗界'));
    expect(cardNames()).toEqual(['验讫']);
    expect(screen.getByText('两个世界渗在一起,就不再是原来任何一个。')).toBeInTheDocument();
    expect(screen.getByText('还有别的世界,正在深界里互相渗着。')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '验讫(融合世界)' })).not.toHaveClass(styles.cardFresh);
  });

  it('全都发现了 → 不显示底句', () => {
    localStorage.setItem(LOBBY_DISCOVERED_KEY, JSON.stringify(['cultivation×rules_creepy', 'cultivation×cthulhu']));
    render(<ArchetypeSelect />);
    fireEvent.click(tab('渗界'));
    expect(cardNames()).toEqual(['识海遗蜕', '验讫']); // 发现先后
    expect(screen.queryByText('还有别的世界,正在深界里互相渗着。')).not.toBeInTheDocument();
  });

  it('后端已撤的组合不进渗界;只剩它时渗界标签不出现', () => {
    localStorage.setItem(LOBBY_DISCOVERED_KEY, JSON.stringify(['rules_creepy×apocalypse']));
    render(<ArchetypeSelect />);
    expect(screen.queryByRole('tab', { name: '渗界' })).not.toBeInTheDocument();
  });

  it('点渗界里的卡 → 发有序双值开局', () => {
    const startGame = vi.fn(async () => {});
    useGameStore.setState({ startGame, saves: [save('s', ['cultivation', 'cthulhu'])] });
    render(<ArchetypeSelect />);
    fireEvent.click(tab('渗界'));
    fireEvent.click(screen.getByRole('button', { name: '验讫(融合世界)' }));
    expect(startGame).toHaveBeenCalledWith(['cultivation', 'cthulhu']);
  });
});

describe('成品卡的去处(决策 6)', () => {
  const ORDER = ['rules_creepy', 'cthulhu', 'cultivation'];
  const NAMES: Record<string, string> = { rules_creepy: '规则怪谈', cthulhu: '克苏鲁', cultivation: '修仙' };
  const topOf = (id: string) => ORDER.indexOf(id) * 100;
  const worldCard = (id: string) => screen.getByRole('button', { name: new RegExp(`^${NAMES[id]}`) });

  function fuse(from: string, to: string) {
    for (const id of ORDER) {
      const top = topOf(id);
      worldCard(id).getBoundingClientRect = () =>
        ({ left: 16, top, width: 343, height: 92, right: 359, bottom: top + 92, x: 16, y: top, toJSON: () => ({}) }) as DOMRect;
    }
    fireEvent.pointerDown(worldCard(from), { pointerId: 1, clientX: 100, clientY: topOf(from) + 40 });
    act(() => void vi.advanceTimersByTime(200));
    const y = topOf(to) + 40;
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
    act(() => void vi.advanceTimersByTime(400));
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
    fireEvent.pointerUp(window, { pointerId: 1 });
    act(() => void vi.advanceTimersByTime(400));
  }

  it('合成当下挂在深界末尾;离开深界再回来 → 深界里没有了,只在渗界;已发现写进浏览器', () => {
    vi.useFakeTimers();
    vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
    render(<ArchetypeSelect />);
    fireEvent.click(tab('深界'));
    fuse('cthulhu', 'cultivation');
    expect(cardNames()).toEqual(['规则怪谈', '克苏鲁', '修仙', '验讫']);
    expect(JSON.parse(localStorage.getItem(LOBBY_DISCOVERED_KEY)!)).toEqual(['cultivation×cthulhu']);

    fireEvent.click(tab('浮世'));
    fireEvent.click(tab('深界'));
    expect(cardNames()).toEqual(['规则怪谈', '克苏鲁', '修仙']);
    fireEvent.click(tab('渗界'));
    expect(cardNames()).toEqual(['验讫']);
  });

  it('存储写不进时,这一次停留仍按已发现处理(渗界照样有它)', () => {
    vi.useFakeTimers();
    vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    render(<ArchetypeSelect />);
    fireEvent.click(tab('深界'));
    fuse('cthulhu', 'cultivation');
    fireEvent.click(tab('渗界'));
    expect(cardNames()).toEqual(['验讫']);
  });
});
