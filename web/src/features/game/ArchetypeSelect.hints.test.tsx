// 融合方向提示(ADR-019 实施记录):classifyPair 三态各走哪句文案、出现在哪个时机,
// 以及 aria-live 播报区的去重(只在文本变化时写入)。
//
// 取证边界(同 useFusionDrag.test):jsdom 没有布局,卡片位置逐张替死;
// 淡入时机(150ms)与侧位的视觉效果在 jsdom 里看不到,只钉「挂没挂、是哪句、写了几次」。
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useGameStore } from '../../state/gameStore';
import { ArchetypeSelect } from './ArchetypeSelect';
import styles from './game.module.css';

const ORDER = ['cultivation', 'cthulhu', 'apocalypse'] as const;
const NAMES: Record<(typeof ORDER)[number], string> = {
  cultivation: '修仙',
  cthulhu: '克苏鲁',
  apocalypse: '末日生存',
};
/** 三张卡上下排开:top 0 / 100 / 200,高 92(卡间缝 8)。 */
const topOf = (id: string) => ORDER.indexOf(id as (typeof ORDER)[number]) * 100;

beforeEach(() => {
  localStorage.clear();
  useGameStore.setState({
    archetypes: ORDER.map((a) => ({ archetype: a, displayName: NAMES[a], tagline: null, vibeTag: null, active: true })),
    // 线上现役组合里与这三张有关的一条:《验讫》cultivation×cthulhu(克苏鲁拖到修仙上)。
    fusions: [{ host: 'cultivation', foreign: 'cthulhu', key: 'cultivation×cthulhu' }],
    capabilities: { history: false },
    saves: [],
    saveNotice: null,
  });
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function setup() {
  vi.useFakeTimers();
  // 浅位命中在屏幕上沿的自动滚动带里;jsdom 没实现 scrollBy,替成空操作(滚动不是这里要钉的)。
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  render(<ArchetypeSelect />);
  for (const id of ORDER) {
    const el = screen.getByRole('button', { name: new RegExp(NAMES[id]) });
    const top = topOf(id);
    el.getBoundingClientRect = () =>
      ({ left: 16, top, width: 343, height: 92, right: 359, bottom: top + 92, x: 16, y: top, toJSON: () => ({}) }) as DOMRect;
  }
  // 记录播报区的每一次写入(announce 直接写 textContent;这里包一层计数)。
  const live = screen.getByTestId('fusion-live');
  const writes: string[] = [];
  const desc = Object.getOwnPropertyDescriptor(Node.prototype, 'textContent')!;
  Object.defineProperty(live, 'textContent', {
    configurable: true,
    get() {
      return desc.get!.call(this);
    },
    set(v: string) {
      writes.push(v);
      desc.set!.call(this, v);
    },
  });
  return { live, spoken: () => writes.filter(Boolean) };
}

/** 抓起 from(长按到点)。手指按在卡顶下 40px 处。 */
function pickUp(from: string) {
  const el = screen.getByRole('button', { name: new RegExp(NAMES[from as keyof typeof NAMES]) });
  fireEvent.pointerDown(el, { pointerId: 1, clientX: 100, clientY: topOf(from) + 40 });
  act(() => void vi.advanceTimersByTime(200));
  return (y: number) => fireEvent.pointerMove(window, { pointerId: 1, clientX: 100, clientY: y });
}
/** 让拖动卡的中心落进 target 卡中心。 */
const over = (target: string) => topOf(target) + 40;
/** 卡间缝里(不命中任何卡)。 */
const gapBelow = (card: string) => topOf(card) + 90;
const release = () => fireEvent.pointerUp(window, { pointerId: 1 });

const hints = () => screen.queryAllByTestId('fusion-hint');
const visibleHint = () => hints().find((h) => !h.className.includes(styles.fusionNoteLeaving)) ?? null;

describe('ok:磁吸方向提示(吸附到合法目标时)', () => {
  it('拖「克苏鲁」到「修仙」上 → 「将『克苏鲁』揉入『修仙』。」挂在宿主卡旁;可提交态加强', () => {
    setup();
    const move = pickUp('cthulhu');
    // 拖动卡中心落在目标上沿下 10px:命中但交叠 < 0.62 → 吸附、尚未可提交;
    // 拖动卡在目标中心之上 → 提示放目标**下沿**外(避开拖动卡伸出上沿的标题)。
    const shallow = topOf('cultivation') + 10 - 6;
    move(shallow);
    const h = visibleHint();
    expect(h).not.toBeNull();
    expect(h!.textContent).toBe('将『克苏鲁』揉入『修仙』。');
    expect(h!.dataset.kind).toBe('magnet');
    expect(h!.getAttribute('aria-hidden')).toBe('true'); // 播报只走 aria-live 区,不重复
    expect(h!.className).not.toContain(styles.fusionNoteArmed);

    act(() => void vi.advanceTimersByTime(400)); // 停留过 dwell 300ms
    move(shallow);
    expect(visibleHint()!.className).toContain(styles.fusionNoteArmed);
    expect(visibleHint()).toBe(h); // 同一条提示只加强,不重挂(不重新淡入)
    // 位置在宿主卡沿(jsdom offsetTop/offsetHeight 恒 0,只证写了 top 与侧位)。
    expect(visibleHint()!.style.top).toBe('0px');
    expect(visibleHint()!.dataset.side).toBe('below');
    // 不松在目标上(那会提交揉合),移到缝里松手。
    move(gapBelow('cthulhu'));
    release();
    expect(visibleHint()).toBeNull(); // 离开即淡出(元素留着,带 leaving)
  });
});

describe('reversed:悬停不说,松手才说', () => {
  it('拖「修仙」到「克苏鲁」上:悬停时没有任何提示;松手 → 「反过来试试:把『克苏鲁』拖到『修仙』上。」', () => {
    const { live } = setup();
    const move = pickUp('cultivation');
    move(over('cthulhu'));
    act(() => void vi.advanceTimersByTime(400));
    move(over('cthulhu'));
    expect(visibleHint()).toBeNull();
    expect(live.textContent).toBe('');

    release();
    const h = visibleHint();
    expect(h!.textContent).toBe('反过来试试:把『克苏鲁』拖到『修仙』上。');
    expect(h!.dataset.kind).toBe('reject');
    expect(live.textContent).toBe('反过来试试:把『克苏鲁』拖到『修仙』上。');
    // 拒绝提示已不在页头(页头里只剩那个视觉上隐藏的播报区)。
    expect(screen.getByRole('banner').querySelector('[data-testid="fusion-hint"]')).toBeNull();
    expect(h!.closest('header')).toBeNull();
  });
});

describe('none:组合不存在', () => {
  it('拖「克苏鲁」到「末日生存」上 → 悬停不说;松手 → 「两个世界尚无法彼此容纳。」挂在目标卡旁', () => {
    const { live } = setup();
    const move = pickUp('cthulhu');
    move(over('apocalypse'));
    expect(visibleHint()).toBeNull();
    release();
    expect(visibleHint()!.textContent).toBe('两个世界尚无法彼此容纳。');
    expect(live.textContent).toBe('两个世界尚无法彼此容纳。');
  });

  it('下一次抓起 → 上一句拒绝提示散掉', () => {
    setup();
    const move = pickUp('cthulhu');
    move(over('apocalypse'));
    release();
    expect(visibleHint()).not.toBeNull();
    pickUp('cthulhu');
    expect(visibleHint()).toBeNull();
    release();
  });
});

describe('aria-live 去重:只在文本变化时写入', () => {
  it('同一手势里 离开 → 回到同一目标 → 再离开再回来:只播报 1 次;松手后下一次手势回到同一目标再播 1 次', () => {
    const { spoken } = setup();
    const move = pickUp('cthulhu');
    move(over('cultivation'));
    move(over('cultivation')); // 同一目标上继续移动(逐帧)
    move(gapBelow('cultivation')); // 离开(缝里)
    move(over('cultivation')); // 回来
    move(gapBelow('cultivation'));
    move(over('cultivation'));
    expect(spoken()).toEqual(['将『克苏鲁』揉入『修仙』。']);

    move(gapBelow('cthulhu'));
    release();
    const again = pickUp('cthulhu');
    again(over('cultivation'));
    expect(spoken()).toEqual(['将『克苏鲁』揉入『修仙』。', '将『克苏鲁』揉入『修仙』。']);
    again(gapBelow('cthulhu'));
    release();
  });

  it('悬停在拖反 / 不存在的目标上 → 一次都不写(只有松手那一句)', () => {
    const { spoken } = setup();
    const move = pickUp('cthulhu');
    move(over('apocalypse'));
    move(gapBelow('cthulhu'));
    move(over('apocalypse'));
    expect(spoken()).toEqual([]);
    release();
    expect(spoken()).toEqual(['两个世界尚无法彼此容纳。']);
  });
});
