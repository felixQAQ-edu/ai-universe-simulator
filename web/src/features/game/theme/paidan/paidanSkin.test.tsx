import { StrictMode, useRef } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AttributeAxisMeta, AxisBand } from '../../../../api';
import type { AvailableAction } from '../../../../types/schema';
import { StatsPanel } from '../../StatsPanel';
import { SkinContext } from '../contract';
import { SkinRuntime, useSkinRuntime } from '../lifecycle';
import { resolveWorldTheme } from '../registry';
import { SKINS } from '../skins';
import { HUD_DWELL_MS, HUD_FADE_MS, HUD_LINE, PaidanAmbient } from './PaidanAmbient';
import { PaidanActions } from './PaidanActions';

// 《派单》皮肤「夜班」(ADR-036 Slice G-2)的测试。口径同 ADR-018 §5 Q8:
//   ① 共享语义(数值 / 中文名 / 档名可读、severity 映射、缺省不崩);② 形态特征冒烟;③ 记忆点触发与收尾。
// 不断言像素 / class 排列 —— 守行为,不守像素。

const skin = SKINS.cyberpunk!;

function withSkin(ui: React.ReactNode) {
  return render(
    <SkinContext.Provider value={{ skin, runtime: new SkinRuntime() }}>{ui}</SkinContext.Provider>,
  );
}

const HP_BANDS: AxisBand[] = [
  { min: 0, max: 20, label: '透支', severity: 'danger' },
  { min: 21, max: 50, label: '发紧', severity: 'caution' },
  { min: 51, max: 100, label: '还撑得住', severity: 'neutral' },
];
const PROFILE_BANDS: AxisBand[] = [
  { min: 0, max: 29, label: '生手', severity: 'neutral' },
  { min: 30, max: 59, label: '熟手', severity: 'caution' },
  { min: 60, max: 100, label: '标杆', severity: 'danger' },
];

describe('登记', () => {
  it('cyberpunk 登记了皮肤,签名轴是 profile,无入场序列', () => {
    expect(resolveWorldTheme('cyberpunk').skin).toBe('cyberpunk');
    expect(skin.id).toBe('cyberpunk');
    expect(skin.signatureAxisKey).toBe('profile');
    expect(skin.hasIntro).toBe(false);
    // 积水反光挂在顶部场景图上(本屏唯一持续环境动效)。
    expect(skin.bannerImgClass).toBeTruthy();
  });
});

describe('PaidanStats(义眼读数)· 共享语义', () => {
  it('数值 / 中文名 / 当前档 label 全部可读,读屏一句读全', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 71 }} />);
    expect(screen.getByText('体力')).toBeInTheDocument();
    expect(screen.getByText('71')).toBeInTheDocument();
    expect(screen.getByText('还撑得住')).toBeInTheDocument();
    expect(screen.getByLabelText('体力 · 71 · 还撑得住 · 正常')).toBeInTheDocument();
  });

  // 裁定补充 1:档名放在数字下方,是数字之后的独立一行,不与数字挤在同一行里竖排。
  it('档名是数字之后的独立元素(不与数字同一行竖排)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 33 }} />);
    const num = screen.getByText('33');
    const band = screen.getByText('发紧');
    expect(num.parentElement).toBe(band.parentElement);
    expect(num.nextElementSibling).toBe(band);
  });

  it('危险态只看 severity:高位画像(越高越糟)显示危险,读屏带「危险」', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'profile', displayName: '画像', bands: PROFILE_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ profile: 63 }} />);
    expect(screen.getByLabelText('画像 · 63 · 标杆 · 危险')).toBeInTheDocument();
  });

  it('无 bands:数字与中文名照常,不显档名、不进危险态', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'cash', displayName: '余钱' }];
    withSkin(<StatsPanel axes={axes} values={{ cash: 48 }} />);
    expect(screen.getByText('48')).toBeInTheDocument();
    expect(screen.getByLabelText('余钱 · 48')).toBeInTheDocument();
  });

  it('四轴一行都渲染(《派单》体力 / 余钱 / 评分 / 画像)', () => {
    const axes: AttributeAxisMeta[] = [
      { key: 'hp', displayName: '体力', bands: HP_BANDS },
      { key: 'cash', displayName: '余钱' },
      { key: 'rating', displayName: '评分' },
      { key: 'profile', displayName: '画像', bands: PROFILE_BANDS },
    ];
    withSkin(<StatsPanel axes={axes} values={{ hp: 33, cash: 48, rating: 66, profile: 63 }} />);
    for (const name of ['体力', '余钱', '评分', '画像']) expect(screen.getByText(name)).toBeInTheDocument();
  });
});

describe('PaidanActions(接单卡)', () => {
  const actions: AvailableAction[] = [
    { id: 'A', text: '一鼓作气爬上去', hint: '腿还没缓透' },
    { id: 'B', text: '按自己节奏爬', hint: '' },
  ];

  it('渲染正文 / hint,点击回传 id;hint 缺失不渲染', () => {
    const onChoose = vi.fn();
    render(<PaidanActions actions={actions} disabled={false} onChoose={onChoose} />);
    expect(screen.getByText('腿还没缓透')).toBeInTheDocument();
    fireEvent.click(screen.getByText('按自己节奏爬'));
    expect(onChoose).toHaveBeenCalledWith('B');
    expect(screen.getAllByRole('button')).toHaveLength(2);
  });

  it('生成中禁用', () => {
    render(<PaidanActions actions={actions} disabled onChoose={() => {}} />);
    screen.getAllByRole('button').forEach((b) => expect(b).toBeDisabled());
  });
});

describe('PaidanAmbient · 一级记忆点「已为你优化时限。」', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function Harness({ tick, turn = 1 }: { tick: number; turn?: number }) {
    const runtime = useSkinRuntime('cyberpunk', turn);
    const root = useRef<HTMLElement | null>(null);
    return (
      <main ref={root}>
        <PaidanAmbient
          runtime={runtime}
          rootRef={root}
          paused={false}
          generating={false}
          turn={turn}
          setRootClass={() => {}}
          onIntroDone={() => {}}
          signatureTick={tick}
        />
      </main>
    );
  }

  const phase = () => screen.getByRole('status').getAttribute('data-phase');

  it('首次装载(tick=0)不浮字 —— init / 续局绝不误触发', () => {
    vi.useFakeTimers();
    render(<Harness tick={0} />);
    act(() => vi.advanceTimersByTime(5000));
    expect(phase()).toBe('idle');
    expect(screen.queryByText(HUD_LINE, { exact: false })).not.toBeInTheDocument();
  });

  it('跨档 → 浮出 → 停留后淡出 → 收起', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness tick={0} />);
    rerender(<Harness tick={1} />);
    expect(phase()).toBe('on');
    expect(screen.getByText(HUD_LINE, { exact: false })).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(HUD_DWELL_MS));
    expect(phase()).toBe('off');
    act(() => vi.advanceTimersByTime(HUD_FADE_MS));
    expect(phase()).toBe('idle');
  });

  // 生产里 delta 一到,turn 与 tick **同一次渲染**一起变:runtime 先因换回合 teardown,浮字随后才排期,不能被吃掉。
  it('换回合与跨档同一次渲染发生(生产的真实时序)→ 仍会浮字', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness tick={0} turn={7} />);
    rerender(<Harness tick={1} turn={8} />);
    expect(phase()).toBe('on');
    act(() => vi.advanceTimersByTime(HUD_DWELL_MS + HUD_FADE_MS));
    expect(phase()).toBe('idle');
  });

  it('浮字在场时玩家换了下一回合 → 立刻收起,不补播', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness tick={0} turn={1} />);
    rerender(<Harness tick={1} turn={1} />);
    expect(phase()).toBe('on');
    rerender(<Harness tick={1} turn={2} />);
    expect(phase()).toBe('idle');
    act(() => vi.advanceTimersByTime(HUD_DWELL_MS + HUD_FADE_MS));
    expect(phase()).toBe('idle');
  });

  it('不堆叠:浮字在场时再跨一次档被忙态锁挡下,收尾后才能再浮', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness tick={0} />);
    rerender(<Harness tick={1} />);
    act(() => vi.advanceTimersByTime(1000));
    rerender(<Harness tick={2} />); // 还在场 → 忽略
    act(() => vi.advanceTimersByTime(HUD_DWELL_MS + HUD_FADE_MS - 1000));
    expect(phase()).toBe('idle'); // 按第一次的时刻收尾,没有被续上
    rerender(<Harness tick={3} />);
    expect(phase()).toBe('on');
  });

  it('StrictMode 双挂载后仍会浮字', () => {
    vi.useFakeTimers();
    const { rerender } = render(
      <StrictMode>
        <Harness tick={0} />
      </StrictMode>,
    );
    rerender(
      <StrictMode>
        <Harness tick={1} />
      </StrictMode>,
    );
    expect(phase()).toBe('on');
  });

  // 浮字是信息不是装饰:reduced-motion 下照常出现与消失(不位移不淡入淡出由 CSS 负责)。
  it('reduced-motion 下照常浮字并按时收起', () => {
    vi.useFakeTimers();
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    const { rerender } = render(<Harness tick={0} />);
    rerender(<Harness tick={1} />);
    expect(phase()).toBe('on');
    act(() => vi.advanceTimersByTime(HUD_DWELL_MS + HUD_FADE_MS));
    expect(phase()).toBe('idle');
  });

  it('非 debug 时不渲染重播按钮', () => {
    render(<Harness tick={0} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
