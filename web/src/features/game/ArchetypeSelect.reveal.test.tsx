// ADR-041 决策 6:第三区「渗界」第一次出现(大厅的一级记忆点)。
// 900ms 那一刻开演:写 revealed、挂上第三个标签、前两个标签 FLIP 600ms、「渗界」两字从 300ms 起淡入 700ms、
// 播第二句(审定 2)。0–900ms 之间离开 → 没开演 → 不写;下次进门补播一次(含玩过融合局的老玩家,审定 8)。
import { StrictMode } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ArchetypeSummary } from '../../api';
import type { Archetype } from '../../types/schema';
import { useGameStore } from '../../state/gameStore';
import { LOBBY_DISCOVERED_KEY, LOBBY_REVEALED_KEY, LOBBY_ZONE_KEY } from '../../state/lobbyPrefs';
import type { SaveSummary } from '../../state/savePointers';
import { ArchetypeSelect } from './ArchetypeSelect';
import lobbyStyles from './lobby.module.css';

const ORDER = ['rules_creepy', 'cthulhu', 'cultivation'] as const;
const NAMES: Record<string, string> = { rules_creepy: '规则怪谈', cthulhu: '克苏鲁', cultivation: '修仙' };
const summary = (archetype: Archetype, displayName: string, experienceMode: 'fushi' | 'shenjie'): ArchetypeSummary => ({
  archetype,
  displayName,
  tagline: null,
  vibeTag: null,
  experienceMode,
  active: true,
});
const save = (saveId: string, archetypes: string[]): SaveSummary =>
  ({ saveId, title: saveId, archetypes, turn: 2, ended: false, lastPlayedAt: 1, availability: 'available' }) as SaveSummary;

let animate: ReturnType<typeof vi.fn>;

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers();
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  // 标签的横向位置:按标签条里现有几个标签算(居中排布,多一个标签前两个就往左挪)。
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    if (this.getAttribute('role') === 'tab') {
      const tabs = Array.from(this.parentElement!.children);
      const left = 187 - tabs.length * 30 + tabs.indexOf(this) * 60;
      return { left, top: 150, width: 44, height: 44, right: left + 44, bottom: 194, x: left, y: 150, toJSON() {} } as DOMRect;
    }
    const id = ORDER.find((a) => this.textContent?.startsWith(NAMES[a]));
    const top = id ? ORDER.indexOf(id) * 100 : 0;
    return { left: 16, top, width: 343, height: 92, right: 359, bottom: top + 92, x: 16, y: top, toJSON() {} } as DOMRect;
  });
  animate = vi.fn(() => ({ cancel() {} }) as unknown as Animation);
  (HTMLElement.prototype as unknown as { animate: unknown }).animate = animate;
  useGameStore.setState({
    archetypes: [
      ...ORDER.map((a) => summary(a, NAMES[a], 'shenjie')),
      summary('life_sim', '寻常', 'fushi'),
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
  delete (HTMLElement.prototype as unknown as { animate?: unknown }).animate;
  useGameStore.setState({ saves: [] });
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const tab = (name: string) => screen.getByRole('tab', { name });
const rongTab = () => screen.queryByRole('tab', { name: '渗界' });
const live = () => screen.getByTestId('fusion-live').textContent;
const worldCard = (id: string) => screen.getByRole('button', { name: new RegExp(`^${NAMES[id]}`) });
const advance = (ms: number) => act(() => void vi.advanceTimersByTime(ms));

/** 拖 from 到 to 上、停够、松手。返回时刚好在松手那一刻(没有多推时间)。 */
function fuse(from: string, to: string) {
  const topOf = (id: string) => ORDER.indexOf(id as (typeof ORDER)[number]) * 100;
  fireEvent.pointerDown(worldCard(from), { pointerId: 1, clientX: 100, clientY: topOf(from) + 40 });
  advance(200);
  const y = topOf(to) + 40;
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
  advance(400);
  fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
  fireEvent.pointerUp(window, { pointerId: 1 });
}

function setReduced(on: boolean) {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (q: string) =>
      ({ matches: on && q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} }) as unknown as MediaQueryList,
  );
}

describe('第一次合成 → 900ms 后第三区出现', () => {
  it('0–899ms:成品卡挂在深界、有焦点、播第一句;没有渗界标签、没写 revealed', () => {
    localStorage.setItem(LOBBY_ZONE_KEY, 'shen');
    render(<ArchetypeSelect />);
    fuse('cthulhu', 'cultivation');
    const card = screen.getByRole('button', { name: '验讫(融合世界)' });
    expect(document.activeElement).toBe(card);
    expect(live()).toBe('融合成功。《验讫》已加入世界列表。');
    expect(JSON.parse(localStorage.getItem(LOBBY_DISCOVERED_KEY)!)).toEqual(['cultivation×cthulhu']);
    advance(899);
    expect(rongTab()).toBeNull();
    expect(localStorage.getItem(LOBBY_REVEALED_KEY)).toBeNull();
  });

  it('900ms:标签挂上、写 revealed、播第二句;焦点仍在成品卡、页面仍在深界', () => {
    localStorage.setItem(LOBBY_ZONE_KEY, 'shen');
    const { container } = render(<ArchetypeSelect />);
    fuse('cthulhu', 'cultivation');
    advance(900);
    expect(rongTab()).not.toBeNull();
    expect(rongTab()).toHaveAttribute('aria-selected', 'false');
    expect(localStorage.getItem(LOBBY_REVEALED_KEY)).toBe('true');
    expect(live()).toBe('出现了新的分区:渗界。');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '验讫(融合世界)' }));
    expect(container.querySelector('[data-zone]')).toHaveAttribute('data-zone', 'shen');
  });

  it('前两个标签 FLIP:从旧位置平移回新位置,600ms ease-out,只动 transform;「渗界」两字挂淡入类', () => {
    localStorage.setItem(LOBBY_ZONE_KEY, 'shen');
    render(<ArchetypeSelect />);
    fuse('cthulhu', 'cultivation');
    advance(900);
    expect(animate).toHaveBeenCalledTimes(2);
    for (const [keyframes, opts] of animate.mock.calls) {
      // 两个标签时 left = 127 / 187;三个标签时 = 97 / 157 → 两者都从 +30px 回到 0。
      expect(keyframes).toEqual([{ transform: 'translateX(30px)' }, { transform: 'none' }]);
      expect(opts).toEqual({ duration: 600, easing: 'ease-out' });
    }
    expect(animate.mock.contexts).toEqual([tab('浮世'), tab('深界')]);
    expect(rongTab()!.querySelector('span')).toHaveClass(lobbyStyles.tabRevealing);
    expect(tab('浮世').querySelector('span')).not.toHaveClass(lobbyStyles.tabRevealing);
  });

  it('0–900ms 之间切区:照常在 900ms 播(在当时所在的区)', () => {
    localStorage.setItem(LOBBY_ZONE_KEY, 'shen');
    const { container } = render(<ArchetypeSelect />);
    fuse('cthulhu', 'cultivation');
    advance(300);
    fireEvent.click(tab('浮世'));
    advance(599);
    expect(rongTab()).toBeNull();
    advance(1);
    expect(rongTab()).not.toBeNull();
    expect(container.querySelector('[data-zone]')).toHaveAttribute('data-zone', 'fushi');
    expect(live()).toBe('出现了新的分区:渗界。');
  });

  it('已经开演过:再合成一个新组合 → 只有成品卡与第一句,没有第二次出现', () => {
    localStorage.setItem(LOBBY_ZONE_KEY, 'shen');
    localStorage.setItem(LOBBY_DISCOVERED_KEY, JSON.stringify(['cultivation×cthulhu']));
    localStorage.setItem(LOBBY_REVEALED_KEY, 'true');
    render(<ArchetypeSelect />);
    expect(rongTab()).not.toBeNull(); // 进门就在,不等
    fuse('rules_creepy', 'cultivation');
    advance(2000);
    expect(live()).toBe('融合成功。《识海遗蜕》已加入世界列表。');
    expect(animate).not.toHaveBeenCalled();
    expect(rongTab()!.querySelector('span')).not.toHaveClass(lobbyStyles.tabRevealing);
  });

  it('同一组合再合成一次(已发现、已开演):只有成品卡,没有第三区出现', () => {
    localStorage.setItem(LOBBY_ZONE_KEY, 'shen');
    render(<ArchetypeSelect />);
    fuse('cthulhu', 'cultivation');
    advance(2000);
    animate.mockClear();
    fuse('cthulhu', 'cultivation');
    advance(2000);
    expect(animate).not.toHaveBeenCalled();
    expect(live()).toBe('融合成功。《验讫》已加入世界列表。');
  });
});

describe('一辈子只一次:记「已开演」,不记「已排期」(ADR-018 §4.9)', () => {
  it('900ms 之前离开大厅 → 不写 revealed;下次进门:卡片挂好后停 900ms 补播一次,在当时所在的区', () => {
    localStorage.setItem(LOBBY_ZONE_KEY, 'shen');
    const first = render(<ArchetypeSelect />);
    fuse('cthulhu', 'cultivation');
    advance(500);
    first.unmount();
    advance(2000);
    expect(localStorage.getItem(LOBBY_REVEALED_KEY)).toBeNull();

    const { container } = render(<ArchetypeSelect />);
    expect(container.querySelector('[data-zone]')).toHaveAttribute('data-zone', 'shen');
    expect(rongTab()).toBeNull();
    advance(899);
    expect(rongTab()).toBeNull();
    advance(1);
    expect(rongTab()).not.toBeNull();
    expect(localStorage.getItem(LOBBY_REVEALED_KEY)).toBe('true');
    expect(live()).toBe('出现了新的分区:渗界。');
  });

  it('玩过融合局的老玩家第一次进新大厅:落在浮世,900ms 后补播一次(审定 8)', () => {
    useGameStore.setState({ saves: [save('s', ['cultivation', 'cthulhu'])] });
    const { container } = render(<ArchetypeSelect />);
    expect(container.querySelector('[data-zone]')).toHaveAttribute('data-zone', 'fushi');
    expect(rongTab()).toBeNull();
    advance(900);
    expect(rongTab()).not.toBeNull();
    expect(localStorage.getItem(LOBBY_REVEALED_KEY)).toBe('true');
    expect(animate).toHaveBeenCalledTimes(2);
  });

  it('补播过之后再进门:标签直接在,不再演', () => {
    useGameStore.setState({ saves: [save('s', ['cultivation', 'cthulhu'])] });
    const first = render(<ArchetypeSelect />);
    advance(900);
    first.unmount();
    animate.mockClear();
    render(<ArchetypeSelect />);
    expect(rongTab()).not.toBeNull();
    advance(2000);
    expect(animate).not.toHaveBeenCalled();
    expect(live()).toBe('');
  });

  it('StrictMode(dev 的双挂载):只开演一次、只写一次、只播一次', () => {
    const setItem = vi.spyOn(localStorage, 'setItem');
    useGameStore.setState({ saves: [save('s', ['cultivation', 'cthulhu'])] });
    render(
      <StrictMode>
        <ArchetypeSelect />
      </StrictMode>,
    );
    advance(2000);
    expect(setItem.mock.calls.filter(([k]) => k === LOBBY_REVEALED_KEY)).toHaveLength(1);
    expect(screen.getAllByRole('tab', { name: '渗界' })).toHaveLength(1);
    expect(live()).toBe('出现了新的分区:渗界。');
  });

  it('写不进 revealed(存储不可用):这一次照样演完、标签留着;下次进门会再看到一次(已知代价 3)', () => {
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    useGameStore.setState({ saves: [save('s', ['cultivation', 'cthulhu'])] });
    render(<ArchetypeSelect />);
    advance(900);
    expect(rongTab()).not.toBeNull();
    advance(5000);
    expect(rongTab()).not.toBeNull();
  });
});

describe('reduced-motion', () => {
  it('没有 900ms 停顿、没有平移、没有淡入:标签与成品卡同时出现;两句并成一次播报', () => {
    setReduced(true);
    localStorage.setItem(LOBBY_ZONE_KEY, 'shen');
    render(<ArchetypeSelect />);
    fuse('cthulhu', 'cultivation');
    expect(rongTab()).not.toBeNull();
    expect(localStorage.getItem(LOBBY_REVEALED_KEY)).toBe('true');
    expect(live()).toBe('融合成功。《验讫》已加入世界列表。出现了新的分区:渗界。');
    expect(animate).not.toHaveBeenCalled();
    expect(rongTab()!.querySelector('span')).not.toHaveClass(lobbyStyles.tabRevealing);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '验讫(融合世界)' }));
  });

  it('进门补播:立即出现,只播第二句', () => {
    setReduced(true);
    useGameStore.setState({ saves: [save('s', ['cultivation', 'cthulhu'])] });
    render(<ArchetypeSelect />);
    expect(rongTab()).not.toBeNull();
    expect(live()).toBe('出现了新的分区:渗界。');
  });
});

describe('跨页签', () => {
  it('别的页签发现了组合:本页签直接显示渗界标签,不演、不播、不写 revealed', () => {
    render(<ArchetypeSelect />);
    expect(rongTab()).toBeNull();
    localStorage.setItem(LOBBY_DISCOVERED_KEY, JSON.stringify(['cultivation×cthulhu']));
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: LOBBY_DISCOVERED_KEY }));
    });
    expect(rongTab()).not.toBeNull();
    advance(2000);
    expect(animate).not.toHaveBeenCalled();
    expect(live()).toBe('');
    expect(localStorage.getItem(LOBBY_REVEALED_KEY)).toBeNull();
  });
});

describe('计时器清理', () => {
  it('等待中卸载:计时器清掉,之后不再写存储、不报 setState 警告', () => {
    const err = vi.spyOn(console, 'error');
    useGameStore.setState({ saves: [save('s', ['cultivation', 'cthulhu'])] });
    const r = render(<ArchetypeSelect />);
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    r.unmount();
    expect(vi.getTimerCount()).toBe(0);
    advance(2000);
    expect(localStorage.getItem(LOBBY_REVEALED_KEY)).toBeNull();
    expect(err).not.toHaveBeenCalled();
  });
});
